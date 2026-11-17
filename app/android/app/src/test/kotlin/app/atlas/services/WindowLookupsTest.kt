package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.places.Brands
import app.atlas.places.TagFilter
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * The lookups where nothing on the phone holds a place but the map's window data, as "What's here" reads it (the user,
 * 11 November: "The long press whats nearby is the only lookup that works reliably"): offline, each kind of lookup
 * finds what the window holds; online, the phone's answer comes first and Overpass only adds to it, and a window the
 * phone keeps no data for comes from Atlas's packs before Overpass is asked.
 */
class WindowLookupsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** The middle of the window holding Leidschendam's Damplein, so 300 m around it stays in the window. */
    private val tile = Mercator.tileAt(LatLon(52.0830, 4.3900), WindowPlaces.LEVEL)
    private val here = tile.bbox.center
    private val cafe = TagFilter.Equals("amenity", "cafe")
    private val supermarket = TagFilter.Equals("shop", "supermarket")

    private val server = MockWebServer()
    private val asked = AtomicInteger()

    @After
    fun stop() = server.close()

    private fun keptDir(): File = tmp.newFolder("windows").also { File(it, "${tile.z}-${tile.x}-${tile.y}.json.gz").writeBytes(gz(window())) }

    private fun offline(dir: File = keptDir()) = LocalData(stores = { null }, packs = { null }, windowPlaces = WindowPlaces({ dir }))

    @Test
    fun offlineEachLookupFindsWhatOnlyTheWindowHolds() = runBlocking {
        val local = offline()
        val near = local.places(here, 300.0, cafe)
        assertEquals(PlaceOrigin.PACK, near.origin)
        assertFalse("300 m stays in the window", near.partial)
        assertEquals(listOf("Café de Vliet"), near.value.map { it.name() })
        assertEquals(listOf("Café de Vliet"), local.places(BBox.around(here, 300.0), cafe).value.map { it.name() })
        // the bench is no place, the far café beyond the circle
        assertEquals(listOf("Café de Vliet", "Albert Heijn"), local.places(here, 300.0, TagFilter.Has("name")).value.map { it.name() })

        val ah = Brands.forTags(AH_TAGS)
        assertNotNull(ah)
        assertEquals(listOf("Albert Heijn"), local.branches(ah!!.id, here, 300.0).value.map { it.name() })
        assertEquals(listOf(ah.id), local.brands(BBox.around(here, 300.0)).value.map { it.brand.id })
        assertEquals(setOf("Café de Vliet", "Albert Heijn"), local.quests(BBox.around(here, 300.0)).value.mapNotNull { it.name() }.toSet())

        val farther = local.farther(here, cafe)
        assertEquals(PlaceOrigin.PACK, farther.origin)
        assertEquals("Café de Vliet", farther.value.first().name())
        val each = local.nearestEach(here, mapOf("cafe" to cafe, "supermarket" to supermarket, "cinema" to TagFilter.Equals("amenity", "cinema"))).toList().toMap()
        assertEquals("Café de Vliet", each.getValue("cafe").value.single().name())
        assertEquals("Albert Heijn", each.getValue("supermarket").value.single().name())
        assertTrue(each.getValue("cinema").value.isEmpty())

        val packed = local.inPacks(here, 300.0, cafe)
        assertNotNull(packed)
        assertEquals(listOf("Café de Vliet"), packed!!.value.map { it.name() })
    }

    @Test
    fun offlineTheCategoryRowFindsWhatOnlyTheWindowHolds() = runBlocking {
        val local = offline()
        val finder = LayeredPlaceFinder(local = { local }, overpass = { null }, online = { false })
        val answers = finder.nearby("eat", here, 300.0, 20).toList()
        assertEquals(listOf(PlaceOrigin.PACK), answers.map { it.source })
        assertEquals(listOf("Café de Vliet"), answers.single().places.map { it.name() })
        val many = finder.nearbyMany(listOf("eat"), here, 300.0, 20)
        assertEquals(listOf("Café de Vliet"), many.getValue("eat").places.map { it.name() })
    }

    @Test
    fun aCircleReachingPastTheWindowIsSaidToBeAPart() = runBlocking {
        val local = offline()
        val wide = local.places(here, 2_000.0, cafe)
        assertEquals(PlaceOrigin.PACK, wide.origin)
        assertTrue(wide.partial)
        assertEquals(listOf("Café de Vliet", "Café de Overkant"), wide.value.map { it.name() })
        // nothing kept at all: nothing on the phone covers it, which the screens say
        assertNull(offline(tmp.newFolder("none")).places(here, 300.0, cafe).origin)
    }

    @Test
    fun onlineThePhonesAnswerComesBeforeOverpassAndOverpassAddsToIt() = runBlocking {
        val first = CountDownLatch(1)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                // Overpass answers only once the window's answer is shown (at most 20 s: else the test fails below)
                released.set(first.await(20, TimeUnit.SECONDS))
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val dir = keptDir()
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = WindowPlaces({ dir }))
        val answers = ArrayList<LocalAnswer<List<app.atlas.model.Place>>>()
        // 2 km reaches past the window: the window's part at once, then Overpass for the whole circle
        local.placesAsFound(here, 2_000.0, cafe).collect {
            answers += it
            first.countDown()
        }
        assertTrue("the window's answer came before Overpass's", released.get())
        assertEquals(listOf(PlaceOrigin.PACK, PlaceOrigin.ONLINE), answers.map { it.origin })
        assertEquals(listOf("Café de Vliet", "Café de Overkant"), answers[0].value.map { it.name() })
        assertTrue(answers[0].partial)
        assertEquals(listOf("Café de Vliet", "Café de Overkant", "Café Ver Weg"), answers[1].value.map { it.name() })
        assertEquals(1, asked.get())
        // a circle the window holds whole asks nothing online
        assertEquals(PlaceOrigin.PACK, local.places(here, 300.0, cafe).origin)
        assertEquals(1, asked.get())
    }

    @Test
    fun aWindowThePhoneKeepsNoDataForComesFromAtlasPacksBeforeOverpass() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        var reads = 0
        var online = true
        val empty = tmp.newFolder("empty")
        val windows = WindowPlaces(
            { empty },
            packed = { x, y ->
                if (!online) throw IOException("offline")
                reads++
                if (x == tile.x && y == tile.y) gz(window()) else null
            },
        )
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { online }, windowPlaces = windows)
        val answers = local.placesAsFound(here, 300.0, cafe).toList()
        assertEquals(listOf(PlaceOrigin.PACK), answers.map { it.origin })
        assertEquals(listOf("Café de Vliet"), answers.single().value.map { it.name() })
        assertFalse(answers.single().partial)
        assertEquals(0, asked.get())
        assertEquals(1, reads)
        // read once: offline now, the window's places are still held
        online = false
        assertEquals(listOf("Café de Vliet"), local.places(here, 300.0, cafe).value.map { it.name() })
        assertEquals(1, reads)
    }

    private fun gz(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    /** A window's Overpass answer around [here]: a café 60 m off, an Albert Heijn 150 m off, a bench, a café 600 m off. */
    private fun window(): String {
        return """{"version":0.6,"generator":"test","osm3s":{"timestamp_osm_base":"2026-11-10T00:00:00Z"},"elements":[
{"type":"node","id":1,${at(40.0, 45.0)},"tags":{"amenity":"cafe","name":"Café de Vliet"}},
{"type":"node","id":2,${at(-150.0, 0.0)},"tags":{"shop":"supermarket","name":"Albert Heijn","brand":"Albert Heijn","brand:wikidata":"Q1653985"}},
{"type":"node","id":3,${at(10.0, 10.0)},"tags":{"amenity":"bench"}},
{"type":"node","id":4,${at(600.0, 0.0)},"tags":{"amenity":"cafe","name":"Café de Overkant"}}
]}"""
    }

    /** Overpass's answer for 2 km around [here]: the window's two cafés, and one 1.5 km off. */
    private val OVERPASS_FAR: String
        get() = """{"version":0.6,"elements":[
{"type":"node","id":1,${at(40.0, 45.0)},"tags":{"amenity":"cafe","name":"Café de Vliet"}},
{"type":"node","id":4,${at(600.0, 0.0)},"tags":{"amenity":"cafe","name":"Café de Overkant"}},
{"type":"node","id":9,${at(1_500.0, 0.0)},"tags":{"amenity":"cafe","name":"Café Ver Weg"}}
]}"""

    /** "lat":…,"lon":… [northM] north and [eastM] east of [here]. */
    private fun at(northM: Double, eastM: Double): String {
        val lat = here.lat + northM / 111_320.0
        val lon = here.lon + eastM / (111_320.0 * Math.cos(Math.toRadians(here.lat)))
        return "\"lat\":$lat,\"lon\":$lon"
    }

    private companion object {
        val AH_TAGS = mapOf("shop" to "supermarket", "name" to "Albert Heijn", "brand" to "Albert Heijn", "brand:wikidata" to "Q1653985")
    }
}
