// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.*;

/** Build grouped cycles and preserve progress through periodic membership refreshes. */
final class PlaybackOrder {
    static final class Cycle {
        final List<DavClient.Photo> photos;
        final int cursor;
        Cycle(List<DavClient.Photo> photos, int cursor) { this.photos = photos; this.cursor = cursor; }
    }

    static List<DavClient.Photo> build(List<DavClient.Photo> photos, ShuffleMode mode, Random random, String last) {
        List<DavClient.Photo> result = new ArrayList<>(photos);
        if (mode == ShuffleMode.GLOBAL) Collections.shuffle(result, random);
        else if (mode == ShuffleMode.WITHIN) {
            for (int start = 0; start < result.size();) {
                int end = start + 1;
                while (end < result.size() && Objects.equals(result.get(start).source, result.get(end).source)) end++;
                Collections.shuffle(result.subList(start, end), random);
                start = end;
            }
        }
        if (mode != ShuffleMode.OFF && result.size() > 1 && result.get(0).key().equals(last)) {
            if (mode == ShuffleMode.GLOBAL || Objects.equals(result.get(0).source, result.get(1).source))
                Collections.swap(result, 0, 1);
        }
        return result;
    }

    static Cycle refresh(List<DavClient.Photo> photos, ShuffleMode mode, Random random,
                         List<DavClient.Photo> old, int cursor, String last) {
        List<DavClient.Photo> fresh = build(photos, mode, random, last);
        if (mode == ShuffleMode.GLOBAL || old.isEmpty()) return new Cycle(fresh, 0);
        if (cursor >= old.size()) return new Cycle(fresh, fresh.size());
        Map<String, DavClient.Photo> byKey = new HashMap<>();
        for (DavClient.Photo photo : fresh) byKey.put(photo.key(), photo);
        DavClient.Photo anchor = null;
        for (int i = cursor; i < old.size(); i++) {
            if (byKey.containsKey(old.get(i).key())) { anchor = byKey.get(old.get(i).key()); break; }
        }
        if (anchor == null) return new Cycle(fresh, 0);
        if (mode == ShuffleMode.OFF) {
            for (int i = 0; i < fresh.size(); i++) if (fresh.get(i).key().equals(anchor.key())) return new Cycle(fresh, i);
        }
        int start = 0;
        while (start < fresh.size() && !Objects.equals(fresh.get(start).source, anchor.source)) start++;
        int end = start;
        while (end < fresh.size() && Objects.equals(fresh.get(end).source, anchor.source)) end++;
        List<DavClient.Photo> group = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < cursor; i++) {
            DavClient.Photo photo = byKey.get(old.get(i).key());
            if (photo != null && Objects.equals(photo.source, anchor.source) && used.add(photo.key())) group.add(photo);
        }
        int played = group.size();
        for (int i = cursor; i < old.size(); i++) {
            DavClient.Photo photo = byKey.get(old.get(i).key());
            if (photo != null && Objects.equals(photo.source, anchor.source) && used.add(photo.key())) group.add(photo);
        }
        for (int i = start; i < end; i++) if (used.add(fresh.get(i).key())) group.add(fresh.get(i));
        List<DavClient.Photo> result = new ArrayList<>(fresh.size());
        result.addAll(fresh.subList(0, start)); result.addAll(group); result.addAll(fresh.subList(end, fresh.size()));
        return new Cycle(result, start + played);
    }

    private PlaybackOrder() {}
}
