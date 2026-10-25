package app.atlas.model

import app.atlas.geo.BBox
import app.atlas.geo.LatLon

enum class OsmType(val letter: Char) { NODE('n'), WAY('w'), RELATION('r') }

/** An OpenStreetMap element id: "n123", "w45", "r6". */
data class OsmId(val type: OsmType, val id: Long) {
    override fun toString() = "${type.letter}$id"
    val url: String get() = "https://www.openstreetmap.org/${type.name.lowercase()}/$id"

    companion object {
        /** Accepts "n123", "node/123", "node 123" and the osm.org URL forms; null for a number too large to be an id. */
        fun parse(s: String): OsmId? {
            val m = Regex("""(?i)\b(n|w|r|node|way|relation)[/ ]?(\d+)\b""").find(s) ?: return null
            // A link, a typed query or a review's metadata can carry any digits; past a Long they name no element.
            val id = m.groupValues[2].toLongOrNull() ?: return null
            val t = when (m.groupValues[1].lowercase().first()) { 'n' -> OsmType.NODE; 'w' -> OsmType.WAY; else -> OsmType.RELATION }
            return OsmId(t, id)
        }
    }
}

/**
 * A place as Atlas knows it: an OpenStreetMap element (or a provider's record) with its full tags.
 * Everything a screen shows about a place is derived from [tags]; providers add [extra] (ratings, live status).
 */
data class Place(
    val id: OsmId?,
    val location: LatLon,
    val tags: Map<String, String>,
    val bbox: BBox? = null,
    /** "osm", or a provider id such as "ocm" (Open Charge Map) for places that come from elsewhere. */
    val source: String = "osm",
    val extra: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String? = tags[key]
    fun has(key: String, value: String? = null) = tags[key]?.let { value == null || it == value } ?: false

    /** The name in [lang] if mapped, else the local name, else brand or operator. */
    fun name(lang: String? = null): String? =
        lang?.let { tags["name:$it"] } ?: tags["name"] ?: tags["brand"] ?: tags["operator"]

    /** A stable key across sources: the OSM id when there is one. */
    val key: String get() = id?.toString() ?: "$source:${location}"
}
