// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import okhttp3.HttpUrl;
import okhttp3.Credentials;

final class Config {
    final HttpUrl folder, preview;
    final List<HttpUrl> folders;
    final String authorization;
    final boolean recursive, shuffle, fill;
    final int seconds, refreshMinutes, cachePhotos;

    Config(Map<String, Object> values) {
        String server = text(values, "serverUrl", "");
        String username = text(values, "username", "");
        String password = values.get("appPassword") instanceof String ? (String) values.get("appPassword") : "";
        if (server.isEmpty() || username.isEmpty() || password.isEmpty())
            throw new IllegalArgumentException("Enter the Nextcloud URL, username and app password in Plugin Manager.");
        HttpUrl base = HttpUrl.parse(server);
        if (base == null || !base.username().isEmpty() || !base.password().isEmpty()
                || base.query() != null || base.fragment() != null)
            throw new IllegalArgumentException("Enter a server URL without credentials, query or fragment.");
        if (!base.isHttps() && !Boolean.TRUE.equals(values.get("allowHttp")))
            throw new IllegalArgumentException("Use HTTPS, or explicitly enable Allow unencrypted HTTP.");
        HttpUrl.Builder url = base.newBuilder();
        // Keep an installation subpath, removing only its trailing empty segment.
        if (base.pathSegments().get(base.pathSize() - 1).isEmpty()) url.removePathSegment(base.pathSize() - 1);
        preview = url.build().newBuilder().addPathSegment("index.php").addPathSegment("core").addPathSegment("preview").build();
        url.addPathSegment("remote.php").addPathSegment("dav").addPathSegment("files").addPathSegment(username);
        LinkedHashSet<HttpUrl> selected = new LinkedHashSet<>();
        folder = folderUrl(url, text(values, "folder", "Photos"));
        selected.add(folder);
        Object additional = values.get("additionalFolders");
        if (additional != null) {
            if (!(additional instanceof List) || ((List<?>) additional).size() > 31)
                throw new IllegalArgumentException("Select at most 31 additional photo folders.");
            for (Object row : (List<?>) additional) {
                Object path = row instanceof Map ? ((Map<?, ?>) row).get("path") : null;
                if (!(path instanceof String) || ((String) path).trim().isEmpty())
                    throw new IllegalArgumentException("Enter a path for each additional photo folder.");
                selected.add(folderUrl(url, ((String) path).trim()));
            }
        }
        folders = Collections.unmodifiableList(new ArrayList<>(selected));
        authorization = Credentials.basic(username, password, java.nio.charset.StandardCharsets.UTF_8);
        recursive = Boolean.TRUE.equals(values.get("includeSubfolders"));
        shuffle = !Boolean.FALSE.equals(values.get("shuffle"));
        fill = "Fill".equals(values.get("fit"));
        seconds = number(values, "secondsPerPhoto", 30, 5, 600);
        refreshMinutes = number(values, "refreshMinutes", 15, 1, 1440);
        cachePhotos = number(values, "cachePhotos", 12, 2, 30);
    }

    private static HttpUrl folderUrl(HttpUrl.Builder home, String path) {
        HttpUrl.Builder url = home.build().newBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0)
                throw new IllegalArgumentException("The photo folder must not contain dot segments or backslashes.");
            url.addPathSegment(part);
        }
        return url.addPathSegment("").build();
    }

    static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? ((String) value).trim() : fallback;
    }

    static int number(Map<String, Object> values, String key, int fallback, int min, int max) {
        Object value = values.get(key);
        double n = value instanceof Number ? ((Number) value).doubleValue() : fallback;
        return Double.isNaN(n) || Double.isInfinite(n) ? fallback : (int) Math.max(min, Math.min(max, n));
    }
}
