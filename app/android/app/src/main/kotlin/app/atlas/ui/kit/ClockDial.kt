package app.atlas.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import java.time.LocalTime

/**
 * The skeuomorphic 24-hour opening-hours clock of the Place board: a cream face in a brushed-steel bezel, midnight at
 * the top and noon at the bottom, a green band for the hours open today ([spans], from `OpeningHours.spans`), amber
 * where the hours are unknown, and two hands at [now]: the short one goes round once a day, the long one once an hour.
 * The caller writes the words ("→ 23:00") under it; [contentDescription] says the same for accessibility.
 */
@Composable
fun ClockDial(
    spans: List<OpenSpan>,
    now: LocalTime,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    contentDescription: String? = null,
    openColour: Color = Atlas.colors.open,
    unknownColour: Color = Atlas.colors.caution,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val ink = LightColors.ink
    val face = remember { Brush.radialGradient(listOf(Color(0xFFFFFDF8), Color(0xFFF3EEE3))) }
    Canvas(
        modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        // Bezel: brushed steel, light from the top left, with a soft drop shadow under it.
        drawCircle(Color(0x33000000), r * 0.98f, c + Offset(0f, r * 0.04f))
        drawCircle(Brush.linearGradient(listOf(Color(0xFFF7F7F5), Color(0xFFB9BCC0), Color(0xFFE9EAEA)), c - Offset(r, r), c + Offset(r, r)), r * 0.97f, c)
        drawCircle(Brush.linearGradient(listOf(Color(0xFF9DA1A6), Color(0xFFF2F2F0)), c - Offset(r, r), c + Offset(r, r)), r * 0.88f, c)
        drawCircle(face, r * 0.84f, c)
        // The band: closed track, then open and unknown hours over it.
        val bandR = r * 0.71f
        val bandW = r * 0.16f
        val box = Size(bandR * 2, bandR * 2)
        val tl = c - Offset(bandR, bandR)
        drawArc(Color(0xFFE9E3D6), 0f, 360f, false, tl, box, style = Stroke(bandW))
        for (s in spans) {
            if (s.state == OpenState.CLOSED) continue
            val colour = if (s.state == OpenState.OPEN) openColour else unknownColour
            var start = s.start.coerceIn(0, 2880)
            val end = s.end.coerceIn(start, 2880)
            // A span past midnight is drawn round to the next morning, at most a full turn.
            val sweep = ((end - start).coerceAtMost(1440)) / 1440f * 360f
            if (sweep <= 0f) continue
            start %= 1440
            drawArc(colour, start / 1440f * 360f - 90f, sweep, false, tl, box, style = Stroke(bandW, cap = StrokeCap.Butt))
        }
        // Hour ticks, longer every six hours.
        for (h in 0 until 24) {
            val major = h % 6 == 0
            rotate(h * 15f, c) {
                val outer = r * 0.83f
                val inner = if (major) r * 0.66f else r * 0.7f
                drawLine(if (major) ink else ink.copy(alpha = 0.45f), Offset(c.x, c.y - outer + bandW * 0.1f), Offset(c.x, c.y - inner), strokeWidth = if (major) r * 0.035f else r * 0.018f)
            }
        }
        drawNumerals(measurer, c, r, ink)
        // Hands: 24-hour, then minutes.
        val minuteOfDay = now.hour * 60 + now.minute
        hand(c, minuteOfDay / 1440f * 360f, r * 0.42f, r * 0.075f, ink)
        hand(c, now.minute / 60f * 360f, r * 0.6f, r * 0.04f, ink.copy(alpha = 0.85f))
        drawCircle(Color(0xFFE0592A), r * 0.07f, c)
        drawCircle(Color(0x66FFFFFF), r * 0.025f, c - Offset(r * 0.015f, r * 0.015f))
    }
}

private fun DrawScope.hand(c: Offset, degrees: Float, length: Float, width: Float, colour: Color) {
    rotate(degrees, c) {
        val p = Path().apply {
            moveTo(c.x - width / 2f, c.y + width * 1.6f)
            lineTo(c.x - width * 0.28f, c.y - length)
            lineTo(c.x + width * 0.28f, c.y - length)
            lineTo(c.x + width / 2f, c.y + width * 1.6f)
            close()
        }
        drawPath(p, Color(0x33000000), alpha = 0.6f)
        drawPath(p, colour)
    }
}

private fun DrawScope.drawNumerals(measurer: TextMeasurer, c: Offset, r: Float, ink: Color) {
    val style = Type.label.copy(fontSize = (r * 0.2f / density).sp, color = ink)
    val labels = arrayOf("0", "6", "12", "18")
    for (i in labels.indices) {
        val layout = measurer.measure(labels[i], style)
        val a = Math.toRadians(i * 90.0 - 90.0)
        val d = r * 0.44f
        val x = c.x + (d * kotlin.math.cos(a)).toFloat() - layout.size.width / 2f
        val y = c.y + (d * kotlin.math.sin(a)).toFloat() - layout.size.height / 2f
        drawText(layout, topLeft = Offset(x, y))
    }
}
