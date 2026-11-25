// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class FolderScanTest {
    static void run() throws Exception {
        Map<String, Object> values = settings("https://cloud.example");
        check(new Config(values).maxCollections == 1000, "default collection traversal limit is 1000");
        values.put("maxCollections", 20000);
        check(new Config(values).maxCollections == 10000, "collection traversal cannot exceed 10000");
        values.put("maxCollections", 0);
        check(new Config(values).maxCollections == 100, "collection traversal keeps a bounded minimum");
        try (Fixture fixture = new Fixture()) {
            values = settings(fixture.url()); values.put("includeSubfolders", true);
            fixture.directoryListing = path -> {
                if (path.equals(fixture.root)) {
                    StringBuilder entries = new StringBuilder(entry(path, true, "", "", "root"));
                    // The root, 105 children and a self-ID alias used to exceed the 100-collection guard.
                    entries.append(entry(path + "alias/", true, "", "", "root"));
                    for (int i = 0; i < 105; i++) entries.append(entry(path + "child" + i + "/", true, "", "", "folder-" + i));
                    return listing(entries.toString());
                }
                if (path.endsWith("alias/")) throw new AssertionError("a self-ID alias must never be traversed");
                return listing(entry(path, true, "", "", "folder-" + path)
                        + entry(path + "photo.jpg", false, "image/jpeg", "v1", "photo-" + path));
            };
            try (DavClient client = new DavClient(new Config(values))) {
                check(client.list().size() == 105 && fixture.propfinds.get() == 106,
                        "recursive scans beyond 100 collections succeed and still skip loops");
            }
            values.put("maxCollections", 100);
            fixture.propfinds.set(0);
            try (DavClient client = new DavClient(new Config(values))) {
                try { client.list(); throw new AssertionError("configured scan limit must be enforced"); }
                catch (IOException error) {
                    check(error.getMessage().contains("(100 collections)") && error.getMessage().contains("Folder and album scan limit"),
                            "limit error identifies the configured value and how to raise it");
                }
                check(fixture.propfinds.get() == 1, "scan cap stops pending traversal before opening hundreds of requests");
            }
        }
    }
    private FolderScanTest() {}
}
