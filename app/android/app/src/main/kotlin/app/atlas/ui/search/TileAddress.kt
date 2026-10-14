package app.atlas.ui.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.tiles.GeomType
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileLayer
import app.atlas.tiles.TileProjection
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a point is, as the map tiles say it: the nearest house number and named street, the quarter and the town. */
data class TileWhere(val street: String?, val housenumber: String?, val quarter: String?, val town: String?) {
    val isEmpty: Boolean get() = street == null && housenumber == null && quarter == null && town == null
}

/**
 * Reads an address from the vector tiles alone (OpenMapTiles' `housenumber`, `transportation_name` and `place` layers),
 * offline and without a place store: the house number within [HOUSE_M], the named street within [STREET_M], the
 * nearest quarter and town. Good enough for "What's here" with only the map on the phone.
 */
object TileAddress {
    private const val ZOOM = 14
    const val HOUSE_M = 25.0
    const val STREET_M = 60.0
    private const val QUARTER_M = 1_500.0
    private const val TOWN_M = 15_000.0
    private val QUARTERS = setOf("neighbourhood", "quarter", "suburb")
    private val TOWNS = setOf("city", "town", "village", "hamlet")
    private val LAYERS = setOf("housenumber", "transportation_name", "place")

    suspend fun at(src: TileSource, p: LatLon): TileWhere? {
        val near = Nearest(p)
        val read = HashSet<TileId>()
        for (id in Mercator.tilesIn(BBox.around(p, STREET_M), ZOOM)) {
            val hit = (src as? TileStack)?.bestTile(id)
            val tileId = hit?.source ?: id
            if (!read.add(tileId)) continue
            val data = hit?.data ?: src.tile(id) ?: continue
            val tile = Mvt.decode(data, LAYERS)
            tile["housenumber"]?.let { near.houses(it, tileId) }
            tile["transportation_name"]?.let { near.streets(it, tileId) }
            tile["place"]?.let { near.places(it, tileId) }
        }
        // Quarters and towns lie further than the street box: their labels sit in the tile below too, at lower zooms.
        if (near.town == null || near.quarter == null) {
            for (z in intArrayOf(12, 10)) {
                val id = Mercator.tileAt(p, z)
                val hit = (src as? TileStack)?.bestTile(id)
                val data = hit?.data ?: src.tile(id) ?: continue
                Mvt.decode(data, setOf("place"))["place"]?.let { near.places(it, hit?.source ?: id) }
            }
        }
        val out = TileWhere(near.street, near.house, near.quarter, near.town)
        return out.takeIf { !it.isEmpty }
    }

    /** Keeps the nearest of each kind of thing, in metres on a flat frame around [p]. */
    private class Nearest(private val p: LatLon) {
        private val mx = 111_320.0 * cos(Math.toRadians(p.lat))
        private val my = 110_540.0
        var house: String? = null
        private var houseD = HOUSE_M
        var street: String? = null
        private var streetD = STREET_M
        var quarter: String? = null
        private var quarterD = QUARTER_M
        var town: String? = null
        private var townD = TOWN_M

        private fun x(proj: TileProjection, tx: Int) = (proj.lon(tx.toDouble()) - p.lon) * mx
        private fun y(proj: TileProjection, ty: Int) = (proj.lat(ty.toDouble()) - p.lat) * my

        fun houses(layer: TileLayer, tile: TileId) {
            val proj = layer.projection(tile)
            for (f in layer.features) {
                if (f.type != GeomType.POINT || f.geometry.isEmpty) continue
                val n = f.string("housenumber") ?: continue
                val g = f.geometry
                val d = hypot(x(proj, g.x(0)), y(proj, g.y(0)))
                if (d < houseD) { houseD = d; house = n }
            }
        }

        fun streets(layer: TileLayer, tile: TileId) {
            val proj = layer.projection(tile)
            for (f in layer.features) {
                if (f.type != GeomType.LINE) continue
                val name = f.string("name") ?: continue
                val g = f.geometry
                g.forEachPart { _, start, end ->
                    var ax = x(proj, g.x(start)); var ay = y(proj, g.y(start))
                    for (i in start + 1 until end) {
                        val bx = x(proj, g.x(i)); val by = y(proj, g.y(i))
                        val d = segment(ax, ay, bx, by)
                        if (d < streetD) { streetD = d; street = name }
                        ax = bx; ay = by
                    }
                }
            }
        }

        fun places(layer: TileLayer, tile: TileId) {
            val proj = layer.projection(tile)
            for (f in layer.features) {
                if (f.type != GeomType.POINT || f.geometry.isEmpty) continue
                val name = f.string("name") ?: continue
                val cls = f.string("class") ?: continue
                val g = f.geometry
                val d = hypot(x(proj, g.x(0)), y(proj, g.y(0)))
                when (cls) {
                    in QUARTERS -> if (d < quarterD) { quarterD = d; quarter = name }
                    in TOWNS -> if (d < townD) { townD = d; town = name }
                }
            }
        }

        /** Metres from the origin to the segment a–b. */
        private fun segment(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }

        private fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)
    }
}
