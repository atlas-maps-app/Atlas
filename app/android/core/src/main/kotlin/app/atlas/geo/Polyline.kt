package app.atlas.geo

/**
 * A line of points with its cumulative length: what a route, a track or a way is.
 * Snapping and walking along it use a local flat frame, so it is meant for lines up to a few hundred kilometres per call.
 */
class Polyline(val points: List<LatLon>) {
    /** Metres from the first point to each point. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()
    val bbox: BBox? by lazy { BBox.of(points) }

    /** The point [metres] along the line, clamped to its ends. */
    fun pointAt(metres: Double): LatLon {
        if (points.size < 2) return points.first()
        val d = metres.coerceIn(0.0, length)
        val i = segmentAt(d)
        val seg = cumulative[i + 1] - cumulative[i]
        val t = if (seg <= 0) 0.0 else (d - cumulative[i]) / seg
        return Geo.interpolate(points[i], points[i + 1], t)
    }

    /** Bearing of the line [metres] along it. */
    fun bearingAt(metres: Double): Double {
        if (points.size < 2) return 0.0
        val i = segmentAt(metres.coerceIn(0.0, length))
        return Geo.bearing(points[i], points[i + 1])
    }

    /** Index i of the segment points[i]..points[i+1] that holds [metres]. */
    fun segmentAt(metres: Double): Int {
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= metres) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    /**
     * The nearest point of the line to [p], searching only segments from [fromSegment] to [toSegment]
     * (the guide passes a window around the last match so a route that doubles back is not mistaken).
     */
    fun snap(p: LatLon, fromSegment: Int = 0, toSegment: Int = points.size - 2): Snap? {
        if (points.size < 2) return null
        val f = LocalFrame(p)
        var best: Snap? = null
        val a = fromSegment.coerceIn(0, points.size - 2)
        val b = toSegment.coerceIn(a, points.size - 2)
        for (i in a..b) {
            val ax = f.x(points[i].lon); val ay = f.y(points[i].lat)
            val bx = f.x(points[i + 1].lon); val by = f.y(points[i + 1].lat)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val qx = ax + dx * tc; val qy = ay + dy * tc
            val dist = kotlin.math.sqrt(qx * qx + qy * qy)
            if (best == null || dist < best.offset) {
                val segLen = cumulative[i + 1] - cumulative[i]
                // Positive offset: p lies to the right of the line's direction.
                val side = dx * (-ay) - dy * (-ax)
                best = Snap(i, tc, cumulative[i] + segLen * tc, dist, if (side < 0) 1 else -1, f.toLatLon(qx, qy))
            }
        }
        return best
    }

    /** The part of the line between two distances along it. */
    fun slice(from: Double, to: Double): Polyline {
        val a = from.coerceIn(0.0, length); val b = to.coerceIn(a, length)
        val out = ArrayList<LatLon>()
        out += pointAt(a)
        for (i in points.indices) if (cumulative[i] > a && cumulative[i] < b) out += points[i]
        out += pointAt(b)
        return Polyline(out)
    }
}

/**
 * Where a point meets a line: segment index, fraction along it, metres from the start ([along]),
 * metres away ([offset]), which side (+1 right, −1 left of the line's direction) and the nearest point itself.
 */
data class Snap(val segment: Int, val t: Double, val along: Double, val offset: Double, val side: Int, val point: LatLon)
