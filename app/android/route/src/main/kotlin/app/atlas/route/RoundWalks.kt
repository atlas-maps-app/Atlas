package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RoutePrefs
import app.atlas.model.RouteRequest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A green a round walk may lead through: the box of its outline, and what it is called. */
data class Green(val box: BBox, val name: String? = null)

/**
 * A way round, back to where it began: the [route]; the [request] that found it, whose stops lead guidance on round when
 * the walker strays (a way back to the end alone would be a way back to where they are); the green it mostly goes through.
 */
data class RoundWalk(val route: Route, val request: RouteRequest, val through: String? = null)

/**
 * Finds round walks offline: a loop of about so many minutes from a place back to it, through the greens around where
 * they are in reach. A loop is an ordinary route through stops the router is asked for, so that it is guided, and found
 * again when the walker strays, as any trip with stops is. The stops are picked from the shape wanted: two far ends in
 * a green big enough to walk it; a diamond with its far corner in a green, its two side corners pulled into greens they
 * lie near; a plain diamond elsewhere, tried in turn towards each side. Each try is measured as it came out of the router
 * (how long, how much of it twice over, how much of it in green), the pace and the streets' detour learnt from it for
 * the next, and the best is kept.
 */
object RoundWalks {
    sealed interface Result

    data class Found(val walk: RoundWalk) : Result

    /** None to be had, and why: the routing data is not on the phone ([RouteResult.NoData]), or no way round was found. */
    data class Failed(val why: RouteResult.Failure) : Result

    /** A walk's own way: quiet and green over quick. */
    val PREFS = RoutePrefs(quiet = 0.6, green = 0.8, fast = 0.0)

    private const val WALKING_M_S = 1.25
    private const val DETOUR = 1.3
    private const val ROUTES = 8
    private const val CLOSE_ENOUGH = 0.10
    private const val SAMPLE_M = 15.0
    private const val SAME_M = 18.0
    private const val SPIKE_M = 15.0
    private const val SPIKE_STEP_M = 5.0
    private const val SAME_STOP_M = 30.0

    /**
     * A round walk of about [minutes] from [from], through [greens] where in reach; each route is asked of [router] in
     * turn, at most [ROUTES] of them, and stops the search when one is good enough.
     */
    suspend fun find(router: Router, from: LatLon, minutes: Int, greens: List<Green>): Result {
        val seconds = minutes * 60.0
        var pace = WALKING_M_S
        var detour = DETOUR
        var failure: RouteResult.Failure? = null
        var lacking: RouteResult.NoData? = null
        val tried = ArrayList<Tried>()
        var asked = 0

        suspend fun ask(vias: List<LatLon>): Tried? {
            asked++
            val req = request(from, vias)
            return when (val r = router.route(req)) {
                is RouteResult.Found -> {
                    val t = Tried(req, r.best, measure(r.best, seconds, greens))
                    tried += t
                    if (r.best.duration > 0) pace = r.best.distance / r.best.duration
                    round(from, vias).takeIf { it > 0 }?.let { detour = (r.best.distance / it).coerceIn(1.05, 1.9) }
                    t
                }
                is RouteResult.Failure -> {
                    failure = r
                    if (r is RouteResult.NoData) lacking = r
                    null
                }
            }
        }

        fun way() = seconds * pace / detour

        val reachable = greens.filter { inReach(from, it, way()) }.sortedByDescending { worth(from, it, way()) }.take(3)
        val shapes = buildList<(Double) -> List<LatLon>?> {
            for (g in reachable) {
                add { w -> endsIn(from, g, w) }
                add { w -> diamondTo(from, g, greens, w) }
            }
            // with none in reach, still towards the nearest green there is
            val toward = reachable.firstOrNull() ?: greens.minByOrNull { Geo.distance(from, nearest(it.box, from)) }
            for (bearing in listOfNotNull(toward?.let { Geo.bearing(from, it.box.center) }) + FREE) add { w -> diamond(from, bearing, w, greens) }
        }
        fun new(vias: List<LatLon>) = tried.none { t -> t.request.waypoints.drop(1).dropLast(1).let { o -> o.size == vias.size && o.indices.all { Geo.distance(o[it], vias[it]) < SAME_STOP_M } } }
        // the data where the walk begins: without it there is no way round to be had until it is fetched
        val home = SegmentStore.tileName(from)
        for (shape in shapes) {
            if (asked >= ROUTES) break
            val vias = shape(way())?.takeIf(::new) ?: continue
            val first = ask(vias)
            lacking?.takeIf { home in it.missingTiles }?.let { return Failed(it) }
            // a try that wanted data farther out (or found no way near the edge of what is here) leaves the other shapes
            if (first == null) continue
            if (first.measure.off > CLOSE_ENOUGH && asked < ROUTES) {
                // the first try taught the pace and the detour here: the same shape once more at the size that makes
                shape(way())?.takeIf(::new)?.let { ask(it) }
            }
            if (tried.any { it.measure.good(reachable.isNotEmpty()) }) break
        }
        val best = tried.minByOrNull { it.measure.cost } ?: return Failed(lacking ?: failure ?: RouteResult.NoRoute("no way round from $from"))
        val neat = despiked(best, seconds, greens, router, from) ?: best
        return Found(RoundWalk(neat.route, neat.request, through(neat.route, greens)))
    }

