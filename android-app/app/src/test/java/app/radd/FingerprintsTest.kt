package app.radd

import app.radd.android.AndroidFingerprinter
import app.radd.engine.*
import org.junit.Assert.*
import org.junit.Test

class FingerprintsTest {
    private fun d(a: Fingerprint, b: Fingerprint) = java.lang.Long.bitCount(a.dhash xor b.dhash)
    private fun a(x: Fingerprint, y: Fingerprint) = java.lang.Long.bitCount(x.ahash xor y.ahash)
    private fun fp(bytes: ByteArray) = Fixtures.printOf(bytes) ?: error("no fingerprint for fixture")

    @Test fun theKotlinPortGivesTheSameHashAsThePythonGenerator() {
        // vector printed by tools/make_fixtures.py for the same raster
        val w = 40; val h = 30
        val gray = ByteArray(w * h) { i -> val x = i % w; val y = i / w; ((x * 7 + y * 13 + (x * y) % 31) % 256).toByte() }
        val f = Fingerprints.fromGray(gray, w, h)!!
        assertEquals(java.lang.Long.parseUnsignedLong("18370718957452458467"), f.dhash)
        assertEquals(2177756241921457400L, f.ahash)
        assertEquals(189, f.spread)
    }

    @Test fun resizedAndRecompressedCopiesAreTheSameScene() {
        for (n in listOf(1, 2, 3, 7, 8)) {
            val original = fp(Fixtures.scene(n))
            val chat = fp(Fixtures.waCopy(n))
            assertTrue("scene $n chat copy (d=${d(original, chat)}, a=${a(original, chat)})", Fingerprints.sameScene(original, chat))
        }
        for (n in listOf(1, 2, 7, 4, 9)) {
            val original = fp(Fixtures.scene(n))
            val thumb = fp(Fixtures.thumb(n))
            assertTrue("scene $n thumbnail", Fingerprints.sameScene(original, thumb, Fingerprints.THUMB_ASPECT_TOLERANCE))
        }
    }

    @Test fun differentPhotosAreNeverTheSameScene() {
        val scenes = (1..12).map { fp(Fixtures.scene(it)) }
        for (i in scenes.indices) for (j in i + 1 until scenes.size)
            assertFalse("scenes ${i + 1} and ${j + 1}", Fingerprints.sameScene(scenes[i], scenes[j], Fingerprints.THUMB_ASPECT_TOLERANCE))
        // and the margin is wide: copies are within 3 bits, different photos are at least 15 apart
        val copies = listOf(1, 2, 3, 7, 8).map { d(fp(Fixtures.scene(it)), fp(Fixtures.waCopy(it))) }
        assertTrue("copies $copies", copies.all { it <= 3 })
        val cross = scenes.indices.flatMap { i -> (i + 1 until scenes.size).map { j -> d(scenes[i], scenes[j]) } }
        assertTrue("closest different pair: ${cross.min()}", cross.min() >= 15)
    }

    @Test fun aDifferentShapeIsNotTheSameScene() {
        val original = fp(Fixtures.scene(1))
        val strip = original.copy(width = 3000, height = 400)
        assertFalse(Fingerprints.sameScene(original, strip))
    }

    @Test fun flatPicturesAreDetected() {
        assertTrue(fp(Fixtures.named("flat_400.png")).flat)
        assertFalse(fp(Fixtures.scene(5)).flat)
        val dark = Fingerprints.fromGray(ByteArray(64 * 48) { 3 }, 64, 48)!!
        assertTrue(dark.flat)
    }

    @Test fun tooSmallRastersGiveNoSignature() {
        assertNull(Fingerprints.fromGray(ByteArray(8 * 8), 8, 8))
        assertNull(Fingerprints.fromGray(ByteArray(10), 20, 20))
    }

    @Test fun exifOrientationTurnsTheRasterAndTheDimensions() {
        val w = 32; val h = 24
        val gray = ByteArray(w * h) { i -> val x = i % w; val y = i / w; (x * 8 + y * 3).toByte() }
        val engine = AndroidFingerprinter()
        val upright = engine.orient(gray, w, h, 4000, 3000, 1)!!
        assertEquals(4000, upright.width)
        // orientation 6 = the picture must be turned 90 degrees clockwise: build that raster by hand
        val turned = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) turned[x * h + (h - 1 - y)] = gray[y * w + x]
        val expected = Fingerprints.fromGray(turned, h, w, 3000, 4000)!!
        val got = engine.orient(gray, w, h, 4000, 3000, 6)!!
        assertEquals(expected, got)
        assertEquals(3000, got.width)
        assertEquals(4000, got.height)
        val half = ByteArray(w * h) { i -> gray[w * h - 1 - i] }   // orientation 3 = rotated 180 degrees
        assertEquals(Fingerprints.fromGray(half, w, h, 4000, 3000), engine.orient(gray, w, h, 4000, 3000, 3))
    }
}
