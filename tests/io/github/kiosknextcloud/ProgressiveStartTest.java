// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class ProgressiveStartTest {
    static void run() throws Exception {
        firstAlbumPlaysWhileIndexing();
        for (ShuffleMode mode : Arrays.asList(ShuffleMode.OFF, ShuffleMode.WITHIN)) startsWithoutFullScan(mode);
        indexingFailureKeepsPlayback();
        firstPhotoFailureDoesNotBlockIndex();
        pauseAndStop();
    }

    private static void prepare(Fixture fixture, boolean block) {
        fixture.directoryListing = path -> listing(entry(path, true, "", "")
                + (path.endsWith("Empty/") ? "" : entry(path + "a.jpg", false, "image/jpeg", "v1", path + "1")
                + entry(path + "b.png", false, "image/png", "v1", path + "2")));
        if (block) {
            fixture.block = new CountDownLatch(1); fixture.entered = new CountDownLatch(1);
            fixture.blockedPathSuffix = "/B/"; fixture.blockSeconds = 30;
        }
    }

    private static Map<String, Object> values(Fixture fixture, ShuffleMode mode) {
        Map<String, Object> values = ShuffleTest.albumSettings(fixture.url(), mode);
        values.put("secondsPerPhoto", 5); return values;
    }

    private static void firstAlbumPlaysWhileIndexing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            prepare(fixture, true);
            AtomicLong clock = new AtomicLong(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, clock::get);
            plugin.start(host, values(fixture, ShuffleMode.GLOBAL));
            try {
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "progressive startup has no idle network traffic");
                show(plugin);
                check(fixture.entered.await(4, TimeUnit.SECONDS), "background index reaches the blocked second album");
                await(() -> host.images.get() > 0, "first album displays before the slow full index finishes");
                await(() -> host.status.contains("2 cached"), "first photo dwell starts before advancing the test clock");
                check(fixture.block.getCount() == 1 && host.status.contains("while indexing"), "initial status explains provisional album playback");
                check(fixture.listedPaths.stream().filter(path -> path.endsWith("/A/")).count() == 1,
                        "background indexing reuses the first collection instead of fetching its metadata twice");
                int first = host.images.get();
                clock.addAndGet(5_000);
                await(() -> host.images.get() > first, "first album continues rotating while the index connection is blocked");
                int beforeReady = host.images.get();
                fixture.block.countDown();
                await(() -> host.status.contains("index ready") && host.status.startsWith("4 photos available"), "completed index is staged for the next photo");
                check(host.images.get() == beforeReady, "finishing the index never interrupts the current photo");
                clock.addAndGet(4_999); Thread.sleep(1_100);
                check(host.images.get() == beforeReady, "global shuffle cannot shorten the current photo's dwell time");
                clock.addAndGet(1);
                await(() -> host.images.get() > beforeReady && !host.status.contains("index ready"), "global shuffle takes over at the next photo deadline");
            } finally { plugin.stop(); fixture.block.countDown(); }
        }
    }

    private static void startsWithoutFullScan(ShuffleMode mode) throws Exception {
        try (Fixture fixture = new Fixture()) {
            prepare(fixture, true);
            Config config = new Config(values(fixture, mode));
            try (DavClient dav = new DavClient(config); PhotoPlaylist playlist = new PhotoPlaylist(dav, mode, new Random(7))) {
                playlist.refresh(true);
                playlist.beginIndexing();
                check(fixture.entered.await(4, TimeUnit.SECONDS), "index is independently blocked for " + mode.label);
                check(playlist.next() != null && playlist.size() == 2, mode.label + " starts with the first collection without awaiting the index");
                playlist.next();
                fixture.block.countDown(); ShuffleTest.awaitIndex(playlist);
                check(playlist.indexReady(), "small-library index becomes available for " + mode.label);
                int listed = fixture.propfinds.get();
                check(playlist.peek() == null && fixture.propfinds.get() == listed, "prefetch waits for a ready plan without reloading another source");
                DavClient.Photo following = playlist.next();
                check(!playlist.perSource() && playlist.size() == 4 && following.source.encodedPath().endsWith("B/"),
                        mode.label + " adopts the full index after the finished provisional album without restarting it");
            } finally { fixture.block.countDown(); }
        }
    }

    private static void indexingFailureKeepsPlayback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            prepare(fixture, false);
            fixture.missingPaths.add("/remote.php/dav/photos/alice/albums/B/");
            Config config = new Config(values(fixture, ShuffleMode.GLOBAL));
            try (DavClient dav = new DavClient(config); PhotoPlaylist playlist = new PhotoPlaylist(dav, ShuffleMode.GLOBAL, new Random(7))) {
                playlist.refresh(true);
                playlist.beginIndexing();
                long deadline = System.nanoTime() + 4_000_000_000L;
                while (!playlist.indexFailed() && System.nanoTime() < deadline) {
                    playlist.maintain(System.nanoTime() / 1_000_000); Thread.sleep(10);
                }
                check(playlist.indexFailed() && playlist.next() != null && playlist.next() != null && playlist.next() != null,
                        "later album failure does not stop provisional album rotation");
                check(playlist.takeIndexError().getMessage().contains("album was not found"), "background failure remains actionable");
                check(playlist.note().contains("album was not found"), "background failure reason stays visible during successful provisional playback");
                fixture.missingPaths.clear(); playlist.refresh(true); ShuffleTest.awaitIndex(playlist);
                playlist.next();
                check(!playlist.indexFailed() && !playlist.perSource(), "manual refresh recovers from an earlier indexing error");
            }
        }
    }

    private static void pauseAndStop() throws Exception {
        try (Fixture fixture = new Fixture()) {
            prepare(fixture, true);
            AtomicLong clock = new AtomicLong(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, clock::get);
            plugin.start(host, values(fixture, ShuffleMode.GLOBAL));
            try {
                show(plugin);
                check(fixture.entered.await(4, TimeUnit.SECONDS), "index starts before lifecycle cancellation test");
                await(() -> host.images.get() > 0, "a provisional photo is visible before pausing");
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", false));
                Thread.sleep(200);
                int requests = fixture.propfinds.get() + fixture.gets.get();
                Thread.sleep(1_100);
                check(fixture.propfinds.get() + fixture.gets.get() == requests, "hidden display stops index and image activity");
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", true));
                int images = host.images.get(); plugin.execute("next", Collections.emptyMap());
                await(() -> host.images.get() > images, "cached provisional playback resumes without waiting for the canceled index");
                plugin.stop();
                int published = host.publishes.get();
                fixture.block.countDown(); Thread.sleep(1_100);
                check(host.publishes.get() == published, "late background completion cannot publish into a stopped session");
            } finally { plugin.stop(); fixture.block.countDown(); }
        }
    }

    private static void firstPhotoFailureDoesNotBlockIndex() throws Exception {
        try (Fixture fixture = new Fixture()) {
            prepare(fixture, false); fixture.failImages = true;
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, () -> 1L);
            plugin.start(host, values(fixture, ShuffleMode.GLOBAL));
            try {
                show(plugin);
                await(() -> fixture.listedPaths.stream().anyMatch(path -> path.endsWith("/B/")),
                        "failed first image still releases background discovery of other sources");
                check(host.images.get() == 0, "metadata indexing does not pretend failed images were displayed");
            } finally { plugin.stop(); }
        }
    }

    private ProgressiveStartTest() {}
}
