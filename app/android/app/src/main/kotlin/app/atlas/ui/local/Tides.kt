package app.atlas.ui.local

import app.atlas.providers.MarineForecast
import java.time.Duration
import java.time.Instant

/** The tide at a place now: how far through its cycle it is and when the next high and low come. */
class TideNow(val nextHigh: Instant?, val nextLow: Instant?, val lastHigh: Instant?, val rising: Boolean, val rangeM: Double) {
    /** How far through the cycle from one high water to the next, 0 at high water, 0.5 at low. */
    fun phase(now: Instant): Double {
        val high = lastHigh ?: return 0.0
        return (Duration.between(high, now).seconds / (TIDAL_PERIOD_HOURS * 3600.0)).coerceIn(0.0, 1.0)
    }

    companion object {
        /** The lunar semi-diurnal cycle of most coasts: 12 h 25 min from high water to high water. */
        const val TIDAL_PERIOD_HOURS = 12.42

        /** Tides are only worth a clock where the sea level moves by more than this many metres in the forecast. */
        const val MIN_RANGE_M = 0.4

        /**
         * Finds the highs and lows in the hourly sea level of [f] around [now]; null when the level hardly moves (inland, a
         * lake, a sea without tides) or the model gave no level.
         */
        fun of(f: MarineForecast?, now: Instant): TideNow? {
            val hours = f?.hours?.filter { it.seaLevel.isFinite() }?.sortedBy { it.time } ?: return null
            if (hours.size < 6) return null
            val range = hours.maxOf { it.seaLevel } - hours.minOf { it.seaLevel }
            if (range < MIN_RANGE_M) return null
            val highs = ArrayList<Instant>()
            val lows = ArrayList<Instant>()
            for (i in 1 until hours.size - 1) {
                val a = hours[i - 1].seaLevel
                val b = hours[i].seaLevel
                val c = hours[i + 1].seaLevel
                // A turning point between three hourly values; its time refined by the parabola through them.
                val denom = a - 2 * b + c
                val shift = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
                val at = hours[i].time.plusSeconds((shift * 3600).toLong())
                if (b > a && b >= c) highs += at else if (b < a && b <= c) lows += at
            }
            val nextHigh = highs.firstOrNull { it.isAfter(now) }
            val nextLow = lows.firstOrNull { it.isAfter(now) }
            val lastHigh = highs.lastOrNull { !it.isAfter(now) }
            val rising = if (nextHigh != null && nextLow != null) nextHigh.isBefore(nextLow) else nextHigh != null
            return TideNow(nextHigh, nextLow, lastHigh, rising, range)
        }
    }
}
