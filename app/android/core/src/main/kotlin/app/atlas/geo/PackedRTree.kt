/*
 * A Kotlin port of Flatbush 4.6.2 by Vladimir Agafonkin (https://github.com/mourner/flatbush), with the FlatQueue
 * 3.1.0 priority queue it uses (https://github.com/mourner/flatqueue). The Hilbert curve code is by
 * http://threadlocalmutex.com/ (public domain). Both libraries are under the ISC License below.
 *
 * ISC License
 *
 * Copyright (c) 2022, Vladimir Agafonkin (Flatbush)
 * Copyright (c) 2025, Volodymyr Agafonkin (FlatQueue)
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

import kotlin.math.max
import kotlin.math.min

/** Receives the index of each item a spatial query finds; returns false to stop the query early. */
fun interface IndexVisitor {
    fun visit(index: Int): Boolean
}

/** Decides which items a nearest-first search may return, by item index; a primitive int, so nothing is boxed. */
fun interface IndexFilter {
    fun accept(index: Int): Boolean
}

/**
 * A static, packed, Hilbert-sorted R-tree of [numItems] boxes (Flatbush): add every box, call [finish], then query.
 * Coordinates are plain doubles: lon/lat, Mercator units or metres. Items are numbered in the order they were added.
 * Queries allocate only their small traversal stack and result, and may run from several threads once finished.
 */
class PackedRTree(val numItems: Int, nodeSize: Int = 16) {
    val nodeSize: Int = nodeSize.coerceIn(2, 65535)

    private val levelBounds: IntArray
    private val boxes: DoubleArray
    private val indices: IntArray
    private var pos = 0

    var minX = Double.POSITIVE_INFINITY; private set
    var minY = Double.POSITIVE_INFINITY; private set
    var maxX = Double.NEGATIVE_INFINITY; private set
    var maxY = Double.NEGATIVE_INFINITY; private set

    /** True once [finish] has built the tree. */
    val finished: Boolean get() = pos == boxes.size

    init {
        require(numItems >= 0) { "Unexpected numItems value: $numItems" }
        // The number of nodes in the tree and where each level ends, bottom-up.
        var n = numItems
        var numNodes = n
        val bounds = ArrayList<Int>()
        bounds += n * 4
        if (numItems > 0) {
            do {
                n = (n + this.nodeSize - 1) / this.nodeSize
                numNodes += n
                bounds += numNodes * 4
            } while (n != 1)
        }
        levelBounds = bounds.toIntArray()
        boxes = DoubleArray(numNodes * 4)
        indices = IntArray(numNodes)
    }

    /** Adds a box and returns its item index (0, 1, 2… in order of adding). */
    fun add(minX: Double, minY: Double, maxX: Double = minX, maxY: Double = minY): Int {
        check(pos < numItems * 4) { "Added more than $numItems items" }
        val p = pos
        val index = p shr 2
        indices[index] = index
        boxes[p] = minX
        boxes[p + 1] = minY
        boxes[p + 2] = maxX
        boxes[p + 3] = maxY
        pos = p + 4
        if (minX < this.minX) this.minX = minX
        if (minY < this.minY) this.minY = minY
        if (maxX > this.maxX) this.maxX = maxX
        if (maxY > this.maxY) this.maxY = maxY
        return index
    }

