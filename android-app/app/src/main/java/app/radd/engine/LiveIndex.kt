package app.radd.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln

/**
 * The photos the gallery still shows, with the three ways a candidate can turn out to be one of them: the same path, the same
 * bytes, or the same scene (perceptual match). Fingerprints of gallery photos are made lazily, in parallel, and only for the
 * photos whose shape could match the candidate.
 */
class LiveIndex(
    private val rows: List<MediaRow>,
    private val fingerprinter: Fingerprinter,
    private val onCompare: (done: Int, total: Int) -> Unit = { _, _ -> },
) {
    private val paths = rows.mapTo(HashSet(rows.size * 2)) { it.path }
    private val bySize = rows.groupBy { it.size }
    private val hashes = ConcurrentHashMap<String, String>()
    private val prints = ConcurrentHashMap<String, Any>()   // Fingerprint, or MISSING
    private val missing = Any()

    val size: Int get() = rows.size

    fun hasPath(path: String) = path in paths

    fun hasSameBytes(length: Long, sha: String): Boolean {
        val same = bySize[length] ?: return false
        for (row in same) {
            val h = hashes.getOrPut(row.path) { try { FileSource(File(row.path)).use { ScanEngine.sha256(it, 0, it.length) } } catch (_: Exception) { "-" } }
            if (h == sha) return true
        }
        return false
    }

    /** True when some gallery photo shows the same scene as [fp]. May take a while the first time (it decodes gallery photos). */
    suspend fun hasSameScene(fp: Fingerprint, aspectTolerance: Double): Boolean {
        val target = ln(fp.width.toDouble() / fp.height.coerceAtLeast(1))
        // MediaStore sizes are the raw sensor size, so a rotated photo has to be allowed to match in either orientation
        val pool = rows.filter {
            if (it.width <= 0 || it.height <= 0) true
            else {
                val own = ln(it.width.toDouble() / it.height)
                minOf(abs(own - target), abs(own + target)) <= aspectTolerance + 0.02
            }
        }
        val todo = pool.filter { !prints.containsKey(it.path) }
        if (todo.isNotEmpty()) compute(todo)
        for (row in pool) {
            val other = prints[row.path] as? Fingerprint ?: continue
            if (Fingerprints.sameScene(fp, other, aspectTolerance)) return true
        }
        return false
    }

    private suspend fun compute(todo: List<MediaRow>) = coroutineScope {
        val done = AtomicInteger()
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        todo.withIndex().groupBy({ it.index % workers }, { it.value }).values.map { part ->
            async(Dispatchers.Default) {
                for (row in part) {
                    ensureActive()
                    val fp = try { fingerprinter.of(File(row.path), 0, File(row.path).length()) } catch (_: Exception) { null }
                    prints[row.path] = fp ?: missing
                    val n = done.incrementAndGet()
                    if (n % 25 == 0 || n == todo.size) onCompare(n, todo.size)
                }
            }
        }.awaitAll()
    }
}
