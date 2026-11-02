package app.radd

import app.radd.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiveIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun live(vararg scenes: Int, width: Int = 720, height: Int = 480, progress: (Int, Int) -> Unit = { _, _ -> }): Pair<LiveIndex, List<File>> {
        val files = scenes.map { Fixtures.write(File(tmp.root, "IMG_$it.jpg"), Fixtures.scene(it)) }
        val rows = files.map { MediaRow(it.path, it.length(), 0, width, height) }
        return LiveIndex(rows, Fixtures.fingerprinter, progress) to files
    }

    private fun scene(n: Int, seed: Int = 0, copy: (Int) -> ByteArray = { Fixtures.scene(n, seed) }) = Fixtures.printOf(copy(n))!!

    @Test fun pathsAndBytesAreRecognised() {
        val (idx, files) = live(1, 2)
        assertTrue(idx.hasPath(files[0].path))
        assertFalse(idx.hasPath("/nope"))
        val bytes = Fixtures.scene(2)
        assertTrue(idx.hasSameBytes(bytes.size.toLong(), Fixtures.sha(bytes)))
        assertFalse(idx.hasSameBytes(bytes.size.toLong(), Fixtures.sha(Fixtures.scene(2, 4))))
        assertFalse(idx.hasSameBytes(5, "x"))
    }

    @Test fun theSameSceneIsFoundThroughACopy() = runBlocking {
        val (idx, _) = live(1, 2, 3)
        assertTrue(idx.hasSameScene(scene(2, copy = Fixtures::waCopy), Fingerprints.ASPECT_TOLERANCE))
        assertTrue(idx.hasSameScene(scene(3, 99), Fingerprints.ASPECT_TOLERANCE))
        assertFalse(idx.hasSameScene(scene(4), Fingerprints.ASPECT_TOLERANCE))
        assertFalse(idx.hasSameScene(scene(12), Fingerprints.ASPECT_TOLERANCE))
    }

    @Test fun rotatedPhotosAreStillCandidatesForTheComparison() = runBlocking {
        // MediaStore reports the sensor size (720x480); the fingerprint of a portrait copy says 480x720
        val (idx, _) = live(1)
        val portrait = scene(1, copy = Fixtures::waCopy).copy(width = 320, height = 480)
        // the pixels would be rotated too, so only the pool selection is under test here: it must not throw or skip the row
        assertFalse(idx.hasSameScene(portrait, Fingerprints.ASPECT_TOLERANCE))
        assertEquals(1, idx.size)
    }

    @Test fun photosOfAnotherShapeAreNotEvenDecoded() = runBlocking {
        var decoded = 0
        val counting = Fingerprinter { f, o, l -> decoded++; Fixtures.fingerprinter.of(f, o, l) }
        val f = Fixtures.write(File(tmp.root, "wide.jpg"), Fixtures.scene(1))
        val idx = LiveIndex(listOf(MediaRow(f.path, f.length(), 0, 4000, 1000)), counting)
        assertFalse(idx.hasSameScene(scene(1), Fingerprints.ASPECT_TOLERANCE))
        assertEquals(0, decoded)
    }

    @Test fun eachGalleryPhotoIsDecodedOnceAndProgressIsReported() = runBlocking {
        var decoded = 0
        var lastDone = 0
        var lastTotal = 0
        val counting = Fingerprinter { f, o, l -> decoded++; Fixtures.fingerprinter.of(f, o, l) }
        val files = listOf(1, 2, 3, 7, 8).map { Fixtures.write(File(tmp.root, "p$it.jpg"), Fixtures.scene(it)) }
        val idx = LiveIndex(files.map { MediaRow(it.path, it.length(), 0, 720, 480) }, counting) { d, t -> lastDone = d; lastTotal = t }
        repeat(3) { idx.hasSameScene(scene(4), Fingerprints.ASPECT_TOLERANCE) }
        assertEquals(5, decoded)
        assertEquals(5, lastDone)
        assertEquals(5, lastTotal)
    }

    @Test fun anUndecodableOrMissingGalleryFileIsIgnored() = runBlocking {
        val rows = listOf(MediaRow("/gone/IMG_1.jpg", 10, 0, 720, 480), MediaRow(Fixtures.write(File(tmp.root, "bad.jpg"), Fixtures.junk(5000)).path, 5000, 0, 720, 480))
        val idx = LiveIndex(rows, Fixtures.fingerprinter)
        assertFalse(idx.hasSameScene(scene(1), Fingerprints.ASPECT_TOLERANCE))
        assertFalse(idx.hasSameBytes(10, "x"))
    }
}
