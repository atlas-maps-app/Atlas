package app.atlas.rendertiles.compile

import java.math.BigInteger

/**
 * Rings clipped at a tile's edges on the grid (Sutherland–Hodgman against the four edges of the tile's grid box), for
 * the water, which the renders only fill and clip by. A crossing lies on the edge's line exactly; its other coordinate
 * is the exact rational crossing rounded to the nearest grid integer (ties upward), computed in integers, so every
 * platform cuts alike, and the pieces of neighbouring tiles share their cut, because both compute the same crossing on
 * the shared edge. The pieces of a ring therefore fill what the ring fills up to that rounding: each cut moves by at
 * most half a grid unit along the edge (about 0.56 cm north–south at the canal ring). A ring that leaves the tile and
 * comes back is joined along the edge by a zero-width bridge, which fills nothing (the vector-tile convention earcut
 * takes).
 *
 * The renders' tests on a ring (`area(sp) > 0.2`, its winding) apply to the whole ring, not to a piece: a reader adds
 * the signed areas of the pieces that share the ring's `index`.
 */
object Clip {
    /** A clipped ring: grid latitudes and longitudes, and which vertices are crossings made by the cut. */
    class Piece(val lat: IntArray, val lon: IntArray, val cut: BooleanArray) {
        val size: Int get() = lat.size
    }

    /**
     * [lat], [lon] (a closed ring, its first vertex not repeated) clipped to the box [south, west, north, east]
     * (inclusive), or null when nothing of it is left with an area.
     */
    fun ring(lat: IntArray, lon: IntArray, box: IntArray): Piece? {
        require(lat.size == lon.size)
        var pts = ArrayList<P>(lat.size)
        for (i in lat.indices) pts.add(P(lat[i].toLong(), lon[i].toLong(), false))
        // west (lon >= w), east (lon <= e), south (lat >= s), north (lat <= n)
        pts = clip(pts, Edge.WEST, box[1].toLong())
        pts = clip(pts, Edge.EAST, box[3].toLong())
        pts = clip(pts, Edge.SOUTH, box[0].toLong())
        pts = clip(pts, Edge.NORTH, box[2].toLong())
        // consecutive repeats (a vertex on an edge, a crossing at a vertex) and the closing repeat go
        val out = ArrayList<P>(pts.size)
        for (p in pts) if (out.isEmpty() || out.last().lat != p.lat || out.last().lon != p.lon) out.add(p) else if (p.cut) out[out.size - 1] = out.last().copy(cut = true)
        while (out.size > 1 && out.first().lat == out.last().lat && out.first().lon == out.last().lon) {
            val l = out.removeAt(out.size - 1)
            if (l.cut) out[0] = out[0].copy(cut = true)
        }
        if (out.size < 3 || area2(out) == BigInteger.ZERO) return null
        return Piece(IntArray(out.size) { out[it].lat.toInt() }, IntArray(out.size) { out[it].lon.toInt() }, BooleanArray(out.size) { out[it].cut })
    }

    /** True when the ring's box lies inside [box]: nothing to cut. */
    fun inside(lat: IntArray, lon: IntArray, box: IntArray): Boolean {
        for (i in lat.indices) if (lat[i] < box[0] || lat[i] > box[2] || lon[i] < box[1] || lon[i] > box[3]) return false
        return true
    }

    private data class P(val lat: Long, val lon: Long, val cut: Boolean)

    private enum class Edge { WEST, EAST, SOUTH, NORTH }

    private fun inside(p: P, e: Edge, c: Long): Boolean = when (e) {
        Edge.WEST -> p.lon >= c
        Edge.EAST -> p.lon <= c
        Edge.SOUTH -> p.lat >= c
        Edge.NORTH -> p.lat <= c
    }

    private fun clip(pts: List<P>, e: Edge, c: Long): ArrayList<P> {
        val out = ArrayList<P>(pts.size + 4)
        if (pts.isEmpty()) return out
        var prev = pts[pts.size - 1]
        var prevIn = inside(prev, e, c)
        for (cur in pts) {
            val curIn = inside(cur, e, c)
            if (curIn) {
                if (!prevIn) out.add(cross(prev, cur, e, c))
                out.add(cur)
            } else if (prevIn) {
                out.add(cross(prev, cur, e, c))
            }
            prev = cur
            prevIn = curIn
        }
        return out
    }

    /** Where segment a–b crosses the edge's line: exactly on the line, the other coordinate rounded to the grid. */
    private fun cross(a: P, b: P, e: Edge, c: Long): P = when (e) {
        Edge.WEST, Edge.EAST -> P(along(a.lat, b.lat, c - a.lon, b.lon - a.lon), c, true)
        Edge.SOUTH, Edge.NORTH -> P(c, along(a.lon, b.lon, c - a.lat, b.lat - a.lat), true)
    }

    /** `a + (b − a) · num / den`, rounded to the nearest integer, ties upward, in exact integers. */
    private fun along(a: Long, b: Long, num: Long, den: Long): Long {
        require(den != 0L) { "a crossing of a segment parallel to the edge" }
        var n = BigInteger.valueOf(b - a).multiply(BigInteger.valueOf(num))
        var d = BigInteger.valueOf(den)
        if (d.signum() < 0) { n = n.negate(); d = d.negate() }
        // floor((2n + d) / 2d)
        val q = n.shiftLeft(1).add(d).divide(d.shiftLeft(1)).let { r ->
            val t = n.shiftLeft(1).add(d)
            if (t.signum() < 0 && t.mod(d.shiftLeft(1)).signum() != 0) r.subtract(BigInteger.ONE) else r
        }
        return a + q.longValueExact()
    }

    /** Twice the signed area of the ring [lat], [lon] on the grid (longitude as x, latitude as y), exactly. */
    fun area2(lat: IntArray, lon: IntArray): BigInteger = area2(List(lat.size) { P(lat[it].toLong(), lon[it].toLong(), false) })

    /** Twice the ring's signed area, exactly. */
    private fun area2(p: List<P>): BigInteger {
        var s = BigInteger.ZERO
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s = s.add(BigInteger.valueOf(a.lon).multiply(BigInteger.valueOf(b.lat)).subtract(BigInteger.valueOf(b.lon).multiply(BigInteger.valueOf(a.lat))))
        }
        return s
    }
}
