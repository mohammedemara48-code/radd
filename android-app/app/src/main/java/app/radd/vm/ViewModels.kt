package app.radd.vm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radd.android.Graph
import app.radd.android.ScanService
import app.radd.data.toItem
import app.radd.model.*
import app.radd.ui.PermissionUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Route { HOME, SCAN, RESULTS, PREVIEW, DONE }

/** Which screen is showing. Lives in the Activity so a rotation or a recreation keeps the place. */
class NavViewModel(@Suppress("UNUSED_PARAMETER") context: Context) : ViewModel() {
    var route by mutableStateOf(Route.HOME)
        private set

    fun go(r: Route) { route = r }

    fun back(): Boolean = when (route) {
        Route.HOME -> false
        Route.SCAN, Route.RESULTS -> { route = Route.HOME; true }
        Route.PREVIEW, Route.DONE -> { route = Route.RESULTS; true }
    }
}

fun interface Permissions { fun state(): PermissionUi }

class AndroidPermissions(private val context: Context) : Permissions {
    override fun state(): PermissionUi {
        // a ROM that mishandles the check must read as "not granted", never crash the home screen
        val all = if (Build.VERSION.SDK_INT >= 30) try { Environment.isExternalStorageManager() } catch (_: Exception) { false } else true
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        // with "all files access" MediaStore can be read without the separate photo permission
        val media = all || ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        return PermissionUi(all, media)
    }
}

class HomeViewModel(context: Context, private val permissions: Permissions = AndroidPermissions(context)) : ViewModel() {
    private val graph = Graph.of(context)
    private val _permission = MutableStateFlow(permissions.state())
    val permission: StateFlow<PermissionUi> = _permission.asStateFlow()

    /** Results kept from the last scan (they live in the database). */
    val previousCount: StateFlow<Int> = graph.db.found().observeCount()
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun refresh() { _permission.value = permissions.state() }
}

class ScanViewModel(private val context: Context) : ViewModel() {
    private val controller = Graph.of(context).controller
    val state: StateFlow<ScanState> = controller.state
    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    fun start() {
        _startError.value = null
        try {
            ScanService.start(context)
        } catch (e: Exception) {
            _startError.value = e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    fun stop() {
        try { ScanService.stop(context) } catch (_: Exception) { }
    }

    fun reset() = controller.reset()
    fun clearError() { _startError.value = null }
}

sealed interface ResultsUi {
    data object Loading : ResultsUi
    data class Failed(val message: String) : ResultsUi
    data class Ready(
        val all: List<RecoverableItem>,
        val shown: List<RecoverableItem>,
        val filters: Filters,
        val selected: Set<Long>,
        val scanning: Boolean,
        val summary: ScanSummary? = null,
    ) : ResultsUi
}

sealed interface RestoreUi {
    data object Idle : RestoreUi
    data class Running(val done: Int, val total: Int) : RestoreUi
    data class Done(val result: RestoreResult) : RestoreUi
    data class Failed(val message: String) : RestoreUi
}

class ResultsViewModel(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    source: Flow<List<app.radd.data.FoundEntity>>? = null,
) : ViewModel() {
    private val graph = Graph.of(context)
    private val filters = MutableStateFlow(Filters())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    private val rows: Flow<Result<List<RecoverableItem>>> = (source ?: graph.db.found().observeAll())
        .map { list -> Result.success(list.mapNotNull { it.toItem() }) }
        .catch { emit(Result.failure(it)) }

    val state: StateFlow<ResultsUi> = combine(rows, filters, selected, graph.controller.state) { r, f, sel, scan ->
        r.fold(
            onSuccess = { items ->
                val ids = items.mapTo(HashSet()) { it.id }
                ResultsUi.Ready(items, f.apply(items, clock()), f, sel.filterTo(HashSet()) { it in ids }, scan is ScanState.Running,
                    (scan as? ScanState.Finished)?.let { ScanSummary(it.stillVisible, it.excluded) })
            },
            onFailure = { ResultsUi.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ResultsUi.Loading)

    var previewId by mutableStateOf<Long?>(null)
        private set

    private val _restore = MutableStateFlow<RestoreUi>(RestoreUi.Idle)
    val restore: StateFlow<RestoreUi> = _restore.asStateFlow()
    private var restoreJob: Job? = null

    fun setFilters(f: Filters) { filters.value = f }
    fun toggle(id: Long) { selected.update { if (id in it) it - id else it + id } }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { (state.value as? ResultsUi.Ready)?.let { r -> selected.value = r.shown.mapTo(HashSet()) { it.id } } }
    fun openPreview(id: Long) { previewId = id }

    /** Restores the selected items, or [only] when given (the single-photo button in the viewer). */
    fun restore(only: Long? = null) {
        val ready = state.value as? ResultsUi.Ready ?: return
        val ids = if (only != null) setOf(only) else ready.selected
        val items = ready.all.filter { it.id in ids }
        if (items.isEmpty() || restoreJob?.isActive == true) return
        _restore.value = RestoreUi.Running(0, items.size)
        restoreJob = viewModelScope.launch {
            try {
                val result = graph.restoreEngine.restore(items, graph.recoveredDir) { done, total -> _restore.value = RestoreUi.Running(done, total) }
                selected.value = emptySet()
                _restore.value = RestoreUi.Done(result)
            } catch (e: CancellationException) {
                _restore.value = RestoreUi.Idle
                throw e
            } catch (e: Exception) {
                _restore.value = RestoreUi.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelRestore() { restoreJob?.cancel() }
    fun dismissRestore() { _restore.value = RestoreUi.Idle }
}
