// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;
import okhttp3.*;

/** A real HTTP fixture exercises authentication, PROPFIND, redirects, failures and lifecycle races. */
public final class NextcloudTest {
    static int checks;

    public static void main(String[] args) throws Exception {
        configAndXml();
        rendererBudget();
        multipleFolders();
        try (Fixture fixture = new Fixture()) {
            webdav(fixture);
            slideshow(fixture);
            lifecycle(fixture);
        }
        idleAndCancellation();
        memoryRecovery(false);
        memoryRecovery(true);
        System.out.println("Nextcloud integration tests passed (" + checks + " assertions).");
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static void expectFailure(Throwing action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }

    interface Throwing { void run() throws Exception; }

    static void configAndXml() throws Exception {
        Map<String, Object> settings = settings("https://cloud.example/nextcloud/");
        settings.put("username", "alice@example.org");
        settings.put("folder", "/Photos/Family & friends/été/");
        Config c = new Config(settings);
        check(c.folder.toString().equals("https://cloud.example/nextcloud/remote.php/dav/files/alice@example.org/Photos/Family%20&%20friends/%C3%A9t%C3%A9/"), "installation prefix and URL encoding: " + c.folder);
        check(c.authorization.equals(Credentials.basic("alice@example.org", "test-password", StandardCharsets.UTF_8)), "basic authentication");
        String encodedPath = c.folder.encodedPath().replace("@", "%40").replace("&", "%26");
        DavClient.Listing emailListing = DavClient.parse(listing(entry(encodedPath + "p.jpg", false, "image/jpeg", "v1")).getBytes(StandardCharsets.UTF_8), c.folder, c.folder);
        check(emailListing.photos.size() == 1, "DAV accepts equivalent server percent-encoding in usernames and folders");
        check(!DavClient.within(HttpUrl.get(c.folder.toString().replace("/Photos/", "/Photos-private/") + "p.jpg"), c.folder), "directory names require a whole segment match");
        settings.put("folder", "Photos/../private");
        expectFailure(() -> new Config(settings), "reject folder traversal");
        settings.put("folder", "Photos");
        settings.put("serverUrl", "http://cloud.example"); settings.put("allowHttp", false);
        expectFailure(() -> new Config(settings), "HTTP requires explicit opt-in");
        settings.put("serverUrl", "https://alice:password@cloud.example");
        expectFailure(() -> new Config(settings), "reject embedded credentials");
        Map<String, Object> multiSettings = settings("https://cloud.example");
        multiSettings.put("additionalFolders", Arrays.asList(Collections.singletonMap("path", "/Photos/"),
                Collections.singletonMap("path", "Holidays/été")));
        Config multi = new Config(multiSettings);
        check(multi.folders.size() == 2 && multi.folder.equals(multi.folders.get(0)), "keep legacy folder and remove duplicate roots");
        check(multi.folders.get(1).encodedPath().endsWith("/Holidays/%C3%A9t%C3%A9/"), "encode additional folder paths");
        final Map<String, Object> invalid = new HashMap<>(multiSettings);
        invalid.put("additionalFolders", Collections.singletonList(Collections.singletonMap("path", "Photos/../private")));
        expectFailure(() -> new Config(invalid), "reject traversal in additional folder");
        invalid.put("additionalFolders", Collections.singletonList(Collections.singletonMap("path", " ")));
        expectFailure(() -> new Config(invalid), "blank additional folder must not select whole account");
        invalid.put("additionalFolders", Collections.nCopies(32, Collections.singletonMap("path", "Photos")));
        expectFailure(() -> new Config(invalid), "bound configured folder count");

        HttpUrl root = HttpUrl.get("https://cloud.example/remote.php/dav/files/alice/Photos/");
        String path = root.encodedPath();
        String xml = listing(entry(path, true, "", "") + entry(path + "one%20photo.jpg", false, "image/jpeg", "v1")
                + entry(path + "sub/", true, "", "") + entry(path + "movie.mp4", false, "video/mp4", "")
                + entry("https://evil.example/steal.jpg", false, "image/jpeg", "")
                + entry(path + "../secret.jpg", false, "image/jpeg", "")
                + entry(path + "bad%2Fname.jpg", false, "image/jpeg", "")
                + entry(path + "sub/deep.jpg", false, "image/jpeg", ""));
        DavClient.Listing parsed = DavClient.parse(xml.getBytes(StandardCharsets.UTF_8), root, root);
        check(parsed.photos.size() == 1, "filter unsupported media, external URLs, traversal and nonchildren");
        check(parsed.photos.get(0).etag.equals("v1"), "read etag");
        check(parsed.folders.size() == 1, "exclude directory self entry");
        check(DavClient.parse(("\uFEFF" + xml).getBytes(StandardCharsets.UTF_8), root, root).photos.size() == 1, "UTF-8 byte order mark");
        String partial = "<d:response><d:href>" + path + "partial.jpg</d:href>"
                + "<d:propstat><d:prop><d:getcontenttype>image/jpeg</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>"
                + "<d:propstat><d:prop><d:collection/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>";
        check(DavClient.parse(listing(partial).getBytes(StandardCharsets.UTF_8), root, root).photos.size() == 1, "ignore failed property groups");
        expectFailure(() -> DavClient.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".getBytes(StandardCharsets.UTF_8), root, root), "deny XML entities");
        expectFailure(() -> DavClient.parse("<invalid".getBytes(StandardCharsets.UTF_8), root, root), "invalid XML rejected");
        check(!DavClient.within(HttpUrl.get("https://user@cloud.example" + path + "x.jpg"), root), "reject href credentials");
        check(!DavClient.within(HttpUrl.get("https://cloud.example" + path + "x.jpg?download=1"), root), "reject href query");
    }

    static void rendererBudget() throws Exception {
        byte[] image = new byte[AndroidImageEncoder.MAX_JPEG];
        String html = Renderer.photo(image, true);
        check(html.getBytes(StandardCharsets.UTF_8).length < 512 * 1024, "worst-case HTML fits SDK limit");
        check(html.contains("object-fit:cover"), "Fill uses cover");
        check(Renderer.photo(new byte[1], false).contains("object-fit:contain"), "Fit uses contain");
        check(!Renderer.message("<script>\"&").contains("<script>"), "escape status HTML");
        expectFailure(() -> Renderer.photo(new byte[AndroidImageEncoder.MAX_JPEG + 1], false), "reject oversized image");
    }

    static void multipleFolders() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> values = settings(fixture.url());
            values.put("additionalFolders", Collections.singletonList(Collections.singletonMap("path", "Photos/sub")));
            try (DavClient client = new DavClient(new Config(values))) {
                check(client.list().size() == 4, "explicit child folder works with recursion disabled");
            }
            fixture.propfinds.set(0);
            values.put("includeSubfolders", true);
            try (DavClient client = new DavClient(new Config(values))) {
                check(client.list().size() == 4 && fixture.propfinds.get() == 2, "overlapping roots visited only once without file IDs");
            }

            String root = fixture.root, holidays = root.replace("Photos/", "Holidays/");
            fixture.directoryListing = path -> {
                if (path.equals(root)) return listing(entry(root, true, "", "", "root")
                        + entry(root + "a.jpg", false, "image/jpeg", "v1", "photo-a")
                        + entry(root + "sub/", true, "", "", "sub")
                        + entry(root + "loop/", true, "", "", "root")
                        + entry(root + "sub-alias/", true, "", "", "sub"));
                if (path.equals(holidays)) return listing(entry(path, true, "", "", "holidays")
                        + entry(path + "copy.jpg", false, "image/jpeg", "v1", "photo-a")
                        + entry(path + "b.jpg", false, "image/jpeg", "v2", "photo-b"));
                if (path.equals(root + "sub/")) return listing(entry(path, true, "", "", "sub")
                        + entry(path + "c.jpg", false, "image/jpeg", "v3", "photo-c")
                        + entry(path + "back/", true, "", "", "root"));
                throw new AssertionError("Followed a cycle or duplicate alias: " + path);
            };
            values.put("additionalFolders", Collections.singletonList(Collections.singletonMap("path", "Holidays")));
            fixture.propfinds.set(0);
            try (DavClient client = new DavClient(new Config(values))) {
                List<DavClient.Photo> photos = client.list();
                check(photos.size() == 3 && fixture.propfinds.get() == 3, "file IDs prevent ancestor loops, queued aliases and duplicate photos");
                DavClient.Photo holiday = photos.stream().filter(photo -> photo.url.encodedPath().contains("Holidays")).findFirst().get();
                check(client.download(holiday).length > 0, "download photos from additional root");
                int gets = fixture.gets.get();
                expectFailure(() -> client.download(new DavClient.Photo(HttpUrl.get(fixture.url() + "/private.jpg"), "", "")), "reject downloads outside all selected roots");
                check(fixture.gets.get() == gets, "outside-root download rejected before request");
            }
            values.put("includeSubfolders", false);
            fixture.propfinds.set(0);
            try (DavClient client = new DavClient(new Config(values))) {
                check(client.list().size() == 2 && fixture.propfinds.get() == 2, "all roots listed without recursion, with shared photo deduplication");
            }

            // A separately configured alias has no ID until its self entry is read.
            fixture.directoryListing = path -> listing(entry(path, true, "", "", "same-root")
                    + entry(path + "x.jpg", false, "image/jpeg", "v1", path.equals(root) ? "first" : "must-skip"));
            try (DavClient client = new DavClient(new Config(values))) {
                check(client.list().size() == 1, "self IDs deduplicate separately configured root aliases");
            }

            values.remove("additionalFolders");
            values.put("includeSubfolders", true);
            fixture.directoryListing = path -> listing(entry(path, true, "", "") + entry(path + "loop/", true, "", ""));
            fixture.propfinds.set(0);
            try (DavClient client = new DavClient(new Config(values))) {
                expectLimit(client, "depth limit");
                check(fixture.propfinds.get() == DavClient.MAX_DEPTH + 1, "stop growing alias paths even without file IDs");
            }
            fixture.directoryListing = path -> {
                StringBuilder children = new StringBuilder();
                for (int i = 0; i < DavClient.MAX_FOLDERS; i++) children.append(entry(path + "child" + i + "/", true, "", ""));
                return listing(children.toString());
            };
            fixture.propfinds.set(0);
            try (DavClient client = new DavClient(new Config(values))) {
                expectLimit(client, "Folder limit");
                check(fixture.propfinds.get() == 1, "bound pending traversal as well as completed directory requests");
            }
            fixture.directoryListing = path -> {
                StringBuilder photos = new StringBuilder();
                for (int i = 0; i <= DavClient.MAX_PHOTOS; i++) photos.append(entry(path + i + ".jpg", false, "image/jpeg", ""));
                return listing(photos.toString());
            };
            try (DavClient client = new DavClient(new Config(values))) { expectLimit(client, "Photo limit"); }
            check(fixture.badAuth.get() == 0 && fixture.badDepth.get() == 0, "multiple roots preserve authenticated Depth 1 requests");
        }
    }

