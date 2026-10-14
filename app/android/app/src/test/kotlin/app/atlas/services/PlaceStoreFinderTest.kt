package app.atlas.services

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.testing.FixtureTiles
import app.atlas.testing.Fixtures
import app.atlas.tiles.TileStack
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// SQLite's native library can belong to one class loader only: tests that open a store run in Robolectric's, as the
// screen tests do.
/** With a region's place store on the phone, the finder answers from it alone, with every tag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceStoreFinderTest {
    private val finder = LayeredPlaceFinder(
        tiles = { TileStack(listOf(FixtureTiles())) },
        overpass = { null },
        online = { true },
        store = { Fixtures.amsStore },
    )

    @Test
    fun eatComesFromTheStoreWithHours() = runBlocking {
        val answers = finder.nearby("eat", Services.WESTERKERK, 400.0, 40).toList()
        assertEquals(listOf(PlaceOrigin.STORE), answers.map { it.source })
        val places = answers.single().places
        assertEquals(40, places.size)
        assertTrue(places.all { Geo.distance(Services.WESTERKERK, it.location) <= 400.0 })
        assertTrue("opening hours: ${places.count { "opening_hours" in it.tags }}", places.count { "opening_hours" in it.tags } >= 10)
        assertTrue(places.zipWithNext().all { (a, b) -> Geo.distance(Services.WESTERKERK, a.location) <= Geo.distance(Services.WESTERKERK, b.location) })
    }

    @Test
    fun aLeafCategoryToo() = runBlocking {
        val toilets = finder.nearby("toilets", Services.WESTERKERK, 500.0, 10).toList().single()
        assertEquals(PlaceOrigin.STORE, toilets.source)
        assertTrue(toilets.places.all { it.tags["amenity"] == "toilets" })
    }

    @Test
    fun whereTheStoreHasNothingTheTilesAnswer() = runBlocking {
        // Far outside the extract: the store has nothing there, the fixture tiles neither, and offline there is no more.
        val far = LayeredPlaceFinder(tiles = { TileStack(listOf(FixtureTiles())) }, overpass = { null }, online = { false }, store = { Fixtures.amsStore })
        val answers = far.nearby("eat", app.atlas.geo.LatLon(52.0907, 5.1214), 300.0, 10).toList()
        assertEquals(PlaceOrigin.TILES, answers.first().source)
    }
}
