// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import okhttp3.HttpUrl;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class LargeLibraryTest {
    private static final byte[] START = ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">").getBytes(StandardCharsets.UTF_8);
    private static final byte[] END = "</d:multistatus>".getBytes(StandardCharsets.UTF_8);
    private static final String PADDING = String.join("", Collections.nCopies(120, "p"));

    static void run() throws Exception {
        largeAlbum();
        streamedSizeLimit();
        parserBoundaries();
    }

    // Runs separately with a constrained JVM heap. This models metadata parsing, not total Android app RAM.
    public static void main(String[] args) throws Exception {
        HttpUrl root = HttpUrl.get("https://cloud.example/remote.php/dav/photos/alice/albums/Library/");
        GeneratedListing input = new GeneratedListing(root.encodedPath(), 20_000, false);
        DavClient.Listing result = DavListingReader.read(input, root, root);
        check(result.photos.size() == 20_000 && input.consumed > 4 * 1024 * 1024,
                "parse a 20000-photo listing larger than the old XML cap within a bounded heap");
        check(input.closed, "streamed listing input is closed after parsing");
        System.out.println("20000-photo metadata parsing passed with a 64 MiB JVM heap (not an Android app memory measurement).");
    }

    private static void largeAlbum() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger count = new AtomicInteger(20_000);
            AtomicBoolean duplicateLast = new AtomicBoolean();
            fixture.streamedListing = path -> new GeneratedListing(path, count.get(), duplicateLast.get());
            Map<String, Object> values = settings(fixture.url());
            values.put("photoSource", "Albums");
            values.put("albums", Collections.singletonList(Collections.singletonMap("name", "Library")));
            try (DavClient client = new DavClient(new Config(values))) {
                List<DavClient.Photo> photos = client.list();
                check(photos.size() == 20_000, "one album supports exactly 20000 unique photos over chunked HTTP");
                check(fixture.gets.get() == 0, "listing a large album downloads metadata only");
                check(photos.get(19_999).id.equals("20000"), "streamed parsing retains the last photo's file ID");
                count.set(20_001); duplicateLast.set(true);
                check(client.list().size() == 20_000, "duplicate file IDs do not consume the unique photo allowance");
                duplicateLast.set(false);
                expectLimit(client, "Photo limit reached (20000)");
                check(fixture.badAuth.get() == 0 && fixture.badDepth.get() == 0 && fixture.badAgent.get() == 0,
                        "large streamed listings preserve auth, Depth 1 and versioned User-Agent");
            }
        }
    }

    private static void streamedSizeLimit() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        InputStream padding = new InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return ' '; }
            @Override public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) ' ');
                consumed.addAndGet(length); return length;
            }
        };
        HttpUrl root = HttpUrl.get("https://cloud.example/Photos/");
        InputStream xml = new SequenceInputStream(new ByteArrayInputStream(START), padding);
        try { DavListingReader.read(xml, root, root); throw new AssertionError("infinite listing must stop at byte cap"); }
        catch (IOException expected) { check(DavClient.LIST_SIZE_ERROR.equals(expected.getMessage()), "unknown-length XML stops at the 16 MiB listing limit"); }
        check(consumed.get() <= DavClient.MAX_XML + 8192, "oversized XML is not fully buffered before rejection");
    }

    private static void parserBoundaries() throws Exception {
        HttpUrl root = HttpUrl.get("https://cloud.example/Photos/");
        String partial = "<response xmlns=\"DAV:\"><href>/Photos/été%20%26%20family.jpg</href>"
                + "<propstat><status>HTTP/1.1 404 Not Found</status><prop><resourcetype><collection/></resourcetype></prop></propstat>"
                + "<propstat><status>HTTP/1.1 200 OK</status><prop><getcontenttype>image/jpeg; charset=utf-8</getcontenttype>"
                + "<getetag><![CDATA[v1 & v2]]></getetag><n:fileid xmlns:n=\"http://owncloud.org/ns\">123</n:fileid></prop></propstat></response>";
        byte[] valid = listing(partial).getBytes(StandardCharsets.UTF_8);
        InputStream split = new ByteArrayInputStream(valid) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(length, 1)); }
        };
        DavClient.Listing parsed = DavListingReader.read(split, root, root);
        check(parsed.photos.size() == 1 && parsed.folders.isEmpty(), "streaming supports default namespaces, split UTF-8 and status-before-properties while ignoring failed groups");
        check(parsed.photos.get(0).etag.equals("v1 & v2") && parsed.photos.get(0).id.equals("123"), "streamed properties preserve CDATA, ampersands and alternate namespace prefixes");
        for (String unsafe : Arrays.asList("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>",
                "<!DOCTYPE d:multistatus [<!ENTITY e 'expanded'>]>" + listing(""), listing("\0"))) {
            byte[] data = unsafe.getBytes(StandardCharsets.UTF_8);
            InputStream chunks = new ByteArrayInputStream(data) {
                @Override public synchronized int read(byte[] bytes, int offset, int length) { return super.read(bytes, offset, Math.min(length, 1)); }
            };
            expectFailure(() -> DavListingReader.read(chunks, root, root), "reject XML declarations and nulls across streaming boundaries");
        }
        String nested = String.join("", Collections.nCopies(65, "<d:x>")) + String.join("", Collections.nCopies(65, "</d:x>"));
        expectFailure(() -> DavClient.parse(listing(nested).getBytes(StandardCharsets.UTF_8), root, root), "reject excessive XML nesting");
        String oversizedText = entry("/Photos/a.jpg", false, "image/jpeg", String.join("", Collections.nCopies(16 * 1024 + 1, "x")));
        expectFailure(() -> DavClient.parse(listing(oversizedText).getBytes(StandardCharsets.UTF_8), root, root), "bound a single streamed property");
        expectFailure(() -> DavClient.parse("<html/>".getBytes(StandardCharsets.UTF_8), root, root), "reject a non-DAV XML root");
    }

    static final class GeneratedListing extends InputStream {
        final String path;
        final int count;
        final boolean duplicateLast;
        final int idOffset;
        byte[] chunk = START;
        int position, index = -1, consumed;
        boolean closed;
        GeneratedListing(String path, int count, boolean duplicateLast) { this(path, count, duplicateLast, 0); }
        GeneratedListing(String path, int count, boolean duplicateLast, int idOffset) {
            this.path = path; this.count = count; this.duplicateLast = duplicateLast; this.idOffset = idOffset;
        }
        private boolean availableChunk() {
            if (chunk == null) return false;
            while (position == chunk.length) {
                position = 0;
                if (index < 0) chunk = entry(path, true, "", "", "root").getBytes(StandardCharsets.UTF_8);
                else if (index < count) chunk = entry(path + index + "-" + PADDING + ".jpg", false, "image/jpeg", "v1",
                        duplicateLast && index == count - 1 ? Integer.toString(idOffset + 1) : Integer.toString(idOffset + index + 1)).getBytes(StandardCharsets.UTF_8);
                else if (index == count) chunk = END;
                else { chunk = null; return false; }
                index++;
            }
            return true;
        }
        @Override public int read() {
            if (!availableChunk()) return -1;
            consumed++; return chunk[position++] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) {
            if (length == 0) return 0;
            int copied = 0;
            while (copied < length && availableChunk()) {
                int size = Math.min(length - copied, chunk.length - position);
                System.arraycopy(chunk, position, bytes, offset + copied, size);
                position += size; copied += size;
            }
            consumed += copied;
            return copied == 0 ? -1 : copied;
        }
        @Override public void close() { closed = true; }
    }

    private LargeLibraryTest() {}
}
