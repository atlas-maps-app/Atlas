package app.atlas.mapview

import app.atlas.engine.PoiCandidate
import app.atlas.places.Palette
import app.atlas.places.Presets
import app.atlas.places.Symbols
import java.util.concurrent.ConcurrentHashMap

/** How an OpenMapTiles point of interest shows: its symbol, its colour and the OSM tag it stands for. */
class PoiStyle(val symbol: String, val colour: Int, val key: String?, val value: String)

/**
 * Symbols and colours for the engine's points of interest, from their OpenMapTiles `class` and `subclass`: the
 * subclass is the OSM value (amenity=cafe, shop=bakery…), so the first key whose preset knows it decides. Resolved once
 * per pair and kept; [warm] does it off the main thread as scenes arrive.
 */
object PoiStyles {
    /** By class, then subclass: looked up per symbol per frame, so without building a key. */
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, PoiStyle>>()

    private val KEYS = listOf("amenity", "shop", "tourism", "leisure", "historic", "office", "craft", "healthcare", "railway", "public_transport", "man_made", "natural")

    /** The style of a point of interest of [cls] / [subclass]. */
    fun of(cls: String, subclass: String): PoiStyle =
        cache.getOrPut(cls) { ConcurrentHashMap() }.getOrPut(subclass) { resolve(cls, subclass) }

    fun of(p: PoiCandidate): PoiStyle = of(p.cls, p.subclass)

    /** Resolves every pair in [pois] now, symbol paths included (presets and symbols load on first use). */
    fun warm(pois: List<PoiCandidate>) {
        for (p in pois) Icons.shape(of(p.cls, p.subclass).symbol)
    }

    private fun resolve(cls: String, subclass: String): PoiStyle {
        var tags: Map<String, String>? = null
        for (k in KEYS) {
            val t = mapOf(k to subclass)
            val p = Presets.match(t) ?: continue
            if (p.id.contains('/')) { tags = t; break }
        }
        if (tags == null) {
            for (k in KEYS) {
                val t = mapOf(k to cls)
                val p = Presets.match(t) ?: continue
                if (p.id.contains('/')) { tags = t; break }
            }
        }
        val t = tags ?: mapOf("amenity" to subclass)
        val direct = Icons.resolveId(subclass) ?: Icons.resolveId(cls)
        val symbol = tags?.let { Symbols.symbolFor(it) }?.takeIf { it != Symbols.MARKER } ?: direct ?: Symbols.MARKER
        val colour = Symbols.familyOf(t)?.colour ?: classColour(cls) ?: Palette.SERVICE
        val (key, value) = t.entries.first()
        return PoiStyle(symbol, colour, if (tags == null) null else key, value)
    }

    /** OpenMapTiles classes the place families do not know by tag. */
    private fun classColour(cls: String): Int? = when (cls) {
        "restaurant", "cafe", "fast_food", "bar", "beer", "ice_cream" -> 0xFFE07A1F.toInt()
        "grocery", "bakery", "alcohol_shop" -> 0xFF2E9E5B.toInt()
        "shop", "clothing_store", "jewelry", "shoes", "gift" -> 0xFF8E5BD0.toInt()
        "museum", "art_gallery", "theatre", "attraction", "monument", "castle", "place_of_worship", "town_hall" -> 0xFFB8326A.toInt()
        "lodging" -> 0xFF2E6FD8.toInt()
        "hospital", "pharmacy", "doctors", "dentist" -> 0xFFD23C3C.toInt()
        "bus", "railway", "tram", "ferry", "bicycle_rental", "harbor" -> 0xFF2E5BFF.toInt()
        "park", "garden", "playground" -> 0xFF4C8C3A.toInt()
        else -> null
    }
}
