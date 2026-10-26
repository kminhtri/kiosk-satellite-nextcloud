# Nextcloud Photo Frame for Kiosk Satellite

A Kiosk Satellite plugin that displays Nextcloud **Files folders** and **personal Photos albums** as a photo screensaver. Choose folders, albums or both, using the same Nextcloud connection.

## Install

Requires Android 7.0+ and a current Kiosk Satellite version with SDK 1 custom screensavers.

### Install from GitHub

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=kminhtri/kiosk-satellite-nextcloud)

Click the button, enter your kiosk's Remote Admin address if asked, then review the plugin and choose **Trust and install**. Remote Admin must be enabled on the kiosk. The installer may show an unlisted-plugin notice until the plugin is approved for Community Plugins.

You can also install it manually:

1. Open **Settings → Plugin Manager** and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/kminhtri/kiosk-satellite-nextcloud`, and select **Preview**.
3. Review the source and permissions, choose **Trust and install**, then enable **Nextcloud Photo Frame**.
4. Enter your connection settings as described below, then select the plugin under **Screensaver → Screensaver mode**.

[Latest release](https://github.com/kminhtri/kiosk-satellite-nextcloud/releases/latest)

### Local ZIP installation and configuration

1. Transfer `dist/nextcloud-photos-0.5.0.zip` to the kiosk, or use its remote admin interface.
2. Open **Settings → Plugin Manager**, enable plugins, then **Developer Tools → Install from ZIP**.
3. Select the ZIP, review and trust it, then enable **Nextcloud Photo Frame**. A local ZIP cannot replace a plugin installed from GitHub. Switching to local testing requires uninstalling first, which deletes saved settings; record them before switching, or wait for the GitHub release update.
4. Open the plugin's settings and enter:
   - **Nextcloud server URL**: e.g. `https://cloud.example.com`, or `https://example.com/nextcloud` for a subdirectory installation. Use the server base URL, not a share URL or the full WebDAV URL.
   - **Username**: the Nextcloud login name.
   - **App password**: generate one under Nextcloud personal settings → Security. A dedicated account with read access to your photo folder is recommended.
   - **Photo source**: choose **Folders** (the default), **Albums**, or **Folders and albums**. Existing installations keep their folder selection.
   - **Photo folders**: use **Add folder** for paths such as `Photos/Frame`, `Holidays/2026` or `Family`, relative to that account's Files home. Use `/` for Files home itself. Existing primary and additional folders are imported automatically into this list; all selected folders form one slideshow.
   - **Include subfolders** (optional): includes descendants of every selected Files folder. It never traverses albums recursively.
   - **Photo albums**: use **Add album** for each personal album’s exact name, for example `Holidays 2026`. Use the name shown in Nextcloud Photos, not a URL or filesystem path. Album names are case-sensitive. The folder and album lists use the same editing controls. Select at most 32 folders and albums in total.
5. Open **Settings → Screensaver**, enable it, and select **Nextcloud Photo Frame (Nextcloud Photo Frame)** as the mode, or select it in a screensaver schedule.

The plugin registers its mode immediately, including before connection settings are complete. Network work starts only when **Nextcloud Photo Frame** is the visible screensaver and the screen is on. An empty folder or initial connection failure displays a message there; after photos load, failures preserve the current photo.

The SDK does not replay the current `screensaver.view` on subscription and has no read command for that view. If you enable the plugin while a screensaver is already running, dismiss it and start it again once. The plugin waits for a view event instead of guessing which mode is visible.

If the mode is missing in Remote Admin after enabling the plugin, reload the whole browser page and reopen the **Screensaver mode** dropdown. Set screensaver brightness above 0% to see photos.

## Features and limits

