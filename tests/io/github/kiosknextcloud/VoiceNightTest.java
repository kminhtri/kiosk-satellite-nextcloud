// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import me.jxl.kiosk.plugins.PluginHost;
import static io.github.kiosknextcloud.NextcloudTest.*;
import static io.github.kiosknextcloud.NightModeTest.*;

final class VoiceNightTest {
    static void run() throws Exception {
        turns();
        overlays();
        interactions();
        snapshots();
        latePowerOff();
        ownership();
        lifecycle();
        pluginEvents();
    }

    private static void event(Rig rig, String name, String field, Object value) {
        rig.mode.voiceEvent("ks.voice." + name, Collections.singletonMap(field, value));
    }

    private static void turns() {
        for (String state : Arrays.asList("listening", "processing", "responding")) {
            try (Rig rig = new Rig(new VoiceHost())) {
                rig.mode.configure(options(true, 5, 1)); rig.select(true); rig.advance(59_999);
                event(rig, "state", "state", state); rig.advance(120_000);
                check(rig.host.off.get() == 0, "night mode cannot power off during voice " + state);
                event(rig, "state", "state", "idle"); rig.advance(59_999);
                check(rig.host.off.get() == 0, "voice " + state + " finishing grants a full new delay");
                rig.advance(1);
                check(rig.host.off.get() == 1, "night mode resumes after voice " + state + " ends");
            }
        }
    }

    private static void overlays() {
        for (String overlay : Arrays.asList("full", "docked")) {
            try (Rig rig = new Rig(new VoiceHost())) {
                rig.mode.configure(options(true, 5, 1)); rig.select(true);
                event(rig, "overlay", "overlay", overlay);
                event(rig, "state", "state", "idle"); rig.advance(120_000);
                check(rig.host.off.get() == 0, "an idle voice turn still holds the screen while its " + overlay + " answer stays visible");
                event(rig, "state", "state", "processing");
                event(rig, "overlay", "overlay", "hidden"); rig.advance(120_000);
                check(rig.host.off.get() == 0, "hiding the overlay cannot end a voice turn still processing");
                event(rig, "state", "state", "idle"); rig.advance(59_999);
                check(rig.host.off.get() == 0, "closing Assist starts a fresh delay");
                rig.advance(1); check(rig.host.off.get() == 1, "night screen-off resumes after both turn and overlay end");
            }
        }
    }

