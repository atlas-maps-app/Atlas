package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.OpeningHoursContext
import app.atlas.places.TagFilter
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** How a run is chosen: as few stops as possible, the shops most likely to have each item, or the lowest basket. */
internal enum class PlanBy { FEWEST, BEST, CHEAPEST }

/** One call of a run: the shop, what is bought there, and when. */
internal class RunStop(
    val shop: ShopPlace,
    val items: List<ListEntry>,
    /** Whole minutes walked from the previous stop (or from where the run starts). */
    val walkMinutes: Int,
    val arriveAt: LocalDateTime,
    /** When it closes today when the hours say so: the receipt's red warning. */
    val closesAt: LocalDateTime?,
    /** Closing within the shop screens' "soon" (45 min) of arriving. */
    val closingSoon: Boolean,
    /** False when the shop has no readable hours: it is assumed open, and the screen says so. */
    val hoursKnown: Boolean,
)

/** The estimated basket of a run, from Open Prices and your notes: [known] of [of] lines have a price. */
internal class RunBasket(val total: Double, val known: Int, val of: Int)

/** A planned run: the stops in walking order, the distance and time, and what could not be fitted in. */
internal class Run(
    val by: PlanBy,
    val stops: List<RunStop>,
    val metres: Double,
    val minutes: Int,
    val startAt: LocalDateTime,
    val doneAt: LocalDateTime,
    /** Items no open shop around sells. */
    val unmet: List<ListEntry>,
    /** True when some stop is reached after it closes (nothing better exists). */
    val late: Boolean,
    val basket: RunBasket?,
) {
    val stopCount: Int get() = stops.size
}

/**
 * Plans a shopping run: which shops to call at and in what order. Each item is matched to the kinds of shop that sell
 * it (the curated table, with likelihoods), the shops of those kinds that will be open when you can get there are the
 * candidates, and the smallest sets of shops that cover the list are tried in turn (a set cover by branching on the
 * least coverable item, to four stops). Each set is walked in the best order by [Tour] (a travelling salesman with
 * closing times) and the plan decides which set wins. Walking times come from the straight line with the city detour
 * (the same as everywhere in Atlas); a region with routing data can refine the winner's legs afterwards.
 */
internal object RunPlanner {
    private const val MAX_STOPS = 4
    private const val BRANCH = 7
    private const val MIN_LIKELIHOOD = 2

    /** The shop kinds' rules for [entries] as one rule: what the screen asks the place finder for. */
    fun filterFor(entries: List<ListEntry>): TagFilter {
        val kinds = entries.flatMap { e -> e.sellers.filter { it.likelihood >= MIN_LIKELIHOOD }.map { it.kind } }.distinct()
        return if (kinds.isEmpty()) TagFilter.parse("shop=supermarket") else TagFilter.or(*kinds.map { it.filter }.toTypedArray())
    }

    /** The shops that sell [entry], with the likelihood of the best matching kind; weaker kinds count only when nothing better is known. */
    fun sellingShops(entry: ListEntry, shops: List<ShopPlace>): List<Pair<ShopPlace, Int>> {
        val sellers = entry.sellers
        val floor = if (sellers.any { it.likelihood >= MIN_LIKELIHOOD }) MIN_LIKELIHOOD else 1
        return shops.mapNotNull { s ->
            val l = sellers.filter { it.likelihood >= floor && it.kind.matches(s.tags) }.maxOfOrNull { it.likelihood } ?: return@mapNotNull null
            s to l
        }
    }

    internal class Window(val shop: ShopPlace, val opensAt: Double, val closesAt: Double?, val closeTime: LocalDateTime?, val hoursKnown: Boolean)

    /** When [shop] can be visited, in minutes from [now]; null when it will not be open in the next hour. */
    internal fun window(shop: ShopPlace, now: LocalDateTime, ctx: OpeningHoursContext, reachMinutes: Double): Window? {
        if (!shop.hasHours) return Window(shop, 0.0, null, null, hoursKnown = false)
        return when (shop.openness(now, ctx)) {
            Open.UNKNOWN -> Window(shop, 0.0, null, null, hoursKnown = false)
            Open.OPEN, Open.CLOSING_SOON -> {
                val close = shop.closesAt(now, ctx)
                val m = close?.let { Duration.between(now, it).toMinutes().toDouble() }
                if (m != null && m < reachMinutes + 2) null else Window(shop, 0.0, m, close, hoursKnown = true)
            }
            Open.CLOSED -> {
                val opens = shop.opensAt(now, ctx) ?: return null
                val m = Duration.between(now, opens).toMinutes().toDouble()
                if (m > 60) return null
                val closes = shop.closesAt(opens.plusMinutes(1), ctx)
                Window(shop, m, closes?.let { Duration.between(now, it).toMinutes().toDouble() }, closes, hoursKnown = true)
            }
        }
    }

