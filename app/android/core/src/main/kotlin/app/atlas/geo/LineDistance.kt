package app.atlas.geo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The nearest approach of two polylines: one line's segments go into a [PackedRTree] in a local metric frame, and each
 * segment of the other searches it within the best distance found so far, so the work stays near-linear.
 */
internal object LineDistance {
    fun nearest(a: Polyline, b: Polyline): LineGap? {
        if (a.points.isEmpty() || b.points.isEmpty()) return null
        val box = a.bbox!!.union(b.bbox!!)
        val frame = LocalFrame(box.center)
        val ax = DoubleArray(a.points.size) { frame.x(a.points[it].lon) }
        val ay = DoubleArray(a.points.size) { frame.y(a.points[it].lat) }
        val bx = DoubleArray(b.points.size) { frame.x(b.points[it].lon) }
        val by = DoubleArray(b.points.size) { frame.y(b.points[it].lat) }
        val segA = max(ax.size - 1, 1)
        val segB = max(bx.size - 1, 1)

        val tree = PackedRTree(segB)
        for (j in 0 until segB) {
            val k = min(j + 1, bx.size - 1)
            tree.add(min(bx[j], bx[k]), min(by[j], by[k]), max(bx[j], bx[k]), max(by[j], by[k]))
        }
        tree.finish()

        val probe = DoubleArray(5)
        var best = Double.POSITIVE_INFINITY
        var bestA = 0
        var bestB = 0
        val bestPoints = DoubleArray(4)

        // A first bound: the segment of b nearest a's first point.
        tree.neighbors(ax[0], ay[0], 1).firstOrNull()?.let { j ->
            val k = min(j + 1, bx.size - 1)
            best = segmentGap(ax[0], ay[0], ax[0], ay[0], bx[j], by[j], bx[k], by[k], probe)
            bestA = 0; bestB = j
            probe.copyInto(bestPoints, 0, 1, 5)
        }

        for (i in 0 until segA) {
            if (best == 0.0) break
            val m = min(i + 1, ax.size - 1)
            val x0 = ax[i]; val y0 = ay[i]; val x1 = ax[m]; val y1 = ay[m]
            val reach = best
            tree.search(min(x0, x1) - reach, min(y0, y1) - reach, max(x0, x1) + reach, max(y0, y1) + reach) { j ->
                val k = min(j + 1, bx.size - 1)
                val d = segmentGap(x0, y0, x1, y1, bx[j], by[j], bx[k], by[k], probe)
                if (d < best) {
                    best = d
                    bestA = i
                    bestB = j
                    probe.copyInto(bestPoints, 0, 1, 5)
                }
                best > 0.0
            }
        }

        val onA = frame.toLatLon(bestPoints[0], bestPoints[1])
        val onB = frame.toLatLon(bestPoints[2], bestPoints[3])
        val metres = if (best == 0.0) 0.0 else Geo.distance(onA, onB)
        return LineGap(metres, onA, if (best == 0.0) onA else onB, bestA, bestB)
    }

    /**
     * Distance between segments p0–p1 and q0–q1; writes it and the nearest points into [out] as
     * distance, px, py, qx, qy.
     */
    fun segmentGap(
        p0x: Double, p0y: Double, p1x: Double, p1y: Double,
        q0x: Double, q0y: Double, q1x: Double, q1y: Double,
        out: DoubleArray,
    ): Double {
        val rx = p1x - p0x; val ry = p1y - p0y
        val sx = q1x - q0x; val sy = q1y - q0y
        val denom = rx * sy - ry * sx
        if (denom != 0.0) {
            val t = ((q0x - p0x) * sy - (q0y - p0y) * sx) / denom
            val u = ((q0x - p0x) * ry - (q0y - p0y) * rx) / denom
            if (t in 0.0..1.0 && u in 0.0..1.0) {
                val x = p0x + t * rx
                val y = p0y + t * ry
                out[0] = 0.0; out[1] = x; out[2] = y; out[3] = x; out[4] = y
                return 0.0
            }
        }
        // Otherwise the nearest pair has an endpoint on one side.
        var best = Double.POSITIVE_INFINITY
        best = endpoint(p0x, p0y, q0x, q0y, q1x, q1y, false, best, out)
        best = endpoint(p1x, p1y, q0x, q0y, q1x, q1y, false, best, out)
        best = endpoint(q0x, q0y, p0x, p0y, p1x, p1y, true, best, out)
        best = endpoint(q1x, q1y, p0x, p0y, p1x, p1y, true, best, out)
        out[0] = best
        return best
    }

    private fun endpoint(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double,
        swapped: Boolean, best: Double, out: DoubleArray,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx
        val qy = ay + t * dy
        val d = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
        if (d >= best) return best
        if (swapped) {
            out[1] = qx; out[2] = qy; out[3] = px; out[4] = py
        } else {
            out[1] = px; out[2] = py; out[3] = qx; out[4] = qy
        }
        return d
    }
}
