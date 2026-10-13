package app.atlas.tiles

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decoding the real OpenFreeMap tiles around the Westerkerk. */
class MvtFixturesTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    @Test
    fun decodesEveryFixtureCompletely() {
        assertEquals(14, Fixtures.all.size)
        for (id in Fixtures.all) {
            val tile = Mvt.decode(Fixtures.bytes(id))
            assertTrue("$id has layers", tile.layers.isNotEmpty())
            for (layer in tile.layers.values) {
                assertEquals(4096, layer.extent)
                assertEquals(2, layer.version)
                for (f in layer.features) {
                    val g = f.geometry
                    assertFalse("$id ${layer.name} #${f.id} has geometry", g.isEmpty)
                    assertEquals(f.type, g.type)
                    for (k in f.props.keys) assertNotNull(f.props[k])
                }
            }
        }
    }

    @Test
    fun z14HasTheOpenMapTilesLayers() {
        val expected = setOf("transportation", "building", "water", "landuse", "poi", "place", "transportation_name")
        for (id in Fixtures.z14) {
            val names = Mvt.decode(Fixtures.bytes(id)).layerNames
            assertTrue("$id has $expected, got $names", names.containsAll(expected))
        }
        assertTrue(Mvt.decode(Fixtures.bytes(app.atlas.geo.TileId(0, 0, 0))).layerNames.containsAll(setOf("water", "place", "boundary")))
    }

    @Test
    fun buildingsAtTheWesterkerk() {
        val tile = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))
        val buildings = tile["building"]!!
        assertEquals(listOf("colour", "render_height", "render_min_height", "hide_3d"), buildings.keys)
        assertTrue(buildings.features.all { it.type == GeomType.POLYGON })
        assertTrue(buildings.features.all { it.long("render_height") != null })
        // OpenFreeMap merges buildings of equal height into multipolygons: a hundred features, thousands of buildings.
        var polygons = 0
        var tall = 0
        for (f in buildings.features) {
            f.geometry.forEachPolygon { _, _, _ -> polygons++ }
            val h = f.long("render_height")!!
            assertTrue(h in 0..400)
            assertTrue(f.long("render_min_height")!! <= h)
            if (h >= 20) tall++
        }
        assertTrue("buildings: $polygons", polygons in 3000..20000)
        assertTrue(tall > 0)
        assertTrue(tile["poi"]!!.size > 1000)
        assertTrue(tile["housenumber"]!!.size > 1000)
    }

    @Test
    fun theWesterkerkIsWhereItStands() {
        val tile = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))
        val poi = tile["poi"]!!.features.single { it.string("name") == "Westerkerk" }
        assertEquals("place_of_worship", poi.string("class"))
        assertEquals("christian", poi.string("subclass"))
        assertEquals(GeomType.POINT, poi.type)
        val p = poi.geometry.toLatLon(Fixtures.CENTRE, 4096).single().single()
        assertTrue("at $p", Geo.distance(p, westerkerk) < 60)
        val unit = poi.geometry.toUnitMercator(Fixtures.CENTRE, 4096)
        assertEquals(app.atlas.geo.Mercator.x(p.lon), unit[0], 1e-12)
        val proj = tile["poi"]!!.projection(Fixtures.CENTRE)
        val back = proj.toTile(p)
        assertEquals(poi.geometry.x(0).toDouble(), back[0], 1e-6)
        assertEquals(poi.geometry.y(0).toDouble(), back[1], 1e-6)
    }

    @Test
    fun polygonRingsCloseAndWindAsTheSpecSays() {
        var rings = 0
        var holes = 0
        for (id in Fixtures.all) {
            val tile = Mvt.decode(Fixtures.bytes(id))
            for (layer in tile.layers.values) for (f in layer.features) {
                val g = f.geometry
                when (g.type) {
                    GeomType.POLYGON -> {
                        assertTrue(g.polygonCount >= 1)
                        assertEquals(0, g.polygons[0])
                        assertEquals(g.partCount, g.polygons[g.polygonCount])
                        g.forEachPart { p, start, end ->
                            rings++
                            assertTrue("ring of ${end - start} points", end - start >= 4)
                            assertEquals("ring closes", g.x(start), g.x(end - 1))
                            assertEquals("ring closes", g.y(start), g.y(end - 1))
                            val a = g.ringArea2(p)
                            if (g.isExterior(p)) assertTrue("exterior winds positive in $id ${layer.name}", a > 0)
                            else { holes++; assertTrue("hole winds negative in $id ${layer.name}", a < 0) }
                        }
                        g.forEachPolygon { j, _, _ -> assertTrue(g.polygonArea(j) > 0) }
                    }
                    GeomType.LINE -> g.forEachPart { _, start, end -> assertTrue(end - start >= 2) }
                    GeomType.POINT -> assertEquals(1, g.partCount)
                }
            }
        }
        assertTrue("rings $rings", rings > 50_000)
        assertTrue("holes $holes", holes > 500)
    }

    @Test
    fun coordinatesStayNearTheTile() {
        val tile = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))
        for (layer in tile.layers.values) for (f in layer.features) {
            val b = f.geometry.bounds()!!
            // Shapes carry a buffer of a few percent beyond 0..4096; label points may sit up to a tile away.
            val reach = if (f.type == GeomType.POINT) 4096 else 1024
            assertTrue("${layer.name} ${b.toList()}", b[0] >= -reach && b[1] >= -reach && b[2] <= 4096 + reach && b[3] <= 4096 + reach)
        }
    }

    @Test
    fun layerFilterAndGzipInput() {
        val raw = Fixtures.bytes(Fixtures.CENTRE)
        val only = Mvt.decode(Gzip.compress(raw), only = setOf("building", "water"))
        assertEquals(setOf("building", "water"), only.layerNames)
        assertEquals(Mvt.decode(raw)["building"]!!.size, only["building"]!!.size)
    }

    @Test
    fun sharedValuesAreSharedObjects() {
        val layer = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))["poi"]!!
        val restaurants = layer.features.filter { it.string("class") == "restaurant" }
        assertTrue(restaurants.size > 50)
        assertTrue(restaurants.all { it["class"] === restaurants[0]["class"] })
        assertEquals(restaurants[0].props.entries.size, restaurants[0].props.size)
    }
}
