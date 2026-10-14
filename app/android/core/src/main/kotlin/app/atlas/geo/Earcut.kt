/*
 * A faithful Kotlin port of Earcut 3.2.4, the polygon triangulation library by Mapbox:
 * https://github.com/mapbox/earcut
 *
 * ISC License
 *
 * Copyright (c) 2026, Mapbox
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose
 * with or without fee is hereby granted, provided that the above copyright notice
 * and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 * OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 */
package app.atlas.geo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Polygon triangulation by ear slicing (Mapbox Earcut). Fast, robust against the bad geometry real maps carry
 * (self-touching rings, holes touching the outline, coincident points), and exact on clean input.
 *
 * Every call keeps its own state, so the functions are safe to call from several threads at once.
 */
object Earcut {
    /**
     * Triangulates a polygon given as flat [coords] (x, y, … per vertex, [dim] numbers each) with optional holes:
     * [holeIndices] are vertex indices (not coordinate indices) where each hole ring starts.
     * Returns triangles as triplets of vertex indices into [coords].
     */
    fun triangulate(coords: DoubleArray, holeIndices: IntArray? = null, dim: Int = 2): IntArray {
        require(dim >= 2) { "dim must be at least 2" }
        return Triangulator().run(coords, holeIndices, dim)
    }

    /**
     * The relative difference between the polygon's area and the area of its [triangles]: near 0 means the
     * triangulation is right. Earcut's own correctness check.
     */
    fun deviation(coords: DoubleArray, holeIndices: IntArray?, dim: Int, triangles: IntArray): Double {
        val hasHoles = holeIndices != null && holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dim else coords.size

        var polygonArea = abs(signedArea(coords, 0, outerLen, dim))
        if (hasHoles) {
            val len = holeIndices.size
            for (i in 0 until len) {
                val start = holeIndices[i] * dim
                val end = if (i < len - 1) holeIndices[i + 1] * dim else coords.size
                polygonArea -= abs(signedArea(coords, start, end, dim))
            }
        }

        var trianglesArea = 0.0
        var i = 0
        while (i < triangles.size) {
            val a = triangles[i] * dim
            val b = triangles[i + 1] * dim
            val c = triangles[i + 2] * dim
            trianglesArea += abs(
                (coords[a] - coords[c]) * (coords[b + 1] - coords[a + 1]) -
                    (coords[a] - coords[b]) * (coords[c + 1] - coords[a + 1]),
            )
            i += 3
        }

        // With no triangles, a polygon area within shoelace roundoff of zero means degenerate input, not a bad result.
        if (trianglesArea == 0.0) {
            var m = 0.0
            var k = 0
            while (k < coords.size) {
                m = max(m, max(abs(coords[k]), abs(coords[k + 1])))
                k += dim
            }
            return if (abs(polygonArea) <= coords.size * m * m * EPSILON) 0.0 else 1.0
        }
        return abs((trianglesArea - polygonArea) / polygonArea)
    }

