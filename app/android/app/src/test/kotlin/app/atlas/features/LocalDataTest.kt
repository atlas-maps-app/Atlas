package app.atlas.features

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Route
import app.atlas.places.Categories
import app.atlas.places.TagFilter
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.render.data.ElementType
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import app.atlas.search.EvalContext
import app.atlas.search.Query
import app.atlas.services.LocalData
import app.atlas.services.PlaceOrigin
import app.atlas.services.PackShelf
import app.atlas.testing.Fixtures
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * [LocalData]'s layers on the real Westerkerk data: the place store of `data/ams/raw.json`, the bundled pack made from
 * the same extract, and Overpass online (a local server) where neither covers. Under Robolectric for the store's SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalDataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: "../..")
    private val packFile = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
    private val pack by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(packFile)))) }
    private val here = Services.WESTERKERK
    private val utrecht = LatLon(52.0907, 5.1214)

    private val server = MockWebServer()
    private val asked = AtomicInteger()
    private var answer = OVERPASS_CAFES
    private var code = 200

    private fun start(): Overpass {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(answer).build()
            }
        }
        server.start()
        return Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
    }

    @After
    fun stop() = server.close()

    private fun both() = LocalData(stores = { Fixtures.amsStore }, packs = { pack })
    private fun packOnly() = LocalData(stores = { null }, packs = { pack })
    private fun storeOnly() = LocalData(stores = { Fixtures.amsStore }, packs = { null })

    @Test
    fun placesComeFromTheStoreWhereItCoversAndThePackAgrees() = runBlocking {
        val cafe = TagFilter.Equals("amenity", "cafe")
        val store = both().places(here, 300.0, cafe)
        assertEquals(PlaceOrigin.STORE, store.origin)
        assertTrue("${store.value.size}", store.value.size >= 10)
        assertTrue(store.value.all { it.tags["amenity"] == "cafe" && Geo.distance(here, it.location) <= 300.0 })
        assertTrue(store.value.zipWithNext().all { (a, b) -> Geo.distance(here, a.location) <= Geo.distance(here, b.location) })
        val fromPack = packOnly().places(here, 300.0, cafe)
        assertEquals(PlaceOrigin.PACK, fromPack.origin)
        // The same extract, two containers: the same places in the same order.
        assertEquals(store.value.map { it.key }, fromPack.value.map { it.key })
        // The pack keeps every tag; the store drops the bookkeeping.
        assertTrue(fromPack.value.zip(store.value).all { (p, s) -> p.tags.keys.containsAll(s.tags.keys) })
    }

    @Test
    fun byBoxAndWithALimit() = runBlocking {
        val box = BBox.around(here, 200.0)
        val shops = packOnly().places(box, TagFilter.Has("shop"), 5)
        assertEquals(5, shops.value.size)
        assertTrue(shops.value.all { it.location in box && "shop" in it.tags })
    }

    @Test
    fun whereNothingOnThePhoneCoversOverpassAnswers() = runBlocking {
        val op = start()
        val online = LocalData(stores = { Fixtures.amsStore }, packs = { pack }, overpass = { op }, isOnline = { true })
        val far = online.places(utrecht, 300.0, TagFilter.Equals("amenity", "cafe"))
        assertEquals(PlaceOrigin.ONLINE, far.origin)
        assertEquals(listOf("Café Oudegracht"), far.value.map { it.tags["name"] })
        // Where the pack covers, the network is never asked.
        val before = asked.get()
        assertEquals(PlaceOrigin.PACK, LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true }).places(here, 200.0, TagFilter.Has("shop")).origin)
        assertEquals(before, asked.get())
        // A failing server is reported, not thrown.
        code = 500
        answer = "busy"
        val failed = online.places(utrecht, 300.0, TagFilter.Equals("amenity", "cafe"))
        assertEquals(PlaceOrigin.ONLINE, failed.origin)
        assertTrue(failed.value.isEmpty())
        assertNotNull(failed.error)
        // Offline, nothing and no origin.
        val offline = LocalData(stores = { Fixtures.amsStore }, packs = { pack }, overpass = { op }, isOnline = { false })
        assertNull(offline.places(utrecht, 300.0, TagFilter.Equals("amenity", "cafe")).origin)
    }

    @Test
    fun aCountrysStoreDoesNotCoverWhatItsBoxReachesBeyondItsBorders() = runBlocking {
        // the Netherlands' store: its box the country's rectangle (its outline's), which holds Antwerp; its own places
        // by the Westerkerk
        val nl = app.atlas.search.MemoryPlaces(
            listOf(
                app.atlas.model.Place(OsmId(OsmType.RELATION, 47796), LatLon(52.15, 5.30), mapOf("boundary" to "administrative", "name" to "Nederland"), bbox = BBox(3.31, 50.75, 7.23, 53.56)),
                app.atlas.model.Place(OsmId(OsmType.NODE, 1), LatLon(52.3746, 4.8838), mapOf("amenity" to "cafe", "name" to "'t Smalle")),
            ),
        )
        val antwerp = LatLon(51.2194, 4.4011)
        val cafe = TagFilter.Equals("amenity", "cafe")
        val op = start()
        // online, Overpass is asked there, where the store answered with nothing until 30 October
        val online = LocalData(stores = { nl }, packs = { null }, overpass = { op }, isOnline = { true }).places(antwerp, 300.0, cafe)
        assertEquals(PlaceOrigin.ONLINE, online.origin)
        assertEquals(1, asked.get())
        // offline: nothing on the phone covers it, which the screens say, not "nothing here"
        assertNull(LocalData(stores = { nl }, packs = { null }, overpass = { op }, isOnline = { false }).places(antwerp, 300.0, cafe).origin)
        // by the Westerkerk the store answers, and the network is not asked
        val home = LocalData(stores = { nl }, packs = { null }, overpass = { op }, isOnline = { true }).places(here, 300.0, cafe)
        assertEquals(PlaceOrigin.STORE, home.origin)
        assertEquals(listOf("'t Smalle"), home.value.map { it.tags["name"] })
        assertEquals(1, asked.get())
    }

    @Test
    fun placesFromAPackHoldingPartOfTheCircleAreCompletedOnline() = runBlocking {
        val op = start()
        answer = OVERPASS_BEYOND_THE_PACK
        val cafe = TagFilter.Equals("amenity", "cafe")
        // 600 m around the church reaches past the pack (390 m south): Overpass answers for the whole circle
        val online = LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true }).places(here, 600.0, cafe)
        assertEquals(PlaceOrigin.ONLINE, online.origin)
        assertEquals(listOf("Café Buiten"), online.value.map { it.tags["name"] })
        // offline, the pack's part, said to be a part
        val offline = packOnly().places(here, 600.0, cafe)
        assertEquals(PlaceOrigin.PACK, offline.origin)
        assertTrue(offline.partial)
        assertTrue(offline.value.isNotEmpty() && offline.value.none { it.tags["name"] == "Café Buiten" })
        // Overpass failing: the pack's part again, with why
        code = 500
        val failed = LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true }).places(here, 600.0, cafe)
        assertEquals(PlaceOrigin.PACK, failed.origin)
        assertEquals(offline.value.map { it.key }, failed.value.map { it.key })
        assertNotNull(failed.error)
    }

    @Test
    fun theStepsBeyondThePacksAreNamedFromOneOverpassRequest() {
        val op = start()
        answer = OVERPASS_UTRECHT
        val local = LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true })
        val prinsengracht = LatLon(52.37530, 4.88405)
        val oudegracht = LatLon(52.0907, 5.1210)
        // a route's steps: one the pack covers, two in Utrecht (11 m apart: one circle asks for both)
        val namer = local.wayNamer().forPoints(listOf(prinsengracht, oudegracht, LatLon(52.0908, 5.1210)))
        assertEquals(1, asked.get())
        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent, sent.contains("around") && sent.contains("52.0907") && !sent.contains("52.0908") && !sent.contains("52.3753"))
        // the pack names its own streets, Overpass's answer the others, by the same rules
        assertEquals("Prinsengracht", namer.name(prinsengracht, 15.0))
        assertEquals("Oudegracht", namer.name(oudegracht, 350.0))
        // the route planned again: the answer is kept, nothing is asked
        assertEquals("Oudegracht", local.wayNamer().forPoints(listOf(oudegracht)).name(oudegracht, 350.0))
        assertEquals(1, asked.get())
        // offline, a step beyond the packs has no name, and nothing is asked
        assertNull(LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { false }).wayNamer().forPoints(listOf(oudegracht)).name(oudegracht, 350.0))
        assertEquals(1, asked.get())
    }

    @Test
    fun aPinsStreetBeyondThePacksComesFromOverpass() = runBlocking {
        val op = start()
        answer = OVERPASS_UTRECHT
        val local = LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true })
        assertEquals("Prinsengracht", local.streetNear(LatLon(52.37530, 4.88405), 15.0))
        assertEquals(0, asked.get())
        assertEquals("Oudegracht", local.streetNear(LatLon(52.0907, 5.1212), 60.0))
        assertEquals(1, asked.get())
        assertNull(packOnly().streetNear(LatLon(52.0907, 5.1212), 60.0))
    }

    @Test
    fun theBridgesBeyondThePacksComeFromOverpassAlongTheRoute() = runBlocking {
        val op = start()
        answer = OVERPASS_UTRECHT
        val line = listOf(LatLon(52.0890, 5.1214), LatLon(52.0925, 5.1209))
        val boat = Route(Mode.BOAT, line, distance = Geo.distance(line[0], line[1]), duration = 600.0, steps = emptyList())
        val found = LocalData(stores = { null }, packs = { pack }, overpass = { op }, isOnline = { true }).bridges(boat)
        assertEquals(listOf("Bakkerbrug"), found.map { it.name })
        assertEquals(2.1, found[0].clearance!!, 1e-9)
        assertEquals(1, asked.get())
        val sent = server.takeRequest().body!!.utf8()
        assertTrue(sent, sent.contains("bridge") && sent.contains("around"))
        // offline, only what the packs hold: nothing here
        assertTrue(packOnly().bridges(boat).isEmpty())
    }

    @Test
    fun chainsAndBranchesFromTheStoreAndThePackAgree() = runBlocking {
        val box = BBox.around(here, 400.0)
        val a = both().brands(box)
        val b = packOnly().brands(box)
        assertEquals(PlaceOrigin.STORE, a.origin)
        assertEquals(PlaceOrigin.PACK, b.origin)
        assertEquals(a.value.map { it.brand.id to it.branches.map { p -> p.key } }, b.value.map { it.brand.id to it.branches.map { p -> p.key } })
        // Albert Heijn, by its Wikidata id
        val ah = a.value.first { it.brand.wikidata == "Q1653985" }.brand.id
        val branches = both().branches(ah, here, 500.0)
        assertEquals(PlaceOrigin.STORE, branches.origin)
        assertEquals(OsmId(OsmType.NODE, 34044750), branches.value.first().id)
        assertEquals(branches.value.map { it.key }, packOnly().branches(ah, here, 500.0).value.map { it.key })
        assertTrue(a.value.zipWithNext().all { (x, y) -> x.branches.size >= y.branches.size })
    }

    @Test
    fun theWesterkerksAddress() {
        val a = both().address(here)!!
        assertEquals(PlaceOrigin.PACK, a.origin)
        // Inside the church: the nearest address point inside its outline, not the house next door whose point is nearer.
        assertEquals("Westermarkt 60A", a.line)
        assertEquals(0.0, a.distanceM!!, 0.0)
        assertEquals("Grachtengordel", a.quarter)
        assertEquals("Amsterdam", a.town)
        assertTrue(a.distanceM!! <= LocalData.HOUSE_M)
        // From the store alone: its address table.
        val s = storeOnly().address(here)!!
        assertEquals(PlaceOrigin.STORE, s.origin)
        // The store holds address points without outlines: the nearest one, next door.
        assertEquals("Westermarkt 72", s.line)
        assertNull(packOnly().address(utrecht))
    }

    @Test
    fun whatsHereAtTheWesterkerk() = runBlocking {
        val h = both().whatsHere(here)
        assertEquals(OsmId(OsmType.WAY, 99205257), h.spot?.id)
        assertEquals(PlaceOrigin.STORE, h.origin)
        assertTrue(h.nearby.isNotEmpty())
        assertTrue(h.nearby.none { it.key == h.spot?.key })
        val p = packOnly().whatsHere(here)
        assertEquals(OsmId(OsmType.WAY, 99205257), p.spot?.id)
        assertEquals(PlaceOrigin.PACK, p.origin)
        assertTrue(p.nearby.isNotEmpty())
        assertTrue(p.nearby.all { it.tags["name"] != null })
        assertEquals("Westermarkt 60A", p.address?.line)
    }

    @Test
    fun aShortTapOnABuildingOpensThePlaceItIsOrTheOneInItUnderTheFinger() = runBlocking {
        // the Westerkerk is its own place, from the store or the pack
        val westerkerk = OsmId(OsmType.WAY, 99205257)
        for (data in listOf(both(), packOnly(), storeOnly())) assertEquals(westerkerk, data.feature(westerkerk, here)?.id)
        // the buildings about it that are no place themselves, from the pack: what stands listed in each one's outline
        val data = packOnly()
        val box = BBox.around(here, 250.0)
        val listed = { e: OsmElement -> e.tags?.let { it["name"] != null && Categories.of(it) != null && LocalData.keeps(e, it) } == true }
        // (the places from a wider box: a building at the box's edge holds places beyond it)
        val places = pack.elements(BBox.around(here, 400.0), listed).mapNotNull { PackFeatures.place(it) }
        var plain = 0
        var opened = 0
        var beyond = 0
        for (b in pack.elements(box) { it.type == ElementType.WAY && it.tag("building") != null && !listed(it) }) {
            val outline = PackFeatures.outlineOf(b) ?: continue
            val id = PackFeatures.osmId(b)
            val inside = places.filter { it.id != id && outline.contains(it.location) }
            val middle = outline.label()
            if (inside.isEmpty()) {
                // a house: an empty tap
                assertNull("building $id", data.feature(id, middle))
                plain++
                continue
            }
            // a tap where a place in it stands opens that place (or one at the same point)
            for (p in inside) {
                val got = data.feature(id, p.location)
                assertNotNull("building $id at ${p.id}", got)
                assertEquals("building $id at ${p.id}", 0.0, Geo.distance(got!!.location, p.location), 0.01)
                opened++
            }
            // a tap on it farther than FEATURE_M from each of them opens none; nearer, the nearest
            val nearest = inside.minBy { Geo.distance(middle, it.location) }
            val got = data.feature(id, middle)
            if (Geo.distance(middle, nearest.location) > LocalData.FEATURE_M) {
                assertNull("building $id from its middle", got)
                beyond++
            } else {
                assertEquals("building $id from its middle", Geo.distance(middle, nearest.location), Geo.distance(middle, got!!.location), 0.01)
            }
        }
        assertTrue("houses with no place: $plain", plain > 10)
        assertTrue("places opened in their buildings: $opened", opened > 3)
        println("FEATURE taps about the Westerkerk: $plain houses empty, $opened places opened where they stand, $beyond buildings tapped beyond their places")
        // a building the phone has nothing of opens none
        assertNull(data.feature(OsmId(OsmType.WAY, 1), here))
        assertNull(LocalData(stores = { null }, packs = { null }).feature(westerkerk, here))
    }

    @Test
    fun aQueryRunsOverTheStoreOrThePack() {
        val q = Query.And(listOf(Query.Tags(TagFilter.Equals("amenity", "cafe")), Query.Within(here, 300.0)))
        val ctx = EvalContext(location = here, now = ZonedDateTime.of(2026, 10, 13, 19, 30, 0, 0, ZoneId.of("Europe/Amsterdam")), limit = 100)
        val a = both().query(q, ctx)
        val b = packOnly().query(q, ctx)
        assertEquals(PlaceOrigin.STORE, a.origin)
        assertEquals(PlaceOrigin.PACK, b.origin)
        assertTrue(a.value.hits.isNotEmpty())
        assertEquals(a.value.hits.map { it.record.place.key }.toSet(), b.value.hits.map { it.record.place.key }.toSet())
        // Where nothing on the phone covers the searcher, no answer: online search is the search engine's.
        assertNull(packOnly().query(Query.Tags(TagFilter.Equals("amenity", "cafe")), ctx.copy(location = utrecht)).origin)
    }

    @Test
    fun questsFromTheStoreAndThePackAreTheSamePlaces() = runBlocking {
        val box = BBox.around(here, 400.0)
        val a = both().quests(box)
        val b = packOnly().quests(box)
        assertEquals(PlaceOrigin.STORE, a.origin)
        assertEquals(PlaceOrigin.PACK, b.origin)
        assertEquals(a.value.map { it.key }.toSet(), b.value.map { it.key }.toSet())
        assertTrue(b.value.all { it.id != null && it.tags["name"] != null })
    }

    @Test
    fun streetsAndBridgesComeFromThePack() {
        val streets = packOnly().streets(BBox.around(here, 80.0))
        assertTrue(streets.any { it.name == "Prinsengracht" && !it.water })
        assertTrue(streets.any { it.name == "Prinsengracht" && it.water })
        assertTrue(streets.all { s -> s.lines.all { it.size >= 2 } })
        val line = listOf(LatLon(52.37780, 4.88520), LatLon(52.37590, 4.88420), LatLon(52.37470, 4.88360), LatLon(52.37380, 4.88340), LatLon(52.37235, 4.88350))
        val bridges = packOnly().bridges(line)
        assertTrue("${bridges.size}", bridges.size >= 2)
        assertTrue(bridges.zipWithNext().all { (a, b) -> b.along > a.along })
        assertTrue(bridges.all { it.ids.isNotEmpty() })
        assertTrue(LocalData(stores = { null }, packs = { null }).bridges(line).isEmpty())
        // The router's street namer, on the same data.
        assertEquals("Prinsengracht", packOnly().wayNamer().name(LatLon(52.37530, 4.88405), 15.0))
        assertNull(LocalData(stores = { null }, packs = { null }).wayNamer().name(LatLon(52.37530, 4.88405), 15.0))
    }

    @Test
    fun aParksOutlineAndItsPathsComeFromThePackOffline() {
        // Postzegelpark, a pocket park some 600 m from the Westerkerk: a closed way in the bundled pack.
        val e = pack.elements(BBox.around(here, 3_000.0)) { it.tag("leisure") == "park" && it.tag("name") == "Postzegelpark" }.single()
        val box = PackFeatures.boxOf(e)!!
        val id = PackFeatures.osmId(e)
        assertEquals(OsmId(OsmType.WAY, 1423270125), id)
        val o = packOnly().outlineOf(id, box, app.atlas.ui.local.Parkland.PATHS)!!
        assertEquals(1, o.rings.size)
        assertTrue(o.holes.none { it })
        assertTrue("${o.rings[0].size}", o.rings[0].size >= 4)
        assertTrue(o.rings[0].all { it in box.buffer(1.0) })
        // some 50 by 100 m less its corners
        assertTrue("${o.areaM2}", o.areaM2 in 1_000.0..6_000.0)
        assertTrue(o.paths.all { it.size >= 2 })
        // the outline alone when no paths are asked for; none for a node, an id the pack lacks, or a box it does not cover
        assertTrue(packOnly().outlineOf(id, box)!!.paths.isEmpty())
        assertNull(packOnly().outlineOf(OsmId(OsmType.NODE, 1), box))
        assertNull(packOnly().outlineOf(OsmId(OsmType.WAY, 1), box))
        assertNull(packOnly().outlineOf(id, BBox(5.0, 51.0, 5.1, 51.1)))
        assertNull(LocalData(stores = { null }, packs = { null }).outlineOf(id, box))
    }

    @Test
    fun aParksOutlineComesFromItsStoreWhereNoPackHoldsIt() {
        // a country kept as its tiles: no element pack, its place store keeps the Vondelpark's outline (here a box of it)
        val id = OsmId(OsmType.RELATION, 2_554_787)
        val box = BBox(4.8584, 52.3553, 4.8826, 52.3620)
        val ring = intArrayOf(523553000, 48584000, 523553000, 48826000, 523620000, 48826000, 523620000, 48584000, 523553000, 48584000)
        val store = app.atlas.search.PlaceStore.inMemory()
        store.writer().use { w ->
            assertTrue(w.putOutline(id, listOf(ring), listOf(false)))
            w.finish()
        }
        val local = LocalData(stores = { app.atlas.search.Regions(listOf(Fixtures.amsStore, store)) }, packs = { null })
        assertNull(local.outlineOf(id, box, app.atlas.ui.local.Parkland.PATHS))
        val o = local.storedOutline(id)!!
        assertEquals(listOf(false), o.holes)
        assertEquals(5, o.rings.single().size)
        assertTrue(o.rings.single().all { it in box.buffer(1.0) })
        // some 1,640 by 745 m, and no paths: this store keeps none, and says so
        assertTrue("${o.areaM2}", o.areaM2 in 1.15e6..1.30e6)
        assertTrue(o.paths.isEmpty())
        assertFalse(o.pathsKept)
        // the packer's store keeps the paths in it beside its outline
        store.writer().use { w ->
            w.putPaths(id, listOf(intArrayOf(523586000, 48640000, 523586000, 48770000)), 1.0)
            w.finish()
        }
        val walked = local.storedOutline(id)!!
        assertTrue(walked.pathsKept)
        assertEquals(listOf(listOf(LatLon(52.3586, 4.864), LatLon(52.3586, 4.877))), walked.paths)
        // none where the store keeps none, or there is no store
        assertNull(storeOnly().storedOutline(id))
        assertNull(LocalData(stores = { null }, packs = { null }).storedOutline(id))
        store.close()
    }

    @Test
    fun theAppsServicesAnswerFromTheBundledPack() {
        val services = testServices()
        try {
            val local = services.localData
            assertEquals("Prinsengracht", local.wayName(LatLon(52.37530, 4.88405), 15.0))
            assertEquals(PlaceOrigin.PACK, local.address(here)?.origin)
        } finally {
            services.close()
        }
    }

    @Test
    fun theShelfReopensAPackThatChanged() {
        val dir = tmp.newFolder("maps")
        val copy = File(dir, "amsterdam.atlas")
        packFile.copyTo(copy)
        var now = 0L
        val shelf = PackShelf({ dir.listFiles().orEmpty().filter { it.name.endsWith(".atlas") } }, clock = { now })
        val first = shelf()
        assertNotNull(first)
        assertSame(first, shelf())
        assertTrue(first!!.covers(here))
        copy.setLastModified(copy.lastModified() - 60_000)
        now = 1_000
        val second = shelf()
        assertNotSame(first, second)
        assertEquals("Prinsengracht", second!!.streetName(LatLon(52.37530, 4.88405), 15.0))
        copy.delete()
        assertNull(shelf())
        shelf.close()
    }

    @Test
    fun bareAddressesAreNotPlaces() {
        assertTrue(LocalData.onlyAddress(mapOf("addr:housenumber" to "12", "addr:street" to "Keizersgracht", "building" to "house", "source" to "BAG")))
        assertTrue(!LocalData.onlyAddress(mapOf("addr:housenumber" to "12", "addr:street" to "Keizersgracht", "building" to "church")))
        assertTrue(!LocalData.onlyAddress(mapOf("addr:housenumber" to "12", "shop" to "bakery")))
        assertTrue(LocalData.isTrade(mapOf("shop" to "supermarket")))
        assertTrue(!LocalData.isTrade(mapOf("amenity" to "bench")))
    }

    /** The Main board's names over the map: the quarter at its place node; canals left to the engine, which names them. */
    @Test
    fun theMapIsNamedFromThePacks() {
        val names = LocalData(stores = { null }, packs = { pack }).mapNames(here, 1_500.0)
        val jordaan = names.single { it.name == "Jordaan" }
        assertTrue(!jordaan.water)
        // node 6693415585, place=quarter: where the map's makers set the name, not the middle of its outline
        assertEquals(LatLon(52.3754157, 4.8810958), jordaan.points.single().let { LatLon(Math.round(it.lat * 1e7) / 1e7, Math.round(it.lon * 1e7) / 1e7) })
        assertTrue("the canals are the engine's to name", names.none { it.name.endsWith("gracht") })
        assertTrue("no water here is large enough", names.none { it.water })
        // quarters before neighbourhoods and suburbs
        assertEquals(names.sortedByDescending { it.rank }, names)
    }

    /** With the canal ring's pack (this machine's renders), the IJ is named along its waterway, nearest first. */
    @Test
    fun theIjIsNamedAlongItsWaterway() {
        val file = File(repo, "cache/packs/regions/grachten.atlas")
        org.junit.Assume.assumeTrue("the canal ring's pack is on this machine", file.isFile)
        val grachten = PackFeatures(listOf(PackBoxes(PackReader.open(file))))
        val names = LocalData(stores = { null }, packs = { grachten }).mapNames(here, 1_700.0)
        val ij = names.single { it.name == "IJ" }
        assertTrue(ij.water)
        assertTrue("points along the line, a hundred metres apart", ij.points.size > 5)
        val d = ij.points.map { Geo.distance(it, here) }
        assertEquals("nearest first, the outline's inner point last", d.dropLast(1), d.dropLast(1).sorted())
        assertTrue(names.first().water)
        assertTrue(names.any { it.name == "Jordaan" && !it.water })
    }

    private companion object {
        /** A café 560 m north-west of the Westerkerk, beyond the bundled pack. */
        val OVERPASS_BEYOND_THE_PACK = """
            {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-16T00:00:00Z"},"elements":[
              {"type":"node","id":401,"lat":52.3790,"lon":4.8800,"tags":{"amenity":"cafe","name":"Café Buiten"}}
            ]}
        """.trimIndent()

        /** Utrecht, beyond every pack: the Oudegracht as a street, and a footbridge over it with its clearance. */
        val OVERPASS_UTRECHT = """
            {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-16T00:00:00Z"},"elements":[
              {"type":"way","id":201,"bounds":{"minlat":52.0890,"minlon":5.1205,"maxlat":52.0925,"maxlon":5.1215},
               "geometry":[{"lat":52.0890,"lon":5.1215},{"lat":52.0925,"lon":5.1205}],"tags":{"highway":"residential","name":"Oudegracht"}},
              {"type":"way","id":301,"bounds":{"minlat":52.0905,"minlon":5.1200,"maxlat":52.0905,"maxlon":5.1225},
               "geometry":[{"lat":52.0905,"lon":5.1200},{"lat":52.0905,"lon":5.1225}],
               "tags":{"highway":"footway","bridge":"yes","name":"Bakkerbrug","maxheight:physical":"2.1"}}
            ]}
        """.trimIndent()

        val OVERPASS_CAFES = """
            {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-16T00:00:00Z"},"elements":[
              {"type":"node","id":101,"lat":52.0910,"lon":5.1210,"tags":{"amenity":"cafe","name":"Café Oudegracht"}},
              {"type":"node","id":102,"lat":52.0990,"lon":5.1300,"tags":{"amenity":"cafe","name":"Too far"}}
            ]}
        """.trimIndent()
    }
}
