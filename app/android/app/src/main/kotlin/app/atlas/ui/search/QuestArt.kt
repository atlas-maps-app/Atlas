package app.atlas.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.atlas.places.FactKind
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.drawSymbolCentred

/**
 * The thing a quest asks about, drawn: the part nobody has mapped is a ghost, dashed in amber with a "?" (the parasol
 * over a café table, the waves of a Wi-Fi router, the ramp at a door, the light of a street lamp), as the Quests board
 * draws a bench with a ghosted backrest.
 */
@Composable
fun QuestArt(kind: FactKind, symbol: String, description: String, modifier: Modifier = Modifier) {
    val c = Atlas.colors
    val ink = if (c.isDark) Color(0xFFE6E0D4) else Color(0xFF3B3A36)
    val ghost = questInk()
    val ground = c.cardMuted
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = size.minDimension
        val o = Offset((size.width - w) / 2, (size.height - w) / 2)
        // A soft floor for the object to stand on.
        drawOval(ground, Offset(o.x + w * 0.08f, o.y + w * 0.80f), Size(w * 0.84f, w * 0.12f))
        val art = Art(this, o, w, ink, ghost)
        when (kind) {
            FactKind.INTERNET -> art.router()
            FactKind.OUTDOOR_SEATING -> art.terrace()
            FactKind.WHEELCHAIR, FactKind.TOILETS_WHEELCHAIR -> art.doorway()
            FactKind.TOILETS -> art.toiletDoor()
            FactKind.LIT -> art.lamp()
            FactKind.TAKEAWAY -> art.cup()
            FactKind.DIET -> art.plate()
            else -> art.generic(symbol)
        }
    }
}

/** The drawings, in a [w]-wide square at [o]; solid in [ink], the unknown part dashed in [ghost]. */
private class Art(val d: DrawScope, val o: Offset, val w: Float, val ink: Color, val ghost: Color) {
    private val line = w * 0.022f
    private val dash = PathEffect.dashPathEffect(floatArrayOf(w * 0.04f, w * 0.03f))
    private fun p(x: Float, y: Float) = Offset(o.x + x * w, o.y + y * w)
    private fun ghostStroke() = Stroke(line, cap = StrokeCap.Round, pathEffect = dash)

    /** The amber "?" on a pale disc beside the unknown part. */
    fun question(x: Float, y: Float) = with(d) {
        val r = w * 0.085f
        drawCircle(ghost.copy(alpha = 0.18f), r, p(x, y))
        drawCircle(ghost, r, p(x, y), style = Stroke(line * 0.7f))
        // A question mark: a hook and a dot.
        val c = p(x, y)
        val k = r * 0.55f
        val hook = Path().apply {
            moveTo(c.x - k * 0.6f, c.y - k * 0.45f)
            cubicTo(c.x - k * 0.6f, c.y - k * 1.15f, c.x + k * 0.6f, c.y - k * 1.15f, c.x + k * 0.6f, c.y - k * 0.45f)
            cubicTo(c.x + k * 0.6f, c.y - k * 0.05f, c.x, c.y - k * 0.05f, c.x, c.y + k * 0.35f)
        }
        drawPath(hook, ghost, style = Stroke(line * 0.8f, cap = StrokeCap.Round))
        drawCircle(ghost, line * 0.6f, Offset(c.x, c.y + k * 0.85f))
    }

    fun router() = with(d) {
        // The box with its lights and two antennas.
        drawRoundRect(ink, p(0.30f, 0.62f), Size(w * 0.40f, w * 0.16f), CornerRadius(w * 0.03f))
        for (i in 0 until 4) drawCircle(Color(0xFF3CC47A), w * 0.012f, p(0.36f + i * 0.05f, 0.70f))
        drawLine(ink, p(0.36f, 0.62f), p(0.33f, 0.47f), line, StrokeCap.Round)
        drawLine(ink, p(0.64f, 0.62f), p(0.67f, 0.47f), line, StrokeCap.Round)
        // The waves nobody has mapped.
        for (k in 1..3) {
            val r = w * (0.08f + 0.075f * k)
            drawArc(ghost, 220f, 100f, false, Offset(p(0.5f, 0.52f).x - r, p(0.5f, 0.52f).y - r), Size(2 * r, 2 * r), style = ghostStroke())
        }
        question(0.80f, 0.24f)
    }

