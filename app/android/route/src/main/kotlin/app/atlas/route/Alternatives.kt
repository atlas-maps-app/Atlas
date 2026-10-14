package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Route

/** Tells alternatives apart: how much of one route runs on another. */
object Alternatives {

    /** The share (0..1) of [candidate]'s length that runs within [tolerance] metres of [other], sampled every [step] metres. */
    fun overlap(candidate: List<LatLon>, other: List<LatLon>, tolerance: Double = 12.0, step: Double = 20.0): Double {
        if (candidate.size < 2 || other.size < 2) return 0.0
        val line = Polyline(candidate)
        val length = line.length
        if (length <= 0) return 1.0
        val index = SegmentIndex(other, cell = 50.0)
        val samples = maxOf(1, (length / step).toInt())
        var on = 0
        for (k in 0 until samples) {
            val p = line.pointAt((k + 0.5) * length / samples)
            if (index.nearest(p, tolerance) != null) on++
        }
        return on.toDouble() / samples
    }

    /** The larger of the two one-way overlaps: a route that is the other plus a detour counts as a duplicate too. */
    fun similarity(a: List<LatLon>, b: List<LatLon>): Double = maxOf(overlap(a, b), overlap(b, a))

    /** Keeps routes in order, dropping each one whose [similarity] to an already kept route is above [maxOverlap]. */
    fun distinct(routes: List<Route>, maxOverlap: Double = 0.8): List<Route> {
        val kept = ArrayList<Route>()
        for (r in routes) {
            if (kept.none { similarity(r.points, it.points) > maxOverlap }) kept += r
        }
        return kept
    }
}