    /**
     * The run for [entries] (the lines still to buy) among [shops], from [start] at [now].
     * [priceOf] gives what Open Prices or a note says an item costs at a shop (null: unknown); it decides [PlanBy.CHEAPEST].
     */
    fun plan(
        entries: List<ListEntry>,
        shops: List<ShopPlace>,
        start: LatLon,
        now: LocalDateTime,
        ctx: OpeningHoursContext,
        by: PlanBy,
        priceOf: (ListEntry, ShopPlace) -> Double? = { _, _ -> null },
        metres: (LatLon, LatLon) -> Double = Geo::distance,
    ): Run {
        val wanted = entries.filterNot { it.atHome }
        val windows = HashMap<String, Window?>()
        fun windowOf(s: ShopPlace): Window? = windows.getOrPut(s.key) { window(s, now, ctx, Tour.walkMinutes(metres(start, s.location))) }
        // Candidates per item: the open shops that sell it, the nearest few and the likeliest few.
        val cands = HashMap<ListEntry, List<Pair<ShopPlace, Int>>>()
        for (e in wanted) {
            val all = sellingShops(e, shops).filter { windowOf(it.first) != null }
            val near = all.sortedBy { it.first.distanceM }.take(BRANCH)
            val likely = all.sortedWith(compareByDescending<Pair<ShopPlace, Int>> { it.second }.thenBy { it.first.distanceM }).take(3)
            val cheap = if (by == PlanBy.CHEAPEST) all.filter { priceOf(e, it.first) != null }.sortedBy { priceOf(e, it.first) }.take(3) else emptyList()
            cands[e] = (near + likely + cheap).distinctBy { it.first.key }
        }
        val coverable = wanted.filter { cands[it].orEmpty().isNotEmpty() }
        val unmet = wanted - coverable.toSet()
        if (coverable.isEmpty()) return empty(by, now, unmet)

        var best: Candidate? = null
        val seen = HashSet<Set<String>>()
        val covers = ArrayList<List<ShopPlace>>()
        fun consider(chosen: List<ShopPlace>) {
            if (!seen.add(chosen.map { it.key }.toSet())) return
            covers += chosen
            val c = evaluate(chosen, coverable, cands, start, now, by, priceOf, metres, ::windowOf) ?: return
            if (best == null || c.better(best!!)) best = c
        }
        fun search(uncovered: List<ListEntry>, chosen: List<ShopPlace>) {
            if (uncovered.isEmpty()) return consider(chosen)
            if (chosen.size >= MAX_STOPS) return
            val e = uncovered.minBy { cands[it]!!.size }
            for ((shop, _) in cands[e]!!) {
                if (chosen.any { it.key == shop.key }) continue
                val covered = uncovered.filter { x -> cands[x]!!.any { it.first.key == shop.key } }
                search(uncovered - covered.toSet(), chosen + shop)
            }
        }
        search(coverable, emptyList())
        if (by != PlanBy.FEWEST) {
            // The smallest covers miss the specialists (the baker for the bread): try each with one more shop that is
            // the likeliest or the cheapest seller of something on the list.
            val extras = coverable.flatMap { e -> cands[e]!!.sortedWith(compareByDescending<Pair<ShopPlace, Int>> { it.second }.thenBy { it.first.distanceM }).take(2).map { it.first } }.distinctBy { it.key }
            for (c in covers.toList()) {
                if (c.size >= MAX_STOPS) continue
                for (x in extras) if (c.none { it.key == x.key }) consider(c + x)
            }
        }
        if (best == null) {
            // More than four stops would be needed (a long list): cover greedily, nearest first, and walk what comes out.
            val chosen = ArrayList<ShopPlace>()
            var left = coverable
            while (left.isNotEmpty()) {
                val pick = left.flatMap { cands[it]!! }.map { it.first }.distinctBy { it.key }
                    .maxBy { s -> left.count { x -> cands[x]!!.any { it.first.key == s.key } } * 10_000 - s.distanceM }
                chosen += pick
                left = left.filterNot { x -> cands[x]!!.any { it.first.key == pick.key } }
            }
            best = evaluate(chosen, coverable, cands, start, now, by, priceOf, metres, ::windowOf)
        }
        return best?.run(by, now, unmet, priceOf) ?: empty(by, now, wanted)
    }

    private fun empty(by: PlanBy, now: LocalDateTime, unmet: List<ListEntry>) = Run(by, emptyList(), 0.0, 0, now, now, unmet, late = false, basket = null)

