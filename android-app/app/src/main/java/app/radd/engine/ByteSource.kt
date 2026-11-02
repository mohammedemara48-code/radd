package app.radd.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Random-access, read-only view of a file with a tiny page cache, so parsers can walk huge blobs without loading them. */
interface ByteSource : Closeable {
    val length: Long

    /** The unsigned byte at [pos], or -1 when [pos] is outside the file. */
    fun u8(pos: Long): Int

    /** Copies up to [len] bytes starting at [pos]; returns how many were read (0 at end of file). */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int

    fun u16(pos: Long): Int {
        val a = u8(pos); val b = u8(pos + 1)
        return if (a < 0 || b < 0) -1 else (a shl 8) or b
    }

    fun u32(pos: Long): Long {
        var v = 0L
        for (k in 0 until 4) {
            val b = u8(pos + k)
            if (b < 0) return -1
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    fun u16le(pos: Long): Int {
        val a = u8(pos); val b = u8(pos + 1)
        return if (a < 0 || b < 0) -1 else (b shl 8) or a
    }

    fun u32le(pos: Long): Long {
        var v = 0L
        for (k in 3 downTo 0) {
            val b = u8(pos + k)
            if (b < 0) return -1
            v = (v shl 8) or b.toLong()
        }
        return v
    }

    fun matches(pos: Long, text: String): Boolean {
        for (k in text.indices) if (u8(pos + k) != text[k].code) return false
        return true
    }
}

class FileSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()
    private val page = ByteArray(PAGE)
    private var pageIndex = -1L
    private var pageLen = 0

    private fun load(index: Long) {
        if (index == pageIndex) return
        raf.seek(index * PAGE)
        var n = 0
        while (n < PAGE) {
            val r = raf.read(page, n, PAGE - n)
            if (r < 0) break
            n += r
        }
        pageIndex = index
        pageLen = n
    }

    override fun u8(pos: Long): Int {
        if (pos < 0 || pos >= length) return -1
        load(pos / PAGE)
        val i = (pos % PAGE).toInt()
        return if (i < pageLen) page[i].toInt() and 0xff else -1
    }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos < 0 || pos >= length || len <= 0) return 0
        raf.seek(pos)
        var n = 0
        val want = minOf(len.toLong(), length - pos).toInt()
        while (n < want) {
            val r = raf.read(buf, off + n, want - n)
            if (r < 0) break
            n += r
        }
        pageIndex = -1 // the shared file pointer moved
        return n
    }

    override fun close() = raf.close()

    private companion object { const val PAGE = 64 * 1024 }
}
