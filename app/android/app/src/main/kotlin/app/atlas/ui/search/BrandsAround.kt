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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map

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

    /** The chains and independents within [radiusM] of [at]: [nearAsFound]'s last answer, the whole. */
    suspend fun near(services: Services, at: LatLon, radiusM: Double): BrandsNearby = nearAsFound(services, at, radiusM).last()

    /**
     * The chains and independents within [radiusM] of [at] as they are found: a region's store where it covers [at];
     * else the map's packs and windows on the phone at once, then online what completes them ([LocalData.placesAsFound]).
     */
    fun nearAsFound(services: Services, at: LatLon, radiusM: Double): Flow<BrandsNearby> = flow {
        val store = services.placeStores
        val country = HereContext.country(services, at)
        if (store.covers(at)) {
            emit(split(store.inBox(BBox.around(at, radiusM)).map { it.place }, at, radiusM, PlaceOrigin.STORE, country))
            return@flow
        }
        emitAll(
            services.localData.placesAsFound(at, radiusM, TagFilter.Has("name"), 5_000)
                .map { split(it.value, at, radiusM, it.origin, country) }
                .catch { emit(split(emptyList(), at, radiusM, null, country)) },
        )
    }.flowOn(Dispatchers.Default)

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

    /**
     * Splits [places] within [radiusM] of [at] into chains (most branches first) and named independents (nearest first);
     * the chains as they trade in [country], the country of the map there ([Brands.forTags]).
     */
    fun split(places: List<Place>, at: LatLon, radiusM: Double, origin: PlaceOrigin?, country: String?): BrandsNearby {
        val byBrand = HashMap<String, MutableList<Place>>()
        val brands = HashMap<String, Brand>()
        val independents = ArrayList<Place>()
        for (p in places) {
            if (!isTrade(p.tags) || Geo.distance(at, p.location) > radiusM) continue
            val brand = Brands.forTags(p.tags, country)
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
