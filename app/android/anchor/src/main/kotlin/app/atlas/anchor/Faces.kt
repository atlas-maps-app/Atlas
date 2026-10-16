package app.atlas.anchor

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Convex faces (plan-gpu D3, "Anti-aliasing: hyb4"): a convex ring of at most [PrimKind.MAX_FACE] corners is drawn
 * with analytic coverage, the product over its edges of `clamp(0.5 + d)` at the pixel's centre (d the signed distance
 * to the edge, device px). An opaque face's interior, inset by [INSET] from every edge, is drawn in the opaque pass at
 * the 4x sample positions; its fringe, the whole face dilated, in the translucent pass at the face's own depth, so the
 * depth test rejects it over its own interior. Walls' and roofs' faces carry [PrimFlag.CULL_ON_FLIP].
 */
object Faces {
    /** The 4x MSAA sample positions in a pixel (the standard rotated pattern), x and y in 0 … 1. */
    val SAMPLES_4X = doubleArrayOf(0.375, 0.125, 0.875, 0.375, 0.125, 0.625, 0.625, 0.875)

    /** How far inside every edge an opaque interior's samples lie, device px: half a pixel plus the farthest sample's reach (0.395). */
    val INSET: Double = 0.5 + hypot(0.125, 0.375)

    /** Item kinds whose faces are walls and roofs: dropped when they turn away from the camera. */
    val WALL_KINDS = setOf("building", "part")

    /** Twice the signed (shoelace) area of a ring, x right and y down: positive when it turns clockwise on the screen. */
    fun area2(xs: DoubleArray, ys: DoubleArray, n: Int): Double {
        var a = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            a += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return a
    }

    /**
     * The corners of a convex ring: [n] vertices ([xs], [ys]) with repeated and straight-through vertices dropped, their
     * positions among the n written to [out]. Returns how many corners there are (≥ 3), or −1 when the ring is not
     * convex, turns more than once round, or has fewer than three corners.
     */
    fun convexCorners(xs: DoubleArray, ys: DoubleArray, n: Int, out: IntArray): Int {
        // drop repeats
        var m = 0
        val keep = IntArray(n)
        for (i in 0 until n) {
            if (m > 0 && xs[i] == xs[keep[m - 1]] && ys[i] == ys[keep[m - 1]]) continue
            keep[m++] = i
        }
        while (m > 1 && xs[keep[m - 1]] == xs[keep[0]] && ys[keep[m - 1]] == ys[keep[0]]) m--
        if (m < 3) return -1
        var sign = 0
        var c = 0
        var turn = 0.0
        for (k in 0 until m) {
            val a = keep[(k + m - 1) % m]; val b = keep[k]; val d = keep[(k + 1) % m]
            val ux = xs[b] - xs[a]; val uy = ys[b] - ys[a]
            val vx = xs[d] - xs[b]; val vy = ys[d] - ys[b]
            val cr = ux * vy - uy * vx
            val scale = hypot(ux, uy) * hypot(vx, vy)
            if (abs(cr) <= 1e-9 * scale) {
                // straight through: not a corner; a turn back on itself is not convex
                if (ux * vx + uy * vy < 0) return -1
                continue
            }
            val s = if (cr > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return -1
            turn += Math.atan2(cr, ux * vx + uy * vy)
            if (c == out.size) return -1
            out[c++] = b
        }
        // a convex ring turns once round
        if (c < 3 || abs(abs(turn) - 2 * Math.PI) > 1e-3) return -1
        return c
    }

    /**
     * Analytic coverage at ([px], [py]) of the convex polygon ([xs], [ys], [n] corners, device px): the product over its
     * edges of `clamp(0.5 + d, 0, 1)`. [edges] holds the edge equations from [edges].
     */
    fun coverage(edges: DoubleArray, m: Int, px: Double, py: Double): Double {
        var cov = 1.0
        var e = 0
        while (e < m && cov > 0) {
            val d = edges[e * 3] * px + edges[e * 3 + 1] * py + edges[e * 3 + 2]
            cov *= clamp(0.5 + d, 0.0, 1.0)
            e++
        }
        return cov
    }

    /**
     * Which side of a face's internal edge ([ux], [uy]) – ([vx], [vy]) the point ([px], [py]) lies on, as both faces that
     * share the edge see it: the orientation of the point against the edge taken from its lower-indexed point ([pu] <
     * [pv] chooses the order), so the two faces compute the same number. [side] is that orientation's sign at the face's
     * own inside; a point exactly on the edge goes to the face whose side is positive.
     */
    fun hardSide(ux: Double, uy: Double, vx: Double, vy: Double, pu: Int, pv: Int, px: Double, py: Double, side: Double): Boolean {
        val o = if (pu < pv) (vx - ux) * (py - uy) - (vy - uy) * (px - ux) else (ux - vx) * (py - vy) - (uy - vy) * (px - vx)
        return if (o == 0.0) side > 0 else (o > 0) == (side > 0)
    }

    /**
     * An ellipse's coverage at a pixel whose centre lies [d] device px inside its edge (the first-order distance, its
     * implicit function over its gradient's length), for semi-axes [rx], [ry] (device px): `clamp(0.5 + d − δ)` times
     * `min(1, 2·min(rx, ry))`, where δ = 1 / (24·max(½, √(rx·ry))) takes back the area the straight-edge coverage adds
     * along a curved edge (π/12 px² for a circle), so a small dot carries the ink its area does.
     */
    fun ellipseCoverage(d: Double, rx: Double, ry: Double): Double =
        clamp(0.5 + d - ellipseShift(rx, ry), 0.0, 1.0) * minOf(1.0, 2 * minOf(rx, ry))

    /** The edge shift δ of [ellipseCoverage]. */
    fun ellipseShift(rx: Double, ry: Double): Double = 1.0 / (24.0 * maxOf(0.5, kotlin.math.sqrt(rx * ry)))

    /**
     * How far inside its edge an opaque ellipse's interior lies: [INSET] plus its shift. The interior is the ellipse with
     * both semi-axes shortened by this (or any polygon inscribed in it), sampled at the 4x positions, so every pixel it
     * touches is fully covered.
     */
    fun ellipseInset(rx: Double, ry: Double): Double = INSET + ellipseShift(rx, ry)

    /** The smallest signed distance from ([px], [py]) to the polygon's edges (positive inside). */
    fun inside(edges: DoubleArray, m: Int, px: Double, py: Double): Double {
        var d = Double.MAX_VALUE
        for (e in 0 until m) d = minOf(d, edges[e * 3] * px + edges[e * 3 + 1] * py + edges[e * 3 + 2])
        return d
    }

    /**
     * Writes the polygon's edge equations (a, b, c per edge: inside when a·x + b·y + c ≥ 0, normalised so the value is
     * the distance) into [out]; returns how many (edges of zero length are left out).
     */
    fun edges(xs: DoubleArray, ys: DoubleArray, n: Int, out: DoubleArray): Int {
        val s = if (area2(xs, ys, n) > 0) 1.0 else -1.0
        var m = 0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val dx = xs[j] - xs[i]; val dy = ys[j] - ys[i]
            val l = hypot(dx, dy)
            if (l < 1e-12) continue
            val a = -dy / l * s; val b = dx / l * s
            out[m * 3] = a; out[m * 3 + 1] = b; out[m * 3 + 2] = -(a * xs[i] + b * ys[i])
            m++
        }
        return m
    }
}

