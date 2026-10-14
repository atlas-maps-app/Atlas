package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.Category
import app.atlas.places.Presets
import app.atlas.places.TagFilter
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.search.PlaceSource
import app.atlas.tiles.GeomType
import app.atlas.tiles.TileProjection
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** Where a list of places came from: the offline place store, the map tiles' points of interest, or Overpass online. */
enum class PlaceOrigin { STORE, TILES, ONLINE }

/** Places found near a point, nearest first, with where they came from and, when a source failed, why. */
data class NearbyPlaces(val places: List<Place>, val source: PlaceOrigin, val error: String? = null)

/**
 * Finds places of a category near a point, for the Map home's category row and quick needs. Results come as a flow:
 * what the phone has at once, then better answers as they arrive (online, with full tags).
 */
fun interface PlaceFinder {
    fun nearby(categoryId: String, centre: LatLon, radiusM: Double, limit: Int): Flow<NearbyPlaces>
}

/**
 * Places by category in layers: the downloaded regions' place stores first (offline, every tag); where they have
 * nothing, the map tiles' points of interest (offline, names and kinds only), then Overpass when [online] (every tag,
 * so opening hours and facts).
 */
class LayeredPlaceFinder(
    private val tiles: () -> TileSource?,
    private val overpass: () -> Overpass?,
    private val online: () -> Boolean,
    private val store: () -> PlaceSource? = { null },
) : PlaceFinder {
    override fun nearby(categoryId: String, centre: LatLon, radiusM: Double, limit: Int): Flow<NearbyPlaces> = flow {
        val category = Categories.byId(categoryId) ?: throw IllegalArgumentException("no category $categoryId")
        val filter = filterOf(category)
        store()?.takeIf { it.size > 0 }?.let { s ->
            val ids = if (category.isGroup) Categories.children(category.id).map { it.id } else listOf(category.id)
            val found = s.byCategory(ids, BBox.around(centre, radiusM))
                .map { it.place }
                .filter { Geo.distance(centre, it.location) <= radiusM }
            // A region on the phone answers fully: no need for the tiles' sketch or the network.
            if (found.isNotEmpty()) {
                emit(NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.STORE))
                return@flow
            }
        }
        tiles()?.let { src ->
            val fromTiles = try {
                TilePlaces.near(src, centre, radiusM, filter, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fromTiles != null) emit(NearbyPlaces(fromTiles, PlaceOrigin.TILES))
        }
        val op = overpass()
        if (op != null && online()) {
            val selectors = OverpassSelectors.of(filter)
            if (selectors != null) {
                val result = try {
                    val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
                    val found = op.places(ql).filter { filter.matches(it.tags) && Categories.isPlace(it.tags) }
                    NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.ONLINE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, e.message ?: "offline")
                } catch (e: Exception) {
                    // An answer that does not parse (a captive portal's page, a broken proxy) is a failure to report too.
                    NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, e.message ?: e.toString())
                }
                emit(result)
            }
        }
    }.flowOn(Dispatchers.Default)

    companion object {
        /** A category's rule; a group's is the union of its leaves' rules. */
        fun filterOf(category: Category): TagFilter =
            category.rule ?: TagFilter.Or(Categories.children(category.id).mapNotNull { it.rule })

        fun nearest(places: List<Place>, centre: LatLon, limit: Int): List<Place> =
            places.distinctBy { it.key }.sortedBy { Geo.distance(centre, it.location) }.take(limit)
    }
}

/**
 * Points of interest from the vector tiles' `poi` layer (OpenMapTiles), as places: offline, with their OSM id (the
 * feature id is the OSM id times ten plus 1 for a node, 2 for a way, 3 for a relation), name and kind. The OSM key is
 * recovered from the kind through the id-tagging-schema presets ("cafe" is `amenity=cafe`, "bakery" `shop=bakery`).
 */
