// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class ShuffleTest {
    static void run() throws Exception {
        settingsMigration();
        ordering();
        refreshProgress();
        pluginMigration();
        for (ShuffleMode mode : ShuffleMode.values()) largePlayback(mode);
        singleCollectionLimit();
        pluginLargeFallback();
    }

    static Map<String, Object> albumSettings(String url, ShuffleMode mode) {
        Map<String, Object> values = settings(url);
        values.put("photoSource", "Albums");
        values.put("albums", Arrays.asList(Collections.singletonMap("name", "A"),
                Collections.singletonMap("name", "Empty"), Collections.singletonMap("name", "B")));
        values.put("shuffleMode", mode.label); values.put("shuffleModeVersion", 1);
        return values;
    }

    private static void settingsMigration() throws Exception {
        Map<String, Object> values = settings("https://cloud.example");
        check(new Config(values).shuffleMode == ShuffleMode.GLOBAL, "legacy default shuffle remains global");
        values.put("shuffle", false);
        values.put("shuffleMode", ShuffleMode.GLOBAL.label);
        check(new Config(values).shuffleMode == ShuffleMode.OFF, "new manifest default cannot overwrite a saved legacy Off");
        Map<String, Object> patch = ShuffleMode.migration(values);
        check(patch.size() == 2 && patch.get("shuffleMode").equals("Off") && Boolean.FALSE.equals(values.get("shuffle")), "migration changes only mode and schema without modifying caller settings");
        values.putAll(patch);
        check(ShuffleMode.migration(values).isEmpty(), "shuffle migration is idempotent");
        values.put("shuffleMode", ShuffleMode.GLOBAL.label);
        check(new Config(values).shuffleMode == ShuffleMode.GLOBAL, "explicit global selection overrides a hidden legacy Off");
        values.put("shuffleMode", ShuffleMode.WITHIN.label); values.put("shuffleModeVersion", 1.0);
        check(new Config(values).shuffleMode == ShuffleMode.WITHIN, "numeric schema versions and Within mode are accepted");
        values.put("shuffleMode", "unknown");
        expectFailure(() -> new Config(values), "invalid migrated shuffle mode is rejected");
    }

    private static List<DavClient.Photo> photos() {
        List<DavClient.Photo> photos = new ArrayList<>();
        for (String group : Arrays.asList("A", "B", "C")) {
            HttpUrl source = HttpUrl.get("https://cloud.example/" + group + "/");
            for (int i = 0; i < 6; i++) photos.add(new DavClient.Photo(source.resolve(i + ".jpg"), "v1", group + i, source));
        }
        return photos;
    }

    private static void ordering() {
        List<DavClient.Photo> original = photos();
        List<DavClient.Photo> off = PlaybackOrder.build(original, ShuffleMode.OFF, new Random(7), original.get(0).key());
        check(off.equals(original), "Off preserves server/source order even at a cycle boundary");
        List<DavClient.Photo> within = PlaybackOrder.build(original, ShuffleMode.WITHIN, new Random(7), null);
        boolean grouped = true;
        for (int i = 0; i < within.size(); i++) grouped &= within.get(i).source.equals(original.get(i).source);
        check(grouped && new HashSet<>(within).size() == original.size() && !within.equals(original), "Within shuffles every group without interleaving groups or losing photos");
        List<DavClient.Photo> global = PlaybackOrder.build(original, ShuffleMode.GLOBAL, new Random(7), null);
        boolean crossesGroups = false;
        for (int i = 0; i < global.size(); i++) crossesGroups |= !global.get(i).source.equals(original.get(i).source);
        check(crossesGroups && new HashSet<>(global).size() == original.size(), "global mode mixes all selected sources without replacement");
    }

    private static void refreshProgress() {
        for (ShuffleMode mode : Arrays.asList(ShuffleMode.OFF, ShuffleMode.WITHIN)) {
            List<DavClient.Photo> photos = photos();
            List<DavClient.Photo> old = PlaybackOrder.build(photos, mode, new Random(7), null);
            PlaybackOrder.Cycle refreshed = PlaybackOrder.refresh(photos, mode, new Random(9), old, 8, old.get(7).key());
            Set<String> played = new HashSet<>();
            for (int i = 0; i < 8; i++) played.add(old.get(i).key());
            boolean repeats = false;
            for (int i = refreshed.cursor; i < refreshed.photos.size(); i++) repeats |= played.contains(refreshed.photos.get(i).key());
            check(!repeats && refreshed.cursor == 8, "periodic refresh preserves progress instead of starving later sources in " + mode.label);
            photos.remove(old.get(8));
            PlaybackOrder.Cycle changed = PlaybackOrder.refresh(photos, mode, new Random(9), old, 8, null);
            check(changed.photos.size() == 17 && changed.cursor < changed.photos.size(), "refresh handles a removed upcoming photo in " + mode.label);
        }
    }

    private static void pluginMigration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> values = settings(fixture.url());
            values.put("folderListVersion", 1); values.put("folders", Collections.singletonList(Collections.singletonMap("path", "Photos")));
            values.put("shuffle", false); values.put("shuffleMode", ShuffleMode.GLOBAL.label);
            MigratingHost host = new MigratingHost(values);
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, () -> 1L);
            plugin.start(host, values);
            try {
                check(host.updates == 1 && host.last.keySet().equals(new HashSet<>(Arrays.asList("shuffleMode", "shuffleModeVersion"))), "host persists a partial shuffle migration only");
                check(host.saved.get("shuffleMode").equals("Off") && host.saved.get("appPassword").equals("test-password"), "migration preserves legacy Off and credentials");
                plugin.configure(host.saved);
                check(host.updates == 1 && fixture.propfinds.get() == 0, "saved migration is not repeated and does not start idle traffic");
            } finally { plugin.stop(); }
        }
    }

    private static void largePlayback(ShuffleMode mode) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.streamedListing = path -> new LargeLibraryTest.GeneratedListing(path,
                    path.endsWith("Empty/") ? 0 : 10_001, false, path.endsWith("B/") ? 10_001 : 0);
            Config config = new Config(albumSettings(fixture.url(), mode));
            try (DavClient client = new DavClient(config); PhotoPlaylist playlist = new PhotoPlaylist(client, mode, new Random(7))) {
                playlist.refresh(true);
                awaitIndex(playlist);
                check(playlist.perSource() && playlist.size() == 10_001, "libraries over 20000 switch to bounded per-source metadata in " + mode.label);
                check(playlist.globalFallback() == (mode == ShuffleMode.GLOBAL) && playlist.note().contains("20000"), "fallback is explicit and matches the selected shuffle mode");
                DavClient.Photo first = playlist.peek();
                check(playlist.peek() == first && playlist.next() == first, "prefetch does not consume or reorder the next photo");
                Set<String> ids = new HashSet<>(); ids.add(first.id);
                boolean onlyA = first.source.encodedPath().endsWith("A/");
                for (int i = 1; i < 10_001; i++) {
                    if (i == 300) {
                        int requests = fixture.propfinds.get();
                        playlist.refresh(false);
                        check(fixture.propfinds.get() == requests + 1, "large-library periodic refresh reloads only the current source");
                    }
                    DavClient.Photo photo = playlist.next();
                    onlyA &= photo.source.encodedPath().endsWith("A/"); ids.add(photo.id);
                }
                check(onlyA && ids.size() == 10_001, "all photos of an album play once despite periodic refresh before changing source");
                if (mode == ShuffleMode.OFF) {
                    fixture.missingPaths.add("/remote.php/dav/photos/alice/albums/B/");
                    expectFailure(playlist::next, "source transition reports network/access failure");
                    check(playlist.activePhotos().get(0).source.encodedPath().endsWith("A/"), "failed transition retains the previous source");
                    fixture.missingPaths.clear();
                }
                DavClient.Photo next = playlist.next();
                check(next.source.encodedPath().endsWith("B/") && playlist.size() <= 20_000, "next source follows after the current album and skips empty albums");
                check(fixture.gets.get() == 0, "large playback indexing never downloads all images");
            }
        }
    }

    private static void singleCollectionLimit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.streamedListing = path -> new LargeLibraryTest.GeneratedListing(path, 20_001, false);
            try (DavClient client = new DavClient(new Config(albumSettings(fixture.url(), ShuffleMode.GLOBAL)));
                 PhotoPlaylist playlist = new PhotoPlaylist(client, ShuffleMode.GLOBAL, new Random(7))) {
                try { playlist.refresh(true); throw new AssertionError("one oversized album must not be truncated"); }
                catch (DavClient.PhotoLimitException error) {
                    check(error.singleCollection && error.getMessage().contains("single album or folder"), "a single collection over 20000 is a distinct actionable error");
                }
            }
        }
    }

    private static void pluginLargeFallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.streamedListing = path -> new LargeLibraryTest.GeneratedListing(path,
                    path.endsWith("Empty/") ? 0 : 10_001, false, path.endsWith("B/") ? 10_001 : 0);
            AtomicLong clock = new AtomicLong(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, clock::get);
            plugin.start(host, albumSettings(fixture.url(), ShuffleMode.GLOBAL));
            try {
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "large-library planning preserves zero idle traffic");
                show(plugin);
                long deadline = System.nanoTime() + 15_000_000_000L;
                while ((host.images.get() == 0 || !host.status.contains("global shuffle is unavailable")) && System.nanoTime() < deadline) Thread.sleep(40);
                check(host.images.get() > 0 && host.status.contains("global shuffle is unavailable") && host.status.contains("20000"),
                        "visible screensaver displays a photo and explicitly reports global-shuffle fallback");
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", false));
                Thread.sleep(200);
                int before = fixture.propfinds.get() + fixture.gets.get();
                Thread.sleep(1_100);
                check(fixture.propfinds.get() + fixture.gets.get() == before, "large-source playback cancels and stays idle while the display is off");
            } finally { plugin.stop(); }
        }
    }

    static void awaitIndex(PhotoPlaylist playlist) throws Exception {
        playlist.beginIndexing();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (playlist.scanning() && System.nanoTime() < deadline) {
            playlist.maintain(System.nanoTime() / 1_000_000); Thread.sleep(10);
        }
        check(!playlist.scanning() && !playlist.indexFailed(), "background index finishes or detects the large-library fallback");
    }

    static final class MigratingHost extends Host {
        final Map<String, Object> saved;
        Map<String, Object> last;
        int updates;
        MigratingHost(Map<String, Object> values) { saved = new HashMap<>(values); }
        @Override public void updateSettings(Map<String, Object> patch) { updates++; last = new HashMap<>(patch); saved.putAll(patch); }
    }

    private ShuffleTest() {}
}
