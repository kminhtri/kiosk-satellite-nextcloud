// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.jxl.kiosk.plugins.PluginHost;
import static io.github.kiosknextcloud.NextcloudTest.*;

final class NightModeTest {
    static void run() throws Exception {
        countdown();
        invalidReadingsAndChanges();
        lateCallbacks();
        daylightWake();
        independentOfDownloads();
    }

    static void countdown() {
        try (Rig rig = new Rig()) {
            rig.mode.configure(Collections.emptyMap());
            check(rig.host.reads.get() == 0 && rig.host.subscribes.get() == 0, "disabled night mode neither queries nor subscribes to the light sensor");
            rig.mode.configure(options(true, 5, 1));
            rig.advance(600_000);
            check(rig.host.off.get() == 0, "dark room does not turn off the display while the photo frame is not selected");
            rig.select(true);
            rig.advance(59_999);
            check(rig.host.off.get() == 0, "night delay cannot fire early");
            rig.mode.light(5.0);
            rig.advance(1);
            check(rig.host.off.get() == 1, "equal-threshold reading keeps the original continuous dark countdown");
            rig.advance(300_000);
            rig.mode.light(2.0);
            rig.mode.check();
            check(rig.host.off.get() == 1, "night mode sends only one screen-off request per viewing period");
            rig.screen(false);
            rig.advance(300_000);
            check(rig.host.off.get() == 1, "screen-off state suspends the countdown");
            rig.screen(true);
            rig.advance(59_999);
            check(rig.host.off.get() == 1, "wake grants a complete new delay");
            rig.advance(1);
            check(rig.host.off.get() == 2, "night countdown resumes after a normal wake");
            rig.select(false);
            rig.select(true);
            rig.advance(30_000);
            rig.mode.light(7.0);
            rig.advance(120_000);
            check(rig.host.off.get() == 2, "room brightening cancels an unfinished countdown");
            rig.mode.light(1.0);
            rig.advance(59_999);
            check(rig.host.off.get() == 2, "returning darkness starts the whole delay again");
            rig.advance(1);
            check(rig.host.off.get() == 3, "continuous darkness eventually powers off again");
        }
    }

