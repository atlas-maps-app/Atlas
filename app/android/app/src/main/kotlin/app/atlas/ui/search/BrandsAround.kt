package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.TagFilter
import app.atlas.search.PlaceRecord
import app.atlas.search.covers
import app.atlas.services.PlaceOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One chain around: the brand and its branches, nearest first. */
data class BrandBranches(val brand: Brand, val branches: List<Place>)

/** The chains around of one kind, [kind] (as the category tree files most of their branches), most branches first. */
data class BrandStack(val kind: CategoryNode?, val chains: List<BrandBranches>) {
    /** Its kind's id, or its one chain's brand's where it has no kind. */
    val id: String get() = kind?.id ?: chains.first().brand.id
}

/** The places around a point split into chains (with their branches) and independents, and where they came from. */
data class BrandsNearby(val chains: List<BrandBranches>, val independents: List<Place>, val origin: PlaceOrigin?)

/**
 * Chains and independents around a point: from the place store of a downloaded region where it covers the point, else
 * from the map's packs on the phone (every tag, matched to brands by the name-suggestion index), else Overpass online.
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
            store.covers(at) -> store.inBox(BBox.around(at, radiusM)).map { it.place } to PlaceOrigin.STORE
            else -> {
                val got = runCatching { services.localData.places(at, radiusM, TagFilter.Has("name"), 5_000) }.getOrNull()
                got?.value.orEmpty() to got?.origin
            }
        }
        split(places, at, radiusM, origin)
    }

    /**
     * [chains] stacked by kind (the owner, 6 November: "Brands around stacks by kind?"): each chain on the kind most of
     * its branches are, so that the pizza chains around share one stack and the shoe chains another; the stacks in the
     * order their first chains come in [chains], the one with the most branches first.
     */
    fun stacks(chains: List<BrandBranches>): List<BrandStack> =
        chains.map { c -> c to kindOf(c) }
            .groupBy { (_, kind) -> kind?.id }
            .values.map { stack -> BrandStack(stack.first().second, stack.map { it.first }) }

    /** The kind most of [chain]'s branches are, as the category tree files them; of kinds as many, its nearest's. */
    private fun kindOf(chain: BrandBranches): CategoryNode? =
        chain.branches.map { CategoryTree.of(it.tags, PlaceRecord.geometryOf(it)) }.groupBy { it?.id }.values.maxByOrNull { it.size }?.first()

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
