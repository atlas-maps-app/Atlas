package app.atlas.mapview

import app.atlas.geo.Mercator
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Route lines as ribbons on the ground: widened in metres and projected, so they narrow into the distance like paint
 * on the road (never thinner than a hairline), with round joins and caps, cut at the near plane and at the haze.
 * Pieces of a route take their own colours (the travelled part faded) or a dash pattern; points closer than a couple
 * of pixels on screen are merged. Buffers are kept between frames.
 */
class RouteRibbons {
    /** A route's points (latitude, longitude) with their distance along it in true metres, computed once per route. */
    private class Prepared(val lat: DoubleArray, val lon: DoubleArray, val s: DoubleArray, val n: Int) {
        val length: Double get() = if (n == 0) 0.0 else s[n - 1]
    }

    private val prepared = IdentityHashMap<RouteLine, Prepared>()
    private val usedThisFrame = IdentityHashMap<RouteLine, Boolean>()

    // The visible run being emitted: local metres, distance along, depth, screen.
    private var lx = DoubleArray(256)
    private var ly = DoubleArray(256)
    private var ls = DoubleArray(256)
    private var ld = DoubleArray(256)
    private var sx = FloatArray(256)
    private var sy = FloatArray(256)
    private var n = 0

    // Every run of the last fill pass, for the label chip: screen points and run breaks.
    private var chipX = FloatArray(256)
    private var chipY = FloatArray(256)
    private var chipBreak = BooleanArray(256)
    private var chipN = 0

    private lateinit var g: GroundView
    private lateinit var buf: TriangleBuffer
    private var hw = 0.0
    private var minHalfPx = 0.0

    /** Call once per frame before drawing routes; forgets routes no longer drawn. */
    fun beginFrame() {
        prepared.keys.retainAll(usedThisFrame.keys)
        usedThisFrame.clear()
    }

    /**
     * Draws one pass of [route]: [halfWidthM] metres each side (at least [minHalfPx] pixels), [colour] before the
     * travelled distance [fadedColour], dashed when the route says.
     */
    fun draw(
        buffer: TriangleBuffer, ground: GroundView, route: RouteLine, halfWidthM: Double, minHalfPx: Double,
        colour: Int, fadedColour: Int, dashPeriodM: Double, dashM: Double, collectChip: Boolean,
    ) {
        val p = prepare(route)
        if (p.n < 2) return
        g = ground
        buf = buffer
        hw = halfWidthM
        this.minHalfPx = minHalfPx
        if (collectChip) chipN = 0
        val far = ground.camera.far
        n = 0
        var prevX = ground.localX(p.lat[0], p.lon[0])
        var prevY = ground.localY(p.lat[0], p.lon[0])
        var prevD = ground.depth(prevX, prevY)
        for (i in 1 until p.n) {
            val x = ground.localX(p.lat[i], p.lon[i])
            val y = ground.localY(p.lat[i], p.lon[i])
            val d = ground.depth(x, y)
            // The part of the segment between the near clip and the haze.
            var t0 = 0.0
            var t1 = 1.0
            if (prevD == d) {
                if (d < ground.nearClip || d > far) { t0 = 1.0; t1 = 0.0 }
            } else {
                val a = (ground.nearClip - prevD) / (d - prevD)
                val b = (far - prevD) / (d - prevD)
                t0 = max(0.0, min(a, b))
                t1 = min(1.0, max(a, b))
            }
            if (t0 >= t1) {
                flushRun(p, route.dashed, dashPeriodM, dashM, colour, fadedColour, route.travelled, collectChip)
            } else {
                val s0 = p.s[i - 1]
                val s1 = p.s[i]
                if (t0 > 0.0 || n == 0) {
                    if (t0 > 0.0) flushRun(p, route.dashed, dashPeriodM, dashM, colour, fadedColour, route.travelled, collectChip)
                    add(prevX + (x - prevX) * t0, prevY + (y - prevY) * t0, s0 + (s1 - s0) * t0, prevD + (d - prevD) * t0, force = true)
                }
                val last = t1 < 1.0 || i == p.n - 1
                add(prevX + (x - prevX) * t1, prevY + (y - prevY) * t1, s0 + (s1 - s0) * t1, prevD + (d - prevD) * t1, force = last)
                if (t1 < 1.0) flushRun(p, route.dashed, dashPeriodM, dashM, colour, fadedColour, route.travelled, collectChip)
            }
            prevX = x; prevY = y; prevD = d
        }
        flushRun(p, route.dashed, dashPeriodM, dashM, colour, fadedColour, route.travelled, collectChip)
    }

