package app.atlas.anchor

import app.atlas.geo.Earcut
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Fills (plan-gpu D3): a convex ring of at most eight corners is a FACE; a concave ring, or rings with holes, are
 * triangulated by core's [Earcut] and the triangles merged into convex FACEs ([ConvexCut]) whose internal edges are
 * cut by the pixel's centre, so the outline keeps its analytic coverage and the faces tile the fill; rings that cross,
 * and any polygon Earcut cannot cut exactly, go through the stencil (a fan per ring, STENCIL) under the path's own
 * fill rule.
 *
 * Several rings of one path are first sorted into polygons with holes by their nesting and the fill rule (nonzero:
 * the winding number; even-odd: the nesting depth). An opaque nonzero path whose rings all turn the same way is drawn
 * ring by ring, since overlap does not show when nothing shows through.
 */
internal class Fills(private val out: PrimBuilder) {
    /** Fills drawn, and per kind: convex ones as one face, cut ones (their faces in [cutFaces]), stencilled ones. */
    var fills = 0; var faces = 0; var tris = 0; var stencils = 0; var cutFaces = 0

    /** Polygons Earcut could not cut exactly (drawn through the stencil instead). */
    var earcutMisses = 0

    /** The largest relative difference between a FACE's or TRI's area and its polygon's, over all fills. */
    var worstArea = 0.0

    // scratch
    private var rx = DoubleArray(64)
    private var ry = DoubleArray(64)
    private var corners = IntArray(PrimKind.MAX_FACE)
    private var rings = IntArray(16) // per ring: start, count into the ring vertex list
    private var ringV = IntArray(256) // vertex positions (in the shape) of every ring, in order
    private var ringCount = 0
    private var ringVCount = 0

    /**
     * Fills [shape] with [paint]: every subpath a ring (closed implicitly), under the even-odd rule when [evenOdd].
     * [opaque]: nothing of the paint shows through; [wall]: its faces are walls or roofs.
     */
    fun fill(shape: ElementShape, evenOdd: Boolean, opaque: Boolean, flags: Int, paint: Int, item: Int, wall: Boolean) {
        collectRings(shape)
        if (ringCount == 0) return
        fills++
        if (ringCount == 1) {
            piece(shape, 0, IntArray(0), flags, paint, item, wall)
            return
        }
        val area = DoubleArray(ringCount)
        for (r in 0 until ringCount) area[r] = ringArea2(shape, r)
        val sameTurn = area.all { it > 0 } || area.all { it < 0 }
        if (!evenOdd && opaque && sameTurn) {
            // the union of rings turning one way: overlap does not show through an opaque paint
            for (r in 0 until ringCount) piece(shape, r, IntArray(0), flags, paint, item, wall)
            return
        }
        val boxes = DoubleArray(ringCount * 4)
        for (r in 0 until ringCount) ringBox(shape, r, boxes, r * 4)
        if (crossing(shape, boxes)) {
            stencil(shape, IntArray(ringCount) { it }, evenOdd, flags, paint, item)
            return
        }
        // nesting: each ring's parent is the smallest ring holding it
        val order = (0 until ringCount).sortedByDescending { abs(area[it]) }
        val parent = IntArray(ringCount) { -1 }
        for ((k, r) in order.withIndex()) {
            for (q in k - 1 downTo 0) {
                val p = order[q]
                if (!holds(boxes, p, r)) continue
                val v = ringV[rings[r * 2]]
                if (insideRing(shape, p, shape.x[v], shape.y[v])) {
                    if (parent[r] < 0 || abs(area[p]) < abs(area[parent[r]])) parent[r] = p
                }
            }
        }
        val children = Array(ringCount) { ArrayList<Int>() }
        for (r in 0 until ringCount) if (parent[r] >= 0) children[parent[r]].add(r)
        // filled: the winding number (nonzero) or the depth (even-odd) of each ring's inside
        val winding = IntArray(ringCount)
        val depth = IntArray(ringCount)
        fun rank(r: Int) {
            for (c in children[r]) {
                winding[c] = winding[r] + (if (area[c] > 0) 1 else -1)
                depth[c] = depth[r] + 1
                rank(c)
            }
        }
        for (r in 0 until ringCount) if (parent[r] < 0) {
            winding[r] = if (area[r] > 0) 1 else -1; depth[r] = 1; rank(r)
        }
        val filled = BooleanArray(ringCount) { if (evenOdd) depth[it] % 2 == 1 else winding[it] != 0 }
        fun visit(r: Int) {
            if (!filled[r]) { for (c in children[r]) visit(c); return }
            val holes = ArrayList<Int>()
            fun scan(c: Int) {
                for (ch in children[c]) {
                    if (filled[ch]) scan(ch) else { holes.add(ch); for (g in children[ch]) visit(g) }
                }
            }
            scan(r)
            piece(shape, r, holes.toIntArray(), flags, paint, item, wall)
        }
        for (r in 0 until ringCount) if (parent[r] < 0) visit(r)
    }

