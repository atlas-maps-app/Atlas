package app.atlas.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The brass compass of the Sail board, also the map's north button in miniature. The card turns so its north points
 * to real north when the phone (or the map) faces [heading] degrees; the lubber mark at the top is straight ahead.
 * [target] adds a pointer on the bezel for a bearing to steer to (a buoy, the nearest toilet). Small sizes leave out
 * the degree ticks and letters other than N.
 */
@Composable
fun Compass(
    heading: Float,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    target: Float? = null,
    targetColour: Color = Atlas.colors.go,
    contentDescription: String? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    // Turn the short way round, smoothly, without spinning through 359 → 0.
    val angle = remember { Animatable(-heading) }
    LaunchedEffect(heading) {
        val current = angle.value
        var goal = -heading
        while (goal - current > 180f) goal -= 360f
        while (goal - current < -180f) goal += 360f
        angle.animateTo(goal, tween(250))
    }
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        drawCompass(angle.value, target?.let { it - heading }, targetColour, measurer)
    }
}

private val BRASS = listOf(Color(0xFF8A6526), Color(0xFFF3D58C), Color(0xFFB0832F), Color(0xFFFBE7AE), Color(0xFF9C7230), Color(0xFFE9C46F), Color(0xFF8A6526))
private val NORTH = Color(0xFFD7322B)
private val SOUTH = Color(0xFF1E2A55)

private fun DrawScope.drawCompass(cardAngle: Float, target: Float?, targetColour: Color, measurer: TextMeasurer) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val small = r < 36.dp.toPx()
    drawCircle(Color(0x40000000), r * 0.98f, c + Offset(0f, r * 0.05f))
    // Bezel: brass, with a darker inner lip.
    drawCircle(Brush.sweepGradient(BRASS, c), r * 0.97f, c)
    drawCircle(Brush.linearGradient(listOf(Color(0xFF6E4F1C), Color(0xFFD9B566)), c - Offset(r, r), c + Offset(r, r)), r * 0.83f, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFFCF2), Color(0xFFF1E7CF)), c, r * 0.8f), r * 0.79f, c)
    rotate(cardAngle, c) {
        if (!small) {
            for (d in 0 until 360 step 10) {
                val major = d % 30 == 0
                rotate(d.toFloat(), c) {
                    drawLine(
                        Color(0xFF3B2F1E).copy(alpha = if (major) 0.9f else 0.5f),
                        Offset(c.x, c.y - r * 0.78f), Offset(c.x, c.y - r * (if (major) 0.66f else 0.71f)),
                        strokeWidth = if (major) r * 0.022f else r * 0.012f,
                    )
                }
            }
            val letters = arrayOf("N", "E", "S", "W")
            for (i in letters.indices) {
                val style = Type.label.copy(fontSize = (r * 0.13f / density).sp, fontWeight = FontWeight.Bold, color = if (i == 0) NORTH else Color(0xFF3B2F1E))
                val layout = measurer.measure(letters[i], style)
                val a = Math.toRadians(i * 90.0 - 90.0)
                val d = r * 0.56f
                rotate(i * 90f, Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())) {
                    drawText(layout, topLeft = Offset(c.x + (d * cos(a)).toFloat() - layout.size.width / 2f, c.y + (d * sin(a)).toFloat() - layout.size.height / 2f))
                }
            }
        }
        // The needle: red to the north, navy to the south, with a light edge on one side for depth.
        val len = r * (if (small) 0.7f else 0.45f)
        val w = r * (if (small) 0.2f else 0.1f)
        val north = Path().apply { moveTo(c.x, c.y - len); lineTo(c.x + w, c.y); lineTo(c.x - w, c.y); close() }
        val south = Path().apply { moveTo(c.x, c.y + len); lineTo(c.x + w, c.y); lineTo(c.x - w, c.y); close() }
        drawPath(north, NORTH)
        drawPath(south, SOUTH)
        val shade = Path().apply { moveTo(c.x, c.y - len); lineTo(c.x + w, c.y); lineTo(c.x, c.y + len); close() }
        drawPath(shade, Color(0x2E000000))
    }
    // The pivot.
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFBE7AE), Color(0xFF8A6526)), c, r * 0.09f), r * 0.08f, c)
    if (!small) {
        // Lubber mark: straight ahead.
        val lubber = Path().apply { moveTo(c.x, c.y - r * 0.8f); lineTo(c.x - r * 0.05f, c.y - r * 0.93f); lineTo(c.x + r * 0.05f, c.y - r * 0.93f); close() }
        drawPath(lubber, Color(0xFF3B2F1E))
    }
    if (target != null) {
        rotate(target, c) {
            drawCircle(targetColour, r * 0.075f, Offset(c.x, c.y - r * 0.9f))
            drawCircle(Color.White, r * 0.075f, Offset(c.x, c.y - r * 0.9f), style = Stroke(r * 0.02f))
        }
    }
}
