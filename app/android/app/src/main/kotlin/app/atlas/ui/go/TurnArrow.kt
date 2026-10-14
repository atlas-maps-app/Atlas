package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.model.Lane
import app.atlas.model.ManeuverType
import app.atlas.model.Turn
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** What a maneuver looks like as an arrow: its type, its turn and, at roundabouts, the exit. */
data class ArrowShape(val type: ManeuverType, val turn: Turn, val exit: Int? = null)

/**
 * The turn arrows of the boards: a stem with a rounded corner and an open chevron head, stroked on the 24 grid (the
 * Ride and Drive boards' right and left turns as drawn there; the other turns in the same hand).
 */
internal object TurnArt {
    private fun stroked(vararg d: String, width: Float = 2.3f) =
        Art(0f, 0f, 24f, 24f, d.map { ArtPart(it, stroke = CURRENT, width = width, cap = StrokeCap.Round, join = StrokeJoin.Round) })

    val right = stroked("M6 21v-8a4 4 0 0 1 4-4h9", "M15 5l4 4-4 4")
    val left = right.mirrored
    val straight = stroked("M12 21V4", "M7.5 8.5L12 4l4.5 4.5")
    val slightRight = stroked("M8 21v-6.5a4 4 0 0 1 1.2-2.85L17 4.5", "M11.5 4.5H17V10")
    val slightLeft = slightRight.mirrored
    val sharpRight = stroked("M7 21V9a4.5 4.5 0 0 1 8.3-2.4L19 14", "M19 8.5V14h-5.5")
    val sharpLeft = sharpRight.mirrored
    /** A U-turn to the left, as right-hand traffic turns. */
    val uTurn = stroked("M16 21V10a4.5 4.5 0 0 0-9 0v5", "M3 11.5l4 4 4-4")
    val arrive = stroked("M7 21V4", "M7 4h11l-2.5 4L18 12H7")

    /** A roundabout: the stem in, the ring, and the exit leaving it at the turn's angle (from straight ahead). */
    fun roundabout(angle: Int): Art {
        val cx = 12.0
        val cy = 11.0
        val r = 4.0
        val a = Math.toRadians(angle.toDouble())
        val ex = cx + r * sin(a)
        val ey = cy - r * cos(a)
        val tx = cx + 9.5 * sin(a)
        val ty = cy - 9.5 * cos(a)
        // The chevron at the tip, its arms 4.5 long at ±45° to the way out.
        val back = a + Math.PI
        val h1x = tx + 4.0 * sin(back + Math.PI / 4)
        val h1y = ty - 4.0 * cos(back + Math.PI / 4)
        val h2x = tx + 4.0 * sin(back - Math.PI / 4)
        val h2y = ty - 4.0 * cos(back - Math.PI / 4)
        fun n(x: Double) = String.format(Locale.ROOT, "%.2f", x)
        return Art(0f, 0f, 24f, 24f, listOf(
            ArtPart("M12 22v-7", stroke = CURRENT, width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            ArtPart("M8 11a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", stroke = CURRENT, width = 2.3f, alpha = 0.55f),
            ArtPart("M${n(ex)} ${n(ey)}L${n(tx)} ${n(ty)}", stroke = CURRENT, width = 2.3f, cap = StrokeCap.Round),
            ArtPart("M${n(h1x)} ${n(h1y)}L${n(tx)} ${n(ty)}L${n(h2x)} ${n(h2y)}", stroke = CURRENT, width = 2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        ))
    }

    fun of(shape: ArrowShape): Art = when {
        shape.type == ManeuverType.ARRIVE || shape.type == ManeuverType.WAYPOINT -> arrive
        shape.type == ManeuverType.ROUNDABOUT || shape.type == ManeuverType.ROUNDABOUT_EXIT -> roundabout(shape.turn.angle)
        shape.type == ManeuverType.U_TURN || shape.turn == Turn.U_TURN -> uTurn
        else -> when (shape.turn) {
            Turn.STRAIGHT -> straight
            Turn.SLIGHT_RIGHT -> slightRight
            Turn.RIGHT -> right
            Turn.SHARP_RIGHT -> sharpRight
            Turn.SLIGHT_LEFT -> slightLeft
            Turn.LEFT -> left
            Turn.SHARP_LEFT -> sharpLeft
            Turn.U_TURN -> uTurn
        }
    }
}

/** The big turn arrow of the guidance screens, in [colour]; [weight] thickens it (1 is the boards' 2.3 on 24). */
@Composable
fun TurnArrow(shape: ArrowShape, modifier: Modifier = Modifier, size: Dp = 160.dp, colour: Color = Color.White, weight: Float = 1f, contentDescription: String? = null) {
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        drawTurnArrow(shape, colour, weight)
    }
}

/** Draws [shape] into the whole draw area. */
fun DrawScope.drawTurnArrow(shape: ArrowShape, colour: Color, weight: Float = 1f) {
    val art = TurnArt.of(shape)
    drawArt(if (weight == 1f) art else art.weighted(weight), tint = colour)
}

/** [this] drawing with every stroke [k] times as wide. */
internal fun Art.weighted(k: Float): Art {
    val a = Art(x, y, w, h, parts.map { p ->
        ArtPart(p.d, p.fill, p.stroke, p.width * k, p.cap, p.join, p.dash, p.alpha, p.gradient, p.dx, p.dy)
    })
    a.flip = flip
    return a
}

/** The lanes' arrows as the Drive board draws them: the lane bent left (or right), or straight up. */
private object LaneArt {
    val left = Art(0f, 0f, 20f, 24f, listOf(ArtPart("M12 23v-9a4 4 0 0 0-4-4H4M7 6.5L3.5 10 7 13.5", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)))
    val right = left.mirrored
    val straight = Art(0f, 0f, 16f, 24f, listOf(ArtPart("M8 23V3M3.5 7.5L8 3l4.5 4.5", stroke = CURRENT, width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)))

    fun of(t: Turn): Art = when {
        t == Turn.U_TURN || t.left -> left
        t.right -> right
        else -> straight
    }
}

/**
 * The lanes at the next junction, as on the Drive board: a dark box with one arrow per lane, white for the lanes that
 * lead along the route and dim for the others, dashed lines between them.
 */
@Composable
fun LaneArrows(lanes: List<Lane>, modifier: Modifier = Modifier, on: Color = Color.White, off: Color = Color(0xFF4A5470)) {
    if (lanes.isEmpty()) return
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A2238)).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEachIndexed { i, lane ->
            if (i > 0) Canvas(Modifier.width(1.dp).height(36.dp)) {
                drawLine(Color(0xFF5A6480), Offset(0f, 0f), Offset(0f, size.height), size.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
            val turn = lane.turns.firstOrNull { it != Turn.STRAIGHT } ?: Turn.STRAIGHT
            val art = LaneArt.of(turn)
            Box(Modifier.size(width = 30.dp, height = 36.dp), contentAlignment = Alignment.Center) {
                ArtIcon(art, size = 24.dp, tint = if (lane.valid) on else off)
            }
        }
    }
}