    /** One polygon: ring [outer] with [holes]: a FACE when convex and small, else Earcut's triangles, else the stencil. */
    private fun piece(shape: ElementShape, outer: Int, holes: IntArray, flags: Int, paint: Int, item: Int, wall: Boolean) {
        val start = rings[outer * 2]; val n = rings[outer * 2 + 1]
        if (holes.isEmpty() && n >= 3) {
            if (rx.size < n) { rx = DoubleArray(n * 2); ry = DoubleArray(n * 2) }
            for (k in 0 until n) { val v = ringV[start + k]; rx[k] = shape.x[v]; ry[k] = shape.y[v] }
            val m = Faces.convexCorners(rx, ry, n, corners)
            if (m in 3..PrimKind.MAX_FACE) {
                val idx = IntArray(m) { shape.pt[ringV[start + corners[it]]] }
                val fx = DoubleArray(m) { rx[corners[it]] }; val fy = DoubleArray(m) { ry[corners[it]] }
                val want = abs(Faces.area2(rx, ry, n)); val got = abs(Faces.area2(fx, fy, m))
                if (want > 0) worstArea = max(worstArea, abs(got - want) / want)
                out.face(idx, fx, fy, m, 0, flags, paint, item, wall)
                faces++
                return
            }
        }
        // Earcut over the outer ring and its holes
        var total = n
        for (h in holes) total += rings[h * 2 + 1]
        val coords = DoubleArray(total * 2)
        val map = IntArray(total)
        var k = 0
        fun add(r: Int) {
            val s = rings[r * 2]; val c = rings[r * 2 + 1]
            for (j in 0 until c) {
                val v = ringV[s + j]
                coords[k * 2] = shape.x[v]; coords[k * 2 + 1] = shape.y[v]; map[k] = shape.pt[v]; k++
            }
        }
        add(outer)
        val holeStarts = IntArray(holes.size)
        for ((i, h) in holes.withIndex()) { holeStarts[i] = k; add(h) }
        val tri = Earcut.triangulate(coords, if (holes.isEmpty()) null else holeStarts)
        // its area against the polygon's: Earcut leaves out what it cannot cut
        var want = abs(ringArea2(shape, outer))
        for (h in holes) want -= abs(ringArea2(shape, h))
        var got = 0.0
        var t = 0
        while (t < tri.size) {
            val a = tri[t]; val b = tri[t + 1]; val c = tri[t + 2]
            got += abs((coords[b * 2] - coords[a * 2]) * (coords[c * 2 + 1] - coords[a * 2 + 1]) - (coords[c * 2] - coords[a * 2]) * (coords[b * 2 + 1] - coords[a * 2 + 1]))
            t += 3
        }
        val dev = if (want > 1e-9) abs(got - want) / want else if (got < 1e-9) 0.0 else 1.0
        if (tri.isEmpty() || dev > EARCUT_TOLERANCE) {
            if (want > 1e-6) earcutMisses++
            if (want <= 1e-6 && tri.isEmpty()) return // a ring with no area: nothing to draw
            val rs = IntArray(holes.size + 1); rs[0] = outer; holes.copyInto(rs, 1)
            stencil(shape, rs, true, flags, paint, item)
            return
        }
        worstArea = max(worstArea, dev)
        // Earcut's triangles merged into convex faces, cut by internal edges (no anti-aliasing inside the fill)
        val ringOf = IntArray(total); val posOf = IntArray(total); val lenOf = IntArray(total)
        var at = 0
        for ((r, ring) in (listOf(outer) + holes.toList()).withIndex()) {
            val c = rings[ring * 2 + 1]
            for (j in 0 until c) { ringOf[at] = r; posOf[at] = j; lenOf[at] = c; at++ }
        }
        val xs = DoubleArray(total) { coords[it * 2] }; val ys = DoubleArray(total) { coords[it * 2 + 1] }
        val outline = { u: Int, v: Int -> ringOf[u] == ringOf[v] && ((posOf[u] + 1) % lenOf[u] == posOf[v] || (posOf[v] + 1) % lenOf[v] == posOf[u]) }
        ConvexCut(xs, ys, outline).run(tri) { corners, hard ->
            val m = corners.size
            out.face(IntArray(m) { map[corners[it]] }, DoubleArray(m) { xs[corners[it]] }, DoubleArray(m) { ys[corners[it]] }, m, hard, flags, paint, item, wall)
            cutFaces++
        }
        tris++
    }

