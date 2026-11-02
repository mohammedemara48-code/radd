package app.radd

import app.radd.engine.*
import app.radd.model.Quality
import app.radd.model.Source
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the real engine on a synthetic phone: every source, the things that must be ignored, and the old prototype's limits. */
class ScanEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File

    private val trashJpg = Fixtures.jpeg(101, 200, 150)
    private val visibleJpg = Fixtures.jpeg(102, 200, 150)

    @Before fun tree() {
        root = tmp.newFolder("storage")
        fun f(path: String, vararg parts: ByteArray) = Fixtures.write(File(root, path), *parts)
        f("DCIM/Camera/IMG_visible.jpg", visibleJpg)
        f("DCIM/Camera/.trashed-1763900000-IMG_trash.jpg", trashJpg)
        f("LOST.DIR/#1234", Fixtures.jpeg(103, 200, 150))
        f("DCIM/.thumbnails/.thumbdata3--1", Fixtures.jpeg(104, 100, 80), Fixtures.junk(300, 1), Fixtures.jpeg(105, 100, 80), Fixtures.junk(300, 2), Fixtures.jpeg(106, 100, 80))
        f("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1-WA0001.jpg", Fixtures.jpeg(107, 200, 150))
        f("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/status1.jpg", Fixtures.jpeg(108, 200, 150))
        f("Android/media/org.telegram.messenger/Telegram/Telegram Images/55_1.jpg", Fixtures.jpeg(109, 200, 150))
        f("Pictures/.private/.x.jpg", Fixtures.jpeg(110, 200, 150))
        f("Download/.cache/blob.bin", Fixtures.junk(6000, 3), Fixtures.jpeg(111, 200, 150), Fixtures.junk(2000, 4), Fixtures.png(112, 100, 100), Fixtures.junk(500, 5))
        f("Android/data/com.some.app/files/secret.jpg", Fixtures.jpeg(113, 200, 150)) // never readable on a real phone: must be skipped
        f("RaddRecovered/old.jpg", Fixtures.jpeg(114, 200, 150))                     // our own output is never scanned again
        f("Documents/readme.txt", "hello".toByteArray())
    }

    private fun run(media: MediaGateway = NoMedia, decode: DecodeCheck = DecodeCheck { _, _, _, _ -> true }) =
        runBlocking { ScanEngine(StorageRoots { listOf(root) }, media, decode).run().toList() }

    private fun List<ScanEvent>.found() = filterIsInstance<ScanEvent.FoundOne>().map { it.item }

    @Test fun everySourceIsScannedAndNothingElse() {
        val found = run().found()
        val bySource = found.groupBy { it.source }.mapValues { it.value.size }
        assertEquals(1, bySource[Source.TRASH])
        assertEquals(1, bySource[Source.LOST_DIR])
        assertEquals(3, bySource[Source.THUMBNAILS])
        assertEquals(2, bySource[Source.WHATSAPP])
        assertEquals(1, bySource[Source.TELEGRAM])
        assertEquals(1, bySource[Source.HIDDEN])
        assertEquals(2, bySource[Source.CARVED])
        assertEquals(11, found.size)
        assertTrue(found.none { "Android/data" in it.path || "RaddRecovered" in it.path || it.path.endsWith("IMG_visible.jpg") })
        assertTrue(found.all { it.quality == Quality.INTACT })
    }

    @Test fun eventsComeInStageOrderWithRealProgress() {
        val events = run()
        val starts = events.filterIsInstance<ScanEvent.StageStarted>().map { it.source }
        assertEquals(STAGES, starts)
        assertEquals(STAGES, events.filterIsInstance<ScanEvent.StageDone>().map { it.source })
        val finished = events.last() as ScanEvent.Finished
        assertEquals(11, finished.found)
        val progress = events.filterIsInstance<ScanEvent.Progress>()
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.last().totalBytes, progress.last().doneBytes)
        assertTrue("progress never goes backwards", progress.zipWithNext().all { (a, b) -> b.doneBytes >= a.doneBytes })
    }

    @Test fun trashedFilesKeepTheirOriginalNameAndDate() {
        val item = run().found().single { it.source == Source.TRASH }
        assertEquals("IMG_trash.jpg", item.name)
        assertEquals(trashJpg.size.toLong(), item.length)
        assertEquals(File(root, "DCIM/Camera/.trashed-1763900000-IMG_trash.jpg").lastModified(), item.modifiedMs)
    }

    @Test fun carvedImagesGetNeutralUniqueNamesAndExactRanges() {
        val carved = run().found().filter { it.source == Source.CARVED }
        assertEquals(setOf("jpg", "png"), carved.map { it.format }.toSet())
        assertEquals(2, carved.map { it.name }.toSet().size)
        assertTrue(carved.all { it.name.startsWith("radd-carved-") })
        assertEquals(6000L, carved.single { it.format == "jpg" }.offset)
    }

    @Test fun theSamePictureIsListedOnce() {
        // a copy of the trashed photo sits in a cache blob and in a thumbnail blob
        Fixtures.write(File(root, "Download/.cache/copy.bin"), Fixtures.junk(900, 6), trashJpg)
        Fixtures.write(File(root, "DCIM/.thumbnails/.thumbdata4--2"), trashJpg)
        val found = run().found()
        assertEquals(1, found.count { it.sha256 == found.single { f -> f.source == Source.TRASH }.sha256 })
        assertEquals(11, found.size)
    }

    @Test fun photosTheGalleryStillShowsAreNotOffered() {
        val gallery = object : MediaGateway {
            override fun trashed() = MediaQuery(emptyList())
            // same bytes as a hidden-folder copy, found by size+hash although the path differs
            override fun visible() = MediaQuery(listOf(MediaRow(File(root, "DCIM/Camera/IMG_visible.jpg").path, visibleJpg.size.toLong(), 0)))
        }
        Fixtures.write(File(root, "Pictures/.private/copy_of_visible.jpg"), visibleJpg)
        val events = run(gallery)
        assertTrue(events.found().none { it.name.contains("copy_of_visible") })
        assertEquals(1, (events.last() as ScanEvent.Finished).stillVisible)
    }

    @Test fun mediaStoreTrashRowsAreScannedEvenOutsideKnownFolders() {
        val odd = Fixtures.write(File(root, "weird/place/lost-in-trash.bin"), Fixtures.jpeg(120, 200, 150))
        val gateway = object : MediaGateway {
            override fun trashed() = MediaQuery(listOf(MediaRow(odd.path, odd.length(), 0)))
            override fun visible() = MediaQuery(emptyList())
        }
        val item = run(gateway).found().single { it.path == odd.path }
        assertEquals(Source.TRASH, item.source)
    }

    @Test fun aBrokenMediaStoreDoesNotStopTheScan() {
        val broken = object : MediaGateway {
            override fun trashed() = MediaQuery(emptyList(), "SecurityException")
            override fun visible() = MediaQuery(emptyList(), "SecurityException")
        }
        val events = run(broken)
        assertEquals(11, events.found().size)
        assertEquals(2, (events.last() as ScanEvent.Finished).warnings.size)
    }

    @Test fun thereIsNoFileCountOrHitCap() {
        // the old prototype stopped after 8,000 files and 400 hits
        val dir = File(root, "Download/.cache/many").also { it.mkdirs() }
        repeat(9_000) { File(dir, "f$it.txt").writeText("x") }
        repeat(450) { i -> Fixtures.write(File(dir, "img$i.bin"), Fixtures.jpeg(1000 + i, 100, 80)) }
        Fixtures.write(File(root, "zzz/.hidden/last.jpg"), Fixtures.jpeg(9999, 200, 150))
        val events = run()
        val found = events.found()
        assertTrue("found ${found.size}", found.size >= 11 + 450 + 1)
        assertTrue(found.any { it.path.endsWith("last.jpg") })
        assertTrue((events.last() as ScanEvent.Finished).visited > 9_000)
    }

    @Test fun partialAndDecodeRejectedCandidates() {
        val cut = trashJpg.copyOf(trashJpg.size / 2 + 4000)
        Fixtures.write(File(root, "Pictures/.private/cut.jpg"), Fixtures.jpeg(130, 400, 300).let { it.copyOf(it.size * 2 / 3) })
        val found = run().found()
        assertTrue(found.any { it.path.endsWith("cut.jpg") && it.quality == Quality.PARTIAL })
        // a platform decoder that refuses everything hides everything but is not an error
        assertTrue(run(decode = DecodeCheck { _, _, _, _ -> false }).found().isEmpty())
        assertNotNull(cut)
    }

    @Test fun unreadableFilesAreCountedNotFatal() {
        val bad = Fixtures.write(File(root, "Pictures/.private/locked.jpg"), Fixtures.jpeg(140, 200, 150))
        val canRead = bad.setReadable(false)
        val finished = run().last() as ScanEvent.Finished
        bad.setReadable(true)
        if (canRead && !System.getProperty("user.name").equals("root")) assertTrue(finished.unreadable >= 1)
        assertTrue(finished.found >= 11)
    }

    @Test fun cancellingStopsTheScanPromptly() {
        val dir = File(root, "Download/.cache/many").also { it.mkdirs() }
        repeat(300) { i -> Fixtures.write(File(dir, "img$i.bin"), Fixtures.jpeg(2000 + i, 100, 80)) }
        val first = runBlocking { ScanEngine(StorageRoots { listOf(root) }).run().take(3).toList() }
        assertEquals(3, first.size)
    }

    @Test fun missingStorageIsAFailureNotAnEmptyResult() {
        try {
            runBlocking { ScanEngine(StorageRoots { listOf(File(root, "nope")) }).run().toList() }
            fail("expected ScanFailure")
        } catch (e: ScanFailure) {
            assertEquals(ScanFailure.Reason.NO_STORAGE, e.reason)
        }
    }

    @Test fun anEmptyPhoneFindsNothingAndSaysSo() {
        val empty = tmp.newFolder("empty")
        val events = runBlocking { ScanEngine(StorageRoots { listOf(empty) }).run().toList() }
        assertTrue(events.found().isEmpty())
        assertEquals(0, (events.last() as ScanEvent.Finished).found)
    }

    @Test fun cleanNameStripsTheTrashPrefix() {
        assertEquals("IMG_1.jpg", ScanEngine.cleanName(".trashed-1763900000-IMG_1.jpg"))
        assertEquals("IMG_1.jpg", ScanEngine.cleanName(".pending-1-IMG_1.jpg"))
        assertEquals("x.jpg", ScanEngine.cleanName(".x.jpg"))
        assertEquals("a-b.jpg", ScanEngine.cleanName("a-b.jpg"))
    }
}
