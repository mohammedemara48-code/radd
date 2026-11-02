package app.radd.engine

import java.io.File

/** A row from MediaStore (path, size, last modified in ms). */
data class MediaRow(val path: String, val size: Long, val modifiedMs: Long)

/** What the system's media index knows. Failing to read it must never stop the scan, so errors are reported, not thrown. */
interface MediaGateway {
    /** Images in the system trash (Android 11+). */
    fun trashed(): MediaQuery
    /** Images the gallery currently shows. Used to avoid presenting photos the user still has. */
    fun visible(): MediaQuery
}

data class MediaQuery(val rows: List<MediaRow>, val error: String? = null)

/** Storage volumes to scan (internal storage, SD card, USB). */
fun interface StorageRoots { fun roots(): List<File> }

/** Optional extra proof that a candidate really decodes (BitmapFactory on a phone). */
fun interface DecodeCheck { fun ok(file: File, offset: Long, length: Long, format: String): Boolean }

/** Tells the gallery about new files. */
fun interface MediaNotifier { fun scanned(paths: List<File>) }

object NoMedia : MediaGateway {
    override fun trashed() = MediaQuery(emptyList())
    override fun visible() = MediaQuery(emptyList())
}
