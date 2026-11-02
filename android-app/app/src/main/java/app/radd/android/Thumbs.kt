package app.radd.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import app.radd.engine.FileSource
import app.radd.model.RecoverableItem
import java.io.ByteArrayInputStream
import java.io.File

/** Decodes a downsampled bitmap of a recovered image straight from its byte range. Returns null when it cannot be decoded. */
object Thumbs {
    private const val MAX_RANGE = 64L * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(maxOf(8 * 1024, (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt())) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private fun key(item: RecoverableItem, maxPx: Int) = "${item.originPath}@${item.offset}+${item.length}:$maxPx"

    fun cached(item: RecoverableItem, maxPx: Int): Bitmap? = cache.get(key(item, maxPx))

    fun load(item: RecoverableItem, maxPx: Int): Bitmap? {
        cached(item, maxPx)?.let { return it }
        return try {
            val bmp = decode(item, maxPx) ?: return null
            cache.put(key(item, maxPx), bmp)
            bmp
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            cache.evictAll()
            null
        }
    }

    private fun sampleFor(w: Int, h: Int, maxPx: Int): Int {
        var s = 1
        while (w / (s * 2) >= maxPx && h / (s * 2) >= maxPx) s *= 2
        return s
    }

    private fun decode(item: RecoverableItem, maxPx: Int): Bitmap? {
        val file = File(item.originPath)
        if (!file.isFile || item.length <= 0 || item.length > MAX_RANGE) return null
        val whole = item.offset == 0L && item.length == file.length()
        val bytes: ByteArray? = if (whole) null else FileSource(file).use { s ->
            val b = ByteArray(item.length.toInt())
            if (s.read(item.offset, b, 0, b.size) != b.size) return null
            b
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) else BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxPx) }
        val bmp = (if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) else BitmapFactory.decodeFile(file.path, opts)) ?: return null
        return rotate(bmp, orientation(item, file, bytes))
    }

    private fun orientation(item: RecoverableItem, file: File, bytes: ByteArray?): Int = try {
        if (item.format != "jpg") ExifInterface.ORIENTATION_NORMAL
        else if (bytes != null) ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        else ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return b
        }
        return try { Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true) } catch (_: Exception) { b }
    }
}
