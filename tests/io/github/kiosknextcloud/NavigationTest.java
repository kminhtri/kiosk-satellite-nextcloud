// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class NavigationTest {
    static void run() throws Exception {
        captions();
        boundedHistory();
        loadingStates();
        for (ShuffleMode mode : ShuffleMode.values()) navigate(mode);
        offlineCaptions();
    }

    private static DavClient.Photo photo(int id) {
        HttpUrl source = HttpUrl.get("https://cloud.example/remote.php/dav/photos/alice/albums/Family/");
        return new DavClient.Photo(source.newBuilder().addPathSegment(id + ".jpg").build(), "v1", "" + id, source);
    }

    private static void boundedHistory() {
        PhotoHistory history = new PhotoHistory();
        check(history.previous() == null && history.next() == null, "empty history has no navigation target");
        for (int i = 0; i < PhotoHistory.LIMIT + 5; i++) history.record(photo(i));
        for (int i = 0; i < PhotoHistory.LIMIT - 1; i++) history.move(-1);
        check(history.previous() == null && history.next().id.equals("6"), "history retains only the last 100 photo references");
        history.move(-1);
        check(history.next().id.equals("6"), "previous at the beginning never wraps around");
        history.move(1);
        history.record(photo(999));
        check(history.next() == null && history.previous().id.equals("6"), "new displays replace any abandoned forward history");
        history.record(photo(999));
        check(history.previous().id.equals("6"), "consecutive displays of one photo do not duplicate history");
        history.clear();
        check(history.previous() == null && history.next() == null, "explicit refresh can reset viewing history");
    }

    private static void captions() {
        Config config = new Config(settings("https://cloud.example/nextcloud"));
        check(!config.showSourceName, "source name is disabled for existing settings");
        HttpUrl album = HttpUrl.get("https://cloud.example/nextcloud/remote.php/dav/photos/alice/albums/")
                .newBuilder().addPathSegment("Vietnam & Laos été 2025").addPathSegment("").build();
        DavClient.Photo photo = new DavClient.Photo(album.resolve("a.jpg"), "", "", album);
        check(config.sourceName(photo).equals("Vietnam & Laos été 2025"), "caption decodes the album name without exposing DAV paths");
        HttpUrl folder = config.folder.newBuilder().addPathSegment("Family").addPathSegment("").build();
        check(config.sourceName(new DavClient.Photo(folder.resolve("a.jpg"), "", "", folder)).equals("Family"),
                "recursive folder caption uses the individual folder instead of its selected parent");
        check(config.sourceName(new DavClient.Photo(config.filesHome.resolve("a.jpg"), "", "", config.filesHome)).equals("Files home"),
                "Files home caption does not show the account username");
        check(!Renderer.photo(new byte[1], false).contains("<div class=\"source\">"), "disabled source caption emits no overlay");
        String html = Renderer.photo(new byte[1], true, "<script>\"&' été");
        check(html.contains("&lt;script&gt;&quot;&amp;&#39; été") && !html.contains("<script>"), "album and folder names are HTML escaped");
        String hugeName = String.join("", Collections.nCopies(16384, "&😀"));
        check(Renderer.photo(new byte[AndroidImageEncoder.MAX_JPEG], false, hugeName).getBytes(StandardCharsets.UTF_8).length < 512 * 1024,
                "maximum JPEG plus an escaped Unicode caption remains below the SDK HTML cap");
    }

    private static void loadingStates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.block = new CountDownLatch(1); fixture.entered = new CountDownLatch(1);
            fixture.blockedPathSuffix = "/Photos/";
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, () -> 1L);
            Map<String, Object> values = settings(fixture.url()); values.put("shuffle", false);
            plugin.start(host, values);
            try {
                show(plugin);
                check(fixture.entered.await(4, TimeUnit.SECONDS), "first folder listing is pending");
                check(host.html.contains("Loading the first available album/folder") && host.status.startsWith("Loading the first available"),
                        "initial metadata loading is visible in both the screensaver and plugin status");
                CountDownLatch listing = fixture.block;
                fixture.block = new CountDownLatch(1); fixture.entered = new CountDownLatch(1);
                fixture.blockedPathSuffix = "/a.jpg"; listing.countDown();
                check(fixture.entered.await(4, TimeUnit.SECONDS), "first image download is pending");
                check(host.html.contains("Loading the first photo from Photos") && host.status.startsWith("Loading the first photo from Photos"),
                        "initial download loading names its source before the photo arrives");
                fixture.block.countDown();
                await(() -> host.images.get() > 0, "first photo replaces the loading message");
            } finally { plugin.stop(); fixture.block.countDown(); }
        }
    }

    private static String action(NextcloudPlugin plugin, Host host, String action) throws Exception {
        int before = host.images.get();
        plugin.execute(action, Collections.emptyMap());
        await(() -> host.images.get() > before, action + " displays a photo");
        return host.html;
    }

    private static void navigate(ShuffleMode mode) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.directoryListing = path -> listing(entry(path + "a.jpg", false, "image/jpeg", "v1", path + "a")
                    + entry(path + "b.png", false, "image/png", "v1", path + "b"));
            Map<String, Object> values = ShuffleTest.albumSettings(fixture.url(), mode);
            values.put("albums", Arrays.asList(Collections.singletonMap("name", "A"), Collections.singletonMap("name", "B")));
            values.put("showSourceName", true); values.put("cachePhotos", 2);
            AtomicLong clock = new AtomicLong(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, clock::get);
            plugin.start(host, values);
            try {
                show(plugin);
                await(() -> host.images.get() > 0 && host.status.contains("index ready"), "initial album and background index ready for " + mode);
                List<String> displayed = new ArrayList<>(); displayed.add(host.html);
                int count = host.images.get();
                plugin.execute("previous", Collections.emptyMap()); Thread.sleep(1200);
                check(host.images.get() == count, "previous before any history keeps the first photo in " + mode);
                for (int i = 0; i < 3; i++) displayed.add(action(plugin, host, "next"));
                int requests = fixture.gets.get();
                for (int i = 2; i >= 0; i--)
                    check(action(plugin, host, "previous").equals(displayed.get(i)), "previous follows displayed history across source/index changes in " + mode);
                check(fixture.gets.get() > requests, "history re-downloads evicted photos without expanding the two-image cache in " + mode);
                for (int i = 1; i < 4; i++)
                    check(action(plugin, host, "next").equals(displayed.get(i)), "next retraces forward history in " + mode);
                String resumed = action(plugin, host, "next");
                check(resumed.contains("<div class=\"source\">"), "new playlist playback resumes with the caption in " + mode);
                // Deferred navigation while hidden must cause no new network work.
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", false));
                Thread.sleep(200); count = host.images.get(); requests = fixture.gets.get();
                plugin.execute("previous", Collections.emptyMap()); Thread.sleep(1100);
                check(host.images.get() == count && fixture.gets.get() == requests, "previous remains deferred with the display off in " + mode);
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", true));
                final int beforeResume = count;
                await(() -> host.images.get() > beforeResume, "deferred previous executes when visible in " + mode);
                check(host.html.equals(displayed.get(3)), "deferred previous returns to the actual last display in " + mode);
                // A normal refresh preserves viewing history, even after walking backward.
                clock.addAndGet(16 * 60_000L);
                action(plugin, host, "previous");
                check(host.html.equals(displayed.get(2)), "periodic membership refresh preserves viewing history in " + mode);
                int listings = fixture.propfinds.get();
                plugin.execute("refresh", Collections.emptyMap());
                await(() -> fixture.propfinds.get() > listings && host.status.contains("index ready"), "manual refresh reloads membership in " + mode);
                action(plugin, host, "next");
                count = host.images.get(); plugin.execute("previous", Collections.emptyMap()); Thread.sleep(1200);
                check(host.images.get() == count, "manual refresh starts a new viewing history in " + mode);
            } finally { plugin.stop(); }
        }
    }

    private static void offlineCaptions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.directoryListing = path -> listing(entry(path + "a.jpg", false, "image/jpeg", "v1", path + "a"));
            Map<String, Object> values = ShuffleTest.albumSettings(fixture.url(), ShuffleMode.OFF);
            values.put("albums", Arrays.asList(Collections.singletonMap("name", "A"), Collections.singletonMap("name", "B")));
            values.put("showSourceName", true); values.put("cachePhotos", 2);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, () -> 1L);
            plugin.start(host, values);
            try {
                show(plugin);
                await(() -> host.images.get() > 0 && host.status.contains("index ready"), "offline caption test indexed");
                String first = host.html;
                String second = action(plugin, host, "next");
                check(!first.equals(second), "source caption distinguishes identical photos in different albums");
                fixture.failImages = true;
                check(action(plugin, host, "previous").equals(first), "cached previous keeps its album caption offline");
                check(action(plugin, host, "next").equals(second), "cached forward history keeps its album caption offline");
                check(action(plugin, host, "next").equals(first), "ordinary offline playback uses the actual cached photo's caption");
            } finally { plugin.stop(); }
        }
    }
}
