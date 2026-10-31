package app.atlas.packer.world

import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Profile
import app.atlas.rendertiles.model.RenderTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * The packer's `globe` (ENGINE.md step 5, the globe: L0 to L5) on Natural Earth's layers and osmdata's simplified land
 * polygons, written here small: a continent with a lake, a river, a glacier and its cities, an ice shelf off it. Natural
 * Earth's land at L0 to L3, osmdata's (in EPSG:3857) at L4 and L5; every tile's ground covers its cells exactly; the
 * lakes are water, the rivers lines, the glaciers land cover, the ice shelves areas over the sea; the cities of scalerank
 * up to 1 at L0 to L2 and up to 4 from L3. With the core's stage P tool the core reads the pack as the packer wrote it.
 */
class GlobeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f = GeometryFactory()

    private fun box(w: Double, s: Double, e: Double, n: Double): Geometry =
        f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))

    private fun metres(g: Geometry): Geometry {
        val r = 6378137.0
        val c = g.copy()
        c.apply(org.locationtech.jts.geom.CoordinateFilter { p ->
            p.x = Math.toRadians(p.x) * r
            p.y = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(p.y) / 2)) * r
        })
        c.geometryChanged()
        return c
    }

    private fun city(name: String, lon: Double, lat: Double, rank: Int, pop: Long, capital: Boolean = false) =
        ShapefileWriter.Record(f.createPoint(Coordinate(lon, lat)), mapOf("SCALERANK" to rank.toLong(), "NAME" to name, "POP_MAX" to pop, "ADM0CAP" to if (capital) 1L else 0L))

    @Test
    fun theGlobesSixLevelsAreBuiltFromItsSourcesAndEveryTileCoversItsCells() {
        val ne = tmp.newFolder("naturalearth")
        val continent = box(-10.0, 35.0, 30.0, 60.0)
        for (scale in listOf("110m", "50m")) {
            ShapefileWriter.zip(File(ne, "ne_${scale}_land.zip"), "ne_${scale}_land", listOf(ShapefileWriter.Record(continent, mapOf("featurecla" to "Land"))))
            ShapefileWriter.zip(File(ne, "ne_${scale}_lakes.zip"), "ne_${scale}_lakes", listOf(ShapefileWriter.Record(box(5.0, 45.0, 9.0, 48.0), mapOf("name" to "Lake"))))
            ShapefileWriter.zip(
                File(ne, "ne_${scale}_rivers_lake_centerlines.zip"), "ne_${scale}_rivers_lake_centerlines",
                listOf(ShapefileWriter.Record(f.createLineString(arrayOf(Coordinate(0.0, 50.0), Coordinate(12.0, 51.0), Coordinate(25.0, 50.5))), mapOf("name" to "River"))),
            )
            ShapefileWriter.zip(File(ne, "ne_${scale}_glaciated_areas.zip"), "ne_${scale}_glaciated_areas", listOf(ShapefileWriter.Record(box(20.0, 55.0, 23.0, 57.0), mapOf("featurecla" to "Glaciated areas"))))
        }
        ShapefileWriter.zip(File(ne, "ne_50m_antarctic_ice_shelves_polys.zip"), "ne_50m_antarctic_ice_shelves_polys", listOf(ShapefileWriter.Record(box(0.0, -78.0, 20.0, -74.0), mapOf("featurecla" to "Ice shelf"))))
        val cities = listOf(city("Capital", 2.35, 48.86, 0, 11_000_000, capital = true), city("Port", -5.0, 40.0, 1, 2_000_000), city("Town", 10.0, 52.0, 3, 500_000), city("Village", 15.0, 45.0, 6, 50_000))
        ShapefileWriter.zip(File(ne, "ne_110m_populated_places.zip"), "ne_110m_populated_places", cities.take(2))
        ShapefileWriter.zip(File(ne, "ne_50m_populated_places.zip"), "ne_50m_populated_places", cities)
        val osm = File(tmp.root, "simplified-land-polygons-complete-3857.zip")
        ShapefileWriter.zip(osm, "simplified_land_polygons", listOf(ShapefileWriter.Record(metres(continent))))

        val out = File(tmp.root, "world-globe.atlas")
        val built = Globe.build(ne, osm, out, 2, ::println)
        assertEquals(0.0, built.worstCover, 0.0)
        assertEquals(0, built.ringsOutside)
        assertEquals((0..5).toList(), built.levels.keys.toList())
        assertEquals(1, built.levels.getValue(0).tiles)

        val pack = Format3Reader(out)
        assertEquals(Profile.WORLD_BASE, pack.head.profile)
        assertEquals((5 downTo 0).toList(), pack.head.levels.toList())
        val tiles = pack.tiles()
        assertEquals(built.tiles, tiles.size)
        for (t in tiles) Covers.assertCovers(t)
        Covers.assertCoreReads(out, 0, out.length(), (5 downTo 0).toList())
        fun names(z: Int): Set<String> = tiles.filter { it.level == z }.flatMap { it.layers[Layer.LABEL].orEmpty() }.map { it["name"].toString() }.toSet()
        assertEquals(setOf("Capital", "Port"), names(0))
        assertEquals(setOf("Capital", "Port"), names(2))
        assertEquals(setOf("Capital", "Port", "Town"), names(3))
        assertEquals(setOf("Capital", "Port", "Town"), names(5))
        val capital = tiles.first { it.level == 0 }.layers[Layer.LABEL]!!.first { it["name"] == "Capital" }
        assertEquals("yes", capital["tag:capital"])
        assertEquals("11000000", capital["tag:population"])
        fun any(z: Int, test: (RenderTile) -> Boolean) = tiles.any { it.level == z && test(it) }
        for (z in 0..5) {
            assertTrue("L$z: the lake", any(z) { t -> t.layers[Layer.WATER].orEmpty().any { it["tidal"] != true } })
            assertTrue("L$z: the river", any(z) { t -> t.layers[Layer.LINE].orEmpty().any { Covers.source(it) == WorldCut.RIVERS } })
            assertTrue("L$z: the glacier", any(z) { t -> t.layers[Layer.GROUND].orEmpty().any { it["kind"] == "glacier" } })
            assertTrue("L$z: the ice shelf", any(z) { t -> t.layers[Layer.GROUND].orEmpty().any { it["kind"] == "ice_shelf" && Covers.source(it) == 0 } })
        }
    }
}
