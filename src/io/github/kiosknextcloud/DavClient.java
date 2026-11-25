// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** DAV requests stay in selected sources; oversized photos use same-server previews by file ID. */
final class DavClient implements AutoCloseable {
    static final int MAX_XML = 16 * 1024 * 1024, MAX_IMAGE = 8 * 1024 * 1024;
    static final String LIST_SIZE_ERROR = "Nextcloud folder or album listing exceeded the 16 MiB size limit. Select a smaller source.";
    static final String PHOTO_SIZE_ERROR = "Nextcloud photo exceeded the 8 MiB download size limit. Use a smaller JPEG, PNG or WebP copy.";
    static final String PREVIEW_SIZE_ERROR = "Nextcloud photo preview exceeded the 8 MiB download size limit. Use a smaller JPEG, PNG or WebP copy.";
    static final int MAX_FOLDERS = Config.DEFAULT_COLLECTION_LIMIT, MAX_DEPTH = 20, MAX_PHOTOS = 20000;
    static final String PHOTO_LIMIT_ERROR = "Photo limit reached (20000); choose fewer folders or albums.";
    static final class PhotoLimitException extends IOException {
        final boolean singleCollection;
        PhotoLimitException(boolean singleCollection) {
            super(PHOTO_LIMIT_ERROR + (singleCollection ? " A single album or folder exceeds 20000 photos; split it into smaller sources." : ""));
            this.singleCollection = singleCollection;
        }
    }
    static final String PROPERTIES = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:prop>"
            + "<d:resourcetype/><d:getcontenttype/><d:getetag/><oc:fileid/></d:prop></d:propfind>";
    final Config config;
    private final OkHttpClient http;
    private final Object callLock = new Object();
    private Call currentCall;
    private boolean enabled = true, closed;

    static final class Photo {
        final HttpUrl url;
        final String etag, id;
        final HttpUrl source;
        Photo(HttpUrl url, String etag, String id) { this(url, etag, id, null); }
        Photo(HttpUrl url, String etag, String id, HttpUrl source) { this.url = url; this.etag = etag; this.id = id; this.source = source; }
        String key() { return url.toString() + "\n" + etag; }
    }

    static final class Folder {
        final HttpUrl url;
        final String id;
        final int depth;
        final boolean album;
        Folder(HttpUrl url, String id, int depth) { this(url, id, depth, false); }
        Folder(HttpUrl url, String id, int depth, boolean album) { this.url = url; this.id = id; this.depth = depth; this.album = album; }
    }

    static final class Listing {
        final List<Photo> photos = new ArrayList<>();
        final List<Folder> folders = new ArrayList<>();
        String directoryId = "";
    }

    static final class ResponseSizeException extends IOException {
        ResponseSizeException(String message) { super(message); }
    }

    DavClient(Config config) {
        this(config, new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build());
    }

    DavClient(Config config, OkHttpClient http) {
        this.config = config;
        this.http = http;
    }

    List<Photo> list() throws IOException {
        return list(traversal(), Collections.emptyList());
    }

    List<Photo> list(Traversal traversal, List<Photo> seed) throws IOException {
        List<Photo> result = new ArrayList<>();
        Set<String> photoUrls = new HashSet<>(), photoIds = new HashSet<>();
        for (Photo photo : seed) {
            if (!photoUrls.add(pathKey(photo.url))) continue;
            if (!photo.id.isEmpty() && !photoIds.add(photo.id)) continue;
            result.add(photo);
        }
        Batch batch;
        while ((batch = traversal.next()) != null) {
            for (Photo photo : batch.photos) {
                if (!photoUrls.add(pathKey(photo.url))) continue;
                if (!photo.id.isEmpty() && !photoIds.add(photo.id)) continue;
                result.add(photo);
                if (result.size() > MAX_PHOTOS) throw new PhotoLimitException(false);
            }
        }
        return result;
    }

    static final class Batch {
        final Folder folder;
        final List<Photo> photos;
        Batch(Folder folder, List<Photo> photos) { this.folder = folder; this.photos = photos; }
    }

    Traversal traversal() { return new Traversal(); }

    Traversal continuation(Traversal from) {
        Traversal copy = new Traversal();
        copy.pending.clear(); copy.pending.addAll(from.pending);
        copy.scheduledUrls.clear(); copy.scheduledUrls.addAll(from.scheduledUrls);
        copy.scheduledIds.addAll(from.scheduledIds); copy.visitedIds.addAll(from.visitedIds);
        return copy;
    }

