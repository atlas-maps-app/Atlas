package app.atlas.services

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.Categories
import app.atlas.testing.FixtureTiles
import app.atlas.tiles.TileStack
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Places from the real fixture tiles around the Westerkerk, offline. */
class TilePlacesTest {
    private val tiles = TileStack(listOf(FixtureTiles()))
    private val here = Services.WESTERKERK

    @Test
    fun featureIdsCarryTheOsmId() {
        assertEquals(OsmId(OsmType.NODE, 240316872), TilePlaces.osmIdOf(2403168721))
        assertEquals(OsmId(OsmType.WAY, 99205257), TilePlaces.osmIdOf(992052572))
        assertNull(TilePlaces.osmIdOf(12345670))
    }

    @Test
    fun kindsBecomeOsmTags() {
        assertEquals(mapOf("amenity" to "cafe"), TilePlaces.tagsOf("cafe", "cafe"))
        assertEquals(mapOf("shop" to "bakery"), TilePlaces.tagsOf("bakery", "bakery"))
        assertEquals(mapOf("tourism" to "museum"), TilePlaces.tagsOf("museum", "museum"))
        assertEquals(mapOf("railway" to "tram_stop"), TilePlaces.tagsOf("railway", "tram_stop"))
        assertNull(TilePlaces.tagsOf("shop", null))
    }

    @Test
    fun eatAroundTheWesterkerk() = runBlocking {
        val filter = LayeredPlaceFinder.filterOf(Categories.byId("eat")!!)
        val places = TilePlaces.near(tiles, here, 400.0, filter, 50)
        assertTrue("found ${places.size}", places.size >= 20)
        assertTrue(places.all { filter.matches(it.tags) && Geo.distance(here, it.location) <= 400.0 })
        assertTrue(places.zipWithNext().all { (a, b) -> Geo.distance(here, a.location) <= Geo.distance(here, b.location) })
        assertTrue(places.count { it.name() != null } > places.size / 2)
        assertTrue(places.all { it.id != null && it.source == "tiles" })
        assertEquals(places.size, places.map { it.key }.toSet().size)
    }

    @Test
    fun theFinderAnswersFromTheTilesWhenOffline() = runBlocking {
        val finder = LayeredPlaceFinder(tiles = { tiles }, overpass = { null }, online = { false })
        val answers = finder.nearby("groceries", here, 600.0, 30).toList()
        assertEquals(1, answers.size)
        assertEquals(PlaceOrigin.TILES, answers[0].source)
        assertTrue(answers[0].places.isNotEmpty())
    }

    @Test
    fun whereTheDetailedTilesAreMissingTheParentTileAnswers() = runBlocking {
        // A pack that stops at z13: the z14 ids fall back to their parents (each decoded once), which still answer.
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val z13 = object : app.atlas.tiles.TileSource by FixtureTiles() {
            override suspend fun tile(id: app.atlas.geo.TileId): ByteArray? {
                if (id.z > 13) return null
                reads.incrementAndGet()
                return FixtureTiles().tile(id)
            }
        }
        // At z13 OpenMapTiles keeps only the notable points of interest: museums, stations, churches.
        val places = TilePlaces.near(TileStack(listOf(z13)), here, 1_500.0, app.atlas.places.TagFilter.Has("name"), 50)
        assertTrue("found ${places.size}", places.isNotEmpty())
        assertTrue(places.all { Geo.distance(here, it.location) <= 1_500.0 })
        assertEquals(places.size, places.map { it.key }.toSet().size)
        assertTrue(reads.get() > 0)
    }
}
