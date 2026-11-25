// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;

/** Foreground playback never waits for the complete index. Only immutable results cross threads. */
final class PhotoPlaylist implements AutoCloseable {
    private final DavClient dav;
    private final ShuffleMode mode;
    private final Random random;
    private final BackgroundIndex index;
    private List<DavClient.Photo> photos = Collections.emptyList(), cycle = Collections.emptyList();
    private int cursor;
    private DavClient.Traversal traversal;
    private DavClient.Folder currentFolder;
    private boolean ready, sequential, indexing, large, pendingSequential, errorUnreported, indexReleased, seedPending;
    private List<DavClient.Photo> pendingIndex;
    private IOException indexError;
    private long retryIndexAt, selectionVersion;
    private String last;

    PhotoPlaylist(DavClient dav, ShuffleMode mode, Random random) {
        this.dav = dav; this.mode = mode; this.random = random;
        index = new BackgroundIndex(dav.config);
    }

    void setActive(boolean active) { dav.setActive(active); index.setActive(active); }

    void refresh(boolean full) throws IOException {
        if (ready && sequential && !full && currentFolder != null) {
            DavClient.Batch batch = traversal.reload(currentFolder);
            if (batch.photos.isEmpty()) advanceSource();
            else setPhotos(batch.photos, true);
            return;
        }
        if (ready && !full && !isEmpty()) {
            indexing = true; pendingIndex = null; indexError = null; retryIndexAt = 0;
            indexReleased = true; seedPending = false;
            index.start(null, Collections.emptyList());
            return;
        }
        startFirst(true);
    }

    private void startFirst(boolean scan) throws IOException {
        DavClient.Traversal nextTraversal = dav.traversal();
        DavClient.Batch first = nonEmpty(nextTraversal);
        List<DavClient.Photo> listed = first == null ? Collections.emptyList() : first.photos;
        List<DavClient.Photo> ordered = PlaybackOrder.build(listed, effectiveMode(true), random, last);
        traversal = nextTraversal; currentFolder = first == null ? null : first.folder;
        photos = listed; cycle = ordered; cursor = 0; sequential = first != null; ready = true;
        selectionVersion++; pendingSequential = false; pendingIndex = null;
        indexError = null; errorUnreported = false; retryIndexAt = 0;
        large = !scan && first != null;
        indexing = scan && first != null;
        indexReleased = false; seedPending = indexing;
        index.cancelPending();
    }

    void beginIndexing() {
        indexReleased = true;
        if (indexing && seedPending) {
            if (index.start(traversal, photos)) seedPending = false;
        }
    }

    /** Poll once per frame tick; a completed index is only adopted by next(), never by prefetch. */
    boolean maintain(long now) {
        BackgroundIndex.Result result = index.poll();
        boolean changed = result != null;
        if (result != null) {
            if (result.tooLarge) {
                large = true; indexing = false; pendingIndex = null; indexError = null;
                pendingSequential = !sequential;
            } else if (result.error != null) {
                indexError = result.error; errorUnreported = true; retryIndexAt = now + 30_000;
            } else {
                pendingIndex = result.photos; indexError = null; indexing = false;
            }
        }
        if (indexing && indexReleased && pendingIndex == null && !index.hasTask() && now >= retryIndexAt) {
            index.start(null, Collections.emptyList());
            seedPending = false;
        }
        return changed;
    }

    IOException takeIndexError() {
        if (!errorUnreported) return null;
        errorUnreported = false; return indexError;
    }

    private void adoptIndex() throws IOException {
        if (pendingSequential) { startFirst(false); return; }
        if (pendingIndex == null) return;
        List<DavClient.Photo> progress = cycle;
        if (sequential && mode != ShuffleMode.GLOBAL && cursor >= cycle.size() && currentFolder != null) {
            int after = -1;
            for (int i = 0; i < pendingIndex.size(); i++)
                if (Objects.equals(pendingIndex.get(i).source, currentFolder.url)) after = i + 1;
            if (after > 0 && after < pendingIndex.size()) {
                // The provisional album ended, not the entire newly indexed library.
                progress = new ArrayList<>(cycle); progress.add(pendingIndex.get(after));
            }
        }
        PlaybackOrder.Cycle replacement = PlaybackOrder.refresh(pendingIndex, mode, random, progress, cursor, last);
        photos = pendingIndex; cycle = replacement.photos; cursor = replacement.cursor;
        pendingIndex = null; sequential = false; traversal = null; currentFolder = null;
        indexing = false; large = false; selectionVersion++;
    }