    /** Rings [rs] through the stencil: a fan per ring, under the even-odd or nonzero rule. */
    private fun stencil(shape: ElementShape, rs: IntArray, evenOdd: Boolean, flags: Int, paint: Int, item: Int) {
        var f = flags or PrimFlag.STENCIL
        if (evenOdd) f = f or PrimFlag.EVEN_ODD
        out.begin(PrimKind.STENCIL, f, paint, item)
        val first = out.triangleCount
        var count = 0
        for (r in rs) {
            val s = rings[r * 2]; val c = rings[r * 2 + 1]
            val p0 = shape.pt[ringV[s]]
            for (j in 1 until c - 1) {
                out.triangle(p0); out.triangle(shape.pt[ringV[s + j]]); out.triangle(shape.pt[ringV[s + j + 1]])
                count += 3
            }
        }
        out.put(first); out.put(count)
        out.end()
        stencils++
    }

    /** Every subpath with three or more distinct vertices, as a ring (its closing repeat dropped). */
    private fun collectRings(shape: ElementShape) {
        ringCount = 0; ringVCount = 0
        for (s in 0 until shape.subs) {
            val st = shape.subStart[s]; val c = shape.subCount[s]
            val begin = ringVCount
            for (j in 0 until c) {
                val v = st + j
                if (ringVCount > begin) {
                    val last = ringV[ringVCount - 1]
                    if (shape.x[v] == shape.x[last] && shape.y[v] == shape.y[last]) continue
                }
                if (ringVCount == ringV.size) ringV = ringV.copyOf(ringV.size * 2)
                ringV[ringVCount++] = v
            }
            while (ringVCount - begin > 1 && shape.x[ringV[ringVCount - 1]] == shape.x[ringV[begin]] && shape.y[ringV[ringVCount - 1]] == shape.y[ringV[begin]]) ringVCount--
            if (ringVCount - begin < 3) { ringVCount = begin; continue }
            if (ringCount * 2 + 2 > rings.size) rings = rings.copyOf(rings.size * 2)
            rings[ringCount * 2] = begin; rings[ringCount * 2 + 1] = ringVCount - begin
            ringCount++
        }
    }

    private fun ringArea2(shape: ElementShape, r: Int): Double {
        val s = rings[r * 2]; val c = rings[r * 2 + 1]
        var a = 0.0
        for (j in 0 until c) {
            val p = ringV[s + j]; val q = ringV[s + (j + 1) % c]
            a += shape.x[p] * shape.y[q] - shape.x[q] * shape.y[p]
        }
        return a
    }

