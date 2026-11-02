package app.radd

import app.radd.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExifTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun hasTags(bytes: ByteArray, prefix: Int = 0): Boolean {
        val f = Fixtures.write(tmp.newFile(), ByteArray(prefix) { 0x11 }, bytes)
        return FileSource(f).use { s ->
            val r = Carvers.parseAt(s, prefix.toLong()) ?: return false
            Exif.hasCameraTags(s, r)
        }
    }

    @Test fun cameraPhotosHaveTags() {
        assertTrue(hasTags(Fixtures.scene(1)))
        assertTrue("also when the JPEG sits inside a container", hasTags(Fixtures.scene(2), prefix = 777))
        assertTrue("a comment segment before EXIF does not hide it", hasTags(Fixtures.scene(3, seed = 5)))
    }

    @Test fun downloadsAndAssetsHaveNone() {
        assertFalse(hasTags(Fixtures.scene(11)))
        assertFalse(hasTags(Fixtures.named("big_noexif.jpg")))
        assertFalse(hasTags(Fixtures.waCopy(1)))
        assertFalse(hasTags(Fixtures.named("icon_96.png")))
    }

    @Test fun brokenExifNeverThrows() {
        val good = Fixtures.scene(1)
        for (cut in listOf(30, 60, 120, 400)) assertFalse(hasTags(good.copyOf(cut)))
        val mangled = good.copyOf()
        for (i in 20 until 200) mangled[i] = (mangled[i].toInt() xor 0x5a).toByte()
        hasTags(mangled) // any answer, no exception
    }
}
