package app.atlas.ui.shop

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.TagFilter
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.search.PlaceSource
import app.atlas.search.byFilter
import app.atlas.search.covers
import app.atlas.services.OverpassSelectors
import app.atlas.services.PlaceOrigin
import app.atlas.services.LocalData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.Locale

/**
 * What a shop screen knows about the places around: the places (nearest first), where they came from, and why the
 * online answer failed when it did. [loading] while a better answer may still come.
 */
internal data class AreaPlaces(
    val places: List<Place>,
    val origin: PlaceOrigin,
    val error: String? = null,
    val loading: Boolean = false,
) {
    companion object {
        val Loading = AreaPlaces(emptyList(), PlaceOrigin.PACK, loading = true)
    }
}

/**
 * Finds the places a shop screen shows, in layers like the rest of Atlas: the downloaded region's place store first
 * (offline, every tag); where the phone has no region for the area, the map's packs and windows on the phone (offline,
 * every tag), online the windows it keeps no data for from Atlas's packs, and Overpass where they do not hold the whole
 * circle, asked beside them where they cannot make it whole ([LocalData.beside]). One query per screen, with the union of
 * everything it shows; the screen sorts the answer into its shelves itself. Answers are kept for a few minutes per
 * area, so going from the pantry into a shelf and back does not ask again.
 */
internal class ShopFinder(
    private val store: () -> PlaceSource,
    private val local: () -> LocalData?,
    private val overpass: () -> app.atlas.providers.Overpass?,
    private val online: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Kept(val at: Long, val value: AreaPlaces)

    private val kept = object : LinkedHashMap<String, Kept>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > 24
    }

    /**
     * The places within [radiusM] of [centre] that pass [filter], nearest first, as they become known. [key] names the
     * query for the cache (a screen's own name).
     */
    fun around(key: String, filter: TagFilter, centre: LatLon, radiusM: Double, limit: Int = 600): Flow<AreaPlaces> = flow {
        val cacheKey = String.format(Locale.ROOT, "%s@%.3f,%.3f/%d", key, centre.lat, centre.lon, radiusM.toInt())
        synchronized(kept) { kept[cacheKey] }?.takeIf { nowMillis() - it.at < KEEP_MS }?.let {
            emit(it.value)
            return@flow
        }
        val s = store()
        if (s.covers(centre)) {
            val box = BBox.around(centre, radiusM)
            val found = s.byFilter(filter, box).map { it.place }
            val answer = AreaPlaces(nearest(found, centre, radiusM, limit), PlaceOrigin.STORE)
            remember(cacheKey, answer)
            emit(answer)
            return@flow
        }
        val packed = inPacks(filter, centre, radiusM, limit)
        var sketch = packed?.value.orEmpty()
        val op = overpass()
        // packs and windows around the whole circle answer fully, with every tag
        val canAsk = op != null && online() && packed?.partial != false
        emit(AreaPlaces(sketch, PlaceOrigin.PACK, loading = canAsk))
        if (!canAsk) {
            if (packed?.partial == false) remember(cacheKey, AreaPlaces(sketch, PlaceOrigin.PACK))
            return@flow
        }
        val selectors = OverpassSelectors.of(filter)
        val ask = if (selectors == null) null else suspend {
            try {
                val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
                Result.success(op!!.places(ql).filter { filter.matches(it.tags) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        // the map's windows the phone keeps no data for, from Atlas's packs: waited for where they can make the circle
        // whole, else beside Overpass, asked at once (the app maintainer, 18 November 2026, batch 5 A1)
        var whole = false
        var loading = true
        val answer = beside(filter, centre, radiusM, limit, show = { w ->
            sketch = w.value
            whole = !w.partial
            loading = w.partial && ask != null
            emit(AreaPlaces(sketch, PlaceOrigin.PACK, loading = loading))
        }, ask = ask)
        if (answer == null) {
            // not asked: the windows made the circle whole, or the filter cannot be asked of Overpass
            if (whole) remember(cacheKey, AreaPlaces(sketch, PlaceOrigin.PACK)) else if (loading) emit(AreaPlaces(sketch, PlaceOrigin.PACK))
            return@flow
        }
        val result = answer.fold(
            { found -> AreaPlaces(nearest(found, centre, radiusM, limit), PlaceOrigin.ONLINE) },
            { e -> failed(sketch, filter, centre, radiusM, limit, e.message ?: if (e is IOException) "offline" else e.toString()) },
        )
        if (result.error == null) remember(cacheKey, result)
        emit(result)
    }.flowOn(Dispatchers.IO)

    /** What the map's packs and windows on the phone hold of [filter] around [centre] ([LocalData.inPacks]); null where none holds any. */
    private fun inPacks(filter: TagFilter, centre: LatLon, radiusM: Double, limit: Int) = try {
        local()?.inPacks(centre, radiusM, filter, limit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * [ask]'s answer with the windows the phone keeps no data for from Atlas's packs beside it ([LocalData.beside]:
     * [show] gets [inPacks]' answer once they came); null where it was not asked (the windows made the circle whole, or
     * there is no [ask]). With nothing on the phone: [ask] alone.
     */
    private suspend fun beside(
        filter: TagFilter,
        centre: LatLon,
        radiusM: Double,
        limit: Int,
        show: suspend (app.atlas.services.LocalAnswer<List<Place>>) -> Unit,
        ask: (suspend () -> Result<List<Place>>)?,
    ): Result<List<Place>>? {
        val l = try {
            local()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (l == null) ask?.invoke() else l.beside(centre, radiusM, filter, limit, show, { it.isFailure }, ask)
    }

    /**
     * Where Overpass failed with [why]: what the packs and windows on the phone hold by now, with the windows the packs
     * were asked for that came meanwhile (the app maintainer, 18 November 2026: the answer kept only [sketch], from
     * before they came), else [sketch].
     */
    private fun failed(sketch: List<Place>, filter: TagFilter, centre: LatLon, radiusM: Double, limit: Int, why: String) =
        AreaPlaces(inPacks(filter, centre, radiusM, limit)?.value?.takeIf { it.isNotEmpty() } ?: sketch, PlaceOrigin.PACK, error = why)

    private fun remember(key: String, value: AreaPlaces) = synchronized(kept) { kept[key] = Kept(nowMillis(), value) }

    /** Forgets every kept answer (a region was downloaded or the user pulled to refresh). */
    fun forget() = synchronized(kept) { kept.clear() }

    companion object {
        private const val KEEP_MS = 5 * 60_000L

        /** For [services]: its place stores, the map's packs, Overpass and connectivity. */
        fun of(services: Services) = ShopFinder(
            store = { services.placeStores },
            local = { services.localData },
            overpass = { if (services.config.offline) null else services.overpass },
            online = { !services.config.offline && services.connectivity.online.value },
        )

        /** [places] within [radiusM] of [centre], each once, nearest first, at most [limit]. */
        fun nearest(places: List<Place>, centre: LatLon, radiusM: Double, limit: Int): List<Place> =
            places.asSequence()
                .distinctBy { it.key }
                .map { it to Geo.distance(centre, it.location) }
                .filter { it.second <= radiusM }
                .sortedBy { it.second }
                .take(limit)
                .map { it.first }
                .toList()
    }
}
