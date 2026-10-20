# Changelog

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
