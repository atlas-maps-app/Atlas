package app.atlas.ui.mine

import app.atlas.guide.Cue
import app.atlas.guide.Haptics
import app.atlas.model.ManeuverType
import app.atlas.model.Turn
import kotlin.math.cos
import kotlin.math.sin

/** What the wrist shows and what it makes you feel, for one next maneuver. */
object WatchModel {
    /** The glyphs the watch face draws for a maneuver. */
    enum class Glyph { RIGHT, LEFT, STRAIGHT, U_TURN, ARRIVE }

    /** The glyph for a maneuver of [type] turning [turn]. */
    fun glyph(type: ManeuverType?, turn: Turn?): Glyph = when {
        type == ManeuverType.ARRIVE -> Glyph.ARRIVE
        turn == Turn.U_TURN || type == ManeuverType.U_TURN -> Glyph.U_TURN
        turn != null && turn.right -> Glyph.RIGHT
        turn != null && turn.left -> Glyph.LEFT
        else -> Glyph.STRAIGHT
    }

    /** The rhythm that goes with a glyph on the legend: dots (short) and bars (long), as the board draws them. */
    fun rhythm(g: Glyph): List<Boolean> = when (g) {
        Glyph.LEFT -> listOf(false, false)
        Glyph.RIGHT -> listOf(false, false, false)
        Glyph.U_TURN -> listOf(false, true, false)
        Glyph.ARRIVE -> listOf(true, true)
        Glyph.STRAIGHT -> listOf(true)
    }

    /** The vibration for a glyph: the guide's own patterns. */
    fun haptic(g: Glyph): Cue.Haptic = when (g) {
        Glyph.LEFT -> Haptics.left()
        Glyph.RIGHT -> Haptics.right()
        Glyph.U_TURN -> Haptics.uTurn()
        Glyph.ARRIVE -> Haptics.arrive()
        Glyph.STRAIGHT -> Haptics.straight()
    }

    /** How many dots the watch shows over the arrow: the pulses of the turn's vibration. */
    fun dots(g: Glyph): Int = haptic(g).pulses

    /** The 60 ticks of the bezel as (x1, y1, x2, y2) in the face's 300-unit square: long every fifth, from the top clockwise. */
    fun ticks(): List<FloatArray> = (0 until 60).map { i ->
        val a = Math.toRadians(i * 6.0 - 90.0)
        val inner = if (i % 5 == 0) 120f else 124f
        floatArrayOf(150f + 128f * cos(a).toFloat(), 150f + 128f * sin(a).toFloat(), 150f + inner * cos(a).toFloat(), 150f + inner * sin(a).toFloat())
    }

    /** The end of the progress arc after [fraction] of the ring (r 112) from the top, clockwise: x, y, and whether it is the long way round. */
    fun arcEnd(fraction: Float): Triple<Float, Float, Boolean> {
        val f = fraction.coerceIn(0f, 0.9999f)
        val a = Math.toRadians(f * 360.0 - 90.0)
        return Triple(150f + 112f * cos(a).toFloat(), 150f + 112f * sin(a).toFloat(), f > 0.5f)
    }
}
