package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceStoreTest {
    private val store get() = Fixtures.store

    @Test
    fun importsTheThreeExtracts() {
        val s = store
        println("import: " + Fixtures.importStats)
        val ams = Fixtures.importStats.getValue("ams")
        // the address points live apart from the places
        assertTrue("places ${s.size}", s.size > 3000)
        assertTrue("addresses ${s.addressCount}", s.addressCount > 7000)
        assertEquals(ams.addresses.toLong(), s.addressCount.toLong())
        assertTrue("seconds, not minutes: ${ams.millis} ms", ams.millis < 20_000)
        assertTrue(ams.trees > 100)
        val b = s.bounds!!
        assertTrue(Fixtures.westerkerk in b)
    }

    @Test
    fun findsByIdBoxAndTag() {
        val box = store.get(OsmId(OsmType.NODE, 26973327))
        assertNotNull(box)
        assertEquals("post_box", box!!.tags["amenity"])
        val around = store.inBox(BBox.around(Fixtures.westerkerk, 150.0))
        assertTrue(around.size > 10)
        val cafes = store.byTag("amenity", "cafe")
        assertTrue("cafes ${cafes.size}", cafes.size > 100)
        assertTrue(cafes.all { it.tags["amenity"] == "cafe" })
        val pizza = store.byTag("cuisine", "pizza")
        assertTrue(pizza.isNotEmpty())
        assertTrue(pizza.any { ';' in it.tags.getValue("cuisine") } || pizza.all { it.tags["cuisine"]!!.contains("pizza") })
        val near = store.byTag("amenity", "cafe", BBox.around(Fixtures.westerkerk, 300.0))
        assertTrue(near.isNotEmpty() && near.size < cafes.size)
        assertTrue(near.all { Geo.distance(it.location, Fixtures.westerkerk) < 600 })
    }

    @Test
    fun importIsIdempotent() {
        val s = PlaceStore.inMemory()
        val first = s.importOverpass(Fixtures.errands)
        val n = s.size
        s.importOverpass(Fixtures.errands)
        assertEquals(n, s.size)
        assertEquals(n, first.places)
        val hits = s.text("toilet")
        assertEquals(hits.size, hits.map { it.place.key }.toSet().size)
        s.close()
    }

    @Test
    fun fullTextFindsNamesAndForgivesTypos() {
        val exact = store.text("westerkerk")
        assertTrue(exact.any { it.id == OsmId(OsmType.WAY, 99205257) })
        assertTrue(store.knows("westerkerk"))
        val fuzzy = store.fuzzyWords("westerkrk")
        assertTrue("fuzzy $fuzzy", "westerkerk" in fuzzy)
        val typo = store.text("westerkrk")
        assertTrue(typo.any { it.id == OsmId(OsmType.WAY, 99205257) })
        val ah = store.text("albert hein", Fixtures.westerkerk)
        assertTrue(ah.any { it.tags["brand"] == "Albert Heijn" })
    }

    @Test
    fun columnsStatisticsAndNearest() {
        val s = store
        val drinks = s.byCategory(listOf("drinks"))
        assertTrue(drinks.isNotEmpty() && drinks.all { it.category == "drinks" })
        assertEquals(2, s.byBrand(listOf("albertheijn-1cf55d")).size)
        assertTrue(s.byPreset(listOf("amenity/cafe")).size > 100)
        assertTrue(s.estimate("amenity", "cafe") >= s.byTag("amenity", "cafe").size)
        assertTrue(s.estimate("amenity", null) > s.estimate("amenity", "cafe"))
        assertEquals(s.byTag("amenity", "cafe").size, s.count("amenity", "cafe"))
        val box = BBox.around(Fixtures.westerkerk, 400.0)
        assertTrue(s.estimate(box) in 1..s.size)
        val nearest = s.nearest(Fixtures.westerkerk, 5) { it.tags["amenity"] == "restaurant" }
        assertEquals(5, nearest.size)
        val d = nearest.map { it.distanceTo(Fixtures.westerkerk) }
        assertEquals(d.sorted(), d)
        val within = s.within(Fixtures.westerkerk, 150.0)
        assertTrue(within.size > 10)
        val dw = within.map { it.distanceTo(Fixtures.westerkerk) }
        assertEquals(dw.sorted(), dw)
        assertTrue(dw.all { it <= 150.0 })
        val brute = s.inBox(BBox.around(Fixtures.westerkerk, 200.0)).count { it.distanceTo(Fixtures.westerkerk) <= 150.0 }
        assertEquals(brute, within.size)
        assertEquals(within.count { it.tags["amenity"] == "cafe" }, s.within(Fixtures.westerkerk, 150.0) { it.tags["amenity"] == "cafe" }.size)
        assertEquals("Amsterdam", s.name)
        assertEquals(PlaceStore.DEFAULT_LANGS, s.languages)
        assertNotNull(s.meta("imported"))
        assertEquals(StoreWriter.fingerprint(), s.meta("derived"))
    }

    @Test
    fun placesFromAnywhereAndAFileThatReopens() {
        val file = java.io.File(Fixtures.dir, "list.db")
        val list = listOf(
            app.atlas.model.Place(OsmId(OsmType.NODE, 1), app.atlas.geo.LatLon(52.37, 4.88), mapOf("amenity" to "cafe", "name" to "Café Molen", "internet_access" to "wlan")),
            app.atlas.model.Place(null, app.atlas.geo.LatLon(52.371, 4.881), mapOf("amenity" to "charging_station", "name" to "Voltra Hub"), source = "ocm", extra = mapOf("rating" to "4.5")),
        )
        PlaceStore.open(file).use { s ->
            val stats = s.importPlaces(list, listOf("en"), "list")
            assertEquals(2, stats.places)
            assertEquals(2, s.size)
        }
        PlaceStore.open(file).use { s ->
            assertEquals(2, s.size)
            val molen = s.text("cafe molen").single()
            assertEquals("cafe", molen.tags["amenity"])
            assertEquals("maki-cafe", molen.symbol)
            val hub = s.text("voltra").single()
            assertEquals(null, hub.id)
            assertEquals("ocm", hub.place.source)
            assertEquals("4.5", hub.place.extra["rating"])
            assertEquals(listOf("en"), s.languages)
            assertFalse(s.isStale)
            // as after an app update with new presets: the derived columns are worked out again, nothing is lost
            s.locked {
                s.exec("UPDATE place SET category = NULL, symbol = ${s.dict.internValue("x")}")
                s.exec("UPDATE meta SET value = 'old' WHERE key = 'derived'")
            }
            assertTrue(s.isStale)
            s.refresh()
            assertFalse(s.isStale)
            assertEquals(2, s.size)
            assertEquals("maki-cafe", s.text("cafe molen").single().symbol)
            assertEquals("restaurants", s.text("cafe molen").single().category)
            s.clear()
            assertEquals(0, s.size)
        }
    }

    @Test
    fun editTimesFromMetaMakeNewerWorkOffline() {
        val json = """{"elements":[
            {"type":"node","id":1,"lat":52.37,"lon":4.88,"timestamp":"2026-09-01T10:00:00Z","version":3,"tags":{"amenity":"cafe","name":"Nieuw"}},
            {"type":"node","id":2,"lat":52.371,"lon":4.881,"timestamp":"2019-02-01T10:00:00Z","version":7,"tags":{"amenity":"cafe","name":"Oud"}},
            {"type":"node","id":3,"lat":52.372,"lon":4.882,"tags":{"amenity":"cafe","name":"Zonder"}}]}"""
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                OverpassImport.read(java.io.StringReader(json), w)
                w.finish()
            }
            assertEquals("2026-09-01T10:00:00Z", s.get(OsmId(OsmType.NODE, 1))!!.place.extra["timestamp"])
            val q = QueryParser.parse("amenity=cafe newer:2026-01-01").query
            val hits = OfflineEvaluator(s).run(q, Fixtures.context()).hits
            assertEquals(listOf("Nieuw"), hits.map { it.tags["name"] })
        }
    }

    @Test
    fun treesGoToTheirOwnTable() {
        val trees = store.trees(BBox.around(Fixtures.westerkerk, 500.0))
        assertTrue(trees.isNotEmpty())
    }

    @Test
    fun longValuesAreFoundAndCountedByTheirOwnValue() {
        val long = "Mo-Fr 08:00-12:30,13:30-18:00; Sa 09:00-17:00; Su,PH off"
        val other = "Mo-Fr 07:00-12:30,13:30-19:00; Sa 09:00-17:00; Su,PH off"
        PlaceStore.inMemory().use { s ->
            s.importPlaces(listOf(
                app.atlas.model.Place(OsmId(OsmType.NODE, 1), app.atlas.geo.LatLon(52.37, 4.88), mapOf("shop" to "bakery", "opening_hours" to long)),
                app.atlas.model.Place(OsmId(OsmType.NODE, 2), app.atlas.geo.LatLon(52.371, 4.881), mapOf("shop" to "bakery", "opening_hours" to other)),
            ))
            assertTrue(long.length > StoreSchema.MAX_INDEXED_VALUE)
            assertEquals(listOf(OsmId(OsmType.NODE, 1)), s.byTag("opening_hours", long).map { it.id })
            assertEquals(1, s.count("opening_hours", long))
            assertEquals(2, s.count("opening_hours", null))
            assertEquals(1, s.byTag("opening_hours", long, limit = 1).size)
        }
    }

    @Test
    fun aWriterClosedAfterItsStoreDoesNotThrow() {
        val s = PlaceStore.inMemory()
        val w = s.writer()
        w.add(app.atlas.model.Place(OsmId(OsmType.NODE, 1), app.atlas.geo.LatLon(52.37, 4.88), mapOf("amenity" to "cafe")))
        s.close()
        w.close()
    }
}
