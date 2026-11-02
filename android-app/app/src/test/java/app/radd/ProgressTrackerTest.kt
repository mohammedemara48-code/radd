package app.radd

import app.radd.engine.Found
import app.radd.engine.ScanEvent
import app.radd.engine.STAGES
import app.radd.model.Quality
import app.radd.model.Source
import app.radd.model.StageStatus
import app.radd.vm.ProgressTracker
import org.junit.Assert.*
import org.junit.Test

class ProgressTrackerTest {
    private fun found(n: Int, s: Source = Source.WHATSAPP) = Found(s, Quality.INTACT, "/p/$n.jpg", 0, 10_000, 1, 10, 10, "jpg", "h$n", "$n.jpg")

    @Test fun foldsEventsIntoWhatTheScreenShows() {
        val t = ProgressTracker(startedMs = 1_000)
        assertEquals(7, t.snapshot(1_000).stages.size)
        t.apply(ScanEvent.Indexing(4_200, "/storage/emulated/0/DCIM"))
        assertEquals(4_200, t.snapshot(2_000).filesSeen)
        t.apply(ScanEvent.StageStarted(Source.TRASH, 3)); t.apply(ScanEvent.FoundOne(found(1, Source.TRASH)))
        t.apply(ScanEvent.Progress(50, 200, 1, 4, "/x"))
        var s = t.snapshot(62_000)
        assertEquals(0.25f, s.percent, 0.001f); assertEquals(1, s.found); assertEquals(61, s.elapsedSec)
        assertEquals(StageStatus.RUNNING, s.stages.first().status); assertEquals(1, s.stageNumber)
        t.apply(ScanEvent.StageDone(Source.TRASH, 1)); t.apply(ScanEvent.StageStarted(Source.LOST_DIR, 0))
        s = t.snapshot(63_000)
        assertEquals(StageStatus.DONE, s.stages.first().status); assertEquals(2, s.stageNumber)
        t.apply(ScanEvent.Finished(1, 4_200, 0, 0, emptyList()))
        assertEquals(1f, t.snapshot(64_000).percent, 0f)
    }

    @Test fun percentNeverReachesOneBeforeFinished() {
        val t = ProgressTracker(0)
        t.apply(ScanEvent.Progress(100, 100, 1, 1, ""))
        assertTrue(t.snapshot(1).percent < 1f)
    }

    @Test fun keepsOnlyTheFiveLatestFindsNewestFirst() {
        val t = ProgressTracker(0)
        (1..8).forEach { t.apply(ScanEvent.FoundOne(found(it))) }
        val latest = t.snapshot(0).latest
        assertEquals(5, latest.size)
        assertEquals("8.jpg", latest.first().name)
        assertEquals(8, t.snapshot(0).found)
        assertEquals(STAGES.size, t.snapshot(0).stages.size)
    }
}
