package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Category
import app.atlas.places.Geometry
import app.atlas.places.Kinds
import app.atlas.places.Localise
import app.atlas.places.Preset
import app.atlas.places.Presets
import app.atlas.places.Symbols
import kotlin.math.max
import kotlin.math.min

/**
 * A place as the stores hold it, with what Atlas works out from its tags once, at import: its category (an Atlas
 * category leaf), its brand (a name-suggestion-index id), its id-tagging-schema preset and its map symbol.
 */
class PlaceRecord(
    val place: Place,
    val category: String?,
    val brand: String?,
    val preset: String?,
    val symbol: String,
) {
    val id: OsmId? get() = place.id
    val location: LatLon get() = place.location
    val tags: Map<String, String> get() = place.tags

    /** The name to show in [lang], with Atlas's fallbacks (brand, operator, ref). */
    fun name(lang: String? = null): String? = Localise.name(place.tags, lang)

    /** Metres from [p] to the place: to its outline's box when it has one (0 inside), else to its point. */
    fun distanceTo(p: LatLon): Double {
        val b = place.bbox ?: return Geo.distance(p, place.location)
        if (b.width == 0.0 && b.height == 0.0) return Geo.distance(p, place.location)
        val lat = min(max(p.lat, b.south), b.north)
        val lon = min(max(p.lon, b.west), b.east)
        return Geo.distance(p.lat, p.lon, lat, lon)
    }

    override fun toString() = "${place.id ?: place.key} ${name() ?: preset ?: ""}"

    companion object {
        /** Works out category, brand, preset and symbol for [place] drawn as [geometry]. */
        fun of(place: Place, geometry: Geometry = geometryOf(place)): PlaceRecord = of(place, geometry, Categories.of(place.tags))

        /** Like [of], with the [category] a store's rules gave the place ([Filing]). */
        internal fun of(place: Place, geometry: Geometry, category: Category?): PlaceRecord {
            val tags = place.tags
            val preset = Presets.match(tags, geometry)
            val brand = if ("brand" in tags || "brand:wikidata" in tags || "name" in tags) Brands.forTags(tags) else null
            return PlaceRecord(place, category?.id, brand?.id, preset?.id, symbolOf(preset, category, tags, geometry))
        }

        /** The map symbol of a place: its kind's ([Kinds]), else its category's, else the one its tags draw. */
        internal fun symbolOf(preset: Preset?, category: Category?, tags: Map<String, String>, geometry: Geometry): String =
            preset?.let { Kinds.symbolOf(it) }
                ?: category?.symbol?.takeIf { Symbols.has(it) }
                ?: Symbols.symbolFor(tags, geometry)

        /** The geometry an element most likely has, judged from its type and tags (an extract with centres cannot tell). */
        fun geometryOf(place: Place): Geometry = geometryOf(place.id?.type, place.tags)

        /** The geometry an element of [type] (null for a place from elsewhere, a point) with tags [t] most likely has. */
        internal fun geometryOf(type: OsmType?, t: Map<String, String>): Geometry {
            if (type == null) return Geometry.POINT
            return when (type) {
                OsmType.NODE -> Geometry.POINT
                OsmType.WAY -> when {
                    t["area"] == "yes" -> Geometry.AREA
                    "highway" in t && t["highway"] != "pedestrian" -> Geometry.LINE
                    "waterway" in t && t["waterway"] !in AREA_WATERWAYS -> Geometry.LINE
                    "railway" in t && t["railway"] != "platform" && t["railway"] != "station" -> Geometry.LINE
                    t["barrier"] in LINE_BARRIERS || "power" in t && t["power"] == "line" -> Geometry.LINE
                    t["natural"] in LINE_NATURAL || t["man_made"] in LINE_MAN_MADE -> Geometry.LINE
                    else -> Geometry.AREA
                }
                OsmType.RELATION -> if (t["type"] == "multipolygon" || t["type"] == "boundary") Geometry.AREA else Geometry.RELATION
            }
        }

        private val AREA_WATERWAYS = setOf("riverbank", "dock", "boatyard", "fuel")
        private val LINE_BARRIERS = setOf("fence", "wall", "hedge", "retaining_wall", "city_wall", "guard_rail", "kerb")
        private val LINE_NATURAL = setOf("coastline", "tree_row", "cliff", "ridge", "arete")
        private val LINE_MAN_MADE = setOf("embankment", "pipeline", "breakwater", "groyne", "cutline")

        /** The box of a place, or a point box. */
        internal fun boxOf(place: Place): BBox = place.bbox ?: BBox(place.location.lon, place.location.lat, place.location.lon, place.location.lat)
    }
}
