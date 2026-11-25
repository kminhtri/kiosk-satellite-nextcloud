// SPDX-License-Identifier: Apache-2.0
package io.github.kiosknextcloud;

import java.util.ArrayList;
import java.util.List;

/** Successful displays only. Metadata is bounded independently of the resized-image cache. */
final class PhotoHistory {
    static final int LIMIT = 100;
    private final List<DavClient.Photo> photos = new ArrayList<>();
    private int position = -1;

    DavClient.Photo previous() { return position > 0 ? photos.get(position - 1) : null; }
    DavClient.Photo next() { return position + 1 < photos.size() ? photos.get(position + 1) : null; }

    void move(int direction) {
        if (direction == -1 && previous() != null) position--;
        else if (direction == 1 && next() != null) position++;
    }

    void record(DavClient.Photo photo) {
        if (position >= 0 && photos.get(position).key().equals(photo.key())) return;
        photos.subList(position + 1, photos.size()).clear();
        photos.add(photo);
        if (photos.size() > LIMIT) photos.remove(0);
        position = photos.size() - 1;
    }

    void clear() { photos.clear(); position = -1; }
}
