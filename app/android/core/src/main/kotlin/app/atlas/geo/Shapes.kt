package app.atlas.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polygon and line algorithms.
 *
 * Flat forms take `coords` as x, y pairs (lon/lat, Mercator units or metres) and, for polygons, `holeIndices` as
 * Earcut does: the vertex index where each hole ring starts. Rings may be open or closed (first point repeated);
 * rings returned are open. [LatLon] forms take a polygon as rings, outer first.
 */
object Shapes {

    // Area

    /** Shoelace area of the ring between vertices [from] and [to] (exclusive): positive when counter-clockwise, y up. */
    fun signedArea(coords: DoubleArray, from: Int = 0, to: Int = coords.size / 2): Double {
        if (to - from < 3) return 0.0
        val ox = coords[2 * from]
        val oy = coords[2 * from + 1]
        var sum = 0.0
        var j = to - 1
        for (i in from until to) {
            sum += (coords[2 * j] - ox) * (coords[2 * i + 1] - oy) - (coords[2 * i] - ox) * (coords[2 * j + 1] - oy)
            j = i
        }
        return sum / 2
    }

    /** Shoelace area of a ring in square degrees, lon as x: positive when counter-clockwise (east right, north up). */
    @JvmName("signedAreaOfRing")
    fun signedArea(ring: List<LatLon>): Double {
        if (ring.size < 3) return 0.0
        val o = ring[0]
        var sum = 0.0
        var prev = ring[ring.size - 1]
        for (p in ring) {
            sum += (prev.lon - o.lon) * (p.lat - o.lat) - (p.lon - o.lon) * (prev.lat - o.lat)
            prev = p
        }
        return sum / 2
    }

    /**
     * Area of a ring on the sphere in m²: the shoelace in the equal-area cylindrical projection
     * (Chamberlain & Duquette), exact for parallels and meridians and within a fraction of a percent of the ellipsoid.
     */
    @JvmName("geodesicAreaOfRing")
    fun geodesicArea(ring: List<LatLon>): Double {
        var n = ring.size
        if (n > 1 && ring[0] == ring[n - 1]) n--
        if (n < 3) return 0.0
        var total = 0.0
        for (i in 0 until n) {
            val lower = ring[i]
            val middle = ring[(i + 1) % n]
            val upper = ring[(i + 2) % n]
            total += (upper.lon - lower.lon) * RAD * sin(middle.lat * RAD)
        }
        return abs(total * Geo.EARTH_RADIUS * Geo.EARTH_RADIUS / 2)
    }

    /** Area of a polygon (outer ring, then holes) on the sphere in m². */
    fun geodesicArea(polygon: List<List<LatLon>>): Double {
        if (polygon.isEmpty()) return 0.0
        var a = geodesicArea(polygon[0])
        for (i in 1 until polygon.size) a -= geodesicArea(polygon[i])
        return max(a, 0.0)
    }

    // Centroid