    /** Sorts the items along a Hilbert curve and packs the tree. Call once, after adding every item. */
    fun finish(): PackedRTree {
        check(pos shr 2 == numItems) { "Added ${pos shr 2} items when expected $numItems" }
        if (numItems == 0) return this

        if (numItems <= nodeSize) {
            // Only one node: skip sorting and just fill the root box.
            boxes[pos++] = minX
            boxes[pos++] = minY
            boxes[pos++] = maxX
            boxes[pos++] = maxY
            return this
        }

        val width = (maxX - minX).let { if (it == 0.0 || it.isNaN()) 1.0 else it }
        val height = (maxY - minY).let { if (it == 0.0 || it.isNaN()) 1.0 else it }
        val hilbertValues = IntArray(numItems)
        val hilbertMax = (1 shl 16) - 1
        val sx = hilbertMax / width
        val sy = hilbertMax / height

        // Item centres into Hilbert space.
        var p = 0
        for (i in 0 until numItems) {
            val itemMinX = boxes[p++]
            val itemMinY = boxes[p++]
            val itemMaxX = boxes[p++]
            val itemMaxY = boxes[p++]
            val x = (sx * ((itemMinX + itemMaxX) / 2 - minX)).toLong().toInt()
            val y = (sy * ((itemMinY + itemMaxY) / 2 - minY)).toLong().toInt()
            hilbertValues[i] = hilbert(x, y)
        }

        sort(hilbertValues, boxes, indices, 0, numItems - 1, nodeSize)

        // Parent nodes level by level, bottom-up.
        p = numItems * 4
        var readPos = 0
        for (i in 0 until levelBounds.size - 1) {
            val end = levelBounds[i]
            while (readPos < end) {
                val nodeIndex = readPos
                var nodeMinX = boxes[readPos++]
                var nodeMinY = boxes[readPos++]
                var nodeMaxX = boxes[readPos++]
                var nodeMaxY = boxes[readPos++]
                var j = 1
                while (j < nodeSize && readPos < end) {
                    nodeMinX = min(nodeMinX, boxes[readPos++])
                    nodeMinY = min(nodeMinY, boxes[readPos++])
                    nodeMaxX = max(nodeMaxX, boxes[readPos++])
                    nodeMaxY = max(nodeMaxY, boxes[readPos++])
                    j++
                }
                indices[p shr 2] = nodeIndex
                boxes[p++] = nodeMinX
                boxes[p++] = nodeMinY
                boxes[p++] = nodeMaxX
                boxes[p++] = nodeMaxY
            }
        }
        pos = p
        return this
    }

    /** Every item whose box intersects or touches the query box. */
    fun search(minX: Double, minY: Double, maxX: Double, maxY: Double): IntArray {
        val out = IntBuffer()
        search(minX, minY, maxX, maxY) { out.add(it); true }
        return out.toArray()
    }

    /** Visits every item whose box intersects or touches the query box, until [visitor] returns false. */
    fun search(minX: Double, minY: Double, maxX: Double, maxY: Double, visitor: IndexVisitor) {
        check(finished) { "Data not yet indexed: call finish()" }
        if (numItems == 0) return
        val numItems4 = numItems * 4
        var nodeIndex = boxes.size - 4
        var level = levelBounds.size - 1
        val stack = IntArray(2 * nodeSize * levelBounds.size + 2)
        var sp = 0
        var contained = false

        while (true) {
            val end = min(nodeIndex + nodeSize * 4, levelBounds[level])
            val isNode = nodeIndex >= numItems4

            if (contained) {
                if (!collectContained(nodeIndex, end, level, numItems4, visitor)) return
            } else {
                var p = nodeIndex
                while (p < end) {
                    val x0 = boxes[p]
                    val y0 = boxes[p + 1]
                    val x1 = boxes[p + 2]
                    val y1 = boxes[p + 3]
                    if (maxX >= x0 && maxY >= y0 && minX <= x1 && minY <= y1) {
                        val index = indices[p shr 2]
                        if (isNode) {
                            // Flag a node whose box lies wholly inside the query: its leaves need no more tests.
                            val c = if (minX <= x0 && minY <= y0 && maxX >= x1 && maxY >= y1) 1 else 0
                            stack[sp++] = index or c
                            stack[sp++] = level - 1
                        } else if (!visitor.visit(index)) {
                            return
                        }
                    }
                    p += 4
                }
            }

            if (sp == 0) return
            level = stack[--sp]
            nodeIndex = stack[--sp]
            contained = nodeIndex and 1 == 1
            nodeIndex = nodeIndex and 1.inv()
        }
    }