    /**
     * Refines [triangles] in place towards the constrained Delaunay triangulation with Lawson flips, removing most
     * slivers. Works on any manifold triangle-index array over [coords]; never produces an invalid mesh.
     */
    fun refine(triangles: IntArray, coords: DoubleArray, dim: Int = 2) {
        val t = triangles
        val n = t.size
        if (n < 6) return
        val edgeStack = IntArray(n)
        val he = IntArray(n) { -1 }
        val edgeStamp = BooleanArray(n)
        var size = 1
        while (size < n * 4) size = size shl 1
        val hTable = IntArray(size)
        val used = BooleanArray(size)
        val hMask = size - 1

        // Twin half-edges through an undirected-edge hash; each linked pair seeds the flip stack once.
        var sp = 0
        for (e in 0 until n) {
            val a = t[e]
            val b = t[nextHalfEdge(e)]
            val lo = if (a < b) a else b
            val hi = if (a < b) b else a
            var h = ((lo * HASH_A) xor (hi * HASH_B)) and hMask
            while (used[h]) {
                val s = hTable[h]
                if (s != -1) {
                    val sa = t[s]
                    val sb = t[nextHalfEdge(s)]
                    if ((sa == lo && sb == hi) || (sa == hi && sb == lo)) {
                        he[e] = s; he[s] = e; hTable[h] = -1
                        edgeStamp[s] = true; edgeStack[sp++] = s
                        break
                    }
                }
                h = (h + 1) and hMask
            }
            if (!used[h]) { hTable[h] = e; used[h] = true }
        }

        while (sp > 0) {
            val a = edgeStack[--sp]
            edgeStamp[a] = false
            val b = he[a]
            if (b == -1) continue

            val a0 = a - a % 3
            val b0 = b - b % 3
            val ar = a0 + (a + 2) % 3
            val al = a0 + (a + 1) % 3
            val bl = b0 + (b + 2) % 3
            val br = b0 + (b + 1) % 3
            val p0 = t[ar]; val pr = t[a]; val pl = t[al]; val p1 = t[bl]

            val x0 = coords[p0 * dim]; val y0 = coords[p0 * dim + 1]
            val xr = coords[pr * dim]; val yr = coords[pr * dim + 1]
            val xl = coords[pl * dim]; val yl = coords[pl * dim + 1]
            val x1 = coords[p1 * dim]; val y1 = coords[p1 * dim + 1]

            if (!inCircle(x0, y0, xr, yr, xl, yl, x1, y1) &&
                orient(x0, y0, xr, yr, x1, y1) > 0 && orient(x0, y0, x1, y1, xl, yl) > 0
            ) {
                t[a] = p1; t[b] = p0
                val hbl = he[bl]
                val har = he[ar]
                he[a] = hbl; if (hbl != -1) he[hbl] = a
                he[b] = har; if (har != -1) he[har] = b
                he[ar] = bl; he[bl] = ar

                if (hbl != -1 && !edgeStamp[a]) { edgeStamp[a] = true; edgeStack[sp++] = a }
                if (har != -1 && !edgeStamp[b]) { edgeStamp[b] = true; edgeStack[sp++] = b }
                if (he[al] != -1 && !edgeStamp[al]) { edgeStamp[al] = true; edgeStack[sp++] = al }
                if (he[br] != -1 && !edgeStamp[br]) { edgeStamp[br] = true; edgeStack[sp++] = br }
            }
        }
    }

    /** A polygon in Earcut's flat form: all rings' coordinates in one array and the vertex index where each hole starts. */
    class Flat(val vertices: DoubleArray, val holes: IntArray, val dim: Int)

    /** Joins [rings] (outer first, then holes; each a flat coordinate array of [dim] numbers per vertex) into Earcut's form. */
    fun flatten(rings: List<DoubleArray>, dim: Int = 2): Flat {
        var total = 0
        for (r in rings) total += r.size
        val vertices = DoubleArray(total)
        val holes = IntArray(rings.size)
        var holeCount = 0
        var offset = 0
        for (r in rings) {
            val vertexIndex = offset / dim
            if (vertexIndex != 0 && r.isNotEmpty()) holes[holeCount++] = vertexIndex
            r.copyInto(vertices, offset)
            offset += r.size
        }
        return Flat(vertices, holes.copyOf(holeCount), dim)
    }

    private const val EPSILON = 2.220446049250313e-16
    private const val HASH_A = -0x61c8864f // 0x9e3779b1 as a 32-bit int
    private const val HASH_B = -0x7a143595 // 0x85ebca6b as a 32-bit int

    internal fun signedArea(data: DoubleArray, start: Int, end: Int, dim: Int): Double {
        var sum = 0.0
        var i = start
        var j = end - dim
        while (i < end) {
            sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1])
            j = i
            i += dim
        }
        return sum
    }

    private fun nextHalfEdge(e: Int) = e - e % 3 + (e + 1) % 3

    private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    // Whether p is inside or on the circumcircle of (a, b, c), with earcut's winding and a tie margin against roundoff.
    private fun inCircle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean {
        val dx = ax - px; val dy = ay - py
        val ex = bx - px; val ey = by - py
        val fx = cx - px; val fy = cy - py
        val ap = dx * dx + dy * dy
        val bp = ex * ex + ey * ey
        val cp = fx * fx + fy * fy
        val s = ap + bp + cp
        return dx * (ey * cp - bp * fy) - dy * (ex * cp - bp * fx) + ap * (ex * fy - ey * fx) <= 1e-13 * s * s
    }
}

