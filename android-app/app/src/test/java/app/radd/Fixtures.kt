package app.radd

import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32

/** Real image bytes made on the JVM, so the engine is tested against genuine encoders, not hand-written look-alikes. */
object Fixtures {
    private fun resource(name: String): ByteArray =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("missing fixture $name")).use { it.readBytes() }

    /**
     * A real baseline JPEG (made once with an independent encoder, see resources/fixtures). [seed] inserts a comment segment, so every
     * seed gives different bytes (and a different SHA-256) while the image stays a valid, decodable JPEG.
     */
    fun jpeg(seed: Int, w: Int = 160, h: Int = 120): ByteArray {
        val base = resource("jpeg_${w}x$h.jpg")
        val text = "radd-fixture-$seed".toByteArray()
        val com = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, (text.size + 2).toByte()) + text
        return base.copyOfRange(0, 2) + com + base.copyOfRange(2, base.size)
    }

    /** A real PNG with an extra tEXt chunk (correct CRC) carrying [seed]. */
    fun png(seed: Int, w: Int = 64, h: Int = 48): ByteArray {
        val base = resource("png_${w}x$h.png")
        val data = "Comment\u0000radd-$seed".toByteArray()
        val type = "tEXt".toByteArray()
        val crc = CRC32().also { it.update(type); it.update(data) }.value
        val chunk = ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type).put(data).putInt(crc.toInt()).array()
        val iend = base.size - 12
        return base.copyOfRange(0, iend) + chunk + base.copyOfRange(iend, base.size)
    }

    /** A RIFF/WebP container (lossless header) with filler payload: enough for structure and size checks. */
    fun webp(seed: Int, w: Int = 300, h: Int = 200, payload: Int = 6000): ByteArray {
        val rnd = Random(seed.toLong())
        val body = ByteArray(payload).also { rnd.nextBytes(it) }
        val bits = ((w - 1) and 0x3fff) or (((h - 1) and 0x3fff) shl 14)
        val header = byteArrayOf(0x2f, (bits and 0xff).toByte(), ((bits shr 8) and 0xff).toByte(), ((bits shr 16) and 0xff).toByte(), ((bits shr 24) and 0xff).toByte())
        val data = header + body
        val out = ByteBuffer.allocate(12 + 8 + data.size + (data.size and 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(4 + 8 + data.size + (data.size and 1)).put("WEBP".toByteArray())
        out.put("VP8L".toByteArray()).putInt(data.size).put(data)
        return out.array()
    }

    /** ftyp + meta(ispe) + mdat, the minimum a HEIC/AVIF reader needs. */
    fun heic(seed: Int, w: Int = 1024, h: Int = 768, payload: Int = 6000, brand: String = "heic"): ByteArray {
        val rnd = Random(seed.toLong())
        fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
        val ftyp = box("ftyp", brand.toByteArray() + ByteArray(4) + brand.toByteArray() + "mif1".toByteArray())
        val ispe = box("ispe", ByteBuffer.allocate(12).putInt(0).putInt(w).putInt(h).array())
        val meta = box("meta", ByteArray(4) + ispe)
        val mdat = box("mdat", ByteArray(payload).also { rnd.nextBytes(it) })
        return ftyp + meta + mdat
    }

    fun junk(n: Int, seed: Int = 7, avoidMarkers: Boolean = true): ByteArray {
        val rnd = Random(seed.toLong())
        val b = ByteArray(n)
        rnd.nextBytes(b)
        if (avoidMarkers) for (i in b.indices) if (b[i] == 0xff.toByte() || b[i] == 0x89.toByte() || b[i] == 0x52.toByte()) b[i] = 0x11
        return b
    }

    fun write(file: File, vararg parts: ByteArray): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { o -> parts.forEach { o.write(it) } }
        return file
    }
}
