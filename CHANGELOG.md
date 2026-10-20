# Changelog

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
