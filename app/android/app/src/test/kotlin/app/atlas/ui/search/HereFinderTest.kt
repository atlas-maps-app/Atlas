package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.services.HereGap
import app.atlas.services.HereWindows
import app.atlas.services.LocalData
import app.atlas.services.PlaceOrigin
import app.atlas.tiles.WindowPacks
import app.atlas.tiles.WorldFetch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        // offline with nothing for the point: said so, not "nothing named"
        assertEquals(HereGap.OFFLINE, f.gap)
    }

    /**
     * Where nothing on the phone holds the point (Leidschendam without its country), the map's window data answers
     * (the user, 1 November: "Sometimes it says looking forever and then claims theres nothing mapped within 150m").
     */
    @Test
    fun theWindowsDataAnswersWhereNothingElseHoldsThePoint() = runBlocking<Unit> {
        val at = LatLon(52.0830, 4.3900)
        val (x, y) = WorldFetch.tileOf(at.lat, at.lon, WindowPacks.LEVEL)
        val dir = Files.createTempDirectory("here").toFile()
        val kept = File(dir, "14-$x-$y.json.gz").apply { writeBytes(gz(WINDOW)) }
        var asked = 0
        val windows = HereWindows(kept = { wx, wy -> kept.takeIf { wx == x && wy == y } }, packed = { _, _ -> asked++; null })
        val data = LocalData(stores = { null }, packs = { null }, windows = { p, r -> windows.around(p, r, 5_000) })
        val here = data.whatsHere(at)
        assertNull(here.gap)
        assertEquals(PlaceOrigin.PACK, here.origin)
        assertEquals(listOf("Café de Vliet"), here.nearby.map { it.name() })
        assertEquals("Damplein", here.address?.street)
        assertEquals("5", here.address?.housenumber)
        // the kept copy is read, not the packs
        assertEquals(0, asked)
        dir.deleteRecursively()
    }

    @Test
    fun aSlowWindowReadIsGivenUpAndItsBytesKeptForTheNextPress() = runBlocking<Unit> {
        val at = LatLon(52.0830, 4.3900)
        var reads = 0
        val windows = HereWindows(kept = { _, _ -> null }, packed = { _, _ -> reads++; Thread.sleep(600); gz(WINDOW) })
        val started = System.nanoTime()
        try {
            windows.around(at, 150.0, withinMs = 100)
            fail("a read slower than its wait gives up")
        } catch (e: IOException) {
            assertTrue("gave up at once", System.nanoTime() - started < 500_000_000L)
        }
        Thread.sleep(900)
        val f = windows.around(at, 150.0, withinMs = 100)
        assertNotNull(f)
        assertEquals(1, reads)
    }

    private fun gz(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private companion object {
        /** A window's Overpass answer: a café and an addressed house by the point, a town's name, a café 3 km off. */
        const val WINDOW = """{"version":0.6,"generator":"test","osm3s":{"timestamp_osm_base":"2026-10-30T00:00:00Z"},"elements":[
{"type":"node","id":1,"lat":52.0832,"lon":4.3903,"tags":{"amenity":"cafe","name":"Café de Vliet"}},
{"type":"way","id":2,"bounds":{"minlat":52.08295,"minlon":4.38990,"maxlat":52.08305,"maxlon":4.39010},"nodes":[10,11,12,13,10],
 "geometry":[{"lat":52.08295,"lon":4.38990},{"lat":52.08295,"lon":4.39010},{"lat":52.08305,"lon":4.39010},{"lat":52.08305,"lon":4.38990},{"lat":52.08295,"lon":4.38990}],
 "tags":{"building":"house","addr:street":"Damplein","addr:housenumber":"5","addr:city":"Leidschendam"}},
{"type":"node","id":3,"lat":52.0840,"lon":4.3950,"tags":{"place":"town","name":"Leidschendam"}},
{"type":"node","id":4,"lat":52.1100,"lon":4.3900,"tags":{"amenity":"cafe","name":"Café Ver Weg"}}
]}"""
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
