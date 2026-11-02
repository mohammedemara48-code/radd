package app.radd

import app.radd.android.ResultsFormat
import app.radd.data.FoundEntity
import app.radd.data.RaddDatabase
import app.radd.model.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Results saved by version 1.0.0 (made with the old, wrong rules) must be gone after the upgrade. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ResultsFormatTest {
    private val name = "upgrade-test.db"
    private val ctx get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun row(i: Int) = FoundEntity(
        source = Source.CARVED.name, quality = "INTACT", path = "/x/$i", offset = 0, length = 20_000, modifiedMs = 1, width = 700, height = 500,
        format = "jpg", sha256 = "sha$i", name = "n$i.jpg",
    )

    private fun count(): Int {
        val db = RaddDatabase.open(ctx, name)
        return try { runBlocking { db.found().count() } } finally { db.close() }
    }

    private fun seed(n: Int) {
        val db = RaddDatabase.open(ctx, name)
        try { runBlocking { db.found().insertAll((1..n).map(::row)) } } finally { db.close() }
    }

    @Test fun anUpgradeFromTheOldVersionDeletesTheOldResults() {
        ctx.getSharedPreferences("radd_state", 0).edit().clear().commit()   // what a 1.0.0 install looks like: no marker
        seed(68)
        assertEquals(68, count())
        assertTrue(ResultsFormat.migrate(ctx, name))
        assertEquals(0, count())
    }

    @Test fun resultsFromTheCurrentVersionAreKept() {
        ctx.getSharedPreferences("radd_state", 0).edit().putInt("results_format", ResultsFormat.CURRENT).commit()
        seed(5)
        assertFalse(ResultsFormat.migrate(ctx, name))
        assertEquals(5, count())
    }

    @Test fun itOnlyRunsOnce() {
        ctx.getSharedPreferences("radd_state", 0).edit().clear().commit()
        ResultsFormat.migrate(ctx, name)
        seed(3)
        assertFalse(ResultsFormat.migrate(ctx, name))
        assertEquals(3, count())
    }

    @Test fun aFreshInstallHasNothingToDeleteAndDoesNotFail() {
        ctx.getSharedPreferences("radd_state", 0).edit().clear().commit()
        ctx.deleteDatabase(name)
        assertFalse(ResultsFormat.migrate(ctx, name))
        assertEquals(0, count())
    }
}
