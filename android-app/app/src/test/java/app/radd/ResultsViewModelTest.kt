package app.radd

import app.radd.model.*
import app.radd.vm.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ResultsViewModelTest {
    @get:Rule val reset = ResetViewModelCaches

    private fun <T> await(what: String, cond: () -> T?): T {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); cond()?.let { return it }; Thread.sleep(20) }
        throw AssertionError("timed out: $what")
    }

    private fun VmReady(vm: ResultsViewModel, n: Int) = await("ready with $n") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.all.size == n } }

    @Test fun startsLoadingThenShowsWhatTheDatabaseHolds() {
        seedResults(8)
        val vm = ResultsViewModel(graph.context)
        val ready = VmReady(vm, 8)
        assertEquals(8, ready.shown.size)
        assertTrue(ready.selected.isEmpty())
    }

    @Test fun filtersSelectionAndSelectAllWork() {
        seedResults(10)
        val vm = ResultsViewModel(graph.context)
        VmReady(vm, 10)
        vm.setFilters(Filters(quality = QualityFilter.PARTIAL_ONLY))
        val partial = await("filtered") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.shown.size == 2 } }
        vm.selectAll()
        assertEquals(2, await("selected") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.selected.size == 2 } }.selected.size)
        vm.toggle(partial.shown.first().id)
        await("one deselected") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.selected.size == 1 } }
        vm.clearSelection()
        await("cleared") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.selected.isEmpty() } }
    }

    @Test fun aDatabaseErrorBecomesAFailedStateNotACrash() {
        val vm = ResultsViewModel(graph.context, source = kotlinx.coroutines.flow.flow { throw android.database.sqlite.SQLiteException("disk I/O error") })
        val failed = await("failed") { vm.state.value as? ResultsUi.Failed }
        assertTrue(failed.message.isNotBlank())
    }

    @Test fun restoreWritesFilesAndReportsDone() {
        seedResults(4)
        val vm = ResultsViewModel(graph.context)
        val ready = VmReady(vm, 4)
        vm.toggle(ready.all[0].id); vm.toggle(ready.all[1].id)
        await("selected") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.selected.size == 2 } }
        vm.restore()
        val done = await("restore done") { vm.restore.value as? RestoreUi.Done }
        assertEquals(2, done.result.saved.size); assertEquals(0, done.result.failed)
        assertEquals(2, graph.recoveredDir.list().orEmpty().size)
        // restoring the same two again is recognised as duplicates, nothing is overwritten or renamed
        vm.dismissRestore()
        vm.toggle(ready.all[0].id); vm.toggle(ready.all[1].id)
        await("selected again") { (vm.state.value as? ResultsUi.Ready)?.takeIf { it.selected.size == 2 } }
        vm.restore()
        val again = await("second restore") { vm.restore.value as? RestoreUi.Done }
        assertEquals(2, again.result.skippedDuplicates); assertEquals(2, graph.recoveredDir.list().orEmpty().size)
    }

    @Test fun restoreOfAMissingSourceIsReportedAsFailedItems() {
        seedResults(2)
        val vm = ResultsViewModel(graph.context)
        val ready = VmReady(vm, 2)
        java.io.File(ready.all[0].originPath).delete()
        vm.restore(only = ready.all[0].id)
        val done = await("done") { vm.restore.value as? RestoreUi.Done }
        assertEquals(1, done.result.failed); assertEquals(0, done.result.saved.size)
    }
}