    private fun ringBox(shape: ElementShape, r: Int, out: DoubleArray, at: Int) {
        val s = rings[r * 2]; val c = rings[r * 2 + 1]
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (j in 0 until c) {
            val v = ringV[s + j]
            x0 = min(x0, shape.x[v]); y0 = min(y0, shape.y[v]); x1 = max(x1, shape.x[v]); y1 = max(y1, shape.y[v])
        }
        out[at] = x0; out[at + 1] = y0; out[at + 2] = x1; out[at + 3] = y1
    }

    private fun holds(boxes: DoubleArray, p: Int, r: Int): Boolean =
        boxes[p * 4] <= boxes[r * 4] && boxes[p * 4 + 1] <= boxes[r * 4 + 1] && boxes[p * 4 + 2] >= boxes[r * 4 + 2] && boxes[p * 4 + 3] >= boxes[r * 4 + 3]

    /** Whether ([x], [y]) lies inside ring [r] (crossing number). */
    private fun insideRing(shape: ElementShape, r: Int, x: Double, y: Double): Boolean {
        val s = rings[r * 2]; val c = rings[r * 2 + 1]
        var inside = false
        var j = c - 1
        for (i in 0 until c) {
            val a = ringV[s + i]; val b = ringV[s + j]
            val yi = shape.y[a]; val yj = shape.y[b]
            if ((yi > y) != (yj > y)) {
                val xi = shape.x[a]; val xj = shape.x[b]
                if (x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Whether any two rings' edges meet (cross or touch). */
    private fun crossing(shape: ElementShape, boxes: DoubleArray): Boolean {
        for (a in 0 until ringCount) for (b in a + 1 until ringCount) {
            if (boxes[a * 4] > boxes[b * 4 + 2] || boxes[b * 4] > boxes[a * 4 + 2] || boxes[a * 4 + 1] > boxes[b * 4 + 3] || boxes[b * 4 + 1] > boxes[a * 4 + 3]) continue
            val sa = rings[a * 2]; val ca = rings[a * 2 + 1]
            val sb = rings[b * 2]; val cb = rings[b * 2 + 1]
            // only the edges of each that reach into the other's box
            for (i in 0 until ca) {
                val p = ringV[sa + i]; val q = ringV[sa + (i + 1) % ca]
                if (max(shape.x[p], shape.x[q]) < boxes[b * 4] || min(shape.x[p], shape.x[q]) > boxes[b * 4 + 2] ||
                    max(shape.y[p], shape.y[q]) < boxes[b * 4 + 1] || min(shape.y[p], shape.y[q]) > boxes[b * 4 + 3]) continue
                for (j in 0 until cb) {
                    val r = ringV[sb + j]; val s = ringV[sb + (j + 1) % cb]
                    if (meet(shape.x[p], shape.y[p], shape.x[q], shape.y[q], shape.x[r], shape.y[r], shape.x[s], shape.y[s])) return true
                }
            }
        }
        return false
    }

    companion object {
        /** The largest relative area difference Earcut's triangles may have before the polygon goes through the stencil. */
        const val EARCUT_TOLERANCE = 1e-6

        private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Int {
            val v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            return if (v > 0) 1 else if (v < 0) -1 else 0
        }

        private fun onSeg(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Boolean =
            px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)

        /** Whether segments ab and cd meet (cross, touch, or overlap). */
        fun meet(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
            val o1 = orient(ax, ay, bx, by, cx, cy); val o2 = orient(ax, ay, bx, by, dx, dy)
            val o3 = orient(cx, cy, dx, dy, ax, ay); val o4 = orient(cx, cy, dx, dy, bx, by)
            if (o1 != o2 && o3 != o4) return true
            if (o1 == 0 && onSeg(ax, ay, bx, by, cx, cy)) return true
            if (o2 == 0 && onSeg(ax, ay, bx, by, dx, dy)) return true
            if (o3 == 0 && onSeg(cx, cy, dx, dy, ax, ay)) return true
            if (o4 == 0 && onSeg(cx, cy, dx, dy, bx, by)) return true
            return false
        }
    }
}
