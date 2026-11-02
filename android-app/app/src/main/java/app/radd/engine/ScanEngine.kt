package app.radd.engine

import app.radd.model.Quality
import app.radd.model.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** One recoverable image, before it gets a database id. [offset]/[length] address the bytes inside [path]. */
data class Found(
    val source: Source,
    val quality: Quality,
    val path: String,
    val offset: Long,
    val length: Long,
    val modifiedMs: Long,
    val width: Int,
    val height: Int,
    val format: String,
    val sha256: String,
    val name: String,
)

sealed interface ScanEvent {
    /** The one-pass walk over storage is still running (no percentage yet). */
    data class Indexing(val visited: Long, val path: String) : ScanEvent
    data class StageStarted(val source: Source, val files: Int) : ScanEvent
    data class Progress(val doneBytes: Long, val totalBytes: Long, val filesDone: Int, val filesTotal: Int, val path: String) : ScanEvent
    data class FoundOne(val item: Found) : ScanEvent
    data class StageDone(val source: Source, val found: Int) : ScanEvent
    data class Finished(val found: Int, val visited: Long, val unreadable: Int, val stillVisible: Int, val warnings: List<String>) : ScanEvent
}

/** Raised when nothing can be scanned at all (for example the storage permission was withdrawn). */
class ScanFailure(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NO_STORAGE, NO_PERMISSION }
}

