// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** A separate DAV connection keeps slow metadata requests from blocking photo downloads. */
final class BackgroundIndex implements AutoCloseable {
    static final class Result {
        final List<DavClient.Photo> photos;
        final IOException error;
        final boolean tooLarge;
        Result(List<DavClient.Photo> photos, IOException error, boolean tooLarge) {
            this.photos = photos; this.error = error; this.tooLarge = tooLarge;
        }
    }

    private final DavClient dav;
    private final ExecutorService worker;
    private Future<Result> task;
    private boolean active = true, closed;

    BackgroundIndex(Config config) {
        dav = new DavClient(config);
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NextcloudPhotoIndex");
            thread.setDaemon(true); return thread;
        });
    }

    synchronized boolean start(DavClient.Traversal after, List<DavClient.Photo> seed) {
        if (closed || !active) return false;
        cancel(); dav.setActive(true);
        DavClient.Traversal cursor = after == null ? dav.traversal() : dav.continuation(after);
        List<DavClient.Photo> first = new ArrayList<>(seed);
        task = worker.submit(() -> {
            try { return new Result(dav.list(cursor, first), null, false); }
            catch (DavClient.PhotoLimitException error) {
                return error.singleCollection ? new Result(null, error, false) : new Result(null, null, true);
            } catch (IOException error) { return new Result(null, error, false); }
        });
        return true;
    }

    synchronized boolean hasTask() { return task != null; }
    synchronized void cancelPending() { cancel(); if (!closed) dav.setActive(active); }

    synchronized Result poll() {
        if (task == null || !task.isDone()) return null;
        Future<Result> completed = task; task = null;
        try { return completed.get(); }
        catch (CancellationException ignored) { return null; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
        catch (ExecutionException error) {
            if (error.getCause() instanceof OutOfMemoryError) throw (OutOfMemoryError) error.getCause();
            return new Result(null, new IOException("Nextcloud photo indexing failed. Retrying in 30 seconds."), false);
        }
    }

    synchronized void setActive(boolean value) {
        if (closed) return;
        active = value;
        if (!active) cancel();
        dav.setActive(active);
    }

    private void cancel() {
        if (task != null) task.cancel(true);
        task = null; dav.setActive(false);
    }

    @Override public synchronized void close() {
        closed = true; active = false; cancel(); worker.shutdownNow(); dav.close();
    }
}
