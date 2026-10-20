# Personal Nextcloud Photos albums

In Plugin Manager, choose **Photo source → Albums** or **Folders and albums**, then add rows under **Nextcloud Photos albums**. Enter the exact personal album name, such as `Holidays 2026`, using the capitalization shown in the Photos app. Do not enter its browser URL or a filesystem path. Names containing slashes are not supported.

Use the same server URL, username and app password as for Files folders. The Nextcloud Photos app must be enabled. Only the selected sources are requested, and only when this screensaver is visible and the screen is on. With Albums selected, old folder settings are retained but ignored; switching back to Folders restores them.

This release supports personal albums belonging to the connected account. Public links, albums shared by another account, and Memories-specific collections are outside its scope. No album is created, edited or deleted. Membership changes are picked up on the next refresh. An empty album contributes no photos; a missing album or access failure fails the refresh and preserves the previously loaded slideshow, if any.

## Read protocol

The client constructs this authenticated endpoint beneath the configured installation subpath:

```text
remote.php/dav/photos/{username}/albums/{album-name}/
```

Each component is encoded as a URL path segment. A `PROPFIND` with `Depth: 1` requests `DAV:resourcetype`, `DAV:getcontenttype`, `DAV:getetag` and `oc:fileid`. Only successful property groups and direct child JPEG, PNG and WebP entries are accepted. Photos commonly have a `fileid-filename` DAV name; the client uses the server's returned href rather than inventing a filename or resolving the original folder.

Images are fetched with GET on those album hrefs. Redirects, external origins, embedded credentials, queries, fragments, encoded separators, parent paths, unselected albums and deeper album descendants are rejected. Original-path and remote download URL properties are not requested or followed. Albums never contribute recursive children, even when **Include subfolders** is enabled for Files folders.

If an original download exceeds 8 MiB, the client can use Nextcloud's authenticated `/index.php/core/preview` endpoint on the configured origin and installation subpath. It uses the selected photo's numeric `oc:fileid`, requests `x=1920`, `y=1920`, `a=1`, `mode=cover`, `forceIcon=0` and `mimeFallback=0`, and keeps the same 8 MiB response cap. This URL is constructed locally, with no server-supplied download or preview URL. Redirects are still refused. JPEG, PNG or WebP previews are required; a missing preview or file ID produces an actionable error. Preview downloads share the same visibility gating and cancellation as original downloads.

The 32 selected-source, 100 scanned-collection and 5000 unique-photo limits are shared by folders and albums. Nextcloud file IDs deduplicate photos across all selected sources when provided; otherwise deduplication uses the DAV URL. Folder identity checks remain separate from album collection IDs.

The WebDAV fixture mirrors the protocol described by the upstream implementation, without copying its server code:

- [Personal album endpoint and naming](https://github.com/nextcloud/photos/blob/master/src/store/albums.ts)
- [Album collections and direct children](https://github.com/nextcloud/photos/blob/master/lib/Sabre/Album/AlbumRootBase.php)
- [Photo name, content type and ETag](https://github.com/nextcloud/photos/blob/master/lib/Sabre/CollectionPhoto.php)
- [File ID property](https://github.com/nextcloud/photos/blob/master/lib/Sabre/PropFindPlugin.php)
- [GET on album photos](https://github.com/nextcloud/photos/blob/master/lib/Sabre/Album/AlbumPhoto.php)
- [Authenticated preview by file ID](https://github.com/nextcloud/server/blob/master/core/Controller/PreviewController.php)
