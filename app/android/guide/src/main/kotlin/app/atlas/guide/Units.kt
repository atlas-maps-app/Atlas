package app.atlas.guide

import app.atlas.geo.UnitSystem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** The grammatical context a distance is spoken in: alone, after "in" (German and Finnish change case), or after "for". */
enum class DistanceForm { BARE, IN, FOR }

/**
 * A quantity as the voice says it: a unit key of the phrase tables ("m", "km", "mi", "yd", "cable"…), its number,
 * how many decimals to speak, and an optional idiom ("quarter" for "a quarter mile").
 */
data class Spoken(val unit: String, val value: Double, val decimals: Int, val idiom: String? = null)

/** CLDR plural categories for the vendored languages, enough for the integers and one-decimal numbers the guide speaks. */
object Plurals {
    /** "one", "few", "many" or "other" for [n] spoken with [decimals] fraction digits, in osrm language [code]. */
    fun category(code: String, n: Double, decimals: Int): String {
        val i = floor(abs(n)).toLong()
        val v = decimals
        return when (code) {
            "fr", "pt-BR" -> if (i == 0L || i == 1L) "one" else "other"
            "ru", "uk" -> when {
                v != 0 -> "other"
                i % 10 == 1L && i % 100 != 11L -> "one"
                i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
                else -> "many"
            }
            "pl" -> when {
                v != 0 -> "other"
                i == 1L -> "one"
                i % 10 in 2..4 && i % 100 !in 12..14 -> "few"
                else -> "many"
            }
            "ja", "zh-Hans" -> "other"
            else -> if (i == 1L && v == 0) "one" else "other"
        }
    }
}

/**
 * Rounding of distances, heights and the rest to what a voice should say. Distances round to the steps navigation voices
 * use (10 m close by, then 50 m, then 100 m; half kilometres up to 10 km), and into the idioms people use: "a quarter
 * mile", "half a mile", "2 cables".
 */
object Rounding {
    private const val FOOT = 0.3048
    private const val YARD = 0.9144
    private const val MILE = 1609.344
    private const val NMI = 1852.0
    private const val CABLE = NMI / 10

    /**
     * The distance to speak for [metres] in [units]. Short distances are metres, feet (US) or yards (UK); at sea they are
     * metres or feet ([feet]), then cables, then nautical miles. [precise] keeps tens of metres up to 300 m, for people
     * on foot finding a place by ear.
     */
    fun distance(
        metres: Double,
        units: UnitSystem,
        feet: Boolean = units != UnitSystem.METRIC && units != UnitSystem.NAUTICAL,
        precise: Boolean = false,
    ): Spoken {
        val d = metres.coerceAtLeast(0.0)
        return when (units) {
            UnitSystem.METRIC -> metric(d, precise)
            UnitSystem.IMPERIAL, UnitSystem.IMPERIAL_UK -> {
                val mi = d / MILE
                when {
                    mi < 0.2 -> if (units == UnitSystem.IMPERIAL_UK) Spoken("yd", roundShort(d / YARD, precise), 0) else Spoken("ft", roundShort(d / FOOT, precise), 0)
                    mi < 0.375 -> Spoken("mi", 0.25, 2, "quarter")
                    mi < 0.625 -> Spoken("mi", 0.5, 1, "half")
                    mi < 0.875 -> Spoken("mi", 0.75, 2, "three_quarters")
                    mi < 10 -> half(mi, "mi")
                    else -> Spoken("mi", mi.roundToLong().toDouble(), 0)
                }
            }
            UnitSystem.NAUTICAL -> {
                val nm = d / NMI
                when {
                    d < CABLE * 0.75 -> if (feet) Spoken("ft", roundShort(d / FOOT, precise), 0) else Spoken("m", roundTo(d, 10.0).coerceAtLeast(10.0), 0)
                    nm < 0.45 -> Spoken("cable", (d / CABLE).roundToLong().coerceIn(1, 4).toDouble(), 0)
                    nm < 0.65 -> Spoken("nmi", 0.5, 1, "half")
                    nm < 0.95 -> Spoken("cable", (d / CABLE).roundToLong().coerceIn(7, 9).toDouble(), 0)
                    nm < 10 -> half(nm, "nmi")
                    else -> Spoken("nmi", nm.roundToLong().toDouble(), 0)
                }
            }
        }
    }

    private fun metric(d: Double, precise: Boolean): Spoken {
        val m = when {
            d < 100 || precise && d < 300 -> roundTo(d, 10.0).coerceAtLeast(10.0)
            d < 300 || precise -> roundTo(d, 50.0)
            else -> roundTo(d, 100.0)
        }
        if (m < 1000) return Spoken("m", m, 0)
        val km = d / 1000
        return if (km < 10) half(km, "km").let { if (it.value < 1) Spoken("km", 1.0, 0) else it } else Spoken("km", km.roundToLong().toDouble(), 0)
    }

    /** Feet or yards: tens below 100 (below 500 when [precise]), fifties below 500, hundreds above. */
    private fun roundShort(x: Double, precise: Boolean): Double = when {
        x < 100 || precise && x < 500 -> roundTo(x, 10.0).coerceAtLeast(10.0)
        x < 500 -> roundTo(x, 50.0)
        else -> roundTo(x, 100.0)
    }

    private fun half(x: Double, unit: String): Spoken {
        val h = (x * 2).roundToLong() / 2.0
        return if (h == floor(h)) Spoken(unit, h, 0) else Spoken(unit, h, 1)
    }

    /** A clearance or vehicle height: metres to one decimal, or feet (and inches below 30 feet) when [feet]. */
    fun height(metres: Double, feet: Boolean): List<Spoken> =
        if (feet) {
            val totalIn = (metres / FOOT * 12).roundToLong()
            val ft = totalIn / 12
            val inch = totalIn % 12
            if (ft >= 30 || inch == 0L) listOf(Spoken("ft", (metres / FOOT).roundToLong().toDouble(), 0))
            else listOf(Spoken("ft", ft.toDouble(), 0), Spoken("inch", inch.toDouble(), 0))
        } else {
            listOf(oneDecimal(metres, "m"))
        }

    /** A small difference in height (a margin): a decimal of a metre, or inches (feet from two feet on) when [feet]. */
    fun margin(metres: Double, feet: Boolean): Spoken =
        if (feet) {
            val inches = (metres / FOOT * 12).roundToLong()
            if (inches >= 24) Spoken("ft", (metres / FOOT).roundToLong().toDouble(), 0) else Spoken("inch", inches.coerceAtLeast(1).toDouble(), 0)
        } else {
            oneDecimal(metres, "m")
        }

    fun tonnes(t: Double): Spoken = oneDecimal(t, "t")

    fun oneDecimal(x: Double, unit: String): Spoken {
        val r = (x * 10).roundToLong() / 10.0
        return if (r == floor(r)) Spoken(unit, r, 0) else Spoken(unit, r, 1)
    }

    /** Whole minutes, or hours and minutes from an hour on. */
    fun duration(seconds: Double): List<Spoken> {
        val min = (seconds / 60).roundToLong().coerceAtLeast(1)
        if (min < 60) return listOf(Spoken("min", min.toDouble(), 0))
        val h = min / 60
        val m = min % 60
        return if (m == 0L) listOf(Spoken("h", h.toDouble(), 0)) else listOf(Spoken("h", h.toDouble(), 0), Spoken("min", m.toDouble(), 0))
    }

    private fun roundTo(x: Double, step: Double) = (x / step).roundToLong() * step
}