    fun terrace() = with(d) {
        // A round table and two chairs on the pavement.
        drawOval(ink, p(0.36f, 0.56f), Size(w * 0.28f, w * 0.06f))
        drawLine(ink, p(0.5f, 0.59f), p(0.5f, 0.82f), line * 1.2f, StrokeCap.Round)
        drawLine(ink, p(0.42f, 0.84f), p(0.58f, 0.84f), line, StrokeCap.Round)
        for (side in listOf(-1f, 1f)) {
            val x = 0.5f + side * 0.27f
            drawLine(ink, p(x, 0.52f), p(x, 0.84f), line, StrokeCap.Round)
            drawLine(ink, p(x, 0.68f), p(x - side * 0.10f, 0.68f), line, StrokeCap.Round)
            drawLine(ink, p(x - side * 0.10f, 0.68f), p(x - side * 0.10f, 0.84f), line, StrokeCap.Round)
        }
        // The parasol, a ghost: is there a terrace at all?
        drawLine(ghost, p(0.5f, 0.56f), p(0.5f, 0.24f), line, StrokeCap.Round)
        val canopy = Path().apply {
            moveTo(p(0.24f, 0.32f).x, p(0.24f, 0.32f).y)
            quadraticTo(p(0.5f, 0.10f).x, p(0.5f, 0.10f).y, p(0.76f, 0.32f).x, p(0.76f, 0.32f).y)
            close()
        }
        drawPath(canopy, ghost.copy(alpha = 0.10f))
        drawPath(canopy, ghost, style = ghostStroke())
        question(0.84f, 0.18f)
    }

