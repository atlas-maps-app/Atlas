package app.atlas.packer.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate

/**
 * The world's profile (ENGINE.md step 5): each element takes the kind or class stage P gives it, through stage P's own
 * chains and tag tables (mapcore/style/tags.json, which tags_test holds to load/Tables.hpp), from the level its kind
 * first shows; what stage P leaves out, the world leaves out. WorldParityTest holds the same against stage P's tiles.
 */
class WorldProfileTest {
    private val profile = WorldProfile()

    private fun tags(vararg kv: Pair<String, String>): TagView = TagView { k -> kv.firstOrNull { it.first == k }?.second }

    private fun way(vararg kv: Pair<String, String>, closed: Boolean = false) =
        profile.decide(tags(*kv), OsmType.WAY, closed, closed, true).map { Triple(it.layer, it.kind, it.minLevel) }

    @Test
    fun theTagTablesAreStagePs() {
        val t = StagePTags.STAGE_P
        assertEquals(0, t.land["farmland"])
        assertEquals(3, t.land["forest"])
        assertEquals(5, t.land["flowerbed"])
        assertEquals(9.0, t.roads["secondary"])
        assertEquals(7.5, t.roads["tertiary"])
        assertTrue("motorway" in t.bigRoads && "secondary_link" in t.bigRoads && "secondary" !in t.bigRoads)
        assertEquals(setOf("rail", "light_rail", "subway", "narrow_gauge"), t.rail)
        assertTrue("runway" in t.aero && "canal" in t.waterWays && "yes" in t.tunnels)
    }

    @Test
    fun eachKindAndClassFromItsLevelAsStagePChainsDecide() {
        // amsterdam.py's water and roads; regions.py's land cover, big roads, rail and runways; the world's rivers
        assertEquals(listOf(Triple(WorldProfile.WATER, "water", 6)), way("natural" to "water", closed = true))
        assertEquals(listOf(Triple(WorldProfile.WATER, "water", 6)), way("waterway" to "canal", closed = true))
        assertEquals(listOf(Triple(WorldProfile.LAND, "forest", 6)), way("landuse" to "forest", closed = true))
        assertEquals(listOf(Triple(WorldProfile.LAND, "park", 6)), way("leisure" to "park", closed = true))
        assertEquals(listOf(Triple(WorldProfile.LAND, "port", 6)), way("industrial" to "port", closed = true))
        assertEquals(listOf(Triple(WorldProfile.LAND, "parking", 6)), way("amenity" to "parking", closed = true))
        assertEquals(listOf(Triple(WorldProfile.ROAD, "secondary", 10)), way("highway" to "secondary"))
        assertEquals(listOf(Triple(WorldProfile.ROAD, "tertiary", 11)), way("highway" to "tertiary"))
        assertEquals(listOf(Triple(WorldProfile.BIG, "motorway", 6)), way("highway" to "motorway"))
        assertEquals(listOf(Triple(WorldProfile.BIG, "trunk_link", 7)), way("highway" to "trunk_link"))
        assertEquals(listOf(Triple(WorldProfile.BIG, "primary", 9)), way("highway" to "primary"))
        assertEquals(listOf(Triple(WorldProfile.BIG, "secondary_link", 10)), way("highway" to "secondary_link"))
        assertEquals(listOf(Triple(WorldProfile.RAIL, "rail", 9)), way("railway" to "rail"))
        assertEquals(listOf(Triple(WorldProfile.AERO, "runway", 10)), way("aeroway" to "runway"))
        assertEquals(listOf(Triple(WorldProfile.RIVER, "river", 8)), way("waterway" to "river"))
    }

    @Test
    fun whatStagePLeavesOutTheWorldLeavesOut() {
        // a building's land use, a coastline, a national park, minor roads, a pedestrian square, a road underground,
        // rail sidings and light rail, taxiways, a riverbank as a line
        assertEquals(emptyList<Any>(), way("building" to "yes", "landuse" to "residential", closed = true))
        assertEquals(emptyList<Any>(), way("natural" to "coastline"))
        assertEquals(emptyList<Any>(), way("boundary" to "national_park", "landuse" to "forest", closed = true))
        assertEquals(emptyList<Any>(), way("highway" to "residential"))
        assertEquals(emptyList<Any>(), way("highway" to "tertiary_link"))
        assertEquals(emptyList<Any>(), way("highway" to "pedestrian", closed = true))
        assertEquals(emptyList<Any>(), way("highway" to "secondary", "area" to "yes", closed = true))
        assertEquals(emptyList<Any>(), way("highway" to "motorway", "tunnel" to "yes"))
        assertEquals(emptyList<Any>(), way("highway" to "trunk", "layer" to "-1"))
        assertEquals(emptyList<Any>(), way("railway" to "rail", "service" to "siding"))
        assertEquals(emptyList<Any>(), way("railway" to "light_rail"))
        assertEquals(emptyList<Any>(), way("railway" to "rail", "tunnel" to "building_passage"))
        assertEquals(emptyList<Any>(), way("aeroway" to "taxiway"))
        assertEquals(emptyList<Any>(), way("landuse" to "no_such_kind", closed = true))
        // a land use on an open way is no area
        assertEquals(emptyList<Any>(), profile.decide(tags("landuse" to "forest"), OsmType.WAY, false, false, true))
    }

    @Test
    fun placesAreNodesOfTheKindsTheWorldDrawsFromTheirLevels() {
        fun node(vararg kv: Pair<String, String>) = profile.decide(tags(*kv), OsmType.NODE, false, false, false)
        val town = node("place" to "town", "name" to "Zaandam").single()
        assertEquals(Triple(WorldProfile.PLACE, "town", 8), Triple(town.layer, town.kind, town.minLevel))
        assertEquals("Zaandam", town.name)
        assertEquals(6, node("place" to "city", "name" to "Amsterdam").single().minLevel)
        assertEquals(6, node("place" to "country", "name" to "Nederland").single().minLevel)
        assertEquals(10, node("place" to "village").single().minLevel)
        assertEquals(11, node("place" to "suburb").single().minLevel)
        assertTrue(node("place" to "hamlet").isEmpty())
        assertTrue(node("place" to "neighbourhood").isEmpty())
        // a place mapped on a way or an area is no place label in stage P
        assertTrue(profile.decide(tags("place" to "town"), OsmType.WAY, true, true, true).isEmpty())
    }

    @Test
    fun numbersAndAreasAsStagePReadsThem() {
        assertEquals(-1.0, WorldProfile.num(" -1"))
        assertEquals(12.5, WorldProfile.num("12.5 m"))
        // Python's \d takes any script's decimal digits
        assertEquals(12.0, WorldProfile.num("\u0661\u0662 m"))
        assertNull(WorldProfile.num("level -1"))
        assertNull(WorldProfile.num(null))
        // a square 1e-4° a side at 52° N: 111,320 m · cos 52° · 1e-4 by 110,540 m · 1e-4
        val lat = 52.0
        val y0 = WorldGeometry.worldY(lat)
        val y1 = WorldGeometry.worldY(lat + 1e-4)
        val x0 = WorldGeometry.worldX(4.9)
        val x1 = WorldGeometry.worldX(4.9 + 1e-4)
        val ring = arrayOf(Coordinate(x0, y0), Coordinate(x1, y0), Coordinate(x1, y1), Coordinate(x0, y1), Coordinate(x0, y0))
        val expected = 111_320.0 * Math.cos(Math.toRadians(lat + 0.5e-4)) * 1e-4 * 110_540.0 * 1e-4
        assertEquals(expected, WorldProfile.squareMetres(ring), expected * 1e-3)
    }
}