- JPEG, PNG and WebP photos, with EXIF rotation/mirroring and black backgrounds for transparency.
- Up to 32 selected sources in total: Files folders and/or personal Photos albums. The whole selection is limited to 100 collections, 20 levels below each selected Files folder and 5000 unique photos per refresh. Select fewer folders, select a deeper folder directly or disable subfolders if a limit is reached. A failed refresh keeps any previously loaded slideshow.
- Personal Photos albums are read directly through their authenticated album WebDAV endpoint. Album membership is refreshed automatically at the configured interval or with **Refresh photos**; photos stay in their original folders. Public links and albums shared with this account are not supported in this version.
- Overlapping folder paths are visited once. Nextcloud `oc:fileid` values also prevent repeated directory aliases and duplicate photos across folders and albums when available. Without IDs, path checks and traversal limits still stop unbounded recursion. Separate copies with different IDs remain separate photos.
- Shuffle without repeats within a cycle, or Nextcloud's listing order with shuffle disabled.
- Configurable 5–600 second interval, Fit/Fill scaling and periodic folder refresh.
- Fetches the first photo and prefetches the next one only while this specific screensaver is visible and the screen is on. Hiding it, switching to another mode or turning off the screen cancels current HTTP work and suspends the worker timer, directory refreshes, downloads and retries. Refresh/next actions invoked while paused are deferred until it is visible again.
- Bounded in-memory cache of 2–30 resized photos. During connection failures, available cached photos continue rotating. **The cache clears when settings change, the plugin stops, or the app restarts.** It is not a persistent offline photo library.
- **Refresh photos** and **Next photo** plugin actions, assignable through Kiosk Satellite gestures, drawer shortcuts or Home Assistant buttons.
- Kiosk Satellite retains its standard idle timeout, brightness, wake triggers, touch dismissal, widgets and schedules. Stock slideshow edge-tap navigation and transitions do not control this plugin.
- Optional **Night mode** powers off the display after continuous darkness while this photo frame is visible, then wakes it when daylight returns. Defaults: off, 5 lx and 2 minutes. It follows the native Clock's 25% hysteresis: enter at or below 5 lx and exit above 6.25 lx. It requires a live ambient-light sensor and an existing Kiosk Satellite screen-off permission. It restores only a display powered off by its own night mode. Manual wake grants a new full delay. See the [night mode guide](docs/night-mode.md).

Images are resized to at most 1920 pixels on their longest side and compressed to at most 350 KiB. Downloads are limited to 8 MiB per response, including responses without Content-Length. If an original exceeds that limit, the plugin requests a 1920 × 1920 aspect-preserving preview from the same Nextcloud server using the selected photo's `oc:fileid`. The preview has the same 8 MiB cap. Nextcloud image previews must be enabled and the file ID must be available; otherwise use a smaller JPEG/PNG/WebP copy. Folder and album listings have a separate 4 MiB XML limit. A caught OutOfMemoryError clears the resized-photo cache and backs off for 30 seconds without terminating the scheduled slideshow; recovery is best effort in the host process. HEIC, GIF, RAW, videos, public-share authentication, shared albums and Memories-specific collections are outside this release's scope. Export those photos to JPEG/PNG/WebP in the selected folder.

## Privacy and connections

Only the configured Nextcloud server receives requests, using PROPFIND and authenticated GET. No analytics, intermediary service or Home Assistant token is used. Credentials are stored through Kiosk Satellite's `secret` setting type and never embedded in screensaver HTML or diagnostic messages. This setting type does not establish a separate security boundary from trusted code in the host app.

Night mode uses Kiosk Satellite's own ambient-light readings and screen controls. The `host.control` capability allows display power-off and waking; the feature is disabled by default. It does not request Android permissions or change the app's native Clock screensaver settings.

HTTPS certificate validation remains enabled. Redirects are refused; enter the final canonical server URL. HTTP is disabled unless **Allow unencrypted HTTP** is explicitly enabled; the Android host's cleartext-network policy must also permit it. Never put a password in the server URL. Returned image paths must stay within the selected Files folders or be direct entries of a selected Photos album, on the configured origin. The oversized-photo fallback uses only the configured server's `/index.php/core/preview` endpoint, with a numeric file ID from the selected DAV entry; server-supplied preview URLs are never followed.

Select folders or personal albums directly in the plugin rather than creating shortcuts. URL shortcut files, public share URLs and remote download URLs are not followed. Album photos are downloaded using their album DAV hrefs; the plugin does not request arbitrary original-path references. See [album protocol and scope](docs/nextcloud-albums.md).

The renderer is isolated HTML containing an embedded image. It has no Nextcloud credentials and makes no network requests. A Java worker fetches and processes images asynchronously and cancels requests on reconfiguration or shutdown. Each new image recreates the screensaver document under the current SDK, so slides use direct replacement without a crossfade.

## Build and test

