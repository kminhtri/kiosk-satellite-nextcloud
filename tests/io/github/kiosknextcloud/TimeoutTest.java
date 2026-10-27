// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class TimeoutTest {
    static void run() throws Exception {
        slowAlbum();
        for (boolean total : new boolean[] {false, true}) {
            for (boolean album : new boolean[] {false, true}) listingTimeout(album, total);
            downloadTimeout(false, total);
            downloadTimeout(true, total);
        }
    }

    private static Map<String, Object> albumSettings(String url) {
        Map<String, Object> values = settings(url);
        values.put("photoSource", "Albums");
        values.put("albums", Collections.singletonList(Collections.singletonMap("name", "Private album")));
        return values;
    }

    private static void slowAlbum() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.directoryListing = path -> {
                try { Thread.sleep(16_000); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                StringBuilder entries = new StringBuilder(entry(path, true, "", ""));
                for (int index = 0; index < 391; index++)
                    entries.append(entry(path + index + ".jpg", false, "image/jpeg", "photo-" + index));
                return listing(entries.toString());
            };
            try (DavClient client = new DavClient(new Config(albumSettings(fixture.url())))) {
                List<DavClient.Photo> photos = client.list();
                check(photos.size() == 391, "album listing can take more than the old 15-second read timeout before headers");
                check(fixture.gets.get() == 0, "slow album listing does not download the photo collection");
                check(client.download(photos.get(0)).length > 0, "a photo can load after a delayed album listing");
            }
        }
    }

    private static OkHttpClient shortTimeout(boolean total) {
        return new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(total ? 2_000 : 200, TimeUnit.MILLISECONDS)
                .callTimeout(total ? 200 : 2_000, TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    }

    private static void listingTimeout(boolean album, boolean total) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> values = album ? albumSettings(fixture.url()) : settings(fixture.url());
            block(fixture);
            try (DavClient client = new DavClient(new Config(values), shortTimeout(total))) {
                assertTimeout(client::list, album ? "listing an album" : "listing a folder", total);
            } finally { fixture.block.countDown(); }
        }
    }

    private static void downloadTimeout(boolean preview, boolean total) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Config config = new Config(albumSettings(fixture.url()));
            DavClient.Photo photo;
            try (DavClient initial = new DavClient(config)) {
                fixture.directoryListing = path -> listing(entry(path, true, "", "")
                        + entry(path + "photo.jpg", false, "image/jpeg", "photo", "123"));
                photo = initial.list().get(0);
            }
            if (preview) fixture.oversizedImage = true;
            // For previews, let the oversized original finish before blocking its preview.
            block(fixture);
            fixture.blockPreview = preview;
            try (DavClient client = new DavClient(config, shortTimeout(total))) {
                assertTimeout(() -> client.download(photo), preview ? "loading a photo preview" : "downloading a photo", total);
            } finally {
                if (fixture.block != null) fixture.block.countDown();
                fixture.blockPreview = false;
            }
        }
    }

    private static void block(Fixture fixture) {
        fixture.block = new CountDownLatch(1);
        fixture.entered = new CountDownLatch(1);
    }

    private static void assertTimeout(Throwing action, String operation, boolean total) throws Exception {
        try { action.run(); throw new AssertionError("request must time out during " + operation); }
        catch (IOException error) {
            String message = error.getMessage();
            check(message != null && message.startsWith("Nextcloud timed out while " + operation),
                    (total ? "total-request" : "read") + " timeout identifies " + operation);
            check(!message.contains("Private album") && !message.contains("test-password") && !message.contains("http://"),
                    "timeout message does not expose the source name, password or URL");
        }
    }

    private TimeoutTest() {}
}
