package app.radd

import app.radd.engine.Carvers
import app.radd.engine.FileSource
import app.radd.engine.Region
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CarversTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun carve(file: File, deep: Boolean = true): List<Region> = FileSource(file).use { Carvers.carve(it, deep) }
    private fun file(vararg parts: ByteArray) = Fixtures.write(File(tmp.root, "f${System.nanoTime()}.bin"), *parts)

    @Test fun intactJpegIsComplete() {
        val j = Fixtures.jpeg(1)
        val r = carve(file(j)).single()
        assertEquals("jpg", r.format); assertTrue(r.complete)
        assertEquals(0L, r.start); assertEquals(j.size.toLong(), r.end)
        assertEquals(160, r.width); assertEquals(120, r.height)
    }

    @Test fun jpegInsideABlobHasExactBounds() {
        val j = Fixtures.jpeg(2)
        val pre = Fixtures.junk(5000, 1); val post = Fixtures.junk(3000, 2)
        val r = carve(file(pre, j, post)).single()
        assertEquals(5000L, r.start); assertEquals(5000L + j.size, r.end); assertTrue(r.complete)
    }

    @Test fun trailingGarbageIsNotPartOfTheImage() {
        val j = Fixtures.jpeg(3)
        val r = carve(file(j, Fixtures.junk(4000, 3)), deep = false).single()
        assertEquals(j.size.toLong(), r.end)
    }

    @Test fun truncatedJpegIsPartialAndEndsAtTheFileEnd() {
        val j = Fixtures.jpeg(4)
        val cut = j.copyOf(j.size * 2 / 3)
        val r = carve(file(cut)).single()
        assertFalse(r.complete); assertEquals(cut.size.toLong(), r.end)
    }

    @Test fun jpegCutOffBeforeTheScanDataIsNotAnImage() {
        val j = Fixtures.jpeg(5)
        assertTrue(carve(file(j.copyOf(150))).isEmpty())
    }

    @Test fun twoNeighbouringImagesAreSeparated() {
        val a = Fixtures.jpeg(6); val b = Fixtures.png(7)
        val rs = carve(file(a, b))
        assertEquals(listOf("jpg", "png"), rs.map { it.format })
        assertEquals(a.size.toLong(), rs[1].start)
    }

    @Test fun anImageCutByTheNextOneKeepsOnlyItsOwnBytes() {
        val a = Fixtures.jpeg(8); val b = Fixtures.jpeg(9)
        val cutA = a.copyOf(a.size / 2)
        val rs = carve(file(cutA, b))
        assertEquals(2, rs.size)
        assertFalse(rs[0].complete); assertEquals(cutA.size.toLong(), rs[0].end)
        assertTrue(rs[1].complete); assertEquals(cutA.size.toLong(), rs[1].start)
    }

    @Test fun pngIsVerifiedChunkByChunk() {
        val p = Fixtures.png(10)
        val r = carve(file(Fixtures.junk(100), p)).single()
        assertTrue(r.complete); assertEquals(64, r.width); assertEquals(48, r.height)
        assertEquals(100L + p.size, r.end)
    }

    @Test fun pngWithADamagedTailIsPartialAndStopsBeforeTheBadChunk() {
        val p = Fixtures.png(11, 120, 120)
        val bad = p.copyOf(); bad[p.size - 1] = (bad[p.size - 1] + 1).toByte() // IEND's CRC no longer matches
        val r = carve(file(bad)).single()
        assertFalse(r.complete); assertEquals((p.size - 12).toLong(), r.end)
    }

    @Test fun pngWhoseOnlyDataChunkIsCorruptIsRejected() {
        val p = Fixtures.png(18, 120, 120)
        val bad = p.copyOf(); bad[p.size / 2] = (bad[p.size / 2] + 1).toByte()
        assertTrue(carve(file(bad)).isEmpty())
    }

    @Test fun webpAndHeicAreRecognisedWithTheirSizes() {
        val w = Fixtures.webp(12); val h = Fixtures.heic(13); val av = Fixtures.heic(14, brand = "avif")
        val rs = carve(file(Fixtures.junk(200), w, Fixtures.junk(200, 4), h, Fixtures.junk(50, 5), av))
        assertEquals(listOf("webp", "heic", "avif"), rs.map { it.format })
        assertTrue(rs.all { it.complete })
        assertEquals(w.size.toLong(), rs[0].length); assertEquals(h.size.toLong(), rs[1].length)
        assertEquals(300, rs[0].width); assertEquals(200, rs[0].height)
        assertEquals(1024, rs[1].width); assertEquals(768, rs[1].height)
    }

    @Test fun randomDataAndFakeSignaturesYieldNothing() {
        val fake = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00, 0x05, 0x01, 0x02)
        assertTrue(carve(file(Fixtures.junk(300_000), fake, Fixtures.junk(1000))).isEmpty())
    }

    @Test fun nonDeepOnlyLooksAtTheStartOfTheFile() {
        val j = Fixtures.jpeg(15)
        assertTrue(carve(file(Fixtures.junk(500), j), deep = false).isEmpty())
        assertEquals(1, carve(file(Fixtures.junk(500), j), deep = true).size)
    }

    @Test fun anImageThatStraddlesTheStreamingWindowIsStillFound() {
        val j = Fixtures.jpeg(16, 400, 300)
        // the window is 1 MiB: start the image a few bytes before the boundary
        val r = carve(file(Fixtures.junk((1 shl 20) - 40, 8), j, Fixtures.junk(100, 9))).single()
        assertEquals(((1 shl 20) - 40).toLong(), r.start); assertTrue(r.complete)
    }

    @Test fun aHugeFileIsStreamedNotLoaded() {
        val f = File(tmp.root, "huge.bin")
        f.outputStream().use { o -> val z = ByteArray(1 shl 20); repeat(150) { o.write(z) }; o.write(Fixtures.jpeg(17)) }
        val t = System.currentTimeMillis()
        val r = carve(f).single()
        assertEquals(150L * (1 shl 20), r.start)
        assertTrue("took ${System.currentTimeMillis() - t} ms", System.currentTimeMillis() - t < 20_000)
    }
}
