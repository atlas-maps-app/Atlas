package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Where an Overpass query looks: a box, a circle, or a corridor along a line. */
sealed interface OverpassArea {
    /** The QL filter, e.g. `(52.37,4.88,52.38,4.89)` or `(around:300,52.3745,4.8838)`. */
    fun clause(): String

    data class Box(val box: BBox) : OverpassArea {
        override fun clause() = "(${f(box.south)},${f(box.west)},${f(box.north)},${f(box.east)})"
    }

    data class Around(val center: LatLon, val metres: Double) : OverpassArea {
        override fun clause() = "(around:${tagNumber(metres)},${f(center.lat)},${f(center.lon)})"
    }

    /** Within [metres] of the polyline through [points] (Overpass 0.7.55 and later). */
    data class Along(val points: List<LatLon>, val metres: Double) : OverpassArea {
        init {
            require(points.isNotEmpty()) { "A corridor needs at least one point" }
        }

        override fun clause() = buildString {
            append("(around:").append(tagNumber(metres))
            for (p in points) append(',').append(f(p.lat)).append(',').append(f(p.lon))
            append(')')
        }
    }
}

private fun f(v: Double) = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')

/** Small, safe builders for Overpass QL, so callers never splice raw user text into a query. */
object OverpassQl {
    /** A QL string literal with quotes and backslashes escaped. */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** `["key"="value"]`, or `["key"]` when [value] is null. */
    fun tag(key: String, value: String? = null): String = if (value == null) "[${quote(key)}]" else "[${quote(key)}=${quote(value)}]"

    /** `["key"~"^(a|b)$"]`: the key has one of [values] exactly. */
    fun oneOf(key: String, values: Collection<String>): String =
        "[${quote(key)}~${quote("^(" + values.joinToString("|") { regexLiteral(it) } + ")$")}]"

    private fun regexLiteral(s: String): String = s.replace(Regex("""[.*+?^${'$'}()\[\]{}|\\]""")) { "\\" + it.value }

    /** Makes sure [ql] asks for JSON and has a timeout, keeping any other settings it had. */
    fun ensureJson(ql: String, timeoutSeconds: Int = 60): String {
        val trimmed = ql.trimStart()
        if (!trimmed.startsWith("[")) return "[out:json][timeout:$timeoutSeconds];\n$trimmed"
        val end = trimmed.indexOf(';')
        if (end < 0) return trimmed
        var settings = trimmed.substring(0, end)
        settings = if (Regex("""\[out:\w+]""").containsMatchIn(settings)) settings.replace(Regex("""\[out:\w+]"""), "[out:json]") else "[out:json]$settings"
        if (!settings.contains("[timeout:")) settings += "[timeout:$timeoutSeconds]"
        return settings + trimmed.substring(end)
    }

    /** The timeout a query declares, or [default]. */
    fun timeoutOf(ql: String, default: Int = 180): Int = Regex("""\[timeout:(\d+)]""").find(ql)?.groupValues?.get(1)?.toInt() ?: default

    /** A union of `nwr` selectors in [area], answered with tags and a centre for ways and relations. */
    fun places(selectors: List<String>, area: OverpassArea, timeoutSeconds: Int = 60, out: String = "out tags center qt;"): String = buildString {
        append("[out:json][timeout:").append(timeoutSeconds).append("];\n(\n")
        for (s in selectors) append("  nwr").append(s).append(area.clause()).append(";\n")
        append(");\n").append(out)
    }

    /** Charging stations in [area], optionally only those with one of [sockets] (OSM `socket:*` names). */
    fun chargers(area: OverpassArea, sockets: Collection<String> = emptyList(), timeoutSeconds: Int = 60): String =
        if (sockets.isEmpty()) places(listOf(tag("amenity", "charging_station")), area, timeoutSeconds)
        else places(sockets.map { tag("amenity", "charging_station") + tag("socket:$it") }, area, timeoutSeconds)

    /**
     * Places matching [selector] created since [since] in [area]: `(newer:…)` keeps elements changed since then, and
     * `version()==1` keeps the ones never edited after creation, which is as close as Overpass gets to "new".
     * Newly mapped is not newly opened; [Trends] weighs it with `opening_date`, `start_date` and `check_date`.
     */
    fun createdSince(selector: String, area: OverpassArea, since: Instant, timeoutSeconds: Int = 90): String = buildString {
        val t = since.truncatedTo(ChronoUnit.SECONDS)
        append("[out:json][timeout:").append(timeoutSeconds).append("];\n")
        append("nwr").append(selector).append(area.clause()).append("(newer:").append(quote(t.toString())).append(")(if: version()==1);\n")
        append("out meta center qt;")
    }

    /** Ways in [area] with full geometry and tags: for restriction checks along a route. */
    fun waysWithGeometry(selectors: List<String>, area: OverpassArea, timeoutSeconds: Int = 90): String =
        places(selectors, area, timeoutSeconds, "out tags geom qt;").replace("  nwr", "  way")
}
