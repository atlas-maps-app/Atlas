package app.atlas.engine

import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.tiles.GeomType
import app.atlas.tiles.TileGeometry
import app.atlas.tiles.TileLayer
import app.atlas.tiles.TileTransform
import app.atlas.tiles.clip
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The metric frame of a scene: tile [id] drawn from the data of [source] (itself or an ancestor). Converts source tile
 * units to the scene's metres (x east, y south from the tile's north-west corner).
 */
internal class TileFrame(val id: TileId, val source: TileId) {
    val transform = TileTransform(source, id)
    val centreLat: Double = Mercator.lat(id.unitY + id.unitSize / 2)
    val centreLon: Double = Mercator.lon(id.unitX + id.unitSize / 2)
    val metresPerUnit: Double = Mercator.metresPerUnit(centreLat)

    /** The tile's side in metres. */
    val size: Double = id.unitSize * metresPerUnit

    /** Metres per density-independent pixel when the tile shows at its own zoom. */
    val metresPerDp: Double = Mercator.metresPerPixel(centreLat, id.z.toDouble())

    val zoom: Int get() = id.z

    fun scale(extent: Int): Double = size * transform.scale / extent

    fun x(sx: Double, extent: Int): Double = (sx - transform.left * extent) * scale(extent)
    fun y(sy: Double, extent: Int): Double = (sy - transform.top * extent) * scale(extent)

    /** The scene square in source units grown by [padMetres], for pre-clipping overzoomed data. */
    fun sourceRect(extent: Int, padMetres: Double): IntArray {
        val k = scale(extent)
        val pad = padMetres / k
        val x0 = transform.left * extent
        val y0 = transform.top * extent
        val s = transform.size * extent
        return intArrayOf(floor(x0 - pad).toInt(), floor(y0 - pad).toInt(), ceil(x0 + s + pad).toInt(), ceil(y0 + s + pad).toInt())
    }

    /** The geometry of a feature, cut down to the scene (plus a margin) when overzoomed. */
    fun geometry(g: TileGeometry, extent: Int, padMetres: Double): TileGeometry {
        if (transform.isIdentity) return g
        val r = sourceRect(extent, padMetres)
        return g.clip(r[0], r[1], r[2], r[3])
    }

    /** Ring [p] of [g] in scene metres, without the closing point. */
    fun ring(g: TileGeometry, p: Int, extent: Int): DoubleArray {
        val s = g.partStart(p)
        var e = g.partEnd(p)
        if (e - s >= 2 && g.x(s) == g.x(e - 1) && g.y(s) == g.y(e - 1)) e--
        val out = DoubleArray((e - s) * 2)
        val k = scale(extent)
        val ox = transform.left * extent
        val oy = transform.top * extent
        for (i in s until e) {
            out[2 * (i - s)] = (g.x(i) - ox) * k
            out[2 * (i - s) + 1] = (g.y(i) - oy) * k
        }
        return out
    }

    /** Line part [p] of [g] in scene metres as floats. */
    fun line(g: TileGeometry, p: Int, extent: Int): FloatArray {
        val s = g.partStart(p)
        val e = g.partEnd(p)
        val out = FloatArray((e - s) * 2)
        val k = scale(extent)
        val ox = transform.left * extent
        val oy = transform.top * extent
        for (i in s until e) {
            out[2 * (i - s)] = ((g.x(i) - ox) * k).toFloat()
            out[2 * (i - s) + 1] = ((g.y(i) - oy) * k).toFloat()
        }
        return out
    }

    /** Each polygon of [g] as rings in metres: outer first (positive area), holes after (negative). */
    fun polygons(g: TileGeometry, extent: Int): List<List<DoubleArray>> {
        if (g.type != GeomType.POLYGON || g.isEmpty) return emptyList()
        val out = ArrayList<List<DoubleArray>>(g.polygonCount)
        g.forEachPolygon { _, first, end ->
            val rings = ArrayList<DoubleArray>(end - first)
            for (p in first until end) {
                var r = ring(g, p, extent)
                if (r.size < 6) continue
                val a = Rings.area(r)
                if (p == first && a < 0) r = Rings.reversed(r)
                if (p != first && a > 0) r = Rings.reversed(r)
                if (p == first) rings += r else if (rings.isNotEmpty()) rings += r
            }
            if (rings.isNotEmpty()) out += rings
        }
        return out
    }

    companion object {
        /** The OSM element behind an OpenMapTiles feature id (`osmId * 10 + 1 node, 2 way, 3 relation`), when it says. */
        fun osmId(featureId: Long): OsmId? {
            if (featureId <= 0) return null
            val type = when ((featureId % 10).toInt()) {
                1 -> OsmType.NODE
                2 -> OsmType.WAY
                3 -> OsmType.RELATION
                else -> return null
            }
            return OsmId(type, featureId / 10)
        }

        /** The layer's coordinate bounds [minX, minY, maxX, maxY]: where its buffer ends, when features reach it. */
        fun layerBounds(layer: TileLayer): IntArray {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            for (f in layer.features) {
                val b = f.geometry.bounds() ?: continue
                if (b[0] < x0) x0 = b[0]
                if (b[1] < y0) y0 = b[1]
                if (b[2] > x1) x1 = b[2]
                if (b[3] > y1) y1 = b[3]
            }
            return intArrayOf(x0, y0, x1, y1)
        }
    }
}
