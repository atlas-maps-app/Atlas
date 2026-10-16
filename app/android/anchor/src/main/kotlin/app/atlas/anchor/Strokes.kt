package app.atlas.anchor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Strokes (plan-gpu D3): each polyline becomes segments (SEG) with exact area coverage, trimmed where they meet: at a
 * vertex shared by two segments the plane is split by the bisector of their directions, and what lies beyond both
 * segments' ends, the join's wedge (round, miter or bevel), is drawn by the segment that ends there. Free ends carry
 * their caps; a zero-length subpath with round caps is a dot (JOIN). So no pixel is composited twice by one stroke,
 * and the coverages are those measured as "exact strip × pixel-box area, trimmed at joins" (M6), with round joins and
 * caps covered radially and miters by their two outer edges. Dashes are cut on the anchor's screen first; their ends
 * are lifted onto the segment in space.
 */
internal class Strokes(private val out: PrimBuilder) {
    var strokes = 0; var segs = 0; var joins = 0; var miters = 0; var bevels = 0; var dots = 0
    // joins counts the round joins, miters and bevels the miter-style ones (as drawn at the anchor's camera)

    /** Zero-length subpaths with square caps (not drawn: no direction to square them by). */
    var squareDots = 0

    // the current polyline: point indices and SVG coordinates
    private var pi = IntArray(64)
    private var px = DoubleArray(64)
    private var py = DoubleArray(64)
    private var pn = 0

    /**
     * Strokes [shape] [width] anchor CSS px wide, with its [cap], [join] (0 miter, 1 round, 2 bevel) and [miterLimit],
     * and [dashes] (anchor CSS px, even count) from [dashOffset]. [cut] makes the point at screen parameter s of the
     * segment from point a to point b, at SVG coordinate (x, y), and returns its index.
     */
    fun stroke(
        shape: ElementShape, width: Double, cap: Int, join: Int, miterLimit: Double, dashes: DoubleArray?, dashOffset: Double,
        flags: Int, paint: Int, item: Int, cut: (a: Int, b: Int, s: Double, x: Double, y: Double) -> Int,
    ) {
        if (!(width > 0)) return
        strokes++
        for (s in 0 until shape.subs) {
            pn = 0
            val st = shape.subStart[s]; val c = shape.subCount[s]
            for (j in 0 until c) {
                val v = st + j
                if (pn > 0 && shape.x[v] == px[pn - 1] && shape.y[v] == py[pn - 1]) continue
                push(shape.pt[v], shape.x[v], shape.y[v])
            }
            var closed = shape.subClosed[s]
            if (closed && pn > 1 && px[pn - 1] == px[0] && py[pn - 1] == py[0]) pn--
            if (pn == 1) { closed = false }
            if (dashes == null) {
                emit(width, cap, join, miterLimit, closed, flags, paint, item)
            } else {
                dash(width, cap, join, miterLimit, closed, dashes, dashOffset, flags, paint, item, cut)
            }
        }
    }

    private fun push(p: Int, x: Double, y: Double) {
        if (pn == pi.size) { pi = pi.copyOf(pn * 2); px = px.copyOf(pn * 2); py = py.copyOf(pn * 2) }
        pi[pn] = p; px[pn] = x; py[pn] = y; pn++
    }

    /** The current polyline, cut into dashes, each stroked as an open polyline with the caps. */
    private fun dash(
        width: Double, cap: Int, join: Int, miterLimit: Double, closed: Boolean, dashes: DoubleArray, dashOffset: Double,
        flags: Int, paint: Int, item: Int, cut: (Int, Int, Double, Double, Double) -> Int,
    ) {
        val total = dashes.sum()
        if (!(total > 0) || pn < 2) { emit(width, cap, join, miterLimit, closed, flags, paint, item); return }
        val n = if (closed) pn + 1 else pn
        val vi = IntArray(n) { pi[it % pn] }; val vx = DoubleArray(n) { px[it % pn] }; val vy = DoubleArray(n) { py[it % pn] }
        // where the pattern stands at the start
        var phase = ((dashOffset % total) + total) % total
        var k = 0
        while (phase >= dashes[k]) { phase -= dashes[k]; k = (k + 1) % dashes.size }
        var on = k % 2 == 0
        var left = dashes[k] - phase
        pn = 0
        if (on) push(vi[0], vx[0], vy[0])
        for (i in 0 until n - 1) {
            val ax = vx[i]; val ay = vy[i]; val bx = vx[i + 1]; val by = vy[i + 1]
            val len = hypot(bx - ax, by - ay)
            var at = 0.0
            while (len - at > left) {
                at += left
                val s = at / len
                val x = ax + (bx - ax) * s; val y = ay + (by - ay) * s
                val p = cut(vi[i], vi[i + 1], s, x, y)
                if (on) {
                    push(p, x, y)
                    emit(width, cap, join, miterLimit, false, flags, paint, item)
                    pn = 0
                } else {
                    pn = 0; push(p, x, y)
                }
                on = !on
                k = (k + 1) % dashes.size
                left = dashes[k]
            }
            left -= len - at
            if (on) push(vi[i + 1], bx, by)
        }
        if (on && pn > 1) emit(width, cap, join, miterLimit, false, flags, paint, item)
        pn = 0
    }

