package app.atlas.ui.go

import app.atlas.services.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A number and its unit, kept apart so the screens can set the unit smaller ("120 m"). */
data class Amount(val value: String, val unit: String) {
    override fun toString() = if (unit.isEmpty()) value else "$value $unit"
}

/**
 * How the go screens write distances, speeds and times, in the units of Settings: metres and kilometres, feet and
 * miles, or (on the water, and for anyone who chose them) nautical miles. Rounded as a glance needs: to 10 m under a
 * kilometre, one decimal under 10 km, whole kilometres beyond.
 */
object GoFormat {
    /** The distance to a maneuver or to go. */
    fun distance(metres: Double, units: Units = Units.METRIC): Amount {
        if (!metres.isFinite() || metres < 0) return Amount("–", "")
        return when (units) {
            Units.METRIC -> when {
                metres < 995 -> Amount(((metres / 10).roundToInt() * 10).coerceAtLeast(0).toString(), "m")
                metres < 9_950 -> Amount(oneDecimal(metres / 1000), "km")
                else -> Amount((metres / 1000).roundToInt().toString(), "km")
            }
            Units.IMPERIAL -> {
                val feet = metres / 0.3048
                val miles = metres / 1609.344
                when {
                    feet < 1000 -> Amount(((feet / 10).roundToInt() * 10).toString(), "ft")
                    miles < 9.95 -> Amount(oneDecimal(miles), "mi")
                    else -> Amount(miles.roundToInt().toString(), "mi")
                }
            }
            Units.NAUTICAL -> {
                val nm = metres / 1852.0
                when {
                    nm < 0.1 -> Amount(((metres / 10).roundToInt() * 10).toString(), "m")
                    nm < 9.95 -> Amount(oneDecimal(nm), "nm")
                    else -> Amount(nm.roundToInt().toString(), "nm")
                }
            }
        }
    }

    /** A speed from metres per second: km/h, mph, or knots. */
    fun speed(ms: Double, units: Units = Units.METRIC): Amount {
        if (!ms.isFinite() || ms < 0) return Amount("–", "")
        return when (units) {
            Units.METRIC -> Amount((ms * 3.6).roundToInt().toString(), "km/h")
            Units.IMPERIAL -> Amount((ms * 2.236936).roundToInt().toString(), "mph")
            Units.NAUTICAL -> Amount(oneDecimal(ms * 1.943844), "kn")
        }
    }

    /** Knots, always (boats): "6.2". */
    fun knots(ms: Double): String = if (!ms.isFinite() || ms < 0) "–" else oneDecimal(ms * 1.943844)

    /** A clock time ("18:42", or "6:42 PM" in a 12-hour locale) in [zone]. */
    fun clock(t: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val twelve = locale.country in setOf("US", "CA", "AU", "IN", "PH", "EG", "SA", "PK", "BD")
        return DateTimeFormatter.ofPattern(if (twelve) "h:mm a" else "H:mm", locale).format(t.atZone(zone))
    }

    /** A duration as the boards write it: "6′", "1 h 05". */
    fun minutes(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "–"
        val m = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (m < 60) "$m′" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
    }

    /** A duration on the tachograph: "4:10" (hours and minutes). */
    fun hoursMinutes(seconds: Long): String {
        val m = (seconds / 60).coerceAtLeast(0)
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** Metres of height: "12 m", or feet. */
    fun height(metres: Double, units: Units = Units.METRIC): Amount =
        if (units == Units.IMPERIAL) Amount((metres / 0.3048).roundToInt().toString(), "ft") else Amount(metres.roundToInt().toString(), "m")

    /** A clearance or vehicle size to the decimetre: "3.5 m", or feet and inches ("13′6″"). */
    fun size(metres: Double, units: Units = Units.METRIC): String {
        if (units == Units.IMPERIAL) {
            val inches = (metres / 0.0254).roundToInt()
            return "${inches / 12}′${inches % 12}″"
        }
        return oneDecimal(metres) + " m"
    }

    private fun oneDecimal(x: Double): String {
        val tenths = (x * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }
}
