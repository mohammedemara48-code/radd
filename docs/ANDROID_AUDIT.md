# Audit of the first Android build (`android/Radd`, `public/radd.apk`)

Audited by reading the source and by compiling `Scanner.java` on a JVM and running it on a synthetic storage tree
(JPEG/PNG blobs, `.trashed-*`, `LOST.DIR`, `.thumbdata`, a truncated hidden file). It has **not** been run on a phone.

## What really works
- Pure-Java, no INTERNET permission, MANAGE_EXTERNAL_STORAGE settings flow (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`).
- Format parsers for JPEG (marker walk, SOS/EOI), PNG (chunk walk to IEND), WebP (RIFF size), HEIC/AVIF (ftyp). On the synthetic tree it found:
  the `.trashed-` file, the `LOST.DIR` file, three JPEGs carved out of a `.thumbdata` blob, a JPEG and a PNG carved out of a `.bin`,
  and a truncated JPEG (flagged `31%`); it skipped the one intact visible photo.
- Restore never overwrites (`unique()` appends `-2`, `-3`, …) and writes to `RaddRecovered`.

## What does not (why it is a prototype, not a product)
1. **Scan is capped at 8,000 files and 400 hits**, depth-first. A phone has tens of thousands of files, so most of the storage is never visited
   (verified: 9,007 files on the test tree → 8,000 collected).
2. No MediaStore query, so the real Android 11+ trash (`IS_TRASHED`) is only found through the `.trashed-` file name, and `READ_MEDIA_IMAGES` is not requested.
3. Only the primary volume; no SD card / secondary volumes. `Android/media` is only visited when its parent is literally `0`.
4. Whole file (≤ 12 MB) is read into memory; larger `.thumbdata` / cache blobs are only partly carved, ≤ 4 hits per file, files > 4 MB skipped unless "suspicious".
5. Truncated JPEGs are cut at end-of-buffer, so the trailing garbage of the blob is included in the "image".
6. No de-duplication (same photo found as original, thumbnail and cache copy is listed several times); each hit is copied into the app cache immediately (doubles storage use during the scan).
7. UI is hand-built `View` code: up to 400 thumbnails decoded on the UI thread, no grid, filters, preview, multi-select, or sizes/dates; save runs on the UI thread;
   restored files are not announced to MediaStore, so they do not show in the Gallery; results are lost if the process dies; `stop` flag is not `volatile`.
8. No tests. `build-apk.sh` hard-codes `/tmp` paths and signs with a throw-away key (password in the script), so a later build cannot update an installed copy.
9. `public/radd.apk` is a real but minimal build: 23 KB of dex + a manifest. Nothing in it is hidden or faked; it is simply small because it is ~700 lines of Java.

Verdict: the carving core is sound and worth porting; everything around it is rebuilt in `android-app/`.
