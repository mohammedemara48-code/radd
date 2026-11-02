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
            val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED)
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
                    out += MediaRow(path, c.getLong(1), c.getLong(2) * 1000)
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

