package app.atlas.ui.shop

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.places.TagFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.WeakHashMap

/**
 * The shop screens' shared state for one [Services]: the places around a point (one finder with a short memory, so
 * going from the pantry into a shelf and back does not ask again), the shopping list, the containers with their tare, the
 * prices from Open Prices and the ones you noted yourself. Everything the person writes stays on the phone.
 */
internal class ShopRepository(private val services: Services, prices: OpenPrices? = null) {
    private val lang: String = Locale.getDefault().language
    private val finder = ShopFinder.of(services)

    /** The shopping list on the fridge. */
    val list = ShoppingList(File(services.dirs.files, "shop/list.tsv"))

    /** The jars, bottles and boxes you refill, each with its weight empty. */
    val containers = ContainerBook(File(services.dirs.files, "shop/containers.tsv"))

    /** Prices you saw on a shelf label and wrote down: used next to Open Prices, never sent anywhere. */
    val priceNotes = PriceNotes(File(services.dirs.files, "shop/prices.tsv"))

    /** Open Prices (Open Food Facts), the open price database. */
    val openPrices: OpenPrices = prices ?: OpenPrices(if (services.config.offline) null else services.http)

    /** Prices Open Prices knows around a point, loaded once per point and basket. */
    val prices: PriceBook = PriceBook(openPrices)

    /**
     * The places that pass [filter] within [radiusM] of [centre], nearest first, as they become known. [key] names the
     * query for the memory (a screen's own name). Places without a name are left out unless [nameless].
     */
    fun area(key: String, filter: TagFilter, centre: LatLon, radiusM: Double = ShopArea.DEFAULT_RADIUS_M, nameless: Boolean = false): Flow<ShopArea> =
        finder.around(key, filter, centre, radiusM).map { a ->
            val places = a.places.mapNotNull { ShopPlace.of(it, centre, lang, nameless) }
            ShopArea(centre, radiusM, places, a.origin, a.loading, a.error, services.clock.zone)
        }

    private val online: Boolean get() = !services.config.offline && services.connectivity.online.value

    /** Open Prices' answer for [entries] around [centre]: empty, with the reason, when the phone is offline (the client then fails at once). */
    suspend fun priceData(entries: List<ListEntry>, centre: LatLon): PriceData {
        val wanted = entries.filterNot { it.atHome }
        if (wanted.isEmpty()) return PriceData(emptyMap(), emptyList())
        return prices.load(wanted, centre, today = java.time.LocalDate.now(services.clock))
    }

    private val ratingCache = HashMap<String, Map<String, app.atlas.providers.Rating>>()

    /**
     * What Mangrove's open reviews say about the places of [area], by place key; empty offline or when nobody reviewed
     * them. Never made up: a shop nobody reviewed has no rating.
     */
    suspend fun ratings(area: ShopArea): Map<String, app.atlas.providers.Rating> {
        if (!online || area.places.isEmpty()) return emptyMap()
        val key = "${area.centre}:${area.places.size}"
        synchronized(ratingCache) { ratingCache[key] }?.let { return it }
        return try {
            val reviews = services.mangrove.near(app.atlas.geo.BBox.around(area.centre, area.radiusM))
            // Matching hundreds of shops against the reviews is work for a background thread.
            val out = withContext(Dispatchers.Default) {
                val places = area.places.map { it.place }
                val byKey = area.places.associateBy { it.key }
                val now = services.clock.instant()
                val found = HashMap<String, app.atlas.providers.Rating>()
                for ((k, rs) in app.atlas.providers.Mangrove.match(reviews, places)) {
                    val p = byKey[k]?.place ?: continue
                    val r = app.atlas.providers.Mangrove.ratingOf(rs, p, now)
                    if (r.stars != null || r.count > 0) found[k] = r
                }
                found
            }
            synchronized(ratingCache) { if (ratingCache.size >= 16) ratingCache.clear(); ratingCache[key] = out }
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Forgets the places kept in memory (a region was downloaded, or the person asked to look again). */
    fun forget() = finder.forget()

    companion object {
        private val all = WeakHashMap<Services, ShopRepository>()

        /** The repository of [services], made on first use. */
        fun of(services: Services, prices: OpenPrices? = null): ShopRepository =
            synchronized(all) { all.getOrPut(services) { ShopRepository(services, prices) } }
    }
}
