package app.radd.vm

import app.radd.engine.Found
import app.radd.engine.STAGES
import app.radd.engine.ScanEvent
import app.radd.engine.ScanFailure
import app.radd.model.*

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val progress: ScanProgress) : ScanState
    data class Finished(val found: Int, val unreadable: Int, val stillVisible: Int, val warnings: List<String>) : ScanState
    data class Cancelled(val found: Int) : ScanState
    data class Failed(val reason: ScanFailure.Reason?, val message: String) : ScanState
}

fun Found.toItem(id: Long) = RecoverableItem(
    id = id, name = name, source = source, quality = quality, sizeBytes = length, modifiedMs = modifiedMs, width = width, height = height,
    originPath = path, format = format, offset = offset, length = length, sha256 = sha256,
)

/** Folds engine events into the numbers the scan screen shows. */
class ProgressTracker(private val startedMs: Long) {
    private var stages = STAGES.map { StageProgress(it, StageStatus.PENDING, 0) }
    private var percent = 0f
    private var filesSeen = 0L
    private var found = 0
    private var path = ""
    private val latest = ArrayDeque<RecoverableItem>()
    private var nextTempId = -1L

    fun snapshot(nowMs: Long) = ScanProgress(
        stages = stages, percent = percent, filesSeen = filesSeen.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), found = found,
        elapsedSec = ((nowMs - startedMs) / 1000).toInt().coerceAtLeast(0), currentPath = path, latest = latest.toList().asReversed(),
    )

    fun apply(e: ScanEvent) {
        when (e) {
            is ScanEvent.Indexing -> { filesSeen = e.visited; path = e.path }
            is ScanEvent.StageStarted -> stages = stages.map { if (it.source == e.source) it.copy(status = StageStatus.RUNNING) else it }
            is ScanEvent.Progress -> {
                percent = (e.doneBytes.toDouble() / e.totalBytes.coerceAtLeast(1)).toFloat().coerceIn(0f, 0.99f)
                path = e.path
            }
            is ScanEvent.FoundOne -> {
                found++
                stages = stages.map { if (it.source == e.item.source) it.copy(found = it.found + 1) else it }
                latest.addLast(e.item.toItem(nextTempId--))
                while (latest.size > 5) latest.removeFirst()
            }
            is ScanEvent.StageDone -> stages = stages.map { if (it.source == e.source) it.copy(status = StageStatus.DONE, found = e.found) else it }
            is ScanEvent.Finished -> { percent = 1f; path = "" }
        }
    }
}