/** Emits one FACE from the [n] points [corners] ([xs], [ys] their SVG coordinates), its internal edges in [hard]. */
internal fun PrimBuilder.face(corners: IntArray, xs: DoubleArray, ys: DoubleArray, n: Int, hard: Int, flags: Int, paint: Int, item: Int, wall: Boolean) {
    var f = flags
    val a = Faces.area2(xs, ys, n)
    if (a > 0) f = f or PrimFlag.POSITIVE
    // only a face whose facing survives its points moving by a tenth of a pixel (their residuals, faded out in motion)
    // keeps it: a sliver's winding is noise
    if (wall) {
        var perimeter = 0.0
        for (k in 0 until n) { val j = if (k + 1 == n) 0 else k + 1; perimeter += hypot(xs[j] - xs[k], ys[j] - ys[k]) }
        if (abs(a) >= 0.2 * perimeter) f = f or PrimFlag.CULL_ON_FLIP
    }
    begin(PrimKind.FACE, f, paint, item)
    put(n)
    put(hard)
    for (k in 0 until n) put(corners[k])
    end()
}

/**
 * Cuts a polygon, given as Earcut's triangles over its vertices ([xs], [ys]), into convex faces of at most
 * [PrimKind.MAX_FACE] corners (Hertel–Mehlhorn: neighbouring pieces are merged across their shared cut while the
 * result stays convex). [outline] tells whether u–v is an edge of the polygon's own rings; every other edge of a face
 * is a cut, internal to the fill.
 */
