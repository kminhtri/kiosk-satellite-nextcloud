// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import me.jxl.kiosk.plugins.PluginHost;

/** A light-driven countdown independent of photo downloads. All state shares the plugin lock. */
final class NightMode implements AutoCloseable {
    interface Display {
        boolean selected();
        boolean screenOn();
    }
    private final Object lock;
    private final PluginHost host;
    private final Display display;
    private final LongSupplier clock;
    private final ScheduledExecutorService worker;
    private ScheduledFuture<?> timer;
    private boolean enabled, closed, attempted, wasVisible;
    private boolean poweredOffByNight, wakeAttempted, nightState;
    private long wakeRevision;
    private Double lux;
    private int threshold = 5, minutes = 2;
    private long darkSince = -1, epoch, lightRevision;
    private String failure;

    NightMode(Object lock, PluginHost host, Display display, LongSupplier clock) {
        this.lock = lock; this.host = host; this.display = display; this.clock = clock;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NextcloudNightMode");
            thread.setDaemon(true);
            return thread;
        });
    }

    void configure(Map<String, Object> settings) {
        synchronized (lock) {
            if (closed) return;
            boolean next = Boolean.TRUE.equals(settings.get("nightMode"));
            int nextLux = Config.number(settings, "nightLux", 5, 0, 1000);
            int nextMinutes = Config.number(settings, "nightMinutes", 2, 1, 60);
            if (next == enabled && nextLux == threshold && nextMinutes == minutes) return;
            boolean wasEnabled = enabled;
            enabled = next; threshold = nextLux; minutes = nextMinutes;
            epoch++; lightRevision++;
            reset(); failure = null; wakeAttempted = false;
            if (enabled && !wasEnabled) {
                lux = null;
                try { host.subscribe("device.light"); }
                catch (IllegalArgumentException | IllegalStateException error) { failure = "Night mode cannot read the light sensor on this host."; }
                readLight();
            } else if (!enabled && wasEnabled) {
                lux = null;
                try { host.unsubscribe("device.light"); }
                catch (IllegalArgumentException | IllegalStateException ignored) { /* host may already be revoked */ }
            }
            decide();
            review();
        }
    }

    private void readLight() {
        final long revision = lightRevision;
        try {
            host.executeCommand("getLightLevel", Collections.emptyMap(), (ok, data, error) -> {
                synchronized (lock) {
                    if (closed || !enabled || revision != lightRevision) return;
                    Object value = null;
                    if (ok && data instanceof Map) {
                        Map<?, ?> reading = (Map<?, ?>) data;
                        if (Boolean.TRUE.equals(reading.get("present")) && Boolean.TRUE.equals(reading.get("live"))) value = reading.get("lux");
                    }
                    light(value);
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            failure = "Night mode cannot read the light sensor on this host.";
        }
    }

    void light(Object reading) {
        synchronized (lock) {
            if (closed || !enabled) return;
            lightRevision++;
            Double next = reading instanceof Number ? ((Number) reading).doubleValue() : null;
            if (next != null && (Double.isNaN(next) || Double.isInfinite(next) || next < 0)) next = null;
            boolean wasDark = dark();
            lux = next;
            decide();
            if (dark()) wakeAttempted = false;
            if (wasDark && !dark()) { epoch++; reset(); }
            review();
        }
    }

    void updateActivity() {
        synchronized (lock) {
            if (closed) return;
            boolean woke = enabled && display.selected() && display.screenOn() && !wasVisible;
            review();
            if (woke) {
                // Recheck sensor liveness when a frame becomes visible or wakes from screen-off.
                lightRevision++; lux = null; reset();
                readLight();
            }
        }
    }

    void screenState(boolean on) {
        synchronized (lock) {
            if (closed || !on) return;
            // A normal/manual wake ends our ownership; a later manual power-off must stay off.
            poweredOffByNight = false;
            wakeAttempted = false;
            if (failure != null && failure.startsWith("Night mode could not wake")) failure = null;
            wakeRevision++;
        }
    }

    private void decide() {
        if (!enabled || lux == null) nightState = false;
        else if (lux <= threshold) nightState = true;
        else if (lux > threshold * 1.25) nightState = false;
    }

    private boolean dark() { return enabled && lux != null && nightState; }

    private void review() {
        boolean visible = display.selected() && display.screenOn();
        if (poweredOffByNight && display.selected()
                && (!enabled || (lux != null && !dark()))) restoreScreen(false);
        if (visible && !wasVisible) { epoch++; reset(); }
        wasVisible = visible;
        if (!enabled || !display.selected() || !dark()) {
            if (darkSince >= 0 || attempted) epoch++;
            reset();
            return;
        }
        if (!visible) { reset(); return; }
        if (attempted) return;
        if (darkSince < 0) darkSince = clock.getAsLong();
        if (timer == null) timer = worker.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
    }

    // Also called directly by deterministic clock tests. The real timer runs only during a dark countdown.
    void check() {
        synchronized (lock) {
            if (closed) return;
            review();
            if (darkSince < 0 || attempted || !display.screenOn() || clock.getAsLong() - darkSince < minutes * 60_000L) return;
            attempted = true;
            failure = null;
            final long requestWakeRevision = ++wakeRevision;
            cancelTimer();
            final long requestEpoch = epoch;
            try {
                host.executeCommand("screenOff", Collections.emptyMap(), (ok, data, error) -> {
                    synchronized (lock) {
                        if (closed) return;
                        if (!ok) {
                            if (requestEpoch != epoch) return;
                            failure = "Night mode could not turn off the display. Check Kiosk Satellite's screen-off permission. Wake or change settings to retry.";
                            host.status(failure, true);
                            return;
                        }
                        // A newer view, light transition, setting or wake must win over a late screen-off.
                        if (requestEpoch != epoch || requestWakeRevision != wakeRevision || !enabled || !display.selected() || !dark()) {
                            restoreScreen(true);
                        } else poweredOffByNight = true;
                    }
                });
            } catch (IllegalArgumentException | IllegalStateException error) {
                failure = "Night mode could not turn off the display. Check Kiosk Satellite's screen-off permission.";
                host.status(failure, true);
            }
        }
    }

    private void restoreScreen(boolean force) {
        if (closed || wakeAttempted || (!poweredOffByNight && !force)) return;
        wakeAttempted = true;
        final long revision = ++wakeRevision;
        try {
            host.executeCommand("screenOn", Collections.emptyMap(), (ok, data, error) -> {
                synchronized (lock) {
                    if (closed || revision != wakeRevision) return;
                    if (ok) { poweredOffByNight = false; failure = null; }
                    else {
                        failure = "Night mode could not wake the display. Use a normal Kiosk Satellite wake control.";
                        host.status(failure, true);
                    }
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            failure = "Night mode could not wake the display. Use a normal Kiosk Satellite wake control.";
            host.status(failure, true);
        }
    }

    String warning() {
        synchronized (lock) {
            if (!enabled || closed) return "";
            if (failure != null) return failure;
            return lux == null ? "Night mode is waiting for a live ambient light reading; screen-off is suspended." : "";
        }
    }

    private void cancelTimer() { if (timer != null) timer.cancel(false); timer = null; }
    private void reset() { cancelTimer(); darkSince = -1; attempted = false; }

    @Override public void close() {
        synchronized (lock) { closed = true; epoch++; reset(); worker.shutdownNow(); }
    }
}
