package app.radd

import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import app.radd.model.*
import app.radd.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Error and empty states render and their buttons work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class StateViewsTest {
    @get:Rule(order = 0) val reset = ResetViewModelCaches
    @get:Rule(order = 1) val rule = createComposeRule()

    @Test fun errorPanelShowsTextAndFiresItsActions() {
        var primary = 0; var secondary = 0
        rule.setContent { RaddTheme(false) { ErrorPanel("الفحص وقف", "حصل خطأ", "رجوع", { primary++ }, secondaryLabel = "شوف اللي اتلقى", onSecondary = { secondary++ }) } }
        rule.onNodeWithText("الفحص وقف").assertExists()
        rule.onNodeWithText("رجوع").performClick()
        rule.onNodeWithText("شوف اللي اتلقى").performClick()
        assertEquals(1, primary); assertEquals(1, secondary)
    }

    @Test fun emptyStateWithoutAnActionHasNoButton() {
        rule.setContent { RaddTheme(false) { EmptyState("لسه ملقيناش حاجة", "الفحص شغال", null, {}) } }
        rule.onNodeWithText("لسه ملقيناش حاجة").assertExists()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test fun homeShowsAStartErrorAndThePreviousResultsButton() {
        var shown = 0
        rule.setContent {
            RaddTheme(false) { HomeScreen(PermissionUi(true, true), {}, {}, previousCount = 7, onShowPrevious = { shown++ }, message = "تعذّر بدء الفحص: X") }
        }
        rule.onNodeWithText("تعذّر بدء الفحص: X").assertExists()
        rule.onNodeWithText("ابدأ الفحص").assertExists()
        rule.onNodeWithText("نتايج آخر فحص (7)").performScrollTo().performClick()
        assertEquals(1, shown)
    }

    @Test fun homeWithoutPermissionOffersToGrantAndNoScan() {
        rule.setContent { RaddTheme(false) { HomeScreen(PermissionUi(false, true), {}, {}) } }
        rule.onNodeWithText("السماح بالوصول").assertExists()
        rule.onAllNodesWithText("ابدأ الفحص").assertCountEquals(0)
    }

    @Test fun anUndecodableThumbnailShowsABrokenImageNotACrash() {
        val item = RecoverableItem(1, "x.jpg", Source.HIDDEN, Quality.INTACT, 10, 0, 1, 1, "/does/not/exist.jpg")
        rule.setContent { RaddTheme(false) { FileThumb(item, androidx.compose.ui.Modifier.size(100.dp)) } }
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("تعذّر عرض الصورة").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun scanScreenRendersProgressAndDisabledPendingStages() {
        val p = ScanProgress(
            stages = app.radd.engine.STAGES.mapIndexed { i, s -> StageProgress(s, if (i < 2) StageStatus.DONE else if (i == 2) StageStatus.RUNNING else StageStatus.PENDING, i) },
            percent = 0.4f, filesSeen = 1234, found = 9, elapsedSec = 75, currentPath = "/storage/emulated/0/DCIM", latest = emptyList(),
        )
        rule.setContent { RaddTheme(false) { ScanScreen(p, { _, _ -> }, {}, {}) } }
        rule.onNodeWithText("40%").assertExists()
        rule.onNodeWithText("01:15").assertExists()
        rule.onNodeWithText("1,234").assertExists()
    }
}

