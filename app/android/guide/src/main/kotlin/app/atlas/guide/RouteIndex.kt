package app.atlas.guide

import app.atlas.geo.Polyline
import app.atlas.model.ManeuverType
import app.atlas.model.Route
import app.atlas.model.Span

/**
 * A route prepared for guidance: the distance along it of every maneuver, the planned time profile (driving, plus the
 * charging at the route's planned stops), and the span facts by position. Built once per route; every query is a
 * binary search.
 */
class RouteIndex(val route: Route, fallbackSpeed: Double = 10.0) {
    val line: Polyline = route.line
    val length: Double = line.length
    val steps = route.steps

    /** Metres from the start to each step's maneuver, non-decreasing. */
    val stepAlong: DoubleArray = DoubleArray(steps.size).also { a ->
        var prev = 0.0
        for (i in steps.indices) {
            val p = steps[i].pointIndex.coerceIn(0, (line.cumulative.size - 1).coerceAtLeast(0))
            val v = if (line.cumulative.isEmpty()) 0.0 else line.cumulative[p]
            a[i] = if (steps[i].maneuver.type == ManeuverType.ARRIVE) length.coerceAtLeast(prev) else v.coerceAtLeast(prev)
            prev = a[i]
        }
    }

    /** Metres along the route of each planned charging stop, ascending, and the seconds planned there. */
    private val stopAlong: DoubleArray
    private val stopSeconds: DoubleArray

    init {
        // Route.stops carry "atlas:charge_minutes"; the route's duration includes that time, its steps do not.
        val stops = route.stops.mapNotNull { p ->
            val minutes = p.extra["atlas:charge_minutes"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val along = line.snap(p.location)?.along ?: return@mapNotNull null
            along to minutes * 60
        }.sortedBy { it.first }
        stopAlong = DoubleArray(stops.size) { stops[it].first }
        stopSeconds = DoubleArray(stops.size) { stops[it].second }
    }

    /** Planned seconds of charging in all. */
    val plannedCharging: Double = stopSeconds.sum()

    /** Planned seconds from the start to each step's maneuver. */
    private val stepTime: DoubleArray
    private val stepDuration: DoubleArray

    init {
        val n = steps.size
        stepTime = DoubleArray(n)
        stepDuration = DoubleArray(n)
        val total = (route.duration - plannedCharging).takeIf { it > 0 } ?: (length / fallbackSpeed)
        val sum = steps.sumOf { it.duration.coerceAtLeast(0.0) }
        for (i in 0 until n) {
            val legM = (if (i + 1 < n) stepAlong[i + 1] else length) - stepAlong[i]
            // Trust the step durations when they add up; else spread the route's duration by distance.
            stepDuration[i] = if (sum > 0) steps[i].duration.coerceAtLeast(0.0) else if (length > 0) total * legM / length else 0.0
            if (i > 0) stepTime[i] = stepTime[i - 1] + stepDuration[i - 1]
        }
    }

    /** Planned seconds of driving (or walking, riding, sailing) for the whole route, without the charging stops. */
    val plannedDriving: Double get() = if (steps.isEmpty()) (route.duration - plannedCharging).coerceAtLeast(0.0) else stepTime.last() + stepDuration.last()

    /** Planned seconds for the whole route, the charging stops included. */
    val plannedDuration: Double get() = plannedDriving + plannedCharging

    /** Index of the last step whose maneuver is at or before [along]; 0 when before the first. */
    fun stepAt(along: Double): Int {
        var lo = 0
        var hi = stepAlong.size - 1
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (stepAlong[mid] <= along) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Planned seconds from the start to [along], the charging at stops already passed included. */
    fun plannedTimeAt(along: Double): Double = drivingTimeAt(along) + chargingBefore(along)

    /**
     * Seconds of charging planned at stops before [along]. A stop you are at is still ahead: its charge is time to come
     * until you drive on from it.
     */
    fun chargingBefore(along: Double): Double {
        var sum = 0.0
        for (i in stopAlong.indices) if (stopAlong[i] < along - STOP_RADIUS_M) sum += stopSeconds[i] else break
        return sum
    }

    /** Whether [along] is at a planned charging stop. */
    fun atStop(along: Double): Boolean = stopAlong.any { kotlin.math.abs(it - along) <= STOP_RADIUS_M }

    /** Planned seconds of driving from the start to [along]. */
    fun drivingTimeAt(along: Double): Double {
        if (steps.isEmpty()) return if (length > 0) plannedDriving * (along / length).coerceIn(0.0, 1.0) else 0.0
        val k = stepAt(along)
        val start = stepAlong[k]
        val end = if (k + 1 < stepAlong.size) stepAlong[k + 1] else length
        val f = if (end > start) ((along - start) / (end - start)).coerceIn(0.0, 1.0) else 1.0
        return stepTime[k] + stepDuration[k] * f
    }

    /** Planned speed around [along], m/s, from the step it lies in. */
    fun plannedSpeedAt(along: Double): Double? {
        if (steps.isEmpty()) return null
        val k = stepAt(along)
        val end = if (k + 1 < stepAlong.size) stepAlong[k + 1] else length
        val d = end - stepAlong[k]
        return if (stepDuration[k] > 0 && d > 0) d / stepDuration[k] else null
    }

    private val spansByKey: Map<String, List<Span>> = route.spans.groupBy { it.key }.mapValues { (_, v) -> v.sortedBy { it.from } }

    /** The facts of every span covering the segment that starts at point [pointIndex]. */
    fun factsAt(pointIndex: Int): Map<String, String> {
        if (spansByKey.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(spansByKey.size * 2)
        for ((key, list) in spansByKey) spanAt(list, pointIndex)?.let { out[key] = it.value }
        return out
    }

    /** The value of span [key] at [pointIndex], or null. */
    fun fact(key: String, pointIndex: Int): String? = spansByKey[key]?.let { spanAt(it, pointIndex)?.value }

    fun spans(key: String): List<Span> = spansByKey[key] ?: emptyList()

    private fun spanAt(list: List<Span>, i: Int): Span? {
        var lo = 0
        var hi = list.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].from <= i) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        // Spans of one key do not overlap in practice; look back a little in case they do.
        for (j in found downTo maxOf(0, found - 3)) if (i >= list[j].from && i < list[j].to) return list[j]
        return null
    }

    /** Metres along the route of point [index]. */
    fun alongOf(index: Int): Double = line.cumulative[index.coerceIn(0, line.cumulative.size - 1)]

    private companion object {
        /** Within this many metres of a charging stop you are at it (the charger is off the road, in a car park). */
        const val STOP_RADIUS_M = 150.0
    }
}
