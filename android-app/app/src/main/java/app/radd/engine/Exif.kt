package app.radd.engine

/** Reads just enough EXIF to tell a camera photo from a downloaded or generated picture. */
object Exif {
    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_DATE_ORIGINAL = 0x9003

    /** True when the JPEG in [region] carries a camera Make, a Model or a DateTimeOriginal. Never throws. */
    fun hasCameraTags(s: ByteSource, region: Region): Boolean {
        if (region.format != "jpg") return false
        return try { scan(s, region) } catch (_: Exception) { false }
    }

    private fun scan(s: ByteSource, region: Region): Boolean {
        var i = region.start + 2
        var guard = 0
        while (i + 4 < region.end && guard++ < 64) {
            if (s.u8(i) != 0xff) return false
            val marker = s.u8(i + 1)
            if (marker == 0xff) { i++; continue }
            if (marker == 0xda || marker == 0xd9 || marker < 0) return false
            val len = s.u16(i + 2)
            if (len < 2) return false
            if (marker == 0xe1 && s.matches(i + 4, "Exif") && s.u16(i + 8) == 0) return tiff(s, i + 10, i + 2 + len)
            i += 2 + len
        }
        return false
    }

    private class Reader(val s: ByteSource, val base: Long, val limit: Long, val little: Boolean) {
        fun u16(pos: Long) = if (little) s.u16le(base + pos) else s.u16(base + pos)
        fun u32(pos: Long) = if (little) s.u32le(base + pos) else s.u32(base + pos)
        fun inside(pos: Long) = pos >= 8 && base + pos + 2 <= limit
    }

    private fun tiff(s: ByteSource, base: Long, limit: Long): Boolean {
        val order = s.u16(base)
        val little = when (order) { 0x4949 -> true; 0x4d4d -> false; else -> return false }
        val r = Reader(s, base, limit, little)
        if (r.u16(2) != 42) return false
        val ifd0 = r.u32(4)
        var exifIfd = -1L
        var found = false
        entries(r, ifd0) { tag, _, count, value ->
            when (tag) {
                TAG_MAKE, TAG_MODEL -> if (count > 2) found = true   // ASCII text longer than "x\0"
                TAG_EXIF_IFD -> exifIfd = value
            }
        }
        if (found) return true
        if (exifIfd > 0) entries(r, exifIfd) { tag, _, count, _ -> if (tag == TAG_DATE_ORIGINAL && count > 2) found = true }
        return found
    }

    private fun entries(r: Reader, at: Long, f: (tag: Int, type: Int, count: Long, value: Long) -> Unit) {
        if (!r.inside(at)) return
        val n = r.u16(at)
        if (n <= 0 || n > 300) return
        for (k in 0 until n) {
            val e = at + 2 + k * 12L
            if (r.base + e + 12 > r.limit) return
            f(r.u16(e), r.u16(e + 2), r.u32(e + 4), r.u32(e + 8))
        }
    }
}
