package app.atlas.rendertiles.model

import app.atlas.render.data.PackTiles

/**
 * OSM's 1e-7 degree grid, on which a tile stores every coordinate, and a frame's metres computed from it exactly as the
 * port computes them (`amsterdam.py`'s `XZ`).
 *
 * The port reads a coordinate as the double `q / 1e7` of its grid integer q (the pack stores only such values), then
 * `x = (lon − lon0) · kx` and `z = −(lat − lat0) · kz`; [x] and [z] do exactly that, so a vertex decoded from a tile is
 * the scene's vertex bit for bit. [latOf] and [lonOf] invert a frame's metres back onto the grid, exactly or not at all.
 */
object Grid {
    /** Grid units per degree. */
    const val SCALE = 1e7

    /** The degrees of grid integer [q]: a division, as the pack's reader makes them. */
    fun deg(q: Int): Double = q / SCALE

    /** The frame's x (metres east of its origin) of grid longitude [q]. */
    fun x(q: Int, lon0: Double, kx: Double): Double = (deg(q) - lon0) * kx

    /** The frame's z (metres south of its origin) of grid latitude [q]. */
    fun z(q: Int, lat0: Double, kz: Double): Double = -(deg(q) - lat0) * kz

    /** The grid latitude whose [z] is exactly [z], or null when no grid latitude gives it. */
    fun latOf(z: Double, lat0: Double, kz: Double): Int? {
        val guess = Math.round((lat0 - z / kz) * SCALE)
        for (d in SEARCH) {
            val q = guess + d
            if (q < Int.MIN_VALUE || q > Int.MAX_VALUE) continue
            if (z(q.toInt(), lat0, kz) == z && (z != 0.0 || 1.0 / z(q.toInt(), lat0, kz) == 1.0 / z)) return q.toInt()
        }
        return null
    }

    /** The grid longitude whose [x] is exactly [x], or null when no grid longitude gives it. */
    fun lonOf(x: Double, lon0: Double, kx: Double): Int? {
        val guess = Math.round((lon0 + x / kx) * SCALE)
        for (d in SEARCH) {
            val q = guess + d
            if (q < Int.MIN_VALUE || q > Int.MAX_VALUE) continue
            if (x(q.toInt(), lon0, kx) == x && (x != 0.0 || 1.0 / x(q.toInt(), lon0, kx) == 1.0 / x)) return q.toInt()
        }
        return null
    }

    /** The grid integer nearest [deg] degrees (for derived points the renders compute off the grid). */
    fun round(deg: Double): Int = Math.round(deg * SCALE).toInt()

    private val SEARCH = longArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)

    /**
     * A tile's bounds on the grid: [south, west, north, east], each the grid integer nearest the tile's edge. Neighbouring
     * tiles share their edges exactly, so clipped pieces meet without gaps.
     */
    fun tileBox(z: Int, x: Int, y: Int): IntArray = intArrayOf(rowEdge(z, y + 1), colEdge(z, x), rowEdge(z, y), colEdge(z, x + 1))

    /** The grid longitude of column [x]'s west edge at zoom [z]. */
    fun colEdge(z: Int, x: Int): Int = round(x.toDouble() / (1 shl z) * 360.0 - 180.0)

    /** The grid latitude of row [y]'s north edge at zoom [z] (Web Mercator, [StrictMath] as [PackTiles] computes tiles). */
    fun rowEdge(z: Int, y: Int): Int {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
        return round(Math.toDegrees(StrictMath.atan(StrictMath.sinh(n))))
    }

    /** The column at zoom [z] whose grid edges hold grid longitude [q]: west edge ≤ q < east edge. */
    fun col(q: Int, z: Int): Int {
        val n = 1 shl z
        var x = PackTiles.tileX(deg(q), z)
        while (x > 0 && q < colEdge(z, x)) x--
        while (x < n - 1 && q >= colEdge(z, x + 1)) x++
        return x
    }

    /** The row at zoom [z] whose grid edges hold grid latitude [q]: south edge ≤ q < north edge. */
    fun row(q: Int, z: Int): Int {
        val n = 1 shl z
        var y = PackTiles.tileY(deg(q), z)
        while (y > 0 && q >= rowEdge(z, y)) y--
        while (y < n - 1 && q < rowEdge(z, y + 1)) y++
        return y
    }
}