/** A vertex in a circular doubly linked ring; prev/next point at the node itself until linked. */
private class EarNode(@JvmField val i: Int, @JvmField val x: Double, @JvmField val y: Double) {
    @JvmField var prev: EarNode = this
    @JvmField var next: EarNode = this
    /** Z-order value; doubles as the owning block index while holes are merged. */
    @JvmField var z: Int = 0
    @JvmField var prevZ: EarNode? = null
    @JvmField var nextZ: EarNode? = null
    /** A single-vertex hole (Steiner point) that filtering must keep. */
    @JvmField var steiner: Boolean = false
}

/** One triangulation's state: what earcut.js keeps in module scope lives here, so calls do not share anything. */
private class Triangulator {
    private var triangles = IntArray(64)
    private var count = 0
    private var filteredOut = false
    private var indexActive = false

    // The hole-bridge block index: one bbox per K consecutive ring edges.
    private var blockBBox = DoubleArray(0)
    private var numBlocks = 0
    private var blockHead = arrayOfNulls<EarNode>(0)
    private var blockStop = arrayOfNulls<EarNode>(0)

    // Z-order sort scratch.
    private var sortArr = arrayOfNulls<EarNode>(0)
    private var sortBuf = arrayOfNulls<EarNode>(0)
    private var zArr = IntArray(0)
    private var zBuf = IntArray(0)
    private val counts = IntArray(256)

    fun run(data: DoubleArray, holeIndices: IntArray?, dim: Int): IntArray {
        val hasHoles = holeIndices != null && holeIndices.isNotEmpty()
        val outerLen = if (hasHoles) holeIndices[0] * dim else data.size

        var outerNode = linkedList(data, 0, outerLen, dim, true)
        if (outerNode == null || outerNode.next === outerNode.prev) return IntArray(0)
        triangles = IntArray(max(64, (data.size / dim) * 3))

        var minX = 0.0
        var minY = 0.0
        var invSize = 0.0

        if (hasHoles) outerNode = eliminateHoles(data, holeIndices, outerNode, dim)

        // If the shape is not too simple, a z-order curve hash speeds up the ear checks; it needs the bbox.
        if (data.size > 80 * dim) {
            minX = data[0]
            minY = data[1]
            var maxX = minX
            var maxY = minY
            var i = dim
            while (i < outerLen) {
                val x = data[i]
                val y = data[i + 1]
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
                i += dim
            }
            invSize = max(maxX - minX, maxY - minY)
            invSize = if (invSize != 0.0) 32767 / invSize else 0.0
        }

        earcutLinked(outerNode, minX, minY, invSize)
        return triangles.copyOf(count)
    }

    private fun emit(a: Int, b: Int, c: Int) {
        if (count + 3 > triangles.size) triangles = triangles.copyOf(triangles.size * 2)
        triangles[count] = a
        triangles[count + 1] = b
        triangles[count + 2] = c
        count += 3
    }

    // A circular doubly linked list from polygon points in the specified winding order.
    private fun linkedList(data: DoubleArray, start: Int, end: Int, dim: Int, clockwise: Boolean): EarNode? {
        var last: EarNode? = null
        if (clockwise == (Earcut.signedArea(data, start, end, dim) > 0)) {
            var i = start
            while (i < end) { last = insertNode(i / dim, data[i], data[i + 1], last); i += dim }
        } else {
            var i = end - dim
            while (i >= start) { last = insertNode(i / dim, data[i], data[i + 1], last); i -= dim }
        }
        if (last != null && equals(last, last.next)) {
            removeNode(last)
            last = last.next
        }
        return last
    }

