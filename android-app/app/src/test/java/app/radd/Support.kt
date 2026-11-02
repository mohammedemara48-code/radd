package app.radd

import android.os.Environment
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModelProvider
import app.radd.android.Graph
import app.radd.data.FoundEntity
import kotlinx.coroutines.runBlocking
import java.io.File

val graph: Graph get() = Graph.of(org.robolectric.RuntimeEnvironment.getApplication())

/**
 * Waits until [cond] holds. Background work (Room, IO) needs real time while the main looper must keep running,
 * which Compose's own waitUntil does not give under Robolectric.
 */
fun ComposeTestRule.waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        waitForIdle()
        if (cond()) return
        if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for: $what")
        Thread.sleep(25)
    }
}

fun ComposeTestRule.hasText(text: String): Boolean =
    try { onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() } catch (_: Throwable) { false }

inline fun <reified VM : androidx.lifecycle.ViewModel> vmOf(activity: androidx.activity.ComponentActivity): VM = ViewModelProvider(activity)[VM::class.java]

/**
 * lifecycle caches the first Application it sees in a static AndroidViewModelFactory. The real app has exactly one
 * Application, but every Robolectric test has its own, so the cache is cleared before each test.
 */
object ResetViewModelCaches : org.junit.rules.TestWatcher() {
    override fun starting(description: org.junit.runner.Description?) {
        val cls = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java
        cls.declaredFields.filter { it.type == cls && java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { it.isAccessible = true; it.set(null, null) }
    }
}

/** Writes real JPEGs to the (Robolectric) shared storage and stores them as scan results, like a finished scan would. */
fun seedResults(count: Int, dir: File = File(Environment.getExternalStorageDirectory(), "seed")): List<FoundEntity> {
    val rows = (1..count).map { i ->
        val bytes = Fixtures.jpeg(i)
        val f = Fixtures.write(File(dir, "p$i.jpg"), bytes)
        FoundEntity(
            source = app.radd.model.Source.entries[i % 7].name, quality = if (i % 5 == 0) "PARTIAL" else "INTACT", path = f.path, offset = 0,
            length = bytes.size.toLong(), modifiedMs = 1_760_000_000_000L + i * 86_400_000L, width = 160, height = 120, format = "jpg",
            sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, name = "photo-$i.jpg",
        )
    }
    runBlocking { graph.db.found().insertAll(rows) }
    return rows
}
