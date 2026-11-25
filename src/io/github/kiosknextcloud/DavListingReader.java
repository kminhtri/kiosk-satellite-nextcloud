// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import okhttp3.HttpUrl;
import okhttp3.ResponseBody;

/** Stream DAV metadata a response at a time instead of retaining an XML document. */
final class DavListingReader {
    private static final String DAV = "DAV:", OC = "http://owncloud.org/ns";
    private static final Pattern OK = Pattern.compile("HTTP/[^ ]+ 200(?: .*)?");
    private static final int MAX_DEPTH = 64, MAX_TEXT = 16 * 1024;

    static DavClient.Listing read(ResponseBody body, HttpUrl directory, HttpUrl root) throws IOException {
        if (body == null) throw new IOException("Empty response from Nextcloud.");
        if (body.contentLength() > DavClient.MAX_XML) throw new DavClient.ResponseSizeException(DavClient.LIST_SIZE_ERROR);
        return read(body.byteStream(), directory, root);
    }

    static DavClient.Listing read(InputStream input, HttpUrl directory, HttpUrl root) throws IOException {
        try (PushbackReader decoded = new PushbackReader(new InputStreamReader(new LimitedStream(input), StandardCharsets.UTF_8), 1)) {
            int first = decoded.read();
            if (first != -1 && first != '\uFEFF') decoded.unread(first);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader parser = factory.newSAXParser().getXMLReader();
            Handler handler = new Handler(directory, root);
            parser.setContentHandler(handler);
            parser.setErrorHandler(handler);
            parser.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entities are disabled"); });
            // The character guard also rejects declarations if an Android parser lacks a security feature.
            parser.parse(new InputSource(new GuardReader(decoded)));
            return handler.result;
        } catch (ParserConfigurationException | SAXException error) {
            if (error.getCause() instanceof IOException) throw (IOException) error.getCause();
            throw new IOException("Nextcloud returned an invalid WebDAV directory listing.");
        }
    }