    boolean indexReady() { return pendingIndex != null || pendingSequential; }
    boolean scanning() { return indexing; }

    boolean indexFailed() { return indexError != null; }

    long selectionVersion() { return selectionVersion; }

    private void repeatCurrent() {
        cycle = PlaybackOrder.build(photos, effectiveMode(sequential), random, last); cursor = 0;
    }

    private boolean waitingForGlobal() {
        return mode == ShuffleMode.GLOBAL && sequential && (indexing || pendingIndex != null) && !large;
    }

    private void ensureCycle() throws IOException {
        if (cursor < cycle.size()) return;
        if (waitingForGlobal()) repeatCurrent();
        else if (sequential && currentFolder != null) advanceSource();
        else if (!photos.isEmpty()) repeatCurrent();
    }

    private void cycleCompleted() throws IOException {
        // Begin a new traversal and defer its index until the first image request has had priority.
        startFirst(true);
    }

    private ShuffleMode effectiveMode(boolean perSource) {
        return perSource && mode == ShuffleMode.GLOBAL ? ShuffleMode.WITHIN : mode;
    }

    private static DavClient.Batch nonEmpty(DavClient.Traversal traversal) throws IOException {
        DavClient.Batch batch;
        while ((batch = traversal.next()) != null) if (!batch.photos.isEmpty()) return batch;
        return null;
    }

    private void setPhotos(List<DavClient.Photo> listed, boolean preserveProgress) {
        PlaybackOrder.Cycle replacement = preserveProgress
                ? PlaybackOrder.refresh(listed, effectiveMode(sequential), random, cycle, cursor, last)
                : new PlaybackOrder.Cycle(PlaybackOrder.build(listed, effectiveMode(sequential), random, last), 0);
        photos = listed; cycle = replacement.photos; cursor = replacement.cursor;
        selectionVersion++;
    }

    private void advanceSource() throws IOException {
        DavClient.Batch next = nonEmpty(traversal);
        if (next == null) { cycleCompleted(); return; }
        setPhotos(next.photos, false);
        currentFolder = next.folder;
    }

    boolean needsNetwork() { return pendingSequential || (sequential && !waitingForGlobal() && cursor >= cycle.size()); }

    DavClient.Photo peek() throws IOException {
        // A pending plan takes effect in next(). Prefetch must not block on a source it will replace.
        if (pendingSequential) return null;
        if (pendingIndex != null) return cursor < cycle.size() ? cycle.get(cursor) : null;
        ensureCycle();
        return cursor < cycle.size() ? cycle.get(cursor) : null;
    }

    DavClient.Photo next() throws IOException {
        adoptIndex();
        DavClient.Photo result = peek();
        if (result != null) { cursor++; last = result.key(); }
        return result;
    }

    boolean isEmpty() { return photos.isEmpty(); }
    int size() { return pendingIndex == null ? photos.size() : pendingIndex.size(); }
    boolean perSource() { return sequential; }
    boolean globalFallback() { return large && mode == ShuffleMode.GLOBAL; }
    List<DavClient.Photo> activePhotos() { return photos; }
    String note() {
        if (indexError != null) return "Photo indexing is incomplete. " + ErrorMessages.connection(indexError) + " Playback continues with available photos.";
        if (pendingIndex != null) return mode == ShuffleMode.GLOBAL
                ? "Photo index ready; global shuffle starts after the current photo (up to 20000 total)."
                : "Photo index ready; the updated selection starts after the current photo.";
        if (indexing) return sequential && mode == ShuffleMode.GLOBAL
                ? "Playing the first available album/folder while indexing other sources; global shuffle supports up to 20000 photos total."
                : "Indexing other sources in the background; playback continues.";
        if (!large) return "";
        return mode == ShuffleMode.GLOBAL
                ? "More than 20000 photos in total: global shuffle is unavailable; shuffling within each album/folder instead (max 20000 per collection)."
                : "More than 20000 photos in total: loading one album/folder at a time (max 20000 per collection).";
    }

    @Override public void close() { index.close(); dav.close(); }
}