    /**
     * Where the label chip of the last fill pass goes: halfway along the part of the route on screen, into [out]
     * (x, y). False when none of it shows.
     */
    fun chipPoint(out: FloatArray, margin: Float): Boolean {
        var total = 0.0
        val w = g.width; val h = g.height
        fun inside(i: Int) = chipX[i] >= margin && chipX[i] <= w - margin && chipY[i] >= margin && chipY[i] <= h - margin
        for (i in 1 until chipN) {
            if (chipBreak[i] || !inside(i) || !inside(i - 1)) continue
            total += dist(chipX[i - 1], chipY[i - 1], chipX[i], chipY[i])
        }
        if (total <= 0.0) return false
        var walked = 0.0
        for (i in 1 until chipN) {
            if (chipBreak[i] || !inside(i) || !inside(i - 1)) continue
            val seg = dist(chipX[i - 1], chipY[i - 1], chipX[i], chipY[i]).toDouble()
            if (walked + seg >= total / 2) {
                val t = if (seg > 0) ((total / 2 - walked) / seg).toFloat() else 0f
                out[0] = chipX[i - 1] + (chipX[i] - chipX[i - 1]) * t
                out[1] = chipY[i - 1] + (chipY[i] - chipY[i - 1]) * t
                return true
            }
            walked += seg
        }
        return false
    }

    private fun prepare(route: RouteLine): Prepared {
        usedThisFrame[route] = true
        prepared[route]?.let { return it }
        val pts = route.points
        val m = pts.size
        val lat = DoubleArray(m) { pts[it].lat }
        val lon = DoubleArray(m) { pts[it].lon }
        return Prepared(lat, lon, along(pts), m).also { prepared[route] = it }
    }

    /** Adds a point to the run, merging it into the last when they are under 1.5 px apart (unless [force]). */
    private fun add(x: Double, y: Double, s: Double, d: Double, force: Boolean) {
        val px = g.screenX(x, y, d)
        val py = g.screenY(x, y, d)
        if (n > 0 && !force) {
            val ddx = px - sx[n - 1]
            val ddy = py - sy[n - 1]
            if (ddx * ddx + ddy * ddy < 2.25f) return
        }
        if (n > 0 && force) {
            // A forced point replaces a last one it nearly coincides with, so the run ends exactly here.
            val ddx = px - sx[n - 1]
            val ddy = py - sy[n - 1]
            if (ddx * ddx + ddy * ddy < 0.25f && n > 1) n--
        }
        if (n == lx.size) grow()
        lx[n] = x; ly[n] = y; ls[n] = s; ld[n] = d; sx[n] = px; sy[n] = py
        n++
    }

    private fun grow() {
        val m = lx.size * 2
        lx = lx.copyOf(m); ly = ly.copyOf(m); ls = ls.copyOf(m); ld = ld.copyOf(m)
        sx = sx.copyOf(m); sy = sy.copyOf(m)
    }

    private fun flushRun(
        p: Prepared, dashed: Boolean, period: Double, dash: Double, colour: Int, faded: Int, travelled: Double, chip: Boolean,
    ) {
        if (n == 0) return
        if (chip) collect()
        if (n == 1) {
            disc(lx[0], ly[0], ld[0], if (ls[0] < travelled) faded else colour)
            n = 0
            return
        }
        val s0 = ls[0]
        val s1 = ls[n - 1]
        cursor = 0
        if (dashed && period > 0.0 && dash > 0.0) {
            var k = floor(s0 / period)
            while (k * period < s1) {
                val a = max(s0, k * period)
                val b = min(s1, k * period + dash)
                if (b > a) piece(a, b, if (b <= travelled) faded else colour)
                k += 1.0
            }
        } else if (travelled > s0 && travelled < s1) {
            piece(s0, travelled, faded)
            piece(travelled, s1, colour)
        } else {
            piece(s0, s1, if (s1 <= travelled) faded else colour)
        }
        n = 0
    }

    private fun collect() {
        for (i in 0 until n) {
            if (chipN == chipX.size) {
                val m = chipX.size * 2
                chipX = chipX.copyOf(m); chipY = chipY.copyOf(m); chipBreak = chipBreak.copyOf(m)
            }
            chipX[chipN] = sx[i]; chipY[chipN] = sy[i]; chipBreak[chipN] = i == 0
            chipN++
        }
    }

