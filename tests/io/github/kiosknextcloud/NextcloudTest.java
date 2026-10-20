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
        try (Fixture fixture = new Fixture()) {
            webdav(fixture);
            slideshow(fixture);
            lifecycle(fixture);
        }
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

    static void webdav(Fixture fixture) throws Exception {
        Map<String, Object> values = settings(fixture.url());
        try (DavClient client = new DavClient(new Config(values))) {
            List<DavClient.Photo> photos = client.list();
            check(photos.size() == 3, "depth-one directory contains three photos");
            check(Arrays.equals(client.download(photos.get(0)), new byte[] {1, 2, 3}), "authenticated GET body");
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
            await(() -> host.images.get() >= 1, "first photo while screensaver idle");
            await(() -> host.status.contains("2 cached"), "prefetch next image");
            int count = host.images.get(), requests = fixture.gets.get();
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

    static Map<String, Object> settings(String server) {
        Map<String, Object> map = new HashMap<>();
        map.put("serverUrl", server); map.put("username", "alice"); map.put("appPassword", "test-password");
        map.put("folder", "Photos"); map.put("allowHttp", true);
        return map;
    }

    static String entry(String href, boolean folder, String type, String etag) {
        return "<d:response><d:href>" + href.replace("&", "&amp;") + "</d:href><d:propstat><d:prop><d:resourcetype>"
                + (folder ? "<d:collection/>" : "") + "</d:resourcetype><d:getcontenttype>" + type
                + "</d:getcontenttype><d:getetag>" + etag + "</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    static String listing(String entries) { return "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" + entries + "</d:multistatus>"; }

    static final class Host implements PluginHost {
        final AtomicInteger images = new AtomicInteger(), publishes = new AtomicInteger();
        volatile String html = "", status = "";
        @Override public void subscribe(String event) {}
        @Override public void executeCommand(String command, Map<String, Object> args, CommandCallback callback) {
            callback.onResult(true, command.equals("isScreenOn"), null);
        }
        @Override public void publishScreensaver(String key, String title, String value) {
            html = value; publishes.incrementAndGet();
            if (value.contains("data:image/jpeg;base64,")) images.incrementAndGet();
        }
        @Override public void status(String message, boolean error) { status = message; }
        @Override public void showWindow(String title, String message, String button) { throw new AssertionError("no overlay required"); }
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger gets = new AtomicInteger(), badAuth = new AtomicInteger(), badDepth = new AtomicInteger(), redirectHits = new AtomicInteger();
        volatile int mode;
        volatile boolean failImages;
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
                    if (!"1".equals(exchange.getRequestHeaders().getFirst("Depth"))) badDepth.incrementAndGet();
                    String xml = path.equals(root) ? listing(entry(root, true, "", "") + entry(root + "a.jpg", false, "image/jpeg", "a1")
                            + entry(root + "b.png", false, "image/png", "b1") + entry(root + "c.webp", false, "image/webp", "c1")
                            + entry(root + "sub/", true, "", ""))
                            : listing(entry(root + "sub/", true, "", "") + entry(root + "sub/d.jpg", false, "image/jpeg", "d1"));
                    send(exchange, 207, xml.getBytes(StandardCharsets.UTF_8));
                } else if (exchange.getRequestMethod().equals("GET")) {
                    gets.incrementAndGet();
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