object TilePlaces {
    private const val ZOOM = 14
    private val KEYS = listOf(
        "amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "historic", "sport", "railway",
        "public_transport", "highway", "aerialway", "waterway", "man_made", "natural", "emergency", "landuse", "building", "place",
    )
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Places within [radiusM] of [centre] that pass [filter], nearest first. */
    suspend fun near(src: TileSource, centre: LatLon, radiusM: Double, filter: TagFilter, limit: Int): List<Place> {
        val box = BBox.around(centre, radiusM)
        val out = HashMap<String, Place>()
        // Where z14 is missing the stack falls back to a parent tile, which several ids share: read each tile once.
        val read = HashSet<TileId>()
        for (id in Mercator.tilesIn(box, ZOOM)) {
            val hit = (src as? TileStack)?.bestTile(id)
            val tileId = hit?.source ?: id
            if (!read.add(tileId)) continue
            val data = hit?.data ?: src.tile(id) ?: continue
            val layer = app.atlas.tiles.Mvt.decode(data, setOf("poi"))["poi"] ?: continue
            val proj = TileProjection(tileId, layer.extent)
            for (f in layer.features) {
                if (f.type != GeomType.POINT) continue
                val g = f.geometry
                if (g.isEmpty) continue
                val at = proj.latLon(g.x(0).toDouble(), g.y(0).toDouble())
                if (Geo.distance(centre, at) > radiusM) continue
                val tags = tagsOf(f.string("class"), f.string("subclass"), f) ?: continue
                if (!filter.matches(tags)) continue
                val osm = if (f.hasId) osmIdOf(f.id) else null
                val place = Place(osm, at, tags, source = "tiles")
                out.putIfAbsent(place.key, place)
            }
        }
        return LayeredPlaceFinder.nearest(out.values.toList(), centre, limit)
    }

    /** The OSM id of a tile feature id, or null when it does not encode one. */
    fun osmIdOf(featureId: Long): OsmId? {
        val type = when ((featureId % 10).toInt()) {
            1 -> OsmType.NODE
            2 -> OsmType.WAY
            3 -> OsmType.RELATION
            else -> return null
        }
        val id = featureId / 10
        return if (id > 0) OsmId(type, id) else null
    }

    /** OSM-like tags for a POI of OpenMapTiles [cls] and [subclass], with its names; null without a kind. */
    fun tagsOf(cls: String?, subclass: String?, f: app.atlas.tiles.TileFeature? = null): Map<String, String>? {
        val sub = subclass ?: return null
        val key = keyFor(cls, sub)
        val tags = LinkedHashMap<String, String>()
        tags[key] = sub
        f?.let { feature ->
            feature.string("name")?.let { tags["name"] = it }
            for (k in listOf("name:en", "name:nl", "name:de", "name:fr")) feature.string(k)?.let { tags[k] = it }
            feature.int("level")?.let { tags["level"] = it.toString() }
        }
        return tags
    }

    private fun keyFor(cls: String?, sub: String): String = keyCache.getOrPut("$cls/$sub") {
        KEYS.firstOrNull { Presets.byId("$it/$sub") != null }
            ?: when (cls) {
                "shop", "grocery", "clothing_store", "alcohol_shop", "bakery", "furniture", "jewelry", "hardware", "laundry",
                "music", "pharmacy", "car" -> "shop"
                "lodging", "museum", "attraction", "campsite" -> "tourism"
                "park", "garden", "pitch", "playground", "stadium", "swimming", "golf" -> "leisure"
                "railway" -> "railway"
                "bus" -> "highway"
                else -> "amenity"
            }
    }
}

/** Overpass tag selectors for a [TagFilter], as a union: `a or b` is two selectors, `a and (b or c)` is `ab`, `ac`. */
object OverpassSelectors {
    private const val MAX = 32

    /**
     * The selectors, or null when the filter cannot be written (too many alternatives, or a test Overpass lacks).
     * Selectors may match more than the filter (a number test becomes "has the key"); filter the answers with it.
     */
    fun of(filter: TagFilter): List<String>? = dnf(filter)?.takeIf { s -> s.isNotEmpty() && s.size <= MAX && s.none { it.isBlank() } }

    private fun dnf(f: TagFilter): List<String>? = when (f) {
        is TagFilter.Or -> {
            val out = ArrayList<String>()
            for (p in f.parts) out += dnf(p) ?: return null
            out.distinct()
        }
        is TagFilter.And -> {
            var acc = listOf("")
            for (p in f.parts) {
                val alts = dnf(p) ?: return null
                acc = acc.flatMap { a -> alts.map { a + it } }
                if (acc.size > MAX) return null
            }
            acc
        }
        is TagFilter.Compare -> listOf(OverpassQl.tag(f.key))
        // A negation Overpass cannot say is left to the client-side filter: no constraint here.
        is TagFilter.Not -> listOf(f.toOverpass() ?: "")
        else -> f.toOverpass()?.let { listOf(it) } ?: f.keys().takeIf { it.isNotEmpty() }?.map { OverpassQl.tag(it) }
    }
}