    // Removes collinear or coincident points: the whole ring when end == start, else only the window up to end.
    private fun filterPoints(start: EarNode, endIn: EarNode = start): EarNode {
        var end = endIn
        val full = end === start
        var p = start
        var again: Boolean
        do {
            again = false
            if (p !== p.next && !p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0.0)) {
                if (full || p === end) end = p.prev
                filteredOut = true
                removeNode(p)
                p = p.prev
                again = true
            } else if (full || p !== end) {
                p = p.next
                again = !full
            }
        } while (again || p !== end)
        return end
    }

    // The main ear slicing loop.
    private fun earcutLinked(earIn: EarNode, minX: Double, minY: Double, invSize: Double) {
        var ear = earIn
        val hashed = invSize > 0
        if (hashed) indexCurve(ear, minX, minY, invSize)

        // A strictly convex ring has no vertex that could block an ear, so skip the point checks until one fails.
        var stop = ear
        var cured = false
        var convex = if (hashed) isConvex(ear) else false

        while (ear.prev !== ear.next) {
            val prev = ear.prev
            val next = ear.next

            if (area(prev, ear, next) < 0 && (convex || (if (hashed) isEarHashed(ear, minX, minY, invSize) else isEar(ear)))) {
                emit(prev.i, ear.i, next.i)
                removeNode(ear)
                ear = next
                stop = next
                continue
            }

            convex = false
            ear = next

            if (ear === stop) {
                // Filtering may expose new ears; repeat while it removes anything.
                filteredOut = false
                ear = filterPoints(ear)
                if (filteredOut) { stop = ear; continue }

                // Then cure small local self-intersections once.
                if (!cured) {
                    ear = cureLocalIntersections(ear)
                    stop = ear
                    cured = true
                    continue
                }

                // As a last resort, split the remaining polygon in two.
                splitEarcut(ear, minX, minY, invSize)
                break
            }
        }
    }

    private fun isConvex(start: EarNode): Boolean {
        var p = start
        do {
            if (area(p.prev, p, p.next) >= 0) return false
            p = p.next
        } while (p !== start)
        return true
    }

    private fun isEar(ear: EarNode): Boolean {
        val a = ear.prev
        val c = ear.next
        val ax = a.x; val bx = ear.x; val cx = c.x
        val ay = a.y; val by = ear.y; val cy = c.y
        val x0 = min(ax, min(bx, cx))
        val y0 = min(ay, min(by, cy))
        val x1 = max(ax, max(bx, cx))
        val y1 = max(ay, max(by, cy))

        var p = c.next
        while (p !== a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && !(ax == p.x && ay == p.y) &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) && area(p.prev, p, p.next) >= 0
            ) return false
            p = p.next
        }
        return true
    }

    private fun isEarHashed(ear: EarNode, minX: Double, minY: Double, invSize: Double): Boolean {
        val a = ear.prev
        val c = ear.next
        val ax = a.x; val bx = ear.x; val cx = c.x
        val ay = a.y; val by = ear.y; val cy = c.y
        val x0 = min(ax, min(bx, cx))
        val y0 = min(ay, min(by, cy))
        val x1 = max(ax, max(bx, cx))
        val y1 = max(ay, max(by, cy))
        val minZ = zOrder(x0, y0, minX, minY, invSize)
        val maxZ = zOrder(x1, y1, minX, minY, invSize)

        var p = ear.prevZ
        while (p != null && p.z >= minZ) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 && p !== c && !(ax == p.x && ay == p.y) &&
                pointInTriangle(ax, ay, bx, by, cx, cy, p.x, p.y) && area(p.prev, p, p.next) >= 0
            ) return false
            p = p.prevZ
        }
        var n = ear.nextZ
        while (n != null && n.z <= maxZ) {
            if (n.x >= x0 && n.x <= x1 && n.y >= y0 && n.y <= y1 && n !== c && !(ax == n.x && ay == n.y) &&
                pointInTriangle(ax, ay, bx, by, cx, cy, n.x, n.y) && area(n.prev, n, n.next) >= 0
            ) return false
            n = n.nextZ
        }
        return true
    }

    private fun cureLocalIntersections(startIn: EarNode): EarNode {
        var start = startIn
        var p = start
        var cured = false
        do {
            val a = p.prev
            val b = p.next.next
            if (intersects(a, p, p.next, b, false) && locallyInside(a, b) && locallyInside(b, a)) {
                emit(a.i, p.i, b.i)
                removeNode(p)
                removeNode(p.next)
                p = b
                start = b
                cured = true
            }
            p = p.next
        } while (p !== start)
        return if (cured) filterPoints(p) else p
    }

    private fun splitEarcut(start: EarNode, minX: Double, minY: Double, invSize: Double) {
        var a = start
        do {
            var b = a.next.next
            while (b !== a.prev) {
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    var c = splitPolygon(a, b)
                    a = filterPoints(a, a.next)
                    c = filterPoints(c, c.next)
                    earcutLinked(a, minX, minY, invSize)
                    earcutLinked(c, minX, minY, invSize)
                    return
                }
                b = b.next
            }
            a = a.next
        } while (a !== start)
    }

    // Links every hole into the outer ring, producing a single ring without holes.
    private fun eliminateHoles(data: DoubleArray, holeIndices: IntArray, outer: EarNode, dim: Int): EarNode {
        var outerNode = outer
        val len = holeIndices.size
        val queue = arrayOfNulls<EarNode>(len)
        var q = 0
        for (i in 0 until len) {
            val start = holeIndices[i] * dim
            val end = if (i < len - 1) holeIndices[i + 1] * dim else data.size
            val list = linkedList(data, start, end, dim, false) ?: continue
            if (list === list.next) list.steiner = true
            queue[q++] = getLeftmost(list)
        }
        @Suppress("UNCHECKED_CAST")
        val holes = queue.copyOf(q) as Array<EarNode>
        stableSort(holes)

        buildBlockIndex(data.size / dim, len)
        indexSegment(outerNode, outerNode)

        indexActive = true
        for (h in holes) outerNode = eliminateHole(h, outerNode)
        indexActive = false

        return filterPoints(outerNode)
    }

    // A stable merge sort by compareXYSlope that, unlike TimSort, never throws on the comparator's NaN ties.
    private fun stableSort(a: Array<EarNode>) {
        val n = a.size
        if (n < 2) return
        var src = a
        var dst = a.copyOf()
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = min(lo + width, n)
                val hi = min(lo + 2 * width, n)
                var i = lo
                var j = mid
                var k = lo
                while (i < mid && j < hi) dst[k++] = if (compareXYSlope(src[j], src[i]) < 0) src[j++] else src[i++]
                while (i < mid) dst[k++] = src[i++]
                while (j < hi) dst[k++] = src[j++]
                lo += 2 * width
            }
            val t = src; src = dst; dst = t
            width *= 2
        }
        if (src !== a) src.copyInto(a)
    }

    // When the leftmost points of two holes meet at a vertex, sort them counter-clockwise so the bridge lands there.
    private fun compareXYSlope(a: EarNode, b: EarNode): Double {
        val dx = a.x - b.x
        if (dx != 0.0 && !dx.isNaN()) return dx
        val dy = a.y - b.y
        if (dy != 0.0 && !dy.isNaN()) return dy
        val s = (a.next.y - a.y) / (a.next.x - a.x) - (b.next.y - b.y) / (b.next.x - b.x)
        return if (s.isNaN()) 0.0 else s
    }

    private fun eliminateHole(hole: EarNode, outerNode: EarNode): EarNode {
        val bridge = findHoleBridge(hole, outerNode) ?: return outerNode
        val bridgeReverse = splitPolygon(bridge, hole)

        // Index the merged-in run before filtering: bridge -> hole -> bridgeReverse -> bridge2 -> bridge's old next.
        val bridge2 = bridgeReverse.next
        indexSegment(bridge, bridge2.next)

        filterPoints(bridgeReverse, bridgeReverse.next)
        return filterPoints(bridge, bridge.next)
    }

    private fun buildBlockIndex(maxNodes: Int, numHoles: Int) {
        val maxBlocks = (maxNodes + 2 * numHoles + K - 1) / K + numHoles + 2
        if (blockBBox.size < maxBlocks * 4) {
            blockBBox = DoubleArray(maxBlocks * 4)
            blockHead = arrayOfNulls(maxBlocks)
            blockStop = arrayOfNulls(maxBlocks)
        }
        numBlocks = 0
    }

    private fun growBlocks() {
        val cap = blockHead.size * 2 + 4
        blockBBox = blockBBox.copyOf(cap * 4)
        blockHead = blockHead.copyOf(cap)
        blockStop = blockStop.copyOf(cap)
    }

    // Indexes the ring run head..stop (exclusive) as ceil(len / K) blocks; head == stop means the whole ring.
    private fun indexSegment(head: EarNode, stop: EarNode) {
        var p = head
        do {
            if (numBlocks == blockHead.size) growBlocks()
            val b = numBlocks++
            blockHead[b] = p
            var minX = p.x; var minY = p.y; var maxX = p.x; var maxY = p.y
            var k = 0
            do {
                val c = p.next
                p.z = b
                if (c.x < minX) minX = c.x
                if (c.x > maxX) maxX = c.x
                if (c.y < minY) minY = c.y
                if (c.y > maxY) maxY = c.y
                p = c
            } while (++k < K && p !== stop)
            blockStop[b] = p
            val g = b * 4
            blockBBox[g] = minX; blockBBox[g + 1] = minY; blockBBox[g + 2] = maxX; blockBBox[g + 3] = maxY
        } while (p !== stop)
    }

    // A healed edge head->tail can reach past head's block bbox; grow the bbox so the bridge scan cannot skip it.
    private fun growBlock(head: EarNode, tail: EarNode) {
        val g = head.z * 4
        if (g < 0 || g + 3 >= blockBBox.size) return
        if (tail.x < blockBBox[g]) blockBBox[g] = tail.x
        if (tail.y < blockBBox[g + 1]) blockBBox[g + 1] = tail.y
        if (tail.x > blockBBox[g + 2]) blockBBox[g + 2] = tail.x
        if (tail.y > blockBBox[g + 3]) blockBBox[g + 3] = tail.y
    }

    private fun liveBlockStop(b: Int): EarNode {
        var stop = blockStop[b]!!
        while (stop.prev.next !== stop) stop = stop.next
        blockStop[b] = stop
        return stop
    }

    private fun liveBlockHead(b: Int): EarNode {
        var head = blockHead[b]!!
        while (head.prev.next !== head) head = head.next
        blockHead[b] = head
        return head
    }

    // David Eberly's algorithm for a bridge between a hole and the outer polygon.
    private fun findHoleBridge(hole: EarNode, outerNode: EarNode): EarNode? {
        var p = outerNode
        val hx = hole.x
        val hy = hole.y
        var qx = Double.NEGATIVE_INFINITY
        var m: EarNode? = null

        if (equals(hole, p)) return p

        // A ray from the hole's leftmost point to the left: the nearest segment it crosses holds the candidate.
        var g = 0
        for (b in 0 until numBlocks) {
            if (!(hy < blockBBox[g + 1] || hy > blockBBox[g + 3] || blockBBox[g] > hx || blockBBox[g + 2] <= qx)) {
                val stop = liveBlockStop(b)
                p = liveBlockHead(b)
                do {
                    if (p.prev.next === p) {
                        if (equals(hole, p.next)) {
                            return p.next
                        } else if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                            val x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
                            if (x <= hx && x > qx) {
                                qx = x
                                m = if (p.x < p.next.x) p else p.next
                                if (x == hx) return m
                            }
                        }
                    }
                    p = p.next
                } while (p !== stop)
            }
            g += 4
        }

        if (m == null) return null

        // Points inside the triangle of hole point, intersection and endpoint: take the one at the least angle to the ray.
        val mx = m.x
        val my = m.y
        val tMinY = min(hy, my)
        val tMaxY = max(hy, my)
        var tanMin = Double.POSITIVE_INFINITY

        g = 0
        for (b in 0 until numBlocks) {
            if (!(blockBBox[g + 2] < mx || blockBBox[g] > hx || blockBBox[g + 3] < tMinY || blockBBox[g + 1] > tMaxY)) {
                val stop = liveBlockStop(b)
                p = liveBlockHead(b)
                do {
                    if (p.prev.next === p && hx >= p.x && p.x >= mx && hx != p.x &&
                        pointInTriangle(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)
                    ) {
                        val tan = abs(hy - p.y) / (hx - p.x)
                        // A hole point on p's horizontal edge (a T-junction touch) bridges along that edge.
                        if ((locallyInside(p, hole) || (p.y == hy && p.next.y == hy && p.next.x > hx)) &&
                            (tan < tanMin || (tan == tanMin && (p.x > m!!.x || (p.x == m.x && sectorContainsSector(m, p)))))
                        ) {
                            m = p
                            tanMin = tan
                        }
                    }
                    p = p.next
                } while (p !== stop)
            }
            g += 4
        }
        return m
    }

    private fun sectorContainsSector(m: EarNode, p: EarNode) =
        area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0

    // Interlinks the ring's nodes in z-order.
    private fun indexCurve(start: EarNode, minX: Double, minY: Double, invSize: Double) {
        var n = 0
        var p = start
        do { n++; p = p.next } while (p !== start)
        if (sortArr.size < n) sortArr = arrayOfNulls(n)

        n = 0
        p = start
        do {
            p.z = zOrder(p.x, p.y, minX, minY, invSize)
            sortArr[n++] = p
            p = p.next
        } while (p !== start)

        sortNodes(n)

        var prev: EarNode? = null
        for (i in 0 until n) {
            val node = sortArr[i]!!
            node.prevZ = prev
            prev?.nextZ = node
            prev = node
        }
        prev!!.nextZ = null
        sortArr.fill(null, 0, n)
        if (sortBuf.size >= n) sortBuf.fill(null, 0, n)
    }

    // Insertion sort for small rings, else an LSD radix sort in four 8-bit passes over z's 30 bits.
    private fun sortNodes(n: Int) {
        if (n <= 32) {
            for (i in 1 until n) {
                val node = sortArr[i]!!
                val z = node.z
                var j = i - 1
                while (j >= 0 && sortArr[j]!!.z > z) { sortArr[j + 1] = sortArr[j]; j-- }
                sortArr[j + 1] = node
            }
            return
        }
        if (zArr.size < n) {
            zArr = IntArray(n)
            zBuf = IntArray(n)
            sortBuf = arrayOfNulls(n)
        }
        for (i in 0 until n) zArr[i] = sortArr[i]!!.z
        radixPass(n, sortArr, zArr, sortBuf, zBuf, 0)
        radixPass(n, sortBuf, zBuf, sortArr, zArr, 8)
        radixPass(n, sortArr, zArr, sortBuf, zBuf, 16)
        radixPass(n, sortBuf, zBuf, sortArr, zArr, 24)
    }

    private fun radixPass(n: Int, src: Array<EarNode?>, srcZ: IntArray, dst: Array<EarNode?>, dstZ: IntArray, shift: Int) {
        counts.fill(0)
        for (i in 0 until n) counts[(srcZ[i] ushr shift) and 0xff]++
        var sum = 0
        for (b in 0 until 256) { val c = counts[b]; counts[b] = sum; sum += c }
        for (i in 0 until n) {
            val z = srcZ[i]
            val pos = counts[(z ushr shift) and 0xff]++
            dst[pos] = src[i]
            dstZ[pos] = z
        }
    }

    private fun getLeftmost(start: EarNode): EarNode {
        var p = start
        var leftmost = start
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) leftmost = p
            p = p.next
        } while (p !== start)
        return leftmost
    }

    private fun isValidDiagonal(a: EarNode, b: EarNode): Boolean {
        val zeroLength = equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0
        return a.next.i != b.i &&
            (zeroLength || locallyInside(a, b) && locallyInside(b, a) &&
                (area(a.prev, a, b.prev) != 0.0 || area(a, b.prev, b) != 0.0)) &&
            !intersectsPolygon(a, b) && (zeroLength || middleInside(a, b))
    }

    private fun intersects(p1: EarNode, q1: EarNode, p2: EarNode, q2: EarNode, includeBoundary: Boolean): Boolean {
        val o1 = area(p1, q1, p2)
        val o2 = area(p1, q1, q2)
        val o3 = area(p2, q2, p1)
        val o4 = area(p2, q2, q1)

        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) return true
        if (!includeBoundary) return false

        if (o1 == 0.0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0.0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0.0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0.0 && onSegment(p2, q1, q2)) return true
        return false
    }

    private fun intersectsPolygon(a: EarNode, b: EarNode): Boolean {
        val minX = min(a.x, b.x)
        val maxX = max(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxY = max(a.y, b.y)
        var p = a
        do {
            val n = p.next
            if (!((p.x > maxX && n.x > maxX) || (p.x < minX && n.x < minX) ||
                    (p.y > maxY && n.y > maxY) || (p.y < minY && n.y < minY)) &&
                p.i != a.i && n.i != a.i && p.i != b.i && n.i != b.i && intersects(p, n, a, b, true)
            ) return true
            p = n
        } while (p !== a)
        return false
    }

    private fun locallyInside(a: EarNode, b: EarNode): Boolean =
        if (area(a.prev, a, a.next) < 0) area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
        else area(a, b, a.prev) < 0 || area(a, a.next, b) < 0

    private fun middleInside(a: EarNode, b: EarNode): Boolean {
        var p = a
        var inside = false
        val px = (a.x + b.x) / 2
        val py = (a.y + b.y) / 2
        do {
            val n = p.next
            if (((p.y > py) != (n.y > py)) && (px < (n.x - p.x) * (py - p.y) / (n.y - p.y) + p.x)) inside = !inside
            p = n
        } while (p !== a)
        return inside
    }

    // Links two vertices with a bridge: splits one ring in two, or merges a hole into the outer ring.
    private fun splitPolygon(a: EarNode, b: EarNode): EarNode {
        val a2 = EarNode(a.i, a.x, a.y)
        val b2 = EarNode(b.i, b.x, b.y)
        val an = a.next
        val bp = b.prev

        a.next = b
        b.prev = a
        a2.next = an
        an.prev = a2
        b2.next = a2
        a2.prev = b2
        bp.next = b2
        b2.prev = bp
        return b2
    }

    private fun insertNode(i: Int, x: Double, y: Double, last: EarNode?): EarNode {
        val p = EarNode(i, x, y)
        if (last != null) {
            p.next = last.next
            p.prev = last
            last.next.prev = p
            last.next = p
        }
        return p
    }

    private fun removeNode(p: EarNode) {
        p.next.prev = p.prev
        p.prev.next = p.next
        p.prevZ?.nextZ = p.nextZ
        p.nextZ?.prevZ = p.prevZ
        if (indexActive) growBlock(p.prev, p.next)
    }

    private companion object {
        const val K = 16

        fun zOrder(px: Double, py: Double, minX: Double, minY: Double, invSize: Double): Int {
            // JavaScript's `| 0`: truncate, then wrap to 32 bits.
            var x = ((px - minX) * invSize).toLong().toInt()
            var y = ((py - minY) * invSize).toLong().toInt()
            x = (x or (x shl 8)) and 0x00FF00FF
            x = (x or (x shl 4)) and 0x0F0F0F0F
            x = (x or (x shl 2)) and 0x33333333
            x = (x or (x shl 1)) and 0x55555555
            y = (y or (y shl 8)) and 0x00FF00FF
            y = (y or (y shl 4)) and 0x0F0F0F0F
            y = (y or (y shl 2)) and 0x33333333
            y = (y or (y shl 1)) and 0x55555555
            return x or (y shl 1)
        }

        fun pointInTriangle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double) =
            (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
                (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
                (bx - px) * (cy - py) >= (cx - px) * (by - py)

        fun area(p: EarNode, q: EarNode, r: EarNode) = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)

        fun equals(p1: EarNode, p2: EarNode) = p1.x == p2.x && p1.y == p2.y

        fun onSegment(p: EarNode, q: EarNode, r: EarNode) =
            q.x <= max(p.x, r.x) && q.x >= min(p.x, r.x) && q.y <= max(p.y, r.y) && q.y >= min(p.y, r.y)
    }
}
