// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;
import me.jxl.kiosk.plugins.PluginHost;

/** Observe voice activity without speech or transcripts. All state shares the night policy's lock. */
final class VoiceActivity {
    private final Object lock;
    private final PluginHost host;
    private final Runnable changed;
    private final Set<String> subscriptions = new HashSet<>();
    private final Set<String> interactions = new HashSet<>();
    private boolean started, ready, turn, overlay;
    private long generation, readRevision, stateRevision, overlayRevision;

    VoiceActivity(Object lock, PluginHost host, Runnable changed) {
        this.lock = lock; this.host = host; this.changed = changed;
    }

    void start() {
        synchronized (lock) {
            if (started) return;
            started = true; generation++;
            for (String event : Arrays.asList("voice.interaction", "voice.state", "voice.overlay")) {
                try { host.subscribe(event); subscriptions.add(event); }
                catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | LinkageError ignored) {
                    // Older SDK 1 hosts may expose only the interaction or overlay feed.
                }
            }
            refresh();
        }
    }

    void refresh() {
        synchronized (lock) {
            if (!started) return;
            final long session = generation, revision = ++readRevision;
            final long stateAtRead = stateRevision, overlayAtRead = overlayRevision;
            ready = false;
            changed.run();
            try {
                host.executeCommand("getVoiceState", Collections.emptyMap(), (ok, data, error) -> {
                    synchronized (lock) {
                        if (!started || session != generation || revision != readRevision) return;
                        if (ok && data instanceof Map) {
                            Map<?, ?> value = (Map<?, ?>) data;
                            if (stateAtRead == stateRevision) updateState(value.get("state"));
                            if (overlayAtRead == overlayRevision) updateOverlay(value.get("overlay"));
                        }
                        // A host without this snapshot falls back to whichever event feeds it supports.
                        ready = true;
                        changed.run();
                    }
                });
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | LinkageError ignored) {
                ready = true;
                changed.run();
            }
        }
    }

    void event(String event, Map<String, Object> payload) {
        synchronized (lock) {
            if (!started) return;
            if (event.equals("ks.voice.state")) {
                if (!updateState(payload.get("state"))) return;
                stateRevision++;
            } else if (event.equals("ks.voice.overlay")) {
                if (!updateOverlay(payload.get("overlay"))) return;
                overlayRevision++;
            } else if (event.equals("ks.voice.interaction")) {
                Object active = payload.get("active"), source = payload.get("source");
                if (!(active instanceof Boolean) || !Arrays.asList("page", "sendspin", "command").contains(source)) return;
                if ((Boolean) active) interactions.add((String) source);
                else interactions.remove(source);
            } else return;
            changed.run();
        }
    }

    private boolean updateState(Object value) {
        if (!Arrays.asList("idle", "listening", "processing", "responding").contains(value)) return false;
        turn = !"idle".equals(value);
        return true;
    }

    private boolean updateOverlay(Object value) {
        if (!Arrays.asList("hidden", "full", "docked").contains(value)) return false;
        overlay = !"hidden".equals(value);
        return true;
    }

    boolean active() { return started && (turn || overlay || !interactions.isEmpty()); }
    boolean pending() { return started && !ready; }
    boolean blocksScreenOff() { return active() || pending(); }

    void stop() {
        synchronized (lock) {
            started = false; generation++; readRevision++;
            ready = false; turn = false; overlay = false; interactions.clear();
            for (String event : subscriptions) {
                try { host.unsubscribe(event); }
                catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | LinkageError ignored) { /* host may already be revoked */ }
            }
            subscriptions.clear();
            changed.run();
        }
    }
}
