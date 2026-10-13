/*
 * The pole of inaccessibility, after Mapbox's polylabel (https://github.com/mapbox/polylabel).
 *
 * ISC License, Copyright (c) 2016 Mapbox
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
import kotlin.math.sqrt

/** Quadtree search for the interior point farthest from a polygon's edges. */
internal object Polylabel {
    private const val SQRT2 = 1.4142135623730951
    private const val MAX_CELLS = 256

    fun find(coords: DoubleArray, holeIndices: IntArray?, precision: Double): DoubleArray {
        val n = coords.size / 2
        if (n == 0) return doubleArrayOf(Double.NaN, Double.NaN, 0.0)
        val outerEnd = if (holeIndices != null && holeIndices.isNotEmpty()) holeIndices[0] else n
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until outerEnd) {
            minX = min(minX, coords[2 * i]); maxX = max(maxX, coords[2 * i])
            minY = min(minY, coords[2 * i + 1]); maxY = max(maxY, coords[2 * i + 1])
        }
        val width = maxX - minX
        val height = maxY - minY
        val shortSide = min(width, height)
        if (shortSide == 0.0) return doubleArrayOf(minX, minY, 0.0)
        // Square cells, but never more than MAX_CELLS along the long side: a sliver (a tile-clipped edge, say) would
        // otherwise start from millions of cells.
        val cellSize = max(shortSide, max(width, height) / MAX_CELLS)

        // Start from the centroid, or the box centre if that is better.
        val c = Shapes.centroid(coords.copyOf(outerEnd * 2)) ?: doubleArrayOf(coords[0], coords[1])
        var bestX = c[0]
        var bestY = c[1]
        var bestD = signedDistance(bestX, bestY, coords, holeIndices)
        val bx = minX + width / 2
        val by = minY + height / 2
        val bd = signedDistance(bx, by, coords, holeIndices)
        if (bd > bestD) { bestX = bx; bestY = by; bestD = bd }

        // A max-queue by the most a cell could still hold: its centre's distance plus its half-diagonal.
        val cells = Cells()
        val queue = FlatQueue()
        fun probe(x: Double, y: Double, h: Double) {
            val d = signedDistance(x, y, coords, holeIndices)
            val potential = d + h * SQRT2
            if (potential > bestD + precision) queue.push(cells.add(x, y, h, d), -potential)
            if (d > bestD) { bestX = x; bestY = y; bestD = d }
        }

        var h = cellSize / 2
        var x = minX
        while (x < maxX) {
            var y = minY
            while (y < maxY) {
                probe(x + h, y + h, h)
                y += cellSize
            }
            x += cellSize
        }

        while (queue.size > 0) {
            val id = queue.pop()
            val cx = cells.x[id]; val cy = cells.y[id]; val ch = cells.h[id]
            // The most promising cell cannot beat the best by more than the precision: neither can the rest.
            if (cells.d[id] + ch * SQRT2 - bestD <= precision) break
            h = ch / 2
            probe(cx - h, cy - h, h)
            probe(cx + h, cy - h, h)
            probe(cx - h, cy + h, h)
            probe(cx + h, cy + h, h)
        }
        return doubleArrayOf(bestX, bestY, bestD)
    }

    // Distance from (x, y) to the polygon outline: positive inside, negative outside.
    private fun signedDistance(x: Double, y: Double, coords: DoubleArray, holeIndices: IntArray?): Double {
        var inside = false
        var minD2 = Double.POSITIVE_INFINITY
        Shapes.forEachRing(coords.size / 2, holeIndices) { _, from, to ->
            var j = to - 1
            for (i in from until to) {
                val ax = coords[2 * i]; val ay = coords[2 * i + 1]
                val bx = coords[2 * j]; val by = coords[2 * j + 1]
                if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
                minD2 = min(minD2, Shapes.sqSegmentDistance(x, y, ax, ay, bx, by))
                j = i
            }
        }
        return if (minD2 == 0.0) 0.0 else (if (inside) 1 else -1) * sqrt(minD2)
    }

    private class Cells {
        var x = DoubleArray(64); var y = DoubleArray(64); var h = DoubleArray(64); var d = DoubleArray(64)
        var size = 0

        fun add(cx: Double, cy: Double, ch: Double, cd: Double): Int {
            if (size == x.size) {
                val cap = size * 2
                x = x.copyOf(cap); y = y.copyOf(cap); h = h.copyOf(cap); d = d.copyOf(cap)
            }
            x[size] = cx; y[size] = cy; h[size] = ch; d[size] = cd
            return size++
        }
    }
}
