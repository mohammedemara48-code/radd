package app.radd.engine

import app.radd.model.RecoverableItem
import app.radd.model.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class RestoreFailure(message: String) : IOException(message)

/**
 * Copies recovered images into a destination folder. Guarantees: an existing file is never overwritten or modified
 * (names are claimed with an atomic create-if-absent), the copy is checked for size and hash, the original date is kept,
 * and the source is only read.
 */
class RestoreEngine(private val notifier: MediaNotifier = MediaNotifier { }) {

    suspend fun restore(items: List<RecoverableItem>, dest: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RestoreResult {
        if (!dest.isDirectory && !dest.mkdirs() && !dest.isDirectory) throw RestoreFailure("cannot create ${dest.path}")
        if (!dest.canWrite()) throw RestoreFailure("cannot write to ${dest.path}")
        val existing = ExistingHashes(dest)
        val saved = ArrayList<RecoverableItem>()
        val written = ArrayList<File>()
        var skipped = 0
        var failed = 0
        var renamed = 0
        onProgress(0, items.size)
        for ((index, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (item.sha256.isNotEmpty() && existing.has(item.sizeBytes, item.sha256)) {
                skipped++
            } else {
                var target: File? = null
                try {
                    val claim = claimName(dest, item.name)
                    target = claim.file
                    copy(item, claim.file)
                    claim.file.setLastModified(item.modifiedMs.takeIf { it > 0 } ?: System.currentTimeMillis())
                    if (claim.renamed) renamed++
                    saved += item
                    written += claim.file
                    if (item.sha256.isNotEmpty()) existing.add(item.sizeBytes, item.sha256)
                } catch (e: CancellationException) {
                    target?.delete()
                    throw e
                } catch (_: IOException) {
                    target?.delete()
                    failed++
                } catch (_: SecurityException) {
                    target?.delete()
                    failed++
                }
            }
            onProgress(index + 1, items.size)
        }
        if (written.isNotEmpty()) notifier.scanned(written)
        return RestoreResult(saved, skipped, failed, dest.path, renamed)
    }

    private class Claim(val file: File, val renamed: Boolean)

    /** Picks a free name by creating the file atomically; never reuses a name that exists. */
    private fun claimName(dir: File, wanted: String): Claim {
        val safe = wanted.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').ifBlank { "photo.jpg" }
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var n = 1
        while (n < 10_000) {
            val f = File(dir, if (n == 1) safe else "$stem-$n$ext")
            if (f.createNewFile()) return Claim(f, n > 1)
            n++
        }
        throw RestoreFailure("no free name for $wanted")
    }

    private fun copy(item: RecoverableItem, target: File) {
        val md = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        FileSource(File(item.originPath)).use { src ->
            if (item.offset + item.length > src.length) throw RestoreFailure("source changed: ${item.originPath}")
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var at = item.offset
                var left = item.length
                while (left > 0) {
                    val n = src.read(at, buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n <= 0) throw RestoreFailure("short read")
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    at += n
                    left -= n
                    copied += n
                }
                out.fd.sync()
            }
        }
        if (copied != item.length || target.length() != item.length) throw RestoreFailure("size mismatch for ${target.name}")
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (item.sha256.isNotEmpty() && sha != item.sha256) throw RestoreFailure("content changed while copying ${target.name}")
    }

    /** Hashes of what already sits in the destination (only files whose size matches are read). */
    private class ExistingHashes(private val dir: File) {
        private val bySize: MutableMap<Long, MutableSet<String>> = HashMap()
        private val files = dir.listFiles()?.filter { it.isFile }.orEmpty().groupBy { it.length() }
        private val loaded = HashSet<Long>()

        private fun load(size: Long) {
            if (!loaded.add(size)) return
            val set = bySize.getOrPut(size) { HashSet() }
            for (f in files[size].orEmpty()) {
                try { FileSource(f).use { set += ScanEngine.sha256(it, 0, it.length) } } catch (_: IOException) { }
            }
        }

        fun has(size: Long, sha: String): Boolean { load(size); return bySize[size]?.contains(sha) == true }
        fun add(size: Long, sha: String) { load(size); bySize.getOrPut(size) { HashSet() } += sha }
    }
}
