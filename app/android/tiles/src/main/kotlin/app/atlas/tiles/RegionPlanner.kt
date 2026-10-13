package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import kotlin.math.pow

/** How much a region holds, for size estimates: a dense old city centre down to open country. */
enum class Density(val factor: Double) { DENSE_CITY(1.0), CITY(0.55), MIXED(0.3), RURAL(0.1) }

/** The tiles of one zoom in a plan: a rectangle of columns and rows, or an explicit sorted set (`x shl 32 or y`). */
class ZoomTiles internal constructor(val z: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, private val keys: LongArray?) {
    val count: Long get() = keys?.size?.toLong() ?: ((x1 - x0 + 1).toLong() * (y1 - y0 + 1))

    operator fun contains(id: TileId): Boolean {
        if (id.z != z || id.x !in x0..x1 || id.y !in y0..y1) return false
        return keys == null || java.util.Arrays.binarySearch(keys, (id.x.toLong() shl 32) or id.y.toLong()) >= 0
    }

    /** Tiles row by row. */
    fun tiles(): Sequence<TileId> =
        if (keys != null) keys.asSequence().map { TileId(z, (it ushr 32).toInt(), (it and 0xffffffffL).toInt()) }
        else sequence { for (y in y0..y1) for (x in x0..x1) yield(TileId(z, x, y)) }
}

/** An offline region to download: its area, zooms, tile count and estimated size (stored gzipped). */
class RegionPlan internal constructor(val bbox: BBox, val minZoom: Int, val maxZoom: Int, val zooms: List<ZoomTiles>, val density: Density) {
    val tileCount: Long get() = zooms.sumOf { it.count }

    /** Estimated bytes once downloaded and stored gzipped. */
    val estimatedBytes: Long get() = zooms.sumOf { it.count * RegionPlanner.typicalBytes(it.z, density) }

    fun tilesAt(z: Int): ZoomTiles? = zooms.firstOrNull { it.z == z }

    operator fun contains(id: TileId): Boolean = tilesAt(id.z)?.contains(id) == true

    /** Every tile, low zooms first (so a partial download is already a usable map). */
    fun tiles(): Sequence<TileId> = zooms.asSequence().flatMap { it.tiles() }

    override fun toString(): String = "RegionPlan($bbox z$minZoom-$maxZoom, $tileCount tiles, ~${estimatedBytes / 1024} KB)"
}

/** Plans offline regions and estimates their size. */
object RegionPlanner {
    /**
     * Typical stored (gzipped) bytes of an OpenMapTiles tile per zoom in a dense city, measured on the Amsterdam fixtures
     * (z0 47 KB, z6 218 KB, z10 145 KB, z12 149 KB, z13 139 KB, z14 247–534 KB, mean 359 KB); zooms between are interpolated.
     */
    private val denseBytes = longArrayOf(
        48_000, 90_000, 130_000, 170_000, 200_000, 210_000, 218_000, 200_000,
        180_000, 160_000, 145_000, 147_000, 149_000, 139_000, 359_000,
    )

    /** Typical stored size of a tile at [z]: low zooms cover so much ground that they barely depend on density. */
    fun typicalBytes(z: Int, density: Density = Density.CITY): Long {
        val base = denseBytes[z.coerceIn(0, denseBytes.size - 1)]
        val weight = ((z - 8) / 6.0).coerceIn(0.0, 1.0)
        return (base * density.factor.pow(weight)).toLong()
    }

    /** The tiles of [bbox] at zooms [minZoom]..[maxZoom]. */
    fun plan(bbox: BBox, minZoom: Int = 0, maxZoom: Int = 14, density: Density = Density.CITY): RegionPlan {
        require(minZoom in 0..maxZoom && maxZoom <= 22) { "zooms $minZoom..$maxZoom" }
        val zooms = (minZoom..maxZoom).map { z ->
            val a = Mercator.tileAt(LatLon(bbox.north, bbox.west), z)
            val b = Mercator.tileAt(LatLon(bbox.south, bbox.east), z)
            ZoomTiles(z, a.x, a.y, b.x, b.y, null)
        }
        return RegionPlan(bbox, minZoom, maxZoom, zooms, density)
    }

    /** The tiles within [radiusMetres] of a line (a route to ride offline), at zooms [minZoom]..[maxZoom]. */
    fun planCorridor(line: List<LatLon>, radiusMetres: Double, minZoom: Int = 0, maxZoom: Int = 14, density: Density = Density.MIXED): RegionPlan {
        require(line.isNotEmpty()) { "an empty line" }
        require(minZoom in 0..maxZoom && maxZoom <= 22) { "zooms $minZoom..$maxZoom" }
        val bbox = BBox.of(line)!!.buffer(radiusMetres)
        val zooms = (minZoom..maxZoom).map { z -> corridorAt(line, radiusMetres, z) }
        return RegionPlan(bbox, minZoom, maxZoom, zooms, density)
    }

    private fun corridorAt(line: List<LatLon>, radius: Double, z: Int): ZoomTiles {
        val keys = HashSet<Long>()
        val n = 1 shl z
        for (i in line.indices) {
            val a = line[i]
            val b = if (i + 1 < line.size) line[i + 1] else a
            // A segment's buffer, as the tiles of its box, thinned to those near the segment.
            val box = BBox.of(listOf(a, b))!!.buffer(radius)
            val t0 = Mercator.tileAt(LatLon(box.north, box.west), z)
            val t1 = Mercator.tileAt(LatLon(box.south, box.east), z)
            val frame = LocalFrame(a)
            val bx = frame.x(b.lon); val by = frame.y(b.lat)
            for (x in t0.x..t1.x) for (y in t0.y..t1.y) {
                val t = TileId(z, x, y)
                if (segmentNearTile(frame, bx, by, t, radius)) keys += (x.toLong() shl 32) or y.toLong()
            }
        }
        val sorted = keys.toLongArray().also { it.sort() }
        var x0 = n; var y0 = n; var x1 = -1; var y1 = -1
        for (k in sorted) {
            val x = (k ushr 32).toInt(); val y = (k and 0xffffffffL).toInt()
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
        return ZoomTiles(z, x0, y0, x1, y1, sorted)
    }

    /** True when the segment from the frame origin to (bx, by) passes within [radius] metres of tile [t]. */
    private fun segmentNearTile(f: LocalFrame, bx: Double, by: Double, t: TileId, radius: Double): Boolean {
        val box = t.bbox
        val rx0 = f.x(box.west) - radius; val rx1 = f.x(box.east) + radius
        val ry0 = f.y(box.south) - radius; val ry1 = f.y(box.north) + radius
        // Liang–Barsky: does the segment meet the grown rectangle?
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx
        val dy = by
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(-rx0, rx1, -ry0, ry1)
        for (k in 0 until 4) {
            if (p[k] == 0.0) {
                if (q[k] < 0) return false
            } else {
                val r = q[k] / p[k]
                if (p[k] < 0) { if (r > t1) return false; if (r > t0) t0 = r }
                else { if (r < t0) return false; if (r < t1) t1 = r }
            }
        }
        return true
    }
}
