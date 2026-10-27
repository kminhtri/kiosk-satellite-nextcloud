# Changelog

## 0.5.1

- Allow 60 seconds without receiving data and 90 seconds in total per request, so album listings taking more than 15 seconds to prepare can load. Keep the 10-second connect timeout, response-size limits and cancellation when hidden or screen-off.
- Identify timeouts during folder/album listing, photo download or preview loading instead of showing the generic connection/certificate error.
- Add HTTP regressions for a delayed album response, read and total-request timeouts, safe error messages and cancellation.

## 0.5.0

- Add an optional night mode with a switch, ambient-light threshold and continuous-darkness delay, defaulting to off, 5 lx and 2 minutes.
- Use Kiosk Satellite's light-sensor events and screen controls, with a countdown independent of photo downloads and no countdown while another view is active.
- Wake a display powered off by this plugin's night mode when daylight returns, or when night mode is disabled, while respecting manual power-off.
- Match the native Clock's light hysteresis: enter at or below the threshold and exit above 125% of it (5 lx / 6.25 lx).
- Guard against stale sensor reads and late screen-off callbacks, suspend night screen-off without live readings, and avoid repeated permission failures in one viewing period.
- Use matching Photo folders and Photo albums lists, with Add folder and Add album actions, and move source settings into a Photo sources group.
- Import the primary folder and additional folders into the unified list without changing credentials, albums or night settings; retain legacy values for compatibility.
- Add deterministic timing, lifecycle, daylight wake, manual power-off, hysteresis, blocked-download and folder migration regression coverage.

## 0.4.0

- Add personal Nextcloud Photos albums as a source, selectable alongside existing Files folders or on their own.
- Allow switching to an album source before adding the first album; validate the required selection in the plugin instead of blocking the settings editor.
- Distinguish the 4 MiB directory-listing limit from the 8 MiB photo-download limit in error messages.
- Fall back to an authenticated same-server preview by file ID when an original exceeds 8 MiB, retaining the response cap, idle suspension and redirect protections.
- Read album membership with authenticated Depth 1 PROPFIND and download images directly from the album DAV endpoint.
- Preserve existing folder configuration, deduplicate file IDs across folders and albums, and retain the 8 MiB image cap and visibility-based network suspension.
- Limit selection to 32 folders/albums in total and 5000 unique photos per refresh; never recurse into albums or follow external/original-path download references.
- Add WebDAV regressions for album-only and mixed sources, URL encoding, empty/missing albums, refreshes, deduplication and scope limits.

## 0.3.1

- Use an authenticated Nextcloud preview by file ID when an original photo exceeds 8 MiB, without increasing the per-response memory cap.
- Preserve the 0.3.0 folder settings, cache limits, visibility-based suspension and redirect protections.
- Distinguish folder-listing, original-photo and preview size errors, and explain when previews are unavailable.
- Add regressions for large originals, unknown-length downloads, unavailable/oversized previews, invalid file IDs and first-photo loading.

## 0.3.0

- Rename the plugin and screensaver to Nextcloud Photo Frame, preserving the existing plugin ID, settings and schedules.
- Cap each original photo download at 8 MiB instead of 24 MiB.
- Recover from OutOfMemoryError in the slideshow worker by clearing the cache and backing off for 30 seconds, including when reporting memory pressure also fails.
- Load, refresh, rotate, prefetch and retry only while this renderer is visible and the screen is on. Cancel in-flight HTTP calls and suspend the timer when it is hidden; defer manual actions until it returns.
- Derive the User-Agent from the effective package version, including release-tag overrides.
- Produce mapping.txt from R8 and include it in CI artifacts and GitHub release uploads.
- Include the Nextcloud/photo-stack plugin icon.


## 0.2.0

- Combine the existing photo folder with up to 31 additional folders from the same Nextcloud account.
- Preserve existing connection settings and apply optional subfolder traversal to every selected folder.
- Deduplicate overlapping paths and Nextcloud file IDs; skip repeated folder IDs to prevent recursive alias loops.
- Bound traversal to 20 levels below each selected folder, 100 folders and 5000 unique photos per refresh, including servers without file IDs.
- Exercise multiple roots, overlapping selections, aliases, cycles and traversal limits through the WebDAV integration fixture.

## 0.1.2

- Pin Android platform 35 and build-tools 35.0.0 for consistent local and GitHub Actions dependency isolation.
- Fail with an installation hint when the tested compiler is missing instead of silently selecting a newer R8/D8.

## 0.1.1

- Fix startup failures when the host includes an older Kotlin runtime.
- Relocate Kotlin, OkHttp, Okio and annotations into the plugin's private namespace before creating DEX.
- Reproduce the original missing-method error in a host-runtime fixture, then run the isolated integration suite against that conflicting runtime.
- Verify the final DEX contains no references to shared dependency types.

## 0.1.0

- Initial Nextcloud folder screensaver using authenticated WebDAV.
- Folder recursion, shuffle, configurable interval, Fit/Fill and refresh/next actions.
- Bounded image decoding, EXIF orientation, in-memory offline cache and prefetch.
- Asynchronous lifecycle with canceled obsolete requests and screen-state awareness.
- WebDAV integration tests, installer validation, CI and release packaging.
