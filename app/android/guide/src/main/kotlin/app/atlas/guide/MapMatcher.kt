package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.geo.Snap
import app.atlas.model.Fix
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One fix matched to the route.
 *
 * @property onRoute this fix lies within [tolerance] of the route; [offRoute] is the debounced verdict.
 * @property becameOffRoute the verdict turned to off route with this fix.
 * @property wrongWay this fix confirmed travel against the route's direction (once per episode).
 * @property headingDelta degrees between the traveller's heading and the route, when the heading is known.
 * @property coarse the fix was too vague to place (a cell tower's guess): the match held where it was.
 */
data class Match(
    val along: Double,
    val snapped: LatLon,
    val segment: Int,
    val offset: Double,
    val side: Int,
    val routeBearing: Double,
    val headingDelta: Double?,
    val onRoute: Boolean,
    val offRoute: Boolean,
    val becameOffRoute: Boolean,
    val rejoined: Boolean,
    val wrongWay: Boolean,
    val confidence: Double,
    val tolerance: Double,
    val speed: Double,
    val coarse: Boolean = false,
)

/**
 * Map-matches fixes to a route: [app.atlas.geo.Polyline.snap] in a window that moves with the traveller, a heading check
 * that picks the leg running your way (where a route doubles back, or where noise near a corner puts the fix nearer the
 * street you have not turned into yet), a tolerance that widens with poor accuracy (and is wider on water), and
 * hysteresis both ways: off route needs several consecutive fixes beyond the tolerance with a heading that disagrees
 * (or points away from the route), held for the mode's time; back on route needs two good fixes in a row.
 */
class MapMatcher(index: RouteIndex, private val profile: ModeProfile) {
    var index: RouteIndex = index
        private set

    private var along = 0.0
    private var acquired = false
    private var lastFix: Fix? = null
    private var outsideCount = 0
    private var outsideSince: Instant? = null
    private var insideCount = 0
    private var wrongWayCount = 0
    private var wrongWayActive = false
    private var confidence = 0.5
    private var lastSpeed = 0.0
    /** The compass bearing of each segment of the route. */
    private var bearings = bearingsOf(index)

    /** The debounced off-route verdict. */
    var offRoute = false
        private set

    /** Starts matching against a new route; the match is searched afresh near its start. */
    fun reset(newIndex: RouteIndex) {
        index = newIndex
        bearings = bearingsOf(newIndex)
        along = 0.0
        acquired = false
        offRoute = false
        outsideCount = 0
        outsideSince = null
        insideCount = 0
        wrongWayCount = 0
        wrongWayActive = false
    }

    /** Off-route distance for a fix of this [accuracy], metres. */
    fun tolerance(accuracy: Double): Double =
        (profile.offRouteM + (accuracy - 10.0).coerceAtLeast(0.0)).coerceAtMost(profile.offRouteM * 3)

    fun match(fix: Fix): Match {
        val line = index.line
        if (fix.accuracy > coarseAccuracy && line.points.size >= 2) return held(fix)
        val p = fix.location
        val prev = lastFix
        // Seconds since the last fix: capped for the speed estimate, but not for the window, so a long GPS gap (a tunnel)
        // still finds the traveller where they come out.
        val elapsed = prev?.let { Duration.between(it.time, fix.time).toMillis() / 1000.0 }?.coerceAtLeast(0.2) ?: 1.0
        val dt = elapsed.coerceAtMost(30.0)
        val moved = prev?.let { Geo.distance(it.location, p) }
        val speed = fix.speed ?: moved?.let { it / dt } ?: 0.0
        val heading = heading(fix, prev, moved, speed)
        val tol = tolerance(fix.accuracy)

        val lastSeg = (line.points.size - 2).coerceAtLeast(0)
        val (a, b) = when {
            !acquired -> 0 to lastSeg
            offRoute -> line.segmentAt((along - 100).coerceAtLeast(0.0)) to lastSeg
            else -> {
                val reach = max(speed * elapsed, moved ?: 0.0)
                val ahead = max(150.0, reach * 3 + 2 * fix.accuracy + 60)
                line.segmentAt((along - 25 - fix.accuracy).coerceAtLeast(0.0)) to line.segmentAt(along + ahead)
            }
        }
        var best: Snap = line.snap(p, a, b) ?: return degenerate(fix, tol, speed)
        if (heading != null && headingDelta(heading, best) > LEG_CHECK_DEG) {
            // The nearest leg runs another way: the route doubles back, or noise near a corner puts the fix nearer the
            // street you have not turned into yet. Weigh every leg in the window by distance and heading.
            best = byHeading(p, a, b, heading, tol, best)
        }
        val routeBearing = segmentBearing(best.segment)
        val delta = heading?.let { abs(Geo.turnAngle(routeBearing, it)) }
        val inside = best.offset <= tol
        // Heading away from the route counts as a mismatch too: going straight on past a turn keeps the old leg's bearing,
        // but the way back to the route is behind you. GPS noise moves the fix sideways while the heading stays along.
        val away = heading != null && best.offset > 1.0 && abs(Geo.turnAngle(Geo.bearing(best.point, p), heading)) < AWAY_DEG
        val mismatch = if (delta != null) delta > 45 || away else best.offset > 1.5 * tol

        var became = false
        var rejoined = false
        if (!offRoute) {
            if (!inside && (mismatch || best.offset > 3 * tol)) {
                outsideCount++
                if (outsideSince == null) outsideSince = fix.time
            } else {
                outsideCount = 0
                outsideSince = null
            }
            val held = outsideSince?.let { Duration.between(it, fix.time).toMillis() / 1000.0 } ?: 0.0
            if (outsideCount >= OFF_FIXES && held >= profile.offRouteSeconds - dt * 0.5) {
                offRoute = true
                became = true
                insideCount = 0
            }
        } else {
            val good = best.offset <= 0.7 * tol && (delta == null || delta <= 60)
            insideCount = if (good) insideCount + 1 else 0
            if (insideCount >= BACK_FIXES) {
                offRoute = false
                rejoined = true
                outsideCount = 0
                outsideSince = null
            }
        }
        if (!offRoute) {
            along = best.along
            acquired = true
        }

        var wrongWay = false
        if (!offRoute && inside && delta != null && speed >= profile.minMovingSpeed) {
            if (delta > 150) {
                wrongWayCount++
                if (wrongWayCount >= OFF_FIXES && !wrongWayActive) { wrongWayActive = true; wrongWay = true }
            } else if (delta < 90) {
                wrongWayCount = 0
                wrongWayActive = false
            }
        }

        val accFactor = (1.0 - (fix.accuracy - 10.0).coerceAtLeast(0.0) / 80.0).coerceIn(0.4, 1.0)
        val headFactor = if (delta != null) 0.5 + 0.5 * cos(Math.toRadians(delta)) else 0.8
        val inst = exp(-1.5 * (best.offset / tol) * (best.offset / tol)) * headFactor * accFactor
        confidence = (0.6 * confidence + 0.4 * inst).coerceIn(0.0, 1.0)
        lastFix = fix
        lastSpeed = speed

        val shownAlong = if (offRoute) along else best.along
        return Match(
            along = shownAlong, snapped = if (offRoute) p else best.point, segment = best.segment, offset = best.offset,
            side = best.side, routeBearing = routeBearing, headingDelta = delta, onRoute = inside, offRoute = offRoute,
            becameOffRoute = became, rejoined = rejoined, wrongWay = wrongWay, confidence = confidence, tolerance = tol, speed = speed,
        )
    }

    /** Accuracy, metres, beyond which a fix cannot be told from a neighbouring street or leg: the tolerance's own cap. */
    private val coarseAccuracy = profile.offRouteM * 3

    /**
     * A fix too coarse to place on the route (cell towers in a tunnel or an urban canyon): it neither moves the match,
     * counts towards leaving the route nor ends the journey. The last good fix stays the reference for the next one.
     */
    private fun held(fix: Fix): Match {
        val line = index.line
        val seg = line.segmentAt(along)
        val pt = line.pointAt(along)
        confidence *= 0.7
        return Match(
            along = along, snapped = if (offRoute) fix.location else pt, segment = seg, offset = Geo.distance(pt, fix.location), side = 0,
            routeBearing = segmentBearing(seg), headingDelta = null, onRoute = !offRoute, offRoute = offRoute, becameOffRoute = false,
            rejoined = false, wrongWay = false, confidence = confidence, tolerance = tolerance(fix.accuracy), speed = lastSpeed, coarse = true,
        )
    }

    private fun degenerate(fix: Fix, tol: Double, speed: Double): Match {
        lastFix = fix
        val pt = index.line.points.firstOrNull() ?: fix.location
        return Match(0.0, pt, 0, Geo.distance(pt, fix.location), 0, 0.0, null, true, false, false, false, false, confidence, tol, speed)
    }

    /** The traveller's heading: the fix's bearing when moving, else the course from the last fix when it moved clearly. */
    private fun heading(fix: Fix, prev: Fix?, moved: Double?, speed: Double): Double? {
        if (speed < profile.minMovingSpeed) return null
        fix.bearing?.let { return Geo.normalize(it) }
        if (prev != null && moved != null && moved > max(5.0, 2 * fix.accuracy)) return Geo.bearing(prev.location, fix.location)
        return null
    }

    private fun headingDelta(heading: Double, s: Snap) = abs(Geo.turnAngle(segmentBearing(s.segment), heading))

    /** Metres off the route plus a penalty that grows with the angle between the leg and the heading. */
    private fun cost(offset: Double, delta: Double, tol: Double): Double {
        val d = delta / 180.0
        return offset + 2 * tol * d * d
    }

    /**
     * The point of segments [a]..[b] with the least [cost], among those within [tol]; [nearest] when none beats it.
     * One pass in a frame centred on [p], as [app.atlas.geo.Polyline.snap] does; only the winner becomes a [Snap].
     */
    private fun byHeading(p: LatLon, a: Int, b: Int, heading: Double, tol: Double, nearest: Snap): Snap {
        val pts = index.line.points
        val f = LocalFrame(p)
        var bestCost = cost(nearest.offset, headingDelta(heading, nearest), tol)
        var bi = -1
        var bt = 0.0
        for (i in a..b) {
            val ax = f.x(pts[i].lon); val ay = f.y(pts[i].lat)
            val dx = f.x(pts[i + 1].lon) - ax; val dy = f.y(pts[i + 1].lat) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val qx = ax + dx * tc; val qy = ay + dy * tc
            val off = sqrt(qx * qx + qy * qy)
            if (off > tol) continue
            val k = cost(off, abs(Geo.turnAngle(bearings[i], heading)), tol)
            if (k < bestCost) { bestCost = k; bi = i; bt = tc }
        }
        if (bi < 0) return nearest
        val ax = f.x(pts[bi].lon); val ay = f.y(pts[bi].lat)
        val dx = f.x(pts[bi + 1].lon) - ax; val dy = f.y(pts[bi + 1].lat) - ay
        val qx = ax + dx * bt; val qy = ay + dy * bt
        val cum = index.line.cumulative
        // Positive side: p lies to the right of the leg's direction, as in Polyline.snap.
        val side = dx * (-ay) - dy * (-ax)
        return Snap(bi, bt, cum[bi] + (cum[bi + 1] - cum[bi]) * bt, sqrt(qx * qx + qy * qy), if (side < 0) 1 else -1, f.toLatLon(qx, qy))
    }

    private fun segmentBearing(i: Int): Double = if (bearings.isEmpty()) 0.0 else bearings[i.coerceIn(0, bearings.size - 1)]

    private companion object {
        fun bearingsOf(index: RouteIndex): DoubleArray {
            val pts = index.line.points
            return DoubleArray((pts.size - 1).coerceAtLeast(0)) { Geo.bearing(pts[it], pts[it + 1]) }
        }

        const val OFF_FIXES = 3
        const val BACK_FIXES = 2
        /** A nearest leg this many degrees off the heading makes the matcher weigh every leg in the window. */
        const val LEG_CHECK_DEG = 45.0
        /** A heading within this many degrees of the direction from the route to the fix is heading away from it. */
        const val AWAY_DEG = 60.0
    }
}
