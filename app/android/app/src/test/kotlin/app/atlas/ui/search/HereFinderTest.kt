package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.services.PlaceOrigin
import app.atlas.testing.FixtureTiles
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "What's here" at the Westerkerk: from the Amsterdam extract, from the fixture tiles alone, and with nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HereFinderTest {
    @Test
    fun theStoreKnowsTheChurchItsAddressAndItsNeighbours() = runBlocking {
        val f = HereFinder.find(testServices(places = true), Services.WESTERKERK, "en", online = false)
        assertEquals(PlaceOrigin.STORE, f.origin)
        assertEquals("Westerkerk", f.spot?.name())
        val a = f.address
        assertNotNull(a)
        assertEquals("Westermarkt", a!!.street)
        assertTrue(a.line!!.startsWith("Westermarkt "))
        assertEquals(PlaceOrigin.STORE, a.origin)
        // The tiles add the quarter and the town.
        assertEquals("Jordaan", a.quarter)
        assertEquals("Amsterdam", a.town)
        assertTrue(f.nearby.isNotEmpty() && f.nearby.size <= 8)
        assertTrue(f.nearby.none { it.name() == "Westerkerk" })
        assertTrue("Westertoren nearby: ${f.nearby.map { it.name() }}", f.nearby.any { it.name() == "Westertoren" })
    }

    @Test
    fun theTilesAloneGiveAnAddress() = runBlocking {
        val w = TileAddress.at(FixtureTiles(), LatLon(52.37515, 4.88260))
        assertNotNull(w)
        assertEquals("Bloemgracht", w!!.street)
        assertNotNull(w.housenumber)
        assertEquals("Jordaan", w.quarter)
        assertEquals("Amsterdam", w.town)
        val f = HereFinder.find(testServices(), LatLon(52.37515, 4.88260), "en", online = false)
        assertEquals(PlaceOrigin.TILES, f.origin)
        assertEquals(PlaceOrigin.TILES, f.address?.origin)
        assertTrue(f.address!!.line!!.startsWith("Bloemgracht"))
        assertTrue(f.nearby.isNotEmpty())
    }

    @Test
    fun outsideTheTilesNothingIsMadeUp() = runBlocking {
        // The North Sea, far from the fixture tiles: no address, no spot, nobody near.
        val sea = LatLon(53.5, 3.0)
        val w = TileAddress.at(FixtureTiles(), sea)
        assertTrue(w == null || (w.street == null && w.housenumber == null))
        val f = HereFinder.find(testServices(), sea, "en", online = false)
        assertNull(f.address?.line)
        assertNull(f.spot)
        assertTrue(f.nearby.isEmpty())
    }

    @Test
    fun theSmallestOutlineHoldingThePointWins() {
        val s = testServices(places = true)
        val records = s.placeStores.inBox(BBox.around(Services.WESTERKERK, 200.0))
        val f = HereFinder.fromStore(records, Services.WESTERKERK, null)
        assertEquals("Westerkerk", f.spot?.name())
        // Without tiles there is no quarter or town, but the address still comes from the map data.
        assertNull(f.address?.quarter)
        assertEquals("Westermarkt", f.address?.street)
    }

    @Test
    fun addressLines() {
        val a = HereAddress("Prinsengracht", "263", "1016GV", "Jordaan", "Amsterdam", PlaceOrigin.STORE)
        assertEquals("Prinsengracht 263", a.line)
        assertEquals("1016GV · Jordaan · Amsterdam", a.area)
        assertEquals("Prinsengracht", a.copy(housenumber = null).line)
        assertEquals("Amsterdam", HereAddress(null, null, null, "Amsterdam", "Amsterdam", PlaceOrigin.TILES).area)
        assertNull(HereAddress.of(mapOf("addr:housenumber" to "1"), PlaceOrigin.STORE, null))
    }
}
