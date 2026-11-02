package app.radd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Own-made procedural scenes (no third-party artwork). Used for the welcome illustration, empty states,
 * and as stand-in photos in design previews. Deterministic per [seed].
 */
@Composable
fun ProceduralPhoto(seed: Int, modifier: Modifier = Modifier.fillMaxSize(), partial: Boolean = false) {
    Canvas(modifier) {
        when (Math.floorMod(seed, 7)) {
            0 -> sunset(seed)
            1 -> mountains(seed)
            2 -> nightCity(seed)
            3 -> table(seed)
            4 -> screenshot(seed)
            5 -> sea(seed)
            else -> bokeh(seed)
        }
        if (partial) truncatedBand()
    }
}

private fun c(hex: Int) = Color(hex or 0xFF000000.toInt())

private fun DrawScope.hill(rnd: Random, baseY: Float, amp: Float, color: Color) {
    val p = Path()
    val phase = rnd.nextFloat() * 6f
    val freq = 1f + rnd.nextFloat() * 1.6f
    p.moveTo(0f, size.height)
    var x = 0f
    while (x <= size.width + 4f) {
        val y = baseY + amp * sin((x / size.width * freq * 2 * PI + phase).toFloat())
        p.lineTo(x, y)
        x += 4f
    }
    p.lineTo(size.width, size.height)
    p.close()
    drawPath(p, color)
}

private fun DrawScope.sunset(seed: Int) {
    val rnd = Random(seed)
    val tops = listOf(0x3B2A6B, 0x5A2D6E, 0x23395D)
    val mids = listOf(0xE0674A, 0xD9534F, 0xE58A4B)
    drawRect(Brush.verticalGradient(listOf(c(tops[seed.mod(3)]), c(mids[seed.mod(3)]), c(0xF7C66A)), 0f, size.height * 0.7f))
    drawCircle(c(0xFFE9A8), size.minDimension * 0.13f, Offset(size.width * (0.3f + rnd.nextFloat() * 0.4f), size.height * 0.52f))
    hill(rnd, size.height * 0.68f, size.height * 0.04f, c(0x4A3350))
    hill(rnd, size.height * 0.8f, size.height * 0.05f, c(0x2A1E38))
}

private fun DrawScope.mountains(seed: Int) {
    val rnd = Random(seed)
    drawRect(Brush.verticalGradient(listOf(c(0x4C8FD6), c(0xBCE0F5)), 0f, size.height * 0.7f))
    repeat(3) { i ->
        val cx = size.width * (0.15f + 0.35f * i + rnd.nextFloat() * 0.1f)
        val h = size.height * (0.3f + rnd.nextFloat() * 0.2f)
        val w = size.width * 0.55f
        val p = Path().apply { moveTo(cx - w / 2, size.height * 0.75f); lineTo(cx, size.height * 0.75f - h); lineTo(cx + w / 2, size.height * 0.75f); close() }
        drawPath(p, listOf(c(0x6C7E8F), c(0x55697A), c(0x7C8D9C))[i])
        val cap = Path().apply { moveTo(cx - w * 0.09f, size.height * 0.75f - h * 0.8f); lineTo(cx, size.height * 0.75f - h); lineTo(cx + w * 0.09f, size.height * 0.75f - h * 0.8f); close() }
        drawPath(cap, Color.White)
    }
    repeat(3) { drawCircle(Color(0xCCFFFFFF), size.width * 0.07f, Offset(size.width * rnd.nextFloat(), size.height * (0.1f + rnd.nextFloat() * 0.2f))) }
    hill(rnd, size.height * 0.78f, size.height * 0.03f, c(0x4E8F4A))
}

private fun DrawScope.nightCity(seed: Int) {
    val rnd = Random(seed)
    drawRect(Brush.verticalGradient(listOf(c(0x0E1A3A), c(0x2D3F78)), 0f, size.height))
    drawCircle(c(0xF4EFD8), size.minDimension * 0.07f, Offset(size.width * 0.75f, size.height * 0.2f))
    var x = 0f
    while (x < size.width) {
        val w = size.width * (0.1f + rnd.nextFloat() * 0.1f)
        val h = size.height * (0.25f + rnd.nextFloat() * 0.35f)
        drawRect(c(0x131B33), Offset(x, size.height - h), Size(w, h))
        var wy = size.height - h + 8f
        while (wy < size.height - 10f) {
            var wx = x + 6f
            while (wx < x + w - 8f) {
                if (rnd.nextFloat() > 0.45f) drawRect(c(0xF3D27A), Offset(wx, wy), Size(5f, 7f))
                wx += 11f
            }
            wy += 14f
        }
        x += w + 2f
    }
}

