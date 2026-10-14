package app.atlas.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Whether sunlight reaches a point: a ray marched toward the sun over a grid of building prisms (full height, as the
 * living map does: a gabled roof shades like a box up to its ridge). Built once per scene, camera-independent.
 */
class ShadowCaster(private val buildings: List<Building>, private val light: Light, private val cell: Double = 16.0) {
    private val n = buildings.size
    private val bx0 = DoubleArray(n); private val by0 = DoubleArray(n)
    private val bx1 = DoubleArray(n); private val by1 = DoubleArray(n)
    private val ox: Double
    private val oy: Double
    private val cols: Int
    private val rows: Int
    private val start: IntArray
    private val items: IntArray
    private val maxH: Double

    init {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var top = 0.0
        for (i in 0 until n) {
            val r = buildings[i].outer
            var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
            for (k in 0 until r.size / 2) {
                a = min(a, r[2 * k]); c = max(c, r[2 * k]); b = min(b, r[2 * k + 1]); d = max(d, r[2 * k + 1])
            }
            bx0[i] = a; by0[i] = b; bx1[i] = c; by1[i] = d
            x0 = min(x0, a); y0 = min(y0, b); x1 = max(x1, c); y1 = max(y1, d)
            top = max(top, buildings[i].height)
        }
        if (n == 0) { x0 = 0.0; y0 = 0.0; x1 = 1.0; y1 = 1.0 }
        ox = x0; oy = y0
        cols = max(1, ((x1 - x0) / cell).toInt() + 1)
        rows = max(1, ((y1 - y0) / cell).toInt() + 1)
        maxH = top
        val counts = IntArray(cols * rows + 1)
        for (i in 0 until n) forCells(i) { counts[it]++ }
        start = IntArray(cols * rows + 1)
        for (c in 0 until cols * rows) start[c + 1] = start[c] + counts[c]
        items = IntArray(start[cols * rows])
        val fill = start.copyOf()
        for (i in 0 until n) forCells(i) { items[fill[it]++] = i }
    }

    private inline fun forCells(i: Int, block: (Int) -> Unit) {
        val c0 = ((bx0[i] - ox) / cell).toInt().coerceIn(0, cols - 1)
        val c1 = ((bx1[i] - ox) / cell).toInt().coerceIn(0, cols - 1)
        val r0 = ((by0[i] - oy) / cell).toInt().coerceIn(0, rows - 1)
        val r1 = ((by1[i] - oy) / cell).toInt().coerceIn(0, rows - 1)
        for (r in r0..r1) for (c in c0..c1) block(r * cols + c)
    }

    /** True when the sun reaches ([x], [y], [z]); building [skip] (its own) is ignored. False whenever the sun is down. */
    fun lit(x: Double, y: Double, z: Double, skip: Int = -1, reach: Double = 220.0): Boolean {
        if (!light.isUp || light.z <= 0f) return false
        val lx = light.x.toDouble(); val ly = light.y.toDouble(); val lz = light.z.toDouble()
        val tMax = min(reach, (maxH - z) / lz)
        var t = 0.8
        while (t < tMax) {
            val qx = x + lx * t
            val qy = y + ly * t
            val qz = z + lz * t
            val c = floor((qx - ox) / cell).toInt()
            val r = floor((qy - oy) / cell).toInt()
            if (c in 0 until cols && r in 0 until rows) {
                val k = r * cols + c
                for (j in start[k] until start[k + 1]) {
                    val b = items[j]
                    if (b == skip) continue
                    val bb = buildings[b]
                    if (qz > bb.height || qz < bb.minHeight) continue
                    if (qx < bx0[b] || qx > bx1[b] || qy < by0[b] || qy > by1[b]) continue
                    if (Rings.contains(bb.rings, qx, qy)) return false
                }
            }
            t += 1.6
        }
        return true
    }

    /** Index of the building containing ground point ([x], [y]), or -1. */
    fun buildingAt(x: Double, y: Double): Int {
        val c = floor((x - ox) / cell).toInt()
        val r = floor((y - oy) / cell).toInt()
        if (c !in 0 until cols || r !in 0 until rows) return -1
        val k = r * cols + c
        for (j in start[k] until start[k + 1]) {
            val b = items[j]
            if (x < bx0[b] || x > bx1[b] || y < by0[b] || y > by1[b]) continue
            if (Rings.contains(buildings[b].rings, x, y)) return b
        }
        return -1
    }
}