    // The leaves of a subtree wholly inside the query are one contiguous run of the leaf level: sweep it.
    private fun collectContained(nodeIndex: Int, end: Int, level: Int, numItems4: Int, visitor: IndexVisitor): Boolean {
        var p = nodeIndex
        for (l in level downTo 1) p = indices[p shr 2]
        var span = (end - nodeIndex).toLong()
        repeat(level) { span *= nodeSize }
        val leafEnd = min(p + span, numItems4.toLong()).toInt()
        while (p < leafEnd) {
            if (!visitor.visit(indices[p shr 2])) return false
            p += 4
        }
        return true
    }

    /**
     * Items in order of distance from (x, y), nearest first: at most [maxResults], none further than [maxDistance]
     * (in the index's units). Distance is to each item's box, so it is exact for points.
     */
    fun neighbors(
        x: Double,
        y: Double,
        maxResults: Int = Int.MAX_VALUE,
        maxDistance: Double = Double.POSITIVE_INFINITY,
        filter: IndexFilter? = null,
    ): IntArray {
        check(finished) { "Data not yet indexed: call finish()" }
        if (numItems == 0 || maxResults <= 0 || !(maxDistance >= 0)) return IntArray(0)
        val numItems4 = numItems * 4
        val nodeSize4 = nodeSize * 4
        val results = IntBuffer()
        val maxDistSquared = maxDistance * maxDistance
        val q = FlatQueue()

        // With one result wanted, the closest leaf so far bounds the search.
        val trackNearest = maxResults == 1
        var bound = maxDistSquared

        // Nodes and leaves share the queue: leaves are odd ids, nodes even.
        q.push((boxes.size - 4) shl 1, 0.0)

        while (q.size > 0) {
            val top = q.peek()
            if (top and 1 == 1) {
                q.pop()
                results.add(top shr 1)
                if (results.size == maxResults) break
                continue
            }
            q.pop()
            val nodeIndex = top shr 1
            val isLeafLevel = nodeIndex < numItems4
            val end = min(nodeIndex + nodeSize4, upperBound(nodeIndex))
            var p = nodeIndex
            while (p < end) {
                val dx = max(max(boxes[p] - x, x - boxes[p + 2]), 0.0)
                val dy = max(max(boxes[p + 1] - y, y - boxes[p + 3]), 0.0)
                val dist = dx * dx + dy * dy
                if (dist <= bound) {
                    val childIndex = indices[p shr 2]
                    if (isLeafLevel) {
                        if (filter == null || filter.accept(childIndex)) {
                            q.push((childIndex shl 1) or 1, dist)
                            if (trackNearest && dist < bound) bound = dist
                        }
                    } else {
                        q.push(childIndex shl 1, dist)
                    }
                }
                p += 4
            }
        }
        return results.toArray()
    }

    // The first level bound greater than value.
    private fun upperBound(value: Int): Int {
        var i = 0
        var j = levelBounds.size - 1
        while (i < j) {
            val m = (i + j) shr 1
            if (levelBounds[m] > value) j = m else i = m + 1
        }
        return levelBounds[i]
    }

