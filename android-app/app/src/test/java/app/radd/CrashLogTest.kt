package app.radd

import app.radd.android.CrashLog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class CrashLogTest {
    @Test fun uncaughtExceptionIsRecordedAndStillPassedOn() {
        val seen = ArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        try {
            val log = CrashLog(graph.context)
            log.clear(); assertNull(log.pending())
            log.install()
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
            val text = log.pending()
            assertNotNull(text)
            assertTrue(text.orEmpty().contains("IllegalStateException") && text.orEmpty().contains("boom"))
            assertTrue("the earlier handler (system crash dialog) must still run", seen.single().message == "boom")
            log.clear(); assertNull(log.pending())
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }

    @Test fun veryLongTracesAreCut() {
        val log = CrashLog(graph.context)
        log.record("main", RuntimeException("x".repeat(50_000)))
        assertTrue(log.pending().orEmpty().length <= 8_200)
        log.clear()
    }
}
