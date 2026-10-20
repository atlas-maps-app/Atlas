package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.Category
import app.atlas.places.TagFilter
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.search.PlaceSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** Where a list of places came from: a downloaded region's place store, Atlas's own map packs, or Overpass online. */
enum class PlaceOrigin { STORE, PACK, ONLINE }

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
 * nothing, the map's packs on the phone ([LocalData.inPacks]: offline, every tag), and Overpass when [online] where no
 * pack holds the whole circle (every tag, so opening hours and facts).
 */
class LayeredPlaceFinder(
    private val local: () -> LocalData?,
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
            // A region on the phone answers fully: no need for the packs or the network.
            if (found.isNotEmpty()) {
                emit(NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.STORE))
                return@flow
            }
        }
        val packed = try {
            local()?.inPacks(centre, radiusM, filter, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (packed != null) {
            emit(NearbyPlaces(packed.value, PlaceOrigin.PACK))
            // Packs around the whole circle answer fully, with every tag: nothing more to ask.
            if (!packed.partial) return@flow
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
