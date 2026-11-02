package app.radd.android

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.radd.engine.*
import java.io.File

/** Volumes from StorageManager (internal + SD card + USB). Falls back to the primary external directory. */
class AndroidRoots(private val context: Context) : StorageRoots {
    override fun roots(): List<File> {
        val found = LinkedHashSet<File>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= 30) {
                sm.storageVolumes.forEach { v -> v.directory?.let { found += it } }
            } else {
                // Below Android 11: the app-specific dirs sit inside every volume; cut the path back to the volume root.
                ContextCompat.getExternalFilesDirs(context, null).filterNotNull().forEach { d ->
                    val i = d.path.indexOf("/Android/data")
                    if (i > 0) found += File(d.path.substring(0, i))
                }
            }
        } catch (_: Exception) { /* use the fallback below */ }
        @Suppress("DEPRECATION")
        if (found.isEmpty()) found += Environment.getExternalStorageDirectory()
        return found.toList()
    }
}

/** MediaStore access. A failure is returned as an error message; the scan carries on without that information. */
class AndroidMedia(private val context: Context) : MediaGateway {
    override fun trashed(): MediaQuery =
        if (Build.VERSION.SDK_INT >= 30) query(trashed = true) else MediaQuery(emptyList())

    override fun visible(): MediaQuery = query(trashed = false)

    private fun query(trashed: Boolean): MediaQuery {
        val out = ArrayList<MediaRow>()
        return try {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            @Suppress("DEPRECATION")
            val projection = arrayOf(
                MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT,
            )
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                }
                context.contentResolver.query(uri, projection, args, null)
            } else {
                context.contentResolver.query(uri, projection, null, null, null)
            }
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    out += MediaRow(path, c.getLong(1), c.getLong(2) * 1000, c.getInt(3), c.getInt(4))
                }
            }
            MediaQuery(out)
        } catch (e: Exception) {
            MediaQuery(out, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}

/** Makes restored files appear in the Gallery straight away. */
class AndroidNotifier(private val context: Context) : MediaNotifier {
    override fun scanned(paths: List<File>) {
        try { MediaScannerConnection.scanFile(context, paths.map { it.path }.toTypedArray(), null, null) } catch (_: Exception) { }
    }
}

/** Optional proof that a candidate decodes as an image, using the platform decoder on the first bytes of the range. */
class AndroidDecodeCheck : DecodeCheck {
    override fun ok(file: File, offset: Long, length: Long, format: String): Boolean {
        if (format == "heic" || format == "avif") return true // the platform decoder may lack them; the structure was already validated
        return try {
            FileSource(file).use { s ->
                val n = minOf(length, 256 * 1024L).toInt()
                val buf = ByteArray(n)
                val read = s.read(offset, buf, 0, n)
                if (read <= 0) return false
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(buf, 0, read, o)
                o.outWidth > 0 && o.outHeight > 0
            }
        } catch (_: Exception) { true } // a decoder problem must not hide a structurally valid photo
    }
}


/**
 * Perceptual fingerprints with the platform decoder: the image is decoded at a reduced size (cheap even for 50 MP photos),
 * rotated by its EXIF orientation, reduced to luminance and handed to the pure [Fingerprints] code.
 */
class AndroidFingerprinter : Fingerprinter {
    override fun of(file: File, offset: Long, length: Long): Fingerprint? = try { decode(file, offset, length) } catch (_: Throwable) { null }

    private class Range(file: File, private val start: Long, private val length: Long) : java.io.InputStream() {
        private val raf = java.io.RandomAccessFile(file, "r").also { it.seek(start) }
        private var left = length
        override fun read(): Int {
            if (left <= 0) return -1
            val b = raf.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = raf.close()
    }

    private fun decode(file: File, offset: Long, length: Long): Fingerprint? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Range(file, offset, length).use { android.graphics.BitmapFactory.decodeStream(it.buffered(), null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (minOf(w, h) / (sample * 2) >= 96) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = Range(file, offset, length).use { android.graphics.BitmapFactory.decodeStream(it.buffered(), null, opts) } ?: return null
        val orientation = try {
            Range(file, offset, length).use { androidx.exifinterface.media.ExifInterface(it.buffered()).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Throwable) { androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL }
        try {
            val bw = bmp.width
            val bh = bmp.height
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            val lum = ByteArray(bw * bh) { i ->
                val c = px[i]
                ((((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000).toByte()
            }
            return orient(lum, bw, bh, w, h, orientation)
        } finally {
            bmp.recycle()
        }
    }

    /** Applies EXIF orientation [o] (1..8) to a luminance raster and swaps the real dimensions when it is a quarter turn. */
    internal fun orient(src: ByteArray, w: Int, h: Int, fullW: Int, fullH: Int, o: Int): Fingerprint? {
        val quarter = o in 5..8
        val ow = if (quarter) h else w
        val oh = if (quarter) w else h
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (o) {
                2 -> (w - 1 - x) to y
                3 -> (w - 1 - x) to (h - 1 - y)
                4 -> x to (h - 1 - y)
                5 -> y to x
                6 -> (h - 1 - y) to x
                7 -> (h - 1 - y) to (w - 1 - x)
                8 -> y to (w - 1 - x)
                else -> x to y
            }
            out[ny * ow + nx] = src[y * w + x]
        }
        return Fingerprints.fromGray(out, ow, oh, if (quarter) fullH else fullW, if (quarter) fullW else fullH)
    }
}
