package app.atlas.nav

import app.atlas.geo.LatLon
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * A destination's address as text, `name?key=value&…`, URL-encoded: what the back stack is saved as, what deep links
 * and CI open, and what [Dest.parse] reads back.
 */
object Routes {
    /** Builds `name?k=v&…`, leaving out null arguments. */
    fun build(name: String, vararg args: Pair<String, Any?>): String {
        val present = args.filter { it.second != null }
        if (present.isEmpty()) return name
        return name + "?" + present.joinToString("&") { (k, v) -> k + "=" + encode(format(v!!)) }
    }

    /** Splits a route into its name and arguments. */
    fun split(route: String): Pair<String, RouteArgs> {
        val q = route.indexOf('?')
        if (q < 0) return route to RouteArgs(emptyMap())
        val args = route.substring(q + 1).split('&').filter { it.isNotEmpty() }.associate { part ->
            val eq = part.indexOf('=')
            if (eq < 0) decode(part) to "" else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
        }
        return route.substring(0, q) to RouteArgs(args)
    }

    private fun format(v: Any): String = when (v) {
        is Double -> String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')
        is Float -> format(v.toDouble())
        else -> v.toString()
    }

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)
}

/** The arguments of a route, read with defaults. */
class RouteArgs(val values: Map<String, String>) {
    fun str(key: String): String? = values[key]
    fun str(key: String, default: String): String = values[key] ?: default
    fun double(key: String): Double? = values[key]?.toDoubleOrNull()
    fun int(key: String): Int? = values[key]?.toIntOrNull()
    fun bool(key: String, default: Boolean = false): Boolean = values[key]?.toBooleanStrictOrNull() ?: default

    /** A point from `<prefix>lat` and `<prefix>lon`, or null when either is missing. */
    fun latLon(prefix: String = ""): LatLon? {
        val lat = double(prefix + "lat") ?: return null
        val lon = double(prefix + "lon") ?: return null
        return LatLon(lat, lon)
    }
}
