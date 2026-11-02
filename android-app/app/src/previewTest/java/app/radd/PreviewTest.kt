package app.radd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.radd.model.*
import app.radd.ui.*
import org.junit.Rule
import org.junit.Test

private const val THEME = "android:Theme.Material.Light.NoActionBar"

/** Paparazzi does not supply window insets, so we reserve room for the system bars it draws. */
@Composable
private fun Frame(bg: Color? = null, body: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        RaddTheme(dark = false) {
            Box(Modifier.fillMaxSize().background(bg ?: MaterialTheme.colorScheme.background).padding(top = 24.dp, bottom = 48.dp)) { body() }
        }
    }
}

private val thumb: ThumbSlot = { item, modifier ->
    Box(modifier) { ProceduralPhoto(item.id.toInt(), Modifier.fillMaxSize(), partial = item.quality == Quality.PARTIAL) }
}

private fun sampleScan(): ScanProgress {
    val latest = SampleItems.filter { it.source == Source.WHATSAPP && it.quality == Quality.INTACT }.take(5)
    return ScanProgress(
        stages = listOf(
            StageProgress(Source.TRASH, StageStatus.DONE, 23),
            StageProgress(Source.LOST_DIR, StageStatus.DONE, 9),
            StageProgress(Source.THUMBNAILS, StageStatus.DONE, 31),
            StageProgress(Source.WHATSAPP, StageStatus.RUNNING, 48),
            StageProgress(Source.TELEGRAM, StageStatus.PENDING, 0),
            StageProgress(Source.HIDDEN, StageStatus.PENDING, 0),
            StageProgress(Source.CARVED, StageStatus.PENDING, 0),
        ),
        percent = 0.58f, filesSeen = 41_260, found = 111, elapsedSec = 161,
        currentPath = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-20260918-WA0023.jpg",
        latest = latest,
    )
}

/** Renders the real Compose screens with sample data: ./gradlew -Ppreviews=true :app:testDebugUnitTest */
class PreviewTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ar", layoutDirection = com.android.resources.LayoutDirection.RTL),
        showSystemUi = true, theme = THEME, snapshotHandler = PngHandler(), useDeviceResolution = true,
    )

    private fun shot(name: String, bg: Color? = null, body: @Composable () -> Unit): Unit = paparazzi.snapshot(name) { Frame(bg, body) }

    @Test fun home(): Unit = shot("1_home_permission_ar") {
        HomeScreen(PermissionUi(allFilesGranted = false, mediaGranted = true), onGrant = {}, onStart = {})
    }

    @Test fun scanning(): Unit = shot("2_scanning_ar") {
        ScanScreen(sampleScan(), thumb, onStop = {}, onShowResults = {})
    }

    @Test fun results(): Unit = shot("3_results_grid_filters_ar") {
        val filters = Filters(size = SizeFilter.OVER_100K, date = DateFilter.MONTH)
        val shown = filters.apply(SampleItems, PreviewNow)
        // multi-select: five photos picked
        val picked = shown.filter { it.quality == Quality.INTACT }.take(8).filterIndexed { i, _ -> i in setOf(0, 2, 3, 5, 7) }.map { it.id }.toSet()
        ResultsScreen(SampleItems, shown, filters, picked, thumb, {}, {}, {}, {}, {}, {})
    }

    @Test fun emptyResults(): Unit = shot("6_results_empty_ar") {
        ResultsScreen(emptyList(), emptyList(), Filters(), emptySet(), thumb, {}, {}, {}, {}, {}, {}, summary = ScanSummary(stillVisible = 4_212, excluded = 1_930))
    }

    @Test fun fullPreview(): Unit = shot("4_full_preview_ar", bg = Color(0xFF0B100E)) {
        val item = SampleItems.first { it.source == Source.WHATSAPP && it.quality == Quality.INTACT && it.height > it.width && it.sizeBytes > 600 * 1024 }
        PhotoPreviewScreen(item, 3, 47, selected = false, thumb, {}, {}, {})
    }

    @Test fun restoreDone(): Unit = shot("5_restore_done_ar") {
        val saved = SampleItems.filter { it.quality == Quality.INTACT && it.source != Source.THUMBNAILS }.take(4) +
            SampleItems.first { it.quality == Quality.PARTIAL && it.source == Source.CARVED }
        RestoreDoneScreen(RestoreResult(saved, skippedDuplicates = 1, failed = 0, folderPath = "/storage/emulated/0/RaddRecovered", renamed = 2), thumb, {}, {}, {})
    }
}
