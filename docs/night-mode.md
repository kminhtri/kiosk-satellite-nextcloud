# Night mode

Open the plugin settings and enable **Night mode**. It is off by default, so existing installations retain their current behavior.

- **Night light threshold**: 0–1000 lx, default 5 lx.
- **Turn screen off after**: 1–60 minutes, default 2 minutes.

The light decision follows the native Clock screensaver in Kiosk Satellite 2026.10.40: enter night mode at or below the threshold and leave it above 125% of the threshold. Between these values, keep the previous decision to avoid flickering around the threshold. With the default 5 lx setting, enter at 5 lx or below and leave above 6.25 lx. See the [native Clock implementation](https://github.com/jxlarrea/kiosk-satellite/blob/2026.10.40/app/lib/managers/screensaver/screensaver_manager.dart#L151).

The countdown runs only while Nextcloud Photo Frame is the selected active screensaver, the screen is on, and live light readings keep it in night mode for the full delay. Leaving night mode, an invalid reading, leaving this screensaver, switching night mode off or changing its threshold/delay cancels the old countdown. Waking the display starts a full new delay and rechecks the sensor.

This is real display power-off through Kiosk Satellite's `screenOff` command, not a black photo. The application must already have a working screen-off permission; the plugin never opens a permission prompt. A failed request is reported without repeated attempts during the same dark viewing period. A new wake, relevant settings change or new dark period permits another attempt.

When daylight returns, the plugin calls `screenOn` to restore the photo frame, provided this screensaver is still selected and its own night mode powered the display off. Disabling night mode also restores a display it powered off. Like the native Clock, it tracks ownership so it does not wake a display that was manually powered off. Normal Kiosk Satellite wake controls remain available. Late successful screen-off callbacks are corrected if a newer viewing period, light transition or setting change already canceled the request.

The device needs an ambient-light sensor. Night mode waits if it is absent, the reading is unavailable/non-finite/negative, or the initial reading is only a persisted value from an earlier app session. The slideshow continues normally, and its status includes the night-mode warning. New sensor events supersede late initial-state callbacks.

The light countdown has its own worker, so slow Nextcloud requests or image processing cannot delay it. Its timer runs only during an active dark countdown. Photo refreshes, downloads and prefetching retain their existing visibility and screen-state gating, and stop when Kiosk Satellite reports the display off.

The plugin requests `host.control` in addition to its existing capabilities to issue screen-off and wake commands. It does not change Kiosk Satellite's saved screensaver settings or the native Clock mode's night settings.

## Device smoke test

1. Enable night mode with a one-minute delay and a threshold above the room's current light level. Select Nextcloud Photo Frame and verify the panel turns off after a full minute.
2. Wake normally and verify that photos return and another full minute is granted.
3. Before the deadline, brighten the sensor above 125% of the threshold. Verify that no power-off occurs; darken the room again and verify a new full delay starts. After night mode powers off the panel, brighten the sensor again and verify the photos return automatically. With a 5 lx threshold, readings of 6 lx must keep it off and readings above 6.25 lx must wake it. This depends on the device continuing to deliver light-sensor events while off.
4. Repeat while switching to Clock, dismissing the screensaver, disabling night mode and changing the delay. No canceled countdown should power off a different view.
5. If screen-off permission is unavailable, verify the status explains the failure and the plugin does not continuously retry or open an Android permission prompt.
6. Power off the panel manually while the photo frame is active, then brighten the sensor. Verify the plugin leaves it off. Disable night mode after it powers off the panel and verify that it wakes.
7. Restore the desired threshold and delay after testing. Actual sensor behavior, hardware wake support and panel power-off require this device check; JVM tests substitute the host commands and readings.
