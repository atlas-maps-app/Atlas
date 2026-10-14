package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Localise
import app.atlas.places.TagFilter
import app.atlas.search.PlaceRecord
import app.atlas.services.PlaceOrigin
import app.atlas.services.TilePlaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** The address of a point: its street and number, the quarter and town, and where it was read. */
data class HereAddress(
    val street: String?,
    val housenumber: String?,
    val postcode: String?,
    val quarter: String?,
    val town: String?,
    val origin: PlaceOrigin,
) {
    /** "Westermarkt 13", or the street alone, or null. */
    val line: String? get() = street?.let { s -> housenumber?.let { "$s $it" } ?: s }

    /** "1016 DK · Jordaan · Amsterdam": what is known beyond the street. */
    val area: String? get() = listOfNotNull(postcode, quarter, town?.takeIf { it != quarter }).joinToString(" · ").ifEmpty { null }

    companion object {
        /** The address in [tags] (`addr:*`), or null when they have no street or place. */
        fun of(tags: Map<String, String>, origin: PlaceOrigin, where: TileWhere?): HereAddress? {
            val street = tags["addr:street"] ?: tags["addr:place"] ?: return null
            return HereAddress(street, tags["addr:housenumber"], tags["addr:postcode"], where?.quarter, tags["addr:city"] ?: where?.town, origin)
        }
    }
}

/** What is at a point: its address, the place it is in (a building, a church, a park), and the named things around. */
data class HereFacts(val address: HereAddress?, val spot: Place?, val nearby: List<Place>, val origin: PlaceOrigin?)

/**
 * Finds what is at a point for "What's here": from a downloaded region (the place whose outline holds the point, the
 * nearest mapped address, named places measured to their outlines), else from the map tiles (house numbers, streets,
 * labels), and only then, online, Photon's reverse geocoder for the address.
 */
object HereFinder {
    const val NEARBY_M = 150.0
    private const val ADDRESS_M = 40.0
    private const val SPOT_POINT_M = 12.0
    private const val NEARBY_MAX = 8

    suspend fun find(services: Services, p: LatLon, lang: String, online: Boolean): HereFacts = withContext(Dispatchers.Default) {
        val tiles = services.tiles.value
        val where = tiles?.let { runCatching { TileAddress.at(it, p) }.getOrNull() }
        val store = services.placeStores
        val facts = if (store.size > 0 && store.bounds?.let { p in it } == true) fromStore(store.inBox(BBox.around(p, NEARBY_M * 1.5)), p, where)
        else fromTiles(services, p, where)
        if (facts.address != null || !online) return@withContext facts
        val reverse = try {
            services.geocoder.reverse(p, lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        }
        val address = reverse?.takeIf { Geo.distance(it.location, p) <= ADDRESS_M * 2 }?.let { HereAddress.of(it.tags, PlaceOrigin.ONLINE, where) }
        facts.copy(address = address)
    }

    /** From a region's records around [p]: the smallest outline holding it, the nearest address and the named places. */
    fun fromStore(records: List<PlaceRecord>, p: LatLon, where: TileWhere?): HereFacts {
        val holding = records.filter { r -> r.place.bbox?.let { b -> b.width > 0 && b.height > 0 && p in b } == true && (r.tags["name"] != null || r.tags["building"] != null) }
        val spot = holding.minByOrNull { r -> r.place.bbox!!.let { it.width * it.height } }
            ?: records.filter { it.category != null && it.tags["name"] != null && it.distanceTo(p) <= SPOT_POINT_M }.minByOrNull { it.distanceTo(p) }
        val address = spot?.let { HereAddress.of(it.tags, PlaceOrigin.STORE, where) }
            ?: records.filter { it.tags["addr:housenumber"] != null && it.distanceTo(p) <= ADDRESS_M }.minByOrNull { it.distanceTo(p) }
                ?.let { HereAddress.of(it.tags, PlaceOrigin.STORE, where) }
            ?: where?.let { fromTiles(it) }
        // Places Atlas lists (not streets, districts or signs), measured to their outlines; a vast area whose middle is far
        // away (the whole canal ring) is not "here".
        val nearby = records.asSequence()
            .filter { it !== spot && it.category != null && it.tags["name"] != null && it.distanceTo(p) <= NEARBY_M && Geo.distance(p, it.location) <= 2 * NEARBY_M }
            .sortedBy { it.distanceTo(p) }
            .distinctBy { Localise.name(it.tags) }
            .take(NEARBY_MAX).map { it.place }.toList()
        return HereFacts(address, spot?.place, nearby, PlaceOrigin.STORE)
    }

    private suspend fun fromTiles(services: Services, p: LatLon, where: TileWhere?): HereFacts {
        val tiles = services.tiles.value ?: return HereFacts(where?.let { fromTiles(it) }, null, emptyList(), null)
        val named = runCatching { TilePlaces.near(tiles, p, NEARBY_M, TagFilter.Has("name"), 40) }.getOrNull().orEmpty()
        val spot = named.firstOrNull { Geo.distance(p, it.location) <= SPOT_POINT_M }
        val nearby = named.filter { it !== spot }.distinctBy { it.tags["name"] }.take(NEARBY_MAX)
        return HereFacts(where?.let { fromTiles(it) }, spot, nearby, PlaceOrigin.TILES)
    }

    private fun fromTiles(w: TileWhere): HereAddress? =
        if (w.street == null && w.quarter == null) null else HereAddress(w.street, w.housenumber?.takeIf { w.street != null }, null, w.quarter, w.town, PlaceOrigin.TILES)
}
