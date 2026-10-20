package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.services.PlaceOrigin
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

/** "What's here" at the Westerkerk: from the Amsterdam extract and the bundled pack, from the pack alone, and with nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HereFinderTest {
    @Test
    fun theChurchItsAddressAndItsNeighbours() = runBlocking {
        val f = HereFinder.find(testServices(places = true), Services.WESTERKERK, "en", online = false)
        assertEquals(PlaceOrigin.STORE, f.origin)
        assertEquals("Westerkerk", f.spot?.name())
        val a = f.address
        assertNotNull(a)
        // the pack holds the church's outline: the address point inside it, and the quarter holding the point
        assertEquals(PlaceOrigin.PACK, a!!.origin)
        assertEquals("Westermarkt", a.street)
        assertTrue(a.line!!.startsWith("Westermarkt "))
        assertEquals("Grachtengordel", a.quarter)
        assertEquals("Amsterdam", a.town)
        assertTrue(f.nearby.isNotEmpty() && f.nearby.size <= 8)
        assertTrue(f.nearby.none { it.name() == "Westerkerk" })
        assertTrue("Westertoren nearby: ${f.nearby.map { it.name() }}", f.nearby.any { it.name() == "Westertoren" })
    }

    @Test
    fun theBundledPackAloneGivesAnAddress() = runBlocking {
        val f = HereFinder.find(testServices(), LatLon(52.37515, 4.88260), "en", online = false)
        assertEquals(PlaceOrigin.PACK, f.origin)
        assertEquals(PlaceOrigin.PACK, f.address?.origin)
        assertTrue("${f.address}", f.address!!.line!!.startsWith("Bloemgracht "))
        assertEquals("Amsterdam", f.address!!.town)
        assertTrue(f.nearby.isNotEmpty())
    }

    @Test
    fun outsideEverythingOnThePhoneNothingIsMadeUp() = runBlocking {
        // The North Sea, far from the bundled pack: no address, no spot, nobody near.
        val f = HereFinder.find(testServices(), LatLon(53.5, 3.0), "en", online = false)
        assertNull(f.address?.line)
        assertNull(f.spot)
        assertTrue(f.nearby.isEmpty())
    }

    @Test
    fun addressLines() {
        val a = HereAddress("Prinsengracht", "263", "1016GV", "Jordaan", "Amsterdam", PlaceOrigin.STORE)
        assertEquals("Prinsengracht 263", a.line)
        assertEquals("1016GV · Jordaan · Amsterdam", a.area)
        assertEquals("Prinsengracht", a.copy(housenumber = null).line)
        assertEquals("Amsterdam", HereAddress(null, null, null, "Amsterdam", "Amsterdam", PlaceOrigin.PACK).area)
        assertNull(HereAddress.of(mapOf("addr:housenumber" to "1"), PlaceOrigin.STORE))
    }
}
