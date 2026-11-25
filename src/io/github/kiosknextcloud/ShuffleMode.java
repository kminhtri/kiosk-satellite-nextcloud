// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;

enum ShuffleMode {
    OFF("Off"), WITHIN("Within each source"), GLOBAL("Across all sources (up to 20000)");
    final String label;
    ShuffleMode(String label) { this.label = label; }
    private static boolean migrated(Map<String, Object> settings) {
        Object version = settings.get("shuffleModeVersion");
        return version instanceof Number && ((Number) version).intValue() == 1;
    }

    static ShuffleMode read(Map<String, Object> settings) {
        Object selected = settings.get("shuffleMode");
        boolean migrated = migrated(settings);
        for (ShuffleMode mode : values())
            if (mode.label.equals(selected) && (migrated || mode != GLOBAL)) return mode;
        if (migrated) throw new IllegalArgumentException("Choose a valid Shuffle mode.");
        return Boolean.FALSE.equals(settings.get("shuffle")) ? OFF : GLOBAL;
    }

    static Map<String, Object> migration(Map<String, Object> settings) {
        if (migrated(settings)) return Collections.emptyMap();
        Map<String, Object> patch = new HashMap<>();
        patch.put("shuffleMode", read(settings).label); patch.put("shuffleModeVersion", 1);
        return patch;
    }
}
