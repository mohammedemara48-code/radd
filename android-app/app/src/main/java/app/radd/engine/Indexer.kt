package app.radd.engine

import app.radd.model.Source
import java.io.File
import java.nio.file.Files

/** A file worth opening, assigned to exactly one scan stage. */
data class Candidate(val file: File, val source: Source, val size: Long, val modifiedMs: Long, val deep: Boolean)

/** Order of the scan stages, as shown to the user. */
val STAGES: List<Source> = listOf(Source.TRASH, Source.LOST_DIR, Source.THUMBNAILS, Source.WHATSAPP, Source.TELEGRAM, Source.HIDDEN, Source.CARVED)

/** Decides which stage (if any) a file belongs to, from its path alone. Pure, so it is easy to test. */
object Classifier {
    val IMAGE_EXT = setOf("jpg", "jpeg", "jpe", "png", "webp", "heic", "heif", "avif")
    val SKIP_EXT = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "mp3", "aac", "m4a", "ogg", "opus", "wav", "flac", "amr",
        "apk", "xapk", "zip", "rar", "7z", "obb", "iso", "exe", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "json", "xml", "log",
        "gif", "html", "css", "js", "so", "dex", "jar", "ttf", "otf",
    )
    val CARVE_EXT = setOf("bin", "dat", "tmp", "temp", "cache", "chk", "db", "sqlite", "blob", "thumb", "bak", "idx", "data", "img", "")
    private val TRASH_DIRS = setOf(".trash", ".trashed", ".recycle", ".recyclebin", "recyclebin", "\$recycle.bin", ".deleted", ".recycled", "trash", ".rubbish")
    private val THUMB_DIRS = setOf(".thumbnails", ".thumbs", ".thumb")
    private val MESSENGER_SKIP_DIRS = setOf(
        "whatsapp video", "whatsapp audio", "whatsapp voice notes", "whatsapp documents", "whatsapp animated gifs", "whatsapp stickers",
        "backups", "databases", "telegram video", "telegram audio", "telegram documents", "telegram files",
        // pictures the user still has in a chat, other people's statuses, profile pictures and stickers are not "deleted photos"
        "private", ".statuses", "whatsapp profile photos", "whatsapp stickers", "whatsapp business stickers", "telegram stickers",
        "profile pictures", "wallpaper",
    )
    const val MIN_COMPLETE = PhotoRules.MIN_BYTES
    const val MIN_THUMB = PhotoRules.MIN_THUMB_BYTES
    const val MIN_PARTIAL = PhotoRules.MIN_BYTES

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** What a directory (given as lower-cased path segments below its volume root) means for the files inside it. */
    data class DirKind(val trash: Boolean, val lost: Boolean, val thumbs: Boolean, val messenger: Source?, val hidden: Boolean, val skip: Boolean)

    fun dirKind(segments: List<String>, parent: DirKind?, hasNoMedia: Boolean): DirKind {
        val name = segments.lastOrNull().orEmpty()
        var trash = parent?.trash ?: false
        var lost = parent?.lost ?: false
        var thumbs = parent?.thumbs ?: false
        var messenger = parent?.messenger
        var hidden = parent?.hidden ?: false
        var skip = parent?.skip ?: false
        val marker = name in TRASH_DIRS || name == "lost.dir" || name in THUMB_DIRS
        if (name in TRASH_DIRS) trash = true
        if (name == "lost.dir") lost = true
        if (name in THUMB_DIRS) thumbs = true
        // Android/<anything but media> is private app storage; Android/media/<app> only counts for the two chat apps
        if (segments.size == 2 && segments[0] == "android" && name != "media") skip = true
        if (messenger == null) {
            if (segments.size == 3 && segments[0] == "android" && segments[1] == "media") {
                if (name == "com.whatsapp" || name == "com.whatsapp.w4b") messenger = Source.WHATSAPP
                if (name == "org.telegram.messenger" || name == "org.telegram.messenger.web") messenger = Source.TELEGRAM
            }
            if (segments.size == 1 && name == "whatsapp") messenger = Source.WHATSAPP
            if (segments.size == 1 && name == "telegram") messenger = Source.TELEGRAM
        }
        if (segments.size == 3 && segments[0] == "android" && segments[1] == "media" && messenger == null) skip = true
        if (messenger != null && (name in MESSENGER_SKIP_DIRS || (hasNoMedia && segments.size > 1 && !(segments[0] == "android" && segments.size <= 3)))) skip = true
        // other apps' icon, sticker, cache and package-named folders are app assets, not photos
        if (messenger == null && !marker && AppAssets.isAssetDir(name)) skip = true
        if (name.startsWith(".") && name.length > 1 && !trash && !lost && !thumbs) hidden = hidden || messenger == null
        if (hasNoMedia && messenger == null) hidden = true
        return DirKind(trash, lost, thumbs, messenger, hidden, skip)
    }

    /** Assigns a file to a stage, or returns null when it is not a candidate. */
    fun classify(name: String, dir: DirKind, size: Long): Source? {
        val lower = name.lowercase()
        val ext = extension(lower)
        if (size <= 0 || dir.skip) return null
        if (AppAssets.isAssetFile(lower)) return null
        if (lower.startsWith(".trashed-") || lower.startsWith(".pending-") || dir.trash) return if (size >= MIN_COMPLETE) Source.TRASH else null
        if (dir.lost) return if (size >= MIN_COMPLETE) Source.LOST_DIR else null
        if (dir.thumbs || lower.startsWith(".thumbdata") || lower.startsWith("thumbdata")) return if (size >= MIN_THUMB) Source.THUMBNAILS else null
        if (ext in SKIP_EXT || lower == ".nomedia") return null
        val m = dir.messenger
        if (m != null) return if ((ext in IMAGE_EXT || ext == "") && size >= MIN_COMPLETE) m else null
        if (size < MIN_COMPLETE) return null
        if (dir.hidden || lower.startsWith(".")) {
            if (ext in IMAGE_EXT || ext == "") return Source.HIDDEN
            return if (ext in CARVE_EXT) Source.CARVED else null
        }
        if (ext in IMAGE_EXT) return null // an ordinary visible photo is not "lost"
        return if (ext in CARVE_EXT && size >= MIN_PARTIAL) Source.CARVED else null
    }

    fun isDeep(source: Source) = source == Source.THUMBNAILS || source == Source.CARVED || source == Source.LOST_DIR
}

