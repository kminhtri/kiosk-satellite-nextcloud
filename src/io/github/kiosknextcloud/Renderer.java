// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import okio.ByteString;

final class Renderer {
    private static final String HEAD = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
            + "img{display:block;width:100%;height:100%}p{box-sizing:border-box;margin:0;display:flex;align-items:center;"
            + "justify-content:center;height:100%;padding:8vw;color:#ddd;font:20px sans-serif;text-align:center}</style></head><body>";

    static String photo(byte[] jpeg, boolean fill) {
        if (jpeg.length > AndroidImageEncoder.MAX_JPEG) throw new IllegalArgumentException("Embedded photo is too large");
        return HEAD + "<img alt=\"\" style=\"object-fit:" + (fill ? "cover" : "contain")
                + "\" src=\"data:image/jpeg;base64," + ByteString.of(jpeg).base64() + "\"></body></html>";
    }

    static String message(String text) {
        return HEAD + "<p>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;") + "</p></body></html>";
    }
}
