/*
 * A Kotlin port of KDBush 4.1.0 by Vladimir Agafonkin (https://github.com/mourner/kdbush), with a k-nearest
 * search after geokdbush's approach (https://github.com/mourner/geokdbush), here in plain planar distance.
 *
 * ISC License
 *
 * Copyright (c) 2026, Vladimir Agafonkin
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

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A static k-d tree of [numItems] points (KDBush): add every point, call [finish], then query by box, radius or
 * nearness. Coordinates are plain doubles; for a radius in metres, give it metres (a [LocalFrame]) rather than degrees.
 */
class PointIndex(val numItems: Int, nodeSize: Int = 64) {
    val nodeSize: Int = nodeSize.coerceIn(2, 65535)

    private val ids = IntArray(numItems)
    private val coords = DoubleArray(numItems * 2)
    private var pos = 0

    /** True once [finish] has sorted the points. */
    var finished = false
        private set

    init {
        require(numItems >= 0) { "Unexpected numItems value: $numItems" }
    }

    /** Adds a point and returns its item index (0, 1, 2… in order of adding). */
    fun add(x: Double, y: Double): Int {
        check(pos < numItems * 2) { "Added more than $numItems items" }
        val index = pos shr 1
        ids[index] = index
        coords[pos++] = x
        coords[pos++] = y
        return index
    }

    /** Sorts the points into a k-d tree. Call once, after adding every point. */
    fun finish(): PointIndex {
        check(pos shr 1 == numItems) { "Added ${pos shr 1} items when expected $numItems" }
        sort(0, numItems - 1, 0)
        finished = true
        return this
    }

    /** Every point inside the box, edges included. */
    fun range(minX: Double, minY: Double, maxX: Double, maxY: Double): IntArray {
        val out = IntBuffer()
        range(minX, minY, maxX, maxY) { out.add(it); true }
        return out.toArray()
    }

