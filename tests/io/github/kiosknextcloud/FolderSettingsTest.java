// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class FolderSettingsTest {
    static void run() throws Exception {
        formatsAndLimits();
        migration();
    }

    static List<Map<String, String>> rows(String... paths) {
        List<Map<String, String>> result = new ArrayList<>();
        for (String path : paths) result.add(Collections.singletonMap("path", path));
        return result;
    }

    static void formatsAndLimits() throws Exception {
        Map<String, Object> values = settings("https://cloud.example/nextcloud/");
        values.put("folder", " Photos Plex/ ");
        values.put("additionalFolders", rows("Family", "/Photos Plex//", "été & +"));
        Config legacy = new Config(values);
        Map<String, Object> patch = FolderSettings.migration(values);
        check(patch.keySet().equals(new HashSet<>(Arrays.asList("folders", "folderListVersion"))), "folder migration updates only the unified list and its version");
        check(patch.get("folders").equals(rows("Photos Plex", "Family", "été & +")), "migration retains folder order, names and normalized deduplication");
        check(!values.containsKey("folders"), "migration preparation does not mutate the caller settings map");
        values.putAll(patch);
        Config unified = new Config(values);
        check(legacy.folders.equals(unified.folders), "unified folders resolve to exactly the same authenticated DAV locations");
        check(FolderSettings.migration(values).isEmpty(), "folder migration is idempotent");
        values.put("folder", "../inactive");
        values.put("additionalFolders", "inactive legacy list");
        check(new Config(values).folders.equals(unified.folders), "migrated configurations ignore stale hidden legacy settings");
        values.put("folders", rows("/"));
        check(new Config(values).folder.toString().equals("https://cloud.example/nextcloud/remote.php/dav/files/alice/"), "slash is a valid non-empty list entry for Files home");
        for (String invalid : Arrays.asList("", " ", ".", "..", "Photos/../secret", "bad\\path")) {
            values.put("folders", rows(invalid));
            expectFailure(() -> new Config(values), "reject unsafe or incomplete unified folder path");
        }
        values.put("folders", Collections.emptyList());
        expectFailure(() -> new Config(values), "an explicitly empty migrated list never falls back to old folders");
        check(FolderSettings.migration(values).isEmpty(), "deleted folders are not silently reimported");
        values.put("photoSource", "Albums");
        values.put("albums", Collections.singletonList(Collections.singletonMap("name", "Family")));
        check(new Config(values).folders.isEmpty(), "album-only mode does not require a hidden folder list");
        values.put("photoSource", "Folders and albums");
        List<Map<String, String>> folders = new ArrayList<>();
        for (int i = 0; i < 31; i++) folders.add(Collections.singletonMap("path", "Folder" + i));
        values.put("folders", folders);
        check(new Config(values).folders.size() == 31, "31 folders and one album fit the shared source limit");
        folders.add(Collections.singletonMap("path", "Folder31"));
        expectFailure(() -> new Config(values), "new folder list and albums share the existing 32-source limit");
        values.put("photoSource", "Folders");
        check(new Config(values).folders.size() == 32, "all 32 source slots can be folders without a separate primary slot");
        folders.add(Collections.singletonMap("path", "Folder32"));
        expectFailure(() -> new Config(values), "bound raw folder rows before parsing");
        Map<String, Object> root = settings("https://cloud.example");
        root.put("folder", "");
        check(FolderSettings.migration(root).get("folders").equals(rows("/")), "legacy empty home path is imported as an editable slash entry");
        root.put("folders", rows("Chosen"));
        check(FolderSettings.migration(root).get("folders").equals(rows("Chosen")), "an explicit new folder list wins over legacy defaults before first migration");
    }

    static void migration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> values = settings(fixture.url());
            values.put("folder", "Photos Plex");
            values.put("additionalFolders", rows("Family"));
            values.put("photoSource", "Folders and albums");
            values.put("albums", Collections.singletonList(Collections.singletonMap("name", "Gueux & Queen")));
            values.put("nightMode", false);
            values.put("nightLux", 8);
            values.put("nightMinutes", 3);
            values.put("folders", Collections.emptyList());
            values.put("folderListVersion", 0);
            MigrationHost host = new MigrationHost(values);
            NextcloudPlugin plugin = new NextcloudPlugin(original -> original, () -> 1L);
            plugin.start(host, values);
            try {
                check(host.updates.get() == 1 && host.saved.get("folders").equals(rows("Photos Plex", "Family")), "plugin imports the screenshot-style primary and additional folders into one list");
                check(host.saved.get("albums").equals(values.get("albums")) && host.saved.get("photoSource").equals("Folders and albums"), "migration preserves albums and mixed-source mode");
                check(host.saved.get("appPassword").equals("test-password") && host.saved.get("nightLux").equals(8)
                        && host.saved.get("nightMinutes").equals(3), "migration leaves credentials, night mode and other settings intact");
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "folder migration is local and does not fetch sources or photos");
                plugin.configure(host.saved);
                check(host.updates.get() == 1, "subsequent configure callbacks do not rewrite the folder selection");
                Map<String, Object> edited = new HashMap<>(host.saved);
                edited.put("folders", rows("Family"));
                plugin.configure(edited);
                check(new Config(edited).folders.size() == 1 && new Config(edited).folder.toString().endsWith("/Family/"), "removing the former primary removes it from the slideshow");
                edited.put("folders", Collections.emptyList());
                plugin.configure(edited);
                check(host.status.startsWith("Add at least one photo folder") && host.updates.get() == 1, "deleting all rows gives an actionable error without restoring hidden legacy entries");
                edited.put("photoSource", "Albums");
                plugin.configure(edited);
                check(host.status.startsWith("Ready."), "switching to albums works after clearing the folder list");
            } finally { plugin.stop(); }
            Host older = new Host();
            plugin.start(older, values);
            try {
                check(older.status.contains("Existing folders remain active"), "hosts without partial settings updates retain the legacy selection and explain the migration requirement");
                check(new Config(values).folders.size() == 2, "unsupported persistence cannot erase the old folder configuration");
            } finally { plugin.stop(); }
        }
    }

    static final class MigrationHost extends Host {
        Map<String, Object> saved;
        final AtomicInteger updates = new AtomicInteger();
        MigrationHost(Map<String, Object> values) { saved = new HashMap<>(values); }
        @Override public void updateSettings(Map<String, Object> patch) {
            if (!patch.keySet().equals(new HashSet<>(Arrays.asList("folders", "folderListVersion"))))
                throw new AssertionError("migration must not resend connection credentials or unrelated values");
            Map<String, Object> next = new HashMap<>(saved);
            next.putAll(patch); saved = next; updates.incrementAndGet();
        }
    }
}