Install Python 3, JDK 17+ and an Android SDK with platform 35 and build-tools 35.0.0. Set JAVA_HOME and ANDROID_HOME to those installations. The build and test scripts pin the tested platform and build-tools, even when newer versions are installed.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/verify-package.py
```

Tools download pinned OkHttp, Okio, Kotlin standard library and annotations JARs from Maven Central and verify SHA-256 digests in `tools/dependencies.lock.json`. R8 relocates these libraries into `io.github.kiosknextcloud.internal` before D8 converts them to DEX. This prevents the host's older Kotlin/OkHttp/Okio from shadowing the plugin's libraries in its parent-first class loader. The build generates the User-Agent version from the effective package manifest, including a release tag version override. The SDK remains a compile-time dependency supplied by Kiosk Satellite. See `assets/licenses/` and `THIRD-PARTY-NOTICES.txt` for attribution.

The test runner first reproduces the `Intrinsics.checkNotNullParameter` failure with an incompatible host Kotlin fixture, then runs the isolated probe and full integration suite with that same host class first on the classpath. Package verification also checks the final DEX type references for dependency namespace leaks.

Every build writes R8’s class and member renaming map to `dist/mapping.txt`. CI keeps it with the build artifact, and the release workflow attaches it alongside the ZIP, manifest and checksum. Reviewers can trace private class names back to the pinned OkHttp, Okio and Kotlin inputs in `tools/dependencies.lock.json`. The map is produced by the same R8 invocation as the packaged code; it is not embedded in the plugin ZIP.

Tests start a local HTTP WebDAV fixture and exercise authentication, multiple folders, personal albums, mixed-source deduplication, escaped album names, empty/missing albums, global album photo limits, overlapping selections, directory aliases and loops, traversal limits, URL/XML validation, redirects, bounded responses, rendering size limits, timing, cached offline rotation, zero idle traffic, mode-switch cancellation, the 8 MiB download limit, versioned User-Agent, simulated out-of-memory recovery and cancellation of obsolete work. Image encoding is substituted in JVM tests; Android bitmap/EXIF decoding and the actual WebView require a device smoke test.

To compare the bundled SDK with a Kiosk Satellite checkout:

```sh
python3 tools/check-sdk.py /path/to/kiosk-satellite
```

## Device smoke test

1. Use a small folder containing an EXIF-rotated portrait JPEG, landscape JPEG, transparent PNG and WebP.
2. Verify Fit/Fill, image orientation, widgets, idle activation and touch dismissal.
3. Let several slides cache, disconnect networking, and verify that the current photo remains visible and cached slides rotate.
4. Restore networking and run **Refresh photos**. Add/delete a photo and verify the next refresh updates the selection.
5. Switch to Clock or dismiss the screensaver and verify that Nextcloud requests stop, including retries after a connection failure. Turn the screen off and wake it, then activate this mode again. Restart the plugin and reboot the kiosk; verify photos reload when this screensaver becomes visible and Nextcloud is reachable.
6. Choose **Albums**, add a personal album name and activate the screensaver. Add/remove a photo in Nextcloud Photos and run **Refresh photos** while the frame is visible. Select **Folders and albums** and verify a photo present in both appears only once per cycle when Nextcloud provides its file ID.
7. Use **Add folder** under **Photo folders** and verify both folders appear in the slideshow. Select an overlapping subfolder too, then enable **Include subfolders** and check that photos are not repeated within a cycle. Upgrade from an older version and verify primary and additional folders appear in the same list, while credentials and album selections are retained. Remove the old primary folder and verify it stays removed after restarting.

## Publish

CI tests and packages changes. To distribute through Kiosk Satellite's GitHub installer, put this project in your own public repository, publish a stable tag such as `v0.4.0`, and let `.github/workflows/build.yml` attach the manifest, ZIP and checksum through GitHub Actions. Kiosk Satellite rejects manually uploaded release packages. Local ZIP installation is the supported developer testing route.

Based on the [official plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) at commit `2260f801f047dab06a1ca0c1c4f7bec926cb73b1`. The `sdk/` and `docs/` folders retain upstream SDK reference material. The plugin is named Nextcloud Photo Frame to distinguish it from Nextcloud’s own Photos app. Its technical ID remains `nextcloud-photos`, so existing settings, mode selections and schedules survive the rename. This project is independent of Nextcloud GmbH and Kiosk Satellite.

## License

Apache-2.0. See LICENSE and the third-party notices.
