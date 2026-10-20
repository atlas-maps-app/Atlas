package app.atlas.services

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import app.atlas.testing.Fixtures
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
    private val repo = java.io.File(System.getProperty("atlas.repo") ?: "../..")
    private val pack by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(java.io.File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas"))))) }
    private val packs = LocalData(stores = { null }, packs = { pack })
    private val finder = LayeredPlaceFinder(
        local = { packs },
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
    fun withoutAStoreThePacksAnswerWithTheSameTags() = runBlocking {
        val fromPack = LayeredPlaceFinder(local = { packs }, overpass = { null }, online = { false }).nearby("eat", Services.WESTERKERK, 300.0, 40).toList().single()
        assertEquals(PlaceOrigin.PACK, fromPack.source)
        val fromStore = finder.nearby("eat", Services.WESTERKERK, 300.0, 40).toList().single()
        // the same extract, two containers: the same places, nearest first
        assertEquals(fromStore.places.map { it.key }, fromPack.places.map { it.key })
    }

    @Test
    fun whereNothingOnThePhoneCoversAndOfflineThereIsNoAnswer() = runBlocking {
        // Utrecht, far outside the extract and the pack: nothing to answer with, and offline there is no more
        val far = LayeredPlaceFinder(local = { packs }, overpass = { null }, online = { false }, store = { Fixtures.amsStore })
        assertTrue(far.nearby("eat", app.atlas.geo.LatLon(52.0907, 5.1214), 300.0, 10).toList().isEmpty())
    }
}