    static void expectLimit(DavClient client, String message) throws Exception {
        try { client.list(); throw new AssertionError("Missing traversal limit: " + message); }
        catch (IOException expected) { check(expected.getMessage().contains(message), "specific traversal limit: " + expected.getMessage()); }
    }

    static void webdav(Fixture fixture) throws Exception {
        Map<String, Object> values = settings(fixture.url());
        try (DavClient client = new DavClient(new Config(values))) {
            List<DavClient.Photo> photos = client.list();
            check(photos.size() == 3, "depth-one directory contains three photos");
            check(Arrays.equals(client.download(photos.get(0)), new byte[] {1, 2, 3}), "authenticated GET body");
            fixture.oversizedImage = true;
            try {
                client.download(photos.get(0));
                throw new AssertionError("9 MiB image must exceed download cap");
            } catch (IOException expected) { check(expected.getMessage().contains("size limit"), "reject 9 MiB Content-Length before buffering"); }
            finally { fixture.oversizedImage = false; }
            fixture.mode = 401;
            expectFailure(client::list, "authentication failure");
            fixture.mode = 302;
            expectFailure(client::list, "redirect is not followed");
            check(fixture.redirectHits.get() == 0, "credentials never follow redirect");
            fixture.mode = 200;
            expectFailure(client::list, "login page is not a DAV response");
            fixture.mode = 0;
        }
        values.put("includeSubfolders", true);
        try (DavClient client = new DavClient(new Config(values))) {
            check(client.list().size() == 4, "recursion lists subfolder");
        }
        byte[] big = new byte[33];
        expectFailure(() -> DavClient.read(ResponseBody.create(MediaType.parse("image/jpeg"), big), 32), "enforce response length limit");
        AtomicInteger streamed = new AtomicInteger();
        InputStream stream = new InputStream() {
            private int remaining = 9 * 1024 * 1024;
            @Override public int read() { if (remaining-- <= 0) return -1; streamed.incrementAndGet(); return 0; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int n = Math.min(remaining, length);
                Arrays.fill(bytes, offset, offset + n, (byte) 0);
                remaining -= n; streamed.addAndGet(n); return n;
            }
        };
        ResponseBody chunked = ResponseBody.create(MediaType.parse("image/jpeg"), -1, okio.Okio.buffer(okio.Okio.source(stream)));
        try {
            DavClient.read(chunked, DavClient.MAX_IMAGE);
            throw new AssertionError("9 MiB stream without Content-Length must exceed download cap");
        } catch (IOException expected) { check(expected.getMessage().contains("size limit"), "unknown-length image also stops at 8 MiB"); }
        check(streamed.get() <= 8 * 1024 * 1024 + 8192, "unknown-length response stops reading promptly at the cap");
        check(fixture.badAuth.get() == 0 && fixture.badDepth.get() == 0, "all requests carry correct authentication and depth");
    }

