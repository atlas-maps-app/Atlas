package app.atlas.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Plane geometry on rings stored as flat (x, y) arrays in metres, without a repeated closing point. Positive area
 * means clockwise on a y-down screen: an outer ring, as vector tiles wind them.
 */
object Rings {
    fun area(r: DoubleArray): Double {
        var a = 0.0
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            a += r[2 * j] * r[2 * i + 1] - r[2 * i] * r[2 * j + 1]
            j = i
        }
        return a / 2
    }

    /** The area centroid, or the vertex average for a ring without area. */
    fun centroid(r: DoubleArray): DoubleArray {
        val n = r.size / 2
        var a = 0.0; var cx = 0.0; var cy = 0.0
        // Relative to the first point for precision.
        val ox = r[0]; val oy = r[1]
        var j = n - 1
        for (i in 0 until n) {
            val xj = r[2 * j] - ox; val yj = r[2 * j + 1] - oy
            val xi = r[2 * i] - ox; val yi = r[2 * i + 1] - oy
            val f = xj * yi - xi * yj
            a += f; cx += (xj + xi) * f; cy += (yj + yi) * f
            j = i
        }
        if (abs(a) < 1e-9) {
            var sx = 0.0; var sy = 0.0
            for (i in 0 until n) { sx += r[2 * i]; sy += r[2 * i + 1] }
            return doubleArrayOf(sx / n, sy / n)
        }
        return doubleArrayOf(ox + cx / (3 * a), oy + cy / (3 * a))
    }

    /** Drops a closing duplicate, repeated points, and points that bend the ring by less than [areaTol] m² of triangle. */
    fun clean(r: DoubleArray, areaTol: Double = 0.02, keep: BooleanArray? = null): DoubleArray {
        var n = r.size / 2
        if (n >= 2 && r[0] == r[2 * n - 2] && r[1] == r[2 * n - 1]) n--
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        var m = 0
        for (i in 0 until n) {
            val x = r[2 * i]; val y = r[2 * i + 1]
            if (m > 0 && abs(xs[m - 1] - x) < 1e-6 && abs(ys[m - 1] - y) < 1e-6) continue
            xs[m] = x; ys[m] = y; m++
        }
        if (m > 1 && abs(xs[0] - xs[m - 1]) < 1e-6 && abs(ys[0] - ys[m - 1]) < 1e-6) m--
        // Remove near-collinear points until none is left (a few passes at most).
        var changed = true
        while (changed && m > 3) {
            changed = false
            var i = 0
            while (i < m && m > 3) {
                val p = (i + m - 1) % m; val q = (i + 1) % m
                val tri = abs((xs[i] - xs[p]) * (ys[q] - ys[p]) - (xs[q] - xs[p]) * (ys[i] - ys[p])) / 2
                if (tri < areaTol) {
                    for (k in i until m - 1) { xs[k] = xs[k + 1]; ys[k] = ys[k + 1] }
                    m--
                    changed = true
                } else i++
            }
        }
        val out = DoubleArray(m * 2)
        for (i in 0 until m) { out[2 * i] = xs[i]; out[2 * i + 1] = ys[i] }
        return out
    }

    fun reversed(r: DoubleArray): DoubleArray {
        val n = r.size / 2
        val out = DoubleArray(r.size)
        for (i in 0 until n) { out[2 * i] = r[2 * (n - 1 - i)]; out[2 * i + 1] = r[2 * (n - 1 - i) + 1] }
        return out
    }

    /** Index of the ring's longest edge (from vertex i to i+1). */
    fun longestEdge(r: DoubleArray): Int {
        val n = r.size / 2
        var best = 0; var bestLen = -1.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val l = hypot(r[2 * j] - r[2 * i], r[2 * j + 1] - r[2 * i + 1])
            if (l > bestLen) { bestLen = l; best = i }
        }
        return best
    }

    fun edgeLength(r: DoubleArray, i: Int): Double {
        val n = r.size / 2
        val j = (i + 1) % n
        return hypot(r[2 * j] - r[2 * i], r[2 * j + 1] - r[2 * i + 1])
    }

    /**
     * The ring's box along the unit axis (ux, uy) and its perpendicular, about point (cx, cy):
     * [s0, s1, t0, t1] where s runs along the axis and t across it.
     */
    fun extent(r: DoubleArray, cx: Double, cy: Double, ux: Double, uy: Double): DoubleArray {
        var s0 = Double.MAX_VALUE; var s1 = -Double.MAX_VALUE; var t0 = Double.MAX_VALUE; var t1 = -Double.MAX_VALUE
        for (i in 0 until r.size / 2) {
            val dx = r[2 * i] - cx; val dy = r[2 * i + 1] - cy
            val s = dx * ux + dy * uy
            val t = -dx * uy + dy * ux
            s0 = min(s0, s); s1 = max(s1, s); t0 = min(t0, t); t1 = max(t1, t)
        }
        return doubleArrayOf(s0, s1, t0, t1)
    }

    /** Even-odd point in ring test. */
    fun contains(r: DoubleArray, x: Double, y: Double): Boolean {
        var c = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]
            val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi) c = !c
            j = i
        }
        return c
    }

    /** A polygon (outer ring then holes) contains a point. */
    fun contains(rings: List<DoubleArray>, x: Double, y: Double): Boolean {
        if (!contains(rings[0], x, y)) return false
        for (k in 1 until rings.size) if (contains(rings[k], x, y)) return false
        return true
    }

    /**
     * The ring moved inward by [d] metres, corners mitred (miters capped at 3d). Null when the inset ring folds over
     * itself (the ring is too thin for it).
     */
    fun inset(r: DoubleArray, d: Double): DoubleArray? {
        val n = r.size / 2
        if (n < 3) return null
        val out = DoubleArray(r.size)
        val sign = if (area(r) > 0) 1.0 else -1.0
        for (i in 0 until n) {
            val p = (i + n - 1) % n; val q = (i + 1) % n
            val e0x = r[2 * i] - r[2 * p]; val e0y = r[2 * i + 1] - r[2 * p + 1]
            val e1x = r[2 * q] - r[2 * i]; val e1y = r[2 * q + 1] - r[2 * i + 1]
            val l0 = hypot(e0x, e0y); val l1 = hypot(e1x, e1y)
            if (l0 < 1e-9 || l1 < 1e-9) return null
            // Outward normals (ey, -ex) for a positive ring; inward is the negation.
            val n0x = -e0y / l0 * sign; val n0y = e0x / l0 * sign
            val n1x = -e1y / l1 * sign; val n1y = e1x / l1 * sign
            var bx = n0x + n1x; var by = n0y + n1y
            val bl = hypot(bx, by)
            if (bl < 1e-9) return null
            bx /= bl; by /= bl
            val cosHalf = bx * n0x + by * n0y
            val m = min(d / max(cosHalf, 1e-6), 3 * d)
            out[2 * i] = r[2 * i] + bx * m
            out[2 * i + 1] = r[2 * i + 1] + by * m
        }
        val a0 = area(r); val a1 = area(out)
        if (a1 * a0 <= 0 || abs(a1) > abs(a0)) return null
        // Every inset edge must keep its direction, or the ring folded.
        for (i in 0 until n) {
            val q = (i + 1) % n
            val ox = r[2 * q] - r[2 * i]; val oy = r[2 * q + 1] - r[2 * i + 1]
            val ix = out[2 * q] - out[2 * i]; val iy = out[2 * q + 1] - out[2 * i + 1]
            if (ox * ix + oy * iy <= 0) return null
        }
        return out
    }

    /** Distance from (px, py) to segment a–b. */
    fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 > 0) ((px - ax) * dx + (py - ay) * dy) / l2 else 0.0
        t = t.coerceIn(0.0, 1.0)
        val x = ax + dx * t - px; val y = ay + dy * t - py
        return sqrt(x * x + y * y)
    }
}
