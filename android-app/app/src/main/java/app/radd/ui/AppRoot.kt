package app.radd.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.radd.android.Graph
import app.radd.engine.ScanFailure
import app.radd.model.*
import app.radd.vm.*

/**
 * Creates a ViewModel from the *application* Context. It never reads the Application out of CreationExtras: screens may use a
 * ViewModelStoreOwner without default extras, which crashed مستند v1.0.0 on launch.
 */
@Composable
inline fun <reified VM : androidx.lifecycle.ViewModel> appViewModel(crossinline make: (Context) -> VM): VM {
    val app = LocalContext.current.applicationContext
    val factory = remember(app) { viewModelFactory { initializer { make(app) } } }
    return viewModel(factory = factory)
}

private val FileThumbSlot: ThumbSlot = { item, modifier -> FileThumb(item, modifier) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRoot(onGrant: () -> Unit, onNeedNotifications: () -> Unit) {
    val ctx = LocalContext.current
    val nav: NavViewModel = appViewModel { NavViewModel(it) }
    val home: HomeViewModel = appViewModel { HomeViewModel(it) }
    val scan: ScanViewModel = appViewModel { ScanViewModel(it) }
    val results: ResultsViewModel = appViewModel { ResultsViewModel(it) }

    val permission by home.permission.collectAsStateWithLifecycle()
    val previous by home.previousCount.collectAsStateWithLifecycle()
    val scanState by scan.state.collectAsStateWithLifecycle()
    val startError by scan.startError.collectAsStateWithLifecycle()
    val resultsUi by results.state.collectAsStateWithLifecycle()
    val restoreUi by results.restore.collectAsStateWithLifecycle()

    // permissions can change while the app is in the background (the all-files switch lives in system settings)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) home.refresh() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // a scan that is already running (the service outlives the activity) takes us to its screen; a finished scan opens the results
    LaunchedEffect(scanState) {
        when (scanState) {
            is ScanState.Running -> if (nav.route == Route.HOME) nav.go(Route.SCAN)
            is ScanState.Finished -> if (nav.route == Route.SCAN) nav.go(Route.RESULTS)
            is ScanState.Cancelled -> if (nav.route == Route.SCAN) nav.go(Route.RESULTS)
            else -> Unit
        }
    }
    LaunchedEffect(restoreUi) { if (restoreUi is RestoreUi.Done && nav.route != Route.DONE) nav.go(Route.DONE) }
    LaunchedEffect(startError) { if (startError != null) Toast.makeText(ctx, "تعذّر بدء الفحص", Toast.LENGTH_LONG).show() }

    BackHandler(enabled = nav.route != Route.HOME) {
        if (nav.route == Route.DONE) results.dismissRestore()
        nav.back()
    }

    Box(Modifier.fillMaxSize()) {
        when (nav.route) {
            Route.HOME -> HomeScreen(
                permission = permission, onGrant = onGrant,
                onStart = { onNeedNotifications(); scan.start() },
                previousCount = previous, onShowPrevious = { nav.go(Route.RESULTS) },
                message = startError?.let { "تعذّر بدء الفحص: $it" },
            )

            Route.SCAN -> when (val st = scanState) {
                is ScanState.Running -> ScanScreen(
                    st.progress, FileThumbSlot,
                    onStop = { scan.stop() }, onShowResults = { nav.go(Route.RESULTS) },
                )
                is ScanState.Failed -> ErrorPanel(
                    title = "الفحص وقف",
                    body = when (st.reason) {
                        ScanFailure.Reason.NO_PERMISSION -> "التطبيق مش قادر يقرا الذاكرة. اتأكد إن «إدارة كل الملفات» لسه متفعّلة وارجع جرّب."
                        ScanFailure.Reason.NO_STORAGE -> "مش لاقي ذاكرة يتفحص."
                        null -> "حصل خطأ غير متوقع (${st.message}). اللي اتلقى قبل الخطأ محفوظ."
                    },
                    actionLabel = "رجوع", onAction = { scan.reset(); nav.go(Route.HOME) },
                    secondaryLabel = if (st.reason == null) "شوف اللي اتلقى" else null, onSecondary = { nav.go(Route.RESULTS) },
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            Route.RESULTS -> when (val ui = resultsUi) {
                is ResultsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ResultsUi.Failed -> ErrorPanel(
                    "مش قادر أعرض النتايج", "قاعدة البيانات رجّعت خطأ: ${ui.message}", "فحص جديد",
                    { nav.go(Route.HOME) },
                )
                is ResultsUi.Ready -> ResultsScreen(
                    all = ui.all, shown = ui.shown, filters = ui.filters, selected = ui.selected, thumb = FileThumbSlot,
                    onFilters = results::setFilters,
                    onOpen = { results.openPreview(it.id); nav.go(Route.PREVIEW) },
                    onToggle = { results.toggle(it.id) },
                    onSelectAll = results::selectAll, onClearSelection = results::clearSelection, onRestore = { results.restore() },
                    onBack = { nav.go(Route.HOME) }, scanning = ui.scanning, onNewScan = { nav.go(Route.HOME) }, summary = ui.summary,
                )
            }

            Route.PREVIEW -> {
                val ui = resultsUi
                if (ui is ResultsUi.Ready && ui.shown.isNotEmpty()) {
                    val start = ui.shown.indexOfFirst { it.id == results.previewId }.coerceAtLeast(0)
                    val pager = rememberPagerState(initialPage = start) { ui.shown.size }
                    var zoomed by remember { mutableStateOf(false) }
                    val current = ui.shown.getOrNull(pager.currentPage) ?: ui.shown.first()
                    LaunchedEffect(pager.currentPage) { zoomed = false }
                    PhotoPreviewScreen(
                        item = current, index = pager.currentPage + 1, total = ui.shown.size, selected = current.id in ui.selected, thumb = FileThumbSlot,
                        onClose = { nav.go(Route.RESULTS) }, onToggle = { results.toggle(current.id) }, onRestore = { results.restore(only = current.id) },
                        imageArea = {
                            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, pageSpacing = 12.dp) { page ->
                                ui.shown.getOrNull(page)?.let { ZoomableThumb(it, onZoomed = { z -> zoomed = z }, Modifier.fillMaxSize()) }
                            }
                        },
                    )
                } else {
                    EmptyState("مفيش صورة تتعرض", "ارجع للنتايج.", "رجوع", { nav.go(Route.RESULTS) })
                }
            }

            Route.DONE -> (restoreUi as? RestoreUi.Done)?.let { done ->
                RestoreDoneScreen(
                    done.result, FileThumbSlot,
                    onOpenFolder = { openFolder(ctx, done.result.folderPath) },
                    onBack = { results.dismissRestore(); nav.go(Route.RESULTS) },
                    onNewScan = { results.dismissRestore(); nav.go(Route.HOME) },
                )
            } ?: LaunchedEffect(Unit) { nav.go(Route.RESULTS) }
        }

        // restore progress / failure, over whatever screen started it
        when (val r = restoreUi) {
            is RestoreUi.Running -> AlertDialog(
                onDismissRequest = {},
                title = { Text("بيسترجع الصور…") },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { if (r.total == 0) 0f else r.done.toFloat() / r.total }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${r.done} من ${r.total}")
                    }
                },
                confirmButton = { TextButton(results::cancelRestore) { Text("إلغاء") } },
            )
            is RestoreUi.Failed -> AlertDialog(
                onDismissRequest = results::dismissRestore,
                title = { Text("الاسترجاع فشل") },
                text = { Text("مقدرناش نكتب في مجلد RaddRecovered: ${r.message}") },
                confirmButton = { TextButton(results::dismissRestore) { Text("تمام") } },
            )
            else -> Unit
        }

        CrashReportDialog()
    }
}

/** After a crash, the next start offers to send the error text (nothing is sent automatically). */
@Composable
private fun CrashReportDialog() {
    val ctx = LocalContext.current
    val log = remember { Graph.of(ctx).crashLog }
    var report by remember { mutableStateOf(log.pending()) }
    val r = report ?: return
    AlertDialog(
        onDismissRequest = { log.clear(); report = null },
        title = { Text("التطبيق قفل فجأة آخر مرة") },
        text = { Text("تحب تبعت تفاصيل الخطأ عشان نصلحه؟ مفيش حاجة بتتبعت إلا لو اخترت مشاركة.") },
        confirmButton = {
            TextButton({
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r)
                try { ctx.startActivity(Intent.createChooser(i, null)) } catch (_: Exception) { }
                log.clear(); report = null
            }) { Text("مشاركة") }
        },
        dismissButton = { TextButton({ log.clear(); report = null }) { Text("لأ") } },
    )
}

private fun openFolder(ctx: Context, path: String) {
    val rel = path.substringAfter("/storage/emulated/0/", "")
    val uri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
    val tries = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory"),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
    )
    for (i in tries) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
    }
    Toast.makeText(ctx, path, Toast.LENGTH_LONG).show()
}
