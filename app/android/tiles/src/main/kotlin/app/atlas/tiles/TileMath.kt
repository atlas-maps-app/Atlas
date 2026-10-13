package app.atlas.tiles

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * What a camera sees: [centerX], [centerY] in unit Mercator, fractional [zoom] (256-pixel tiles), [bearing] in degrees
 * clockwise from north, [pitch] in degrees from straight down, a viewport of [width] × [height] pixels and a vertical
 * field of view [fovY] in degrees. [farFactor] limits how far ahead a pitched view reaches, in viewport heights.
 */
data class CameraView(
    val centerX: Double,
    val centerY: Double,
    val zoom: Double,
    val width: Double,
    val height: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
    val fovY: Double = 36.87,
    val farFactor: Double = 4.0,
) {
    companion object {
        fun at(center: LatLon, zoom: Double, width: Double, height: Double, bearing: Double = 0.0, pitch: Double = 0.0) =
            CameraView(Mercator.x(center.lon), Mercator.y(center.lat), zoom, width, height, bearing, pitch)
    }
}

/** The tiles to draw: [tiles] nearest the centre first, and [parents] to draw while they load. */
class TileCover(val zoom: Int, val tiles: List<TileId>, val parents: List<TileId>)

/** Tile coverage: which tiles a view needs, in the order to fetch them. */
object TileMath {
    /** The integer zoom of tiles to request for a camera at fractional [zoom], overzooming past [maxZoom]. */
    fun tileZoom(zoom: Double, maxZoom: Int = 14, minZoom: Int = 0): Int = floor(zoom + 1e-6).toInt().coerceIn(minZoom, maxZoom)

    /**
     * The ground a camera sees as a quadrilateral in unit Mercator, interleaved x, y, in order: bottom-left,
     * bottom-right, top-right, top-left of the screen. A pitched view is cut off at [CameraView.farFactor] viewport
     * heights ahead of the centre (and below the horizon).
     */
    fun footprint(c: CameraView): DoubleArray {
        val p = c.pitch.coerceIn(0.0, 89.0) * PI / 180
        val d = c.height / 2 / tan(c.fovY * PI / 360)
        val sinP = sin(p)
        val cosP = cos(p)
        val halfW = c.width / 2
        val halfH = c.height / 2
        val maxAhead = c.farFactor * c.height
        // Screen y (up from the centre, pixels) to ground distance ahead of the centre; only y decides it.
        fun ahead(sy: Double): Double = -d * sinP + d * cosP * (d * sinP + sy * cosP) / (d * cosP - sy * sinP)
        fun scale(sy: Double): Double = d * cosP / (d * cosP - sy * sinP)
        var top = halfH
        if (d * cosP - top * sinP <= 1e-9 || ahead(top) > maxAhead) {
            val y = maxAhead + d * sinP
            top = d * cosP * (y - d * sinP) / (d * cosP * cosP + y * sinP)
        }
        val bottom = -halfH
        val worldSize = 256.0 * Math.pow(2.0, c.zoom)
        val b = c.bearing * PI / 180
        val fx = sin(b); val fy = -cos(b) // Screen up on the map (unit Mercator y runs south).
        val rx = cos(b); val ry = sin(b)
        val out = DoubleArray(8)
        fun put(i: Int, sx: Double, sy: Double) {
            val gx = sx * scale(sy)
            val gy = ahead(sy)
            out[2 * i] = c.centerX + (gx * rx + gy * fx) / worldSize
            out[2 * i + 1] = c.centerY + (gx * ry + gy * fy) / worldSize
        }
        put(0, -halfW, bottom)
        put(1, halfW, bottom)
        put(2, halfW, top)
        put(3, -halfW, top)
        return out
    }

    /** The tiles of zoom [z] a camera needs, nearest its centre first, with their parents. */
    fun cover(c: CameraView, z: Int, parentLevels: Int = 1): TileCover {
        val tiles = cover(footprint(c), z, c.centerX, c.centerY)
        return TileCover(z, tiles, parents(tiles, parentLevels))
    }