internal class ConvexCut(private val xs: DoubleArray, private val ys: DoubleArray, private val outline: (Int, Int) -> Boolean) {
    /** The pieces: vertex lists turning positively (shoelace > 0), each with its cut edges (bit k: vertex k to k + 1). */
    fun run(tris: IntArray, emit: (IntArray, Int) -> Unit) {
        val pieces = ArrayList<IntArray?>(tris.size / 3)
        // directed edge to the piece that has it; stale entries (of merged pieces) are passed over by merge()
        val owner = LongIntMap(tris.size * 2)
        var t = 0
        while (t < tris.size) {
            var a = tris[t]; var b = tris[t + 1]; var c = tris[t + 2]
            t += 3
            val o = orient(a, b, c)
            if (abs(o) <= 1e-12 * scale(a, b, c)) continue
            if (o < 0) { val q = b; b = c; c = q }
            val id = pieces.size
            pieces.add(intArrayOf(a, b, c))
            owner.put(key(a, b), id); owner.put(key(b, c), id); owner.put(key(c, a), id)
        }
        for (p in pieces.indices) {
            var merged = true
            while (merged) {
                merged = false
                val poly = pieces[p] ?: break
                val n = poly.size
                for (k in 0 until n) {
                    val u = poly[k]; val v = poly[(k + 1) % n]
                    if (outline(u, v)) continue
                    val q = owner.get(key(v, u))
                    if (q < 0 || q == p) continue
                    val other = pieces[q] ?: continue
                    val m = merge(poly, k, other, u, v) ?: continue
                    pieces[q] = null
                    pieces[p] = m
                    for (j in m.indices) owner.put(key(m[j], m[(j + 1) % m.size]), p)
                    merged = true
                    break
                }
            }
        }
        for (poly in pieces) if (poly != null) emitPiece(poly, emit)
    }

    /** [p] with its edge u→v (at [k]) replaced by [q]'s path from u round to v; null unless convex with at most MAX_FACE corners. */
    private fun merge(p: IntArray, k: Int, q: IntArray, u: Int, v: Int): IntArray? {
        val j = (0 until q.size).firstOrNull { q[it] == v && q[(it + 1) % q.size] == u } ?: return null
        val out = IntArray(p.size + q.size - 2)
        var n = 0
        for (i in 0 until p.size) out[n++] = p[(k + 1 + i) % p.size] // v … u
        for (i in 2 until q.size) out[n++] = q[(j + i) % q.size] // after u, up to before v
        if (n > MAX_VERTICES) return null
        var corners = 0
        for (i in 0 until n) {
            val a = out[(i + n - 1) % n]; val b = out[i]; val c = out[(i + 1) % n]
            val o = orient(a, b, c)
            val sc = scale(a, b, c)
            if (o < -1e-12 * sc) return null // a reflex turn
            if (o <= 1e-12 * sc) {
                // straight through: fine where both sides are of one kind (the face is drawn without the vertex)
                if (dot(a, b, c) < 0) return null
                if (outline(a, b) != outline(b, c)) return null
                continue
            }
            corners++
        }
        return if (corners in 3..PrimKind.MAX_FACE) out else null
    }

    /** One face: straight-through vertices dropped, cut edges marked. */
    private fun emitPiece(poly: IntArray, emit: (IntArray, Int) -> Unit) {
        val n = poly.size
        val keep = IntArray(n)
        var m = 0
        for (i in 0 until n) {
            val a = poly[(i + n - 1) % n]; val b = poly[i]; val c = poly[(i + 1) % n]
            if (abs(orient(a, b, c)) <= 1e-12 * scale(a, b, c) && dot(a, b, c) > 0) continue
            keep[m++] = i
        }
        if (m < 3) return
        val corners = IntArray(m) { poly[keep[it]] }
        var hard = 0
        for (i in 0 until m) {
            // an edge between kept corners is a cut when the polygon's edge it starts with is (they are of one kind)
            val at = keep[i]
            if (!outline(poly[at], poly[(at + 1) % n])) hard = hard or (1 shl i)
        }
        emit(corners, hard)
    }

    private fun orient(a: Int, b: Int, c: Int): Double = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])
    private fun dot(a: Int, b: Int, c: Int): Double = (xs[b] - xs[a]) * (xs[c] - xs[b]) + (ys[b] - ys[a]) * (ys[c] - ys[b])
    private fun scale(a: Int, b: Int, c: Int): Double {
        val l1 = hypot(xs[b] - xs[a], ys[b] - ys[a]); val l2 = hypot(xs[c] - xs[b], ys[c] - ys[b])
        return maxOf(l1 * l2, 1e-300)
    }

    private fun key(u: Int, v: Int): Long = (u.toLong() shl 32) or (v.toLong() and 0xFFFFFFFFL)

    companion object {
        private const val MAX_VERTICES = 32
    }
}