    static void slideshow(Fixture fixture) throws Exception {
        AtomicLong clock = new AtomicLong(1);
        Host host = new Host();
        NextcloudPlugin plugin = new NextcloudPlugin(original -> original, clock::get);
        Map<String, Object> values = settings(fixture.url());
        values.put("shuffle", false); values.put("cachePhotos", 2); values.put("secondsPerPhoto", 5);
        plugin.start(host, values);
        try {
            show(plugin);
            await(() -> host.images.get() >= 1, "first photo after this screensaver starts");
            await(() -> host.status.contains("2 cached"), "prefetch next image");
            int count = host.images.get(), requests = fixture.gets.get();
            plugin.onEvent("ks.screensaver.state", Collections.singletonMap("active", false));
            clock.addAndGet(10_000);
            Thread.sleep(1200);
            check(host.images.get() == count && fixture.gets.get() == requests, "idle slideshow does not advance or keep fetching");
            plugin.onEvent("ks.screensaver.state", Collections.singletonMap("active", true));
            Thread.sleep(1100); // let the worker establish a fresh dwell deadline
            clock.addAndGet(6000);
            await(() -> host.images.get() > count, "active slideshow advances");
            await(() -> host.status.contains("2 cached"), "bounded LRU cache");
            fixture.failImages = true;
            final int cachedBefore = host.images.get();
            plugin.execute("next", Collections.emptyMap());
            await(() -> host.images.get() > cachedBefore, "cached next photo remains available");
            String offlineImage = host.html;
            final int offlineBefore = host.images.get();
            plugin.execute("next", Collections.emptyMap());
            await(() -> host.images.get() > offlineBefore, "offline cache keeps rotating");
            check(!host.html.equals(offlineImage), "offline navigation changes the displayed image");
            plugin.onEvent("ks.screen.state", Collections.singletonMap("on", false));
            int stoppedAt = host.images.get();
            clock.addAndGet(60_000);
            Thread.sleep(1200);
            check(host.images.get() == stoppedAt, "no slideshow advance while screen is off");
            fixture.failImages = false;
            clock.addAndGet(60_000);
            plugin.execute("refresh", Collections.emptyMap());
            plugin.onEvent("ks.screen.state", Collections.singletonMap("on", true));
            await(() -> host.status.startsWith("3 photos available"), "manual refresh recovers connection");
        } finally { fixture.failImages = false; plugin.stop(); }
        int afterStop = host.publishes.get();
        Thread.sleep(1200);
        check(host.publishes.get() == afterStop, "no publish after stop");
    }

