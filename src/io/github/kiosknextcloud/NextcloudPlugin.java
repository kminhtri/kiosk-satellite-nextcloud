// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/** SDK callbacks never wait for network or image decoding. Each configuration owns a cancelable worker. */
public final class NextcloudPlugin implements KioskPlugin {
    private final Object lock = new Object();
    private final ImageEncoder encoder;
    private final java.util.function.LongSupplier clock;
    private PluginHost host;
    private Session session;
    private boolean alive;
    private volatile boolean active, screenOn = true;
    private int saverRevision, screenRevision;

    public NextcloudPlugin() { this(new AndroidImageEncoder(), () -> System.nanoTime() / 1_000_000); }
    NextcloudPlugin(ImageEncoder encoder, java.util.function.LongSupplier clock) { this.encoder = encoder; this.clock = clock; }

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        synchronized (lock) { this.host = host; alive = true; }
        host.subscribe("screensaver.state");
        host.subscribe("screen.state");
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
                host.publishScreensaver("photos", "Nextcloud Photos", Renderer.message("Loading Nextcloud photos…"));
                host.status("Connecting to Nextcloud…", false);
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
                if (active && session != null) session.resetDwell = true;
            } else if (event.equals("ks.screen.state")) {
                screenRevision++;
                screenOn = Boolean.TRUE.equals(payload.get("on"));
            }
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
        long refreshAt, nextAt, networkRetryAt;

        Session(Config config) {
            this.config = config;
            dav = new DavClient(config);
            worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "NextcloudPhotos");
                thread.setDaemon(true);
                return thread;
            });
        }

        void start() { worker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS); }
        long now() { return clock.getAsLong(); }

        void tick() {
            if (closed) return;
            try {
                long now = now();
                if (refresh || now >= refreshAt) {
                    refresh = false;
                    try {
                        List<DavClient.Photo> listed = dav.list();
                        if (closed) return;
                        photos = listed;
                        Set<String> valid = new HashSet<>();
                        for (DavClient.Photo photo : photos) valid.add(photo.key());
                        cache.keySet().retainAll(valid);
                        cycle.clear(); cursor = 0;
                        refreshAt = now() + config.refreshMinutes * 60_000L;
                        networkRetryAt = 0;
                        report(photos.isEmpty() ? "No JPEG, PNG or WebP photos found in this folder." : photos.size() + " photos available.", photos.isEmpty());
                        if (photos.isEmpty()) {
                            current = null;
                            publish(Renderer.message("No JPEG, PNG or WebP photos found in this folder."));
                        }
                    } catch (IOException error) {
                        refreshAt = now() + 30_000;
                        report(connectionError(error), true);
                        if (current == null) publish(Renderer.message(connectionError(error)));
                    }
                }
                if (closed || photos.isEmpty()) return;
                if (resetDwell) { resetDwell = false; nextAt = now() + config.seconds * 1000L; }
                boolean forced = advance;
                advance = false;
                if (current == null || forced || (active && screenOn && now() >= nextAt)) {
                    DavClient.Photo candidate = next();
                    byte[] image = cachedOrDownload(candidate);
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
                // Prefetch one upcoming photo; no continuous slideshow downloads while idle or screen-off.
                if (!closed && current != null && (active && screenOn || cache.size() < 2)) {
                    ensureCycle();
                    cachedOrDownload(cycle.get(cursor));
                }
            } catch (RuntimeException error) {
                // Do not expose raw exception strings, which may contain private URLs or credentials.
                report("Photo processing failed. Try another photo or a smaller folder.", true);
                nextAt = now() + 30_000;
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

        private byte[] cachedOrDownload(DavClient.Photo photo) {
            byte[] image = cache.get(photo.key());
            if (image != null || closed || now() < networkRetryAt) return image;
            try {
                image = encoder.encode(dav.download(photo));
                if (closed) return null;
                cache.put(photo.key(), image);
                while (cache.size() > config.cachePhotos) cache.remove(cache.keySet().iterator().next());
                report(photos.size() + " photos available; " + cache.size() + " cached.", false);
                return image;
            } catch (IOException error) {
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
            worker.shutdownNow();
            dav.close();
        }
    }
}
