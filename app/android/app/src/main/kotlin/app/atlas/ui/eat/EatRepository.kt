package app.atlas.ui.eat

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.Categories
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.services.PlaceOrigin
import app.atlas.services.TilePlaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToLong

/**
 * The eat & drink screens' shared state for one [Services]: the places around a point (loaded once and shared by
 * every screen), the table's time and party, the order pad's shortlist and the ratings. Places come in layers: the
 * offline place store when a downloaded region covers the point; otherwise the map tiles at once (names and kinds),
 * then Overpass when online (every tag).
 */
class EatRepository internal constructor(private val services: Services) {
    private val lang: String = Locale.getDefault().language

    /** When and for how many: the table's time and party, shared by every eat screen. */
    val table: TableState = TableState(services.clock)

    /** The places jotted on the order pad. */
    val shortlist: Shortlist = Shortlist(File(services.dirs.files, "eat/shortlist.tsv"))

    /** The guest check being written, and the bookmarked order. */
    val orders: OrderBook = OrderBook(File(services.dirs.files, "eat/order.txt"))

    /** Ratings for the places of an area, from Mangrove (open, no key) and the keyed providers the user enabled. */
    val ratings: EatRatings = EatRatings(services)

    private class Loaded(val version: Int, val flow: MutableStateFlow<EatArea>)

    private val areas = HashMap<String, Loaded>()

    /**
     * The places to eat and drink around [centre], loading in the background on first ask; then kept until the
     * offline files change ([version], `Services.regionsChanged`) or [refresh] forgets them.
     */
    fun area(centre: LatLon, version: Int = 0): StateFlow<EatArea> = synchronized(areas) {
        val key = keyOf(centre)
        areas[key]?.takeIf { it.version == version }?.let { return it.flow.asStateFlow() }
        val flow = MutableStateFlow(EatArea.empty(centre))
        areas[key] = Loaded(version, flow)
        services.scope.launch(Dispatchers.IO) { load(centre, flow) }
        flow.asStateFlow()
    }

    /**
     * Loads the places around [centre] again into the same flow (to retry the network), keeping what is shown until
     * the new answer comes. Does nothing while a load is still running.
     */
    fun refresh(centre: LatLon) {
        val flow = synchronized(areas) { areas[keyOf(centre)]?.flow } ?: return
        val now = flow.value
        if (now.loading) return
        flow.value = EatArea(now.centre, now.radiusM, now.places, now.origin, loading = true, country = now.country, zone = services.clock.zone)
        services.scope.launch(Dispatchers.IO) { load(now.centre, flow) }
    }

    private suspend fun load(centre: LatLon, out: MutableStateFlow<EatArea>) {
        val r = EatArea.RADIUS_M
        val online = !services.config.offline && services.connectivity.online.value
        try {
            fromStore(centre, r)?.let { out.value = it; return }
            val sketch = fromTiles(centre, r, online)
            if (sketch != null) out.value = sketch
            if (!online) {
                if (sketch == null) out.value = EatArea(centre, r, emptyList(), null, loading = false)
                return
            }
            out.value = try {
                fromOverpass(centre, r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A network failure, or an answer that does not parse (a captive portal's page).
                val why = (e as? IOException)?.message ?: e.message ?: e.javaClass.simpleName
                sketch?.let { it.copyWith(loading = false, error = why) } ?: EatArea(centre, r, emptyList(), null, loading = false, error = why)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.value = EatArea(centre, r, out.value.places, out.value.origin, loading = false, error = e.message ?: e.toString())
        }
    }

    private fun fromStore(centre: LatLon, r: Double): EatArea? {
        val store = services.placeStores
        if (store.size == 0) return null
        val found = store.inBox(BBox.around(centre, r))
            .asSequence()
            .map { it.place }
            .filter { EatKind.FILTER.matches(it.tags) && Categories.isPlace(it.tags) && Geo.distance(centre, it.location) <= r }
            .mapNotNull { EatPlace.of(it, centre, lang) }
            .sortedBy { it.distanceM }
            .toList()
        if (found.isEmpty()) return null
        return build(centre, r, found, PlaceOrigin.STORE, loading = false)
    }

    private suspend fun fromTiles(centre: LatLon, r: Double, more: Boolean): EatArea? {
        val src = withTimeoutOrNull(3_000) { services.tiles.filterNotNull().first() } ?: return null
        val found = try {
            TilePlaces.near(src, centre, r, EatKind.FILTER, limit = 5_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (found.isEmpty()) return null
        return build(centre, r, found.mapNotNull { EatPlace.of(it, centre, lang) }.sortedBy { it.distanceM }, PlaceOrigin.TILES, loading = more)
    }

    private suspend fun fromOverpass(centre: LatLon, r: Double): EatArea {
        val selectors = listOf(OverpassQl.oneOf("amenity", EatKind.amenityValues), OverpassQl.oneOf("shop", EatKind.shopValues))
        val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, r), timeoutSeconds = 30)
        val places = services.overpass.places(ql)
            .filter { EatKind.FILTER.matches(it.tags) && Categories.isPlace(it.tags) }
            .distinctBy { it.key }
            .mapNotNull { EatPlace.of(it, centre, lang) }
            .sortedBy { it.distanceM }
        return build(centre, r, places, PlaceOrigin.ONLINE, loading = false)
    }

    private fun build(centre: LatLon, r: Double, places: List<EatPlace>, origin: PlaceOrigin, loading: Boolean) =
        EatArea(centre, r, places, origin, loading, country = EatArea.countryOf(places), zone = services.clock.zone)

    private fun EatArea.copyWith(loading: Boolean, error: String?) =
        EatArea(centre, radiusM, places, origin, loading, error, country, services.clock.zone)

    companion object {
        private val all = WeakHashMap<Services, EatRepository>()

        /** The eat screens' state for [services], made on first use. */
        fun of(services: Services): EatRepository = synchronized(all) { all.getOrPut(services) { EatRepository(services) } }

        /** Points within about 100 m share an area. */
        private fun keyOf(p: LatLon): String = "${(p.lat * 1000).roundToLong()}:${(p.lon * 600).roundToLong()}"
    }
}
