package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.TagFilter
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.search.byFilter
import app.atlas.services.OverpassSelectors
import app.atlas.services.PlaceOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A place with how far it is from where the search began. */
class Found(val place: Place, val distanceM: Double)

/** The places a search found, nearest first, where they came from and why the network failed when it did. */
class FindResult(val places: List<Found>, val origin: PlaceOrigin, val error: String? = null) {
    /** True when the answer is "none": a source answered and had nothing. */
    val empty: Boolean get() = places.isEmpty()

    companion object {
        val NONE = FindResult(emptyList(), PlaceOrigin.STORE)
    }
}

/**
 * The local screens' way to ask "what of this kind is near me": the downloaded regions' place store first (offline,
 * every tag), then Overpass when the store has nothing and the phone is online. Each screen names what it wants as a
 * [TagFilter] in its text form (`amenity=marketplace`, `leisure=park and name`).
 */
class LocalFinder(private val services: Services) {
    /** Tests: answer [filterText] from here instead of the store and the network. */
    @Volatile
    var fake: ((TagFilter, LatLon, Double) -> List<Place>)? = null

    suspend fun find(filterText: String, centre: LatLon, radiusM: Double, limit: Int = 60, online: Boolean = true, out: String = OUT_CENTRE): FindResult =
        find(TagFilter.parse(filterText), centre, radiusM, limit, online, out)

    suspend fun find(filter: TagFilter, centre: LatLon, radiusM: Double, limit: Int = 60, online: Boolean = true, out: String = OUT_CENTRE): FindResult = withContext(Dispatchers.Default) {
        fake?.let { f -> return@withContext FindResult(rank(f(filter, centre, radiusM), centre, radiusM, limit), PlaceOrigin.STORE) }
        val store = services.placeStores
        if (store.size > 0) {
            val inStore = store.byFilter(filter, BBox.around(centre, radiusM)).map { it.place }
            if (inStore.isNotEmpty()) return@withContext FindResult(rank(inStore, centre, radiusM, limit), PlaceOrigin.STORE)
        }
        if (!online || !services.local.online()) return@withContext FindResult(emptyList(), PlaceOrigin.STORE)
        val selectors = OverpassSelectors.of(filter) ?: return@withContext FindResult(emptyList(), PlaceOrigin.ONLINE, "query too wide")
        try {
            val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 25, out = out)
            FindResult(rank(services.overpass.places(ql).filter { filter.matches(it.tags) }, centre, radiusM, limit), PlaceOrigin.ONLINE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FindResult(emptyList(), PlaceOrigin.ONLINE, e.message ?: "offline")
        } catch (e: Exception) {
            FindResult(emptyList(), PlaceOrigin.ONLINE, e.message ?: e.toString())
        }
    }

    /**
     * Several kinds at once, by name: each answered from the place store, or, when the store has none of any, by one Overpass
     * query for them all (one request, not one per kind), the answer sorted into the kinds by their filters.
     */
    suspend fun findMany(kinds: Map<String, TagFilter>, centre: LatLon, radiusM: Double, limit: Int = 60, online: Boolean = true): Map<String, FindResult> = withContext(Dispatchers.Default) {
        fake?.let { f ->
            val all = f(TagFilter.Or(kinds.values.toList()), centre, radiusM)
            return@withContext kinds.mapValues { (_, flt) -> FindResult(rank(all.filter { flt.matches(it.tags) }, centre, radiusM, limit), PlaceOrigin.STORE) }
        }
        val store = services.placeStores
        if (store.size > 0) {
            val box = BBox.around(centre, radiusM)
            val answers = kinds.mapValues { (_, flt) -> store.byFilter(flt, box).map { it.place } }
            if (answers.values.any { it.isNotEmpty() }) return@withContext answers.mapValues { (_, places) -> FindResult(rank(places, centre, radiusM, limit), PlaceOrigin.STORE) }
        }
        val none = kinds.mapValues { FindResult(emptyList(), PlaceOrigin.STORE) }
        if (!online || !services.local.online()) return@withContext none
        val selectors = kinds.values.flatMap { OverpassSelectors.of(it) ?: return@withContext kinds.mapValues { FindResult(emptyList(), PlaceOrigin.ONLINE, "query too wide") } }.distinct()
        try {
            val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 40)
            val all = services.overpass.places(ql)
            kinds.mapValues { (_, flt) -> FindResult(rank(all.filter { flt.matches(it.tags) }, centre, radiusM, limit), PlaceOrigin.ONLINE) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            kinds.mapValues { FindResult(emptyList(), PlaceOrigin.ONLINE, e.message ?: e.toString()) }
        }
    }

    companion object {
        /** Overpass's usual answer here: tags and a centre. */
        const val OUT_CENTRE = "out tags center qt;"

        /** With each way's box too, for footprints. */
        const val OUT_BOX = "out tags center bb qt;"
    }

    private fun rank(places: List<Place>, centre: LatLon, radiusM: Double, limit: Int): List<Found> =
        places.distinctBy { it.key }.map { Found(it, Geo.distance(centre, it.location)) }.filter { it.distanceM <= radiusM }.sortedBy { it.distanceM }.take(limit)
}
