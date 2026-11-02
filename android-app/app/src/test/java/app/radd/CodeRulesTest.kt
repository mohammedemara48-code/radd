package app.radd

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lessons from مستند, enforced on the source itself so they cannot quietly come back. */
class CodeRulesTest {
    private val main = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }
    private val sources = main.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun codeOf(f: File) = f.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }

    @Test fun thereAreSources() { assertTrue(sources.size > 20) }

    @Test fun noNonNullAssertionsAnywhere() {
        val bad = sources.flatMap { f -> codeOf(f).filter { it.contains("!!") && !it.contains("\"") }.map { "${f.name}: ${it.trim()}" } }
        assertTrue("`!!` found:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theApplicationIsNeverReadFromCreationExtras() {
        val bad = sources.filter { f -> codeOf(f).any { it.contains("APPLICATION_KEY") } }.map { it.name }
        assertTrue("APPLICATION_KEY used in $bad", bad.isEmpty())
    }

    @Test fun theManifestHasNoInternetPermission() {
        val manifest = File(main, "../AndroidManifest.xml").readText()
        val permissions = manifest.lines().filter { it.contains("<uses-permission") }
        assertTrue(permissions.none { it.contains("INTERNET") })
        assertTrue(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }
}
