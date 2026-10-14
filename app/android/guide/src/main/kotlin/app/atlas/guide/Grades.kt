package app.atlas.guide

import kotlin.math.abs

/** A climb (positive [rise]) or descent between two distances along a route. */
data class Grade(val fromAlong: Double, val toAlong: Double, val rise: Double) {
    val length: Double get() = toAlong - fromAlong
    /** Average grade in percent, negative downhill. */
    val percent: Double get() = if (length > 0) rise / length * 100 else 0.0
}

/** Finds sustained climbs and descents in an elevation profile. */
object Grades {

    /**
     * Climbs and descents of at least [minPercent] average grade over at least [minLength] metres. A run ends when
     * the profile turns back by more than [hysteresisM] metres, so small bumps (and elevation noise) do not split it;
     * flat ground before and after a slope is not counted in it. [along] and [elevations] are per point.
     */
    fun find(along: DoubleArray, elevations: DoubleArray, minPercent: Double, minLength: Double, hysteresisM: Double = 4.0): List<Grade> {
        val n = minOf(along.size, elevations.size)
        if (n < 2) return emptyList()
        val out = ArrayList<Grade>()
        var dir = 0 // 1 up, -1 down, 0 not yet known
        // The run starts at [anchor]; [first] is where it first reached its extreme value, [last] where it last held it.
        var anchor = 0
        var first = 0
        var last = 0
        var lowFirst = 0
        var lowLast = 0
        var highFirst = 0
        var highLast = 0
        fun close(from: Int, to: Int) {
            if (to <= from) return
            val g = Grade(along[from], along[to], elevations[to] - elevations[from])
            if (g.length >= minLength && abs(g.percent) >= minPercent) out += g
        }
        for (i in 1 until n) {
            val e = elevations[i]
            when (dir) {
                0 -> {
                    if (e < elevations[lowFirst]) { lowFirst = i; lowLast = i } else if (e == elevations[lowFirst]) lowLast = i
                    if (e > elevations[highFirst]) { highFirst = i; highLast = i } else if (e == elevations[highFirst]) highLast = i
                    // The first run starts where the ground last stood at its lowest (or highest), not at the very start.
                    if (e - elevations[lowFirst] > hysteresisM) { dir = 1; anchor = lowLast; first = i; last = i }
                    else if (elevations[highFirst] - e > hysteresisM) { dir = -1; anchor = highLast; first = i; last = i }
                }
                else -> {
                    val d = (e - elevations[first]) * dir
                    when {
                        d > 0 -> { first = i; last = i }
                        d == 0.0 -> last = i
                        -d > hysteresisM -> {
                            close(anchor, first)
                            anchor = last
                            dir = -dir
                            first = i
                            last = i
                        }
                    }
                }
            }
        }
        if (dir != 0) close(anchor, first)
        return out
    }
}
