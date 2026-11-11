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
 * up to 1 at L0 to L2 and up to 4 from L3; the seas', gulfs' and lakes' names (LABEL source 2) from the levels their
 * min_label asks for, a reef's none; a place or a water by OpenStreetMap's name where world.yml's names file has its
 * Wikidata id (a place's node near it). With the core's stage P tool the core reads the pack as the packer wrote it.
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

    private fun city(name: String, lon: Double, lat: Double, rank: Int, pop: Long, capital: Boolean = false, wikidata: String = "") =
        ShapefileWriter.Record(
            f.createPoint(Coordinate(lon, lat)),
            mapOf("SCALERANK" to rank.toLong(), "NAME" to name, "POP_MAX" to pop, "ADM0CAP" to if (capital) 1L else 0L, "WIKIDATAID" to wikidata),
        )

    @Test
    fun theGlobesSixLevelsAreBuiltFromItsSourcesAndEveryTileCoversItsCells() {
        val ne = tmp.newFolder("naturalearth")
        val continent = box(-10.0, 35.0, 30.0, 60.0)
        for (scale in listOf("110m", "50m")) {
            ShapefileWriter.zip(File(ne, "ne_${scale}_land.zip"), "ne_${scale}_land", listOf(ShapefileWriter.Record(continent, mapOf("featurecla" to "Land"))))
            ShapefileWriter.zip(
                File(ne, "ne_${scale}_lakes.zip"), "ne_${scale}_lakes",
                listOf(ShapefileWriter.Record(box(5.0, 45.0, 9.0, 48.0), mapOf("name" to "Lake", "featurecla" to "Lake", "min_label" to 2.0, "wikidataid" to "Q6343"))),
            )
            // the sea west of the continent from L0, a gulf in it from L2 (min_label 3.7), a reef without a name drawn
            ShapefileWriter.zip(
                File(ne, "ne_${scale}_geography_marine_polys.zip"), "ne_${scale}_geography_marine_polys",
                listOf(
                    ShapefileWriter.Record(box(-30.0, 30.0, -12.0, 50.0), mapOf("featurecla" to "sea", "name" to "Mare", "min_label" to 1.0, "wikidataid" to "Q1")),
                    ShapefileWriter.Record(box(-14.0, 40.0, -11.0, 44.0), mapOf("featurecla" to "gulf", "name" to "Gulf", "min_label" to 3.7, "wikidataid" to "")),
                    ShapefileWriter.Record(box(-25.0, 32.0, -24.0, 33.0), mapOf("featurecla" to "reef", "name" to "Reef", "min_label" to 1.0, "wikidataid" to "")),
                ),
            )
            ShapefileWriter.zip(
                File(ne, "ne_${scale}_rivers_lake_centerlines.zip"), "ne_${scale}_rivers_lake_centerlines",
                listOf(ShapefileWriter.Record(f.createLineString(arrayOf(Coordinate(0.0, 50.0), Coordinate(12.0, 51.0), Coordinate(25.0, 50.5))), mapOf("name" to "River"))),
            )
            ShapefileWriter.zip(File(ne, "ne_${scale}_glaciated_areas.zip"), "ne_${scale}_glaciated_areas", listOf(ShapefileWriter.Record(box(20.0, 55.0, 23.0, 57.0), mapOf("featurecla" to "Glaciated areas"))))
        }
        ShapefileWriter.zip(File(ne, "ne_50m_antarctic_ice_shelves_polys.zip"), "ne_50m_antarctic_ice_shelves_polys", listOf(ShapefileWriter.Record(box(0.0, -78.0, 20.0, -74.0), mapOf("featurecla" to "Ice shelf"))))
        val cities = listOf(
            city("Capital", 2.35, 48.86, 0, 11_000_000, capital = true, wikidata = "Q90"), city("Port", -5.0, 40.0, 1, 2_000_000, wikidata = "Q2"),
            city("Town", 10.0, 52.0, 3, 500_000), city("Village", 15.0, 45.0, 6, 50_000),
        )
        ShapefileWriter.zip(File(ne, "ne_110m_populated_places.zip"), "ne_110m_populated_places", cities.take(2))
        ShapefileWriter.zip(File(ne, "ne_50m_populated_places.zip"), "ne_50m_populated_places", cities)
        val osm = File(tmp.root, "simplified-land-polygons-complete-3857.zip")
        ShapefileWriter.zip(osm, "simplified_land_polygons", listOf(ShapefileWriter.Record(metres(continent))))
        // OpenStreetMap's names by Wikidata id, as world.yml's filter writes them: the capital's node near Natural Earth's
        // point, the lake and the sea; Port's id names a node 3° off, no node of it
        val names = File(tmp.root, "names.tsv").apply {
            writeText(
                "Q90\tn1\tplace=city\t48.8566\t2.3522\tParis\n" +
                    "Q2\tn2\tplace=city\t43.0\t-5.0\tElsewhere\n" +
                    "Q6343\tr3\tnatural=water\t\t\tLac Léman\n" +
                    "Q1\tr4\tplace=sea\t\t\tMer\n" +
                    "not an id\tn5\tplace=city\t0\t0\tNobody\n",
            )
        }

        val out = File(tmp.root, "world-globe.atlas")
        val built = Globe.build(ne, osm, out, 2, ::println, OsmNames.read(names))
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
        fun names(z: Int, source: Int = 1): Set<String> =
            tiles.filter { it.level == z }.flatMap { it.layers[Layer.LABEL].orEmpty() }.filter { Covers.source(it) == source }.map { it["name"].toString() }.toSet()
        // the places by OSM's name where their Wikidata id has a node near them, else Natural Earth's
        assertEquals(setOf("Paris", "Port"), names(0))
        assertEquals(setOf("Paris", "Port"), names(2))
        assertEquals(setOf("Paris", "Port", "Town"), names(3))
        assertEquals(setOf("Paris", "Port", "Town"), names(5))
        // a level's label rows of [name], over all its tiles (a level past L0 has several)
        fun labels(z: Int, name: String) = tiles.filter { it.level == z }.flatMap { it.layers[Layer.LABEL].orEmpty() }.filter { it["name"] == name }
        val capital = labels(0, "Paris").first()
        assertEquals("yes", capital["tag:capital"])
        assertEquals("11000000", capital["tag:population"])
        // the water's names (source 2) from the level their min_label asks for, by OSM's name where it has their id
        assertEquals(setOf("Mer"), names(0, WorldCut.WATER_NAMES))
        assertEquals(setOf("Mer", "Lac Léman"), names(1, WorldCut.WATER_NAMES))
        assertEquals(setOf("Mer", "Lac Léman", "Gulf"), names(2, WorldCut.WATER_NAMES))
        assertEquals(setOf("Mer", "Lac Léman", "Gulf"), names(5, WorldCut.WATER_NAMES))
        val sea = labels(0, "Mer").single()
        assertEquals("sea", sea["kind"])
        // its area in m², the box's 18° by 20° about 40° N: some 3.39 million km²
        assertEquals(3.39e12, (sea["count"] as Number).toDouble(), 0.05e12)
        // a water's name in one tile of each level
        assertEquals("bay", labels(2, "Gulf").single()["kind"])
        assertEquals("lake", labels(1, "Lac Léman").single()["kind"])
        for (z in 1..5) assertEquals("L$z", 1, labels(z, "Mer").size)
        assertEquals(1, Globe.labelLevel(2.0))
        assertEquals(2, Globe.labelLevel(3.7))
        assertEquals(0, Globe.labelLevel(1.8))
        assertEquals(0, Globe.labelLevel(null))
        fun any(z: Int, test: (RenderTile) -> Boolean) = tiles.any { it.level == z && test(it) }
        for (z in 0..5) {
            assertTrue("L$z: the lake", any(z) { t -> t.layers[Layer.WATER].orEmpty().any { it["tidal"] != true } })
            assertTrue("L$z: the river", any(z) { t -> t.layers[Layer.LINE].orEmpty().any { Covers.source(it) == WorldCut.RIVERS } })
            assertTrue("L$z: the glacier", any(z) { t -> t.layers[Layer.GROUND].orEmpty().any { it["kind"] == "glacier" } })
            assertTrue("L$z: the ice shelf", any(z) { t -> t.layers[Layer.GROUND].orEmpty().any { it["kind"] == "ice_shelf" && Covers.source(it) == 0 } })
        }
    }
}
