package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
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

    private fun offline(dir: File = keptDir()) = LocalData(stores = { null }, packs = { null }, windowPlaces = WindowPlaces({ dir }), countryAt = { null })

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

        val ah = Brands.forTags(AH_TAGS, "NL")
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
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = WindowPlaces({ dir }), countryAt = { null })
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
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { online }, windowPlaces = windows, countryAt = { null })
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

    /**
     * The wheel's question ([LayeredPlaceFinder.nearbyMany]) for a circle the phone's one kept window holds only a part
     * of goes on to Overpass for the rest, as the same category asked alone ([LayeredPlaceFinder.nearby]) does (the
     * reviewer, 17 November 2026: until then the part was taken for the whole, an empty list included).
     */
    @Test
    fun theWheelAsksOnWhereTheWindowHoldsOnlyAPartOfTheCircle() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        // the one window the phone keeps holds a supermarket and no café; 2 km reaches far past it
        val dir = tmp.newFolder("one").also {
            File(it, "${tile.z}-${tile.x}-${tile.y}.json.gz").writeBytes(
                gz("""{"version":0.6,"elements":[{"type":"node","id":2,${at(-150.0, 0.0)},"tags":{"shop":"supermarket","name":"Albert Heijn"}}]}"""),
            )
        }
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = WindowPlaces({ dir }), countryAt = { null })
        val finder = LayeredPlaceFinder(local = { local }, overpass = { op }, online = { true })
        val alone = finder.nearby("eat", here, 2_000.0, 20).toList()
        assertEquals(listOf("Café de Vliet", "Café de Overkant", "Café Ver Weg"), alone.last().places.map { it.name() })
        val many = finder.nearbyMany(listOf("eat"), here, 2_000.0, 20)
        assertEquals(listOf("Café de Vliet", "Café de Overkant", "Café Ver Weg"), many.getValue("eat").places.map { it.name() })
    }

    /**
     * A circle of more windows than [WindowPlaces] holds at once keeps the windows at its middle, whichever lookup asks:
     * here the nine a kilometre around, from Atlas's packs, then five by five (the reviewer, 17 November 2026: held by
     * when they were last read, the windows a wide circle fetched pushed out those nearest the user, and the lookups of
     * one circle held 9, 7, 5 and 5 of the nine).
     */
    @Test
    fun aWideCircleKeepsTheWindowAtItsMiddle() = runBlocking {
        val empty = tmp.newFolder("wide")
        val windows = WindowPlaces({ empty }, packed = { x, y -> cafeAt(x, y) })
        val near = BBox.around(here, 1_000.0)
        val first = windows.fetched(near, here, 10_000L)!!
        assertTrue("the nine come from the packs whole", first.covers(near))
        assertEquals(block, first.places.map { it.name() }.toSet())
        val box = BBox.around(here, 3_500.0)
        assertTrue("more windows than are held at once", Mercator.tilesIn(box, WindowPlaces.LEVEL).size > WindowPlaces.HELD)
        val holds = (1..4).map { windows.fetched(box, here, 10_000L)!!.places.count { it.name() in block } }
        assertEquals("each lookup of the same circle holds the nine windows nearest its middle", listOf(9, 9, 9, 9), holds)
    }

    /**
     * Offline, a circle the phone keeps data for in more windows than are held at once is read once: each lookup reads
     * at most nine windows it does not hold, nearest first, and none past those it can hold, so once they are held the
     * next lookup reads none (the reviewer, 17 November 2026: up to nine window files read and parsed again at every
     * lookup, for ever).
     */
    @Test
    fun offlineAWideCircleIsReadOnce() = runBlocking {
        val dir = tmp.newFolder("kept")
        for (dx in -3..3) for (dy in -3..3) File(dir, "${tile.z}-${tile.x + dx}-${tile.y + dy}.json.gz").writeBytes(cafeAt(tile.x + dx, tile.y + dy))
        var reads = 0
        val windows = WindowPlaces({ dir }, open = { reads++; it.inputStream() })
        val box = BBox.around(here, 5_000.0)
        assertEquals("seven by seven", 49, Mercator.tilesIn(box, WindowPlaces.LEVEL).size)
        val read = ArrayList<Int>()
        val holds = (1..4).map {
            val before = reads
            val held = windows.held(box, here)!!
            read += reads - before
            held.places.count { it.name() in block }
        }
        assertEquals("each lookup holds the nine windows nearest its middle", listOf(9, 9, 9, 9), holds)
        assertEquals("the windows each lookup reads", listOf(9, 9, 0, 0), read)
    }

    /**
     * Over the windows held at once, those farthest from the last lookup's middle go, never one of its own: a walk east
     * and back finds the windows it passed still held, and the packs are asked for each window once, but for the
     * column the walk left farthest behind (the reviewer, 17 November 2026: held by when they were last read).
     */
    @Test
    fun theWindowsHeldAreThoseNearestTheLastLookup() = runBlocking {
        // the packs are read three at a time
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        val empty = tmp.newFolder("walk")
        val windows = WindowPlaces({ empty }, packed = { x, y -> sent += "$x/$y"; cafeAt(x, y) })
        for (dx in listOf(0, 1, 2, 3, 4, 0, 1, 2)) {
            val c = TileId(WindowPlaces.LEVEL, tile.x + dx, tile.y).bbox.center
            val box = BBox.around(c, 1_000.0)
            assertTrue("whole at ${tile.x + dx}", windows.fetched(box, c, 10_000L)!!.covers(box))
        }
        // nine, then a column of three at each step east; back at the start, the column it left first, and nothing more
        val again = (-1..1).map { dy -> "${tile.x - 1}/${tile.y + dy}" }
        assertEquals(9 + 4 * 3 + 3, sent.size)
        assertEquals(again.toSet(), sent.groupingBy { it }.eachCount().filterValues { it > 1 }.keys)
    }

    /**
     * Where more windows are missing than the packs are asked for at once, the packs cannot make the circle whole:
     * Overpass is asked at once, and no window is waited for; where few enough are missing, the packs make it whole and
     * Overpass is not asked (the reviewer, 17 November 2026: nine windows were waited for, up to 10 s, before Overpass,
     * for a circle of any size).
     */
    @Test
    fun overpassIsAskedAtOnceWhereThePacksCannotMakeTheCircleWhole() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val sent = AtomicInteger()
        val empty = tmp.newFolder("none")
        val windows = WindowPlaces({ empty }, packed = { x, y -> sent.incrementAndGet(); cafeAt(x, y) })
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null })
        // 2 km around the window's east edge: four windows by three, twelve missing
        val edge = LatLon(here.lat, tile.bbox.east)
        assertEquals(12, Mercator.tilesIn(BBox.around(edge, 2_000.0), WindowPlaces.LEVEL).size)
        val wide = local.placesAsFound(edge, 2_000.0, cafe).toList()
        assertEquals("the packs sent no window", 0, sent.get())
        assertEquals(listOf(PlaceOrigin.ONLINE), wide.map { it.origin })
        assertEquals(1, asked.get())
        // a kilometre around the window: nine missing, from the packs, whole
        assertFalse(local.places(here, 1_000.0, cafe).partial)
        assertEquals(9, sent.get())
        // now the circle at the edge lacks three: they complete it, and Overpass is not asked
        val whole = local.placesAsFound(edge, 2_000.0, cafe).toList()
        assertEquals(listOf(PlaceOrigin.PACK, PlaceOrigin.PACK), whole.map { it.origin })
        assertEquals(listOf(true, false), whole.map { it.partial })
        assertEquals(12, sent.get())
        assertEquals(1, asked.get())
    }

    /** The window at [tile]'s middle and the eight around it, by the names [cafeAt] gives their cafés. */
    private val block: Set<String>
        get() = (-1..1).flatMap { dx -> (-1..1).map { dy -> "${tile.x + dx}/${tile.y + dy}" } }.toSet()

    /** Window ([x], [y])'s data, gzipped: one café at its middle, named "x/y". */
    private fun cafeAt(x: Int, y: Int): ByteArray {
        val c = TileId(WindowPlaces.LEVEL, x, y).bbox.center
        return gz("""{"elements":[{"type":"node","id":${x * 100_000L + y},"lat":${c.lat},"lon":${c.lon},"tags":{"amenity":"cafe","name":"$x/$y"}}]}""")
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
