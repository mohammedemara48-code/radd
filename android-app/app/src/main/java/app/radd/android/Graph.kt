package app.radd.android

import android.app.Application
import android.content.Context
import android.os.Environment
import app.radd.data.RaddDatabase
import app.radd.engine.RestoreEngine
import app.radd.engine.ScanEngine
import app.radd.vm.ScanController
import java.io.File

/** Open for tests: each Robolectric test application gets its own database file. */
open class RaddApp : Application() {
    open val databaseName: String get() = RaddDatabase.NAME

    override fun onCreate() {
        super.onCreate()
        // results saved by an older version were made with older rules; they must not show up as if they were real
        ResultsFormat.migrate(this, databaseName)
        Graph.of(this).crashLog.install()
    }
}

/**
 * The app's long-lived objects. Always built from the *application* context, never from a screen's CreationExtras
 * (that was the مستند v1.0.0 launch crash).
 */
class Graph private constructor(val context: Context, databaseName: String) {
    val db: RaddDatabase by lazy { RaddDatabase.open(context, databaseName) }
    val roots by lazy { AndroidRoots(context) }
    val crashLog by lazy { CrashLog(context) }
    val restoreEngine by lazy { RestoreEngine(AndroidNotifier(context)) }
    val controller: ScanController by lazy {
        ScanController(db) { ScanEngine(roots, AndroidMedia(context), AndroidDecodeCheck(), AndroidFingerprinter()) }
    }

    /** `…/RaddRecovered` on the primary storage volume. */
    val recoveredDir: File
        get() {
            @Suppress("DEPRECATION")
            val primary = roots.roots().firstOrNull() ?: Environment.getExternalStorageDirectory()
            return File(primary, "RaddRecovered")
        }

    companion object {
        @Volatile private var instance: Graph? = null

        fun of(context: Context): Graph {
            val app = context.applicationContext ?: context
            instance?.let { if (it.context === app) return it }
            return synchronized(this) {
                val cur = instance
                if (cur != null && cur.context === app) cur
                else Graph(app, (app as? RaddApp)?.databaseName ?: RaddDatabase.NAME).also { instance = it }
            }
        }
    }
}

/**
 * Version of the rules that produced the saved results. When it changes (an upgrade with better rules), the saved results
 * are deleted, because they cannot be trusted any more. Runs before the database is opened.
 */
object ResultsFormat {
    const val CURRENT = 2
    private const val PREFS = "radd_state"
    private const val KEY = "results_format"

    /** Returns true when old results were deleted. */
    fun migrate(context: Context, databaseName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY, 0) == CURRENT) return false
        val deleted = try { context.deleteDatabase(databaseName) } catch (_: Exception) { false }
        prefs.edit().putInt(KEY, CURRENT).commit()
        return deleted
    }
}
