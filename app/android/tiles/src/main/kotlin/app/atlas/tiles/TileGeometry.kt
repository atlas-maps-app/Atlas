package app.atlas.tiles

import app.atlas.geo.LatLon
import app.atlas.geo.TileId

/** The kind of a vector tile feature's geometry. */
enum class GeomType { POINT, LINE, POLYGON }

/**
 * A feature's geometry in tile units (0..extent, y down, with the tile's buffer beyond), as flat arrays.
 *
 * - [coords] holds x, y pairs; point i is `coords[2i], coords[2i+1]`.
 * - [parts] holds point offsets: part p spans points `parts[p] until parts[p+1]`. Points form a single part;
 *   each line is a part; each polygon ring is a part, explicitly closed (its last point repeats its first).
 * - [polygons] (polygons only) holds part offsets: polygon j spans parts `polygons[j] until polygons[j+1]`,
 *   the first its exterior ring and the rest its holes, grouped by winding order as the MVT 2.1 spec says.
 */
class TileGeometry(val type: GeomType, val coords: IntArray, val parts: IntArray, val polygons: IntArray) {
    val pointCount: Int get() = coords.size / 2
    val partCount: Int get() = parts.size - 1
    val polygonCount: Int get() = if (polygons.isEmpty()) 0 else polygons.size - 1
    val isEmpty: Boolean get() = coords.isEmpty()

    fun x(i: Int): Int = coords[2 * i]
    fun y(i: Int): Int = coords[2 * i + 1]
    fun partStart(p: Int): Int = parts[p]
    fun partEnd(p: Int): Int = parts[p + 1]
    fun partSize(p: Int): Int = parts[p + 1] - parts[p]

    /** True when part [p] is a polygon's exterior ring. */
    fun isExterior(p: Int): Boolean =
        polygons.size > 1 && java.util.Arrays.binarySearch(polygons, 0, polygons.size - 1, p) >= 0

    /** Twice the signed area of ring [p] in tile units², positive for clockwise on screen (y down), the spec's exterior. */
    fun ringArea2(p: Int): Long {
        var a = 0L
        val c = coords
        for (i in parts[p] until parts[p + 1] - 1) {
            val j = 2 * i
            a += c[j].toLong() * c[j + 3] - c[j + 2].toLong() * c[j + 1]
        }
        return a
    }

    /** Area of polygon [j] (exterior minus holes) in tile units². */
    fun polygonArea(j: Int): Double {
        var a = 0L
        for (p in polygons[j] until polygons[j + 1]) a += kotlin.math.abs(ringArea2(p)) * (if (p == polygons[j]) 1 else -1)
        return a / 2.0
    }

    /** [minX, minY, maxX, maxY] in tile units, or null when empty. */
    fun bounds(): IntArray? {
        if (coords.isEmpty()) return null
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
        var i = 0
        while (i < coords.size) {
            val x = coords[i]; val y = coords[i + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            i += 2
        }
        return intArrayOf(x0, y0, x1, y1)
    }

    inline fun forEachPoint(block: (x: Int, y: Int) -> Unit) {
        var i = 0
        while (i < coords.size) {
            block(coords[i], coords[i + 1])
            i += 2
        }
    }

    /** Calls [block] with each part's index and its point range `start until end`. */
    inline fun forEachPart(block: (part: Int, start: Int, end: Int) -> Unit) {
        for (p in 0 until parts.size - 1) block(p, parts[p], parts[p + 1])
    }

    /** Calls [block] with each polygon's index and its part range `firstPart until endPart` (exterior first). */
    inline fun forEachPolygon(block: (polygon: Int, firstPart: Int, endPart: Int) -> Unit) {
        for (j in 0 until polygons.size - 1) block(j, polygons[j], polygons[j + 1])
    }

    /** All coordinates in unit Mercator (0..1, y down) for a tile [tile] of [extent], interleaved x, y. */
    fun toUnitMercator(tile: TileId, extent: Int): DoubleArray {
        val k = tile.unitSize / extent
        val ox = tile.unitX
        val oy = tile.unitY
        val out = DoubleArray(coords.size)
        var i = 0
        while (i < coords.size) {
            out[i] = ox + coords[i] * k
            out[i + 1] = oy + coords[i + 1] * k
            i += 2
        }
        return out
    }

    /** Each part as a list of points on Earth. */
    fun toLatLon(tile: TileId, extent: Int): List<List<LatLon>> {
        val proj = TileProjection(tile, extent)
        return List(partCount) { p -> List(partSize(p)) { k -> val i = parts[p] + k; proj.latLon(x(i).toDouble(), y(i).toDouble()) } }
    }

    override fun toString(): String = "TileGeometry($type, points=$pointCount, parts=$partCount, polygons=$polygonCount)"

    companion object {
        private val NONE = IntArray(0)
        private val ZERO = intArrayOf(0)

        fun empty(type: GeomType) = TileGeometry(type, NONE, ZERO, if (type == GeomType.POLYGON) ZERO else NONE)
    }
}
