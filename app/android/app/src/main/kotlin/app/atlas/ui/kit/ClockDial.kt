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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 24-hour opening-hours dial of the Place board (`Place.dc.html`), drawn from the board's own SVG on its 108-unit
 * grid: a drop shadow, the bezel's diagonal gradient with its stone rim, the cream face lit from the top left, 24 hour
 * ticks, midnight at the top and noon at the bottom with the board's 0, 6, 12 and 18, a green band at radius 40 for
 * each span open today ([spans], from `OpeningHours.spans`), amber where the hours are unknown, and one hand with its
 * counterweight pointing at [now] round a copper hub. The caller writes the words ("→ 23:00") under it;
 * [contentDescription] says the same for accessibility.
 */
@Composable
fun ClockDial(
    spans: List<OpenSpan>,
    now: LocalTime,
    modifier: Modifier = Modifier,
    size: Dp = 108.dp,
    contentDescription: String? = null,
    openColour: Color = DialLook.OPEN,
    unknownColour: Color = DialLook.UNKNOWN,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val numeral = remember { TextStyle(fontFamily = BoardFonts.of("Geist"), fontWeight = FontWeight(600), color = DialLook.NUMERAL) }
    Canvas(
        modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        // One board unit: the board draws the dial in a 108 × 108 viewBox.
        val u = this.size.minDimension / 108f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        fun at(x: Float, y: Float) = Offset(c.x + (x - 54f) * u, c.y + (y - 54f) * u)
        // <circle cx="54" cy="55.5" r="52" fill="#000" opacity=".12">
        drawCircle(Color.Black.copy(alpha = 0.12f), 52f * u, at(54f, 55.5f))
        // The bezel: linearGradient x1=0 y1=0 x2=1 y2=1 over the circle's box, #FFFFFF, #D9D2C4 at .5, #F3EFE7; rim #B9AE99.
        drawCircle(
            Brush.linearGradient(0f to Color.White, 0.5f to Color(0xFFD9D2C4), 1f to Color(0xFFF3EFE7), start = at(2f, 2f), end = at(106f, 106f)),
            52f * u, c,
        )
        drawCircle(Color(0xFFB9AE99), 52f * u, c, style = Stroke(1f * u))
        // The face: radialGradient cx=.4 cy=.35 r=.75 over the r 45 circle's box (9…99), #FFFEFB to #EFE9DE; rim #CFC6B6.
        drawCircle(
            Brush.radialGradient(0f to Color(0xFFFFFEFB), 1f to Color(0xFFEFE9DE), center = at(9f + 0.4f * 90f, 9f + 0.35f * 90f), radius = 0.75f * 90f * u),
            45f * u, c,
        )
        drawCircle(Color(0xFFCFC6B6), 45f * u, c, style = Stroke(1f * u))
        // The ticks: r 41, 3 wide, dashed 1 on 9.73 from 3 o'clock, turned back half a degree: one tick an hour.
        val tickSweep = Math.toDegrees(1.0 / 41.0).toFloat()
        val tickBox = Size(82f * u, 82f * u)
        val tickTl = c - Offset(41f * u, 41f * u)
        var a = -0.5f
        val period = Math.toDegrees(10.73 / 41.0).toFloat()
        while (a < 359.5f) {
            drawArc(DialLook.TICK, a, tickSweep, false, tickTl, tickBox, style = Stroke(3f * u, cap = StrokeCap.Butt))
            a += period
        }
        // The open hours: arcs of r 40, 7 wide, at 85 %, clockwise from midnight at the top.
        val bandBox = Size(80f * u, 80f * u)
        val bandTl = c - Offset(40f * u, 40f * u)
        for (s in spans) {
            if (s.state == OpenState.CLOSED) continue
            val colour = if (s.state == OpenState.OPEN) openColour else unknownColour
            val start = s.start.coerceIn(0, 2880)
            val end = s.end.coerceIn(start, 2880)
            // A span past midnight runs on round to the next morning, at most a full turn.
            val sweep = (end - start).coerceAtMost(1440) / 1440f * 360f
            if (sweep <= 0f) continue
            drawArc(colour.copy(alpha = colour.alpha * 0.85f), (start % 1440) / 1440f * 360f - 90f, sweep, false, bandTl, bandBox, style = Stroke(7f * u, cap = StrokeCap.Butt))
        }
        // The board's numerals: Geist 600 at 9, #464C5A, centred on their baselines.
        numerals(measurer, numeral.copy(fontSize = (9f * u).toSp()), ::at)
        // The hand, 2.6 wide with round caps: 31.2 to the hour on the 24-hour dial, 8.3 behind the hub.
        val minutes = now.hour * 60 + now.minute + now.second / 60f
        val angle = Math.toRadians(minutes / 1440.0 * 360.0)
        val dir = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        drawLine(DialLook.INK, c, c + dir * (HAND * u), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
        drawLine(DialLook.INK, c, c - dir * (TAIL * u), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
        // The hub: r 4 in copper #C8643A with a 1.5 white rim.
        drawCircle(DialLook.HUB, 4f * u, c)
        drawCircle(Color.White, 4f * u, c, style = Stroke(1.5f * u))
    }
}

/** The board's hand: from the hub to (24, 45.5) at 19:02, and its tail to (62, 56.3). */
private const val HAND = 31.18f
private const val TAIL = 8.32f

/** The board's numerals and their baselines on the 108 grid. */
private val NUMERALS = listOf(Triple("0", 54f, 33f), Triple("6", 78f, 57f), Triple("12", 54f, 82f), Triple("18", 31f, 61f))

private fun DrawScope.numerals(measurer: TextMeasurer, style: TextStyle, at: (Float, Float) -> Offset) {
    for ((text, x, y) in NUMERALS) {
        val layout = measurer.measure(text, style)
        val p = at(x, y)
        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.firstBaseline))
    }
}

/** The Place board's dial colours. */
object DialLook {
    /** The open band, #178A4E. */
    val OPEN = Color(0xFF178A4E)

    /** Hours the data leaves unsure: the boards' star amber. */
    val UNKNOWN = Color(0xFFF2A71B)

    internal val TICK = Color(0xFF8C826F)
    internal val NUMERAL = Color(0xFF464C5A)
    internal val INK = Color(0xFF14161B)
    internal val HUB = Color(0xFFC8643A)
}
