package app.atlas.ui.shop

import app.atlas.geo.LatLon

/**
 * One place to call at on a walk: where it is, from which minute it is open (0: already), the last minute one may still
 * arrive (null: no closing time known) and how long the visit takes. Minutes count from the start of the walk.
 */
internal class TourStop(val at: LatLon, val opensAt: Double = 0.0, val closesAt: Double? = null, val serviceMinutes: Double = 3.0)

/** A walk through [order] (indices into the stops given): when each is reached, and whether every window is kept. */
internal class TourResult(
    val order: List<Int>,
    /** Minutes from the start at which each stop of [order] is reached (after any wait for it to open). */
    val arrivals: List<Double>,
    /** Minutes from the start until the last visit is done. */
    val totalMinutes: Double,
    /** Metres walked. */
    val metres: Double,
    /** True when every stop is reached before it closes. */
    val feasible: Boolean,
)

/**
 * The shortest walk through some stops that keeps their opening windows (a travelling salesman with time windows,
 * open path from the start). Exact for up to [EXACT_LIMIT] stops: dynamic programming over the subsets visited, keeping
 * the earliest finish for each (waiting for a shop to open never hurts, so earliest is best). Beyond that: nearest
 * neighbour by deadline, improved by 2-opt moves that stay feasible. When no order keeps every window, the order that
 * misses the fewest comes back with `feasible = false`.
 */
internal object Tour {
    const val EXACT_LIMIT = 10

    /** Streets run about 30% longer than the straight line: the kit's walking time uses it, so the kilometres shown must too. */
    const val STREET_FACTOR = 1.3

    /** Minutes to walk the straight line [metres], with the city detour (the kit's walking time). */
    fun walkMinutes(metres: Double): Double = app.atlas.ui.kit.WalkTime.seconds(metres) / 60.0

    fun solve(
        start: LatLon,
        stops: List<TourStop>,
        metres: (LatLon, LatLon) -> Double = app.atlas.geo.Geo::distance,
    ): TourResult {
        val n = stops.size
        if (n == 0) return TourResult(emptyList(), emptyList(), 0.0, 0.0, true)
        val d = Array(n + 1) { i -> DoubleArray(n + 1) { j -> if (i == j) 0.0 else metres(point(start, stops, i), point(start, stops, j)) } }
        return if (n <= EXACT_LIMIT) exact(stops, d) ?: heuristic(stops, d) else heuristic(stops, d)
    }

    private fun point(start: LatLon, stops: List<TourStop>, i: Int) = if (i == 0) start else stops[i - 1].at

    /** Dynamic programming over subsets: finish[mask][last] is the earliest time a visit of exactly [mask] ends at [last]. */
    private fun exact(stops: List<TourStop>, d: Array<DoubleArray>): TourResult? {
        val n = stops.size
        val full = (1 shl n) - 1
        val inf = Double.POSITIVE_INFINITY
        val finish = Array(1 shl n) { DoubleArray(n) { inf } }
        val from = Array(1 shl n) { IntArray(n) { -1 } }
        for (j in 0 until n) enter(stops[j], 0.0, d[0][j + 1])?.let { finish[1 shl j][j] = it }
        for (mask in 1..full) {
            for (last in 0 until n) {
                val t = finish[mask][last]
                if (t == inf) continue
                for (next in 0 until n) {
                    if (mask and (1 shl next) != 0) continue
                    val done = enter(stops[next], t, d[last + 1][next + 1]) ?: continue
                    val m2 = mask or (1 shl next)
                    if (done < finish[m2][next]) {
                        finish[m2][next] = done
                        from[m2][next] = last
                    }
                }
            }
        }
        var best = -1
        for (last in 0 until n) if (finish[full][last] < (if (best < 0) inf else finish[full][best])) best = last
        if (best < 0) return null
        val order = ArrayList<Int>(n)
        var mask = full
        var cur = best
        while (cur >= 0) {
            order.add(cur)
            val prev = from[mask][cur]
            mask = mask and (1 shl cur).inv()
            cur = prev
        }
        order.reverse()
        return evaluate(stops, d, order)
    }

    /** The time the visit ends when walking [walkM] metres from a stop left at [t]; null when it is reached after closing. */
    private fun enter(stop: TourStop, t: Double, walkM: Double): Double? {
        val arrive = maxOf(t + walkMinutes(walkM), stop.opensAt)
        if (stop.closesAt != null && arrive > stop.closesAt) return null
        return arrive + stop.serviceMinutes
    }

    private fun evaluate(stops: List<TourStop>, d: Array<DoubleArray>, order: List<Int>): TourResult {
        var t = 0.0
        var at = 0
        var metres = 0.0
        var ok = true
        val arrivals = ArrayList<Double>(order.size)
        for (j in order) {
            metres += d[at][j + 1]
            val arrive = maxOf(t + walkMinutes(d[at][j + 1]), stops[j].opensAt)
            if (stops[j].closesAt != null && arrive > stops[j].closesAt!!) ok = false
            arrivals.add(arrive)
            t = arrive + stops[j].serviceMinutes
            at = j + 1
        }
        return TourResult(order, arrivals, t, metres, ok)
    }

    /** How many windows an order misses, then its length: what the heuristic minimises. */
    private fun cost(stops: List<TourStop>, d: Array<DoubleArray>, order: List<Int>): Pair<Int, Double> {
        val r = evaluate(stops, d, order)
        val late = order.indices.count { k -> stops[order[k]].closesAt?.let { r.arrivals[k] > it } == true }
        return late to r.totalMinutes
    }

    private fun better(a: Pair<Int, Double>, b: Pair<Int, Double>) = a.first < b.first || (a.first == b.first && a.second < b.second - 1e-9)

    private fun heuristic(stops: List<TourStop>, d: Array<DoubleArray>): TourResult {
        val n = stops.size
        // Start from the order of closing times, then nearest neighbour among the ones still to do.
        val left = (0 until n).toMutableList()
        val order = ArrayList<Int>(n)
        var at = 0
        var t = 0.0
        while (left.isNotEmpty()) {
            val next = left.minBy { j ->
                val arrive = maxOf(t + walkMinutes(d[at][j + 1]), stops[j].opensAt)
                val slack = (stops[j].closesAt ?: 24 * 60.0) - arrive
                arrive + 0.5 * slack.coerceAtMost(180.0)
            }
            left.remove(next)
            t = maxOf(t + walkMinutes(d[at][next + 1]), stops[next].opensAt) + stops[next].serviceMinutes
            at = next + 1
            order.add(next)
        }
        var best = cost(stops, d, order)
        var improved = true
        while (improved) {
            improved = false
            for (i in 0 until n - 1) {
                for (k in i + 1 until n) {
                    val cand = ArrayList(order)
                    cand.subList(i, k + 1).reverse()
                    val c = cost(stops, d, cand)
                    if (better(c, best)) {
                        order.clear(); order.addAll(cand); best = c; improved = true
                    }
                }
            }
            // Or-opt: move one stop elsewhere.
            for (i in 0 until n) {
                for (k in 0 until n) {
                    if (i == k) continue
                    val cand = ArrayList(order)
                    val v = cand.removeAt(i)
                    cand.add(k, v)
                    val c = cost(stops, d, cand)
                    if (better(c, best)) {
                        order.clear(); order.addAll(cand); best = c; improved = true
                    }
                }
            }
        }
        return evaluate(stops, d, order)
    }
}