/** Walks every volume once and sorts the files into stages. Memory use is proportional to the number of *candidates*, not files. */
class Indexer(private val roots: List<File>, private val cancelled: () -> Boolean = { false }) {
    var visited = 0L
        private set
    var unreadableDirs = 0
        private set

    fun index(onVisit: (visited: Long, path: String) -> Unit = { _, _ -> }): Map<Source, MutableList<Candidate>> {
        val out = STAGES.associateWith { ArrayList<Candidate>() }
        for (root in roots) walk(root, out, onVisit)
        return out
    }

    private fun isSymlink(f: File) = try { Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { true }

    private fun walk(root: File, out: Map<Source, MutableList<Candidate>>, onVisit: (Long, String) -> Unit) {
        class Frame(val dir: File, val segments: List<String>, val kind: Classifier.DirKind?)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, emptyList(), null))
        var lastReport = 0L
        while (stack.isNotEmpty()) {
            if (cancelled()) return
            val f = stack.removeLast()
            val kids = try { f.dir.listFiles() } catch (_: SecurityException) { null }
            if (kids == null) { unreadableDirs++; continue }
            val hasNoMedia = kids.any { it.name == ".nomedia" }
            val here = if (f.segments.isEmpty()) Classifier.DirKind(false, false, false, null, false, false)
            else Classifier.dirKind(f.segments, f.kind, hasNoMedia)
            for (k in kids) {
                visited++
                if (visited - lastReport >= 500) { lastReport = visited; onVisit(visited, f.dir.path) }
                val name = k.name
                if (k.isDirectory) {
                    val lower = name.lowercase()
                    val seg = f.segments + lower
                    if (f.segments.isEmpty() && lower == "raddrecovered") continue
                    if (f.segments.size == 1 && f.segments[0] == "android" && (lower == "data" || lower == "obb")) continue
                    if (isSymlink(k)) continue
                    stack.addLast(Frame(k, seg, here))
                } else {
                    val size = k.length()
                    val source = Classifier.classify(name, here, size) ?: continue
                    out.getValue(source) += Candidate(k, source, size, k.lastModified(), Classifier.isDeep(source))
                }
            }
        }
        onVisit(visited, root.path)
    }
}
