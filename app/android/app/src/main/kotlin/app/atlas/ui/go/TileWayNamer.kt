package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.route.WayNamer
import app.atlas.tiles.GeomType
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileProjection
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import kotlinx.coroutines.runBlocking
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Names the street under a route from the map tiles: BRouter's data has no names, the tiles' `transportation_name`
 * layer has every named street with its line, and the `waterway` layer every named canal and river for boats. The street along the way of travel wins (a side street that merely
 * touches the point does not); a ref ("A10", "s100") stands in where a road has no name. Decoded tiles are kept, a few
 * dozen, so a route asks each tile once. Thread-safe: the router asks from its own threads.
 */
class TileWayNamer(private val tiles: () -> TileSource?, private val language: String? = null) : WayNamer {
    /** One tile's named lines as segments in tile units, with each segment's name. */
    private class Lines(val proj: TileProjection, val seg: IntArray, val name: Array<String>, val metresPerUnit: Double)

    private val cache = object : LinkedHashMap<TileId, Lines>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, Lines>?) = size > MAX_TILES
    }

    override fun name(at: LatLon, bearing: Double): String? = find(at, bearing, NEAR_M)

    /** The nearest named street (or canal) within [withinM] of [at], whichever way it runs: "from Prinsengracht". */
    fun nearest(at: LatLon, withinM: Double = 120.0): String? = find(at, null, withinM)

    private fun find(at: LatLon, bearing: Double?, nearM: Double): String? {
        val src = tiles() ?: return null
        val id = Mercator.tileAt(at, ZOOM)
        val lines = synchronized(cache) { cache[id] } ?: load(src, id)?.also { l -> synchronized(cache) { cache[id] = l } } ?: return null
        val p = lines.proj.toTile(at)
        val px = p[0]
        val py = p[1]
        val reach = nearM / lines.metresPerUnit
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        val s = lines.seg
        var i = 0
        while (i < s.size) {
            val ax = s[i].toDouble(); val ay = s[i + 1].toDouble(); val bx = s[i + 2].toDouble(); val by = s[i + 3].toDouble()
            val k = i / 4
            i += 4
            val d = distance(px, py, ax, ay, bx, by)
            if (d > reach) continue
            val score = if (bearing == null) d else {
                // Tile y runs down: a segment's bearing is atan2(dx, -dy).
                val segBearing = Math.toDegrees(atan2(bx - ax, -(by - ay)))
                val diff = ((segBearing - bearing) % 180 + 180) % 180
                val off = minOf(diff, 180 - diff)
                // Along the way of travel counts most; a crossing street only when nothing runs along.
                d + if (off <= ALONG_DEG) 0.0 else reach * 2
            }
            if (score < bestScore) {
                bestScore = score
                best = lines.name[k]
            }
        }
        return best
    }

    private fun load(src: TileSource, id: TileId): Lines? = runBlocking {
        val hit = (src as? TileStack)?.bestTile(id)
        val tileId = hit?.source ?: id
        val data = hit?.data ?: runCatching { src.tile(id) }.getOrNull() ?: return@runBlocking null
        val layers = runCatching { Mvt.decode(data, LAYERS).layers }.getOrNull().orEmpty()
        val extent = layers.values.firstOrNull()?.extent ?: 4096
        val proj = TileProjection(tileId, extent)
        val segs = ArrayList<Int>()
        val names = ArrayList<String>()
        for (layer in layers.values) {
            // Every layer of a tile shares one extent in OpenMapTiles; a layer that differs would not line up.
            if (layer.extent != extent) continue
            for (f in layer.features) {
                if (f.type != GeomType.LINE) continue
                val label = language?.let { f.string("name:$it") } ?: f.string("name") ?: f.string("ref") ?: continue
                val g = f.geometry
                g.forEachPart { _, start, end ->
                    for (j in start until end - 1) {
                        segs += g.x(j); segs += g.y(j); segs += g.x(j + 1); segs += g.y(j + 1)
                        names += label
                    }
                }
            }
        }
        val centre = proj.latLon(extent / 2.0, extent / 2.0)
        val mpu = Mercator.metresPerUnit(centre.lat) * tileId.unitSize / extent
        Lines(proj, segs.toIntArray(), names.toTypedArray(), mpu)
    }

    private fun distance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx - px
        val y = ay + t * dy - py
        return sqrt(x * x + y * y)
    }

    private companion object {
        /** Named streets and roads, and named canals and rivers (what a boat follows). */
        val LAYERS = setOf("transportation_name", "waterway")
        const val ZOOM = 14
        const val MAX_TILES = 32
        const val NEAR_M = 25.0
        const val ALONG_DEG = 40.0
    }
}