    private static void interaction(Rig rig, String source, boolean active) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("source", source); payload.put("active", active);
        rig.mode.voiceEvent("ks.voice.interaction", payload);
    }

    private static void interactions() {
        try (Rig rig = new Rig(new VoiceHost())) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true);
            interaction(rig, "page", true); interaction(rig, "command", true);
            interaction(rig, "page", false); rig.advance(120_000);
            check(rig.host.off.get() == 0, "ending one interaction does not release another source's hold");
            interaction(rig, "sendspin", true); interaction(rig, "command", false); rig.advance(120_000);
            check(rig.host.off.get() == 0, "sendspin voice interaction also holds night screen-off");
            interaction(rig, "sendspin", false); rig.advance(60_000);
            check(rig.host.off.get() == 1, "night countdown resumes only after every voice interaction ends");
        }
    }

    private static void snapshots() {
        VoiceHost host = new VoiceHost(); host.delayVoice = true;
        try (Rig rig = new Rig(host)) {
            rig.mode.configure(options(true, 5, 1));
            PluginHost.CommandCallback first = host.pendingVoice;
            rig.select(true);
            PluginHost.CommandCallback current = host.pendingVoice;
            rig.advance(120_000);
            check(host.off.get() == 0 && rig.mode.warning().contains("Voice Assist"), "initial voice read must finish before night screen-off is allowed");
            event(rig, "state", "state", "responding"); event(rig, "overlay", "overlay", "full");
            current.onResult(true, voice("idle", "hidden"), null);
            first.onResult(true, voice("idle", "hidden"), null);
            rig.advance(120_000);
            check(host.off.get() == 0, "late initial idle and hidden values cannot replace newer busy voice events");
            event(rig, "state", "state", "idle"); rig.advance(120_000);
            check(host.off.get() == 0, "late snapshot preserves the independently updated overlay hold");
            event(rig, "overlay", "overlay", "hidden"); rig.advance(60_000);
            check(host.off.get() == 1, "night mode resumes once the current voice read and live holds finish");
        }
        host = new VoiceHost(); host.state = "processing"; host.overlay = "docked";
        try (Rig rig = new Rig(host)) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true); rig.advance(120_000);
            check(host.off.get() == 0, "starting a plugin during an existing Assist session honors its snapshot");
        }
    }

    private static void latePowerOff() {
        for (boolean finishBeforeCallback : new boolean[] {false, true}) {
            VoiceHost host = new VoiceHost(); host.delayOff = true;
            try (Rig rig = new Rig(host)) {
                rig.mode.configure(options(true, 5, 1)); rig.select(true); rig.advance(60_000);
                PluginHost.CommandCallback pending = host.pendingOff;
                event(rig, "state", "state", "listening");
                if (finishBeforeCallback) event(rig, "state", "state", "idle");
                rig.screen(false);
                pending.onResult(true, null, null);
                check(host.on.get() == 1, "late screen-off is undone when a voice interaction started before its completion");
            }
        }
    }

    private static void ownership() {
        try (Rig rig = new Rig(new VoiceHost())) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true); rig.advance(60_000); rig.screen(false);
            event(rig, "state", "state", "listening");
            check(rig.host.on.get() == 1, "Assist restores a screen previously powered off by plugin night mode");
            event(rig, "overlay", "overlay", "full");
            check(rig.host.on.get() == 1, "overlapping voice signals do not duplicate the restore request");
        }
        try (Rig rig = new Rig(new VoiceHost())) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true); rig.screen(false);
            event(rig, "state", "state", "listening");
            check(rig.host.on.get() == 0, "the night plugin does not override a manual screen-off during Assist");
        }
    }

    private static void lifecycle() {
        VoiceHost host = new VoiceHost();
        try (Rig rig = new Rig(host)) {
            rig.mode.configure(Collections.emptyMap());
            check(host.voiceReads.get() == 0 && host.voiceSubscriptions.isEmpty(), "disabled night mode does not observe voice activity");
            rig.mode.configure(options(true, 5, 1));
            check(host.voiceSubscriptions.equals(new HashSet<>(Arrays.asList("voice.state", "voice.overlay", "voice.interaction"))), "voice protection subscribes only to activity, not speech or transcripts");
            rig.mode.configure(options(false, 5, 1));
            check(host.voiceSubscriptions.isEmpty(), "disabling night mode removes voice subscriptions");
            rig.mode.configure(options(true, 5, 1)); rig.select(true);
            event(rig, "state", "state", "processing");
            event(rig, "state", "state", "unexpected"); rig.advance(120_000);
            check(host.off.get() == 0, "an invalid voice payload cannot clear an active hold");
        }
        check(host.voiceSubscriptions.isEmpty(), "stopping night mode removes voice subscriptions");
        host = new VoiceHost(); host.rejectVoiceState = true; host.failRead = true;
        try (Rig rig = new Rig(host)) {
            rig.mode.configure(options(true, 5, 1)); rig.select(true);
            event(rig, "overlay", "overlay", "full"); rig.advance(120_000);
            check(host.off.get() == 0, "older host fallback still honors its supported voice overlay feed");
            event(rig, "overlay", "overlay", "hidden"); rig.advance(60_000);
            check(host.off.get() == 1, "unsupported voice-state API does not permanently disable night mode");
        }
        host = new VoiceHost(); host.delayVoice = true;
        try (Rig rig = new Rig(host)) {
            rig.mode.configure(options(true, 5, 1));
            PluginHost.CommandCallback pending = host.pendingVoice;
            rig.mode.close(); pending.onResult(true, voice("processing", "full"), null);
            rig.advance(120_000);
            check(host.off.get() == 0 && host.on.get() == 0, "stopped night mode ignores late voice read callbacks");
        }
    }

    private static void pluginEvents() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.block = new CountDownLatch(1); fixture.entered = new CountDownLatch(1);
            AtomicLong clock = new AtomicLong(1);
            VoiceHost host = new VoiceHost();
            NextcloudPlugin plugin = new NextcloudPlugin(bytes -> bytes, clock::get);
            Map<String, Object> values = settings(fixture.url()); values.putAll(options(true, 5, 1));
            plugin.start(host, values);
            try {
                check(fixture.propfinds.get() == 0, "voice observation does not start idle Nextcloud work");
                show(plugin);
                check(fixture.entered.await(4, TimeUnit.SECONDS), "photo worker is blocked before Assist protection is tested");
                plugin.onEvent("ks.voice.state", Collections.singletonMap("state", "listening"));
                clock.addAndGet(120_000); Thread.sleep(1_100);
                check(host.off.get() == 0, "plugin forwards voice activity even while photo listing is blocked");
                plugin.onEvent("ks.voice.overlay", Collections.singletonMap("overlay", "docked"));
                plugin.onEvent("ks.voice.state", Collections.singletonMap("state", "idle"));
                clock.addAndGet(120_000); Thread.sleep(1_100);
                check(host.off.get() == 0, "plugin keeps a docked Assist answer lit after the turn becomes idle");
                plugin.onEvent("ks.voice.overlay", Collections.singletonMap("overlay", "hidden"));
                clock.addAndGet(59_999); Thread.sleep(1_100);
                check(host.off.get() == 0, "plugin grants a full delay after closing Assist");
                clock.addAndGet(1);
                await(() -> host.off.get() == 1, "night timer resumes after Assist without waiting for the HTTP worker");
            } finally { plugin.stop(); fixture.block.countDown(); }
        }
    }

    private static Map<String, Object> voice(String state, String overlay) {
        Map<String, Object> values = new HashMap<>();
        values.put("enabled", true); values.put("state", state); values.put("overlay", overlay);
        return values;
    }

    static final class VoiceHost extends LightHost {
        final AtomicInteger voiceReads = new AtomicInteger();
        final Set<String> voiceSubscriptions = new HashSet<>();
        boolean delayVoice, failRead, rejectVoiceState;
        String state = "idle", overlay = "hidden";
        PluginHost.CommandCallback pendingVoice;
        @Override public void subscribe(String event) {
            if (event.equals("voice.state") && rejectVoiceState) throw new IllegalArgumentException("old host");
            if (event.startsWith("voice.")) voiceSubscriptions.add(event);
            super.subscribe(event);
        }
        @Override public void unsubscribe(String event) { voiceSubscriptions.remove(event); super.unsubscribe(event); }
        @Override public void executeCommand(String command, Map<String, Object> args, PluginHost.CommandCallback callback) {
            if (command.equals("getVoiceState")) {
                voiceReads.incrementAndGet();
                if (delayVoice) pendingVoice = callback;
                else callback.onResult(!failRead, failRead ? null : voice(state, overlay), failRead ? "secret host error" : null);
            } else super.executeCommand(command, args, callback);
        }
    }

    private VoiceNightTest() {}
}
