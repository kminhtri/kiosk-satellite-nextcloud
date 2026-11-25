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
    private static final String TITLE = "Nextcloud Photo Frame";
    private static final String MEMORY_WARNING = "Not enough memory for this photo. Cache cleared; retrying in 30 seconds.";
    private final Object lock = new Object();
    private final ImageEncoder encoder;
    private final java.util.function.LongSupplier clock;
    private PluginHost host;
    private Session session;
    private NightMode night;
    private String folderMigrationWarning = "";
    private String shuffleMigrationWarning = "";
    private boolean alive;
    private volatile boolean active, screenOn = true;
    private volatile String view;
    private int saverRevision, screenRevision;

    public NextcloudPlugin() { this(new AndroidImageEncoder(), () -> System.nanoTime() / 1_000_000); }
    NextcloudPlugin(ImageEncoder encoder, java.util.function.LongSupplier clock) { this.encoder = encoder; this.clock = clock; }

    @Override public void start(PluginHost host, Map<String, Object> settings) {
        synchronized (lock) {
            this.host = host; alive = true;
            if (night != null) night.close();
            night = new NightMode(lock, host, new NightMode.Display() {
                @Override public boolean selected() { return alive && active && VIEW.equals(view); }
                @Override public boolean screenOn() { return screenOn; }
            }, clock);
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
                if (night != null) night.updateActivity();
            }
        });
    }

    @Override public void configure(Map<String, Object> settings) {
        synchronized (lock) {
            if (!alive) return;
            folderMigrationWarning = "";
            shuffleMigrationWarning = "";
            try {
                Map<String, Object> patch = FolderSettings.migration(settings);
                if (!patch.isEmpty()) {
                    try {
                        host.updateSettings(patch);
                        settings = new HashMap<>(settings);
                        settings.putAll(patch);
                    } catch (UnsupportedOperationException | IllegalStateException | LinkageError error) {
                        folderMigrationWarning = "Existing folders remain active. Update Kiosk Satellite to import them into the unified folder list.";
                    }
                }
            } catch (IllegalArgumentException error) {
                // Album-only configurations must keep ignoring inactive legacy folder values.
                if (!"Albums".equals(Config.text(settings, "photoSource", "Folders")))
                    folderMigrationWarning = "Could not import the old folder selection. Check the Photo folders list before using folders.";
            }
            if (settings.containsKey("shuffleMode")) {
                Map<String, Object> patch = ShuffleMode.migration(settings);
                if (!patch.isEmpty()) {
                    try { host.updateSettings(patch); }
                    catch (UnsupportedOperationException | IllegalStateException | LinkageError error) {
                        shuffleMigrationWarning = "Update Kiosk Satellite to save the migrated Shuffle mode.";
                    }
                    settings = new HashMap<>(settings); settings.putAll(patch);
                }
            }
            if (night != null) night.configure(settings);
            if (session != null) { session.close(); session = null; }
            try {
                Config config = new Config(settings);
                session = new Session(config);
                host.publishScreensaver("photos", TITLE, Renderer.message("Waiting for Nextcloud Photo Frame to start…"));
                String warning = (folderMigrationWarning + " " + shuffleMigrationWarning).trim();
                host.status("Ready. Photos load when this screensaver is visible." + (warning.isEmpty() ? "" : " " + warning), !warning.isEmpty());
                session.start();
            } catch (IllegalArgumentException error) {
                host.publishScreensaver("photos", TITLE, Renderer.message(error.getMessage()));
                host.status(error.getMessage(), true);
            }
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        synchronized (lock) {
            if (!alive) return;
            if (session == null) return;
            if (command.equals("refresh")) session.refresh = true;
            else if (command.equals("next")) session.navigation = 1;
            else if (command.equals("previous")) session.navigation = -1;
            else throw new IllegalArgumentException("Unknown Nextcloud Photo Frame action");
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        synchronized (lock) {
            if (!alive) return;
            if (event.equals("ks.screensaver.state")) {
                saverRevision++;
                active = Boolean.TRUE.equals(payload.get("active"));
            } else if (event.equals("ks.screen.state")) {
                screenRevision++;
                screenOn = Boolean.TRUE.equals(payload.get("on"));
                if (night != null) night.screenState(screenOn);
            } else if (event.equals("ks.screensaver.view")) {
                view = payload.get("view") instanceof String ? (String) payload.get("view") : null;
            } else if (event.equals("ks.device.light")) {
                if (night != null) night.light(payload.get("lux"));
            } else if (event.startsWith("ks.voice.")) {
                if (night != null) night.voiceEvent(event, payload);
            }
            if (session != null) session.updateActivity();
            if (night != null) night.updateActivity();
        }
    }

    @Override public void stop() {
        synchronized (lock) {
            alive = false;
            if (night != null) night.close();
            night = null;
            if (session != null) session.close();
            session = null;
            // The host has already revoked this session and removes its renderer itself.
        }
    }

    private static final class CachedPhoto {
        final DavClient.Photo photo;
        final byte[] jpeg;
        CachedPhoto(DavClient.Photo photo, byte[] jpeg) { this.photo = photo; this.jpeg = jpeg; }
    }

    private final class Session implements AutoCloseable {
        final Config config;
        final DavClient dav;
        final ScheduledExecutorService worker;
        final LinkedHashMap<String, CachedPhoto> cache = new LinkedHashMap<>(16, .75f, true);
        final PhotoPlaylist playlist;
        final PhotoHistory history = new PhotoHistory();
        String current;
        volatile boolean refresh = true, resetDwell;
        int navigation; // Accessed under the plugin lock; the latest deferred direction wins.
        volatile boolean closed;
        volatile boolean running;
        volatile long activityRevision;
        private ScheduledFuture<?> loop;
        long refreshAt, nextAt, networkRetryAt, processingRetryAt, cacheSelectionVersion = -1;

        Session(Config config) {
            this.config = config;
            dav = new DavClient(config);
            playlist = new PhotoPlaylist(dav, config.shuffleMode, new Random());
            playlist.setActive(false);
            worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "NextcloudPhotoFrame");
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
                playlist.setActive(true);
                activityRevision++;
                resetDwell = true;
                running = true;
                loop = worker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
            } else {
                running = false;
                activityRevision++;
                playlist.setActive(false);
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
                updateIndex(revision);
                if (refresh || now >= refreshAt) {
                    boolean full = refresh;
                    refresh = false;
                    try {
                        if (current == null) {
                            String loading = "Loading the first available album/folder…";
                            publish(Renderer.message(loading)); report(loading, false);
                        }
                        playlist.refresh(full);
                        if (!working(revision)) { refresh = true; return; }
                        if (full) history.clear();
                        if (current != null) playlist.beginIndexing();
                        syncCache();
                        refreshAt = now() + config.refreshMinutes * 60_000L;
                        networkRetryAt = 0;
                        report(playlist.isEmpty() ? "No JPEG, PNG or WebP photos found in the selected sources." : playlist.size() + " photos available.", playlist.isEmpty());
                        if (playlist.isEmpty()) {
                            current = null; history.clear();
                            publish(Renderer.message("No JPEG, PNG or WebP photos found in the selected sources."));
                        }
                    } catch (IOException error) {
                        if (!working(revision)) { refresh = true; return; }
                        refreshAt = now() + 30_000;
                        report(connectionError(error), true);
                        if (current == null) publish(Renderer.message(connectionError(error)));
                    }
                }
                updateIndex(revision);
                if (!working(revision) || playlist.isEmpty()) return;
                if (resetDwell) { resetDwell = false; nextAt = now() + config.seconds * 1000L; }
                int direction;
                synchronized (lock) { direction = navigation; navigation = 0; }
                if (current == null || direction != 0 || (active && screenOn && now() >= nextAt))
                    displayPhoto(direction, revision);
                if (working(revision) && current != null) {
                    DavClient.Photo upcoming = history.next();
                    if (upcoming == null) upcoming = playlistPhoto(false, revision);
                    if (upcoming != null) cachedOrDownload(upcoming, revision);
                }
            } catch (OutOfMemoryError error) {
                // Release the largest retained buffers before calling the host.
                // Do not let a second OOM in status reporting kill the timer.
                cache.clear();
                processingRetryAt = now() + 30_000;
                nextAt = processingRetryAt;
                if (playlist.isEmpty()) refresh = true;
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

        private void displayPhoto(int direction, long revision) {
            boolean backwards = direction < 0;
            DavClient.Photo candidate = backwards ? history.previous() : history.next();
            boolean replay = backwards || candidate != null;
            if (backwards && candidate == null) {
                // No wrap-around or new random draw at the start of the viewing history.
                nextAt = now() + config.seconds * 1000L;
                return;
            }
            if (!replay) candidate = playlistPhoto(true, revision);
            if (current == null && candidate != null && working(revision)) {
                String name = config.sourceName(candidate);
                if (name.length() > 160) name = name.substring(0, name.offsetByCodePoints(0, 80)) + "…";
                String loading = "Loading the first photo from " + name + "…";
                publish(Renderer.message(loading)); report(loading, false);
            }
            byte[] image = candidate == null ? null : cachedOrDownload(candidate, revision);
            if (!working(revision)) return;
            if (image == null && !replay) {
                // Retain the actual photo metadata so offline captions and history stay accurate.
                for (Map.Entry<String, CachedPhoto> entry : cache.entrySet()) {
                    candidate = entry.getValue().photo; image = entry.getValue().jpeg;
                    if (!entry.getKey().equals(current)) break;
                }
                // Touch after iteration so offline fallback rotates through the LRU cache.
                if (image != null) cache.get(candidate.key());
            }
            if (image != null) {
                publish(Renderer.photo(image, config.fill, config.showSourceName ? config.sourceName(candidate) : ""));
                current = candidate.key();
                if (replay) history.move(backwards ? -1 : 1);
                else history.record(candidate);
            }
            nextAt = now() + (image == null ? 30_000 : config.seconds * 1000L);
            // Give the first image request priority, but don't stall indexing if that image failed.
            playlist.beginIndexing();
        }

        private boolean syncCache() {
            if (cacheSelectionVersion == playlist.selectionVersion()) return false;
            if (!playlist.perSource()) {
                Set<String> valid = new HashSet<>();
                for (DavClient.Photo photo : playlist.activePhotos()) valid.add(photo.key());
                cache.keySet().retainAll(valid);
            }
            cacheSelectionVersion = playlist.selectionVersion();
            return true;
        }

        private void updateIndex(long revision) {
            if (!working(revision)) return;
            boolean changed = playlist.maintain(now());
            if (!working(revision)) return;
            IOException error = playlist.takeIndexError();
            if (error != null) report("Background photo indexing failed.", true);
            else if (changed) report(playlist.size() + " photos available.", false);
        }

        private DavClient.Photo playlistPhoto(boolean consume, long revision) {
            if (!working(revision) || (playlist.needsNetwork() && now() < networkRetryAt)) return null;
            try {
                DavClient.Photo photo = consume ? playlist.next() : playlist.peek();
                if (!working(revision)) return null;
                boolean changed = syncCache();
                if (changed) {
                    if (current != null) playlist.beginIndexing();
                    report(playlist.size() + " photos available.", false);
                }
                if (photo == null && playlist.isEmpty()) {
                    cache.clear(); current = null; history.clear();
                    report("No JPEG, PNG or WebP photos found in the selected sources.", true);
                    publish(Renderer.message("No JPEG, PNG or WebP photos found in the selected sources."));
                }
                return photo;
            } catch (IOException error) {
                if (!working(revision)) return null;
                networkRetryAt = now() + 30_000;
                report(connectionError(error), true);
                if (current == null) publish(Renderer.message(connectionError(error)));
                return null;
            }
        }

        private byte[] cachedOrDownload(DavClient.Photo photo, long revision) {
            if (!working(revision)) return null;
            CachedPhoto cached = cache.get(photo.key());
            byte[] image = cached == null ? null : cached.jpeg;
            if (image != null || closed || now() < networkRetryAt) return image;
            try {
                image = encoder.encode(dav.download(photo));
                if (!working(revision)) return null;
                cache.put(photo.key(), new CachedPhoto(photo, image));
                while (cache.size() > config.cachePhotos) cache.remove(cache.keySet().iterator().next());
                report(playlist.size() + " photos available; " + cache.size() + " cached.", false);
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
            return ErrorMessages.connection(error);
        }

        private void publish(String html) {
            synchronized (lock) {
                if (alive && session == this && !closed) {
                    try { host.publishScreensaver("photos", TITLE, html); }
                    catch (IllegalStateException ignored) { /* host may revoke before calling stop */ }
                }
            }
        }

        private void report(String message, boolean error) {
            synchronized (lock) {
                if (alive && session == this && !closed) {
                    try {
                        String warning = night == null ? "" : night.warning();
                        if (!folderMigrationWarning.isEmpty()) warning = (warning.isEmpty() ? "" : warning + " ") + folderMigrationWarning;
                        if (!shuffleMigrationWarning.isEmpty()) warning = (warning.isEmpty() ? "" : warning + " ") + shuffleMigrationWarning;
                        String note = playlist.note();
                        String full = message + (note.isEmpty() ? "" : " " + note);
                        host.status(warning.isEmpty() ? full : full + " " + warning, error || !warning.isEmpty() || playlist.globalFallback() || playlist.indexFailed());
                    }
                    catch (IllegalStateException ignored) { /* revoked session */ }
                }
            }
        }

        @Override public void close() {
            closed = true;
            running = false;
            activityRevision++;
            worker.shutdownNow();
            playlist.close();
        }
    }
}
