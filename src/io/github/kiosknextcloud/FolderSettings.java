// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;

/** Imports legacy primary/additional folders once; an explicitly emptied new list stays empty. */
final class FolderSettings {
    static boolean migrated(Map<String, Object> settings) {
        Object version = settings.get("folderListVersion");
        return version instanceof Number && ((Number) version).intValue() == 1;
    }

    static List<String> paths(Map<String, Object> settings) {
        Object folders = settings.get("folders");
        boolean useList = migrated(settings) || (folders != null && (!(folders instanceof List) || !((List<?>) folders).isEmpty()));
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (useList) {
            addRows(paths, folders, 32);
        } else {
            paths.add(normalize(Config.text(settings, "folder", "Photos"), true));
            Object extra = settings.get("additionalFolders");
            if (extra != null) addRows(paths, extra, 31);
        }
        return new ArrayList<>(paths);
    }

    static Map<String, Object> migration(Map<String, Object> settings) {
        if (migrated(settings)) return Collections.emptyMap();
        List<Map<String, String>> rows = new ArrayList<>();
        for (String path : paths(settings)) rows.add(Collections.singletonMap("path", path));
        Map<String, Object> patch = new HashMap<>();
        patch.put("folders", rows);
        patch.put("folderListVersion", 1);
        return patch;
    }

    private static void addRows(Set<String> paths, Object value, int maximum) {
        if (!(value instanceof List) || ((List<?>) value).size() > maximum)
            throw new IllegalArgumentException("Select at most " + maximum + " photo folders in this list.");
        for (Object row : (List<?>) value) {
            Object path = row instanceof Map ? ((Map<?, ?>) row).get("path") : null;
            if (!(path instanceof String)) throw new IllegalArgumentException("Enter a path for each photo folder.");
            paths.add(normalize((String) path, false));
        }
    }

    private static String normalize(String path, boolean allowEmptyHome) {
        path = path.trim();
        if (path.isEmpty() && !allowEmptyHome)
            throw new IllegalArgumentException("Enter a path for each photo folder. Use / for Files home.");
        StringBuilder result = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0)
                throw new IllegalArgumentException("Photo folder paths must not contain dot segments or backslashes.");
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    private FolderSettings() {}
}
