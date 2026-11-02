package app.radd.engine

import java.util.zip.CRC32

/** One image found inside a file: bytes [start, end) of the container. */
data class Region(
    val format: String,        // jpg | png | webp | heic | avif
    val start: Long,
    val end: Long,
    val complete: Boolean,
    val width: Int,
    val height: Int,
) {
    val length: Long get() = end - start
}

/**
 * Structure-validating parsers. A region is only returned when the header is real (JPEG needs SOF+SOS, PNG needs IHDR+IDAT with
 * correct CRCs, …); damage ends the region at the last good byte and marks it partial instead of dragging trailing junk along.
 */
object Carvers {
    private const val CHUNK = 1 shl 20

    fun parseAt(s: ByteSource, p: Long): Region? = jpeg(s, p) ?: png(s, p) ?: webp(s, p) ?: isoImage(s, p)

    /**
     * Streams through [s] in 1 MiB windows (never loading the file), trying every signature. Without [deep], only offset 0 is tried.
     * [onProgress] receives bytes scanned so far; [cancelled] is polled once per window.
     */
    fun carve(s: ByteSource, deep: Boolean, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): List<Region> {
        if (!deep) return listOfNotNull(parseAt(s, 0))
        val out = ArrayList<Region>()
        val buf = ByteArray(CHUNK + 16)
        var pos = 0L
        while (pos < s.length) {
            if (cancelled()) break
            val n = s.read(pos, buf, 0, buf.size)
            if (n <= 0) break
            val limit = if (n == buf.size) CHUNK else n
            var jumped = false
            var i = 0
            while (i < limit) {
                if (looksLikeStart(buf, i, n)) {
                    val r = parseAt(s, pos + i)
                    if (r != null && r.end > pos + i) {
                        out += r
                        pos = r.end
                        jumped = true
                        break
                    }
                }
                i++
            }
            if (!jumped) pos += limit
            onProgress(minOf(pos, s.length))
        }
        return out
    }

    private fun looksLikeStart(b: ByteArray, i: Int, n: Int): Boolean {
        val b0 = b[i].toInt() and 0xff
        return when (b0) {
            0xff -> i + 2 < n && (b[i + 1].toInt() and 0xff) == 0xd8 && (b[i + 2].toInt() and 0xff) == 0xff
            0x89 -> i + 3 < n && b[i + 1].toInt() == 0x50 && b[i + 2].toInt() == 0x4e && b[i + 3].toInt() == 0x47
            0x52 -> i + 11 < n && b[i + 1].toInt() == 0x49 && b[i + 2].toInt() == 0x46 && b[i + 3].toInt() == 0x46 &&
                b[i + 8].toInt() == 0x57 && b[i + 9].toInt() == 0x45
            else -> i + 11 < n && b[i + 4].toInt() == 0x66 && b[i + 5].toInt() == 0x74 && b[i + 6].toInt() == 0x79 && b[i + 7].toInt() == 0x70
        }
    }

    // ---- JPEG -----------------------------------------------------------------------------------------------------

    private fun isSof(m: Int) = m in 0xc0..0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc

    private fun validJpegMarker(m: Int) = m in 0xc0..0xcf && m != 0xc8 || m in 0xda..0xdf || m in 0xe0..0xfe

    fun jpeg(s: ByteSource, p: Long): Region? {
        if (s.u8(p) != 0xff || s.u8(p + 1) != 0xd8 || s.u8(p + 2) != 0xff) return null
        var i = p + 2
        var w = 0
        var h = 0
        var sawSof = false
        var sawSos = false
        fun partial(end: Long): Region? = if (sawSof && sawSos && w > 0 && h > 0 && end > p) Region("jpg", p, end, false, w, h) else null
        while (true) {
            val first = s.u8(i)
            if (first < 0) return partial(s.length)
            if (first != 0xff) return partial(i)
            val markerAt = i
            while (s.u8(i) == 0xff) i++
            val m = s.u8(i)
            if (m < 0) return partial(s.length)
            i++
            if (m == 0xd9) return if (sawSof && sawSos && w > 0 && h > 0) Region("jpg", p, i, true, w, h) else null
            if (!validJpegMarker(m)) return partial(markerAt)
            val len = s.u16(i)
            if (len < 2) return partial(markerAt)
            if (i + len > s.length) return partial(s.length)
            if (isSof(m)) {
                sawSof = true
                h = s.u16(i + 3)
                w = s.u16(i + 5)
                if (w <= 0 || h <= 0) return null
            }
            i += len
            if (m == 0xda) {
                sawSos = true
                // entropy-coded data: runs until a marker that is not a stuffed byte or a restart marker
                while (true) {
                    val b = s.u8(i)
                    if (b < 0) return partial(s.length)
                    if (b != 0xff) { i++; continue }
                    val n = s.u8(i + 1)
                    if (n < 0) return partial(s.length)
                    if (n == 0x00 || n in 0xd0..0xd7) { i += 2; continue }
                    if (n == 0xff) { i++; continue }
                    break
                }
            }
        }
    }

    // ---- PNG ------------------------------------------------------------------------------------------------------