    private companion object {
        // Quicksort that partially sorts the boxes by Hilbert value, down to node size.
        fun sort(values: IntArray, boxes: DoubleArray, indices: IntArray, left: Int, right: Int, nodeSize: Int) {
            val stack = IntBuffer()
            stack.add(left)
            stack.add(right)
            while (stack.size > 0) {
                val r = stack.pop()
                val l = stack.pop()
                if (r - l <= nodeSize && l / nodeSize >= r / nodeSize) continue

                val a = values[l]
                val b = values[(l + r) shr 1]
                val c = values[r]
                val pivot = if ((a > b) != (a > c)) a else if ((b < a) != (b < c)) b else c

                var i = l - 1
                var j = r + 1
                while (true) {
                    do i++ while (values[i] < pivot)
                    do j-- while (values[j] > pivot)
                    if (i >= j) break
                    swap(values, boxes, indices, i, j)
                }
                stack.add(l); stack.add(j); stack.add(j + 1); stack.add(r)
            }
        }

        fun swap(values: IntArray, boxes: DoubleArray, indices: IntArray, i: Int, j: Int) {
            val temp = values[i]
            values[i] = values[j]
            values[j] = temp
            val k = 4 * i
            val m = 4 * j
            for (o in 0 until 4) {
                val t = boxes[k + o]
                boxes[k + o] = boxes[m + o]
                boxes[m + o] = t
            }
            val e = indices[i]
            indices[i] = indices[j]
            indices[j] = e
        }

        /** Fast Hilbert curve index of a 16-bit (x, y), shifted into signed range so ints compare in curve order. */
        fun hilbert(x: Int, y: Int): Int {
            var a = x xor y
            var b = 0xFFFF xor a
            var c = 0xFFFF xor (x or y)
            var d = x and (y xor 0xFFFF)

            var aa = a or (b shr 1)
            var bb = (a shr 1) xor a
            var cc = c xor ((c shr 1) xor (b and (d shr 1)))
            var dd = d xor ((a and (c shr 1)) xor (d shr 1))

            a = (aa and (aa shr 2)) xor (bb and (bb shr 2))
            b = (aa and (bb shr 2)) xor (bb and ((aa xor bb) shr 2))
            c = cc xor ((aa and (cc shr 2)) xor (bb and (dd shr 2)))
            d = dd xor ((bb and (cc shr 2)) xor ((aa xor bb) and (dd shr 2)))

            aa = (a and (a shr 4)) xor (b and (b shr 4))
            bb = (a and (b shr 4)) xor (b and ((a xor b) shr 4))
            cc = c xor ((a and (c shr 4)) xor (b and (d shr 4)))
            dd = d xor ((b and (c shr 4)) xor ((a xor b) and (d shr 4)))

            c = cc xor ((aa and (cc shr 8)) xor (bb and (dd shr 8)))
            d = dd xor ((bb and (cc shr 8)) xor ((aa xor bb) and (dd shr 8)))

            c = c xor (c shr 1)
            d = d xor (d shr 1)
            a = x xor y
            b = d or (0xFFFF xor (a or c))

            a = (a or (a shl 8)) and 0x00FF00FF
            a = (a or (a shl 4)) and 0x0F0F0F0F
            a = (a or (a shl 2)) and 0x33333333
            a = (a or (a shl 1)) and 0x55555555

            b = (b or (b shl 8)) and 0x00FF00FF
            b = (b or (b shl 4)) and 0x0F0F0F0F
            b = (b or (b shl 2)) and 0x33333333
            b = (b or (b shl 1)) and 0x55555555

            return ((b shl 1) or a) xor Int.MIN_VALUE
        }
    }
}

/** A growable int array: the results of spatial queries, without boxing. */
internal class IntBuffer(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0; private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2 + 4)
        a[size++] = v
    }

    fun pop(): Int = a[--size]
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun toArray(): IntArray = a.copyOf(size)
}

/** A binary min-heap of int ids by double priority (FlatQueue). */
internal class FlatQueue {
    private var ids = IntArray(16)
    private var values = DoubleArray(16)
    var size = 0; private set

    fun push(id: Int, value: Double) {
        if (size == ids.size) {
            ids = ids.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        var p = size++
        while (p > 0) {
            val parent = (p - 1) shr 1
            val parentValue = values[parent]
            if (value >= parentValue) break
            ids[p] = ids[parent]
            values[p] = parentValue
            p = parent
        }
        ids[p] = id
        values[p] = value
    }

    fun peek(): Int = ids[0]

    fun pop(): Int {
        val top = ids[0]
        size--
        if (size > 0) {
            val id = ids[size]
            val value = values[size]
            ids[0] = id
            values[0] = value
            val halfLength = size shr 1
            var p = 0
            while (p < halfLength) {
                var left = (p shl 1) + 1
                val right = left + 1
                var bestIndex = ids[left]
                var bestValue = values[left]
                if (right < size) {
                    val rightValue = values[right]
                    if (rightValue < bestValue) {
                        left = right
                        bestIndex = ids[right]
                        bestValue = rightValue
                    }
                }
                if (bestValue >= value) break
                ids[p] = bestIndex
                values[p] = bestValue
                p = left
            }
            ids[p] = id
            values[p] = value
        }
        return top
    }
}
