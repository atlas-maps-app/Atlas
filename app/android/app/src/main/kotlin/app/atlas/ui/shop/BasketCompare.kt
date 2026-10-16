package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What Open Prices answered for a basket: the reports per list line (by its text), and the lines it could not ask for. */
internal class PriceData(
    val reports: Map<String, List<PriceReport>>,
    /** Lines with no Open Food Facts category in the item table: nothing to ask about. */
    val unaskable: List<ListEntry>,
    /** Why the network failed, when it did and nothing came back. */
    val error: String? = null,
) {
    val any: Boolean get() = reports.values.any { it.isNotEmpty() }
}

/** Fetches the prices for a basket, a few requests at a time, and keeps the answer for a few minutes. */
internal class PriceBook(private val prices: OpenPrices, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private class Kept(val key: String, val at: Long, val data: PriceData)

    @Volatile
    private var kept: Kept? = null

    suspend fun load(entries: List<ListEntry>, centre: LatLon, radiusKm: Double = 3.0, today: LocalDate = LocalDate.now()): PriceData {
        val wanted = entries.filterNot { it.atHome }
        val key = wanted.joinToString("|") { it.text } + "@" + "%.2f,%.2f".format(java.util.Locale.ROOT, centre.lat, centre.lon) + "/" + radiusKm
        kept?.takeIf { it.key == key && nowMillis() - it.at < KEEP_MS }?.let { return it.data }
        val askable = wanted.filter { it.item?.offCategory != null }
        var failure: String? = null
        val gate = Semaphore(3)
        val got = coroutineScope {
            askable.map { e ->
                async {
                    gate.withPermit {
                        try {
                            e.text to prices.around(centre, e.item!!.offCategory!!, radiusKm, today = today)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (x: IOException) {
                            failure = x.message ?: "offline"
                            e.text to emptyList()
                        } catch (x: Exception) {
                            failure = x.message ?: x.javaClass.simpleName
                            e.text to emptyList()
                        }
                    }
                }
            }.awaitAll()
        }.toMap()
        val data = PriceData(got, wanted - askable.toSet(), error = failure.takeIf { got.values.none { it.isNotEmpty() } })
        if (failure == null) kept = Kept(key, nowMillis(), data)
        return data
    }

    companion object {
        private const val KEEP_MS = 10 * 60_000L
    }
}

/** A shop in a price comparison: from Open Prices' own record of it (or your note), with its position. */
internal data class PriceStore(val key: String, val name: String, val brand: String?, val at: LatLon)

/** The product a line is compared by: the one most shops around have a price for. */
internal data class RefProduct(val code: String?, val name: String?, val quantity: String?)

/** One line of a shop's receipt: its [price] (null: nobody reported one, shown as "?"), how old it is and whose it is. */
internal data class PriceLine(val entry: ListEntry, val price: Double?, val ageDays: Int?, val own: Boolean)

/** A shop's basket: a line per list item, what is known of its total, and how far away it is. */
internal class StoreBasket(val store: PriceStore, val lines: List<PriceLine>, val metres: Double) {
    val known: Int get() = lines.count { it.price != null }
    val total: Double get() = lines.sumOf { it.price ?: 0.0 }

    /** Every line has a price: the total is the whole basket. */
    val complete: Boolean get() = known == lines.size
    val walkMinutes: Int get() = maxOf(1, Math.round(Tour.walkMinutes(metres)).toInt())
}

/** The split plan: each item bought where it is cheapest, in the order of the shortest walk, and what it saves. */
internal class SplitPlan(val stops: List<StoreBasket>, val total: Double, val minutes: Int, val saving: Double, val order: List<PriceStore>)

/** The biggest gap in one item's price between shops, for the card "where the money is". */
internal class PriceGap(val entry: ListEntry, val product: RefProduct?, val rows: List<Pair<PriceStore, Double?>>) {
    val best: Double? get() = rows.mapNotNull { it.second }.minOrNull()
    val worst: Double? get() = rows.mapNotNull { it.second }.maxOrNull()
}

/** Everything the Compare screen shows. */
internal class Comparison(
    val entries: List<ListEntry>,
    /** Up to three shops, best first (the most lines priced, then the lowest total). */
    val baskets: List<StoreBasket>,
    val products: Map<String, RefProduct>,
    val currency: String,
    /** The ages in days of the youngest and the oldest price used. */
    val freshness: IntRange?,
    val split: SplitPlan?,
    val gap: PriceGap?,
) {
    /** The one stop: the shop with the best basket. */
    val oneStop: StoreBasket? get() = baskets.firstOrNull()
    val empty: Boolean get() = baskets.isEmpty()
}

/** One price at one shop: [own] when you wrote it down yourself. */
internal class Quote(val price: Double, val date: LocalDate, val own: Boolean)

/**
 * The prices of a basket by shop: for each list line the reference product (the one most shops around have a price for,
 * so shops are compared on the same thing) and its latest price at each shop, with your own notes laid over Open Prices'.
 */
internal class PriceIndex(
    val stores: Map<String, PriceStore>,
    val products: Map<String, RefProduct>,
    /** List line text -> shop key -> price. */
    val quotes: Map<String, Map<String, Quote>>,
    val currency: String,
) {
    fun quote(entry: ListEntry, shopKey: String): Quote? = quotes[entry.text]?.get(shopKey)
    fun price(entry: ListEntry, shopKey: String): Double? = quote(entry, shopKey)?.price

    /** True when any line has a price anywhere. */
    val any: Boolean get() = quotes.values.any { it.isNotEmpty() }

    /** What [entries] cost at [shopKey] when every line is priced; null when any line has no price there. */
    fun completeTotal(entries: List<ListEntry>, shopKey: String): Double? {
        var t = 0.0
        for (e in entries) t += price(e, shopKey) ?: return null
        return t
    }

    companion object {
        val Empty = PriceIndex(emptyMap(), emptyMap(), emptyMap(), "EUR")

        fun of(entries: List<ListEntry>, data: PriceData, notes: List<PriceNote>): PriceIndex {
            val stores = LinkedHashMap<String, PriceStore>()
            val products = LinkedHashMap<String, RefProduct>()
            val quotes = HashMap<String, HashMap<String, Quote>>()
            // One currency to add up in: the one most reports use (a border town may see two).
            val currency = data.reports.values.flatten().groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key ?: "EUR"
            for (e in entries.filterNot { it.atHome }) {
                val reports = data.reports[e.text].orEmpty()
                val ref = BasketCompare.reference(reports)
                if (ref != null) products[e.text] = RefProduct(ref.first().productCode, ref.first().productName, ref.first().quantity)
                val per = quotes.getOrPut(e.text) { HashMap() }
                // The latest report of the reference product at each shop.
                for (r in ref.orEmpty().sortedBy { it.date }) {
                    // A shop with no name cannot be shown on a receipt; a price in another currency cannot be added up.
                    val name = r.shopBrand ?: r.shopName ?: continue
                    if (r.currency != currency) continue
                    stores.getOrPut(r.shopOsmId) { PriceStore(r.shopOsmId, name, r.shopBrand, r.at) }
                    per[r.shopOsmId] = Quote(r.price, r.date, false)
                }
                for (n in notes.filter { it.what.equals(e.text, ignoreCase = true) }) {
                    val day = runCatching { LocalDate.parse(n.day) }.getOrNull() ?: continue
                    stores.getOrPut(n.shopKey) { PriceStore(n.shopKey, n.shopName, null, LatLon(n.lat, n.lon)) }
                    val have = per[n.shopKey]
                    if (have == null || !day.isBefore(have.date)) per[n.shopKey] = Quote(n.price, day, true)
                }
            }
            return PriceIndex(stores, products, quotes, currency)
        }
    }
}

/** Turns Open Prices' reports (and your own notes) into shop baskets. */
internal object BasketCompare {
    fun compare(
        entries: List<ListEntry>,
        data: PriceData,
        notes: List<PriceNote>,
        here: LatLon,
        today: LocalDate,
        maxStores: Int = 3,
    ): Comparison {
        val wanted = entries.filterNot { it.atHome }
        val index = PriceIndex.of(wanted, data, notes)
        val baskets = index.stores.values.map { s ->
            val lines = wanted.map { e ->
                val q = index.quote(e, s.key)
                PriceLine(e, q?.price, q?.let { ChronoUnit.DAYS.between(it.date, today).toInt().coerceAtLeast(0) }, q?.own == true)
            }
            StoreBasket(s, lines, Geo.distance(here, s.at))
        }.filter { it.known > 0 }
            .sortedWith(compareByDescending<StoreBasket> { it.known }.thenBy { it.total / it.known }.thenBy { it.metres })
            .take(maxStores)
        val ages = baskets.flatMap { b -> b.lines.mapNotNull { it.ageDays } }
        return Comparison(
            wanted, baskets, index.products, index.currency, if (ages.isEmpty()) null else ages.min()..ages.max(),
            split(wanted, baskets, here), gap(wanted, baskets, index.products),
        )
    }

    /** The reports of the product most shops have a price for (ties: the newest report), or null when there are none. */
    internal fun reference(reports: List<PriceReport>): List<PriceReport>? {
        if (reports.isEmpty()) return null
        val byProduct = reports.groupBy { it.productCode ?: ("name:" + it.productName) }
        return byProduct.values.maxWith(
            compareBy<List<PriceReport>> { it.map { r -> r.shopOsmId }.distinct().size }.thenBy { l -> l.maxOf { it.date } },
        )
    }

    private fun split(entries: List<ListEntry>, baskets: List<StoreBasket>, here: LatLon): SplitPlan? {
        val one = baskets.firstOrNull() ?: return null
        if (baskets.size < 2) return null
        // Each item where it is cheapest among the compared shops.
        val chosen = HashMap<String, Pair<StoreBasket, Double>>()
        for (e in entries) {
            val cheapest = baskets.mapNotNull { b -> b.lines.firstOrNull { it.entry == e }?.price?.let { b to it } }.minByOrNull { it.second } ?: continue
            chosen[e.text] = cheapest
        }
        if (chosen.isEmpty()) return null
        val used = chosen.values.map { it.first }.distinct()
        val tour = Tour.solve(here, used.map { TourStop(it.store.at) })
        val order = tour.order.map { used[it] }
        val total = chosen.values.sumOf { it.second }
        // Compare like with like: what the one stop would have charged for the same items.
        val same = entries.filter { it.text in chosen }.sumOf { e -> one.lines.firstOrNull { it.entry == e }?.price ?: chosen[e.text]!!.second }
        return SplitPlan(order, total, Math.round(tour.totalMinutes - used.size * 3.0).toInt().coerceAtLeast(1), (same - total).coerceAtLeast(0.0), order.map { it.store })
    }

    private fun gap(entries: List<ListEntry>, baskets: List<StoreBasket>, products: Map<String, RefProduct>): PriceGap? {
        var best: PriceGap? = null
        var spread = 0.0
        for (e in entries) {
            val rows = baskets.map { b -> b.store to b.lines.firstOrNull { it.entry == e }?.price }
            val prices = rows.mapNotNull { it.second }
            if (prices.size < 2) continue
            val s = prices.max() - prices.min()
            if (s > spread) {
                spread = s
                best = PriceGap(e, products[e.text], rows)
            }
        }
        return best
    }
}