    private var cursor = 0

    // One piece's points (local metres, depth) and, per point, the offsets the segments ending and starting there use.
    private var px = DoubleArray(256)
    private var py = DoubleArray(256)
    private var pd = DoubleArray(256)
    private var inX = DoubleArray(256)
    private var inY = DoubleArray(256)
    private var outX = DoubleArray(256)
    private var outY = DoubleArray(256)
    private var sharp = BooleanArray(256)
    private var m = 0

    /**
     * The ribbon from [a] to [b] metres along the current run: one quad per segment, mitred where the line bends gently
     * (neighbouring quads share their edge, so no gaps and no overlaps), a round join where it turns sharply, and round
     * caps at both ends.
     */
    private fun piece(a: Double, b: Double, colour: Int) {
        while (cursor < n - 2 && ls[cursor + 1] <= a) cursor++
        var i = cursor
        m = 0
        point(lerp(lx, i, a), lerp(ly, i, a), lerp(ld, i, a))
        while (i < n - 2 && ls[i + 1] < b) {
            point(lx[i + 1], ly[i + 1], ld[i + 1])
            i++
        }
        point(lerp(lx, i, b), lerp(ly, i, b), lerp(ld, i, b))
        if (m < 2) {
            if (m == 1) disc(px[0], py[0], pd[0], colour)
            return
        }
        joins()
        disc(px[0], py[0], pd[0], colour)
        for (k in 0 until m - 1) {
            quad(px[k], py[k], pd[k], outX[k], outY[k], px[k + 1], py[k + 1], pd[k + 1], inX[k + 1], inY[k + 1], colour)
            if (sharp[k + 1]) disc(px[k + 1], py[k + 1], pd[k + 1], colour)
        }
        disc(px[m - 1], py[m - 1], pd[m - 1], colour)
    }

    /** Adds a point to the piece, skipping one that repeats the last. */
    private fun point(x: Double, y: Double, d: Double) {
        if (m > 0) {
            val dx = x - px[m - 1]
            val dy = y - py[m - 1]
            if (dx * dx + dy * dy < 1e-8) return
        }
        if (m == px.size) {
            val k = m * 2
            px = px.copyOf(k); py = py.copyOf(k); pd = pd.copyOf(k)
            inX = inX.copyOf(k); inY = inY.copyOf(k); outX = outX.copyOf(k); outY = outY.copyOf(k); sharp = sharp.copyOf(k)
        }
        px[m] = x; py[m] = y; pd[m] = d
        m++
    }

    /** The offsets at every point of the piece: the segment's normal at the ends and at sharp turns, a miter elsewhere. */
    private fun joins() {
        for (k in 0 until m) {
            val h = halfWidth(pd[k])
            var nax = 0.0; var nay = 0.0; var nbx = 0.0; var nby = 0.0
            if (k > 0) {
                val dx = px[k] - px[k - 1]; val dy = py[k] - py[k - 1]
                val l = sqrt(dx * dx + dy * dy)
                nax = -dy / l; nay = dx / l
            }
            if (k < m - 1) {
                val dx = px[k + 1] - px[k]; val dy = py[k + 1] - py[k]
                val l = sqrt(dx * dx + dy * dy)
                nbx = -dy / l; nby = dx / l
            }
            if (k == 0) { nax = nbx; nay = nby }
            if (k == m - 1) { nbx = nax; nby = nay }
            val c = nax * nbx + nay * nby
            if (c < SHARP_COS) {
                sharp[k] = true
                inX[k] = nax * h; inY[k] = nay * h
                outX[k] = nbx * h; outY[k] = nby * h
            } else {
                sharp[k] = false
                // The miter: along the mean normal, long enough that both edges stay [h] from the line.
                var mx = nax + nbx
                var my = nay + nby
                val ml = sqrt(mx * mx + my * my)
                mx /= ml; my /= ml
                val k2 = h / max(0.5, mx * nbx + my * nby)
                inX[k] = mx * k2; inY[k] = my * k2
                outX[k] = inX[k]; outY[k] = inY[k]
            }
        }
    }

    /** The run's value [v] at distance [s] along it, within segment [i] (clamped to it). */
    private fun lerp(v: DoubleArray, i: Int, s: Double): Double {
        val sa = ls[i]
        val sb = ls[i + 1]
        val t = if (sb > sa) ((s - sa) / (sb - sa)).coerceIn(0.0, 1.0) else 0.0
        return v[i] + (v[i + 1] - v[i]) * t
    }

