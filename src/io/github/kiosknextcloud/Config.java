// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import okhttp3.HttpUrl;
import okhttp3.Credentials;

final class Config {
    final HttpUrl folder, preview;
    final List<HttpUrl> folders, albums;
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
        url.addPathSegment("remote.php").addPathSegment("dav");
        HttpUrl dav = url.build();
        String source = text(values, "photoSource", "Folders");
        boolean useFolders = source.equals("Folders") || source.equals("Folders and albums");
        boolean useAlbums = source.equals("Albums") || source.equals("Folders and albums");
        if (!useFolders && !useAlbums)
            throw new IllegalArgumentException("Choose Folders, Albums or Folders and albums as the photo source.");
        LinkedHashSet<HttpUrl> selected = new LinkedHashSet<>();
        if (useFolders) {
            HttpUrl.Builder home = dav.newBuilder().addPathSegment("files").addPathSegment(username);
            selected.add(folderUrl(home, text(values, "folder", "Photos")));
            Object additional = values.get("additionalFolders");
            if (additional != null) {
                if (!(additional instanceof List) || ((List<?>) additional).size() > 31)
                    throw new IllegalArgumentException("Select at most 31 additional photo folders.");
                for (Object row : (List<?>) additional) {
                    Object path = row instanceof Map ? ((Map<?, ?>) row).get("path") : null;
                    if (!(path instanceof String) || ((String) path).trim().isEmpty())
                        throw new IllegalArgumentException("Enter a path for each additional photo folder.");
                    selected.add(folderUrl(home, ((String) path).trim()));
                }
            }
        }
        folders = Collections.unmodifiableList(new ArrayList<>(selected));
        folder = folders.isEmpty() ? null : folders.get(0);
        selected.clear();
        if (useAlbums) {
            Object rows = values.get("albums");
            if (!(rows instanceof List) || ((List<?>) rows).isEmpty() || ((List<?>) rows).size() > 32)
                throw new IllegalArgumentException("Add between 1 and 32 album names in Plugin Manager.");
            HttpUrl home = dav.newBuilder().addPathSegment("photos").addPathSegment(username).addPathSegment("albums").build();
            for (Object row : (List<?>) rows) {
                Object value = row instanceof Map ? ((Map<?, ?>) row).get("name") : null;
                String name = value instanceof String ? ((String) value).trim() : "";
                if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
                    throw new IllegalArgumentException("Enter each album's exact name, without slashes. Do not paste a URL or folder path.");
                selected.add(home.newBuilder().addPathSegment(name).addPathSegment("").build());
            }
        }
        albums = Collections.unmodifiableList(new ArrayList<>(selected));
        if (folders.size() + albums.size() > 32)
            throw new IllegalArgumentException("Select at most 32 folders and albums in total.");
        authorization = Credentials.basic(username, password, java.nio.charset.StandardCharsets.UTF_8);
        recursive = useFolders && Boolean.TRUE.equals(values.get("includeSubfolders"));
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
