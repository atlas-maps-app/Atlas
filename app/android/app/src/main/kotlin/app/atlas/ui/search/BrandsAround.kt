package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.TagFilter
import app.atlas.services.PlaceOrigin
import app.atlas.services.TilePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One chain around: the brand and its branches, nearest first. */
data class BrandBranches(val brand: Brand, val branches: List<Place>)

/** The places around a point split into chains (with their branches) and independents, and where they came from. */
data class BrandsNearby(val chains: List<BrandBranches>, val independents: List<Place>, val origin: PlaceOrigin?)

/**
 * Chains and independents around a point, offline: from the place store of a downloaded region, else from the map
 * tiles' points of interest (names and kinds, matched to brands by the name-suggestion index).
 */
object BrandsAround {
    /** The kinds of place a chain can run: shops and the amenities brands trade as. */
    private val TRADES = setOf(
        "restaurant", "fast_food", "cafe", "bar", "pub", "ice_cream", "pharmacy", "bank", "fuel", "charging_station",
        "car_rental", "bicycle_rental", "cinema", "post_office",
    )

    /** True for a place a chain could run: a shop, an eatery or another trade, or a hotel. */
    fun isTrade(tags: Map<String, String>): Boolean =
        "shop" in tags || tags["amenity"] in TRADES || tags["tourism"] in setOf("hotel", "hostel", "motel")

    suspend fun near(services: Services, at: LatLon, radiusM: Double): BrandsNearby = withContext(Dispatchers.Default) {
        val store = services.placeStores
        val (places, origin) = when {
            store.size > 0 -> store.inBox(BBox.around(at, radiusM)).map { it.place } to PlaceOrigin.STORE
            else -> {
                val tiles = services.tiles.value
                val found = tiles?.let { runCatching { TilePlaces.near(it, at, radiusM, TagFilter.Has("name"), 5_000) }.getOrNull() }
                (found ?: emptyList()) to (if (found != null) PlaceOrigin.TILES else null)
            }
        }
        split(places, at, radiusM, origin)
    }

    /**
     * The branches of brand [id] within [radiusM] of [at], nearest first: from the regions' brand index, else from the
     * map tiles' labels. The origin says which.
     */
    suspend fun branches(services: Services, id: String, at: LatLon, radiusM: Double): Pair<List<Place>, PlaceOrigin?> = withContext(Dispatchers.Default) {
        val store = services.placeStores
        val near = compareBy<Place> { Geo.distance(at, it.location) }
        if (store.size > 0) {
            val found = runCatching { store.byBrand(listOf(id), BBox.around(at, radiusM)) }.getOrNull().orEmpty()
                .map { it.place }.filter { Geo.distance(at, it.location) <= radiusM }
            if (found.isNotEmpty()) return@withContext found.distinctBy { it.key }.sortedWith(near) to PlaceOrigin.STORE
        }
        val tiles = services.tiles.value ?: return@withContext emptyList<Place>() to null
        val found = runCatching { TilePlaces.near(tiles, at, radiusM, TagFilter.Has("name"), 5_000) }.getOrNull()
            ?: return@withContext emptyList<Place>() to null
        found.filter { isTrade(it.tags) && Brands.forTags(it.tags)?.id == id }.sortedWith(near) to PlaceOrigin.TILES
    }

    /** Splits [places] within [radiusM] of [at] into chains (most branches first) and named independents (nearest first). */
    fun split(places: List<Place>, at: LatLon, radiusM: Double, origin: PlaceOrigin?): BrandsNearby {
        val byBrand = HashMap<String, MutableList<Place>>()
        val brands = HashMap<String, Brand>()
        val independents = ArrayList<Place>()
        for (p in places) {
            if (!isTrade(p.tags) || Geo.distance(at, p.location) > radiusM) continue
            val brand = Brands.forTags(p.tags)
            if (brand != null) {
                brands[brand.id] = brand
                byBrand.getOrPut(brand.id) { ArrayList() } += p
            } else if (p.tags["name"] != null) {
                independents += p
            }
        }
        val near = compareBy<Place> { Geo.distance(at, it.location) }
        val chains = byBrand.map { (id, list) -> BrandBranches(brands.getValue(id), list.distinctBy { it.key }.sortedWith(near)) }
            .sortedWith(compareByDescending<BrandBranches> { it.branches.size }.thenBy { Geo.distance(at, it.branches.first().location) })
        return BrandsNearby(chains, independents.distinctBy { it.key }.sortedWith(near), origin)
    }
}