    fun doorway() = with(d) {
        // The door and its step.
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF8A5A2B), Color(0xFF6B4320)), p(0f, 0.2f).y, p(0f, 0.74f).y), p(0.38f, 0.20f), Size(w * 0.26f, w * 0.54f), CornerRadius(w * 0.02f))
        drawRect(ink, p(0.36f, 0.18f), Size(w * 0.30f, w * 0.56f), style = Stroke(line))
        drawCircle(Color(0xFFE2B33D), w * 0.015f, p(0.60f, 0.48f))
        drawRect(ink, p(0.30f, 0.74f), Size(w * 0.42f, w * 0.08f))
        // A ramp nobody has mapped.
        val ramp = Path().apply {
            moveTo(p(0.72f, 0.74f).x, p(0.72f, 0.74f).y)
            lineTo(p(0.94f, 0.82f).x, p(0.94f, 0.82f).y)
            lineTo(p(0.72f, 0.82f).x, p(0.72f, 0.82f).y)
            close()
        }
        drawPath(ramp, ghost.copy(alpha = 0.12f))
        drawPath(ramp, ghost, style = ghostStroke())
        drawSymbolCentred("ui-wheelchair", p(0.18f, 0.62f), w * 0.2f, ghost)
        question(0.84f, 0.30f)
    }

    fun toiletDoor() = with(d) {
        drawRoundRect(Color(0xFF5D7A8C), p(0.34f, 0.18f), Size(w * 0.32f, w * 0.62f), CornerRadius(w * 0.02f))
        drawRect(ink, p(0.32f, 0.16f), Size(w * 0.36f, w * 0.66f), style = Stroke(line))
        drawCircle(Color(0xFFE2B33D), w * 0.015f, p(0.62f, 0.50f))
        // The plaque nobody has mapped.
        drawRoundRect(ghost.copy(alpha = 0.15f), p(0.40f, 0.26f), Size(w * 0.20f, w * 0.14f), CornerRadius(w * 0.02f))
        drawRoundRect(ghost, p(0.40f, 0.26f), Size(w * 0.20f, w * 0.14f), CornerRadius(w * 0.02f), style = ghostStroke())
        drawSymbolCentred("ui-toilet", p(0.5f, 0.33f), w * 0.1f, ghost)
        question(0.82f, 0.22f)
    }

    fun lamp() = with(d) {
        drawLine(ink, p(0.42f, 0.84f), p(0.42f, 0.22f), line * 1.4f, StrokeCap.Round)
        drawLine(ink, p(0.42f, 0.22f), p(0.56f, 0.22f), line * 1.2f, StrokeCap.Round)
        drawRoundRect(ink, p(0.52f, 0.22f), Size(w * 0.10f, w * 0.07f), CornerRadius(w * 0.015f))
        // Its light: is it lit at night?
        val cone = Path().apply {
            moveTo(p(0.53f, 0.29f).x, p(0.53f, 0.29f).y)
            lineTo(p(0.40f, 0.82f).x, p(0.40f, 0.82f).y)
            lineTo(p(0.84f, 0.82f).x, p(0.84f, 0.82f).y)
            lineTo(p(0.61f, 0.29f).x, p(0.61f, 0.29f).y)
            close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(Color(0x66F2C66D), Color(0x00F2C66D)), p(0f, 0.29f).y, p(0f, 0.82f).y))
        drawPath(cone, ghost, style = ghostStroke())
        question(0.84f, 0.22f)
    }

    fun cup() = with(d) {
        val body = Path().apply {
            moveTo(p(0.38f, 0.34f).x, p(0.38f, 0.34f).y)
            lineTo(p(0.62f, 0.34f).x, p(0.62f, 0.34f).y)
            lineTo(p(0.58f, 0.82f).x, p(0.58f, 0.82f).y)
            lineTo(p(0.42f, 0.82f).x, p(0.42f, 0.82f).y)
            close()
        }
        drawPath(body, Color(0xFFD9C3A0))
        drawPath(body, ink, style = Stroke(line))
        drawRect(ink, p(0.40f, 0.52f), Size(w * 0.20f, w * 0.10f))
        // The lid: is it to go?
        drawRoundRect(ghost.copy(alpha = 0.15f), p(0.35f, 0.26f), Size(w * 0.30f, w * 0.08f), CornerRadius(w * 0.02f))
        drawRoundRect(ghost, p(0.35f, 0.26f), Size(w * 0.30f, w * 0.08f), CornerRadius(w * 0.02f), style = ghostStroke())
        question(0.80f, 0.24f)
    }

    fun plate() = with(d) {
        drawOval(Color.White, p(0.20f, 0.56f), Size(w * 0.60f, w * 0.22f))
        drawOval(ink, p(0.20f, 0.56f), Size(w * 0.60f, w * 0.22f), style = Stroke(line))
        drawOval(ink.copy(alpha = 0.25f), p(0.32f, 0.61f), Size(w * 0.36f, w * 0.12f), style = Stroke(line * 0.6f))
        // A leaf: is there a vegetarian choice?
        rotate(-30f, p(0.5f, 0.42f)) {
            val leaf = Path().apply {
                moveTo(p(0.36f, 0.42f).x, p(0.36f, 0.42f).y)
                quadraticTo(p(0.5f, 0.28f).x, p(0.5f, 0.28f).y, p(0.64f, 0.42f).x, p(0.64f, 0.42f).y)
                quadraticTo(p(0.5f, 0.56f).x, p(0.5f, 0.56f).y, p(0.36f, 0.42f).x, p(0.36f, 0.42f).y)
            }
            drawPath(leaf, Color(0x332F7D3A))
            drawPath(leaf, ghost, style = ghostStroke())
        }
        question(0.82f, 0.24f)
    }

    fun generic(symbol: String) = with(d) {
        val r = w * 0.26f
        drawCircle(ghost.copy(alpha = 0.10f), r, p(0.5f, 0.5f))
        drawCircle(ghost, r, p(0.5f, 0.5f), style = ghostStroke())
        drawSymbolCentred(symbol, p(0.5f, 0.5f), w * 0.26f, ink)
        question(0.80f, 0.24f)
    }
}
