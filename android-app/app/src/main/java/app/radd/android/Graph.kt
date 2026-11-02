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
        ScanController(db) { ScanEngine(roots, AndroidMedia(context), AndroidDecodeCheck()) }
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