    final class Traversal {
        final ArrayDeque<Folder> pending = new ArrayDeque<>();
        final Set<String> scheduledUrls = new HashSet<>(), scheduledIds = new HashSet<>(), visitedIds = new HashSet<>();
        Traversal() {
            for (HttpUrl root : config.folders)
                if (scheduledUrls.add(pathKey(root))) pending.add(new Folder(root, "", 0));
            for (HttpUrl root : config.albums)
                if (scheduledUrls.add(pathKey(root))) pending.add(new Folder(root, "", 0, true));
        }
        Batch next() throws IOException {
            while (!pending.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                Folder current = pending.peek();
                if (!current.album && !current.id.isEmpty() && visitedIds.contains(current.id)) { pending.remove(); continue; }
                Listing listing = readFolder(current);
                String id = listing.directoryId.isEmpty() ? current.id : listing.directoryId;
                if (!current.album && !id.isEmpty() && visitedIds.contains(id)) { pending.remove(); continue; }
                schedule(current, listing);
                if (!current.album && !id.isEmpty()) visitedIds.add(id);
                pending.remove();
                return new Batch(current, listing.photos);
            }
            return null;
        }
        Batch reload(Folder current) throws IOException {
            Listing listing = readFolder(current);
            schedule(current, listing);
            return new Batch(current, listing.photos);
        }
        private void schedule(Folder current, Listing listing) throws IOException {
            if (config.recursive && !current.album) for (Folder child : listing.folders) {
                if (scheduledUrls.contains(pathKey(child.url))) continue;
                if (!child.id.isEmpty() && (visitedIds.contains(child.id) || scheduledIds.contains(child.id)
                        || child.id.equals(current.id) || child.id.equals(listing.directoryId))) continue;
                if (current.depth >= MAX_DEPTH)
                    throw new IOException("Subfolder depth limit reached (20); select the deeper folder directly or disable subfolders.");
                if (scheduledUrls.size() >= config.maxCollections)
                    throw new IOException("Folder limit reached (" + config.maxCollections
                            + " collections). Increase Folder and album scan limit, select narrower folders, or disable subfolders.");
                scheduledUrls.add(pathKey(child.url));
                if (!child.id.isEmpty()) scheduledIds.add(child.id);
                pending.add(new Folder(child.url, child.id, current.depth + 1));
            }
        }
    }