    /** The current polyline as segments, each drawing the join at its end and the caps at its free ends. */
    private fun emit(width: Double, cap: Int, join: Int, miterLimit: Double, closed: Boolean, flags: Int, paint: Int, item: Int) {
        val f = flags or PrimFlag.TRANSLUCENT
        val w = java.lang.Float.floatToRawIntBits(width.toFloat())
        val limit = java.lang.Float.floatToRawIntBits(miterLimit.toFloat())
        if (pn == 0) return
        if (pn == 1) {
            if (cap == Cap.ROUND) {
                out.begin(PrimKind.JOIN, f, paint, item); out.put(pi[0]); out.put(w); out.end()
                dots++
            } else if (cap == Cap.SQUARE) squareDots++
            return
        }
        val m = if (closed) pn else pn - 1
        for (i in 0 until m) {
            val a = i; val b = (i + 1) % pn
            val prev = if (i > 0) pi[i - 1] else if (closed) pi[pn - 1] else -1
            val next = if (closed) pi[(i + 2) % pn] else if (i + 2 < pn) pi[i + 2] else -1
            val ends = (if (prev < 0) cap else Cap.BUTT) or ((if (next < 0) cap else Cap.BUTT) shl 2) or ((if (next < 0) 0 else join) shl 4)
            out.begin(PrimKind.SEG, f, paint, item)
            out.put(pi[a]); out.put(pi[b]); out.put(prev); out.put(next); out.put(w); out.put(ends); out.put(limit)
            out.end()
            segs++
            if (next >= 0) {
                when (join) {
                    Join.ROUND -> joins++
                    Join.BEVEL -> bevels++
                    else -> {
                        // the miter's length over the stroke's width, 1 / sin(half the inner angle), against the limit
                        val c = (i + 2) % pn
                        val ux = px[b] - px[a]; val uy = py[b] - py[a]; val vx = px[c] - px[b]; val vy = py[c] - py[b]
                        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
                        val dot = if (lu > 0 && lv > 0) (ux * vx + uy * vy) / (lu * lv) else 1.0
                        val half = sqrt(max(0.0, (1 + dot) / 2))
                        if (half == 0.0 || 1.0 / half > miterLimit) bevels++ else miters++
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Segments at most this wide (device px) are drawn as Skia draws them: as anti-aliased hairlines whose alpha is
         * their width ([hairCoverage]), each segment its own line with no trims or joins, a capped free end half a pixel
         * longer.
         */
        const val HAIRLINE = 1.0

        /** Skia's anti-aliased hairline: the coverage of a pixel whose centre lies [d] px from the line along its minor axis. */
        fun hairCoverage(d: Double): Double = max(0.0, 1 - abs(d))

        /**
         * The pixels of a hairline from ([ax], [ay]) to ([bx], [by]), device px, and their coverage, as Skia draws a stroke
         * no wider than a pixel: along the major axis pixel by pixel (the end pixels by the share of them the line spans,
         * a capped end extended half a pixel: [extendA], [extendB]), across it the two pixels straddling the line sharing
         * the coverage ([hairCoverage]), all scaled by the stroke's [width]. Calls [plot] with each pixel and its coverage.
         */
        inline fun hairline(ax0: Double, ay0: Double, bx0: Double, by0: Double, extendA: Boolean, extendB: Boolean, width: Double, plot: (Int, Int, Double) -> Unit) {
            val xMajor = abs(bx0 - ax0) >= abs(by0 - ay0)
            var lo = if (xMajor) ax0 else ay0; var hi = if (xMajor) bx0 else by0
            var m0 = if (xMajor) ay0 else ax0; var m1 = if (xMajor) by0 else bx0
            var extLo = extendA; var extHi = extendB
            if (lo > hi) { var q = lo; lo = hi; hi = q; q = m0; m0 = m1; m1 = q; val e = extLo; extLo = extHi; extHi = e }
            val len = hi - lo
            val slope = if (len > 1e-12) (m1 - m0) / len else 0.0
            val l0 = lo - (if (extLo) 0.5 else 0.0); val h0 = hi + (if (extHi) 0.5 else 0.0)
            for (u in kotlin.math.floor(l0).toInt()..kotlin.math.ceil(h0).toInt()) {
                val c = u + 0.5
                val span = kotlin.math.min(c + 0.5, h0) - max(c - 0.5, l0)
                if (span <= 0) continue
                val m = m0 + slope * (kotlin.math.min(max(c, lo), hi) - lo)
                for (v in kotlin.math.floor(m - 1).toInt()..kotlin.math.ceil(m + 1).toInt()) {
                    val cov = hairCoverage(v + 0.5 - m) * span * width
                    if (cov <= 0) continue
                    if (xMajor) plot(u, v, cov) else plot(v, u, cov)
                }
            }
        }

        /**
         * The area of the unit pixel box centred at the origin on the near side of the line n·o = [t], for the unit
         * normal ([nx], [ny]): the trapezoid CDF the exact-area segment coverage is built from.
         */
        fun boxCdf(t: Double, nx: Double, ny: Double): Double {
            var a = abs(nx) / 2; var b = abs(ny) / 2
            if (b > a) { val q = a; a = b; b = q }
            if (t <= -(a + b)) return 0.0
            if (t >= a + b) return 1.0
            if (b < 1e-9) return clamp(0.5 + t / (2 * a), 0.0, 1.0)
            if (t < -(a - b)) { val u = t + a + b; return u * u / (8 * a * b) }
            if (t <= a - b) return 0.5 + t / (2 * a)
            val u = a + b - t
            return 1 - u * u / (8 * a * b)
        }

        /** The overlap of [d] − [hw] … [d] + [hw] with −0.5 … 0.5: a round join's or cap's coverage at distance [d]. */
        fun radial(d: Double, hw: Double): Double = max(0.0, min(d + hw, 0.5) - max(d - hw, -0.5))
    }
}
