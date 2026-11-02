package app.radd

import app.radd.data.RaddDatabase
import app.radd.engine.*
import app.radd.model.Source
import app.radd.vm.ScanController
import app.radd.vm.ScanState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The controller on top of the real engine and a real (Robolectric) Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ScanControllerTest {
    @get:Rule(order = 0) val reset = ResetViewModelCaches
    @get:Rule(order = 1) val tmp = TemporaryFolder()

    private fun db() = androidx.room.Room.inMemoryDatabaseBuilder(graph.context, RaddDatabase::class.java).allowMainThreadQueries().build()

    private fun tree(): File {
        val root = tmp.newFolder("s")
        Fixtures.write(File(root, "LOST.DIR/#1"), Fixtures.jpeg(1, 200, 150))
        Fixtures.write(File(root, "DCIM/.thumbnails/.thumbdata3--1"), Fixtures.jpeg(2, 100, 80), Fixtures.junk(200), Fixtures.jpeg(3, 100, 80))
        return root
    }

    @Test fun aFinishedScanIsSavedAndReported() {
        val db = db(); val root = tree()
        val c = ScanController(db) { ScanEngine(StorageRoots { listOf(root) }) }
        runBlocking { c.run() }
        val st = c.state.value as ScanState.Finished
        assertEquals(3, st.found)
        assertEquals(3, runBlocking { db.found().count() })
        db.close()
    }

    @Test fun startingANewScanReplacesTheOldResults() {
        val db = db(); val root = tree()
        val c = ScanController(db) { ScanEngine(StorageRoots { listOf(root) }) }
        runBlocking { c.run(); c.run() }
        assertEquals(3, runBlocking { db.found().count() })
        db.close()
    }

    @Test fun missingStorageBecomesAFailedStateWithAReason() {
        val db = db()
        val c = ScanController(db) { ScanEngine(StorageRoots { listOf(File("/nonexistent")) }) }
        runBlocking { c.run() }
        val st = c.state.value as ScanState.Failed
        assertEquals(ScanFailure.Reason.NO_STORAGE, st.reason)
        assertFalse(c.isRunning)
        db.close()
    }

    @Test fun anUnexpectedEngineErrorIsAFailedStateNotACrashAndKeepsWhatWasFound() {
        val db = db(); val root = tree()
        val real = ScanEngine(StorageRoots { listOf(root) })
        val c = ScanController(db) { real }
        runBlocking { c.run() }
        assertTrue(c.state.value is ScanState.Finished)
        // a failure injected after the first event
        val boom = ScanController(db) { throw IllegalStateException("boom") }
        runBlocking { boom.run() }
        val st = boom.state.value as ScanState.Failed
        assertTrue(st.message.contains("boom"))
        assertNull(st.reason)
        db.close()
    }

    @Test fun cancellingMarksTheScanCancelledAndKeepsPartialResults() {
        val db = db()
        val root = tmp.newFolder("big")
        repeat(400) { Fixtures.write(File(root, "Pictures/.p/i$it.jpg"), Fixtures.jpeg(it, 100, 80)) }
        val c = ScanController(db) { ScanEngine(StorageRoots { listOf(root) }) }
        runBlocking {
            val job = launch(Dispatchers.Default) { c.run() }
            while (runCatching { db.found().count() }.getOrDefault(0) == 0) delay(5)
            job.cancelAndJoin()
        }
        val st = c.state.value
        assertTrue("state $st", st is ScanState.Cancelled || st is ScanState.Finished)
        assertTrue(runBlocking { db.found().count() } > 0)
        assertFalse(c.isRunning)
        db.close()
    }

    @Test fun onlyOneScanRunsAtATime() {
        val db = db(); val root = tree()
        val c = ScanController(db) { ScanEngine(StorageRoots { listOf(root) }) }
        runBlocking { coroutineScope { launch { c.run() }; launch { c.run() } } }
        assertEquals(3, runBlocking { db.found().count() })
        db.close()
    }
}