    private static void feature(SAXParserFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); }
        catch (ParserConfigurationException | SAXException ignored) { /* character guard and resolver remain active */ }
    }

    private static final class LimitedStream extends FilterInputStream {
        private int count;
        LimitedStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) add(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int received = in.read(bytes, offset, Math.min(length, DavClient.MAX_XML - count + 1));
            if (received > 0) add(received);
            return received;
        }
        private void add(int received) throws IOException {
            count += received;
            if (count > DavClient.MAX_XML) throw new DavClient.ResponseSizeException(DavClient.LIST_SIZE_ERROR);
        }
    }

    private static final class GuardReader extends FilterReader {
        private int doctype, entity;
        GuardReader(Reader input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) inspect((char) value);
            return value;
        }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            int received = in.read(chars, offset, length);
            for (int i = 0; i < received; i++) inspect(chars[offset + i]);
            return received;
        }
        private void inspect(char value) throws IOException {
            char upper = Character.toUpperCase(value);
            doctype = advance("<!DOCTYPE", doctype, upper);
            entity = advance("<!ENTITY", entity, upper);
            if (value == 0 || doctype == 9 || entity == 8)
                throw new IOException("Nextcloud returned an unsafe WebDAV directory listing.");
        }
        private int advance(String token, int position, char value) {
            return value == token.charAt(position) ? position + 1 : value == '<' ? 1 : 0;
        }
    }

    private static final class Handler extends DefaultHandler {
        final DavClient.Listing result = new DavClient.Listing();
        final HttpUrl directory, root;
        final Set<String> urls = new HashSet<>(), ids = new HashSet<>();
        final StringBuilder text = new StringBuilder();
        int depth, responseDepth = -1, groupDepth = -1, propDepth = -1, resourceDepth = -1;
        int field, fieldDepth;
        String href, type, etag, id, groupType, groupEtag, groupId, status;
        boolean success, collection, groupCollection;

        Handler(HttpUrl directory, HttpUrl root) { this.directory = directory; this.root = root; }

        @Override public void startElement(String uri, String name, String qualified, Attributes attributes) throws SAXException {
            if (++depth > MAX_DEPTH) fail("Nextcloud WebDAV XML nesting limit reached (64).");
            if (depth == 1 && !(DAV.equals(uri) && name.equals("multistatus")))
                fail("Nextcloud returned an invalid WebDAV directory listing.");
            if (DAV.equals(uri) && name.equals("response")) {
                if (responseDepth >= 0) fail("Nextcloud returned an invalid WebDAV directory listing.");
                responseDepth = depth; href = type = etag = id = ""; success = collection = false;
            } else if (responseDepth >= 0) {
                if (DAV.equals(uri) && name.equals("href") && depth == responseDepth + 1) capture(1);
                else if (DAV.equals(uri) && name.equals("propstat") && depth == responseDepth + 1) {
                    groupDepth = depth; groupType = groupEtag = groupId = status = ""; groupCollection = false;
                } else if (groupDepth >= 0) {
                    if (DAV.equals(uri) && name.equals("status") && depth == groupDepth + 1) capture(2);
                    else if (DAV.equals(uri) && name.equals("prop") && depth == groupDepth + 1) propDepth = depth;
                    else if (propDepth >= 0) {
                        if (depth == propDepth + 1) {
                            if (DAV.equals(uri) && name.equals("getcontenttype")) capture(3);
                            else if (DAV.equals(uri) && name.equals("getetag")) capture(4);
                            else if (OC.equals(uri) && name.equals("fileid")) capture(5);
                            else if (DAV.equals(uri) && name.equals("resourcetype")) resourceDepth = depth;
                        } else if (resourceDepth >= 0 && depth == resourceDepth + 1 && DAV.equals(uri) && name.equals("collection"))
                            groupCollection = true;
                    }
                }
            }
        }

        private void capture(int kind) { field = kind; fieldDepth = depth; text.setLength(0); }

        @Override public void characters(char[] chars, int offset, int length) throws SAXException {
            if (field == 0) return;
            if (text.length() + length > MAX_TEXT) fail("Nextcloud WebDAV property exceeded the 16384-character text limit.");
            text.append(chars, offset, length);
        }

        @Override public void endElement(String uri, String name, String qualified) throws SAXException {
            if (field != 0 && depth == fieldDepth) {
                String value = text.toString().trim();
                switch (field) {
                    case 1: href = value; break;
                    case 2: status = value; break;
                    case 3: groupType = value; break;
                    case 4: groupEtag = value; break;
                    case 5: groupId = value; break;
                    default: break;
                }
                field = 0; text.setLength(0);
            }
            if (depth == resourceDepth) resourceDepth = -1;
            if (depth == propDepth) propDepth = -1;
            if (depth == groupDepth) {
                if (OK.matcher(status).matches()) {
                    success = true;
                    if (!groupType.isEmpty()) type = groupType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                    if (!groupEtag.isEmpty()) etag = groupEtag;
                    if (!groupId.isEmpty()) id = groupId;
                    collection |= groupCollection;
                }
                groupDepth = -1;
            }
            if (depth == responseDepth) { finishResponse(); responseDepth = -1; }
            depth--;
        }

        private void finishResponse() throws SAXException {
            if (!success) return;
            HttpUrl url = directory.resolve(href);
            if (url == null || !DavClient.within(url, root)) return;
            boolean self = DavClient.descendant(url, directory) && DavClient.pathLength(url) == DavClient.pathLength(directory);
            if (self) { if (collection) result.directoryId = id; return; }
            if (!DavClient.directChild(url, directory)) return;
            if (collection) {
                if (!url.encodedPath().endsWith("/")) url = url.newBuilder().addPathSegment("").build();
                result.folders.add(new DavClient.Folder(url, id, 0));
            } else if (Arrays.asList("image/jpeg", "image/png", "image/webp").contains(type)) {
                if (!urls.add(DavClient.pathKey(url)) || (!id.isEmpty() && !ids.add(id))) return;
                if (result.photos.size() >= DavClient.MAX_PHOTOS) throw new SAXException(new DavClient.PhotoLimitException(true));
                result.photos.add(new DavClient.Photo(url, etag, id, directory));
            }
        }

        private void fail(String message) throws SAXException { throw new SAXException(new IOException(message)); }
        @Override public void error(SAXParseException error) throws SAXException { throw error; }
        @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
    }

    private DavListingReader() {}
}
