// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import okio.ByteString;

final class Renderer {
    private static final String HEAD = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
            + "img{display:block;width:100%;height:100%}p{box-sizing:border-box;margin:0;display:flex;align-items:center;"
            + "justify-content:center;height:100%;padding:8vw;color:#ddd;font:20px sans-serif;text-align:center}"
            + ".source{position:absolute;left:20px;right:20px;bottom:16px;color:#fff;font:14px/1.4 sans-serif;"
            + "text-shadow:0 1px 4px #000,0 0 8px #000;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;pointer-events:none}"
            + "</style></head><body>";

    static String photo(byte[] jpeg, boolean fill) {
        return photo(jpeg, fill, "");
    }

    static String photo(byte[] jpeg, boolean fill, String sourceName) {
        if (jpeg.length > AndroidImageEncoder.MAX_JPEG) throw new IllegalArgumentException("Embedded photo is too large");
        // Keep even unusual DAV names well inside the host's 512 KiB HTML limit.
        int end = sourceName.offsetByCodePoints(0, Math.min(256, sourceName.codePointCount(0, sourceName.length())));
        String caption = sourceName.isEmpty() ? "" : "<div class=\"source\">" + escape(sourceName.substring(0, end)) + "</div>";
        return HEAD + "<img alt=\"\" style=\"object-fit:" + (fill ? "cover" : "contain")
                + "\" src=\"data:image/jpeg;base64," + ByteString.of(jpeg).base64() + "\">" + caption + "</body></html>";
    }

    static String message(String text) {
        return HEAD + "<p>" + escape(text) + "</p></body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
