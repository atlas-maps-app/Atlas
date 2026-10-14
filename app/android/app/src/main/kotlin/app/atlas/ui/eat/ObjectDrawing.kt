package app.atlas.ui.eat

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.random.Random

internal val NEON_PINK = Color(0xFFFF4FD8)
internal val NEON_CYAN = Color(0xFF36E3FF)

/** A frame of polished wood [width] wide round the whole canvas. */
internal fun drawWoodFrame(scope: DrawScope, width: Float) = with(scope) {
    val r = CornerRadius(5.dp.toPx())
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFA9703C), Color(0xFF8A5528), Color(0xFFB57B45), Color(0xFF7E4B22)), Offset.Zero, Offset(size.width, size.height)), cornerRadius = r)
    // Grain: long faint streaks along each side.
    val rnd = Random(9)
    repeat(40) {
        val y = rnd.nextFloat() * size.height
        drawLine(Color(0x22000000), Offset(0f, y), Offset(size.width, y + rnd.nextFloat() * 6f), 0.8f)
    }
    drawRoundRect(Color(0x55000000), topLeft = Offset(width - 1f, width - 1f), size = Size(size.width - 2 * width + 2f, size.height - 2 * width + 2f), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(2f))
}

/** A slate: dark green-grey, with the faint sweeps of a cloth that wiped old chalk away. */
internal fun DrawScope.drawSlate(topLeft: Offset, size: Size, seed: Int) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF3A413C), Color(0xFF2A302C), Color(0xFF232825)), center = topLeft + Offset(size.width * 0.45f, size.height * 0.4f), radius = size.maxDimension * 0.8f), topLeft, size)
    val rnd = Random(seed)
    val band = minOf(size.width, size.height) * 0.12f
    repeat(7) {
        val y = topLeft.y + rnd.nextFloat() * size.height
        val x0 = topLeft.x + rnd.nextFloat() * size.width * 0.3f
        val x1 = topLeft.x + size.width * (0.6f + rnd.nextFloat() * 0.4f)
        val sag = (rnd.nextFloat() - 0.5f) * band * 1.5f
        val wipe = Path().apply {
            moveTo(x0, y)
            quadraticTo((x0 + x1) / 2f, y + sag, x1, y - sag * 0.3f)
        }
        val cloth = Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x07FFFFFF), Color(0x05FFFFFF), Color(0x00FFFFFF)), x0, x1)
        drawPath(wipe, cloth, style = Stroke(band * (0.5f + rnd.nextFloat() * 0.6f), cap = StrokeCap.Butt))
    }
    // Dust: a few fine chalk specks.
    val r = 0.6.dp.toPx()
    repeat(120) { drawCircle(Color(0x1CFFFFFF), r, topLeft + Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)) }
}

/** A gold double-line frame with stepped Art Deco corners. */
internal fun drawDecoFrame(scope: DrawScope, gold: Color, inset: Float, step: Float) = with(scope) {
    fun frame(i: Float, s: Float) {
        val p = Path().apply {
            val l = i; val t = i; val r = size.width - i; val b = size.height - i
            moveTo(l + s, t)
            lineTo(r - s, t); lineTo(r - s, t + s * 0.5f); lineTo(r - s * 0.5f, t + s * 0.5f); lineTo(r - s * 0.5f, t + s); lineTo(r, t + s)
            lineTo(r, b - s); lineTo(r - s * 0.5f, b - s); lineTo(r - s * 0.5f, b - s * 0.5f); lineTo(r - s, b - s * 0.5f); lineTo(r - s, b)
            lineTo(l + s, b); lineTo(l + s, b - s * 0.5f); lineTo(l + s * 0.5f, b - s * 0.5f); lineTo(l + s * 0.5f, b - s); lineTo(l, b - s)
            lineTo(l, t + s); lineTo(l + s * 0.5f, t + s); lineTo(l + s * 0.5f, t + s * 0.5f); lineTo(l + s, t + s * 0.5f); close()
        }
        drawPath(p, gold, style = Stroke(1.dp.toPx(), cap = StrokeCap.Square))
    }
    frame(inset, step)
    frame(inset + 4.dp.toPx(), step)
}

/** Fine speckles of light and dark: leather, paper and cardboard grain, the same each time for [seed]. */
internal fun DrawScope.grain(light: Color, dark: Color, n: Int, seed: Int) {
    val rnd = Random(seed)
    val r = 0.7.dp.toPx()
    repeat(n) {
        val c = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)
        drawCircle(if (it % 2 == 0) light else dark, r * (0.6f + rnd.nextFloat()), c)
    }
}
