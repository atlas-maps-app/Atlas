package app.atlas.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a [RoadSign] limits. */
enum class SignKind {
    /** Maximum speed: the number alone. */
    SPEED,

    /** Maximum height, with the arrowheads above and below. */
    HEIGHT,

    /** Maximum width, with the arrowheads left and right. */
    WIDTH,

    /** Maximum weight. */
    WEIGHT,

    /** Maximum length, with arrows along a bar. */
    LENGTH,
}

/**
 * A prohibitory road sign as Europe draws it: a white disc in a red ring with the limit in black. [value] is what the
 * sign says ("50", "3.5 m", "7.5 t"). Used for the speed limit while driving and for the truck's restrictions.
 */
@Composable
fun RoadSign(kind: SignKind, value: String, modifier: Modifier = Modifier, size: Dp = 56.dp, contentDescription: String? = null) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription ?: value }) {
        drawSign(kind, value, measurer)
    }
}

private val SIGN_RED = Color(0xFFD2232A)
private val SIGN_INK = Color(0xFF111111)

private fun DrawScope.drawSign(kind: SignKind, value: String, measurer: TextMeasurer) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(Color(0x33000000), r * 0.98f, c + Offset(0f, r * 0.05f))
    drawCircle(Color.White, r * 0.98f, c)
    drawCircle(SIGN_RED, r * 0.9f, c)
    drawCircle(Color.White, r * 0.7f, c)
    // The number shrinks to fit: "130" and "3.5 m" both stay inside the white.
    val maxWidth = r * (if (kind == SignKind.WIDTH) 0.85f else 1.15f)
    var fontPx = r * (if (kind == SignKind.SPEED) 0.78f else 0.5f)
    var layout = measurer.measure(value, Type.numeral.copy(fontSize = (fontPx / density).sp, fontWeight = FontWeight.Black, color = SIGN_INK))
    while (layout.size.width > maxWidth && fontPx > r * 0.2f) {
        fontPx *= 0.9f
        layout = measurer.measure(value, Type.numeral.copy(fontSize = (fontPx / density).sp, fontWeight = FontWeight.Black, color = SIGN_INK))
    }
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
    val a = r * 0.14f
    when (kind) {
        SignKind.HEIGHT -> {
            drawPath(triangle(Offset(c.x, c.y - r * 0.42f), a, pointingDown = true), SIGN_INK)
            drawPath(triangle(Offset(c.x, c.y + r * 0.42f), a, pointingDown = false), SIGN_INK)
        }
        SignKind.WIDTH -> {
            drawPath(sideTriangle(Offset(c.x - r * 0.5f, c.y), a, pointingRight = true), SIGN_INK)
            drawPath(sideTriangle(Offset(c.x + r * 0.5f, c.y), a, pointingRight = false), SIGN_INK)
        }
        SignKind.LENGTH -> {
            val y = c.y + r * 0.4f
            drawLine(SIGN_INK, Offset(c.x - r * 0.42f, y), Offset(c.x + r * 0.42f, y), strokeWidth = r * 0.05f)
            drawPath(sideTriangle(Offset(c.x - r * 0.46f, y), a * 0.7f, pointingRight = false), SIGN_INK)
            drawPath(sideTriangle(Offset(c.x + r * 0.46f, y), a * 0.7f, pointingRight = true), SIGN_INK)
        }
        SignKind.SPEED, SignKind.WEIGHT -> Unit
    }
}

private fun triangle(tip: Offset, a: Float, pointingDown: Boolean): Path = Path().apply {
    val dir = if (pointingDown) -1f else 1f
    moveTo(tip.x, tip.y)
    lineTo(tip.x - a, tip.y + dir * a)
    lineTo(tip.x + a, tip.y + dir * a)
    close()
}

private fun sideTriangle(tip: Offset, a: Float, pointingRight: Boolean): Path = Path().apply {
    val dir = if (pointingRight) -1f else 1f
    moveTo(tip.x, tip.y)
    lineTo(tip.x + dir * a, tip.y - a)
    lineTo(tip.x + dir * a, tip.y + a)
    close()
}
