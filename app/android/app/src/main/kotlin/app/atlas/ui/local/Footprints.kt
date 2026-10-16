package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Localise

/**
 * A building as the map has it: its box (the map's footprints reach the app as the box round them), the year it was built from
 * `start_date` (1005, the Dutch register's "unknown", is none), a height from `height` or `building:levels`, and a name.
 */
class Footprint(val place: Place, val box: BBox, val built: BuiltYear, val heightM: Double?, val name: String?) {
    /** The year to place it on a timeline: the year, the middle of a century; null when no year is known. */
    val year: Int? = when (built) {
        is BuiltYear.Year -> built.year
        is BuiltYear.Century -> (built.century - 1) * 100 + 50
        BuiltYear.Unknown -> null
    }

    /** True when something besides a number names it: a name, a heritage tag, a Wikidata item or a historic tag. */
    val notable: Boolean get() = name != null && (place.tags.keys.any { it == "wikidata" || it == "historic" || it.startsWith("heritage") || it == "ref:rce" } || place.tags["tourism"] != null)

    /** Building work not yet finished: `building=construction` or a `construction` tag. */
    val underConstruction: Boolean get() = place.tags["building"] == "construction" || place.tags["construction"] != null || place.tags["proposed:building"] != null
}

object Footprints {
    /** What the map says a building's height is, in metres: `height`, else `building:levels` at 3 m, else `roof:levels` added; null when unmapped. */
    fun height(tags: Map<String, String>): Double? {
        tags["height"]?.let { h ->
            val m = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(m|ft|feet|')?""", RegexOption.IGNORE_CASE).find(h) ?: return@let
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            // OSM's feet are written 12' or 12 ft; a bare number is metres.
            return if (m.groupValues[2].lowercase() in setOf("ft", "feet", "'")) value * 0.3048 else value
        }
        val levels = tags["building:levels"]?.toDoubleOrNull() ?: return null
        return levels * 3.0 + (tags["roof:levels"]?.toDoubleOrNull() ?: 0.0) * 1.5
    }

    fun of(place: Place, lang: String?): Footprint? {
        val box = place.bbox ?: return null
        val tags = place.tags
        if (tags["building"] == null && tags["building:part"] == null) return null
        return Footprint(place, box, BuildingStory.year(tags["start_date"] ?: tags["building:start_date"]), height(tags), Localise.name(tags, lang))
    }

    /** The buildings within [radiusM] of [at], nearest first, from the place store or Overpass (with their boxes); empty when neither has any. */
    suspend fun around(services: Services, at: LatLon, radiusM: Double, lang: String?, limit: Int = 900): List<Footprint> =
        services.local.finder.find("building", at, radiusM, limit = limit, out = LocalFinder.OUT_BOX).places.mapNotNull { of(it.place, lang) }
}