    private class Candidate(
        val by: PlanBy,
        val assignment: Map<ShopPlace, List<ListEntry>>,
        val tour: TourResult,
        val ordered: List<ShopPlace>,
        val windows: List<Window>,
        val likeScore: Int,
        val cost: Double,
    ) {
        private fun key(): List<Double> = when (by) {
            PlanBy.FEWEST -> listOf(late(), ordered.size.toDouble(), tour.totalMinutes)
            PlanBy.BEST -> listOf(late(), -(likeScore * 10.0 - 4.0 * ordered.size - 0.3 * tour.totalMinutes))
            PlanBy.CHEAPEST -> listOf(late(), cost, ordered.size.toDouble(), tour.totalMinutes)
        }

        private fun late() = if (tour.feasible) 0.0 else 1.0

        fun better(o: Candidate): Boolean {
            val a = key()
            val b = o.key()
            for (i in a.indices) if (a[i] != b[i]) return a[i] < b[i] - 1e-9
            return false
        }

        fun run(by: PlanBy, now: LocalDateTime, unmet: List<ListEntry>, priceOf: (ListEntry, ShopPlace) -> Double?): Run {
            var prev: LatLon? = null
            val stops = ordered.indices.map { i ->
                val shop = ordered[i]
                val w = windows[i]
                val arrive = now.plusMinutes(tour.arrivals[i].roundToInt().toLong())
                val soon = w.closeTime?.let { Duration.between(arrive, it).toMinutes() in 0..ShopPlace.SOON_MINUTES } == true
                RunStop(shop, assignment[shop].orEmpty(), 0, arrive, w.closeTime, soon, w.hoursKnown)
            }
            // Walking minutes per leg, from the arrival gaps: arrival minus the previous departure.
            var depart = 0.0
            val legs = tour.arrivals.indices.map { i ->
                val leg = (tour.arrivals[i] - depart).coerceAtLeast(1.0)
                depart = tour.arrivals[i] + service(assignment[ordered[i]].orEmpty().size)
                leg.roundToInt().coerceAtLeast(1)
            }
            val withLegs = stops.mapIndexed { i, s -> RunStop(s.shop, s.items, legs[i], s.arriveAt, s.closesAt, s.closingSoon, s.hoursKnown) }
            val lines = assignment.values.flatten()
            val priced = assignment.flatMap { (shop, items) -> items.map { priceOf(it, shop) } }
            prev = null
            val basket = if (priced.any { it != null }) RunBasket(priced.filterNotNull().sum(), priced.count { it != null }, lines.size) else null
            return Run(by, withLegs, tour.metres * Tour.STREET_FACTOR, Math.ceil(tour.totalMinutes).toInt(), now, now.plusMinutes(Math.ceil(tour.totalMinutes).toLong()), unmet, !tour.feasible, basket)
        }
    }

    private fun service(items: Int): Double = (1.0 + 0.5 * items).coerceAtMost(4.0)

    private fun evaluate(
        chosen: List<ShopPlace>,
        entries: List<ListEntry>,
        cands: Map<ListEntry, List<Pair<ShopPlace, Int>>>,
        start: LatLon,
        now: LocalDateTime,
        by: PlanBy,
        priceOf: (ListEntry, ShopPlace) -> Double?,
        metres: (LatLon, LatLon) -> Double,
        windowOf: (ShopPlace) -> Window?,
    ): Candidate? {
        // Each item goes to the chosen shop that suits the plan best.
        val assignment = LinkedHashMap<ShopPlace, MutableList<ListEntry>>()
        var like = 0
        var cost = 0.0
        for (e in entries) {
            val options = cands[e].orEmpty().filter { c -> chosen.any { it.key == c.first.key } }
            if (options.isEmpty()) continue
            val pick = when (by) {
                PlanBy.CHEAPEST -> options.minWith(compareBy<Pair<ShopPlace, Int>> { priceOf(e, it.first) ?: Double.MAX_VALUE }.thenByDescending { it.second }.thenBy { it.first.distanceM })
                else -> options.maxWith(compareBy<Pair<ShopPlace, Int>> { it.second }.thenByDescending { it.first.distanceM })
            }
            assignment.getOrPut(chosen.first { it.key == pick.first.key }) { ArrayList() }.add(e)
            like += pick.second
            cost += priceOf(e, pick.first) ?: 0.0
        }
        val used = assignment.keys.toList()
        if (used.isEmpty()) return null
        val ws = used.map { windowOf(it) ?: return null }
        val tour = Tour.solve(start, used.indices.map { i ->
            TourStop(used[i].location, ws[i].opensAt, ws[i].closesAt, service(assignment[used[i]]!!.size))
        }, metres)
        val order = tour.order.map { used[it] }
        return Candidate(by, assignment, tour, order, tour.order.map { ws[it] }, like, cost)
    }
}