    private Listing readFolder(Folder current) throws IOException {
        Request request = request(current.url).header("Depth", "1")
                .method("PROPFIND", RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), PROPERTIES)).build();
        Call call = newCall(request);
        try (Response response = call.execute()) {
            if (current.album && response.code() == 404)
                throw new IOException("Nextcloud album was not found. Check its exact name and that the Photos app is enabled.");
            require(response, 207);
            return DavListingReader.read(response.body(), current.url, current.url);
        } catch (InterruptedIOException error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            throw timeout(current.album ? "listing an album" : "listing a folder", error);
        } finally { finished(call); }
    }

    byte[] download(Photo photo) throws IOException {
        boolean allowed = false;
        for (HttpUrl root : config.folders) allowed |= within(photo.url, root);
        // Use the album's DAV href directly, never an original-path or remote
        // downloadURL property that could escape the selected collection.
        for (HttpUrl root : config.albums) allowed |= within(photo.url, root) && directChild(photo.url, root);
        if (!allowed) throw new IOException("Photo address is outside the configured folders and albums.");
        try {
            return download(photo.url, PHOTO_SIZE_ERROR, false);
        } catch (ResponseSizeException error) {
            // Keep the original and preview transfers bounded independently.
            // Only use the file ID from this selected DAV entry, never a server-supplied preview URL.
            if (!photo.id.matches("[1-9][0-9]{0,18}"))
                throw new IOException(PHOTO_SIZE_ERROR + " No usable Nextcloud file ID is available for a preview.");
            HttpUrl preview = config.preview.newBuilder().addQueryParameter("fileId", photo.id)
                    .addQueryParameter("x", "1920").addQueryParameter("y", "1920").addQueryParameter("a", "1")
                    .addQueryParameter("mode", "cover").addQueryParameter("forceIcon", "0").addQueryParameter("mimeFallback", "0").build();
            return download(preview, PREVIEW_SIZE_ERROR, true);
        }
    }

    private byte[] download(HttpUrl url, String sizeError, boolean preview) throws IOException {
        Call call = newCall(request(url).get().build());
        try (Response response = call.execute()) {
            if (preview && response.code() == 404)
                throw new IOException("Nextcloud photo exceeds 8 MiB and no preview is available. Enable image previews on Nextcloud or use a smaller copy.");
            require(response, 200);
            if (preview && !Arrays.asList("image/jpeg", "image/png", "image/webp").contains(
                    response.header("Content-Type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT)))
                throw new IOException("Nextcloud did not return a supported photo preview. Enable image previews or use a smaller copy.");
            return read(response.body(), MAX_IMAGE, sizeError);
        } catch (InterruptedIOException error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            throw timeout(preview ? "loading a photo preview" : "downloading a photo", error);
        } finally { finished(call); }
    }

    private static IOException timeout(String operation, InterruptedIOException cause) {
        return new IOException("Nextcloud timed out while " + operation
                + ". The server took too long to respond or finish the transfer. Retrying in 30 seconds.", cause);
    }

    void setActive(boolean active) {
        synchronized (callLock) {
            enabled = active && !closed;
            if (!enabled && currentCall != null) currentCall.cancel();
        }
    }

    private Call newCall(Request request) throws IOException {
        synchronized (callLock) {
            if (!enabled || closed) throw new InterruptedIOException("Photo frame is inactive.");
            // Register before execute(), so a pause cannot miss a call that has
            // not reached OkHttp's dispatcher yet. This client has one worker.
            currentCall = http.newCall(request);
            return currentCall;
        }
    }

    private void finished(Call call) {
        synchronized (callLock) { if (currentCall == call) currentCall = null; }
    }

    private Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("Authorization", config.authorization)
                .header("User-Agent", "KioskSatellite-NextcloudPhotoFrame/" + BuildInfo.VERSION);
    }

    private static void require(Response response, int expected) throws IOException {
        int code = response.code();
        if (code == expected) return;
        if (code == 401 || code == 403) throw new IOException("Nextcloud rejected access. Check the app password and folder or album permissions.");
        if (code == 404) throw new IOException("Nextcloud folder, album or photo was not found.");
        if (code >= 300 && code < 400) throw new IOException("Nextcloud redirected the request. Enter its final server URL.");
        throw new IOException("Nextcloud returned HTTP " + code + ".");
    }

    static byte[] read(ResponseBody body, int limit) throws IOException {
        return read(body, limit, "Nextcloud response exceeded the size limit.");
    }

    static byte[] read(ResponseBody body, int limit, String sizeError) throws IOException {
        if (body == null) throw new IOException("Empty response from Nextcloud.");
        if (body.contentLength() > limit) throw new ResponseSizeException(sizeError);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (InputStream in = body.byteStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (result.size() + count > limit) throw new ResponseSizeException(sizeError);
                result.write(buffer, 0, count);
            }
        }
        return result.toByteArray();
    }

    static Listing parse(byte[] xml, HttpUrl directory, HttpUrl root) throws IOException {
        return DavListingReader.read(new ByteArrayInputStream(xml), directory, root);
    }

    static boolean within(HttpUrl url, HttpUrl root) {
        if (!url.scheme().equals(root.scheme()) || !url.host().equals(root.host()) || url.port() != root.port()
                || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null)
            return false;
        for (String segment : url.pathSegments())
            if (segment.contains("/") || segment.contains("\\") || segment.equals(".") || segment.equals("..")) return false;
        return descendant(url, root);
    }

    static boolean directChild(HttpUrl url, HttpUrl directory) {
        return descendant(url, directory) && pathLength(url) == pathLength(directory) + 1;
    }

    static int pathLength(HttpUrl url) {
        int length = url.pathSize();
        return length > 0 && url.pathSegments().get(length - 1).isEmpty() ? length - 1 : length;
    }

    static String pathKey(HttpUrl url) {
        // Paths have already passed within(): decoded segments contain no slash.
        // This also treats equivalent percent encodings and trailing slashes alike.
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < pathLength(url); i++) key.append('/').append(url.pathSegments().get(i));
        return key.toString();
    }

    static boolean descendant(HttpUrl url, HttpUrl directory) {
        int length = pathLength(directory);
        if (pathLength(url) < length) return false;
        for (int i = 0; i < length; i++)
            if (!url.pathSegments().get(i).equals(directory.pathSegments().get(i))) return false;
        return true;
    }

    @Override public void close() {
        synchronized (callLock) { closed = true; setActive(false); }
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
}
