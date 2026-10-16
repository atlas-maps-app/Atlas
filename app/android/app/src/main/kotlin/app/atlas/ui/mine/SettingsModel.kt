package app.atlas.ui.mine

import app.atlas.services.Units
import java.util.Locale

/** The small choices of Settings, apart from the screen so they can be tested. */
object SettingsModel {
    /** The distance unit after [u]: km, mi, nm and round again. */
    fun nextUnits(u: Units): Units = Units.entries[(u.ordinal + 1) % Units.entries.size]

    /** The languages offered for names on the map: null is the local names. */
    val NAME_LANGUAGES: List<String?> = listOf(null, "en", "nl", "de", "fr", "es", "it", "pt", "pl", "sv")

    /** A language's own name, "Nederlands" for nl, "English" for en. */
    fun languageName(code: String): String = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.uppercase() }

    /** A temperature in °C as the setting says: whole degrees, in °F when [fahrenheit]. */
    fun temperature(celsius: Double, fahrenheit: Boolean): String =
        if (fahrenheit) "${Math.round(celsius * 9.0 / 5.0 + 32)}°" else "${Math.round(celsius)}°"

    /** A time of day as the setting says: "18:40" or "6:40 pm". */
    fun clock(hour: Int, minute: Int, twelve: Boolean): String =
        if (!twelve) String.format(Locale.ROOT, "%d:%02d", hour, minute)
        else String.format(Locale.ROOT, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "am" else "pm")

    /** A speed in km/h in the unit chosen: km/h, mph or knots. */
    fun speed(kmh: Double, u: Units): String = when (u) {
        Units.METRIC -> "${Math.round(kmh)} km/h"
        Units.IMPERIAL -> "${Math.round(kmh / 1.609344)} mph"
        Units.NAUTICAL -> "${Math.round(kmh / 1.852)} kn"
    }

    /** A distance in metres in the unit chosen, short: "350 m", "1.2 km", "0.8 mi", "0.7 nm". */
    fun distance(metres: Double, u: Units): String = when (u) {
        Units.METRIC -> if (metres < 1000) "${Math.round(metres / 10) * 10} m" else String.format(Locale.ROOT, "%.1f km", metres / 1000)
        Units.IMPERIAL -> if (metres < 300) "${Math.round(metres * 3.28084 / 10) * 10} ft" else String.format(Locale.ROOT, "%.1f mi", metres / 1609.344)
        Units.NAUTICAL -> if (metres < 300) "${Math.round(metres / 10) * 10} m" else String.format(Locale.ROOT, "%.1f nm", metres / 1852)
    }
}
