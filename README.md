# Nextcloud Photos for Kiosk Satellite

A Kiosk Satellite plugin that displays a Nextcloud **Files folder** as a photo screensaver. Nextcloud Photos/Memories virtual albums are not supported in this first version.

## Install

Requires Android 7.0+ and a current Kiosk Satellite version with SDK 1 custom screensavers.

### Install from GitHub

1. Open **Settings → Plugin Manager** and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/kminhtri/kiosk-satellite-nextcloud`, and select **Preview**.
3. Review the source and permissions, choose **Trust and install**, then enable **Nextcloud Photos**.
4. Enter your connection settings as described below, then select the plugin under **Screensaver → Screensaver mode**.

[Latest release](https://github.com/kminhtri/kiosk-satellite-nextcloud/releases/latest)

### Local ZIP installation and configuration

1. Transfer `dist/nextcloud-photos-0.1.2.zip` to the kiosk, or use its remote admin interface.
2. Open **Settings → Plugin Manager**, enable plugins, then **Developer Tools → Install from ZIP**.
3. Select the ZIP, review and trust it, then enable **Nextcloud Photos**.
4. Open the plugin's settings and enter:
   - **Nextcloud server URL**: e.g. `https://cloud.example.com`, or `https://example.com/nextcloud` for a subdirectory installation. Use the server base URL, not a share URL or the full WebDAV URL.
   - **Username**: the Nextcloud login name.
   - **App password**: generate one under Nextcloud personal settings → Security. A dedicated account with read access to your photo folder is recommended.
   - **Photo folder**: e.g. `Photos/Frame`, relative to that account's Files home.
5. Open **Settings → Screensaver**, enable it, and select **Nextcloud Photos (Nextcloud Photos)** as the mode, or select it in a screensaver schedule.

The plugin registers its mode immediately, including before connection settings are complete. Connection status appears on its settings page. An empty folder or initial connection failure also displays a message in the screensaver. After photos load, failures preserve the current photo.

If the mode is missing in Remote Admin after enabling the plugin, reload the whole browser page and reopen the **Screensaver mode** dropdown. Set screensaver brightness above 0% to see photos.

## Features and limits

- JPEG, PNG and WebP photos, with EXIF rotation/mirroring and black backgrounds for transparency.
- Optional subfolders, limited to 100 directories and 5000 photos per listing. Choose a smaller folder if a limit is reached.
- Shuffle without repeats within a cycle, or Nextcloud's listing order with shuffle disabled.
- Configurable 5–600 second interval, Fit/Fill scaling and periodic folder refresh.
- Prefetches the next photo. Rotation pauses while the screensaver is inactive or the screen is off; directory refresh still runs periodically. The first two photos can preload while idle.
- Bounded in-memory cache of 2–30 resized photos. During connection failures, available cached photos continue rotating. **The cache clears when settings change, the plugin stops, or the app restarts.** It is not a persistent offline photo library.
- **Refresh photos** and **Next photo** plugin actions, assignable through Kiosk Satellite gestures, drawer shortcuts or Home Assistant buttons.
- Kiosk Satellite retains its standard idle timeout, brightness, wake triggers, touch dismissal, widgets and schedules. Stock slideshow edge-tap navigation and transitions do not control this plugin.

Images are resized to at most 1920 pixels on their longest side and compressed to at most 350 KiB. Original downloads are limited to 24 MiB per photo. HEIC, GIF, RAW, videos, public-share authentication and virtual albums are outside this release's scope. Export those photos to JPEG/PNG/WebP in the selected folder.

## Privacy and connections

Only the configured Nextcloud server receives requests, using PROPFIND and authenticated GET. No analytics, intermediary service or Home Assistant token is used. Credentials are stored through Kiosk Satellite's `secret` setting type and never embedded in screensaver HTML or diagnostic messages. This setting type does not establish a separate security boundary from trusted code in the host app.

HTTPS certificate validation remains enabled. Redirects are refused; enter the final canonical server URL. HTTP is disabled unless **Allow unencrypted HTTP** is explicitly enabled; the Android host's cleartext-network policy must also permit it. Never put a password in the server URL. Returned image paths must stay within the selected folder and origin.

The renderer is isolated HTML containing an embedded image. It has no Nextcloud credentials and makes no network requests. A Java worker fetches and processes images asynchronously and cancels requests on reconfiguration or shutdown. Each new image recreates the screensaver document under the current SDK, so slides use direct replacement without a crossfade.

## Build and test

Install Python 3, JDK 17+ and an Android SDK with platform 35 and build-tools 35.0.0. Set JAVA_HOME and ANDROID_HOME to those installations. The build and test scripts pin the tested platform and build-tools, even when newer versions are installed.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
python3 tools/verify-package.py
```

Tools download pinned OkHttp, Okio, Kotlin standard library and annotations JARs from Maven Central and verify SHA-256 digests in `tools/dependencies.lock.json`. R8 relocates these libraries into `io.github.kiosknextcloud.internal` before D8 converts them to DEX. This prevents the host's older Kotlin/OkHttp/Okio from shadowing the plugin's libraries in its parent-first class loader. The SDK remains a compile-time dependency supplied by Kiosk Satellite. See `assets/licenses/` and `THIRD-PARTY-NOTICES.txt` for attribution.

The test runner first reproduces the `Intrinsics.checkNotNullParameter` failure with an incompatible host Kotlin fixture, then runs the isolated probe and full integration suite with that same host class first on the classpath. Package verification also checks the final DEX type references for dependency namespace leaks.

Tests start a local HTTP WebDAV fixture and exercise authentication, folder traversal, URL/XML validation, redirects, bounded responses, rendering size limits, timing, cached offline rotation, screen-off behavior and cancellation of obsolete work. Image encoding is substituted in JVM tests; Android bitmap/EXIF decoding and the actual WebView require a device smoke test.

To compare the bundled SDK with a Kiosk Satellite checkout:

```sh
python3 tools/check-sdk.py /path/to/kiosk-satellite
```

## Device smoke test

1. Use a small folder containing an EXIF-rotated portrait JPEG, landscape JPEG, transparent PNG and WebP.
2. Verify Fit/Fill, image orientation, widgets, idle activation and touch dismissal.
3. Let several slides cache, disconnect networking, and verify that the current photo remains visible and cached slides rotate.
4. Restore networking and run **Refresh photos**. Add/delete a photo and verify the next refresh updates the selection.
5. Turn the screen off and wake it. Restart the plugin and reboot the kiosk; verify photos reload when Nextcloud is reachable.

## Publish

CI tests and packages changes. To distribute through Kiosk Satellite's GitHub installer, put this project in your own public repository, publish a stable tag such as `v0.1.2`, and let `.github/workflows/build.yml` attach the manifest, ZIP and checksum through GitHub Actions. Kiosk Satellite rejects manually uploaded release packages. Local ZIP installation is the supported developer testing route.

Based on the [official plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) at commit `2260f801f047dab06a1ca0c1c4f7bec926cb73b1`. The `sdk/` and `docs/` folders retain upstream SDK reference material. This project is independent of Nextcloud GmbH and Kiosk Satellite.

## License

Apache-2.0. See LICENSE and the third-party notices.
