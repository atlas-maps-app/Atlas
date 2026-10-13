package app.atlas.geo

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Which units to measure in. Imperial UK uses miles with yards for short distances. */
enum class UnitSystem {
    METRIC, IMPERIAL, IMPERIAL_UK, NAUTICAL;

    companion object {
        /** The usual system for a locale's country: the US, Liberia and Myanmar imperial, the UK miles and yards. */
        fun forLocale(locale: Locale): UnitSystem = when (locale.country) {
            "US", "LR", "MM" -> IMPERIAL
            "GB" -> IMPERIAL_UK
            else -> METRIC
        }
    }
}

/** A unit with its symbol and its size in the base unit (metres, metres per second or seconds). */
enum class QuantityUnit(val symbol: String, val base: Double) {
    METRE("m", 1.0),
    KILOMETRE("km", 1000.0),
    FOOT("ft", 0.3048),
    YARD("yd", 0.9144),
    MILE("mi", 1609.344),
    NAUTICAL_MILE("nm", 1852.0),
    METRE_PER_SECOND("m/s", 1.0),
    KILOMETRE_PER_HOUR("km/h", 1 / 3.6),
    MILE_PER_HOUR("mph", 0.44704),
    KNOT("kn", 1852.0 / 3600),
    SECOND("s", 1.0),
    MINUTE("min", 60.0),
    HOUR("h", 3600.0),
    DAY("d", 86400.0),
}

/**
 * A number with its unit, already rounded the way people read and say it: "120 m", "1.2 km", "0.3 mi", "2.1 nm",
 * "14 kn", or for durations a chain such as 1 h then 25 min ([next]). Not UI text: the UI and the voice word it.
 */
data class Quantity(val value: Double, val unit: QuantityUnit, val decimals: Int = 0, val next: Quantity? = null) {
    /** The value in the base unit (metres, metres per second or seconds), [next] included. */
    val baseValue: Double get() = value * unit.base + (next?.baseValue ?: 0.0)

    /** "1.2 km", "1,2 km" in Dutch, "1 h 25 min": the number in [locale]'s digits, then the unit symbol. */
    fun format(locale: Locale = Locale.ROOT): String {
        val nf = NumberFormat.getNumberInstance(locale)
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        nf.isGroupingUsed = true
        val head = "${nf.format(value)} ${unit.symbol}"
        return if (next == null) head else "$head ${next.format(locale)}"
    }

    override fun toString(): String = format(Locale.ROOT)

    companion object {
        /**
         * A distance for display, or with [forSpeech] in the coarser steps a voice uses ("in 350 metres",
         * "in a quarter mile"). Switches to the larger unit where people do (1 km, 0.1 mi, 0.1 nm).
         */
        fun distance(metres: Double, system: UnitSystem, forSpeech: Boolean = false): Quantity {
            val m = abs(metres)
            return when (system) {
                UnitSystem.METRIC -> metric(m, forSpeech)
                UnitSystem.IMPERIAL -> imperial(m, QuantityUnit.FOOT, forSpeech)
                UnitSystem.IMPERIAL_UK -> imperial(m, QuantityUnit.YARD, forSpeech)
                UnitSystem.NAUTICAL -> nautical(m, forSpeech)
            }
        }

        /** A speed: km/h, mph or knots, with one decimal below 10. */
        fun speed(metresPerSecond: Double, system: UnitSystem): Quantity {
            val unit = when (system) {
                UnitSystem.METRIC -> QuantityUnit.KILOMETRE_PER_HOUR
                UnitSystem.IMPERIAL, UnitSystem.IMPERIAL_UK -> QuantityUnit.MILE_PER_HOUR
                UnitSystem.NAUTICAL -> QuantityUnit.KNOT
            }
            val v = abs(metresPerSecond) / unit.base
            return if (round(v, 0.1) < 10) Quantity(round(v, 0.1), unit, 1) else Quantity(round(v, 1.0), unit)
        }

        /** A height, depth or clearance: metres (one decimal below 10) or feet. */
        fun height(metres: Double, system: UnitSystem): Quantity = when (system) {
            UnitSystem.IMPERIAL, UnitSystem.IMPERIAL_UK -> Quantity(round(metres / QuantityUnit.FOOT.base, 1.0), QuantityUnit.FOOT)
            else -> if (abs(round(metres, 0.1)) < 10) Quantity(round(metres, 0.1), QuantityUnit.METRE, 1)
            else Quantity(round(metres, 1.0), QuantityUnit.METRE)
        }

        /** A duration: seconds under a minute, minutes under an hour, then hours and minutes, then days and hours. */
        fun duration(seconds: Double): Quantity {
            val s = abs(seconds)
            if (s < 59.5) return Quantity(round(s, 1.0), QuantityUnit.SECOND)
            val minutes = (s / 60).roundToLong()
            if (minutes < 60) return Quantity(minutes.toDouble(), QuantityUnit.MINUTE)
            if (minutes < 24 * 60) {
                val h = minutes / 60
                val min = minutes % 60
                return Quantity(h.toDouble(), QuantityUnit.HOUR, next = if (min > 0) Quantity(min.toDouble(), QuantityUnit.MINUTE) else null)
            }
            val hours = (s / 3600).roundToLong()
            val d = hours / 24
            val h = hours % 24
            return Quantity(d.toDouble(), QuantityUnit.DAY, next = if (h > 0) Quantity(h.toDouble(), QuantityUnit.HOUR) else null)
        }

        private fun metric(m: Double, speech: Boolean): Quantity {
            val small = when {
                m < 10 -> if (speech) round(m, 5.0) else round(m, 1.0)
                m < 100 -> round(m, if (speech) 10.0 else 5.0)
                else -> round(m, if (speech) 50.0 else 10.0)
            }
            if (small < 1000) return Quantity(small, QuantityUnit.METRE)
            return large(m / 1000, QuantityUnit.KILOMETRE, speech)
        }

        private fun imperial(m: Double, shortUnit: QuantityUnit, speech: Boolean): Quantity {
            val miles = m / QuantityUnit.MILE.base
            if (miles < 0.1) {
                val v = m / shortUnit.base
                val step = when {
                    v < 10 -> if (speech) 5.0 else 1.0
                    v < 100 -> 10.0
                    else -> if (speech) 50.0 else 10.0
                }
                return Quantity(round(v, step), shortUnit)
            }
            if (speech && miles < 1) {
                // Quarters, as people say them; the guide words 0.25 as "a quarter mile".
                val q = round(miles, 0.25).coerceAtLeast(0.25)
                return if (q >= 1) Quantity(1.0, QuantityUnit.MILE) else Quantity(q, QuantityUnit.MILE, 2)
            }
            return large(miles, QuantityUnit.MILE, speech)
        }

        private fun nautical(m: Double, speech: Boolean): Quantity {
            val nm = m / QuantityUnit.NAUTICAL_MILE.base
            if (nm < 0.1) return metric(m, speech)
            return large(nm, QuantityUnit.NAUTICAL_MILE, speech)
        }

        // One decimal up to 10 (halves when spoken, whole numbers without a decimal), whole units beyond.
        private fun large(v: Double, unit: QuantityUnit, speech: Boolean): Quantity {
            val r = round(v, if (speech) 0.5 else 0.1)
            if (r >= 10) return Quantity(round(v, 1.0), unit)
            return Quantity(r, unit, if (speech && r == floor(r)) 0 else 1)
        }

        private fun round(v: Double, step: Double): Double {
            val r = (v / step).roundToLong() * step
            // Strip binary noise such as 1.2000000000000002.
            return (r * 1e6).roundToLong() / 1e6
        }
    }
}
