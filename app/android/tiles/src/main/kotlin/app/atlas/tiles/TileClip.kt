package app.atlas.tiles

import kotlin.math.roundToInt

/**
 * The part of this geometry inside the rectangle [x0]..[x1] × [y0]..[y1] (tile units): points outside are dropped,
 * lines are cut (one line may become several), polygon rings are clipped with Sutherland–Hodgman and keep their
 * winding, so exteriors and holes stay grouped. A polygon whose exterior falls outside goes with its holes.
 *
 * Use it with [TileTransform.clip] to draw only the overzoomed part of an ancestor tile.
 */
fun TileGeometry.clip(x0: Int, y0: Int, x1: Int, y1: Int): TileGeometry {
    require(x0 <= x1 && y0 <= y1) { "empty rectangle" }
    if (isEmpty) return this
    val b = bounds()!!
    if (b[0] >= x0 && b[1] >= y0 && b[2] <= x1 && b[3] <= y1) return this
    if (b[2] < x0 || b[0] > x1 || b[3] < y0 || b[1] > y1) return TileGeometry.empty(type)
    return when (type) {
        GeomType.POINT -> clipPoints(this, x0, y0, x1, y1)
        GeomType.LINE -> clipLines(this, x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
        GeomType.POLYGON -> clipPolygons(this, x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
    }
}

/** The target's square in source tile units, grown by [buffer] target units on every side, as integers for [clip]. */
fun TileTransform.clipRect(extent: Int, buffer: Int = 0): IntArray {
    val c = clip(extent)
    val pad = buffer / scale
    return intArrayOf(
        kotlin.math.floor(c[0] - pad).toInt(), kotlin.math.floor(c[1] - pad).toInt(),
        kotlin.math.ceil(c[2] + pad).toInt(), kotlin.math.ceil(c[3] + pad).toInt(),
    )
}

/** Growable flat output for clipped geometry. */
private class Out(capacity: Int) {
    var coords = IntArray(maxOf(capacity, 8))
    var n = 0
    var parts = IntArray(16)
    var nParts = 0
    var polygons = IntArray(8)
    var nPolygons = 0

    fun point(x: Int, y: Int) {
        if (n + 2 > coords.size) coords = coords.copyOf(coords.size * 2)
        coords[n++] = x
        coords[n++] = y
    }

    /** Adds a point unless it repeats the last point of the open part starting at [partStart]. */
    fun pointDistinct(x: Int, y: Int, partStart: Int) {
        if (n - partStart >= 2 && coords[n - 2] == x && coords[n - 1] == y) return
        point(x, y)
    }

    fun startPart(): Int {
        if (nParts + 2 > parts.size) parts = parts.copyOf(parts.size * 2)
        parts[nParts] = n / 2
        return n
    }

    fun endPart() { nParts++ }

    fun startPolygon() {
        if (nPolygons + 2 > polygons.size) polygons = polygons.copyOf(polygons.size * 2)
        polygons[nPolygons++] = nParts
    }

    fun build(type: GeomType): TileGeometry {
        if (nParts == 0) return TileGeometry.empty(type)
        val p = parts.copyOf(nParts + 1)
        p[nParts] = n / 2
        val polys = if (type == GeomType.POLYGON) polygons.copyOf(nPolygons + 1).also { it[nPolygons] = nParts } else IntArray(0)
        return TileGeometry(type, coords.copyOf(n), p, polys)
    }
}

private fun clipPoints(g: TileGeometry, x0: Int, y0: Int, x1: Int, y1: Int): TileGeometry {
    val out = Out(g.coords.size)
    g.forEachPoint { x, y -> if (x in x0..x1 && y in y0..y1) out.point(x, y) }
    if (out.n == 0) return TileGeometry.empty(GeomType.POINT)
    return TileGeometry(GeomType.POINT, out.coords.copyOf(out.n), intArrayOf(0, out.n / 2), IntArray(0))
}

private fun clipLines(g: TileGeometry, x0: Double, y0: Double, x1: Double, y1: Double): TileGeometry {
    val out = Out(g.coords.size)
    val seg = DoubleArray(4)
    g.forEachPart { _, start, end ->
        var open = -1 // coords offset of the open output part, or -1
        for (i in start until end - 1) {
            val ax = g.x(i).toDouble(); val ay = g.y(i).toDouble()
            val bx = g.x(i + 1).toDouble(); val by = g.y(i + 1).toDouble()
            if (!liangBarsky(ax, ay, bx, by, x0, y0, x1, y1, seg)) {
                if (open >= 0) { closeLine(out, open); open = -1 }
                continue
            }
            val sx = seg[0].roundToInt(); val sy = seg[1].roundToInt()
            val ex = seg[2].roundToInt(); val ey = seg[3].roundToInt()
            if (open < 0) { open = out.startPart(); out.point(sx, sy) }
            out.pointDistinct(ex, ey, open)
            // The segment left the rectangle: the line breaks here.
            if (seg[2] != bx || seg[3] != by) { closeLine(out, open); open = -1 }
        }
        if (open >= 0) closeLine(out, open)
    }
    return out.build(GeomType.LINE)
}

private fun closeLine(out: Out, start: Int) {
    if (out.n - start >= 4) out.endPart() else out.n = start
}

/** Clips segment a–b to the rectangle; writes the visible part to [seg] and returns false when none is. */
private fun liangBarsky(ax: Double, ay: Double, bx: Double, by: Double, x0: Double, y0: Double, x1: Double, y1: Double, seg: DoubleArray): Boolean {
    val dx = bx - ax
    val dy = by - ay
    var t0 = 0.0
    var t1 = 1.0
    for (k in 0 until 4) {
        val p = when (k) { 0 -> -dx; 1 -> dx; 2 -> -dy; else -> dy }
        val q = when (k) { 0 -> ax - x0; 1 -> x1 - ax; 2 -> ay - y0; else -> y1 - ay }
        if (p == 0.0) {
            if (q < 0) return false
        } else {
            val r = q / p
            if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r }
            else { if (r < t0) return false; if (r < t1) t1 = r }
        }
    }
    seg[0] = if (t0 == 0.0) ax else ax + t0 * dx
    seg[1] = if (t0 == 0.0) ay else ay + t0 * dy
    seg[2] = if (t1 == 1.0) bx else ax + t1 * dx
    seg[3] = if (t1 == 1.0) by else ay + t1 * dy
    return true
}

private fun clipPolygons(g: TileGeometry, x0: Double, y0: Double, x1: Double, y1: Double): TileGeometry {
    val out = Out(g.coords.size)
    var a = DoubleArray(64)
    var b = DoubleArray(64)
    g.forEachPolygon { _, first, endPart ->
        for (p in first until endPart) {
            // The ring without its closing point, as doubles.
            val s = g.partStart(p)
            var n = g.partSize(p) - 1
            if (a.size < 2 * n + 8) { a = DoubleArray(4 * n + 16); b = DoubleArray(4 * n + 16) }
            for (k in 0 until n) { a[2 * k] = g.x(s + k).toDouble(); a[2 * k + 1] = g.y(s + k).toDouble() }
            for (edge in 0 until 4) {
                if (b.size < 4 * n + 8) b = DoubleArray(4 * n + 16)
                n = clipEdge(a, n, b, edge, when (edge) { 0 -> x0; 1 -> x1; 2 -> y0; else -> y1 })
                val t = a; a = b; b = t
                if (n == 0) break
            }
            if (n < 3) { if (p == first) break else continue }
            val start = out.n
            val partStart = out.startPart()
            for (k in 0 until n) out.pointDistinct(a[2 * k].roundToInt(), a[2 * k + 1].roundToInt(), partStart)
            if (out.n - start >= 4 && out.coords[out.n - 2] == out.coords[start] && out.coords[out.n - 1] == out.coords[start + 1]) out.n -= 2
            var area = 0L
            val c = out.coords
            val pts = (out.n - start) / 2
            for (k in 0 until pts) {
                val i = start + 2 * k
                val j = start + 2 * ((k + 1) % pts)
                area += c[i].toLong() * c[j + 1] - c[j].toLong() * c[i + 1]
            }
            if (pts < 3 || area == 0L) {
                out.n = start
                if (p == first) break else continue
            }
            out.point(c[start], c[start + 1])
            if (p == first) out.startPolygon()
            out.endPart()
        }
    }
    return out.build(GeomType.POLYGON)
}

/** One Sutherland–Hodgman pass of [n] points in [src] against one edge of the rectangle; returns the points written. */
private fun clipEdge(src: DoubleArray, n: Int, dst: DoubleArray, edge: Int, v: Double): Int {
    fun inside(x: Double, y: Double) = when (edge) { 0 -> x >= v; 1 -> x <= v; 2 -> y >= v; else -> y <= v }
    var m = 0
    var px = src[2 * (n - 1)]
    var py = src[2 * (n - 1) + 1]
    var pIn = inside(px, py)
    for (k in 0 until n) {
        val cx = src[2 * k]
        val cy = src[2 * k + 1]
        val cIn = inside(cx, cy)
        if (cIn != pIn) {
            val t = if (edge < 2) (v - px) / (cx - px) else (v - py) / (cy - py)
            dst[2 * m] = if (edge < 2) v else px + t * (cx - px)
            dst[2 * m + 1] = if (edge < 2) py + t * (cy - py) else v
            m++
        }
        if (cIn) {
            dst[2 * m] = cx
            dst[2 * m + 1] = cy
            m++
        }
        px = cx; py = cy; pIn = cIn
    }
    return m
}
