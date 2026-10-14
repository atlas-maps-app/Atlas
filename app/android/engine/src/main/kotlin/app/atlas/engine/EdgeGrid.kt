package app.atlas.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Line segments (metres) on a uniform grid, for "how far is the nearest one, up to a limit?" questions: a chart's
 * water shades by distance from the bank without measuring every bank edge for every point. [edges] holds
 * ax, ay, bx, by per segment; cells are [cellSize] metres, or coarser for segments spread very wide.
 */
internal class EdgeGrid(edges: DoubleArray, cellSize: Double) {
    private val n = edges.size / 4
    private val cell: Double
    private val ax = DoubleArray(n)
    private val ay = DoubleArray(n)
    private val bx = DoubleArray(n)
    private val by = DoubleArray(n)
    private val ox: Double
    private val oy: Double
    private val cols: Int
    private val rows: Int
    private val start: IntArray
    private val items: IntArray

    init {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (e in 0 until n) {
            ax[e] = edges[4 * e]; ay[e] = edges[4 * e + 1]; bx[e] = edges[4 * e + 2]; by[e] = edges[4 * e + 3]
            x0 = min(x0, min(ax[e], bx[e])); y0 = min(y0, min(ay[e], by[e]))
            x1 = max(x1, max(ax[e], bx[e])); y1 = max(y1, max(ay[e], by[e]))
        }
        if (n == 0) { x0 = 0.0; y0 = 0.0; x1 = 0.0; y1 = 0.0 }
        ox = x0; oy = y0
        // At most 512 cells a side, however far the segments spread (a low-zoom tile spans hundreds of kilometres).
        cell = max(cellSize, max(x1 - x0, y1 - y0) / 512)
        cols = ((x1 - x0) / cell).toInt() + 1
        rows = ((y1 - y0) / cell).toInt() + 1
        val counts = IntArray(cols * rows + 1)
        for (k in 0 until n) forCells(k) { counts[it]++ }
        start = IntArray(cols * rows + 1)
        for (c in 0 until cols * rows) start[c + 1] = start[c] + counts[c]
        items = IntArray(start[cols * rows])
        val fill = start.copyOf()
        for (k in 0 until n) forCells(k) { items[fill[it]++] = k }
    }

    private inline fun forCells(k: Int, block: (Int) -> Unit) {
        val c0 = col(min(ax[k], bx[k])); val c1 = col(max(ax[k], bx[k]))
        val r0 = row(min(ay[k], by[k])); val r1 = row(max(ay[k], by[k]))
        for (r in r0..r1) for (c in c0..c1) block(r * cols + c)
    }

    private fun col(x: Double) = floor((x - ox) / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Double) = floor((y - oy) / cell).toInt().coerceIn(0, rows - 1)

    /** Distance from ([x], [y]) to the nearest edge, or [limit] when none is nearer than that. */
    fun distance(x: Double, y: Double, limit: Double): Double {
        nearest(x, y, limit)
        return lastDistance
    }

    /** The distance [nearest] found last. */
    var lastDistance = 0.0
        private set

    /** The edge nearest to ([x], [y]) within [limit], or -1; its distance goes to [lastDistance]. */
    fun nearest(x: Double, y: Double, limit: Double): Int {
        // Cells wholly beyond the limit cannot hold a nearer edge; clamping to the grid keeps edge cells in reach.
        val c0 = floor((x - limit - ox) / cell).toInt().coerceAtLeast(0)
        val c1 = floor((x + limit - ox) / cell).toInt().coerceAtMost(cols - 1)
        val r0 = floor((y - limit - oy) / cell).toInt().coerceAtLeast(0)
        val r1 = floor((y + limit - oy) / cell).toInt().coerceAtMost(rows - 1)
        var best = limit
        var which = -1
        for (r in r0..r1) for (c in c0..c1) {
            val i = r * cols + c
            for (k in start[i] until start[i + 1]) {
                val e = items[k]
                val d = Rings.segmentDistance(x, y, ax[e], ay[e], bx[e], by[e])
                if (d < best) { best = d; which = e }
            }
        }
        lastDistance = best
        return which
    }

    /** Edge [e]'s unit direction into [out] (x, y). */
    fun direction(e: Int, out: DoubleArray) {
        val dx = bx[e] - ax[e]; val dy = by[e] - ay[e]
        val l = kotlin.math.hypot(dx, dy)
        if (l > 0) { out[0] = dx / l; out[1] = dy / l } else { out[0] = 1.0; out[1] = 0.0 }
    }
}
