package app.atlas.geo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The buffer around a line, traced as an iso-line of its distance field.
 *
 * Distances are sampled on a sparse square grid in Web Mercator, scaled per row to true metres (Mercator is conformal,
 * so this is exact at the corridor's scale anywhere on Earth). Only grid nodes near the line are stored, so memory
 * grows with the line's length, not its bounding box. Marching squares then trace the outline at the given distance,
 * which handles lines that cross, double back or loop: the union of all the round-capped pieces, with holes.
 *
 * Accuracy, with cells a quarter of the width: the outline sits within a few percent of the width along the line and
 * round its caps, and cuts across the notches where the buffers of two parts of the line meet by up to about half a
 * cell, so a point there up to about a tenth of the width beyond can count as inside.
 */
internal object Corridor {
    /** Grid nodes we aim to stay under; past it the cells grow from a quarter of the width towards the width. */
    private const val NODE_BUDGET = 600_000

    fun rings(line: Polyline, metres: Double): List<List<LatLon>> {
        require(metres > 0 && metres.isFinite()) { "Corridor width must be a positive number of metres" }
        if (line.points.isEmpty()) return emptyList()
        val pts = Shapes.simplify(line.points, metres * 0.05)
        val n = pts.size
        val xs = DoubleArray(n) { Mercator.x(pts[it].lon) }
        val ys = DoubleArray(n) { Mercator.y(pts[it].lat) }

        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        var maxAbsLat = 0.0
        var sumLat = 0.0
        for (i in 0 until n) {
            minX = min(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = max(maxY, ys[i])
            maxAbsLat = max(maxAbsLat, abs(pts[i].lat))
            sumLat += pts[i].lat
        }

        // A quarter of the width per cell; coarser on long lines so the grid stays within budget. Cells up to the
        // width itself still keep the outline in one piece, as every cell along the line has a corner inside.
        var cellMetres = metres / 4
        for (factor in doubleArrayOf(0.25, 0.5, 0.8)) {
            cellMetres = metres * factor
            if (line.length * 2 * (metres + 2 * cellMetres) / (cellMetres * cellMetres) <= NODE_BUDGET) break
        }
        val cell = cellMetres / Mercator.metresPerUnit(sumLat / n)
        val poleward = min(maxAbsLat + Geo.metresToDegreesLat(metres * 2), Mercator.MAX_LAT)
        val reach = metres / Mercator.metresPerUnit(poleward) + 2 * cell
        val x0 = minX - reach - cell
        val y0 = minY - reach - cell
        val cols = ceil((maxX - minX + 2 * reach + 2 * cell) / cell).toInt() + 2
        val rows = ceil((maxY - minY + 2 * reach + 2 * cell) / cell).toInt() + 2
        val rowK = DoubleArray(rows) { Mercator.metresPerUnit(Mercator.lat(y0 + it * cell)) }

        // Distances in metres at every node within the width plus two cells of the line.
        val field = LongFloatMap(max(64, (line.length / cellMetres * 8).toInt().coerceAtMost(1 shl 22)))
        val segments = max(n - 1, 1)
        for (s in 0 until segments) {
            val ax = xs[s]; val ay = ys[s]
            val bx = xs[min(s + 1, n - 1)]; val by = ys[min(s + 1, n - 1)]
            val rowLo = max(floor((min(ay, by) - reach - y0) / cell).toInt(), 0)
            val rowHi = min(ceil((max(ay, by) + reach - y0) / cell).toInt(), rows - 1)
            val dy = by - ay
            for (row in rowLo..rowHi) {
                val yr = y0 + row * cell
                val k = rowK[row]
                val r = metres / k + 2 * cell
                val tLo: Double
                val tHi: Double
                if (dy == 0.0) {
                    if (abs(ay - yr) > r) continue
                    tLo = 0.0; tHi = 1.0
                } else {
                    val t1 = (yr - r - ay) / dy
                    val t2 = (yr + r - ay) / dy
                    tLo = max(0.0, min(t1, t2))
                    tHi = min(1.0, max(t1, t2))
                    if (tLo > tHi) continue
                }
                val xa = ax + tLo * (bx - ax)
                val xb = ax + tHi * (bx - ax)
                val colLo = max(floor((min(xa, xb) - r - x0) / cell).toInt(), 0)
                val colHi = min(ceil((max(xa, xb) + r - x0) / cell).toInt(), cols - 1)
                val r2 = r * r
                for (col in colLo..colHi) {
                    val d2 = Shapes.sqSegmentDistance(x0 + col * cell, yr, ax, ay, bx, by)
                    if (d2 <= r2) field.putMin(nodeKey(col, row), (sqrt(d2) * k).toFloat())
                }
            }
        }

        // Marching squares: segments with the inside on their left, joined at shared cell-edge crossings.
        val level = metres.toFloat()
        val px = DoubleBuffer()
        val py = DoubleBuffer()
        val next = IntBuffer()
        val pointOf = LongIntMap(1024)
        val keys = LongArray(4)
        val inside = BooleanArray(4)
        val values = FloatArray(4)

        fun crossing(edge: Long): Int {
            val known = pointOf.get(edge)
            if (known >= 0) return known
            val col = (edge ushr 32).toInt()
            val row = ((edge and 0xFFFFFFFFL) ushr 1).toInt()
            val vertical = edge and 1L == 1L
            val va = field.get(nodeKey(col, row), INF)
            val vb = if (vertical) field.get(nodeKey(col, row + 1), INF) else field.get(nodeKey(col + 1, row), INF)
            val t = when {
                va == INF -> 1.0
                vb == INF -> 0.0
                else -> ((level - va) / (vb - va)).toDouble().coerceIn(0.0, 1.0)
            }
            px.add(x0 + (if (vertical) col.toDouble() else col + t) * cell)
            py.add(y0 + (if (vertical) row + t else row.toDouble()) * cell)
            next.add(-1)
            val index = px.size - 1
            pointOf.put(edge, index)
            return index
        }

        field.forEach { key, v00 ->
            val col = (key ushr 32).toInt()
            val row = key.toInt()
            values[0] = v00
            values[1] = field.get(nodeKey(col + 1, row), INF)
            values[2] = field.get(nodeKey(col + 1, row + 1), INF)
            values[3] = field.get(nodeKey(col, row + 1), INF)
            var mask = 0
            for (c in 0 until 4) {
                inside[c] = values[c] <= level
                if (inside[c]) mask = mask or (1 shl c)
            }
            if (mask == 0 || mask == 15) return@forEach

            // Cell edges counter-clockwise: bottom, right, top, left; edge c runs from corner c to corner c + 1.
            keys[0] = edgeKey(col, row, false)
            keys[1] = edgeKey(col + 1, row, true)
            keys[2] = edgeKey(col, row + 1, false)
            keys[3] = edgeKey(col, row, true)
            val saddle = mask == 5 || mask == 10
            val centreInside = saddle && values.all { it != INF } && (values.sum() / 4) <= level
            for (c in 0 until 4) {
                if (!(inside[c] && !inside[(c + 1) and 3])) continue
                // From a leaving crossing to an entering one: the next counter-clockwise if the centre joins the
                // inside corners, else the next clockwise (the same edge for every non-saddle cell).
                var e = -1
                for (step in 1..3) {
                    val cand = if (!saddle || centreInside) (c + step) and 3 else (c - step) and 3
                    if (!inside[cand] && inside[(cand + 1) and 3]) { e = cand; break }
                }
                if (e < 0) continue
                val from = crossing(keys[c])
                val to = crossing(keys[e])
                next[from] = to
            }
        }

        // Join the segments into rings.
        val count = px.size
        val visited = BooleanArray(count)
        val raw = ArrayList<DoubleArray>()
        for (s in 0 until count) {
            if (visited[s] || next[s] < 0) continue
            val ring = DoubleBuffer()
            var cur = s
            while (cur >= 0 && !visited[cur]) {
                visited[cur] = true
                ring.add(px[cur])
                ring.add(py[cur])
                cur = next[cur]
            }
            if (ring.size >= 6) raw += Shapes.simplify(ring.toArray(), cell * 0.1)
        }

        // The outline is the largest ring; holes wind the other way. Outline counter-clockwise in lon/lat.
        val rings = raw.filter { it.size >= 6 }.map { r -> List(r.size / 2) { LatLon(Mercator.lat(r[2 * it + 1]), Mercator.lon(r[2 * it])) } }
        if (rings.isEmpty()) return emptyList()
        val areas = rings.map { Shapes.signedArea(it) }
        val outer = areas.indices.maxBy { abs(areas[it]) }
        val sign = if (areas[outer] >= 0) 1.0 else -1.0
        val out = ArrayList<List<LatLon>>()
        out += if (sign > 0) rings[outer] else rings[outer].reversed()
        // Every hole rings at least one grid node beyond the width, so each is real, but where the outline pinches in
        // the grid can leave a speck far smaller than a cell. Drop only those: a hole under a quarter of a cell's area
        // is under a third of a cell across. (Dropping holes of a few cells filled real gaps whose middles lay a sixth
        // of the width outside.)
        val minHole = 0.25 * cellMetres * cellMetres
        for (i in rings.indices) {
            if (i == outer || areas[i] * sign >= 0) continue
            if (Shapes.geodesicArea(rings[i]) < minHole) continue
            out += if (sign > 0) rings[i] else rings[i].reversed()
        }
        return out
    }

    private const val INF = Float.POSITIVE_INFINITY

    private fun nodeKey(col: Int, row: Int): Long = (col.toLong() shl 32) or row.toLong()

    private fun edgeKey(col: Int, row: Int, vertical: Boolean): Long =
        (col.toLong() shl 32) or (row.toLong() shl 1) or (if (vertical) 1L else 0L)
}
