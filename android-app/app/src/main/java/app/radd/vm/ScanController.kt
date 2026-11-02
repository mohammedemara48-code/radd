package app.radd.vm

import app.radd.data.FoundEntity
import app.radd.data.RaddDatabase
import app.radd.data.toEntity
import app.radd.engine.Found
import app.radd.engine.ScanEngine
import app.radd.engine.ScanEvent
import app.radd.engine.ScanFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one scan at a time and publishes its state. Results are written to the database in small batches, so they survive the
 * app being closed and the Results screen can show them while the scan is still running.
 */
class ScanController(
    private val db: RaddDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val engineFactory: () -> ScanEngine,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun reset() { if (!running.get()) _state.value = ScanState.Idle }

    suspend fun run() {
        if (!running.compareAndSet(false, true)) return
        val tracker = ProgressTracker(clock())
        val buffer = ArrayList<FoundEntity>()
        var lastFlush = clock()
        var lastPublish = 0L
        suspend fun flush() {
            if (buffer.isEmpty()) return
            val rows = buffer.toList()
            buffer.clear()
            db.found().insertAll(rows)
        }
        try {
            db.found().clear()
            _state.value = ScanState.Running(tracker.snapshot(clock()))
            engineFactory().run().flowOn(Dispatchers.IO).collect { e ->
                tracker.apply(e)
                val now = clock()
                if (e is ScanEvent.FoundOne) buffer += e.item.toEntity()
                if (buffer.size >= 40 || (buffer.isNotEmpty() && now - lastFlush > 400)) { flush(); lastFlush = now }
                when (e) {
                    is ScanEvent.Finished -> {
                        flush()
                        _state.value = ScanState.Finished(e.found, e.unreadable, e.stillVisible, e.warnings, e.excluded)
                    }
                    is ScanEvent.StageStarted, is ScanEvent.StageDone -> { lastPublish = now; _state.value = ScanState.Running(tracker.snapshot(now)) }
                    else -> if (now - lastPublish >= 150) { lastPublish = now; _state.value = ScanState.Running(tracker.snapshot(now)) }
                }
            }
        } catch (e: CancellationException) {
            val n = withContext(NonCancellable) {
                try { flush(); db.found().count() } catch (_: Exception) { 0 }
            }
            _state.value = ScanState.Cancelled(n)
            throw e
        } catch (f: ScanFailure) {
            _state.value = ScanState.Failed(f.reason, f.message.orEmpty())
        } catch (e: Exception) {
            _state.value = ScanState.Failed(null, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            running.set(false)
        }
    }
}
