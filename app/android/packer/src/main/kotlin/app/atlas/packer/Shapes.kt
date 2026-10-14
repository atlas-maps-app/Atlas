package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Boxes and centres of node lists given as 1e-7 degree integers, (lat, lon) pairs. Areas and lengths are worked out in
 * a local plane (longitudes shrunk by the cosine of the latitude), which is exact enough for anything a place is.
 */
object Shapes {
    // divided, not multiplied by 1e-7: the double nearest the decimal OSM writes, as an Overpass import parses it
    private const val E7 = 1e7

    /** The box around the first [n] points of [c]. */
    fun box(c: IntArray, n: Int): BBox {
        require(n > 0) { "no points" }
        var s = Int.MAX_VALUE
        var w = Int.MAX_VALUE
        var no = Int.MIN_VALUE
        var e = Int.MIN_VALUE
        for (i in 0 until n) {
            val la = c[2 * i]
            val lo = c[2 * i + 1]
            if (la < s) s = la
            if (la > no) no = la
            if (lo < w) w = lo
            if (lo > e) e = lo
        }
        return BBox(w / E7, s / E7, e / E7, no / E7)
    }

    /** Where to pin a way: the centroid of its area when it closes on itself, else the point halfway along it. */
    fun wayCentre(c: IntArray, n: Int): LatLon {
        val closed = n >= 4 && c[0] == c[2 * n - 2] && c[1] == c[2 * n - 1]
        return if (closed) ringCentroid(c, n) ?: vertexMean(c, n - 1) else midpoint(c, n)
    }

    /** The centroid of the area of a closed ring, or null when it encloses no area. */
    fun ringCentroid(c: IntArray, n: Int): LatLon? {
        val m = Moments(c[0], c[1])
        m.addRing(c, 0, n, sign = 1.0)
        return m.centroid()
    }

    /** The point halfway along the line (its only point when it has one). */
    fun midpoint(c: IntArray, n: Int): LatLon {
        if (n == 1) return LatLon(c[0] / E7, c[1] / E7)
        val k = cos(Math.toRadians(c[0] / E7))
        var total = 0.0
        for (i in 1 until n) total += segment(c, i, k)
        if (total == 0.0) return LatLon(c[0] / E7, c[1] / E7)
        var half = total / 2
        for (i in 1 until n) {
            val d = segment(c, i, k)
            if (d >= half && d > 0) {
                val t = half / d
                val la = c[2 * i - 2] + (c[2 * i].toDouble() - c[2 * i - 2]) * t
                val lo = c[2 * i - 1] + (c[2 * i + 1].toDouble() - c[2 * i - 1]) * t
                return LatLon(la / E7, lo / E7)
            }
            half -= d
        }
        return LatLon(c[2 * n - 2] / E7, c[2 * n - 1] / E7)
    }

    /** The mean of the first [n] points. */
    fun vertexMean(c: IntArray, n: Int): LatLon {
        var la = 0.0
        var lo = 0.0
        for (i in 0 until n) {
            la += c[2 * i]
            lo += c[2 * i + 1]
        }
        return LatLon(la / n / E7, lo / n / E7)
    }

    private fun segment(c: IntArray, i: Int, k: Double): Double {
        val dy = c[2 * i].toDouble() - c[2 * i - 2]
        val dx = (c[2 * i + 1].toDouble() - c[2 * i - 1]) * k
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Area moments of rings in one local plane around an origin, so rings add up: outer rings count, holes subtract.
     * Coordinates are relative to the origin, which keeps the shoelace sums exact for small shapes far from (0, 0).
     */
    class Moments(private val originLat: Int, private val originLon: Int) {
        private val k = cos(Math.toRadians(originLat / E7))
        private var area = 0.0
        private var mx = 0.0
        private var my = 0.0

        /** Adds the ring of points `from until to` (closed or not) with [sign] +1 for an outer ring, -1 for a hole. */
        fun addRing(c: IntArray, from: Int, to: Int, sign: Double) {
            var a = 0.0
            var cx = 0.0
            var cy = 0.0
            val n = to - from
            if (n < 3) return
            for (i in 0 until n) {
                val p = from + i
                val q = from + (i + 1) % n
                val x0 = (c[2 * p + 1].toDouble() - originLon) * k
                val y0 = c[2 * p].toDouble() - originLat
                val x1 = (c[2 * q + 1].toDouble() - originLon) * k
                val y1 = c[2 * q].toDouble() - originLat
                val cross = x0 * y1 - x1 * y0
                a += cross
                cx += (x0 + x1) * cross
                cy += (y0 + y1) * cross
            }
            if (a == 0.0) return
            // whatever the winding, weigh the ring by its absolute area
            val s = if (a < 0) -sign else sign
            area += s * a / 2
            mx += s * cx / 6
            my += s * cy / 6
        }

        /** The centroid of what was added, or null when it has no area (or the holes ate it). */
        fun centroid(): LatLon? {
            if (abs(area) < 1e-6 || area < 0) return null
            val x = mx / area
            val y = my / area
            return LatLon((originLat + y) / E7, (originLon + x / k) / E7)
        }
    }
}