class ScanEngine(
    private val roots: StorageRoots,
    private val media: MediaGateway = NoMedia,
    private val decode: DecodeCheck = DecodeCheck { _, _, _, _ -> true },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun run(): Flow<ScanEvent> = channelFlow {
        val volumes = roots.roots().filter { it.isDirectory }
        if (volumes.isEmpty()) throw ScanFailure(ScanFailure.Reason.NO_STORAGE, "no storage volume is available")
        if (volumes.none { runCatching { it.listFiles() != null }.getOrDefault(false) }) {
            throw ScanFailure(ScanFailure.Reason.NO_PERMISSION, "storage cannot be listed")
        }
        val warnings = ArrayList<String>()
        val stillAlive = { isActive }

        // 1) one pass over storage, sorting files into stages
        val indexer = Indexer(volumes) { !stillAlive() }
        var lastIndexing = 0L
        val index = indexer.index { visited, path ->
            val now = System.nanoTime()
            if (now - lastIndexing > 120_000_000L) { lastIndexing = now; trySend(ScanEvent.Indexing(visited, path)) }
        }
        currentCoroutineContext().ensureActive()
        send(ScanEvent.Indexing(indexer.visited, ""))

        // 2) the system trash and the "gallery still shows this" list
        val trashed = media.trashed()
        trashed.error?.let { warnings += "trash: $it" }
        val visible = media.visible()
        visible.error?.let { warnings += "gallery: $it" }
        val visiblePaths = visible.rows.mapTo(HashSet()) { it.path }
        val visibleBySize = visible.rows.groupBy { it.size }
        val knownTrash = index.getValue(Source.TRASH).mapTo(HashSet()) { it.file.path }
        for (row in trashed.rows) {
            val f = File(row.path)
            if (f.path !in knownTrash && f.isFile && f.length() > 0) index.getValue(Source.TRASH) += Candidate(f, Source.TRASH, f.length(), f.lastModified(), false)
        }

        val totalFiles = STAGES.sumOf { index.getValue(it).size }
        val totalBytes = STAGES.sumOf { s -> index.getValue(s).sumOf { it.size } }.coerceAtLeast(1)
        var doneBytes = 0L
        var filesDone = 0
        var unreadable = 0
        var stillVisible = 0
        var total = 0
        val seenHashes = HashSet<String>()
        val visibleHashes = HashMap<String, String>() // path -> sha, computed lazily
        val counters = HashMap<Source, Int>()
        var lastProgress = 0L

        for (stage in STAGES) {
            val list = index.getValue(stage)
            send(ScanEvent.StageStarted(stage, list.size))
            var stageFound = 0
            for (c in list) {
                currentCoroutineContext().ensureActive()
                if (c.file.path in visiblePaths) { doneBytes += c.size; filesDone++; stillVisible++; continue }
                try {
                    FileSource(c.file).use { src ->
                        val regions = Carvers.carve(src, c.deep, cancelled = { !stillAlive() }, onProgress = { b ->
                            val now = System.nanoTime()
                            if (now - lastProgress > 100_000_000L) {
                                lastProgress = now
                                trySend(ScanEvent.Progress(doneBytes + b, totalBytes, filesDone, totalFiles, c.file.path))
                            }
                        })
                        for (r in regions) {
                            if (!acceptable(r, c.source)) continue
                            if (!decode.ok(c.file, r.start, r.length, r.format)) continue
                            val sha = sha256(src, r.start, r.length)
                            if (!seenHashes.add(sha)) continue
                            if (isStillVisible(r.length, sha, visibleBySize, visibleHashes)) { stillVisible++; continue }
                            val n = (counters[c.source] ?: 0) + 1
                            counters[c.source] = n
                            val found = Found(
                                source = c.source,
                                quality = if (r.complete) Quality.INTACT else Quality.PARTIAL,
                                path = c.file.path, offset = r.start, length = r.length, modifiedMs = c.modifiedMs,
                                width = r.width, height = r.height, format = r.format, sha256 = sha,
                                name = nameFor(c, r, n),
                            )
                            stageFound++; total++
                            send(ScanEvent.FoundOne(found))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    unreadable++
                } catch (_: SecurityException) {
                    unreadable++
                }
                doneBytes += c.size
                filesDone++
                val now = System.nanoTime()
                if (now - lastProgress > 100_000_000L) {
                    lastProgress = now
                    trySend(ScanEvent.Progress(doneBytes, totalBytes, filesDone, totalFiles, c.file.path))
                }
            }
            send(ScanEvent.Progress(doneBytes, totalBytes, filesDone, totalFiles, ""))
            send(ScanEvent.StageDone(stage, stageFound))
        }
        send(ScanEvent.Finished(total, indexer.visited, unreadable + indexer.unreadableDirs, stillVisible, warnings))
    }.buffer(Channel.UNLIMITED)

    private fun acceptable(r: Region, source: Source): Boolean {
        val min = if (source == Source.THUMBNAILS) Classifier.MIN_THUMB else Classifier.MIN_COMPLETE
        return if (r.complete) r.length >= min else r.length >= Classifier.MIN_PARTIAL
    }

    private fun isStillVisible(length: Long, sha: String, bySize: Map<Long, List<MediaRow>>, cache: MutableMap<String, String>): Boolean {
        val same = bySize[length] ?: return false
        for (row in same) {
            val f = File(row.path)
            val h = cache.getOrPut(row.path) { try { FileSource(f).use { sha256(it, 0, it.length) } } catch (_: Exception) { "" } }
            if (h == sha) return true
        }
        return false
    }

    /** Whole-file hits keep their own name (minus the trash prefix); carved hits get a neutral, unique name. */
    private fun nameFor(c: Candidate, r: Region, n: Int): String {
        val wholeFile = r.start == 0L && r.end == c.size
        val ext = r.format
        if (wholeFile && c.source != Source.THUMBNAILS && c.source != Source.CARVED && c.source != Source.LOST_DIR) {
            val cleaned = cleanName(c.file.name)
            val stem = cleaned.substringBeforeLast('.', cleaned).ifBlank { "photo" }
            val oldExt = Classifier.extension(cleaned)
            val okExt = (ext == "jpg" && (oldExt == "jpg" || oldExt == "jpeg" || oldExt == "jpe")) || oldExt == ext || (ext == "heic" && oldExt == "heif")
            return if (okExt) cleaned else "$stem.$ext"
        }
        val day = Instant.ofEpochMilli(if (c.modifiedMs > 0) c.modifiedMs else 0L).atZone(zone).toLocalDate()
        val date = String.format(Locale.US, "%04d%02d%02d", day.year, day.monthValue, day.dayOfMonth)
        return "radd-${c.source.name.lowercase(Locale.US).replace('_', '-')}-$date-$n.$ext"
    }

    companion object {
        /** `.trashed-1763900000-IMG_1.jpg` -> `IMG_1.jpg`; `.pending-1763900000-IMG_1.jpg` likewise; a leading dot is dropped. */
        fun cleanName(name: String): String {
            var n = name
            for (prefix in listOf(".trashed-", ".pending-")) {
                if (n.startsWith(prefix)) {
                    val cut = n.indexOf('-', prefix.length)
                    if (cut > 0 && cut + 1 < n.length) n = n.substring(cut + 1)
                    break
                }
            }
            return n.trimStart('.').ifBlank { "photo" }
        }

        fun sha256(s: ByteSource, start: Long, length: Long): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var at = start
            var left = length
            while (left > 0) {
                val n = s.read(at, buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                at += n
                left -= n
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
