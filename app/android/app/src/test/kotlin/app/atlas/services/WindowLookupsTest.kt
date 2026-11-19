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
import app.atlas.render.data.ElementType
import app.atlas.render.data.MemoryBoxes
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
     * one circle held 9, 7, 5 and 5 of the nine). Each lookup waits for what it asks of the packs, and asks for the
     * nearest it lacks: the first the nine around the nine, the rest none (the app maintainer, 18 November 2026, batch 4
     * C25: not waited for, the circle's lookups were counted before anything they asked for came, so nothing they
     * fetched could push the nine out).
     */
    @Test
    fun aWideCircleKeepsTheWindowAtItsMiddle() = runBlocking {
        val empty = tmp.newFolder("wide")
        val sent = AtomicInteger()
        val windows = WindowPlaces({ empty }, packed = { x, y -> sent.incrementAndGet(); cafeAt(x, y) })
        val near = BBox.around(here, 1_000.0)
        val first = windows.fetched(near, here, 10_000L)!!
        assertTrue("the nine come from the packs whole", first.covers(near))
        assertEquals(block, first.places.map { it.name() }.toSet())
        assertEquals(9, sent.get())
        val box = BBox.around(here, 3_500.0)
        assertTrue("more windows than are held at once", Mercator.tilesIn(box, WindowPlaces.LEVEL).size > WindowPlaces.HELD)
        val asked = ArrayList<Int>()
        val holds = (1..4).map {
            val before = sent.get()
            val held = windows.fetched(box, here, 10_000L, waitForPart = true)!!
            asked += sent.get() - before
            held.places.count { it.name() in block }
        }
        assertEquals("the first lookup asks the packs for the nine nearest it lacks, the rest for none", listOf(9, 0, 0, 0), asked)
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
     * Overpass is asked at once, and the nine nearest windows the packs are asked for are not waited for; once they have
     * come the circle lacks three, which complete it, and Overpass is not asked (the reviewer, 17 November 2026: nine
     * windows were waited for, up to 10 s, before Overpass, for a circle of any size; the app maintainer, 18 November
     * 2026: and then none was read).
     */
    @Test
    fun overpassIsAskedAtOnceWhereThePacksCannotMakeTheCircleWhole() = runBlocking {
        // the packs send nothing before Overpass is asked: a lookup that waited for them would wait its 10 s
        val overpassAsked = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                overpassAsked.countDown()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        val empty = tmp.newFolder("none")
        val windows = WindowPlaces({ empty }, packed = { x, y -> overpassAsked.await(20, TimeUnit.SECONDS); sent += "$x/$y"; cafeAt(x, y) })
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null })
        // 2 km around [off]: four windows by three, twelve missing
        val box = BBox.around(off, 2_000.0)
        assertEquals(12, Mercator.tilesIn(box, WindowPlaces.LEVEL).size)
        val started = System.nanoTime()
        val wide = local.placesAsFound(off, 2_000.0, cafe).toList()
        assertTrue("Overpass was not held up by the packs", System.nanoTime() - started < 5_000_000_000L)
        assertEquals(PlaceOrigin.ONLINE, wide.last().origin)
        assertEquals(1, asked.get())
        // the nine nearest come from the packs all the same
        assertEquals(9, heldIn(windows, box, off, 9))
        assertEquals(nineNearest, sent.toSet())
        assertEquals(9, sent.size)
        // now the circle lacks three: they complete it, and Overpass is not asked
        val whole = local.placesAsFound(off, 2_000.0, cafe).toList()
        assertEquals(listOf(PlaceOrigin.PACK, PlaceOrigin.PACK), whole.map { it.origin })
        assertEquals(listOf(true, false), whole.map { it.partial })
        assertEquals(12, sent.size)
        assertEquals(1, asked.get())
    }

    /**
     * Where the packs cannot make a box whole, the nearest of its windows they can give are read all the same, at most
     * nine, nearest its middle first, and not waited for: where more than nine are missing, where the box has more
     * windows than are held at once, and where one of them is known to be in no pack, as every box that crosses a border
     * has (the app maintainer, 18 November 2026: none was read, so where Overpass then failed the answer was empty where
     * it had held the nine nearest windows). A widening search around one middle reads the same nearest windows once.
     */
    @Test
    fun theNearestWindowsAreReadWhereThePacksCannotMakeTheBoxWhole() = runBlocking {
        // more than nine missing: twelve, the nine nearest read; widened past what is held at once, the same nine, once
        val gate = CountDownLatch(1)
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        val first = tmp.newFolder("twelve")
        val windows = WindowPlaces({ first }, packed = { x, y -> gate.await(20, TimeUnit.SECONDS); sent += "$x/$y"; cafeAt(x, y) })
        val twelve = BBox.around(off, 2_000.0)
        val wider = BBox.around(off, 3_500.0)
        assertTrue(Mercator.tilesIn(wider, WindowPlaces.LEVEL).size > WindowPlaces.HELD)
        val started = System.nanoTime()
        windows.fetched(twelve, off, 10_000L)
        windows.fetched(wider, off, 10_000L)
        assertTrue("not waited for", System.nanoTime() - started < 5_000_000_000L)
        gate.countDown()
        assertEquals(9, heldIn(windows, twelve, off, 9))
        assertEquals(nineNearest, sent.toSet())
        assertEquals("each once", 9, sent.size)

        // a box of more windows than are held at once, alone: the nine around its middle
        val alone = java.util.Collections.synchronizedList(ArrayList<String>())
        val second = tmp.newFolder("wide")
        val wide = WindowPlaces({ second }, packed = { x, y -> alone += "$x/$y"; cafeAt(x, y) })
        val box = BBox.around(here, 3_500.0)
        wide.fetched(box, here, 10_000L)
        assertEquals(9, heldIn(wide, box, here, 9))
        assertEquals(block, alone.toSet())

        // a border: east of it no pack holds a window; a box across it still reads the windows west of it
        val border = tile.x + 2
        val across = java.util.Collections.synchronizedList(ArrayList<String>())
        val third = tmp.newFolder("border")
        val east = WindowPlaces({ third }, packed = { x, y -> across += "$x/$y"; if (x >= border) null else cafeAt(x, y) })
        val past = TileId(WindowPlaces.LEVEL, border, tile.y).bbox.center
        east.fetched(BBox.around(past, 1_000.0), past, 10_000L)
        assertEquals("the column before the border is held, those past it are in no pack", 3, east.held(BBox.around(past, 1_000.0), past)!!.places.size)
        across.clear()
        val before = TileId(WindowPlaces.LEVEL, border - 1, tile.y).bbox.center
        val box3 = BBox.around(before, 1_000.0)
        east.fetched(box3, before, 10_000L)
        assertEquals(6, heldIn(east, box3, before, 6))
        assertEquals((-1..1).map { dy -> "${border - 2}/${tile.y + dy}" }.toSet(), across.toSet())
    }

    /**
     * Where Overpass fails at once for a circle the packs cannot make whole (a 504 here; Overpass switched off in
     * Sources, a 429, every server resting, a captive portal), the answer holds the windows the packs were asked for:
     * they are waited for, at most for what is left of the wait (the app maintainer, 18 November 2026, batch 5 A1: it
     * answered before they came, with none of them, where 38ddafa1 waited for the nine nearest first). The places'
     * lookups, the category row and the Where wheel, the shop screens; the eat screens in `EatWindowsTest`.
     */
    @Test
    fun whereOverpassFailsAtOnceTheAnswerHoldsTheWindowsThePacksSend() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(504).body("busy").build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        // each window comes 300 ms after it is asked, three at a time: after Overpass's 504
        fun fresh(): LocalData {
            val dir = tmp.newFolder()
            val windows = WindowPlaces({ dir }, packed = { x, y -> Thread.sleep(300); cafeAt(x, y) })
            return LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null }, windowWaitMs = 5_000L)
        }

        val places = fresh().placesAsFound(off, 2_000.0, cafe).toList().last()
        assertNotNull(places.error)
        assertEquals(PlaceOrigin.PACK, places.origin)
        assertTrue(places.partial)
        assertEquals(nearOff, places.value.map { it.name() }.toSet())

        var local = fresh()
        val finder = LayeredPlaceFinder(local = { local }, overpass = { op }, online = { true })
        val alone = finder.nearby("eat", off, 2_000.0, 20).toList().last()
        assertNotNull(alone.error)
        assertEquals(nearOff, alone.places.map { it.name() }.toSet())
        local = fresh()
        val wheel = finder.nearbyMany(listOf("eat"), off, 2_000.0, 20).getValue("eat")
        assertNotNull(wheel.error)
        assertEquals(nearOff, wheel.places.map { it.name() }.toSet())

        local = fresh()
        val shop = app.atlas.ui.shop.ShopFinder(store = { app.atlas.search.Regions() }, local = { local }, overpass = { op }, online = { true })
            .around("cafes", cafe, off, 2_000.0).toList().last()
        assertNotNull(shop.error)
        assertEquals(nearOff, shop.places.map { it.name() }.toSet())
        assertEquals(4, asked.get())
    }

    /**
     * Where Overpass is slow (up to about 40 s a server), the packs' part of a circle they cannot make whole shows as
     * soon as the windows they were asked for have come, within the wait, not when Overpass answers (the app
     * maintainer, 18 November 2026, batch 5 A1: 38ddafa1 showed it after one to three seconds, and asked Overpass
     * after). The places' lookups, the category row, the shop screens; the eat screens in `EatWindowsTest`.
     */
    @Test
    fun whereOverpassIsSlowThePacksPartComesWithinTheWait() = runBlocking {
        val gate = java.util.concurrent.atomic.AtomicReference(CountDownLatch(1))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                gate.get().await(20, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        fun fresh(): LocalData {
            val dir = tmp.newFolder()
            val windows = WindowPlaces({ dir }, packed = { x, y -> Thread.sleep(100); cafeAt(x, y) })
            return LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null }, windowWaitMs = 3_000L)
        }

        val places = untilTheWindowsShow(gate, fresh().placesAsFound(off, 2_000.0, cafe)) { a -> a.value.mapNotNull { it.name() }.toSet() }
        assertNotNull("the places' lookup did not show the windows before Overpass answered", places)
        assertTrue("the places' lookup showed the windows after $places ms", places!! < 3_500)

        var local = fresh()
        val finder = LayeredPlaceFinder(local = { local }, overpass = { op }, online = { true })
        val row = untilTheWindowsShow(gate, finder.nearby("eat", off, 2_000.0, 20)) { a -> a.places.mapNotNull { it.name() }.toSet() }
        assertNotNull("the category row did not show the windows before Overpass answered", row)
        assertTrue("the category row showed the windows after $row ms", row!! < 3_500)

        local = fresh()
        val shops = app.atlas.ui.shop.ShopFinder(store = { app.atlas.search.Regions() }, local = { local }, overpass = { op }, online = { true })
        val shop = untilTheWindowsShow(gate, shops.around("cafes", cafe, off, 2_000.0)) { a -> a.places.mapNotNull { it.name() }.toSet() }
        assertNotNull("the shop screen did not show the windows before Overpass answered", shop)
        assertTrue("the shop screen showed the windows after $shop ms", shop!! < 3_500)
        assertEquals(3, asked.get())
    }

    /**
     * How long until an answer of [flow] holds the cafés of the nine nearest windows within 2 km of [off] ([names] reads
     * an answer's), at most 8 s; then Overpass, held at [gate], answers, and the flow is followed to its end. Null where
     * none held them before.
     */
    private suspend fun <T> untilTheWindowsShow(gate: java.util.concurrent.atomic.AtomicReference<CountDownLatch>, flow: Flow<T>, names: (T) -> Set<String>): Long? =
        coroutineScope {
            gate.set(CountDownLatch(1))
            val started = System.nanoTime()
            val seen = java.util.concurrent.atomic.AtomicLong(-1)
            val following = launch(Dispatchers.IO) {
                flow.collect { if (seen.get() < 0 && names(it) == nearOff) seen.set((System.nanoTime() - started) / 1_000_000) }
            }
            while (seen.get() < 0 && System.nanoTime() - started < 8_000_000_000L) delay(20)
            gate.get().countDown()
            following.join()
            seen.get().takeIf { it >= 0 }
        }

    /**
     * The search's pool asks nothing after the packs ([LocalData.inWindowPacks] with `waitForPart`): it waits for the
     * nearest windows however much of the circle they make, as it did before the packs were waited for only where they
     * make a box whole (the app maintainer, 18 November 2026).
     */
    @Test
    fun aLookupThatAsksNothingAfterWaitsForTheNearestWindows() = runBlocking {
        val dir = tmp.newFolder("pool")
        val windows = WindowPlaces({ dir }, packed = { x, y -> Thread.sleep(50); cafeAt(x, y) })
        val local = LocalData(stores = { null }, packs = { null }, isOnline = { true }, windowPlaces = windows, countryAt = { null })
        val pool = local.inWindowPacks(off, 2_000.0, cafe, waitForPart = true)!!
        assertTrue(pool.partial)
        assertEquals(nearOff, pool.value.map { it.name() }.toSet())
    }

    /**
     * A pack's box is no proof of what it holds (the app maintainer, 18 November 2026, batch 5 A2: the box of the
     * Netherlands' extract reaches Antwerp and Aachen). 2 km around [here], in a pack that holds the column of windows
     * west of [tile] and whose box ends at [tile]'s east edge: [tile]'s column lies inside the box, but the pack holds
     * none of its places (Belgium's, as Lanaken's beside Maastricht). Its windows are read as on 38ddafa1, and its café
     * is found; where its window cannot come, the circle is not whole without it, and Overpass is asked. With batch 4's
     * B12 the windows inside the box were the pack's: never asked or read, and counted whole, so the café was lost and
     * Overpass not asked; and the pack's box counted for the windows it lacked before batch 4 too.
     */
    @Test
    fun aPacksBoxIsNoProofOfWhatItHolds() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(OVERPASS_FAR).build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val box = BBox.around(here, 2_000.0)
        val tiles = Mercator.tilesIn(box, WindowPlaces.LEVEL)
        assertEquals(9, tiles.size)
        val pack = PackFeatures(
            listOf(MemoryBoxes(tiles.filter { it.x == tile.x - 1 }.map { cafeNode(it.x, it.y) }, BBox(box.west - 0.1, box.south - 0.1, tile.bbox.east, box.north + 0.1))),
        )
        assertTrue(pack.covers(tile.bbox))
        assertFalse(pack.covers(box))
        val belgian = "${tile.x}/${tile.y}"
        fun local(comes: Boolean): LocalData {
            val dir = tmp.newFolder()
            val windows = WindowPlaces({ dir }, packed = { x, y -> if (!comes && "$x/$y" == belgian) null else cafeAt(x, y) })
            return LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null })
        }
        val found = local(comes = true).placesAsFound(here, 2_000.0, cafe).toList()
        assertEquals(PlaceOrigin.PACK, found.last().origin)
        assertTrue("the Belgian café", belgian in found.last().value.map { it.name() })
        assertFalse(found.last().partial)
        assertEquals(0, asked.get())
        // its window does not come: the circle is a part, and Overpass is asked for the whole
        val without = local(comes = false).placesAsFound(here, 2_000.0, cafe).toList()
        assertTrue(without.filter { it.origin == PlaceOrigin.PACK }.all { it.partial })
        assertEquals(PlaceOrigin.ONLINE, without.last().origin)
        assertEquals(1, asked.get())
    }

    /**
     * Two lookups at once each hold the windows they wait for (the app maintainer, 18 November 2026, batch 4 B13): a
     * lookup far off, between the first's asking and its windows' coming, made its middle and its own windows the ones
     * held, so as the first's came, over the eighteen held, each went as it came (the farthest from that middle), and
     * the first held none of what it waited for.
     */
    @Test
    fun twoLookupsAtOnceEachHoldWhatTheyWaitFor() = runBlocking {
        val dir = tmp.newFolder("two")
        val farOff = TileId(WindowPlaces.LEVEL, tile.x + 20, tile.y)
        val beside = TileId(WindowPlaces.LEVEL, tile.x + 23, tile.y)
        // the phone keeps the windows far off and those beside them; those around [tile] come from the packs
        for (t in listOf(farOff, beside)) for (dx in -1..1) for (dy in -1..1) {
            File(dir, "${WindowPlaces.LEVEL}-${t.x + dx}-${t.y + dy}.json.gz").writeBytes(cafeAt(t.x + dx, t.y + dy))
        }
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val windows = WindowPlaces({ dir }, packed = { x, y -> started.countDown(); gate.await(20, TimeUnit.SECONDS); cafeAt(x, y) })
        assertEquals(9, windows.held(nine(beside), beside.bbox.center)?.places?.size)
        val first = async(Dispatchers.IO) { windows.fetched(nine(tile), here, 20_000) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // the second, far off, while the first waits: what it holds is held beside what the first waits for
        assertEquals(9, windows.held(nine(farOff), farOff.bbox.center)?.places?.size)
        gate.countDown()
        val got = first.await()
        assertNotNull("the first holds what it waited for", got)
        assertTrue(got!!.covers(nine(tile)))
        assertEquals(block, got.places.map { it.name() }.toSet())
    }

    /**
     * The Where wheel waits once for the windows Atlas's packs send, however many kinds it shows (the app maintainer, 18
     * November 2026, batch 4 B14): each of its kinds waited again, the whole wait, for the windows still coming, so its
     * eleven could wait eleven times as long. Here the wait is a second, the window comes after it.
     */
    @Test
    fun theWheelWaitsOnceForTheWindows() = runBlocking {
        val gate = CountDownLatch(1)
        val empty = tmp.newFolder("wheel")
        val windows = WindowPlaces({ empty }, packed = { x, y -> gate.await(20, TimeUnit.SECONDS); cafeAt(x, y) })
        val local = LocalData(stores = { null }, packs = { null }, isOnline = { true }, windowPlaces = windows, countryAt = { null }, windowWaitMs = 1_000L)
        val finder = LayeredPlaceFinder(local = { local }, overpass = { null }, online = { true })
        val started = System.nanoTime()
        finder.nearbyMany(listOf("restaurants", "drinks", "museums", "parks"), here, 300.0, 20)
        val took = (System.nanoTime() - started) / 1_000_000
        gate.countDown()
        assertTrue("the wheel waited $took ms for four kinds", took < 2_500)
        assertTrue("the wheel waited for the windows: $took ms", took >= 1_000)
    }

    /**
     * Where Overpass fails, the answer holds every place shown before it, though another lookup let its windows go
     * meanwhile (the app maintainer, 19 November 2026, batch 6 A3): the read after the failure held only the window the
     * phone keeps, where 38ddafa1 kept what it had shown. The places' lookup here; the category row, the wheel and the
     * shop screens below, the eat screens in `EatWindowsTest`.
     */
    @Test
    fun aFailureHoldsWhatWasShownThoughAnotherLookupLetItsWindowsGo() {
        val (error, names) = afterAnotherLookup { local, _, report ->
            local.placesAsFound(off, 2_000.0, cafe).collect { report(it.error, it.value.mapNotNull { p -> p.name() }.toSet()) }
        }
        assertNotNull(error)
        assertEquals(nearOff, names)
    }

    @Test
    fun theCategoryRowsFailureHoldsWhatWasShown() {
        val (error, names) = afterAnotherLookup { local, op, report ->
            LayeredPlaceFinder(local = { local }, overpass = { op }, online = { true }).nearby("eat", off, 2_000.0, 20)
                .collect { report(it.error, it.places.mapNotNull { p -> p.name() }.toSet()) }
        }
        assertNotNull(error)
        assertEquals(nearOff, names)
    }

    /** The wheel shows nothing till it answers: what the windows brought is what it had (38ddafa1 waited for them). */
    @Test
    fun theWheelsFailureHoldsWhatTheWindowsBrought() {
        val (error, names) = afterAnotherLookup(byAnswer = false) { local, op, report ->
            val got = LayeredPlaceFinder(local = { local }, overpass = { op }, online = { true }).nearbyMany(listOf("eat"), off, 2_000.0, 20).getValue("eat")
            report(got.error, got.places.mapNotNull { it.name() }.toSet())
        }
        assertNotNull(error)
        assertEquals(nearOff, names)
    }

    @Test
    fun theShopScreensFailureHoldsWhatWasShown() {
        val (error, names) = afterAnotherLookup { local, op, report ->
            app.atlas.ui.shop.ShopFinder(store = { app.atlas.search.Regions() }, local = { local }, overpass = { op }, online = { true })
                .around("cafes-shown", cafe, off, 2_000.0).collect { report(it.error, it.places.mapNotNull { p -> p.name() }.toSet()) }
        }
        assertNotNull(error)
        assertEquals(nearOff, names)
    }

    /**
     * Batch 6 A3's scene: a lookup 2 km around [off], whose windows came from Atlas's packs while Overpass waits;
     * then a second lookup far west that holds eighteen windows (the nine the phone keeps around its middle, nine from
     * the packs) and looks last, so at the first's close the trim takes the first's; then Overpass fails for the
     * first. The phone keeps [off]'s own window, so what it holds after is never none. [lookup] runs the first and
     * gives each answer to its `report` as the error and the names; where [byAnswer] the second begins once an answer
     * held [nearOff], else (the wheel, which shows nothing) once Overpass was asked, the phone holds the first's ten
     * windows, and a little more, for the first's own look at them. The last answer.
     */
    private fun afterAnotherLookup(byAnswer: Boolean = true, lookup: suspend (LocalData, Overpass, (String?, Set<String>) -> Unit) -> Unit): Pair<String?, Set<String>> = runBlocking {
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                requested.countDown()
                release.await(20, TimeUnit.SECONDS)
                return MockResponse.Builder().code(504).body("busy").build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val far = TileId(WindowPlaces.LEVEL, tile.x - 6, tile.y)
        val dir = tmp.newFolder("shown")
        File(dir, "${tile.z}-${tile.x}-${tile.y}.json.gz").writeBytes(cafeAt(tile.x, tile.y))
        for (dx in -1..1) for (dy in -1..1) File(dir, "${WindowPlaces.LEVEL}-${far.x + dx}-${far.y + dy}.json.gz").writeBytes(cafeAt(far.x + dx, far.y + dy))
        val windows = WindowPlaces({ dir }, packed = { x, y -> cafeAt(x, y) })
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null })
        val shown = CountDownLatch(1)
        val answers = java.util.Collections.synchronizedList(ArrayList<Pair<String?, Set<String>>>())
        val first = launch(Dispatchers.IO) {
            lookup(local, op) { error, names ->
                answers += error to names
                if (names == nearOff) shown.countDown()
            }
        }
        if (byAnswer) assertTrue("the first lookup's windows showed", shown.await(10, TimeUnit.SECONDS))
        else {
            assertTrue("Overpass was asked", requested.await(10, TimeUnit.SECONDS))
            assertEquals("the first lookup's windows came", 10, heldIn(windows, BBox.around(off, 2_000.0), off, 10))
            Thread.sleep(200)
        }
        val farBox = BBox.around(far.bbox.center, 3_000.0)
        val held = windows.fetched(farBox, far.bbox.center, 10_000L, waitForPart = true)
        assertEquals("the second lookup holds eighteen windows", 18, held?.places?.size)
        // the second's the last look before Overpass answers: its middle and its windows are the ones held
        windows.held(farBox, far.bbox.center)
        release.countDown()
        first.join()
        assertEquals("Overpass was asked once", 1, asked.get())
        answers.last()
    }

    /**
     * A lookup cancelled as it starts leaves nothing waited for (the app maintainer, 19 November 2026, batch 6 A4): the
     * windows it had asked the packs for stayed waited for, for ever, as `withContext` dropped the fetch it made, so
     * every later lookup's windows went in their place. Here the lookup is cancelled while it reads the regions, on
     * the dispatcher the category row runs on; then a lookup east, one farther east, and the first east again: its
     * windows are still held, so the packs are not asked for them again.
     */
    @Test
    fun aLookupCancelledAsItStartsLeavesNothingWaitedFor() = runBlocking {
        val gated = java.util.concurrent.atomic.AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = AtomicInteger()
        val empty = tmp.newFolder("cancelled")
        val windows = WindowPlaces({ empty }, packed = { x, y -> sent.incrementAndGet(); cafeAt(x, y) })
        val local = LocalData(
            stores = {
                if (gated.get()) {
                    entered.countDown()
                    release.await(20, TimeUnit.SECONDS)
                }
                null
            },
            packs = { null },
            isOnline = { true },
            windowPlaces = windows,
            countryAt = { null },
        )
        val lookup = launch(Dispatchers.Default) {
            local.beside<Result<List<app.atlas.model.Place>>>(here, 1_000.0, cafe, failed = { it.isFailure }, ask = null)
        }
        assertTrue("the lookup reads the regions", entered.await(10, TimeUnit.SECONDS))
        lookup.cancel()
        gated.set(false)
        release.countDown()
        lookup.join()
        // the cancelled lookup's nine windows come all the same
        assertEquals(9, heldIn(windows, nine(tile), here, 9))
        val east = TileId(WindowPlaces.LEVEL, tile.x + 10, tile.y)
        val farther = TileId(WindowPlaces.LEVEL, tile.x + 20, tile.y)
        assertEquals(9, windows.fetched(nine(east), east.bbox.center, 10_000L)?.places?.size)
        assertEquals(9, windows.fetched(nine(farther), farther.bbox.center, 10_000L)?.places?.size)
        val before = sent.get()
        assertEquals(9, windows.fetched(nine(east), east.bbox.center, 10_000L)?.places?.size)
        assertEquals("the windows east asked of the packs again", 0, sent.get() - before)
    }

    /** Waits at most 5 s until [windows] holds [n] windows meeting [box] (one café each), and gives how many it holds. */
    private fun heldIn(windows: WindowPlaces, box: BBox, centre: LatLon, n: Int): Int {
        val until = System.nanoTime() + 5_000_000_000L
        while (true) {
            val have = windows.held(box, centre)?.places?.size ?: 0
            if (have >= n || System.nanoTime() > until) return have
            Thread.sleep(20)
        }
    }

    /** The point [east] of the way east and [south] of the way south across window [t]. */
    private fun inside(t: TileId, east: Double, south: Double): LatLon =
        t.bbox.let { b -> LatLon(b.north - south * (b.north - b.south), b.west + east * (b.east - b.west)) }

    /** A point near [tile]'s east edge, a little north of its middle: 2 km around it meet four windows by three. */
    private val off: LatLon get() = inside(tile, 0.9, 0.4)

    /**
     * The nine windows nearest [off], by the names [cafeAt] gives their cafés: [tile]'s column and the next east, the
     * windows west and east of [tile], and the one north-west of it; of the twelve 2 km around [off], not the windows
     * south-west, north-east and south-east.
     */
    private val nineNearest: Set<String>
        get() = nearOff + setOf("${tile.x - 1}/${tile.y}", "${tile.x + 2}/${tile.y}", "${tile.x - 1}/${tile.y - 1}")

    /** Of [nineNearest], the cafés within 2 km of [off]: those of [tile]'s column and the next east. */
    private val nearOff: Set<String>
        get() = (0..1).flatMap { dx -> (-1..1).map { dy -> "${tile.x + dx}/${tile.y + dy}" } }.toSet()

    /** The window at [tile]'s middle and the eight around it, by the names [cafeAt] gives their cafés. */
    private val block: Set<String>
        get() = (-1..1).flatMap { dx -> (-1..1).map { dy -> "${tile.x + dx}/${tile.y + dy}" } }.toSet()

    /** The box of the three by three windows around [t], a hair inside their edges. */
    private fun nine(t: TileId): BBox {
        val nw = TileId(t.z, t.x - 1, t.y - 1).bbox
        val se = TileId(t.z, t.x + 1, t.y + 1).bbox
        return BBox(nw.west + 1e-7, se.south + 1e-7, se.east - 1e-7, nw.north - 1e-7)
    }

    /** Window ([x], [y])'s data, gzipped: one café at its middle, named "x/y". */
    private fun cafeAt(x: Int, y: Int): ByteArray {
        val c = TileId(WindowPlaces.LEVEL, x, y).bbox.center
        return gz("""{"elements":[{"type":"node","id":${x * 100_000L + y},"lat":${c.lat},"lon":${c.lon},"tags":{"amenity":"cafe","name":"$x/$y"}}]}""")
    }

    /** Window ([x], [y])'s café as a pack holds it: [cafeAt]'s, an element. */
    private fun cafeNode(x: Int, y: Int): OsmElement {
        val c = TileId(WindowPlaces.LEVEL, x, y).bbox.center
        return OsmElement(ElementType.NODE, x * 100_000L + y, c.lat, c.lon, mapOf("amenity" to "cafe", "name" to "$x/$y"))
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
