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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Each of [categoryIds]' first answer as [nearby] gives it, by id; none where nothing answers. Here one category
     * after another; [LayeredPlaceFinder] asks all those the phone cannot answer in one Overpass query, waited for no
     * longer than [onlineMs].
     */
    suspend fun nearbyMany(categoryIds: List<String>, centre: LatLon, radiusM: Double, limit: Int, onlineMs: Long = NEARBY_ONLINE_MS): Map<String, NearbyPlaces> {
        val out = LinkedHashMap<String, NearbyPlaces>()
        for (id in categoryIds.distinct()) {
            val first = try {
                withTimeoutOrNull(onlineMs) { nearby(id, centre, radiusM, limit).firstOrNull() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (first != null) out[id] = first
        }
        return out
    }
}

/** How long [PlaceFinder.nearbyMany] waits for Overpass's one answer: three times what the Where wheel gave each of its eleven. */
const val NEARBY_ONLINE_MS = 15_000L

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
        // A region on the phone answers fully: no need for the packs or the network.
        val found = inStore(category, centre, radiusM)
        if (found != null) {
            emit(NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.STORE))
            return@flow
        }
        val packed = inPacks(centre, radiusM, filter, limit)
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

    /**
     * Each category's first answer, as [nearby] gives it: a region's store, else the map's packs, else Overpass, where
     * every category the phone cannot answer is asked in one query, its answer sorted into them by their rules on the
     * phone. The Where wheel asks its eleven so, where it asked Overpass for one category after another, each cut at 5 s
     * (the user, 30 October: "are all sources reliable and respond fast?").
     */
    override suspend fun nearbyMany(categoryIds: List<String>, centre: LatLon, radiusM: Double, limit: Int, onlineMs: Long): Map<String, NearbyPlaces> =
        withContext(Dispatchers.Default) {
            val out = LinkedHashMap<String, NearbyPlaces>()
            val ask = LinkedHashMap<String, Pair<TagFilter, List<String>>>()
            for (id in categoryIds.distinct()) {
                val category = Categories.byId(id) ?: continue
                val filter = filterOf(category)
                val found = inStore(category, centre, radiusM)
                if (found != null) {
                    out[id] = NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.STORE)
                    continue
                }
                val packed = inPacks(centre, radiusM, filter, limit)
                if (packed != null) {
                    out[id] = NearbyPlaces(packed.value, PlaceOrigin.PACK)
                    continue
                }
                val selectors = OverpassSelectors.of(filter) ?: continue
                ask[id] = filter to selectors
            }
            val op = overpass()
            if (ask.isEmpty() || op == null || !online()) return@withContext out
            val answer = try {
                val ql = OverpassQl.places(ask.values.flatMap { it.second }.distinct(), OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
                withTimeoutOrNull(onlineMs) { Result.success(op.places(ql).filter { Categories.isPlace(it.tags) }) }
                    ?: Result.failure(IOException("Overpass did not answer in ${onlineMs / 1000} s"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a failure of the network, or an answer that does not parse (a captive portal's page): said, not thrown
                Result.failure(e)
            }
            for ((id, f) in ask) {
                out[id] = answer.fold(
                    { all -> NearbyPlaces(nearest(all.filter { f.first.matches(it.tags) }, centre, limit), PlaceOrigin.ONLINE) },
                    { e -> NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, e.message ?: if (e is IOException) "offline" else e.toString()) },
                )
            }
            out
        }

    /** What a region's store holds of [category] within [radiusM] of [centre]; null where it holds none, or there is none. */
    private fun inStore(category: Category, centre: LatLon, radiusM: Double): List<Place>? {
        val s = store()?.takeIf { it.size > 0 } ?: return null
        val ids = if (category.isGroup) Categories.children(category.id).map { it.id } else listOf(category.id)
        return s.byCategory(ids, BBox.around(centre, radiusM))
            .map { it.place }
            .filter { Geo.distance(centre, it.location) <= radiusM }
            .takeIf { it.isNotEmpty() }
    }

    /** What the map's packs on the phone hold of [filter] within [radiusM] of [centre]; null where none holds the centre. */
    private fun inPacks(centre: LatLon, radiusM: Double, filter: TagFilter, limit: Int): LocalAnswer<List<Place>>? = try {
        local()?.inPacks(centre, radiusM, filter, limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

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
