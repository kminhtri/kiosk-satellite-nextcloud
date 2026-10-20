// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import okhttp3.*;

/** All requests stay within the selected folder and never follow redirects with credentials. */
final class DavClient implements AutoCloseable {
    static final int MAX_XML = 4 * 1024 * 1024, MAX_IMAGE = 24 * 1024 * 1024;
    static final String PROPERTIES = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:resourcetype/><d:getcontenttype/><d:getetag/></d:prop></d:propfind>";
    final Config config;
    private final OkHttpClient http;

    static final class Photo {
        final HttpUrl url;
        final String etag;
        Photo(HttpUrl url, String etag) { this.url = url; this.etag = etag; }
        String key() { return url.toString() + "\n" + etag; }
    }

    static final class Listing {
        final List<Photo> photos = new ArrayList<>();
        final List<HttpUrl> folders = new ArrayList<>();
    }

    DavClient(Config config) {
        this.config = config;
        http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    List<Photo> list() throws IOException {
        LinkedHashMap<String, Photo> result = new LinkedHashMap<>();
        ArrayDeque<HttpUrl> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(config.folder);
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            HttpUrl directory = pending.remove();
            if (!visited.add(directory.toString())) continue;
            if (visited.size() > 100) throw new IOException("Folder limit reached (100); choose a smaller folder.");
            Request request = request(directory).header("Depth", "1")
                    .method("PROPFIND", RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), PROPERTIES)).build();
            Listing listing;
            try (Response response = http.newCall(request).execute()) {
                require(response, 207);
                listing = parse(read(response.body(), MAX_XML), directory, config.folder);
            }
            for (Photo photo : listing.photos) {
                result.put(photo.url.toString(), photo);
                if (result.size() > 5000) throw new IOException("Photo limit reached (5000); choose a smaller folder.");
            }
            if (config.recursive) pending.addAll(listing.folders);
        }
        return new ArrayList<>(result.values());
    }

    byte[] download(Photo photo) throws IOException {
        if (!within(photo.url, config.folder)) throw new IOException("Photo address is outside the configured folder.");
        try (Response response = http.newCall(request(photo.url).get().build()).execute()) {
            require(response, 200);
            return read(response.body(), MAX_IMAGE);
        }
    }

    private Request.Builder request(HttpUrl url) {
        return new Request.Builder().url(url).header("Authorization", config.authorization)
                .header("User-Agent", "KioskSatellite-NextcloudPhotos/0.1.0");
    }

    private static void require(Response response, int expected) throws IOException {
        int code = response.code();
        if (code == expected) return;
        if (code == 401 || code == 403) throw new IOException("Nextcloud rejected access. Check the app password and folder permissions.");
        if (code == 404) throw new IOException("Nextcloud folder or photo was not found.");
        if (code >= 300 && code < 400) throw new IOException("Nextcloud redirected the request. Enter its final server URL.");
        throw new IOException("Nextcloud returned HTTP " + code + ".");
    }

    static byte[] read(ResponseBody body, int limit) throws IOException {
        if (body == null) throw new IOException("Empty response from Nextcloud.");
        if (body.contentLength() > limit) throw new IOException("Nextcloud response exceeded the size limit.");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (InputStream in = body.byteStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (result.size() + count > limit) throw new IOException("Nextcloud response exceeded the size limit.");
                result.write(buffer, 0, count);
            }
        }
        return result.toByteArray();
    }

    static Listing parse(byte[] xml, HttpUrl directory, HttpUrl root) throws IOException {
        try {
            String source = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            String upper = source.toUpperCase(Locale.ROOT);
            if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY") || source.indexOf('\0') >= 0)
                throw new IOException("Nextcloud returned an unsafe WebDAV directory listing.");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Android and the JDK expose different optional XML security features.
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entities are disabled"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            });
            Document document = builder.parse(new org.xml.sax.InputSource(new StringReader(source)));
            Listing result = new Listing();
            NodeList responses = document.getElementsByTagNameNS("DAV:", "response");
            for (int i = 0; i < responses.getLength(); i++) {
                Element response = (Element) responses.item(i);
                HttpUrl url = directory.resolve(value(response, "href"));
                if (url == null || !within(url, root) || !directChild(url, directory)) continue;
                NodeList propstats = response.getElementsByTagNameNS("DAV:", "propstat");
                String type = "", etag = "";
                boolean collection = false, success = false;
                for (int j = 0; j < propstats.getLength(); j++) {
                    Element propstat = (Element) propstats.item(j);
                    if (!value(propstat, "status").matches("HTTP/[^ ]+ 200(?: .*)?")) continue;
                    success = true;
                    String candidate = value(propstat, "getcontenttype");
                    if (!candidate.isEmpty()) type = candidate.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    candidate = value(propstat, "getetag");
                    if (!candidate.isEmpty()) etag = candidate;
                    collection |= propstat.getElementsByTagNameNS("DAV:", "collection").getLength() > 0;
                }
                if (!success) continue;
                if (collection) {
                    if (!url.encodedPath().endsWith("/")) url = url.newBuilder().addPathSegment("").build();
                    result.folders.add(url);
                } else if (Arrays.asList("image/jpeg", "image/png", "image/webp").contains(type)) {
                    result.photos.add(new Photo(url, etag));
                }
            }
            return result;
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Nextcloud returned an invalid WebDAV directory listing.");
        }
    }

    private static void feature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (ParserConfigurationException ignored) { /* resolver still denies external entities */ }
    }

    private static String value(Element element, String name) {
        NodeList list = element.getElementsByTagNameNS("DAV:", name);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    static boolean within(HttpUrl url, HttpUrl root) {
        if (!url.scheme().equals(root.scheme()) || !url.host().equals(root.host()) || url.port() != root.port()
                || !url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null)
            return false;
        for (String segment : url.pathSegments())
            if (segment.contains("/") || segment.contains("\\") || segment.equals(".") || segment.equals("..")) return false;
        return descendant(url, root);
    }

    private static boolean directChild(HttpUrl url, HttpUrl directory) {
        return descendant(url, directory) && pathLength(url) == pathLength(directory) + 1;
    }

    private static int pathLength(HttpUrl url) {
        int length = url.pathSize();
        return length > 0 && url.pathSegments().get(length - 1).isEmpty() ? length - 1 : length;
    }

    private static boolean descendant(HttpUrl url, HttpUrl directory) {
        int length = pathLength(directory);
        if (pathLength(url) < length) return false;
        for (int i = 0; i < length; i++)
            if (!url.pathSegments().get(i).equals(directory.pathSegments().get(i))) return false;
        return true;
    }

    @Override public void close() {
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }
}