    private val PNG_SIG = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun png(s: ByteSource, p: Long): Region? {
        for (k in PNG_SIG.indices) if (s.u8(p + k) != PNG_SIG[k]) return null
        var i = p + 8
        var w = 0
        var h = 0
        var sawIdat = false
        var first = true
        val crc = CRC32()
        val buf = ByteArray(8192)
        fun partial(end: Long): Region? = if (sawIdat && w > 0 && h > 0) Region("png", p, end, false, w, h) else null
        while (true) {
            val len = s.u32(i)
            if (len < 0) return partial(minOf(i, s.length))
            val typeAt = i + 4
            for (k in 0 until 4) {
                val c = s.u8(typeAt + k)
                if (c < 0) return partial(i)
                if (!(c in 0x41..0x5a || c in 0x61..0x7a)) return partial(i)
            }
            val end = i + 12 + len
            if (first) {
                if (!s.matches(typeAt, "IHDR") || len != 13L) return null
                w = s.u32(i + 8).toInt()
                h = s.u32(i + 12).toInt()
                if (w <= 0 || h <= 0) return null
                first = false
            }
            if (end > s.length) return partial(i)
            // CRC covers type + data
            crc.reset()
            var at = typeAt
            var left = len + 4
            while (left > 0) {
                val n = s.read(at, buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (n <= 0) return partial(i)
                crc.update(buf, 0, n)
                at += n
                left -= n
            }
            if (crc.value != s.u32(i + 8 + len)) return partial(i)
            if (s.matches(typeAt, "IDAT")) sawIdat = true
            i = end
            if (s.matches(typeAt, "IEND")) return if (sawIdat) Region("png", p, i, true, w, h) else null
        }
    }

    // ---- WebP -----------------------------------------------------------------------------------------------------

    fun webp(s: ByteSource, p: Long): Region? {
        if (!s.matches(p, "RIFF") || !s.matches(p + 8, "WEBP")) return null
        val size = s.u32le(p + 4)
        if (size < 12) return null
        val kind = when {
            s.matches(p + 12, "VP8 ") -> 0
            s.matches(p + 12, "VP8L") -> 1
            s.matches(p + 12, "VP8X") -> 2
            else -> return null
        }
        var w = 0
        var h = 0
        when (kind) {
            0 -> { w = s.u16le(p + 26) and 0x3fff; h = s.u16le(p + 28) and 0x3fff }
            1 -> {
                if (s.u8(p + 20) != 0x2f) return null
                val b0 = s.u8(p + 21); val b1 = s.u8(p + 22); val b2 = s.u8(p + 23); val b3 = s.u8(p + 24)
                if (b0 < 0 || b3 < 0) return null
                w = (b0 or ((b1 and 0x3f) shl 8)) + 1
                h = ((b1 shr 6) or (b2 shl 2) or ((b3 and 0x0f) shl 10)) + 1
            }
            else -> {
                w = (s.u8(p + 24) or (s.u8(p + 25) shl 8) or (s.u8(p + 26) shl 16)) + 1
                h = (s.u8(p + 27) or (s.u8(p + 28) shl 8) or (s.u8(p + 29) shl 16)) + 1
            }
        }
        if (w <= 0 || h <= 0) return null
        val end = p + 8 + size
        return if (end > s.length) Region("webp", p, s.length, false, w, h) else Region("webp", p, end, true, w, h)
    }

    // ---- HEIC / AVIF (ISO base media) -----------------------------------------------------------------------------

    private val HEIC_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")
    private val ISO_TOP = setOf("ftyp", "meta", "mdat", "free", "skip", "uuid", "idat", "pdin")

    fun isoImage(s: ByteSource, p: Long): Region? {
        if (!s.matches(p + 4, "ftyp")) return null
        val brand = buildString { for (k in 0 until 4) { val c = s.u8(p + 8 + k); if (c < 0) return null; append(c.toChar()) } }
        val fmt = when (brand) { in HEIC_BRANDS -> "heic"; in AVIF_BRANDS -> "avif"; else -> return null }
        var i = p
        var sawMeta = false
        var w = 0
        var h = 0
        while (true) {
            var size = s.u32(i)
            if (size < 0) break
            var header = 8L
            if (size == 1L) { size = (s.u32(i + 8) shl 32) or s.u32(i + 12); header = 16 }
            if (size == 0L) size = s.length - i
            if (size < header) break
            val type = buildString { for (k in 0 until 4) { val c = s.u8(i + 4 + k); if (c < 0) return null; append(c.toChar()) } }
            if (type !in ISO_TOP) break
            val end = i + size
            if (type == "meta") {
                sawMeta = true
                val scanTo = minOf(end, i + 256 * 1024)
                var q = i + header
                while (q + 16 <= scanTo) {
                    if (s.matches(q, "ispe")) {
                        val cw = s.u32(q + 8).toInt(); val ch = s.u32(q + 12).toInt()
                        if (cw > 0 && ch > 0 && cw.toLong() * ch > w.toLong() * h) { w = cw; h = ch }
                    }
                    q++
                }
            }
            if (type == "mdat") {
                if (!sawMeta) return null
                return if (end > s.length) Region(fmt, p, s.length, false, w, h) else Region(fmt, p, end, true, w, h)
            }
            if (end > s.length) break
            i = end
        }
        return null
    }
}
