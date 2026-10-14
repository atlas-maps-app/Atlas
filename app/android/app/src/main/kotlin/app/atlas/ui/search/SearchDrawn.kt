package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Symbol
import kotlin.math.cos
import kotlin.math.sin

/** The star of ratings: five points, filled to [fill] (0..1) from the left in [colour] over a pale track. */
fun DrawScope.star(centre: Offset, radius: Float, fill: Float, colour: Color, track: Color) {
    val p = starPath(centre, radius)
    drawPath(p, track)
    if (fill > 0f) {
        val left = centre.x - radius
        clipRect(left = left, top = centre.y - radius, right = left + 2 * radius * fill.coerceIn(0f, 1f), bottom = centre.y + radius) {
            drawPath(p, colour)
        }
    }
}

private fun starPath(c: Offset, r: Float): Path = Path().apply {
    val inner = r * 0.48f
    for (i in 0 until 10) {
        val a = Math.toRadians(-90.0 + i * 36.0)
        val rr = if (i % 2 == 0) r else inner
        val x = c.x + (rr * cos(a)).toFloat()
        val y = c.y + (rr * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A row of five stars showing [value] of 5, as on the Place board. */
@Composable
fun Stars(value: Double, modifier: Modifier = Modifier, size: Dp = 14.dp, colour: Color = Color(0xFFF2B33D)) {
    val track = Atlas.colors.line
    Canvas(modifier.size(width = size * 5 + 2.dp * 4, height = size)) {
        val r = this.size.height / 2f
        val gap = 2.dp.toPx()
        for (i in 0 until 5) {
            val cx = r + i * (2 * r + gap)
            star(Offset(cx, r), r, (value - i).toFloat().coerceIn(0f, 1f), colour, track)
        }
    }
}

/** Five big stars to tap, for writing a review: [stars] of them lit. */
@Composable
fun StarPicker(stars: Int, onPick: (Int) -> Unit, describe: (Int) -> String, modifier: Modifier = Modifier) {
    val track = Atlas.colors.line
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..5) {
            Canvas(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) { onPick(i) }
                    .semantics { contentDescription = describe(i) },
            ) {
                star(Offset(size.width / 2, size.height / 2), size.minDimension * 0.42f, if (i <= stars) 1f else 0f, Color(0xFFF2B33D), track)
            }
        }
    }
}

/**
 * A fact's symbol in its state: ink for yes, amber for in part, green for "only", and for no the symbol greyed with a
 * stroke through it (as "no Wi-Fi" on the Place board).
 */
@Composable
fun FactSymbol(symbol: String, struck: Boolean, tint: Color, description: String, size: Dp = 20.dp) {
    val paper = Atlas.colors.card
    Box(Modifier.size(size + 4.dp).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Symbol(symbol, tint = tint, size = size)
        if (struck) {
            Canvas(Modifier.size(size)) {
                val w = 2.dp.toPx()
                drawLine(paper, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = w * 2.4f, cap = StrokeCap.Round)
                drawLine(tint, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

/** A round action under the Place board's route button: a symbol on pale blue. */
@Composable
fun RoundAction(symbol: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val c = Atlas.colors
    Box(
        modifier.size(size).clip(CircleShape).background(c.goSoft).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = c.go, size = size * 0.42f)
    }
}

/** A dashed circle, the "+" of the query builder's chip row. */
fun DrawScope.dashedCircle(colour: Color, width: Float) {
    val r = size.minDimension / 2f - width
    val n = 14
    for (i in 0 until n) {
        drawArc(
            colour, startAngle = i * 360f / n, sweepAngle = 360f / n * 0.55f, useCenter = false,
            topLeft = Offset(center.x - r, center.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = Stroke(width),
        )
    }
}

/** Two overlapping rounded squares: "copy", drawn in [tint] in a [size] square. */
@Composable
fun CopyGlyph(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val s = 1.6f * w / 16f
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.14f)
        drawRoundRect(tint, Offset(w * 0.34f, w * 0.34f), androidx.compose.ui.geometry.Size(w * 0.58f, w * 0.58f), r, style = Stroke(s))
        val p = Path().apply {
            moveTo(w * 0.22f, w * 0.66f)
            lineTo(w * 0.16f, w * 0.66f)
            quadraticTo(w * 0.08f, w * 0.66f, w * 0.08f, w * 0.58f)
            lineTo(w * 0.08f, w * 0.16f)
            quadraticTo(w * 0.08f, w * 0.08f, w * 0.16f, w * 0.08f)
            lineTo(w * 0.58f, w * 0.08f)
            quadraticTo(w * 0.66f, w * 0.08f, w * 0.66f, w * 0.16f)
            lineTo(w * 0.66f, w * 0.22f)
        }
        drawPath(p, tint, style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** The quest amber of the Place board: a pale note by day, a deep one at night. */
@Composable
internal fun questPaper(): Color = if (Atlas.colors.isDark) Color(0xFF3A3119) else Color(0xFFFFF3D6)

/** The ink on [questPaper]. */
@Composable
internal fun questInk(): Color = if (Atlas.colors.isDark) Color(0xFFF2C66D) else Color(0xFF8A6A10)