    private fun request(from: LatLon, vias: List<LatLon>) = RouteRequest(Mode.WALK, listOf(from) + vias + from, PREFS)

    private class Tried(val request: RouteRequest, val route: Route, val measure: Measure)

    /** How a try came out: how far off the time wanted ([off]), the share of it walked twice ([twice]) and in green ([green]). */
    internal data class Measure(val off: Double, val twice: Double, val green: Double) {
        /** Lower is better: near the time wanted; a street twice is forgiven a little, a third of the way twice not at all; green counts up to half of it. */
        val cost: Double get() = 2.0 * off + 1.5 * max(0.0, twice - 0.08) + 3.0 * max(0.0, twice - 0.25) - 1.2 * min(green, 0.5)
        fun good(greens: Boolean): Boolean = off <= 0.12 && twice <= 0.15 && (!greens || green >= 0.25)
    }

    private fun measure(r: Route, seconds: Double, greens: List<Green>): Measure {
        val samples = samples(r.points)
        return Measure(abs(r.duration - seconds) / seconds, twice(samples), if (samples.isEmpty()) 0.0 else samples.count { p -> greens.any { p in it.box } }.toDouble() / samples.size)
    }

    private fun samples(points: List<LatLon>): List<LatLon> {
        val line = Polyline(points)
        val n = (line.length / SAMPLE_M).toInt()
        return (0..n).map { line.pointAt(it * SAMPLE_M) }
    }

    /**
     * The share of [samples] on ground walked before: within a street's width of an earlier sample that is more than a
     * corner's length back along the way.
     */
    internal fun twice(samples: List<LatLon>): Double {
        if (samples.size < 2) return 0.0
        val gap = (60.0 / SAMPLE_M).toInt()
        val cells = HashMap<Long, MutableList<Int>>()
        val lat = Geo.metresToDegreesLat(SAME_M)
        val lon = Geo.metresToDegreesLon(SAME_M, samples.first().lat)
        fun key(i: Long, j: Long) = (i shl 32) xor (j and 0xFFFFFFFFL)
        var count = 0
        for ((n, p) in samples.withIndex()) {
            val i = Math.floor(p.lat / lat).toLong()
            val j = Math.floor(p.lon / lon).toLong()
            var seen = false
            for (a in i - 1..i + 1) for (b in j - 1..j + 1) if (cells[key(a, b)]?.any { n - it > gap && Geo.distance(samples[it], p) <= SAME_M } == true) seen = true
            if (seen) count++
            cells.getOrPut(key(i, j)) { ArrayList() } += n
        }
        return count.toDouble() / samples.size
    }

    /** The green most of [r] goes through, by name, where a tenth of it or more does; null where none. */
    private fun through(r: Route, greens: List<Green>): String? {
        val samples = samples(r.points)
        return greens.filter { it.name != null }.map { g -> g to samples.count { it in g.box } }.filter { it.second * 10 >= samples.size }.maxByOrNull { it.second }?.first?.name
    }

    /**
     * [best] with a stop that made the way go up a side street and back (it lay beside the street, or up one) moved to
     * where that spur began, if the route without it walks less twice and is still about as long as wanted.
     */
    private suspend fun despiked(best: Tried, seconds: Double, greens: List<Green>, router: Router, from: LatLon): Tried? {
        val vias = best.request.waypoints.drop(1).dropLast(1)
        val moved = moveOffSpikes(best.route.points, vias)
        if (moved == vias) return null
        val r = router.route(request(from, moved)) as? RouteResult.Found ?: return null
        val neat = Tried(request(from, moved), r.best, measure(r.best, seconds, greens))
        return neat.takeIf { it.measure.twice < best.measure.twice && it.measure.off <= max(best.measure.off, CLOSE_ENOUGH * 1.5) }
    }

    /** [vias] with those at the tip of a spur of [points] (the way doubles straight back on itself) moved to the spur's foot. */
    internal fun moveOffSpikes(points: List<LatLon>, vias: List<LatLon>): List<LatLon> {
        val line = Polyline(points)
        return vias.map { v ->
            val tip = points.indices.drop(1).dropLast(1).minByOrNull { Geo.distance(points[it], v) } ?: return@map v
            val at = line.cumulative[tip]
            var back = 0.0
            while (back + SPIKE_STEP_M <= min(at, line.length - at) && Geo.distance(line.pointAt(at - back - SPIKE_STEP_M), line.pointAt(at + back + SPIKE_STEP_M)) < 6.0) back += SPIKE_STEP_M
            if (back >= SPIKE_M) line.pointAt(at - back) else v
        }
    }

    private fun round(from: LatLon, vias: List<LatLon>): Double = (listOf(from) + vias + from).zipWithNext { a, b -> Geo.distance(a, b) }.sum()

