package app.atlas.ui.eat

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.Categories
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassQl
import app.atlas.services.PlaceOrigin
import app.atlas.ui.search.HereContext
import app.atlas.ui.search.PlaceLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** Most recently asked last: walking about asks for a new area every hundred metres, so the oldest are let go. */
    private val areas = LinkedHashMap<String, Loaded>()

    /**
     * The places to eat and drink around [centre], loading in the background on first ask; then kept until the
     * offline files change ([version], `Services.regionsChanged`) or [refresh] forgets them.
     */
    fun area(centre: LatLon, version: Int = 0): StateFlow<EatArea> = synchronized(areas) {
        val key = keyOf(centre)
        areas.remove(key)?.takeIf { it.version == version }?.let { areas[key] = it; return it.flow.asStateFlow() }
        val flow = MutableStateFlow(EatArea.empty(centre, zone = services.clock.zone))
        areas[key] = Loaded(version, flow)
        while (areas.size > MAX_AREAS) areas.remove(areas.keys.first())
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
        flow.value = EatArea(now.centre, now.radiusM, now.places, now.origin, loading = true, local = now.local)
        services.scope.launch(Dispatchers.IO) { load(now.centre, flow) }
    }

    private suspend fun load(centre: LatLon, out: MutableStateFlow<EatArea>) {
        val r = EatArea.RADIUS_M
        val online = !services.config.offline && services.connectivity.online.value
        // the country and clock here, the one way for every screen (the regions on the phone are asked: on this thread)
        val local = HereContext.localeAt(services, centre, HereContext.phoneAt(services), services.clock.zone)
        try {
            fromStore(centre, r, local)?.let { out.value = it; return }
            var (sketch, whole) = fromPacks(centre, r, online, local)
            if (sketch != null) out.value = sketch
            // packs and windows around the whole circle answer fully, with every tag
            if (!online || whole) {
                if (sketch == null) out.value = EatArea(centre, r, emptyList(), null, loading = false, local = local)
                return
            }
            // online, the map's windows the phone keeps no data for from Atlas's packs, before Overpass
            val (windows, held) = fromWindowPacks(centre, r, local)
            if (windows != null) {
                sketch = windows
                out.value = windows
            }
            if (held) return
            out.value = try {
                fromOverpass(centre, r, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A network failure, or an answer that does not parse (a captive portal's page): what the packs and windows
                // hold by now, with the windows the packs were asked for that came meanwhile, else what was shown
                val why = (e as? IOException)?.message ?: e.message ?: e.javaClass.simpleName
                val now = fromPacks(centre, r, more = false, local).first
                (now ?: sketch)?.copyWith(loading = false, error = why) ?: EatArea(centre, r, emptyList(), null, loading = false, local = local, error = why)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.value = EatArea(centre, r, out.value.places, out.value.origin, loading = false, local = local, error = e.message ?: e.toString())
        }
    }

    private fun fromStore(centre: LatLon, r: Double, local: PlaceLocale): EatArea? {
        val store = services.placeStores
        if (store.size == 0) return null
        val found = store.inBox(BBox.around(centre, r))
            .asSequence()
            .map { it.place }
            .filter { EatKind.FILTER.matches(it.tags) && Categories.isPlace(it.tags) && Geo.distance(centre, it.location) <= r }
            .distinctBy { it.key }
            .mapNotNull { EatPlace.of(it, centre, lang) }
            .sortedBy { it.distanceM }
            .toList()
        if (found.isEmpty()) return null
        return build(centre, r, found, PlaceOrigin.STORE, loading = false, local)
    }

    /**
     * The places the map's packs and windows on the phone hold around [centre] (every tag), [more] when Overpass will be
     * asked after them; and whether they hold the whole circle. Null places when none has any.
     */
    private fun fromPacks(centre: LatLon, r: Double, more: Boolean, local: PlaceLocale): Pair<EatArea?, Boolean> {
        val got = try {
            services.localData.inPacks(centre, r, EatKind.FILTER, limit = 5_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null to false
        return packArea(centre, r, got, more, local)
    }

    /** [fromPacks] once the map's windows the phone keeps no data for came from Atlas's packs ([LocalData.inWindowPacks]). */
    private suspend fun fromWindowPacks(centre: LatLon, r: Double, local: PlaceLocale): Pair<EatArea?, Boolean> {
        val got = try {
            services.localData.inWindowPacks(centre, r, EatKind.FILTER, limit = 5_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null to false
        return packArea(centre, r, got, more = true, local)
    }

    private fun packArea(centre: LatLon, r: Double, got: app.atlas.services.LocalAnswer<List<app.atlas.model.Place>>, more: Boolean, local: PlaceLocale): Pair<EatArea?, Boolean> {
        val whole = !got.partial
        val found = got.value.filter { Categories.isPlace(it.tags) }.mapNotNull { EatPlace.of(it, centre, lang) }.sortedBy { it.distanceM }
        if (found.isEmpty() && !whole) return null to false
        return build(centre, r, found, PlaceOrigin.PACK, loading = more && !whole, local) to whole
    }

    private suspend fun fromOverpass(centre: LatLon, r: Double, local: PlaceLocale): EatArea {
        val selectors = listOf(OverpassQl.oneOf("amenity", EatKind.amenityValues), OverpassQl.oneOf("shop", EatKind.shopValues))
        val ql = OverpassQl.places(selectors, OverpassArea.Around(centre, r), timeoutSeconds = 30)
        val places = services.overpass.places(ql)
            .filter { EatKind.FILTER.matches(it.tags) && Categories.isPlace(it.tags) }
            .distinctBy { it.key }
            .mapNotNull { EatPlace.of(it, centre, lang) }
            .sortedBy { it.distanceM }
        return build(centre, r, places, PlaceOrigin.ONLINE, loading = false, local)
    }

    private fun build(centre: LatLon, r: Double, places: List<EatPlace>, origin: PlaceOrigin, loading: Boolean, local: PlaceLocale) =
        EatArea(centre, r, places, origin, loading, local)

    private fun EatArea.copyWith(loading: Boolean, error: String?) = EatArea(centre, radiusM, places, origin, loading, local, error)

    companion object {
        private val all = WeakHashMap<Services, EatRepository>()

        /** The eat screens' state for [services], made on first use. */
        fun of(services: Services): EatRepository = synchronized(all) { all.getOrPut(services) { EatRepository(services) } }

        /** How many areas are kept at once. */
        private const val MAX_AREAS = 6

        /** Points within about 100 m share an area. */
        private fun keyOf(p: LatLon): String = "${(p.lat * 1000).roundToLong()}:${(p.lon * 600).roundToLong()}"
    }
}
