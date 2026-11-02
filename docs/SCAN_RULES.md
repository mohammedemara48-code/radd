# What counts as a "deleted photo" (v1.0.1)

Version 1.0.0 listed everything that looked like an image: the gallery's own photos, app icons and cache files (68,452 results
on a real phone). From 1.0.1 a picture is only offered when it passes **all** of these gates. The code is in
`engine/Rules.kt`, `engine/Indexer.kt`, `engine/Fingerprints.kt`, `engine/LiveIndex.kt` and `engine/ScanEngine.kt`.

## 1. Where it is

| Source | Rule |
|---|---|
| Trash | MediaStore trashed rows, `.trashed-*` / `.pending-*` files and trash folders. Always offered (after the size and shape gates). |
| LOST.DIR | Files in `LOST.DIR`, carved for images. Must not match a photo that is still in the gallery. |
| Thumbnails | `.thumbnails` and `.thumbdata*`. Offered only when **no** gallery photo shows the same scene (perceptual match) and the picture is not a smaller copy of something already listed. Centre-cropped squares are ignored (they cannot be compared). |
| WhatsApp / Telegram | Only `Android/media/com.whatsapp*`, `Android/media/org.telegram.messenger*` and the old `WhatsApp/`, `Telegram/` folders. `Private`, `.Statuses`, profile photos, stickers and any chat folder with `.nomedia` are skipped. Offered only when the picture is not in the gallery (same bytes or same scene). WebP is treated as a sticker. |
| Hidden | Dot-folders and `.nomedia` folders. Must carry camera EXIF (Make, Model or DateTimeOriginal) or be large (short side 1000 px and 1.5 MP). |
| Carved | Images found inside temporary files (`.tmp`, `.bin`, `.dat`, ...). Same proof as Hidden, and must not match a gallery photo. |

## 2. What it is

- At least 10 KB (thumbnails: 4 KB) and at least 200 px on the short side (thumbnails: 200 px on the long side).
- Not a strip: long side over short side at most 4.
- Not flat (a solid colour or near-solid picture is a UI asset).
- Not an app asset: folders named like packages (`com.vendor.app`), `cache`, `emoji`, `sticker`, `icon`, `avatar`, `assets`, `glide`, `unity`, ... and
  files named `icon`, `ic_launcher`, `sticker`, `logo`, ... are never scanned. All of `Android/` except the two chat apps' media folders is skipped.

## 3. "Still in the gallery"

A candidate is dropped when MediaStore lists the same path, a file with the same size and SHA-256, or a photo with the same scene.
The scene check uses a 64-bit difference hash and a 64-bit average hash of a luminance raster; two copies must be within 8 and 10 bits and
have shapes within 8% (thumbnails: 35%). On the test photos, resized and recompressed copies differ by at most 3 bits and different photos by at least 15.
Gallery photos are decoded lazily, in parallel, and only when their shape could match; the scan screen shows "بقارن مع صور المعرض".

## 4. Upgrades

Saved results carry a format number (`ResultsFormat.CURRENT`). When it changes, the results database is deleted before it is opened, so results
made with older rules never show up.

## Limits

- The perceptual match is only as good as the platform decoder and the system thumbnail. A thumbnail the system has cropped differently from
  the original can slip through, and a deleted photo that looks almost the same as a kept one (a burst) can be hidden.
- None of this recovers data that was overwritten. Without root the app only finds traces that still exist.