    /**
     * Tiles of zoom [z] touching the polygon [polygon] (unit Mercator, interleaved x, y; convex or not), sorted by distance
     * from [centerX], [centerY]. Each tile row takes the polygon's extent within that row, so concave shapes are covered
     * generously.
     */
    fun cover(polygon: DoubleArray, z: Int, centerX: Double, centerY: Double): List<TileId> {
        require(polygon.size >= 6 && polygon.size % 2 == 0) { "a polygon needs three or more points" }
        val n = 1 shl z
        val nd = n.toDouble()
        val count = polygon.size / 2
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (i in 0 until count) {
            minY = min(minY, polygon[2 * i + 1])
            maxY = max(maxY, polygon[2 * i + 1])
        }
        val row0 = floor(minY * nd).toInt().coerceIn(0, n - 1)
        val row1 = (ceil(maxY * nd).toInt() - 1).coerceIn(row0, n - 1)
        val keys = ArrayList<Long>()
        for (row in row0..row1) {
            val top = row / nd
            val bottom = (row + 1) / nd
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (i in 0 until count) {
                val j = (i + 1) % count
                val x1 = polygon[2 * i]; val y1 = polygon[2 * i + 1]
                val x2 = polygon[2 * j]; val y2 = polygon[2 * j + 1]
                val ya = min(y1, y2)
                val yb = max(y1, y2)
                if (yb < top || ya > bottom) continue
                val c0 = max(ya, top)
                val c1 = min(yb, bottom)
                if (y1 == y2) {
                    lo = min(lo, min(x1, x2)); hi = max(hi, max(x1, x2))
                } else {
                    val xa = x1 + (x2 - x1) * (c0 - y1) / (y2 - y1)
                    val xb = x1 + (x2 - x1) * (c1 - y1) / (y2 - y1)
                    lo = min(lo, min(xa, xb)); hi = max(hi, max(xa, xb))
                }
            }
            // Nothing in this row, or all of it beyond the world's west or east edge (no wrapping across the antimeridian).
            if (lo > hi || hi <= 0.0 || lo >= 1.0) continue
            val col0 = floor(lo * nd).toInt().coerceIn(0, n - 1)
            val col1 = (ceil(hi * nd).toInt() - 1).coerceIn(col0, n - 1)
            for (col in col0..col1) keys += (col.toLong() shl 32) or row.toLong()
        }
        val cx = centerX * nd
        val cy = centerY * nd
        val sorted = keys.sortedWith(compareBy<Long> {
            val dx = (it ushr 32) + 0.5 - cx
            val dy = (it and 0xffffffffL) + 0.5 - cy
            dx * dx + dy * dy
        }.thenBy { it and 0xffffffffL }.thenBy { it ushr 32 })
        return sorted.map { TileId(z, (it ushr 32).toInt(), (it and 0xffffffffL).toInt()) }
    }

    /** Tiles of zoom [z] covering a polygon of points on Earth, nearest [center] first. */
    fun cover(polygon: List<LatLon>, z: Int, center: LatLon): List<TileId> {
        val flat = DoubleArray(polygon.size * 2)
        for ((i, p) in polygon.withIndex()) {
            flat[2 * i] = Mercator.x(p.lon)
            flat[2 * i + 1] = Mercator.y(p.lat)
        }
        return cover(flat, z, Mercator.x(center.lon), Mercator.y(center.lat))
    }

    /** The distinct ancestors [levels] zooms up of [tiles], in the order their children first appear. */
    fun parents(tiles: List<TileId>, levels: Int = 1): List<TileId> {
        if (tiles.isEmpty() || levels <= 0) return emptyList()
        val out = LinkedHashSet<TileId>()
        for (t in tiles) if (t.z - levels >= 0) out += t.ancestor(t.z - levels)
        return out.toList()
    }
}