    private fun halfWidth(d: Double): Double = max(hw, minHalfPx / g.pixelsPerMetre(d))

    /** The quad of one segment: from (x0, y0) offset by ±(o0x, o0y) to (x1, y1) offset by ±(o1x, o1y). */
    private fun quad(
        x0: Double, y0: Double, d0: Double, o0x: Double, o0y: Double,
        x1: Double, y1: Double, d1: Double, o1x: Double, o1y: Double, colour: Int,
    ) {
        val ax = x0 + o0x; val ay = y0 + o0y
        val bx = x0 - o0x; val by = y0 - o0y
        val cx = x1 - o1x; val cy = y1 - o1y
        val ex = x1 + o1x; val ey = y1 + o1y
        // Corner depths: the near plane must not be crossed by the widening.
        val da = g.depth(ax, ay); val db = g.depth(bx, by); val dc = g.depth(cx, cy); val de = g.depth(ex, ey)
        val near = g.camera.near
        if (da < near || db < near || dc < near || de < near) return
        val pax = g.screenX(ax, ay, da); val pay = g.screenY(ax, ay, da)
        val pbx = g.screenX(bx, by, db); val pby = g.screenY(bx, by, db)
        val pcx = g.screenX(cx, cy, dc); val pcy = g.screenY(cx, cy, dc)
        val pex = g.screenX(ex, ey, de); val pey = g.screenY(ex, ey, de)
        val minX = min(min(pax, pbx), min(pcx, pex)); val maxX = max(max(pax, pbx), max(pcx, pex))
        val minY = min(min(pay, pby), min(pcy, pey)); val maxY = max(max(pay, pby), max(pcy, pey))
        if (!g.onScreen(minX, minY, maxX, maxY, 2f)) return
        buf.reserve(4, 6)
        val a = buf.vertex(pax, pay, colour)
        val b = buf.vertex(pbx, pby, colour)
        val c = buf.vertex(pcx, pcy, colour)
        val e = buf.vertex(pex, pey, colour)
        buf.triangle(a, b, c)
        buf.triangle(a, c, e)
    }

    /** A disc on the ground (a round join or cap). */
    private fun disc(x: Double, y: Double, d: Double, colour: Int) {
        val r = halfWidth(d)
        val cxp = g.screenX(x, y, d)
        val cyp = g.screenY(x, y, d)
        val rp = (r * g.pixelsPerMetre(d)).toFloat() * 2f
        if (!g.onScreen(cxp - rp, cyp - rp, cxp + rp, cyp + rp, 2f)) return
        if (d - r < g.camera.near) return
        buf.reserve(SEGMENTS + 1, SEGMENTS * 3)
        val c = buf.vertex(cxp, cyp, colour)
        for (k in 0 until SEGMENTS) {
            val qx = x + r * COS[k]
            val qy = y + r * SIN[k]
            val qd = g.depth(qx, qy)
            buf.vertex(g.screenX(qx, qy, qd), g.screenY(qx, qy, qd), colour)
        }
        for (k in 0 until SEGMENTS) buf.triangle(c, c + 1 + k, c + 1 + (k + 1) % SEGMENTS)
    }

    companion object {
        private const val SEGMENTS = 12

        /** The distance along [points] to each of them, true metres: unit Mercator scaled at each segment's latitude. */
        fun along(points: List<app.atlas.geo.LatLon>): DoubleArray {
            val s = DoubleArray(points.size)
            for (i in 1 until points.size) {
                val dx = Mercator.x(points[i].lon) - Mercator.x(points[i - 1].lon)
                val dy = Mercator.y(points[i].lat) - Mercator.y(points[i - 1].lat)
                val k = Mercator.metresPerUnit((points[i].lat + points[i - 1].lat) / 2)
                s[i] = s[i - 1] + sqrt(dx * dx + dy * dy) * k
            }
            return s
        }

        /** Turns sharper than this (between the segments' normals, 50°) get a round join instead of a miter. */
        private val SHARP_COS = cos(50 * PI / 180)
        private val COS = DoubleArray(SEGMENTS) { cos(2 * PI * it / SEGMENTS) }
        private val SIN = DoubleArray(SEGMENTS) { sin(2 * PI * it / SEGMENTS) }
    }
}
