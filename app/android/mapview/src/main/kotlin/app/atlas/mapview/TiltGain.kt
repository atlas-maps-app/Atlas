package app.atlas.mapview

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * How far the tilting fingers turn the pitch, by how fast they go: [FINE] degrees a dp for slow, small moves (a degree
 * every 12.5 dp, so a careful drag sets the tilt to the degree), rising with the speed's logarithm to [FAST] for a
 * quick sweep (the whole 70° in under 180 dp). The pitch follows each move at once, by that move times the gain, nothing held
 * back or smoothed: only the gain hangs on the speed, the fingers' average over their moves of the last [WINDOW_MS].
 * Not thread-safe: the view's thread.
 */
internal class TiltGain {
    private val times = LongArray(SIZE)
    private val moved = FloatArray(SIZE)
    private val spans = LongArray(SIZE)
    private var n = 0
    private var total = 0
    private var head = 0
    private var last = 0L

    /** A tilt begins, its fingers' move counted from [time]. */
    fun reset(time: Long) {
        n = 0
        total = 0
        head = 0
        last = time
    }

    /** The degrees for a move of [dp] (dp up, positive toward the horizon) reported at [time]. */
    fun degrees(dp: Float, time: Long): Double {
        // The first move carries all of it since the tilt began; later ones after a pause count as a frame's worth at
        // most, so a sweep after a rest is quick at once.
        val span = (time - last).coerceIn(1L, if (total == 0) Long.MAX_VALUE else MAX_SPAN_MS)
        last = max(last, time)
        times[head] = time
        moved[head] = abs(dp)
        spans[head] = span
        head = (head + 1) % SIZE
        if (n < SIZE) n++
        total++
        return dp * gain(speed(time))
    }

    /**
     * dp a second over the moves of the last [WINDOW_MS], or the newest alone over the time it took. The tilt's first
     * move, gathered while the fingers said what they do, counts for its own gain only.
     */
    private fun speed(now: Long): Double {
        var d = 0.0
        var t = 0L
        for (k in 0 until n) {
            val i = (head - 1 - k + SIZE) % SIZE
            if (k > 0 && (now - times[i] >= WINDOW_MS || k == total - 1)) break
            d += moved[i]
            t += spans[i]
        }
        return d / t * 1000.0
    }

    companion object {
        /** Degrees a dp at [SLOW] and below. */
        const val FINE = 0.08

        /** Degrees a dp at [QUICK] and above. */
        const val FAST = 0.40

        /** The fingers' speeds, dp a second, between which the gain rises. */
        const val SLOW = 50.0
        const val QUICK = 600.0

        /** The moves the speed is taken over, ms. */
        const val WINDOW_MS = 60L

        /** The longest a move after the first counts for, ms (a frame at 50 Hz): a pause before it is no slowness of its own. */
        const val MAX_SPAN_MS = 20L

        private const val SIZE = 32

        /** Degrees a dp at [speed] dp a second: [FINE] to [FAST], smoothly in the speed's logarithm. */
        fun gain(speed: Double): Double {
            if (speed <= SLOW) return FINE
            if (speed >= QUICK) return FAST
            val u = ln(speed / SLOW) / ln(QUICK / SLOW)
            return FINE + (FAST - FINE) * u * u * (3 - 2 * u)
        }
    }
}
