package app.radd.android

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash on disk so the next start can offer to share it. It never swallows the crash:
 * the previous handler (which ends the process) still runs. Nothing is ever sent by the app itself.
 */
class CrashLog(context: Context) {
    private val file = File(context.filesDir, "last_crash.txt")
    private val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { record(thread.name, e) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    fun record(threadName: String, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        file.writeText("Radd $version · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\nthread: $threadName\n\n${sw.toString().take(8000)}")
    }

    fun pending(): String? = try { file.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }
    fun clear() { file.delete() }
}