    static void lifecycle(Fixture fixture) throws Exception {
        fixture.block = new CountDownLatch(1);
        fixture.entered = new CountDownLatch(1);
        Host host = new Host();
        NextcloudPlugin plugin = new NextcloudPlugin(original -> original, () -> 1L);
        plugin.start(host, settings(fixture.url()));
        show(plugin);
        check(fixture.entered.await(5, TimeUnit.SECONDS), "request started asynchronously");
        long start = System.nanoTime();
        plugin.configure(Collections.emptyMap());
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "configure promptly cancels blocked work");
        int count = host.publishes.get();
        fixture.block.countDown(); fixture.block = null;
        Thread.sleep(1200);
        check(host.publishes.get() == count && host.html.contains("Enter the Nextcloud URL"), "old configuration cannot publish photos or credentials");
        plugin.stop();
        check(!host.html.contains("test-password") && !host.status.contains("test-password"), "secrets never reach renderer or status");
    }

    static void await(BooleanSupplier ready, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(30);
        check(ready.getAsBoolean(), description);
    }

    static void show(NextcloudPlugin plugin) {
        plugin.onEvent("ks.screensaver.state", Collections.singletonMap("active", true));
        plugin.onEvent("ks.screensaver.view", Collections.singletonMap("view", NextcloudPlugin.VIEW));
    }

    static void idleAndCancellation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicLong clock = new AtomicLong(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(original -> original, clock::get);
            plugin.start(host, settings(fixture.url()));
            try {
                plugin.execute("refresh", Collections.emptyMap());
                plugin.execute("next", Collections.emptyMap());
                clock.addAndGet(3_600_000);
                Thread.sleep(1200);
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "no initial load, prefetch, manual work or retries while idle");
                plugin.onEvent("ks.screensaver.state", Collections.singletonMap("active", true));
                plugin.onEvent("ks.screensaver.view", Collections.singletonMap("view", "clock"));
                Thread.sleep(1200);
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "another active screensaver does not start this plugin's work");
                show(plugin);
                await(() -> host.images.get() >= 1 && host.status.contains("2 cached"), "first load and prefetch after renderer becomes visible");
                int listed = fixture.propfinds.get(), gets = fixture.gets.get(), images = host.images.get();
                plugin.onEvent("ks.screensaver.view", Collections.singletonMap("view", "clock"));
                plugin.execute("refresh", Collections.emptyMap());
                plugin.execute("next", Collections.emptyMap());
                clock.addAndGet(3_600_000);
                Thread.sleep(1200);
                check(fixture.propfinds.get() == listed && fixture.gets.get() == gets && host.images.get() == images, "mode switch pauses due refreshes and queued actions");
                show(plugin);
                await(() -> fixture.propfinds.get() > listed && host.images.get() > images, "queued refresh and next resume only when visible");
                fixture.mode = 503;
                plugin.execute("refresh", Collections.emptyMap());
                await(() -> host.status.contains("HTTP 503"), "visible frame reports refresh error");
                plugin.onEvent("ks.screensaver.view", Collections.singletonMap("view", "clock"));
                int failedAt = fixture.propfinds.get();
                clock.addAndGet(120_000);
                Thread.sleep(1200);
                check(fixture.propfinds.get() == failedAt, "30-second failure retry is suspended while another mode is visible");
            } finally { plugin.stop(); }
        }
        try (Fixture fixture = new Fixture()) {
            fixture.block = new CountDownLatch(1);
            fixture.entered = new CountDownLatch(1);
            Host host = new Host();
            NextcloudPlugin plugin = new NextcloudPlugin(original -> original, () -> 1L);
            plugin.start(host, settings(fixture.url()));
            try {
                show(plugin);
                check(fixture.entered.await(5, TimeUnit.SECONDS), "visible request reaches blocking fixture");
                long start = System.nanoTime();
                plugin.onEvent("ks.screensaver.view", Collections.singletonMap("view", "clock"));
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "hiding promptly cancels a blocked HTTP call");
                fixture.block.countDown(); fixture.block = null;
                Thread.sleep(1200);
                check(host.images.get() == 0 && fixture.gets.get() == 0, "canceled listing cannot trigger image downloads while hidden");
                show(plugin);
                await(() -> host.images.get() > 0, "canceling a request does not close the client permanently");
            } finally { if (fixture.block != null) fixture.block.countDown(); plugin.stop(); }
        }
    }

    static void memoryRecovery(boolean processingFailure) throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicLong clock = new AtomicLong(1);
            AtomicInteger encodes = new AtomicInteger();
            Host host = new Host();
            host.failMemoryStatus = !processingFailure;
            host.failProcessingStatus = processingFailure;
            NextcloudPlugin plugin = new NextcloudPlugin(original -> {
                if (encodes.incrementAndGet() == 2) {
                    if (processingFailure) throw new IllegalArgumentException("simulated decoder failure");
                    throw new OutOfMemoryError("simulated decoder pressure");
                }
                return original;
            }, clock::get);
            Map<String, Object> values = settings(fixture.url());
            values.put("shuffle", false);
            plugin.start(host, values);
            try {
                show(plugin);
                await(() -> host.memoryReports.get() == 1, "OOM recovery survives even a second OOM in status reporting");
                int images = host.images.get();
                clock.addAndGet(10_000);
                Thread.sleep(1200);
                check(encodes.get() == 2, "OOM backoff avoids repeated work on every scheduled tick");
                clock.addAndGet(30_000);
                await(() -> host.images.get() > images && host.status.contains("2 cached"), "scheduled slideshow recovers after OOM and rebuilds the cleared cache");
            } finally { plugin.stop(); }
        }
    }

    static Map<String, Object> settings(String server) {
        Map<String, Object> map = new HashMap<>();
        map.put("serverUrl", server); map.put("username", "alice"); map.put("appPassword", "test-password");
        map.put("folder", "Photos"); map.put("allowHttp", true);
        return map;
    }

    static String entry(String href, boolean folder, String type, String etag) {
        return entry(href, folder, type, etag, "");
    }

    static String entry(String href, boolean folder, String type, String etag, String id) {
        return "<d:response><d:href>" + href.replace("&", "&amp;") + "</d:href><d:propstat><d:prop><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype><d:getcontenttype>" + type
                + "</d:getcontenttype><d:getetag>" + etag + "</d:getetag><oc:fileid>" + id
                + "</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    static String listing(String entries) { return "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">" + entries + "</d:multistatus>"; }

    static final class Host implements PluginHost {
        final AtomicInteger images = new AtomicInteger(), publishes = new AtomicInteger();
        final AtomicInteger memoryReports = new AtomicInteger();
        volatile boolean failMemoryStatus;
        volatile boolean failProcessingStatus;
        volatile String html = "", status = "";
        @Override public void subscribe(String event) {}
        @Override public void executeCommand(String command, Map<String, Object> args, CommandCallback callback) {
            callback.onResult(true, command.equals("isScreenOn"), null);
        }
        @Override public void publishScreensaver(String key, String title, String value) {
            html = value; publishes.incrementAndGet();
            if (value.contains("data:image/jpeg;base64,")) images.incrementAndGet();
        }
        @Override public void status(String message, boolean error) {
            if (message.startsWith("Photo processing failed") && failProcessingStatus) {
                memoryReports.incrementAndGet(); failProcessingStatus = false;
                throw new OutOfMemoryError("simulated status pressure after processing error");
            }
            if (message.startsWith("Not enough memory")) {
                memoryReports.incrementAndGet();
                if (failMemoryStatus) { failMemoryStatus = false; throw new OutOfMemoryError("simulated status pressure"); }
            }
            status = message;
        }
        @Override public void showWindow(String title, String message, String button) { throw new AssertionError("no overlay required"); }
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger gets = new AtomicInteger(), badAuth = new AtomicInteger(), badDepth = new AtomicInteger(), redirectHits = new AtomicInteger();
        final AtomicInteger propfinds = new AtomicInteger();
        volatile java.util.function.Function<String, String> directoryListing;
        volatile int mode;
        volatile boolean failImages;
        volatile boolean oversizedImage;
        volatile CountDownLatch block, entered;
        final String root = "/remote.php/dav/files/alice/Photos/";
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
            server.createContext("/", this::handle);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void handle(HttpExchange exchange) throws IOException {
            try {
                if (exchange.getRequestURI().getPath().equals("/redirect-target")) { redirectHits.incrementAndGet(); send(exchange, 200, new byte[0]); return; }
                if (!Credentials.basic("alice", "test-password").equals(exchange.getRequestHeaders().getFirst("Authorization"))) badAuth.incrementAndGet();
                CountDownLatch gate = block;
                if (gate != null) {
                    entered.countDown();
                    try { gate.await(8, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                if (mode != 0) {
                    if (mode == 302) exchange.getResponseHeaders().set("Location", url() + "/redirect-target");
                    send(exchange, mode, "login".getBytes(StandardCharsets.UTF_8)); return;
                }
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("PROPFIND")) {
                    propfinds.incrementAndGet();
                    if (!"1".equals(exchange.getRequestHeaders().getFirst("Depth"))) badDepth.incrementAndGet();
                    String xml = directoryListing != null ? directoryListing.apply(path) : path.equals(root) ? listing(entry(root, true, "", "") + entry(root + "a.jpg", false, "image/jpeg", "a1")
                            + entry(root + "b.png", false, "image/png", "b1") + entry(root + "c.webp", false, "image/webp", "c1")
                            + entry(root + "sub/", true, "", ""))
                            : listing(entry(root + "sub/", true, "", "") + entry(root + "sub/d.jpg", false, "image/jpeg", "d1"));
                    send(exchange, 207, xml.getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("GET")) {
                    gets.incrementAndGet();
                    if (oversizedImage) { exchange.sendResponseHeaders(200, 9 * 1024 * 1024); return; }
                    send(exchange, failImages ? 503 : 200, new byte[] {(byte) (path.contains("a.jpg") ? 1 : path.contains("b.png") ? 2 : 3), 2, 3});
                } else send(exchange, 405, new byte[0]);
            } finally { exchange.close(); }
        }
        void send(HttpExchange exchange, int code, byte[] bytes) throws IOException {
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        @Override public void close() { server.stop(0); }
    }
}
