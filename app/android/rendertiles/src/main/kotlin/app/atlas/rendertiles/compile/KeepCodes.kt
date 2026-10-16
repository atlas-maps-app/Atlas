package app.atlas.rendertiles.compile

import app.atlas.render.Real
import app.atlas.render.V2
import app.atlas.render.py.PyMath
import app.atlas.rendertiles.model.Keep

/**
 * Keep codes ([Keep]) of a ring, from `regions.py`'s `simplify` (regions.py:30–44) run once at every tolerance at the
 * same time.
 *
 * `simplify` splits a closed ring at the vertex farthest from its first, then each half by Douglas–Peucker: a segment
 * whose farthest inner vertex lies more than the tolerance from its chord splits there, else only its first vertex is
 * kept. The split vertex does not depend on the tolerance, so the recursion is the same tree at every tolerance, cut
 * where a chord's distance falls to the tolerance. A vertex therefore survives a tolerance t exactly when t is below the
 * smallest chord distance on its way down the tree ([thresholds]); its code counts the ladder's tolerances below that.
 *
 * Distances are computed with the port's own arithmetic in the same order (`math.dist` for the farthest vertex,
 * `math.hypot` for a chord), so a code is right to the last bit.
 */
object KeepCodes {
    /**
     * For each vertex of [pts], the tolerance below which `simplify` keeps it: the ring's two anchors (the first vertex
     * and the one farthest from it) are kept at every tolerance (+∞), a vertex never split off at 0.
     */
    fun thresholds(pts: List<V2>): DoubleArray {
        val n = pts.size
        val m = DoubleArray(n)
        if (n == 0) return m
        var iFar = 0
        var best = dist(pts[0], pts[0])
        for (i in 0 until n) {
            val dd = dist(pts[0], pts[i])
            if (dd > best) { best = dd; iFar = i }
        }
        m[0] = Double.POSITIVE_INFINITY
        m[iFar] = Double.POSITIVE_INFINITY
        // the two halves as simplify cuts them: pts[0..iFar], and pts[iFar..n) followed by pts[0]
        val first = IntArray(iFar + 1) { it }
        val second = IntArray(n - iFar + 1) { if (it < n - iFar) iFar + it else 0 }
        split(pts, first, 0, first.size - 1, Double.POSITIVE_INFINITY, m)
        split(pts, second, 0, second.size - 1, Double.POSITIVE_INFINITY, m)
        return m
    }

    /** `dp` over `idx[a..b]` with every split kept, each vertex marked with the smallest chord distance above it. */
    private fun split(pts: List<V2>, idx: IntArray, a: Int, b: Int, bound: Double, m: DoubleArray) {
        if (b - a + 1 < 3) return
        val p0 = pts[idx[a]]
        val p1 = pts[idx[b]]
        val x0 = p0.x; val z0 = p0.y; val x1 = p1.x; val z1 = p1.y
        val dx = x1 - x0; val dz = z1 - z0
        val l = PyMath.hypot(dx, dz).let { if (it == 0.0) 1e-9 else it }
        var dmax = 0.0
        var k = a
        for (i in a + 1 until b) {
            val p = pts[idx[i]]
            val dd = Math.abs((p.x - x0) * dz - (p.y - z0) * dx) / l
            if (dd > dmax) { dmax = dd; k = i }
        }
        // simplify keeps only seg[0] when dmax <= tol: no split at any tolerance of at least dmax
        if (dmax <= 0.0) return
        val t = if (dmax < bound) dmax else bound
        m[idx[k]] = t
        split(pts, idx, a, k, t, m)
        split(pts, idx, k, b, t, m)
    }

    /**
     * The keep codes of [pts] (metres in the pack's frame). A ring of fewer than [Keep.MIN_POINTS] vertices, which
     * `simplify` never touches, keeps every vertex at every tolerance ([Keep.ALL]).
     */
    fun of(pts: List<V2>): ByteArray {
        if (pts.size < Keep.MIN_POINTS) return ByteArray(pts.size) { Keep.ALL.toByte() }
        val m = thresholds(pts)
        val tol = Keep.TOLERANCES
        return ByteArray(pts.size) { i ->
            var c = 0
            for (j in 1 until tol.size) if (tol[j] < m[i]) c++
            c.toByte()
        }
    }

    /**
     * The ring `simplify(pts, Keep.TOLERANCES[level])` gives, from the codes alone, with `simplify`'s two exceptions: a
     * ring of fewer than [Keep.MIN_POINTS] points stays whole, and so does one whose simplified ring has fewer than 3
     * points or an area of 2 m² or less. Level 0 is the whole ring (the port does not simplify at tolerance 0).
     */
    fun simplify(pts: List<V2>, codes: ByteArray, level: Int): List<V2> {
        require(codes.size == pts.size) { "${codes.size} codes for ${pts.size} points" }
        if (level == 0 || pts.size < Keep.MIN_POINTS) return pts
        val ring = ArrayList<V2>(pts.size)
        for (i in pts.indices) if (codes[i] >= level) ring.add(pts[i])
        return if (ring.size >= 3 && Math.abs(Real.signedArea(ring)) > 2) ring else pts
    }

    private fun dist(a: V2, b: V2): Double = PyMath.dist(doubleArrayOf(a.x, a.y), doubleArrayOf(b.x, b.y))
}
