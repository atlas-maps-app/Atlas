package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.OpeningHoursContext
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** One call of an errand walk: which errand, the place that does it, and when. */
internal class ErrandStop(
    val errand: Errand,
    val place: ShopPlace,
    val walkMinutes: Int,
    val arriveAt: LocalDateTime,
    /** When the place closes today, when its hours say so. */
    val closesAt: LocalDateTime?,
    /** The place that closes soonest: do it first. */
    val first: Boolean,
    val hoursKnown: Boolean,
)

/** The walk that does every errand: the stops in order, its length, when it is done, and the errands nobody open can do. */
internal class ErrandPlan(
    val stops: List<ErrandStop>,
    val minutes: Int,
    val metres: Double,
    val doneAt: LocalDateTime,
    val missing: List<Errand>,
    val late: Boolean,
    /** The soonest closing time among the stops: "all open till 19:00". */
    val openTill: LocalDateTime?,
)

/**
 * Picks the places and the order for a set of errands: each errand may be done at its few nearest open places, and every
 * combination is walked in the best order by [Tour] (exact up to ten stops: a travelling salesman that respects closing
 * times). More than six errands only try the nearest place for each, to keep the search small.
 */
internal object ErrandsPlanner {
    private const val CHOICES = 3

    fun plan(
        picks: List<Errand>,
        places: List<ShopPlace>,
        start: LatLon,
        now: LocalDateTime,
        ctx: OpeningHoursContext,
    ): ErrandPlan {
        val windows = HashMap<String, RunPlanner.Window?>()
        fun win(s: ShopPlace) = windows.getOrPut(s.key) { RunPlanner.window(s, now, ctx, Tour.walkMinutes(Geo.distance(start, s.location))) }
        val options = LinkedHashMap<Errand, List<ShopPlace>>()
        val missing = ArrayList<Errand>()
        for (e in picks) {
            val found = places.filter { e.filter.matches(it.tags) && win(it) != null }.sortedBy { it.distanceM }.take(if (picks.size > 6) 1 else CHOICES)
            if (found.isEmpty()) missing += e else options[e] = found
        }
        if (options.isEmpty()) return ErrandPlan(emptyList(), 0, 0.0, now, missing, late = false, openTill = null)
        val errands = options.keys.toList()
        var best: Triple<List<ShopPlace>, TourResult, Boolean>? = null
        fun consider(chosen: List<ShopPlace>) {
            val stops = chosen.mapIndexed { i, p -> val w = win(p)!!; TourStop(p.location, w.opensAt, w.closesAt, errands[i].minutes.toDouble()) }
            val r = Tour.solve(start, stops)
            val b = best
            if (b == null || (r.feasible && !b.second.feasible) || (r.feasible == b.second.feasible && r.totalMinutes < b.second.totalMinutes - 1e-9)) best = Triple(chosen, r, r.feasible)
        }
        fun combos(i: Int, chosen: List<ShopPlace>) {
            if (i == errands.size) return consider(chosen)
            // One place can do two errands only if it is the same place twice; each errand keeps its own stop.
            for (p in options.getValue(errands[i])) combos(i + 1, chosen + p)
        }
        combos(0, emptyList())
        val (chosen, tour, feasible) = best!!
        var depart = 0.0
        val earliestClose = chosen.indices.mapNotNull { win(chosen[it])?.closesAt?.let { c -> chosen[it] to c } }.minByOrNull { it.second }?.first
        val stops = tour.order.mapIndexed { k, idx ->
            val p = chosen[idx]
            val w = win(p)!!
            val leg = (tour.arrivals[k] - depart).coerceAtLeast(1.0)
            depart = tour.arrivals[k] + errands[idx].minutes
            ErrandStop(errands[idx], p, leg.roundToInt().coerceAtLeast(1), now.plusMinutes(tour.arrivals[k].roundToInt().toLong()), w.closeTime, first = p === earliestClose && chosen.size > 1, hoursKnown = w.hoursKnown)
        }
        val till = stops.mapNotNull { it.closesAt }.minOrNull()
        val minutes = Math.ceil(tour.totalMinutes).toInt()
        return ErrandPlan(stops, minutes, tour.metres * Tour.STREET_FACTOR, now.plusMinutes(minutes.toLong()), missing, !feasible, till)
    }

    /** True when [a] and [b] are far enough apart (metres) to count as separate trips: used to sanity-check ordering in tests. */
    fun spread(stops: List<ErrandStop>): Double = stops.zipWithNext { a, b -> Geo.distance(a.place.location, b.place.location) }.sum()

    /** Minutes between two times. */
    fun between(a: LocalDateTime, b: LocalDateTime): Long = Duration.between(a, b).toMinutes()
}