    /** Visits every point inside the box until [visitor] returns false. */
    fun range(minX: Double, minY: Double, maxX: Double, maxY: Double, visitor: IndexVisitor) {
        check(finished) { "Data not yet indexed: call finish()" }
        if (numItems == 0) return
        val stack = IntArray(STACK_SIZE)
        stack[0] = 0
        stack[1] = numItems - 1
        stack[2] = 0
        var sp = 3

        while (sp > 0) {
            val axis = stack[--sp]
            val right = stack[--sp]
            val left = stack[--sp]

            if (right - left <= nodeSize) {
                for (i in left..right) {
                    val x = coords[2 * i]
                    val y = coords[2 * i + 1]
                    if (x >= minX && x <= maxX && y >= minY && y <= maxY && !visitor.visit(ids[i])) return
                }
                continue
            }

            val m = (left + right) shr 1
            val x = coords[2 * m]
            val y = coords[2 * m + 1]
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && !visitor.visit(ids[m])) return

            if (if (axis == 0) minX <= x else minY <= y) {
                stack[sp++] = left
                stack[sp++] = m - 1
                stack[sp++] = 1 - axis
            }
            if (if (axis == 0) maxX >= x else maxY >= y) {
                stack[sp++] = m + 1
                stack[sp++] = right
                stack[sp++] = 1 - axis
            }
        }
    }

    /** Every point within [r] of (qx, qy), the circle's edge included. */
    fun within(qx: Double, qy: Double, r: Double): IntArray {
        val out = IntBuffer()
        within(qx, qy, r) { out.add(it); true }
        return out.toArray()
    }

    /** Visits every point within [r] of (qx, qy) until [visitor] returns false; a negative radius finds nothing. */
    fun within(qx: Double, qy: Double, r: Double, visitor: IndexVisitor) {
        check(finished) { "Data not yet indexed: call finish()" }
        if (numItems == 0 || !(r >= 0)) return
        val stack = IntArray(STACK_SIZE)
        stack[0] = 0
        stack[1] = numItems - 1
        stack[2] = 0
        var sp = 3
        val r2 = r * r

        while (sp > 0) {
            val axis = stack[--sp]
            val right = stack[--sp]
            val left = stack[--sp]

            if (right - left <= nodeSize) {
                for (i in left..right) {
                    if (sqDist(coords[2 * i], coords[2 * i + 1], qx, qy) <= r2 && !visitor.visit(ids[i])) return
                }
                continue
            }

            val m = (left + right) shr 1
            val x = coords[2 * m]
            val y = coords[2 * m + 1]
            if (sqDist(x, y, qx, qy) <= r2 && !visitor.visit(ids[m])) return

            if (if (axis == 0) qx - r <= x else qy - r <= y) {
                stack[sp++] = left
                stack[sp++] = m - 1
                stack[sp++] = 1 - axis
            }
            if (if (axis == 0) qx + r >= x else qy + r >= y) {
                stack[sp++] = m + 1
                stack[sp++] = right
                stack[sp++] = 1 - axis
            }
        }
    }

    /**
     * Points in order of distance from (qx, qy), nearest first: at most [maxResults], none further than
     * [maxDistance], and only those [filter] accepts.
     */
    fun nearest(
        qx: Double,
        qy: Double,
        maxResults: Int = Int.MAX_VALUE,
        maxDistance: Double = Double.POSITIVE_INFINITY,
        filter: IndexFilter? = null,
    ): IntArray {
        check(finished) { "Data not yet indexed: call finish()" }
        if (numItems == 0 || maxResults <= 0 || !(maxDistance >= 0)) return IntArray(0)
        val maxD2 = maxDistance * maxDistance
        val result = IntBuffer()

        // Queue entries: points as (sorted position shl 1) or 1; tree nodes as (node number shl 1).
        // A node's range and bounds live in growable side arrays, indexed by node number.
        var nodeLeft = IntArray(64)
        var nodeRight = IntArray(64)
        var nodeAxis = IntArray(64)
        var nodeBox = DoubleArray(64 * 4)
        var nodes = 0
        val q = FlatQueue()

        fun pushNode(left: Int, right: Int, axis: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
            if (left > right) return
            val dx = max(max(x0 - qx, qx - x1), 0.0)
            val dy = max(max(y0 - qy, qy - y1), 0.0)
            val d2 = dx * dx + dy * dy
            if (d2 > maxD2) return
            if (nodes == nodeLeft.size) {
                val cap = nodes * 2
                nodeLeft = nodeLeft.copyOf(cap)
                nodeRight = nodeRight.copyOf(cap)
                nodeAxis = nodeAxis.copyOf(cap)
                nodeBox = nodeBox.copyOf(cap * 4)
            }
            nodeLeft[nodes] = left
            nodeRight[nodes] = right
            nodeAxis[nodes] = axis
            nodeBox[nodes * 4] = x0; nodeBox[nodes * 4 + 1] = y0; nodeBox[nodes * 4 + 2] = x1; nodeBox[nodes * 4 + 3] = y1
            q.push(nodes shl 1, d2)
            nodes++
        }

        fun pushPoint(i: Int) {
            val d2 = sqDist(coords[2 * i], coords[2 * i + 1], qx, qy)
            if (d2 <= maxD2 && (filter == null || filter.accept(ids[i]))) q.push((i shl 1) or 1, d2)
        }

        val inf = Double.POSITIVE_INFINITY
        pushNode(0, numItems - 1, 0, -inf, -inf, inf, inf)

        while (q.size > 0) {
            val top = q.pop()
            if (top and 1 == 1) {
                result.add(ids[top shr 1])
                if (result.size == maxResults) break
                continue
            }
            val n = top shr 1
            val left = nodeLeft[n]
            val right = nodeRight[n]
            if (right - left <= nodeSize) {
                for (i in left..right) pushPoint(i)
                continue
            }
            val axis = nodeAxis[n]
            val x0 = nodeBox[n * 4]; val y0 = nodeBox[n * 4 + 1]; val x1 = nodeBox[n * 4 + 2]; val y1 = nodeBox[n * 4 + 3]
            val m = (left + right) shr 1
            val mx = coords[2 * m]
            val my = coords[2 * m + 1]
            pushPoint(m)
            if (axis == 0) {
                pushNode(left, m - 1, 1, x0, y0, mx, y1)
                pushNode(m + 1, right, 1, mx, y0, x1, y1)
            } else {
                pushNode(left, m - 1, 0, x0, y0, x1, my)
                pushNode(m + 1, right, 0, x0, my, x1, y1)
            }
        }
        return result.toArray()
    }

    // Kd-sorts ids and coords around the middle so the halves lie left/right or below/above, taking turns.
    private fun sort(left: Int, right: Int, axis: Int) {
        if (right - left <= nodeSize) return
        val m = (left + right) shr 1
        select(m, left, right, axis)
        sort(left, m - 1, 1 - axis)
        sort(m + 1, right, 1 - axis)
    }

    // Floyd–Rivest selection: items left..k-1 end up no greater than item k on the axis.
    private fun select(k: Int, leftIn: Int, rightIn: Int, axis: Int) {
        var left = leftIn
        var right = rightIn
        while (right > left) {
            if (right - left > 600) {
                val n = (right - left + 1).toDouble()
                val m = (k - left + 1).toDouble()
                val z = ln(n)
                val s = 0.5 * exp(2 * z / 3)
                val sd = 0.5 * sqrt(z * s * (n - s) / n) * (if (m - n / 2 < 0) -1 else 1)
                val newLeft = max(left, floor(k - m * s / n + sd).toInt())
                val newRight = min(right, floor(k + (n - m) * s / n + sd).toInt())
                select(k, newLeft, newRight, axis)
            }

            val t = coords[2 * k + axis]
            var i = left
            var j = right

            swapItem(left, k)
            if (coords[2 * right + axis] > t) swapItem(left, right)

            while (i < j) {
                swapItem(i, j)
                i++
                j--
                while (coords[2 * i + axis] < t) i++
                while (coords[2 * j + axis] > t) j--
            }

            if (coords[2 * left + axis] == t) {
                swapItem(left, j)
            } else {
                j++
                swapItem(j, right)
            }

            if (j <= k) left = j + 1
            if (k <= j) right = j - 1
        }
    }

    private fun swapItem(i: Int, j: Int) {
        val id = ids[i]; ids[i] = ids[j]; ids[j] = id
        val x = coords[2 * i]; coords[2 * i] = coords[2 * j]; coords[2 * j] = x
        val y = coords[2 * i + 1]; coords[2 * i + 1] = coords[2 * j + 1]; coords[2 * j + 1] = y
    }

    private companion object {
        // 3 ints per frame × (tree height + 1); the height stays under 32 for any int-sized index.
        const val STACK_SIZE = 96

        fun sqDist(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = ax - bx
            val dy = ay - by
            return dx * dx + dy * dy
        }
    }
}
