package app.atlas.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator in unit coordinates: x and y run 0..1 across the world, y downwards (north at 0), as tiles count them.
 * Multiply by [CIRCUMFERENCE] for Mercator metres; divide those by `1 / cos(lat)` for true metres at that latitude.
 */
object Mercator {
    const val CIRCUMFERENCE = 40_075_016.68557849
    const val MAX_LAT = 85.05112878

    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }
    fun lon(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = atan(kotlin.math.sinh(PI * (1 - 2 * y))) * 180 / PI

    /** True metres covered by one unit of Mercator x at [lat]. */
    fun metresPerUnit(lat: Double): Double = CIRCUMFERENCE * cos(lat * PI / 180)

    /** Ground metres per pixel for 256-pixel tiles at fractional [zoom]. */
    fun metresPerPixel(lat: Double, zoom: Double): Double = metresPerUnit(lat) / (256.0 * exp(zoom * ln(2.0)))

    fun tileAt(p: LatLon, z: Int): TileId {
        val n = 1 shl z
        val tx = floor(x(p.lon) * n).toInt().coerceIn(0, n - 1)
        val ty = floor(y(p.lat) * n).toInt().coerceIn(0, n - 1)
        return TileId(z, tx, ty)
    }

    /** Every tile of zoom [z] touching [box]. */
    fun tilesIn(box: BBox, z: Int): List<TileId> {
        val a = tileAt(LatLon(box.north, box.west), z)
        val b = tileAt(LatLon(box.south, box.east), z)
        val out = ArrayList<TileId>((b.x - a.x + 1) * (b.y - a.y + 1))
        for (tx in a.x..b.x) for (ty in a.y..b.y) out += TileId(z, tx, ty)
        return out
    }
}

/** A slippy-map tile: zoom, column, row (row 0 at the north). */
data class TileId(val z: Int, val x: Int, val y: Int) {
    val bbox: BBox
        get() {
            val n = (1 shl z).toDouble()
            return BBox(Mercator.lon(x / n), Mercator.lat((y + 1) / n), Mercator.lon((x + 1) / n), Mercator.lat(y / n))
        }

    /** Unit-Mercator coordinates of the tile's north-west corner and its size. */
    val unitX: Double get() = x.toDouble() / (1 shl z)
    val unitY: Double get() = y.toDouble() / (1 shl z)
    val unitSize: Double get() = 1.0 / (1 shl z)

    fun parent(): TileId? = if (z == 0) null else TileId(z - 1, x / 2, y / 2)
    fun ancestor(atZoom: Int): TileId = if (atZoom >= z) this else TileId(atZoom, x shr (z - atZoom), y shr (z - atZoom))
    fun children(): List<TileId> = listOf(
        TileId(z + 1, 2 * x, 2 * y), TileId(z + 1, 2 * x + 1, 2 * y),
        TileId(z + 1, 2 * x, 2 * y + 1), TileId(z + 1, 2 * x + 1, 2 * y + 1),
    )

    override fun toString() = "$z/$x/$y"
}