    private fun nearest(box: BBox, p: LatLon) = LatLon(p.lat.coerceIn(box.south, box.north), p.lon.coerceIn(box.west, box.east))

    /** [box] drawn in a little each side: within a green, off the road round it. */
    private fun inner(box: BBox): BBox {
        val dx = min(box.width * 0.15, Geo.metresToDegreesLon(MARGIN_M, box.center.lat))
        val dy = min(box.height * 0.15, Geo.metresToDegreesLat(MARGIN_M))
        return BBox(box.west + dx, box.south + dy, box.east - dx, box.north - dy)
    }

    private fun inReach(from: LatLon, g: Green, way: Double): Boolean = Geo.distance(from, nearest(inner(g.box), from)) <= FARTHEST * way

    private fun worth(from: LatLon, g: Green, way: Double): Double =
        min(perimeter(g.box), way) - Geo.distance(from, nearest(g.box, from))

    private fun perimeter(b: BBox): Double {
        val c = b.center
        return 2 * (Geo.distance(LatLon(c.lat, b.west), LatLon(c.lat, b.east)) + Geo.distance(LatLon(b.south, c.lon), LatLon(b.north, c.lon)))
    }

    /**
     * Two ends in [g], as far apart as [way] allows round from [from] to one, on to the other and back; null where the
     * green cannot take a tenth of the way or more, or the ends lie one behind the other seen from [from] (out and back
     * would be the same streets).
     */
    private fun endsIn(from: LatLon, g: Green, way: Double): List<LatLon>? {
        val b = inner(g.box)
        val grid = (0..GRID).flatMap { i -> (0..GRID).map { j -> LatLon(b.south + b.height * i / GRID, b.west + b.width * j / GRID) } }
        var best: Pair<LatLon, LatLon>? = null
        var longest = way * 0.1
        val inside = from in g.box
        for (a in grid) for (c in grid) {
            val across = Geo.distance(a, c)
            if (across <= longest) continue
            val all = Geo.distance(from, a) + across + Geo.distance(c, from)
            if (abs(all - way) > way * 0.1) continue
            if (!inside && abs(Geo.turnAngle(Geo.bearing(from, a), Geo.bearing(from, c))) < MIN_ANGLE) continue
            longest = across
            best = a to c
        }
        return best?.toList()
    }

    /** A diamond whose far corner lies in [g], as near square as the green's distance allows; null where it is out of reach. */
    private fun diamondTo(from: LatLon, g: Green, greens: List<Green>, way: Double): List<LatLon>? {
        val b = inner(g.box)
        val toward = Geo.bearing(from, b.center)
        val wanted = (way / (2 * sqrt(2.0))).coerceIn(Geo.distance(from, nearest(b, from)), Geo.distance(from, b.center) + max(0.0, diagonal(b) / 2))
        val far = nearest(b, Geo.destination(from, toward, wanted))
        val d = Geo.distance(from, far)
        if (d > FARTHEST * way) return null
        val side = sqrt(max(0.0, (way / 4) * (way / 4) - d * d / 4))
        if (side < d * THINNEST) return null
        return corners(from, Geo.bearing(from, far), d, side, greens)
    }

    private fun diagonal(b: BBox) = Geo.distance(LatLon(b.south, b.west), LatLon(b.north, b.east))

    /** A square diamond towards [bearing], its corners drawn into [greens] they lie near. */
    private fun diamond(from: LatLon, bearing: Double, way: Double, greens: List<Green>): List<LatLon> {
        val d = way / (2 * sqrt(2.0))
        return corners(from, bearing, d, d / 2, greens, far = true)
    }

    private fun corners(from: LatLon, bearing: Double, d: Double, side: Double, greens: List<Green>, far: Boolean = false): List<LatLon> {
        val mid = Geo.destination(from, bearing, d / 2)
        val pull = 0.4 * max(side, d / 2)
        fun drawn(p: LatLon): LatLon {
            val g = greens.minByOrNull { Geo.distance(p, nearest(it.box, p)) } ?: return p
            return if (Geo.distance(p, nearest(g.box, p)) <= pull) nearest(inner(g.box), p) else p
        }
        val tip = Geo.destination(from, bearing, d)
        return listOf(drawn(Geo.destination(mid, bearing - 90, side)), if (far) drawn(tip) else tip, drawn(Geo.destination(mid, bearing + 90, side)))
    }

    /** How far the far corner of a loop can lie, as a share of the way round: a thinnest diamond's. */
    private const val FARTHEST = 0.479

    /** How thin a diamond may be, half its width to its length. */
    private const val THINNEST = 0.15

    /** Seen from the start, how far apart the two ends of a walk must lie, in degrees, for out and back to differ. */
    private const val MIN_ANGLE = 25.0

    private const val GRID = 4

    /** How far in from the edge of a green's box its stops lie, at most. */
    private const val MARGIN_M = 60.0

    /** The sides a walk with no green in reach is tried towards, in turn. */
    private val FREE = listOf(0.0, 90.0, 180.0, 270.0)
}