    /**
     * The area-weighted centroid of a polygon with holes, as [x, y]. A polygon with no area falls back to the
     * length-weighted centre of its outline, and a single point to itself. Returns null for no vertices.
     */
    fun centroid(coords: DoubleArray, holeIndices: IntArray? = null): DoubleArray? {
        val n = coords.size / 2
        if (n == 0) return null
        val ox = coords[0]
        val oy = coords[1]
        var w = 0.0
        var cx = 0.0
        var cy = 0.0
        forEachRing(n, holeIndices) { ring, from, to ->
            var a = 0.0
            var mx = 0.0
            var my = 0.0
            var j = to - 1
            for (i in from until to) {
                val xi = coords[2 * i] - ox; val yi = coords[2 * i + 1] - oy
                val xj = coords[2 * j] - ox; val yj = coords[2 * j + 1] - oy
                val f = xj * yi - xi * yj
                a += f
                mx += (xi + xj) * f
                my += (yi + yj) * f
                j = i
            }
            if (a != 0.0) {
                // Outer rings add, holes subtract, whatever their winding.
                val weight = if (ring == 0) abs(a) else -abs(a)
                cx += weight * mx / (3 * a)
                cy += weight * my / (3 * a)
                w += weight
            }
        }
        if (w > 0 && abs(w) > 1e-12 * boxArea(coords, n)) return doubleArrayOf(ox + cx / w, oy + cy / w)

        // No area: the middle of the outline by length, or the mean of coincident points.
        val outerEnd = if (holeIndices != null && holeIndices.isNotEmpty()) holeIndices[0] else n
        var len = 0.0
        var lx = 0.0
        var ly = 0.0
        for (i in 1 until outerEnd) {
            val x0 = coords[2 * i - 2]; val y0 = coords[2 * i - 1]
            val x1 = coords[2 * i]; val y1 = coords[2 * i + 1]
            val d = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            len += d
            lx += d * (x0 + x1) / 2
            ly += d * (y0 + y1) / 2
        }
        if (len > 0) return doubleArrayOf(lx / len, ly / len)
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until outerEnd) { sx += coords[2 * i]; sy += coords[2 * i + 1] }
        return doubleArrayOf(sx / outerEnd, sy / outerEnd)
    }

    /** The centroid of a polygon given as rings, outer first. */
    fun centroid(polygon: List<List<LatLon>>): LatLon? {
        val flat = flatten(polygon) ?: return null
        val c = centroid(flat.first, flat.second) ?: return null
        return LatLon(c[1], c[0])
    }

    /** The centroid of a single ring. */
    @JvmName("centroidOfRing")
    fun centroid(ring: List<LatLon>): LatLon? = centroid(listOf(ring))

    // Containment

    /** Whether (x, y) lies inside the polygon (even-odd over all rings); a point exactly on an edge counts as inside. */
    fun contains(coords: DoubleArray, holeIndices: IntArray?, x: Double, y: Double): Boolean {
        val n = coords.size / 2
        var inside = false
        var boundary = false
        forEachRing(n, holeIndices) { _, from, to ->
            if (boundary) return@forEachRing
            var j = to - 1
            for (i in from until to) {
                val xi = coords[2 * i]; val yi = coords[2 * i + 1]
                val xj = coords[2 * j]; val yj = coords[2 * j + 1]
                if (onSegment(x, y, xi, yi, xj, yj)) { boundary = true; return@forEachRing }
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return boundary || inside
    }

    /** Whether [p] lies inside the polygon given as rings, outer first (holes excluded). */
    fun contains(polygon: List<List<LatLon>>, p: LatLon): Boolean {
        val flat = flatten(polygon) ?: return false
        return contains(flat.first, flat.second, p.lon, p.lat)
    }

    /** Whether [p] lies inside a single ring. */
    @JvmName("containsInRing")
    fun contains(ring: List<LatLon>, p: LatLon): Boolean = contains(listOf(ring), p)

    // Simplification

    /** Douglas–Peucker: indices of the vertices to keep, ascending, so every dropped vertex lies within [tolerance]. */
    fun simplifyIndices(coords: DoubleArray, tolerance: Double): IntArray {
        val n = coords.size / 2
        if (n <= 2 || tolerance <= 0) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val sqTolerance = tolerance * tolerance
        val stack = IntBuffer()
        stack.add(0)
        stack.add(n - 1)
        while (stack.size > 0) {
            val last = stack.pop()
            val first = stack.pop()
            var maxSq = sqTolerance
            var index = -1
            for (i in first + 1 until last) {
                val d = sqSegmentDistance(
                    coords[2 * i], coords[2 * i + 1],
                    coords[2 * first], coords[2 * first + 1], coords[2 * last], coords[2 * last + 1],
                )
                if (d > maxSq) { index = i; maxSq = d }
            }
            if (index >= 0) {
                keep[index] = true
                if (index - first > 1) { stack.add(first); stack.add(index) }
                if (last - index > 1) { stack.add(index); stack.add(last) }
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val out = IntArray(count)
        var o = 0
        for (i in 0 until n) if (keep[i]) out[o++] = i
        return out
    }

    /** Douglas–Peucker simplification of a line given as x, y pairs. */
    fun simplify(coords: DoubleArray, tolerance: Double): DoubleArray {
        val keep = simplifyIndices(coords, tolerance)
        val out = DoubleArray(keep.size * 2)
        for (k in keep.indices) {
            out[2 * k] = coords[2 * keep[k]]
            out[2 * k + 1] = coords[2 * keep[k] + 1]
        }
        return out
    }

    /** Douglas–Peucker simplification of a line in WGS84, with the tolerance in [metres]. Keeps the original points. */
    fun simplify(points: List<LatLon>, metres: Double): List<LatLon> {
        if (points.size <= 2) return points
        val frame = LocalFrame(BBox.of(points)!!.center)
        val xy = DoubleArray(points.size * 2)
        for (i in points.indices) {
            xy[2 * i] = frame.x(points[i].lon)
            xy[2 * i + 1] = frame.y(points[i].lat)
        }
        val keep = simplifyIndices(xy, metres)
        return List(keep.size) { points[keep[it]] }
    }

    // Clipping

    /**
     * Sutherland–Hodgman: the part of a ring inside the rectangle, as an open ring (empty when nothing is inside).
     * Parts joined along the rectangle's edges come back as one ring with zero-width bridges, which fill correctly.
     */
    fun clipRing(coords: DoubleArray, minX: Double, minY: Double, maxX: Double, maxY: Double): DoubleArray {
        var n = coords.size / 2
        if (n > 1 && coords[0] == coords[2 * n - 2] && coords[1] == coords[2 * n - 1]) n--
        if (n == 0) return DoubleArray(0)
        var src = coords.copyOf(n * 2)
        var dst = DoubleArray(n * 4 + 8)
        for (edge in 0 until 4) {
            if (n == 0) break
            var m = 0
            var px = src[2 * n - 2]
            var py = src[2 * n - 1]
            var pIn = insideEdge(edge, px, py, minX, minY, maxX, maxY)
            for (i in 0 until n) {
                val cx = src[2 * i]
                val cy = src[2 * i + 1]
                val cIn = insideEdge(edge, cx, cy, minX, minY, maxX, maxY)
                if (cIn != pIn) {
                    if (dst.size < 2 * m + 4) dst = dst.copyOf(dst.size * 2)
                    val c = if (edge == 0) minX else if (edge == 1) maxX else if (edge == 2) minY else maxY
                    if (edge < 2) {
                        dst[2 * m] = c
                        dst[2 * m + 1] = py + (cy - py) * (c - px) / (cx - px)
                    } else {
                        dst[2 * m] = px + (cx - px) * (c - py) / (cy - py)
                        dst[2 * m + 1] = c
                    }
                    m++
                }
                if (cIn) {
                    if (dst.size < 2 * m + 4) dst = dst.copyOf(dst.size * 2)
                    dst[2 * m] = cx
                    dst[2 * m + 1] = cy
                    m++
                }
                px = cx; py = cy; pIn = cIn
            }
            val t = src; src = dst; dst = if (t.size >= 2 * m + 8) t else DoubleArray(2 * m + 8)
            n = m
        }
        return src.copyOf(n * 2)
    }

    /**
     * Liang–Barsky: clips the segment (x0, y0)–(x1, y1) to the rectangle. Writes the clipped ends into [out]
     * (x0, y0, x1, y1) and returns true, or returns false when the segment misses the rectangle.
     */
    fun clipSegment(
        x0: Double, y0: Double, x1: Double, y1: Double,
        minX: Double, minY: Double, maxX: Double, maxY: Double,
        out: DoubleArray,
    ): Boolean {
        // The parameters land in out[0..1] first, so a call allocates nothing.
        if (!liangBarsky(x0, y0, x1, y1, minX, minY, maxX, maxY, out)) return false
        val t0 = out[0]
        val t1 = out[1]
        val dx = x1 - x0
        val dy = y1 - y0
        out[0] = if (t0 == 0.0) x0 else x0 + t0 * dx
        out[1] = if (t0 == 0.0) y0 else y0 + t0 * dy
        out[2] = if (t1 == 1.0) x1 else x0 + t1 * dx
        out[3] = if (t1 == 1.0) y1 else y0 + t1 * dy
        return true
    }

    /** The parts of a line (x, y pairs) inside the rectangle, each as its own line, in order; a mere touch is no part. */
    fun clipLine(coords: DoubleArray, minX: Double, minY: Double, maxX: Double, maxY: Double): List<DoubleArray> {
        val n = coords.size / 2
        val parts = ArrayList<DoubleArray>()
        if (n == 1) {
            val x = coords[0]; val y = coords[1]
            if (x in minX..maxX && y in minY..maxY) parts += coords.copyOf()
            return parts
        }
        val t = DoubleArray(2)
        var part = DoubleArray(16)
        var m = 0
        fun put(x: Double, y: Double) {
            if (part.size < 2 * m + 2) part = part.copyOf(part.size * 2)
            part[2 * m] = x
            part[2 * m + 1] = y
            m++
        }
        fun finishPart() {
            if (m >= 2) parts += part.copyOf(2 * m)
            m = 0
        }
        for (i in 0 until n - 1) {
            val x0 = coords[2 * i]; val y0 = coords[2 * i + 1]
            val x1 = coords[2 * i + 2]; val y1 = coords[2 * i + 3]
            if (!liangBarsky(x0, y0, x1, y1, minX, minY, maxX, maxY, t)) { finishPart(); continue }
            // A segment that only touches the box (at a corner, or at an end on its edge) adds no length: a part
            // reaching that point already holds it, and a lone touch is no part.
            if (t[1] <= t[0] && (x1 != x0 || y1 != y0)) { finishPart(); continue }
            if (m == 0 || t[0] > 0.0) {
                finishPart()
                if (t[0] == 0.0) put(x0, y0) else put(x0 + t[0] * (x1 - x0), y0 + t[0] * (y1 - y0))
            }
            if (t[1] == 1.0) put(x1, y1) else put(x0 + t[1] * (x1 - x0), y0 + t[1] * (y1 - y0))
            if (t[1] < 1.0) finishPart()
        }
        finishPart()
        return parts
    }

    // Convex hull

    /** The convex hull (Andrew's monotone chain), counter-clockwise from the lowest-x point, collinear points dropped. */
    fun convexHull(coords: DoubleArray): DoubleArray {
        val n = coords.size / 2
        if (n < 3) return coords.copyOf()
        val order = IntArray(n) { it }
        sortByXY(order, coords)
        val hull = IntArray(2 * n)
        var k = 0
        for (i in 0 until n) {
            val p = order[i]
            while (k >= 2 && cross(coords, hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        val lower = k + 1
        for (i in n - 2 downTo 0) {
            val p = order[i]
            while (k >= lower && cross(coords, hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        k-- // the last point repeats the first
        val out = DoubleArray(2 * k)
        for (i in 0 until k) {
            out[2 * i] = coords[2 * hull[i]]
            out[2 * i + 1] = coords[2 * hull[i] + 1]
        }
        return out
    }

    /** The convex hull of points in WGS84 (planar in degrees, which keeps hull membership for small areas). */
    fun convexHull(points: List<LatLon>): List<LatLon> {
        val xy = DoubleArray(points.size * 2)
        for (i in points.indices) { xy[2 * i] = points[i].lon; xy[2 * i + 1] = points[i].lat }
        val h = convexHull(xy)
        return List(h.size / 2) { LatLon(h[2 * it + 1], h[2 * it]) }
    }

    // Corridors and line distances

    /**
     * The outline of everything within [metres] of [line]: a buffer polygon for "places along my route", as one open
     * ring, counter-clockwise. A line that loops around an area leaves holes; [corridorRings] returns them too.
     */
    fun corridor(line: Polyline, metres: Double): List<LatLon> = Corridor.rings(line, metres).firstOrNull() ?: emptyList()

    /** The corridor as rings: the outline first (counter-clockwise), then any holes (clockwise). */
    fun corridorRings(line: Polyline, metres: Double): List<List<LatLon>> = Corridor.rings(line, metres)

    /** The nearest approach between two lines, or null if either has no points. */
    fun nearest(a: Polyline, b: Polyline): LineGap? = LineDistance.nearest(a, b)

    /** Metres between the nearest points of two lines: 0 where they cross or touch. */
    fun distance(a: Polyline, b: Polyline): Double = nearest(a, b)?.distance ?: Double.POSITIVE_INFINITY

    // Labels

    /**
     * The pole of inaccessibility (Mapbox polylabel): the point inside the polygon farthest from its edges, found to
     * within [precision]. Returns x, y and that distance. Best for label and symbol placement; give it metres.
     */
    fun labelPoint(coords: DoubleArray, holeIndices: IntArray? = null, precision: Double = 1.0): DoubleArray =
        Polylabel.find(coords, holeIndices, precision)

    // Helpers shared with the other geometry files.

    internal const val RAD = PI / 180

    /** Calls [block] with each non-empty ring's number and vertex range [from, to). */
    internal inline fun forEachRing(n: Int, holeIndices: IntArray?, block: (ring: Int, from: Int, to: Int) -> Unit) {
        val rings = (holeIndices?.size ?: 0) + 1
        for (r in 0 until rings) {
            val from = if (r == 0) 0 else holeIndices!![r - 1]
            val to = if (r < rings - 1) holeIndices!![r] else n
            if (to > from) block(r, from, to)
        }
    }

    internal fun sqSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        var x = ax
        var y = ay
        var dx = bx - x
        var dy = by - y
        if (dx != 0.0 || dy != 0.0) {
            val t = ((px - x) * dx + (py - y) * dy) / (dx * dx + dy * dy)
            if (t > 1) { x = bx; y = by } else if (t > 0) { x += dx * t; y += dy * t }
        }
        dx = px - x
        dy = py - y
        return dx * dx + dy * dy
    }

    private fun onSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        if (px < min(ax, bx) || px > max(ax, bx) || py < min(ay, by) || py > max(ay, by)) return false
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax) == 0.0
    }

    private fun insideEdge(edge: Int, x: Double, y: Double, minX: Double, minY: Double, maxX: Double, maxY: Double) =
        when (edge) {
            0 -> x >= minX
            1 -> x <= maxX
            2 -> y >= minY
            else -> y <= maxY
        }

    // Parameters t0, t1 of the part of the segment inside the rectangle; false when there is none.
    private fun liangBarsky(
        x0: Double, y0: Double, x1: Double, y1: Double,
        minX: Double, minY: Double, maxX: Double, maxY: Double,
        t: DoubleArray,
    ): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        var t0 = 0.0
        var t1 = 1.0
        for (k in 0 until 4) {
            val p = when (k) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
            val q = when (k) { 0 -> x0 - minX; 1 -> maxX - x0; 2 -> y0 - minY; else -> maxY - y0 }
            if (p == 0.0) {
                if (q < 0) return false
            } else {
                val r = q / p
                if (p < 0) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        t[0] = t0
        t[1] = t1
        return true
    }

    private fun cross(c: DoubleArray, o: Int, a: Int, b: Int): Double =
        (c[2 * a] - c[2 * o]) * (c[2 * b + 1] - c[2 * o + 1]) - (c[2 * a + 1] - c[2 * o + 1]) * (c[2 * b] - c[2 * o])

    // A stable merge sort of vertex indices by x, then y, without boxing.
    private fun sortByXY(order: IntArray, c: DoubleArray) {
        val n = order.size
        var src = order
        var dst = IntArray(n)
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = min(lo + width, n)
                val hi = min(lo + 2 * width, n)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) {
                    val a = src[i]
                    val b = src[j]
                    val bFirst = c[2 * b] < c[2 * a] || (c[2 * b] == c[2 * a] && c[2 * b + 1] < c[2 * a + 1])
                    dst[k++] = if (bFirst) src[j++] else src[i++]
                }
                while (i < mid) dst[k++] = src[i++]
                while (j < hi) dst[k++] = src[j++]
                lo += 2 * width
            }
            val t = src; src = dst; dst = t
            width *= 2
        }
        if (src !== order) src.copyInto(order)
    }

    private fun boxArea(coords: DoubleArray, n: Int): Double {
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            x0 = min(x0, coords[2 * i]); x1 = max(x1, coords[2 * i])
            y0 = min(y0, coords[2 * i + 1]); y1 = max(y1, coords[2 * i + 1])
        }
        return (x1 - x0) * (y1 - y0)
    }

    // Rings of LatLon to Earcut's flat form (lon, lat) with hole indices.
    private fun flatten(polygon: List<List<LatLon>>): Pair<DoubleArray, IntArray>? {
        var total = 0
        for (r in polygon) total += r.size
        if (total == 0) return null
        val xy = DoubleArray(total * 2)
        val holes = IntArray(max(polygon.size - 1, 0))
        var v = 0
        var h = 0
        for ((ri, ring) in polygon.withIndex()) {
            if (ri > 0) holes[h++] = v
            for (p in ring) { xy[2 * v] = p.lon; xy[2 * v + 1] = p.lat; v++ }
        }
        return xy to holes.copyOf(h)
    }
}

/**
 * Where two lines come nearest: the distance in metres, the point on each line and the segment index on each.
 * Distance 0 means they cross or touch at [onA].
 */
data class LineGap(val distance: Double, val onA: LatLon, val onB: LatLon, val segmentA: Int, val segmentB: Int)
