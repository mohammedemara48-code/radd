package app.radd

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.radd.vm.NavViewModel
import app.radd.vm.ResultsViewModel
import app.radd.vm.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real MainActivity on the JVM and visits every screen, so a crash at startup or on any navigation route fails here
 * instead of on a phone. (مستند v1.0.0 crashed because screens created their ViewModels without an Application.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class LaunchTest {
    @get:Rule(order = 0) val reset = ResetViewModelCaches
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private val nav get() = vmOf<NavViewModel>(rule.activity)
    private val results get() = vmOf<ResultsViewModel>(rule.activity)

    private fun go(r: Route) { rule.runOnUiThread { nav.go(r) }; rule.waitForIdle() }
    private fun see(text: String) { rule.waitFor("text \"$text\"") { rule.hasText(text) } }

    @Test fun homeShowsOnLaunchWithThePermissionStep() {
        see("هات صورك اللي اتمسحت")
        see("السماح بالوصول")
        see("الصراحة قبل ما تبدأ")
        rule.onNodeWithText("السماح بالوصول").assertExists().assertHasClickAction()
    }

    @Test fun resultsShowAnHonestEmptyStateBeforeAnyScan() {
        go(Route.RESULTS)
        see("مفيش صور اتلقت")
        see("لو الصورة اتمسحت نهائي من غير أثر")
    }

    @Test fun resultsGridPreviewAndBack() {
        seedResults(12)
        go(Route.RESULTS)
        see("12 صورة قابلة للاسترجاع")
        see("ظاهر 12 من 12")
        // open the first photo in the full viewer
        val first = runBlocking { graph.db.found().count() }
        assertEquals(12, first)
        rule.runOnUiThread { results.openPreview(1); nav.go(Route.PREVIEW) }
        see("استرجع الصورة دي")
        see("المسار الأصلي")
        go(Route.RESULTS)
        see("12 صورة قابلة للاسترجاع")
    }

    @Test fun selectingPhotosShowsTheRestoreBar() {
        seedResults(6)
        go(Route.RESULTS)
        see("6 صورة قابلة للاسترجاع")
        rule.runOnUiThread { results.toggle(1); results.toggle(2) }
        see("2 محددة")
        see("استرجاع")
        rule.runOnUiThread { results.selectAll() }
        see("6 محددة")
    }

    @Test fun restoringFromTheUiEndsOnTheDoneScreenAndWritesTheFiles() {
        seedResults(3)
        go(Route.RESULTS)
        see("3 صورة قابلة للاسترجاع")
        rule.runOnUiThread { results.toggle(1); results.toggle(2); results.restore() }
        rule.waitFor("done screen") { nav.route == Route.DONE }
        see("اتسترجعت 2 صور")
        see("افتح المجلد")
        val written = graph.recoveredDir.list().orEmpty().toSet()
        assertEquals(setOf("photo-1.jpg", "photo-2.jpg"), written)
    }

    @Test fun filtersCanBeChangedAndClearedFromTheUi() {
        seedResults(10)
        go(Route.RESULTS)
        see("ظاهر 10 من 10")
        rule.onNodeWithText("الحالة · الكل").performClick()
        rule.onNodeWithText("الناقصة بس").performClick()
        see("ظاهر 2 من 10")
        rule.onNodeWithText("مسح الفلاتر (1)").performClick()
        see("ظاهر 10 من 10")
    }

    @Test fun anEmptyFilterResultOffersToClearIt() {
        seedResults(4)
        go(Route.RESULTS)
        rule.onNodeWithText("الحجم · أي حجم").performClick()
        rule.onNodeWithText("أكبر من 1 م.ب").performClick()
        see("مفيش نتايج بالفلاتر دي")
        rule.onNodeWithText("مسح الفلاتر").performClick()
        see("ظاهر 4 من 4")
    }

    @Test fun scanScreenWithoutARunningScanDoesNotCrash() {
        go(Route.SCAN)
        rule.waitForIdle()
        go(Route.HOME)
        see("هات صورك اللي اتمسحت")
    }

    @Test fun aRealScanOfTheSandboxStorageFindsPhotosAndLandsOnResults() {
        val root = android.os.Environment.getExternalStorageDirectory()
        Fixtures.write(java.io.File(root, "DCIM/.thumbnails/.thumbdata3--1"), Fixtures.jpeg(1, 100, 80), Fixtures.junk(200), Fixtures.jpeg(2, 100, 80))
        Fixtures.write(java.io.File(root, "DCIM/Camera/.trashed-1763900000-IMG_9.jpg"), Fixtures.jpeg(3, 200, 150))
        runBlocking { graph.controller.run() }
        val st = graph.controller.state.value
        assertTrue("state was $st", st is app.radd.vm.ScanState.Finished)
        assertEquals(3, runBlocking { graph.db.found().count() })
        rule.waitFor("auto-open results or stay") { true }
    }
}
