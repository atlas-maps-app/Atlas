package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A uniform grid over the segments of a line, in flat metres around the line's centre, for fast
 * "nearest segment" and "which segment does this cross" questions.
 */
internal class SegmentIndex(points: List<LatLon>, private val cell: Double = 50.0) {
    private val frame = LocalFrame((BBox.of(points) ?: BBox(0.0, 0.0, 0.0, 0.0)).center)
    private val xs = DoubleArray(points.size) { frame.x(points[it].lon) }
    private val ys = DoubleArray(points.size) { frame.y(points[it].lat) }
    private val grid = HashMap<Long, IntArray>()

    /** Segment [segment] at fraction [t] is [distance] metres away. */
    data class Near(val segment: Int, val t: Double, val distance: Double)

    init {
        val buckets = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until points.size - 1) {
            val x0 = cellOf(min(xs[i], xs[i + 1]))
            val x1 = cellOf(max(xs[i], xs[i + 1]))
            val y0 = cellOf(min(ys[i], ys[i + 1]))
            val y1 = cellOf(max(ys[i], ys[i + 1]))
            for (cx in x0..x1) for (cy in y0..y1) buckets.getOrPut(key(cx, cy)) { ArrayList(4) }.add(i)
        }
        for ((k, v) in buckets) grid[k] = v.toIntArray()
    }

    private fun cellOf(v: Double) = floor(v / cell).toInt()
    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    /** The nearest segment within [radius] metres of [p], or null. */
    fun nearest(p: LatLon, radius: Double): Near? {
        val px = frame.x(p.lon)
        val py = frame.y(p.lat)
        var best: Near? = null
        for (cx in cellOf(px - radius)..cellOf(px + radius)) for (cy in cellOf(py - radius)..cellOf(py + radius)) {
            val segs = grid[key(cx, cy)] ?: continue
            for (i in segs) {
                val ax = xs[i]; val ay = ys[i]
                val dx = xs[i + 1] - ax; val dy = ys[i + 1] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + dx * t - px
                val qy = ay + dy * t - py
                val d = sqrt(qx * qx + qy * qy)
                if (d <= radius && (best == null || d < best.distance)) best = Near(i, t, d)
            }
        }
        return best
    }

    /** The first segment (by index) the segment [a]–[b] crosses, with the fraction along that segment. */
    fun crossing(a: LatLon, b: LatLon): Near? {
        val ax = frame.x(a.lon); val ay = frame.y(a.lat)
        val bx = frame.x(b.lon); val by = frame.y(b.lat)
        var best: Near? = null
        val seen = HashSet<Int>()
        for (cx in cellOf(min(ax, bx))..cellOf(max(ax, bx))) for (cy in cellOf(min(ay, by))..cellOf(max(ay, by))) {
            val segs = grid[key(cx, cy)] ?: continue
            for (i in segs) {
                if (!seen.add(i)) continue
                val px = xs[i]; val py = ys[i]
                val rx = xs[i + 1] - px; val ry = ys[i + 1] - py
                val sx = bx - ax; val sy = by - ay
                val den = rx * sy - ry * sx
                if (den == 0.0) continue
                val t = ((ax - px) * sy - (ay - py) * sx) / den
                val u = ((ax - px) * ry - (ay - py) * rx) / den
                if (t in 0.0..1.0 && u in 0.0..1.0 && (best == null || i < best.segment)) best = Near(i, t, 0.0)
            }
        }
        return best
    }
}
