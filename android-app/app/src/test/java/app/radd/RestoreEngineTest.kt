package app.radd

import app.radd.engine.*
import app.radd.model.Quality
import app.radd.model.RecoverableItem
import app.radd.model.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class RestoreEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dest: File
    private val notified = ArrayList<File>()
    private val engine get() = RestoreEngine { notified += it }

    @Before fun setUp() { dest = File(tmp.root, "RaddRecovered") }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun item(name: String, file: File, bytes: ByteArray, offset: Long = 0, mtime: Long = 1_700_000_000_000L, sha: String = sha(bytes)) =
        RecoverableItem(
            id = name.hashCode().toLong(), name = name, source = Source.TRASH, quality = Quality.INTACT, sizeBytes = bytes.size.toLong(),
            modifiedMs = mtime, width = 1, height = 1, originPath = file.path, offset = offset, length = bytes.size.toLong(), sha256 = sha,
        )

    private fun source(name: String, vararg parts: ByteArray) = Fixtures.write(File(tmp.root, "src/$name"), *parts)

    @Test fun copiesKeepsDateAndNotifiesTheGallery() {
        val j = Fixtures.jpeg(1)
        val src = source("a.bin", j)
        val r = runBlocking { engine.restore(listOf(item("a.jpg", src, j)), dest) }
        assertEquals(1, r.saved.size)
        assertArrayEquals(j, File(dest, "a.jpg").readBytes())
        assertEquals(1_700_000_000_000L, File(dest, "a.jpg").lastModified())
        assertEquals(listOf(File(dest, "a.jpg")), notified)
        assertEquals(dest.path, r.folderPath)
    }

    @Test fun neverOverwritesAnExistingFile() {
        dest.mkdirs()
        val precious = File(dest, "a.jpg").also { it.writeText("PRECIOUS") }
        val j = Fixtures.jpeg(2)
        val r = runBlocking { engine.restore(listOf(item("a.jpg", source("a.bin", j), j)), dest) }
        assertEquals("PRECIOUS", precious.readText())
        assertArrayEquals(j, File(dest, "a-2.jpg").readBytes())
        assertEquals(1, r.renamed)
    }

    @Test fun twoItemsWithTheSameNameGetNumbers() {
        val a = Fixtures.jpeg(3); val b = Fixtures.jpeg(4)
        val r = runBlocking { engine.restore(listOf(item("p.jpg", source("1.bin", a), a), item("p.jpg", source("2.bin", b), b)), dest) }
        assertEquals(2, r.saved.size); assertEquals(1, r.renamed)
        assertEquals(setOf("p.jpg", "p-2.jpg"), dest.list().orEmpty().toSet())
    }

    @Test fun copiesJustTheCarvedRangeOutOfABlob() {
        val j = Fixtures.jpeg(5)
        val src = source("blob.bin", Fixtures.junk(7000), j, Fixtures.junk(900, 2))
        val r = runBlocking { engine.restore(listOf(item("radd-carved-1.jpg", src, j, offset = 7000)), dest) }
        assertEquals(1, r.saved.size)
        assertArrayEquals(j, File(dest, "radd-carved-1.jpg").readBytes())
    }

    @Test fun identicalContentIsSkippedAsADuplicate() {
        val j = Fixtures.jpeg(6)
        val first = runBlocking { engine.restore(listOf(item("one.jpg", source("1.bin", j), j)), dest) }
        val second = runBlocking { engine.restore(listOf(item("two.jpg", source("2.bin", j), j)), dest) }
        assertEquals(1, first.saved.size)
        assertEquals(0, second.saved.size); assertEquals(1, second.skippedDuplicates)
        assertEquals(listOf("one.jpg"), dest.list().orEmpty().toList())
    }

    @Test fun aSourceThatChangedIsAFailureAndLeavesNoPartialFile() {
        val j = Fixtures.jpeg(7)
        val src = source("c.bin", j)
        val wrong = item("c.jpg", src, j, sha = sha(byteArrayOf(1, 2, 3))) // content does not match what the scan saw
        val r = runBlocking { engine.restore(listOf(wrong), dest) }
        assertEquals(1, r.failed); assertEquals(0, r.saved.size)
        assertFalse(File(dest, "c.jpg").exists())
        assertTrue(notified.isEmpty())
    }

    @Test fun aVanishedOrShortSourceIsAFailureAndTheRestStillWorks() {
        val j = Fixtures.jpeg(8)
        val gone = item("gone.jpg", File(tmp.root, "src/missing.bin"), j)
        val short = source("short.bin", j.copyOf(100))
        val shortItem = item("short.jpg", short, j)
        val ok = item("ok.jpg", source("ok.bin", j), j)
        val r = runBlocking { engine.restore(listOf(gone, shortItem, ok), dest) }
        assertEquals(2, r.failed); assertEquals(listOf("ok.jpg"), r.saved.map { it.name })
        assertEquals(listOf("ok.jpg"), dest.list().orEmpty().toList())
    }

    @Test fun hostileNamesCannotEscapeTheFolder() {
        val j = Fixtures.jpeg(9)
        runBlocking { engine.restore(listOf(item("../../evil.jpg", source("e.bin", j), j)), dest) }
        assertFalse(File(tmp.root, "evil.jpg").exists())
        assertEquals(1, dest.list().orEmpty().size)
    }

    @Test fun anUnwritableDestinationIsReportedNotSwallowed() {
        val blocker = File(tmp.root, "file-not-dir").also { it.writeText("x") }
        try {
            runBlocking { engine.restore(emptyList(), File(blocker, "sub")) }
            fail("expected RestoreFailure")
        } catch (e: RestoreFailure) { /* expected */ }
    }

    @Test fun progressIsReportedPerItem() {
        val j = Fixtures.jpeg(10); val k = Fixtures.jpeg(11)
        val seen = ArrayList<Pair<Int, Int>>()
        runBlocking { engine.restore(listOf(item("a.jpg", source("1.bin", j), j), item("b.jpg", source("2.bin", k), k), ), dest) { d, t -> seen += d to t } }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }
}
