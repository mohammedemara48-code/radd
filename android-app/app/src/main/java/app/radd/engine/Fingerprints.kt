package app.radd.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.ln

/**
 * A tiny perceptual signature of a picture: a difference hash (9x8 grid) and an average hash (8x8 grid), plus the real
 * dimensions after EXIF rotation. A resized or recompressed copy of the same photo (a WhatsApp copy, a gallery thumbnail)
 * lands within a few bits of the original; two different photos land 20+ bits apart.
 */
data class Fingerprint(val width: Int, val height: Int, val dhash: Long, val ahash: Long, val spread: Int) {
    /** A solid colour or near-solid picture: a UI asset or a blank, never somebody's photo. */
    val flat: Boolean get() = spread < Fingerprints.FLAT_SPREAD
}

/** Makes a [Fingerprint] for the image stored in bytes [offset, offset + length) of [file]; null when it cannot be decoded. */
fun interface Fingerprinter { fun of(file: File, offset: Long, length: Long): Fingerprint? }

object Fingerprints {
    const val FLAT_SPREAD = 10
    const val MAX_DHASH_DISTANCE = 8
    const val MAX_AHASH_DISTANCE = 10
    /** Picture shapes may differ by about 8% (ln ratio) for a copy; system thumbnails get more room because they are cropped. */
    const val ASPECT_TOLERANCE = 0.08
    const val THUMB_ASPECT_TOLERANCE = 0.35

    val none = Fingerprinter { _, _, _ -> null }

    /** [gray] holds [w]x[h] luminance bytes, row by row. [fullW]/[fullH] are the picture's real (rotated) dimensions. */
    fun fromGray(gray: ByteArray, w: Int, h: Int, fullW: Int = w, fullH: Int = h): Fingerprint? {
        if (w < 9 || h < 8 || gray.size < w * h) return null
        val d = means(gray, w, h, 9, 8)
        val a = means(gray, w, h, 8, 8)
        var dh = 0L
        for (r in 0 until 8) for (c in 0 until 8) dh = (dh shl 1) or (if (d[r * 9 + c] < d[r * 9 + c + 1]) 1L else 0L)
        val mean = a.sum() / 64
        var ah = 0L
        for (v in a) ah = (ah shl 1) or (if (v > mean) 1L else 0L)
        return Fingerprint(fullW, fullH, dh, ah, a.max() - a.min())
    }

    private fun means(g: ByteArray, w: Int, h: Int, cols: Int, rows: Int): IntArray {
        val out = IntArray(cols * rows)
        for (r in 0 until rows) {
            val y0 = r * h / rows
            val y1 = maxOf(y0 + 1, (r + 1) * h / rows)
            for (c in 0 until cols) {
                val x0 = c * w / cols
                val x1 = maxOf(x0 + 1, (c + 1) * w / cols)
                var sum = 0L
                for (y in y0 until y1) for (x in x0 until x1) sum += g[y * w + x].toInt() and 0xff
                out[r * cols + c] = (sum / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }
        return out
    }

    private fun logAspect(f: Fingerprint) = ln(f.width.toDouble() / f.height.coerceAtLeast(1))

    fun aspectClose(a: Fingerprint, b: Fingerprint, tolerance: Double) = abs(logAspect(a) - logAspect(b)) <= tolerance

    /** True when [a] and [b] show the same scene (the same photo, possibly resized or recompressed). */
    fun sameScene(a: Fingerprint, b: Fingerprint, aspectTolerance: Double = ASPECT_TOLERANCE): Boolean =
        aspectClose(a, b, aspectTolerance) &&
            java.lang.Long.bitCount(a.dhash xor b.dhash) <= MAX_DHASH_DISTANCE &&
            java.lang.Long.bitCount(a.ahash xor b.ahash) <= MAX_AHASH_DISTANCE
}