    static void invalidReadingsAndChanges() {
        try (Rig rig = new Rig()) {
            rig.host.present = false;
            rig.mode.configure(options(true, 5, 1));
            rig.select(true);
            rig.advance(120_000);
            check(rig.host.off.get() == 0 && rig.mode.warning().contains("waiting"), "absent sensor suspends screen-off with an explanation");
            for (Object invalid : Arrays.asList(null, "1", -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                rig.mode.light(1.0);
                rig.advance(30_000);
                rig.mode.light(invalid);
                rig.advance(120_000);
            }
            check(rig.host.off.get() == 0, "null, negative, non-numeric and non-finite readings cancel the countdown");
            rig.host.present = true;
            rig.host.live = false;
            rig.screen(false); rig.screen(true);
            rig.advance(120_000);
            check(rig.host.off.get() == 0, "cached sensor reading is not treated as live after waking");
            rig.mode.light(0.0);
            rig.mode.configure(options(true, 0, 2));
            rig.advance(60_000);
            check(rig.host.off.get() == 0, "zero lux is valid but the new two-minute delay is respected");
            rig.advance(60_000);
            check(rig.host.off.get() == 1, "zero threshold supports darkness readings of zero");
            rig.mode.configure(options(true, 5, 1));
            rig.advance(30_000);
            rig.mode.configure(options(false, 5, 1));
            rig.advance(120_000);
            check(rig.host.off.get() == 1 && rig.mode.warning().isEmpty() && rig.host.unsubscribes.get() == 1, "switching night mode off cancels work and removes its light subscription");
            rig.host.live = true;
            rig.mode.configure(options(true, 5, 1));
            rig.advance(30_000);
            rig.select(false);
            rig.advance(120_000);
            check(rig.host.off.get() == 1, "changing to another screensaver or dismissing cancels night screen-off");
            rig.select(true);
            rig.host.refuseOff = true;
            rig.advance(60_000);
            check(rig.host.off.get() == 2 && rig.mode.warning().contains("permission") && !rig.mode.warning().contains("secret"), "permission failure is reported without exposing host error text");
            rig.advance(600_000);
            check(rig.host.off.get() == 2, "permission failure does not retry continuously in the same dark viewing period");
            rig.host.refuseOff = false;
            rig.screen(false); rig.screen(true);
            rig.advance(60_000);
            check(rig.host.off.get() == 3 && rig.mode.warning().isEmpty(), "a later successful wake retry clears the failure warning");
        }
    }

    static void lateCallbacks() {
        try (Rig rig = new Rig()) {
            rig.host.delayLight = true;
            rig.mode.configure(options(true, 5, 1));
            rig.select(true);
            PluginHost.CommandCallback stale = rig.host.pendingLight;
            rig.mode.light(1.0);
            stale.onResult(true, reading(true, true, 100.0), null);
            rig.advance(60_000);
            check(rig.host.off.get() == 1, "a late initial sensor read cannot override a newer light event");
        }
        for (String change : Arrays.asList("view", "light", "settings", "wake", "ownOff", "stop")) {
            try (Rig rig = new Rig()) {
                rig.host.delayOff = true;
                rig.mode.configure(options(true, 5, 1));
                rig.select(true);
                rig.advance(60_000);
                PluginHost.CommandCallback pending = rig.host.pendingOff;
                check(pending != null, "screen-off callback is pending before " + change);
                if (change.equals("view")) rig.select(false);
                if (change.equals("light")) { rig.mode.light(7.0); rig.mode.light(1.0); }
                if (change.equals("settings")) rig.mode.configure(options(false, 5, 1));
                if (change.equals("wake")) { rig.screen(false); rig.screen(true); }
                if (change.equals("ownOff")) rig.screen(false);
                if (change.equals("stop")) rig.mode.close();
                pending.onResult(true, null, null);
                boolean undo = !change.equals("ownOff") && !change.equals("stop");
                check(rig.host.on.get() == (undo ? 1 : 0), "late screen-off handles " + change + " without undoing its own normal power-off");
                if (change.equals("stop")) {
                    rig.advance(600_000);
                    check(rig.host.off.get() == 1, "stopped night worker cannot issue new commands");
                }
            }
        }
    }

    static void independentOfDownloads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.block = new CountDownLatch(1);
            fixture.entered = new CountDownLatch(1);
            AtomicLong clock = new AtomicLong(1);
            LightHost host = new LightHost();
            NextcloudPlugin plugin = new NextcloudPlugin(original -> original, clock::get);
            Map<String, Object> values = settings(fixture.url());
            values.putAll(options(true, 5, 1));
            plugin.start(host, values);
            try {
                check(fixture.propfinds.get() == 0 && fixture.gets.get() == 0, "night sensor support does not initiate idle Nextcloud work");
                show(plugin);
                check(fixture.entered.await(4, TimeUnit.SECONDS), "photo listing is blocked before night deadline");
                clock.addAndGet(60_001);
                await(() -> host.off.get() == 1, "night timer powers off independently of a blocked photo download worker");
                check(host.images.get() == 0, "night screen-off does not wait for the first photo");
                plugin.onEvent("ks.screen.state", Collections.singletonMap("on", false));
            } finally {
                plugin.stop();
                fixture.block.countDown(); fixture.block = null;
            }
            int before = host.off.get();
            clock.addAndGet(600_000);
            plugin.onEvent("ks.device.light", Collections.singletonMap("lux", 0));
            Thread.sleep(1100);
            check(host.off.get() == before, "plugin stop also stops its night policy and ignores later sensor events");
        }
    }

    static void daylightWake() {
        try (Rig rig = new Rig()) {
            rig.mode.configure(options(true, 5, 1));
            rig.select(true); rig.advance(60_000); rig.screen(false);
            rig.mode.light(5.0);
            check(rig.host.on.get() == 0, "night-owned display stays off at the threshold");
            rig.mode.light(6.0);
            check(rig.host.on.get() == 0, "native-style hysteresis keeps night mode in the 25-percent band");
            rig.mode.light(6.25);
            check(rig.host.on.get() == 0, "the exact 125-percent boundary does not flicker back to daylight");
            rig.mode.light(null);
            check(rig.host.on.get() == 0, "missing reading cannot claim daylight or wake the display");
            rig.mode.light(7.0);
            check(rig.host.on.get() == 1, "daylight wakes a screen powered off by night mode");
            rig.mode.light(7.0);
            check(rig.host.on.get() == 1, "later bright readings do not repeatedly wake an already restored screen");
            rig.screen(true);
            rig.mode.light(1.0); rig.advance(60_000); rig.screen(false);
            rig.mode.configure(options(false, 5, 1));
            check(rig.host.on.get() == 2, "disabling night mode restores a display owned by that policy");
        }
        try (Rig rig = new Rig()) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true);
            rig.screen(false); rig.mode.light(7.0);
            check(rig.host.on.get() == 0, "daylight does not wake a manually powered-off screen");
            rig.screen(true); rig.mode.light(1.0); rig.advance(60_000); rig.screen(false);
            rig.screen(true); rig.screen(false); rig.mode.light(7.0);
            check(rig.host.on.get() == 0, "a manual wake ends night ownership before a later manual power-off");
        }
        try (Rig rig = new Rig()) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true);
            rig.advance(60_000); rig.screen(false);
            rig.host.refuseWake = true;
            rig.mode.light(7.0); rig.mode.light(7.0); rig.mode.updateActivity();
            check(rig.host.on.get() == 1 && rig.mode.warning().contains("could not wake"), "wake failure is reported without a repeated bright-reading retry loop");
            rig.screen(true);
            check(rig.mode.warning().isEmpty(), "normal waking clears an obsolete wake failure warning");
        }
    }

    static Map<String, Object> options(boolean enabled, int lux, int minutes) {
        Map<String, Object> values = new HashMap<>();
        values.put("nightMode", enabled); values.put("nightLux", lux); values.put("nightMinutes", minutes);
        return values;
    }

    static Map<String, Object> reading(boolean present, boolean live, double lux) {
        Map<String, Object> value = new HashMap<>();
        value.put("present", present); value.put("live", live); value.put("lux", lux);
        return value;
    }

    static final class Rig implements AutoCloseable {
        final AtomicLong clock = new AtomicLong();
        final LightHost host = new LightHost();
        boolean selected, on = true;
        final NightMode mode = new NightMode(new Object(), host, new NightMode.Display() {
            @Override public boolean selected() { return selected; }
            @Override public boolean screenOn() { return on; }
        }, clock::get);
        void select(boolean value) { selected = value; mode.updateActivity(); }
        void screen(boolean value) { on = value; mode.screenState(value); mode.updateActivity(); }
        void advance(long milliseconds) { clock.addAndGet(milliseconds); mode.check(); }
        @Override public void close() { mode.close(); }
    }

    static final class LightHost extends Host {
        final AtomicInteger off = new AtomicInteger(), on = new AtomicInteger(), reads = new AtomicInteger();
        final AtomicInteger subscribes = new AtomicInteger(), unsubscribes = new AtomicInteger();
        volatile boolean present = true, live = true, refuseOff, refuseWake, delayLight, delayOff;
        volatile CommandCallback pendingLight, pendingOff;
        @Override public void subscribe(String event) { if (event.equals("device.light")) subscribes.incrementAndGet(); }
        @Override public void unsubscribe(String event) { if (event.equals("device.light")) unsubscribes.incrementAndGet(); }
        @Override public void executeCommand(String command, Map<String, Object> args, CommandCallback callback) {
            if (!args.isEmpty()) throw new AssertionError("night commands must not request permission prompts");
            if (command.equals("getLightLevel")) {
                reads.incrementAndGet();
                if (delayLight) pendingLight = callback;
                else callback.onResult(true, reading(present, live, 1.0), null);
            } else if (command.equals("screenOff")) {
                off.incrementAndGet();
                if (delayOff) pendingOff = callback;
                else callback.onResult(!refuseOff, null, refuseOff ? "secret host failure" : null);
            } else if (command.equals("screenOn")) { on.incrementAndGet(); callback.onResult(!refuseWake, null, refuseWake ? "secret wake failure" : null); }
            else super.executeCommand(command, args, callback);
        }
    }
}