private fun DrawScope.table(seed: Int) {
    val rnd = Random(seed)
    drawRect(Brush.linearGradient(listOf(c(0xB98A5E), c(0xD2A777))))
    repeat(4) { i -> drawRect(Color(0x14000000), Offset(0f, size.height * (0.2f * i + 0.1f)), Size(size.width, 3f)) }
    val r = size.minDimension * 0.28f
    val ctr = Offset(size.width * 0.5f, size.height * 0.5f)
    drawCircle(Color(0x33000000), r + 6f, ctr + Offset(5f, 8f))
    drawCircle(c(0xFAF7F0), r, ctr)
    drawCircle(c(0xEDE6D8), r * 0.78f, ctr)
    val food = listOf(0xD9503F, 0x78A84A, 0xF1B94B, 0xC98B52)
    repeat(7) { drawCircle(c(food[rnd.nextInt(4)]), r * (0.12f + rnd.nextFloat() * 0.1f), ctr + Offset((rnd.nextFloat() - 0.5f) * r, (rnd.nextFloat() - 0.5f) * r)) }
}

private fun DrawScope.screenshot(seed: Int) {
    val rnd = Random(seed)
    drawRect(c(0xF4F6F4))
    drawRect(c(listOf(0x1F8A70, 0x3D4FD6, 0xB5503D)[seed.mod(3)]), Offset.Zero, Size(size.width, size.height * 0.12f))
    var y = size.height * 0.2f
    var i = 0
    while (y < size.height * 0.92f) {
        val w = size.width * (0.35f + rnd.nextFloat() * 0.4f)
        val mine = i % 2 == 0
        drawRoundRectCompat(if (mine) c(0xD8F0D0) else Color.White, if (mine) size.width - w - size.width * 0.06f else size.width * 0.06f, y, w, size.height * 0.07f)
        y += size.height * 0.11f
        i++
    }
}

private fun DrawScope.drawRoundRectCompat(color: Color, x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(color, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(h * 0.3f))
}

private fun DrawScope.sea(seed: Int) {
    val rnd = Random(seed)
    drawRect(Brush.verticalGradient(listOf(c(0x86C7EE), c(0xDDF1FA)), 0f, size.height * 0.5f))
    drawRect(Brush.verticalGradient(listOf(c(0x1D7FA8), c(0x0E4F73)), size.height * 0.5f, size.height), Offset(0f, size.height * 0.5f), Size(size.width, size.height * 0.5f))
    repeat(8) { i -> drawRect(Color(0x33FFFFFF), Offset(size.width * rnd.nextFloat() * 0.7f, size.height * (0.55f + i * 0.055f)), Size(size.width * 0.25f, 3f)) }
    val bx = size.width * (0.35f + rnd.nextFloat() * 0.3f)
    val by = size.height * 0.52f
    drawPath(Path().apply { moveTo(bx - 40f, by); lineTo(bx + 40f, by); lineTo(bx + 26f, by + 14f); lineTo(bx - 26f, by + 14f); close() }, c(0xB5503D))
    drawPath(Path().apply { moveTo(bx, by - 60f); lineTo(bx + 30f, by - 6f); lineTo(bx, by - 6f); close() }, Color.White)
}

private fun DrawScope.bokeh(seed: Int) {
    val rnd = Random(seed)
    val a = listOf(0xE0A15A, 0xD9638A, 0x7C6AD6, 0x3FA88F)[seed.mod(4)]
    drawRect(Brush.linearGradient(listOf(c(a), c(0x1B2530))))
    repeat(16) { drawCircle(Color.White.copy(alpha = 0.10f + rnd.nextFloat() * 0.2f), size.minDimension * (0.04f + rnd.nextFloat() * 0.12f), Offset(size.width * rnd.nextFloat(), size.height * rnd.nextFloat())) }
    drawCircle(c(0xE7C4A5), size.minDimension * 0.13f, Offset(size.width * 0.5f, size.height * 0.42f))
    drawCircle(c(0x2E2A3A), size.minDimension * 0.27f, Offset(size.width * 0.5f, size.height * 0.92f))
}

/** What a truncated JPEG really looks like: the decoder fills the missing tail with flat grey. */
private fun DrawScope.truncatedBand() {
    drawRect(c(0x808080), Offset(0f, size.height * 0.68f), Size(size.width, size.height * 0.32f))
}
