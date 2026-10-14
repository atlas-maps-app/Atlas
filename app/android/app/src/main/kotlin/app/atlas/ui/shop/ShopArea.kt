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
import app.atlas.services.OverpassSelectors
import app.atlas.services.PlaceOrigin
import app.atlas.services.TilePlaces
import app.atlas.tiles.TileSource
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
    /** True when the places carry every tag (opening hours, brands): from a region on the phone or Overpass. */
    val full: Boolean get() = origin != PlaceOrigin.TILES

    companion object {
        val Loading = AreaPlaces(emptyList(), PlaceOrigin.TILES, loading = true)
    }
}

/**
 * Finds the places a shop screen shows, in layers like the rest of Atlas: the downloaded region's place store first
 * (offline, every tag); where the phone has no region for the area, the map tiles' points of interest (offline, names
 * and kinds only) and then Overpass when online (every tag). One query per screen, with the union of everything it
 * shows; the screen sorts the answer into its shelves itself. Answers are kept for a few minutes per area, so going
 * from the pantry into a shelf and back does not ask again.
 */
internal class ShopFinder(
    private val store: () -> PlaceSource,
    private val tiles: () -> TileSource?,
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
        if (s.size > 0 && s.bounds?.contains(centre) == true) {
            val box = BBox.around(centre, radiusM)
            val found = s.byFilter(filter, box).map { it.place }
            val answer = AreaPlaces(nearest(found, centre, radiusM, limit), PlaceOrigin.STORE)
            remember(cacheKey, answer)
            emit(answer)
            return@flow
        }
        val sketch = tiles()?.let { src ->
            try {
                TilePlaces.near(src, centre, radiusM, filter, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }.orEmpty()
        val op = overpass()
        val canAsk = op != null && online()
        emit(AreaPlaces(sketch, PlaceOrigin.TILES, loading = canAsk))
        if (!canAsk) return@flow
        val selectors = OverpassSelectors.of(filter) ?: return@flow emit(AreaPlaces(sketch, PlaceOrigin.TILES))
        val answer = try {
            val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, radiusM), timeoutSeconds = 25)
            val found = op!!.places(ql).filter { filter.matches(it.tags) }
            AreaPlaces(nearest(found, centre, radiusM, limit), PlaceOrigin.ONLINE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AreaPlaces(sketch, PlaceOrigin.TILES, error = e.message ?: "offline")
        } catch (e: Exception) {
            AreaPlaces(sketch, PlaceOrigin.TILES, error = e.message ?: e.toString())
        }
        if (answer.error == null) remember(cacheKey, answer)
        emit(answer)
    }.flowOn(Dispatchers.IO)

    private fun remember(key: String, value: AreaPlaces) = synchronized(kept) { kept[key] = Kept(nowMillis(), value) }

    /** Forgets every kept answer (a region was downloaded or the user pulled to refresh). */
    fun forget() = synchronized(kept) { kept.clear() }

    companion object {
        private const val KEEP_MS = 5 * 60_000L

        /** For [services]: its place stores, tiles, Overpass and connectivity. */
        fun of(services: Services) = ShopFinder(
            store = { services.placeStores },
            tiles = { services.tiles.value },
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
