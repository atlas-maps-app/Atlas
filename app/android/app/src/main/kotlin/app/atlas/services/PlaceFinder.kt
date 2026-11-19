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
 * nothing, the map's packs and windows on the phone ([LocalData.inPacks]: offline, every tag), online the windows the
 * phone keeps no data for from Atlas's packs, and Overpass when [online] where they do not hold the whole circle (every
 * tag, so opening hours and facts), asked beside them where they cannot make it whole ([LocalData.beside]).
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
        // online, the map's windows the phone keeps no data for from Atlas's packs: waited for where they can make the
        // circle whole, else beside Overpass, asked at once (batch 5 A1); a part they add to what was shown, as it comes
        val op = overpass()
        val selectors = OverpassSelectors.of(filter)
        val ask = if (op == null || !online() || selectors == null) null else suspend {
            asked {
                val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
                op.places(ql).filter { filter.matches(it.tags) && Categories.isPlace(it.tags) }
            }
        }
        var shown = packed
        // every place shown, by key, for a failure's answer
        val seen = LinkedHashMap<String, Place>()
        packed?.value?.forEach { seen[it.key] = it }
        val answer = beside(centre, radiusM, filter, limit, show = { w ->
            val before = shown
            if (before == null || w.partial != before.partial || w.value.map { it.key } != before.value.map { it.key }) emit(NearbyPlaces(w.value, PlaceOrigin.PACK))
            shown = w
            w.value.forEach { seen[it.key] = it }
        }, ask = ask) ?: return@flow
        emit(
            answer.fold(
                { found -> NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.ONLINE) },
                // An answer that does not parse (a captive portal's page, a broken proxy) is a failure to report too.
                { e -> failed(centre, radiusM, filter, limit, why(e), seen.values.toList().takeIf { it.isNotEmpty() }?.let { NearbyPlaces(it, PlaceOrigin.PACK) }) },
            ),
        )
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
            // the kinds whose circle the phone does not hold whole
            val lacking = LinkedHashMap<String, TagFilter>()
            for (id in categoryIds.distinct()) {
                val category = Categories.byId(id) ?: continue
                val filter = filterOf(category)
                val found = inStore(category, centre, radiusM)
                if (found != null) {
                    out[id] = NearbyPlaces(nearest(found, centre, limit), PlaceOrigin.STORE)
                    continue
                }
                // as [nearby]: what the phone holds; a whole circle is the answer, a part of one only the first answer
                val packed = inPacks(centre, radiusM, filter, limit)
                if (packed != null) {
                    out[id] = NearbyPlaces(packed.value, PlaceOrigin.PACK)
                    if (!packed.partial) continue
                }
                lacking[id] = filter
            }
            if (lacking.isEmpty()) return@withContext out
            val ask = lacking.filterValues { OverpassSelectors.of(it) != null }
            val op = overpass()
            val query = if (ask.isEmpty() || op == null || !online()) null else suspend {
                asked {
                    val ql = OverpassQl.places(ask.values.flatMap { OverpassSelectors.of(it)!! }.distinct(), OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
                    withTimeoutOrNull(onlineMs) { op.places(ql).filter { Categories.isPlace(it.tags) } }
                        ?: throw IOException("Overpass did not answer in ${onlineMs / 1000} s")
                }
            }
            // the windows of the circle the phone keeps no data for, asked of Atlas's packs once for every kind (the app
            // maintainer, 18 November 2026, batch 4 B14: each of the wheel's eleven waited again, up to 10 s): waited for
            // where they can make the circle whole, else beside Overpass's one query, and where it fails for what is left
            // of the wait (batch 5 A1); what they brought, every kind's, is kept for a failure's answer: the wheel
            // shows nothing till it answers, so it is what it had (the app maintainer, 19 November 2026, batch 6 A3:
            // another lookup could let the windows go before Overpass failed, where 38ddafa1 waited for them and kept
            // them)
            var landed: List<Place> = emptyList()
            val answer = beside(centre, radiusM, TagFilter.Or(lacking.values.toList()), Int.MAX_VALUE, show = { landed = it.value }, ask = query)
            for ((id, filter) in lacking) {
                val got = if (answer != null && id in ask) {
                    answer.fold(
                        { all -> NearbyPlaces(nearest(all.filter { filter.matches(it.tags) }, centre, limit), PlaceOrigin.ONLINE) },
                        // where Overpass fails, the part the phone has by now stays, with what the windows brought and
                        // what went wrong
                        { e ->
                            val came = landed.filter { filter.matches(it.tags) }
                            failed(centre, radiusM, filter, limit, why(e), if (came.isEmpty()) out[id] else NearbyPlaces(out[id]?.places.orEmpty() + came, PlaceOrigin.PACK))
                        },
                    )
                } else {
                    // not asked: what the phone holds once the windows came, with what they brought (a pack that does
                    // not read holds nothing there: the app maintainer, 19 November 2026, batch 6 A5)
                    val now = inPacks(centre, radiusM, filter, limit)
                    val came = landed.filter { filter.matches(it.tags) }
                    if (now == null && came.isEmpty()) null else NearbyPlaces(nearest(now?.value.orEmpty() + came, centre, limit), PlaceOrigin.PACK)
                }
                if (got != null) out[id] = got
            }
            out
        }

    /**
     * Where Overpass failed with [why]: what the map's packs and windows on the phone hold by now ([inPacks]), with the
     * windows the packs were asked for that came meanwhile (the app maintainer, 18 November 2026: the answer was empty
     * where it had held them), and every place [had] (shown, or brought by the windows), each once, now's copy first,
     * nearest first, at most [limit] (19 November 2026, batch 6 A3: what they held by now could be fewer than were
     * shown, where another lookup had let the windows go meanwhile); else what was [had], else none.
     */
    private fun failed(centre: LatLon, radiusM: Double, filter: TagFilter, limit: Int, why: String, had: NearbyPlaces? = null): NearbyPlaces {
        val all = nearest(inPacks(centre, radiusM, filter, limit)?.value.orEmpty() + had?.places.orEmpty(), centre, limit)
        return when {
            all.isNotEmpty() -> NearbyPlaces(all, PlaceOrigin.PACK, why)
            had != null -> had.copy(error = why)
            else -> NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, why)
        }
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

    /** What the map's packs and windows on the phone hold of [filter] within [radiusM] of [centre]; null where none holds any of it. */
    private fun inPacks(centre: LatLon, radiusM: Double, filter: TagFilter, limit: Int): LocalAnswer<List<Place>>? = try {
        local()?.inPacks(centre, radiusM, filter, limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * [ask]'s answer, online, with the windows the phone keeps no data for from Atlas's packs beside it ([LocalData.beside]:
     * [show] gets [inPacks]' answer once they came); null where it was not asked (the windows made the circle whole, or
     * there is no [ask]). Offline or with nothing on the phone: [ask] alone.
     */
    private suspend fun beside(
        centre: LatLon,
        radiusM: Double,
        filter: TagFilter,
        limit: Int,
        show: suspend (LocalAnswer<List<Place>>) -> Unit = {},
        ask: (suspend () -> Result<List<Place>>)?,
    ): Result<List<Place>>? {
        val l = try {
            if (online()) local() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (l == null) ask?.invoke() else l.beside(centre, radiusM, filter, limit, show, { it.isFailure }, ask)
    }

    /** [ask]'s places, or why it failed: the network, or an answer that does not parse (a captive portal's page). */
    private suspend fun asked(ask: suspend () -> List<Place>): Result<List<Place>> = try {
        Result.success(ask())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** What went wrong, as the screens say it. */
    private fun why(e: Throwable): String = e.message ?: if (e is IOException) "offline" else e.toString()

    companion object {
        /**
         * What a category finds: its rule and the places of other kinds behind its door (a kiosk that takes the post in
         * Post, the app maintainer, 18 November 2026, batch 4 A6); a group's, its leaves' together.
         */
        fun filterOf(category: Category): TagFilter =
            category.door ?: TagFilter.Or(Categories.children(category.id).mapNotNull { it.door })

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
