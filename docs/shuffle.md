# Shuffle modes and large libraries

**Shuffle mode** replaces the old Shuffle switch. The choices are:

- **Off**: keep collection traversal order and the photo order returned by Nextcloud. This does not add a chronological or filename sort.
- **Within each source**: keep collection traversal order and shuffle photos separately in each personal album or individual Files folder. Recursive subfolders are separate collections; their photos are not mixed into their parent's shuffled group.
- **Across all sources (up to 20000)**: shuffle the global, deduplicated selection across all albums and folders. The limit is **20000 unique photos TOTAL**, not 20000 per album.

For example, with albums A and B, Within plays all of A's photos in shuffled order, then all of B's photos in shuffled order. Across can interleave photos from A and B. Off plays A and B in their traversal order using Nextcloud's own photo order. Global/Within cycles do not repeat a retained photo within that cycle; refreshes and changes to the library can change the selection.

## Progressive startup

The plugin first reads the first non-empty album or individual folder and gives its first image request priority. It then continues building the complete photo index on a separate worker and DAV connection, reusing the metadata already read for that first collection. Slow metadata requests for later sources do not block the image-download worker. The first image still requires that first non-empty collection's own listing and image request to finish.

In Across mode, the provisional album/folder continues rotating while indexing runs. Once the global selection is ready, it is staged until the next photo deadline (or Next photo action). Completing an index does not interrupt or shorten the current photo; prefetch does not activate the replacement plan. If the total exceeds 20000, playback keeps the per-collection plan and its explicit fallback status instead.

Off and Within can progress through collections during indexing. When a complete bounded index becomes available, they adopt it at a photo boundary while preserving progress, including when the provisional album has just finished. During provisional playback, deduplication is collection-local; a completed global index applies the cross-source deduplication described below.

Index errors remain visible while available photos continue playing, and are retried after 30 seconds. A failed first image does not prevent discovery of other sources. Hiding the screensaver, screen-off and plugin shutdown cancel both image and index requests. After waking, cached/current photos can resume while a canceled index is restarted; late results from a canceled session cannot replace the active plan.

## More than 20000 photos in total

If the deduplicated global selection exceeds 20000 photos, playback automatically keeps only one collection's metadata at a time. After that collection finishes, it loads the next one. Empty collections are skipped for playback but still count toward traversal. The bounds of 32 configured sources, 20 subfolder levels and 16 MiB per listing remain in effect. **Folder and album scan limit** defaults to 1000 traversed collections and allows 100 to 10000 with Include subfolders enabled; a higher limit permits larger folder trees at the cost of more requests and potentially slower scans.

Off remains ordered. Within still shuffles only the current collection. **Across automatically falls back to Within**, and the runtime status explicitly reports that global shuffle is unavailable above 20000 photos TOTAL. The chosen setting is retained: a full rescan can restore global shuffle if the library becomes small enough again.

Each individual album/folder must contain **at most 20000 photos**. A larger individual collection produces an actionable error asking you to split it; photos are not silently sampled or discarded. The mode does not randomly select albums or change their traversal order.

Deduplication in large-library playback is local to each collection. Photos included in different albums or overlapping folders can reappear when those collections play. Avoid overlapping sources if that is undesirable. This keeps the metadata index bounded instead of retaining IDs for the entire large library.

## Refresh and offline behavior

Periodic refresh preserves progress in Off and Within modes. With a complete bounded index, the replacement is built in the background and adopted at a photo boundary while the current plan keeps playing. In large-library playback, periodic refresh reloads the active collection, discovers new child folders of that collection when recursion is enabled, and grants later collections their turn instead of restarting at the first one. Other collections update when they are reached. Completing the traversal or using **Refresh photos** restarts with the first available collection and scans the rest in the background to re-evaluate global-shuffle eligibility. The manual action restarts the playback plan.

The 2–30-photo image cache remains independent of library size. During network failures, cached images can continue rotating and the failed collection request is retried after backoff. A failed source transition keeps its place for retry. Requests and prefetching still stop while the frame is hidden or the screen is off.

## Upgrading

The old switch is preserved as a hidden compatibility setting. Shuffle enabled migrates to Across; disabled migrates to Off. The plugin saves only the new shuffle mode and its migration marker, leaving connection credentials, source selections and night settings intact. Older hosts that cannot persist settings updates keep an in-memory migrated mode and show an update warning.
