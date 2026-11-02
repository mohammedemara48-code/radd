# ردّ — professional native Android design (phase 1)

Kotlin · Jetpack Compose · Material 3 · Arabic RTL (Cairo font, SIL OFL) · minSdk 26 · targetSdk 35 · **no INTERNET permission**.
Project: `android-app/` (the old `android/` folder is kept only for reference until phase 2 replaces it).
Previews: `previews/*.png`, rendered by the real Compose screens through Paparazzi (`gradle -Ppreviews=true :app:testDebugUnitTest`).

## Promise and limits (shown in the UI, not hidden in docs)
ردّ does **not** read flash sectors that were wiped or TRIMmed, and cannot without root. It finds what still leaves a trace.
Every screen that matters says so: the welcome screen ("الصراحة قبل ما تبدأ"), the empty state, the results header (intact vs partial; the grey band is what was lost), and the restore summary.
Android 13+ blocks `Android/data` for every normal app even with all-files access, so app-private caches there are out of reach; `Android/media` is readable and is where WhatsApp/Telegram keep their media on Android 11+.

## Real sources (scan stages)
| Stage | How | API levels |
|---|---|---|
| سلة المحذوفات | `MediaStore.Images` with `QUERY_ARG_MATCH_TRASHED = MATCH_ONLY` (`IS_TRASHED`, `DATE_EXPIRES`), plus files named `.trashed-<expiry>-<name>` / `.pending-*`, plus `.trash`, `.Trash`, `.recycle`, `.RecycleBin`, Samsung `.Trash` folders | 30+ (folders: all) |
| LOST.DIR | every `LOST.DIR` on every volume; files are sniffed by magic bytes, not by name | all |
| المصغّرات | `DCIM/.thumbnails`, `.thumbdata3/4--*` blobs (many JPEGs back to back), `Android/media/*/.thumbnails` | all |
| واتساب | `Android/media/com.whatsapp(.w4b)/WhatsApp*/Media/WhatsApp Images` (+ `Sent`, `Private`, `.Statuses`, `.Shared`), legacy `WhatsApp/Media` | 11+ for `Android/media`; legacy path on ≤10 |
| تيليجرام | `Android/media/org.telegram.messenger/Telegram/Telegram Images` + cache dirs under `Android/media` | same |
| مخفية | dot-folders / `.nomedia` folders under `Pictures`, `DCIM`, `Download`, root; dot-files | all |
| من الكاش | carve JPEG/PNG/WebP/HEIC out of large files that are not images (`*.bin`, `*.dat`, `*.tmp`, extension-less, cache folders) | all |

Volumes come from `StorageManager.storageVolumes` (internal + SD card + USB), not a hard-coded `/storage/emulated/0`.

## Scan engine (phase 2 — port of the audited carving core, rebuilt)
- `ScanEngine` exposes `Flow<ScanEvent>` (`StageStarted`, `Progress(filesSeen, pathNow)`, `Found(item)`, `StageDone`, `Finished`) on `Dispatchers.IO`; cancellable; no file-count or hit caps.
- Walker uses `Files.walkFileTree` with a visitor (no list of all files in memory); a first cheap pass counts directories so the % is real, not a spinner.
- Carver streams a 1 MiB window with 16 B overlap, so a 200 MB cache blob never sits in RAM. Parsers validate structure (JPEG markers/SOS/EOI, PNG chunk CRC lengths, WebP RIFF, ISO-BMFF) and return `INTACT` or `PARTIAL` with the exact byte range — no trailing junk.
- A candidate is shown only if it decodes (`BitmapFactory.Options.inJustDecodeBounds` succeeds) and is ≥ 8 KB, or is a thumbnail ≥ 2 KB.
- De-dup: SHA-256 of the carved bytes, plus a "you still have this" check against visible MediaStore images (same size+hash) so the user is not shown photos they never lost.
- Results are written to a small Room table (`id, source, quality, path, offset, length, size, mtime, w, h`) so the list survives process death; carved ranges are re-read lazily, never copied during the scan.
- Foreground service (`dataSync`) with a notification so a long scan survives the app being backgrounded; wake lock only while scanning.

## Screens (all built, with sample data, in `previews/`)
1. **Home / permission** — brand, "offline" chip, hero, two permission rows (state: required/granted), primary button (`السماح بالوصول` → `ابدأ الفحص` once both are granted), the seven sources, honesty note.
2. **Scanning** — determinate ring with %, live counters (found / files seen / time), per-stage checklist with counts, current path (always LTR), strip of the latest finds, stop / show results so far.
3. **Results** — selection top bar, intact/partial summary, source chips with counts, size / date / status / sort chips, "ظاهر X من Y" + clear filters, 3-column grid (source badge, "ناقصة" tag, check on selected), restore bar with count + total size.
4. **Full preview** — pager (swipe), pinch-zoom, status (intact/partial), source, size, dimensions, date, original path, select toggle, restore this one.
5. **Restore done** — saved / duplicates skipped / renamed / failed, folder path, thumbnails, honest note, open folder / back / new scan.

## Restore semantics
- Destination `…/RaddRecovered/` (created on demand); each file is written with `createNewFile()` (atomic "create if absent"), name collisions get `-2`, `-3`, never overwrite.
- Names: strip `.trashed-<ts>-`/`.pending-<ts>-`; carved items get `radd-<source>-<yyyyMMdd>-<n>.jpg`.
- Copy in 64 KiB chunks on `Dispatchers.IO` with a progress notification; size is verified after the copy; `lastModified` is restored when known.
- `MediaScannerConnection.scanFile` for each result so it appears in the Gallery immediately.
- Optional (later): "return to Gallery trash" via `MediaStore.createTrashRequest(untrash)` for MediaStore-trashed items — system consent dialog, not a silent change.
- Originals are never touched or deleted.

## Permissions / manifest
`MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES` (13+), `READ_/WRITE_EXTERNAL_STORAGE` (≤ 29), later `FOREGROUND_SERVICE_DATA_SYNC` + `POST_NOTIFICATIONS`. **No INTERNET**, `allowBackup=false`.
MANAGE_EXTERNAL_STORAGE is tightly restricted on Google Play and photo recovery is unlikely to qualify, so distribution is GitHub Releases / sideload (same as مستند), with a stable signing key kept outside git.

## Architecture
Single `:app` module: `model/` (pure Kotlin: sources, filters, formatting — unit-tested), `ui/` (stateless Compose screens + `ThumbSlot`), later `engine/` (walker, carvers, MediaStore, restore), `data/` (Room), `vm/` (one ViewModel per flow, `appViewModel{}` pattern from مستند to avoid the launch crash we hit there).
Tests planned: carver fixtures (JPEG/PNG/WebP, truncated, corrupt, adjacent images), filter logic (done: `FiltersTest`), restore no-overwrite, Robolectric launch test, Paparazzi previews (done). No emulator runs on the box, so camera-free but device-dependent parts (SAF-free file access, MediaStore trash, Android/media on a real ROM) are the main "unverified until a phone tries it" risks.

## Assets
Own-made only: launcher mark (vector), procedural scenes in `ui/Art.kt`, Material icons (Apache-2.0), Cairo font (OFL 1.1, already used in مستند). No stock photos.
