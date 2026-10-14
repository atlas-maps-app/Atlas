package app.atlas.ui.search

import app.atlas.geo.CoordinateFormat
import app.atlas.model.Place
import app.atlas.places.Localise
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A list of places written for other apps: GPX waypoints, GeoJSON points, or plain text with links. */
object SavedExport {
    /** GPX 1.1 with one waypoint per place: its name, its kind and its OpenStreetMap link. */
    fun gpx(title: String, places: List<Place>, at: Instant, lang: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Atlas\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata>\n    <name>").append(xml(title)).append("</name>\n")
        append("    <time>").append(DateTimeFormatter.ISO_INSTANT.format(at)).append("</time>\n  </metadata>\n")
        for (p in places) {
            append("  <wpt lat=\"").append(coord(p.location.lat)).append("\" lon=\"").append(coord(p.location.lon)).append("\">\n")
            append("    <name>").append(xml(nameOf(p, lang))).append("</name>\n")
            Localise.address(p.tags)?.let { append("    <desc>").append(xml(it)).append("</desc>\n") }
            p.id?.let { append("    <link href=\"").append(xml(it.url)).append("\"><text>OpenStreetMap</text></link>\n") }
            append("  </wpt>\n")
        }
        append("</gpx>\n")
    }

    /** A GeoJSON FeatureCollection of points, each with the place's tags as properties. */
    fun geoJson(title: String, places: List<Place>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("name", title)
        put("features", JsonArray(places.map { p ->
            buildJsonObject {
                put("type", "Feature")
                p.id?.let { put("id", it.toString()) }
                put("geometry", buildJsonObject {
                    put("type", "Point")
                    put("coordinates", JsonArray(listOf(JsonPrimitive(round(p.location.lon)), JsonPrimitive(round(p.location.lat)))))
                })
                put("properties", JsonObject(p.tags.mapValues { JsonPrimitive(it.value) }))
            }
        }))
    }.toString()

    /** Plain text for sharing: the title, then each place with its geo: link and OpenStreetMap link. */
    fun text(title: String, places: List<Place>, lang: String? = null): String = buildString {
        append(title).append('\n')
        places.forEachIndexed { i, p ->
            append('\n').append(i + 1).append(". ").append(nameOf(p, lang)).append('\n')
            append(CoordinateFormat.geoUri(p.location)).append('\n')
            p.id?.let { append(it.url).append('\n') }
        }
    }

    /** A file name for [title] with [extension]: letters, digits and dashes only. */
    fun fileName(title: String, extension: String): String {
        val base = title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifEmpty { "places" }
        return "$base.$extension"
    }

    private fun nameOf(p: Place, lang: String?): String = Localise.name(p.tags, lang) ?: CoordinateFormat.decimal(p.location)

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')

    private fun round(v: Double) = Math.round(v * 1e7) / 1e7

    private fun xml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c < ' ' && c != '\n' && c != '\t') append(' ') else append(c)
        }
    }
}
