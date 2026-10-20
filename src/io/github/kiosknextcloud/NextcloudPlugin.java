// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** SDK callbacks never wait for network or image decoding. Each configuration owns a cancelable worker. */
public final class NextcloudPlugin implements KioskPlugin {
    static final String VIEW = "plugin:nextcloud-photos:photos";
    private static final String MEMORY_WARNING = "Not enough memory for this photo. Cache cleared; retrying in 30 seconds.";
    private final Object lock = new Object();
    private final ImageEncoder encoder;
    private final java.util.function.LongSupplier clock;
    private PluginHost host;
    private Session session;
    private boolean alive;
    private volatile boolean active, screenOn = true;
    private volatile String view;
    private int saverRevision, screenRevision;

    public NextcloudPlugin() { this(new AndroidImageEncoder(), () -> System.nanoTime() / 1_000_000); }
    NextcloudPlugin(ImageEncoder encoder, java.util.function.LongSupplier clock) { this.encoder = encoder; this.clock = clock; }

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        synchronized (lock) {
            this.host = host; alive = true;
            active = false; screenOn = false; view = null;
            saverRevision++; screenRevision++;
        }
        host.subscribe("screensaver.state");
        host.subscribe("screen.state");
        host.subscribe("screensaver.view");
        initialState("isScreensaverActive", false);
        initialState("isScreenOn", true);
        configure(settings);
    }

    private void initialState(String command, boolean screen) {
        final int revision = screen ? screenRevision : saverRevision;
        host.executeCommand(command, Collections.emptyMap(), (ok, data, error) -> {
            synchronized (lock) {
                if (!alive || !ok || revision != (screen ? screenRevision : saverRevision)) return;
                if (screen) screenOn = Boolean.TRUE.equals(data);
                else active = Boolean.TRUE.equals(data);
                if (session != null) session.updateActivity();
            }
        });
    }

    @Override public void configure(Map<String, Object> settings) {
        synchronized (lock) {
            if (!alive) return;
            if (session != null) { session.close(); session = null; }
            try {
                Config config = new Config(settings);
                session = new Session(config);
                host.publishScreensaver("photos", "Nextcloud Photos", Renderer.message("Waiting for Nextcloud Photos to start…"));
                host.status("Ready. Photos load when this screensaver is visible.", false);
                session.start();
            } catch (IllegalArgumentException error) {
                host.publishScreensaver("photos", "Nextcloud Photos", Renderer.message(error.getMessage()));
                host.status(error.getMessage(), true);
            }
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        synchronized (lock) {
            if (session == null) return;
            if (command.equals("refresh")) session.refresh = true;
            else if (command.equals("next")) session.advance = true;
            else throw new IllegalArgumentException("Unknown Nextcloud Photos action");
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        synchronized (lock) {
            if (event.equals("ks.screensaver.state")) {
                saverRevision++;
                active = Boolean.TRUE.equals(payload.get("active"));
            } else if (event.equals("ks.screen.state")) {
                screenRevision++;
                screenOn = Boolean.TRUE.equals(payload.get("on"));
            } else if (event.equals("ks.screensaver.view")) {
                view = payload.get("view") instanceof String ? (String) payload.get("view") : null;
            }
            if (session != null) session.updateActivity();
        }
    }

    @Override public void stop() {
        synchronized (lock) {
            alive = false;
            if (session != null) session.close();
            session = null;
            // The host has already revoked this session and removes its renderer itself.
        }
    }

    private final class Session implements AutoCloseable {
        final Config config;
        final DavClient dav;
        final ScheduledExecutorService worker;
        final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, .75f, true);
        final Random random = new Random();
        List<DavClient.Photo> photos = new ArrayList<>();
        List<DavClient.Photo> cycle = new ArrayList<>();
        int cursor;
        String current;
        volatile boolean refresh = true, advance, resetDwell;
        volatile boolean closed;
        volatile boolean running;
        volatile long activityRevision;
        private ScheduledFuture<?> loop;
        long refreshAt, nextAt, networkRetryAt, processingRetryAt;

        Session(Config config) {
            this.config = config;
            dav = new DavClient(config);
            dav.setActive(false);
            worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "NextcloudPhotos");
                thread.setDaemon(true);
                return thread;
            });
        }

        void start() { updateActivity(); }

        // Called only under the plugin lock by lifecycle callbacks. No timer or
        // HTTP work is scheduled until this particular renderer is visible.
        void updateActivity() {
            boolean visible = !closed && active && screenOn && VIEW.equals(view);
            if (running == visible) return;
            if (visible) {
                dav.setActive(true);
                activityRevision++;
                resetDwell = true;
                running = true;
                loop = worker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
            } else {
                running = false;
                activityRevision++;
                dav.setActive(false);
                if (loop != null) loop.cancel(false);
                loop = null;
            }
        }

        boolean working(long revision) { return !closed && running && activityRevision == revision; }
        long now() { return clock.getAsLong(); }

        void tick() {
            long revision = activityRevision;
            if (!working(revision) || now() < processingRetryAt) return;
            try {
                long now = now();
                if (refresh || now >= refreshAt) {
                    refresh = false;
                    try {
                        List<DavClient.Photo> listed = dav.list();
                        if (!working(revision)) { refresh = true; return; }
                        photos = listed;
                        Set<String> valid = new HashSet<>();
                        for (DavClient.Photo photo : photos) valid.add(photo.key());
                        cache.keySet().retainAll(valid);
                        cycle.clear(); cursor = 0;
                        refreshAt = now() + config.refreshMinutes * 60_000L;
                        networkRetryAt = 0;
                        report(photos.isEmpty() ? "No JPEG, PNG or WebP photos found in the selected folders." : photos.size() + " photos available.", photos.isEmpty());
                        if (photos.isEmpty()) {
                            current = null;
                            publish(Renderer.message("No JPEG, PNG or WebP photos found in the selected folders."));
                        }
                    } catch (IOException error) {
                        if (!working(revision)) { refresh = true; return; }
                        refreshAt = now() + 30_000;
                        report(connectionError(error), true);
                        if (current == null) publish(Renderer.message(connectionError(error)));
                    }
                }
                if (!working(revision) || photos.isEmpty()) return;
                if (resetDwell) { resetDwell = false; nextAt = now() + config.seconds * 1000L; }
                boolean forced = advance;
                advance = false;
                if (current == null || forced || (active && screenOn && now() >= nextAt)) {
                    DavClient.Photo candidate = next();
                    byte[] image = cachedOrDownload(candidate, revision);
                    if (!working(revision)) return;
                    if (image == null) {
                        // Continue rotating the bounded cache while the server is unavailable.
                        for (int i = 0; i < photos.size(); i++) {
                            candidate = next();
                            image = cache.get(candidate.key());
                            if (image != null && !candidate.key().equals(current)) break;
                        }
                    }
                    if (image != null) {
                        publish(Renderer.photo(image, config.fill));
                        current = candidate.key();
                    }
                    nextAt = now() + (image == null ? 30_000 : config.seconds * 1000L);
                }
                if (working(revision) && current != null) {
                    ensureCycle();
                    cachedOrDownload(cycle.get(cursor), revision);
                }
            } catch (OutOfMemoryError error) {
                // Release the largest retained buffers before calling the host.
                // Do not let a second OOM in status reporting kill the timer.
                cache.clear();
                processingRetryAt = now() + 30_000;
                nextAt = processingRetryAt;
                if (photos.isEmpty()) refresh = true;
                try { if (working(revision)) report(MEMORY_WARNING, true); }
                catch (OutOfMemoryError ignored) { /* the next scheduled tick may recover */ }
            } catch (RuntimeException error) {
                // Do not expose raw exception strings, which may contain private URLs or credentials.
                processingRetryAt = now() + 30_000;
                nextAt = processingRetryAt;
                try { if (working(revision)) report("Photo processing failed. Try another photo or a smaller folder.", true); }
                catch (OutOfMemoryError ignored) { cache.clear(); }
            }
        }

        private void ensureCycle() {
            if (cursor < cycle.size()) return;
            cycle = new ArrayList<>(photos);
            if (config.shuffle) Collections.shuffle(cycle, random);
            cursor = 0;
            if (cycle.size() > 1 && cycle.get(0).key().equals(current)) Collections.swap(cycle, 0, 1);
        }

        private DavClient.Photo next() { ensureCycle(); return cycle.get(cursor++); }

        private byte[] cachedOrDownload(DavClient.Photo photo, long revision) {
            if (!working(revision)) return null;
            byte[] image = cache.get(photo.key());
            if (image != null || closed || now() < networkRetryAt) return image;
            try {
                image = encoder.encode(dav.download(photo));
                if (!working(revision)) return null;
                cache.put(photo.key(), image);
                while (cache.size() > config.cachePhotos) cache.remove(cache.keySet().iterator().next());
                report(photos.size() + " photos available; " + cache.size() + " cached.", false);
                return image;
            } catch (IOException error) {
                if (!working(revision)) return null;
                networkRetryAt = now() + 30_000;
                report(connectionError(error), true);
                if (current == null) publish(Renderer.message(connectionError(error)));
                return null;
            }
        }

        private String connectionError(IOException error) {
            String message = error.getMessage();
            // Only messages produced by our client are suitable for the settings page.
            return message != null && (message.startsWith("Nextcloud ") || message.startsWith("Photo limit")
                    || message.startsWith("Folder limit") || message.startsWith("Unsupported or damaged"))
                    ? message : "Cannot load Nextcloud photos. Check the connection and HTTPS certificate. Retrying in 30 seconds.";
        }

        private void publish(String html) {
            synchronized (lock) {
                if (alive && session == this && !closed) {
                    try { host.publishScreensaver("photos", "Nextcloud Photos", html); }
                    catch (IllegalStateException ignored) { /* host may revoke before calling stop */ }
                }
            }
        }

        private void report(String message, boolean error) {
            synchronized (lock) {
                if (alive && session == this && !closed) {
                    try { host.status(message, error); }
                    catch (IllegalStateException ignored) { /* revoked session */ }
                }
            }
        }

        @Override public void close() {
            closed = true;
            running = false;
            activityRevision++;
            worker.shutdownNow();
            dav.close();
        }
    }
}
