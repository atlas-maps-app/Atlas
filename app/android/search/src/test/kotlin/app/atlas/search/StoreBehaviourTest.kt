package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.providers.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The slim store's behaviour: replacing places, address points, trees, versions, and the lookups without an index. */
class StoreBehaviourTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun node(id: Long, lat: Double, lon: Double, vararg tags: Pair<String, String>) =
        OverpassElement(OsmType.NODE, id, mapOf(*tags), lat, lon)

    private fun place(id: Long, lat: Double, lon: Double, vararg tags: Pair<String, String>) =
        Place(OsmId(OsmType.NODE, id), LatLon(lat, lon), mapOf(*tags))

    /** A record of the address table: nothing but an address. */
    private val PlaceRecord.isAddressPoint: Boolean get() = category == null && tags.keys.all { it in Addresses.KEYS }

    /** A few streets of Amsterdam: address points, a shop that has an address, a café, trees. */
    private fun amsterdam(s: PlaceStore) = s.writer(regionName = "Grachten").use { w ->
        var id = 1L
        for (hn in listOf("261", "263", "263-H", "265", "267")) {
            w.add(node(id++, 52.3752 + id * 1e-5, 4.8840, "addr:street" to "Prinsengracht", "addr:housenumber" to hn, "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam"))
        }
        for (hn in listOf("1", "2", "3")) {
            w.add(node(id++, 52.3700 + id * 1e-5, 4.8900, "addr:street" to "Prinsengracht", "addr:housenumber" to hn, "addr:postcode" to "1015 DT", "addr:city" to "Amsterdam"))
        }
        // the same street name in another town: its own street
        w.add(node(id++, 52.0800, 4.3100, "addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:postcode" to "2512 EZ", "addr:city" to "Den Haag"))
        w.add(node(id++, 52.3760, 4.8850, "addr:place" to "Westermarkt", "addr:housenumber" to "8", "addr:city" to "Amsterdam"))
        // the same address twice (two entrances): one point
        w.add(node(id++, 52.37601, 4.88501, "addr:place" to "Westermarkt", "addr:housenumber" to "8", "addr:city" to "Amsterdam", "entrance" to "yes"))
        // an address that is also a shop stays a place
        w.add(node(100, 52.3753, 4.8841, "shop" to "books", "name" to "Boekhandel", "addr:street" to "Prinsengracht", "addr:housenumber" to "269", "addr:postcode" to "1016 GV"))
        w.add(node(101, 52.3745, 4.8838, "amenity" to "cafe", "name" to "Café Molen", "cuisine" to "coffee_shop;cake", "wikidata" to "Q42"))
        w.add(node(102, 52.3746, 4.8839, "natural" to "tree", "leaf_type" to "broadleaved", "genus" to "Ulmus", "height" to "12.5 m"))
        w.add(node(103, 52.3747, 4.8837, "natural" to "tree", "leaf_type" to "needleleaved"))
        w.finish()
    }

    @Test
    fun addressPointsLiveUnderTheirStreets() {
        PlaceStore.inMemory().use { s ->
            val stats = amsterdam(s)
            assertEquals(11, stats.addresses)
            assertEquals(10, s.addressCount)
            assertEquals(2, s.placeCount)
            assertEquals(12, s.size)
            assertEquals(2, s.trees(BBox(4.88, 52.37, 4.89, 52.38)).size)
            // by street and number, either way round, and while typing
            val a = s.text("prinsengracht 263", Fixtures.westerkerk).filter { it.isAddressPoint }
            assertEquals(setOf("Amsterdam", "Den Haag"), a.filter { it.tags["addr:housenumber"] == "263" }.map { it.tags["addr:city"] }.toSet())
            assertTrue(a.any { it.tags["addr:housenumber"] == "263-H" })
            assertEquals(a.map { it.place.key }.toSet(), s.text("263 prinsengracht", Fixtures.westerkerk).filter { it.isAddressPoint }.map { it.place.key }.toSet())
            val typing = s.text("prinsengracht 26", Fixtures.westerkerk).filter { it.isAddressPoint }.mapNotNull { it.tags["addr:housenumber"] }.toSet()
            assertTrue("$typing", typing.containsAll(listOf("261", "263", "265", "267")))
            assertTrue(s.text("prinsengracht 263 den haag").filter { it.isAddressPoint }.all { it.tags["addr:city"] == "Den Haag" })
            assertEquals(listOf("8"), s.text("westermarkt 8").filter { it.isAddressPoint }.map { it.tags["addr:housenumber"] })
            // by postcode, with or without its space, and a typo in the street
            assertEquals(5, s.text("1016 gv").count { it.isAddressPoint })
            assertEquals(5, s.text("1016gv").count { it.isAddressPoint })
            assertTrue(s.text("prinsengrcht 263").any { it.isAddressPoint && it.tags["addr:housenumber"] == "263" })
            // the shop with an address is a place, found by its address too
            assertEquals("Boekhandel", s.text("prinsengracht 269").single().tags["name"])
            assertNotNull(s.get(OsmId(OsmType.NODE, 100)))
            assertNull(s.get(OsmId(OsmType.NODE, 1)))
            // by tag: places and address points alike
            assertEquals(8, s.byTag("addr:street", "Prinsengracht").count { it.isAddressPoint && it.tags["addr:city"] == "Amsterdam" })
            assertEquals(9, s.byTag("addr:street", "Prinsengracht").count { it.isAddressPoint })
            assertEquals(10, s.byTag("addr:street", "Prinsengracht").size)
            assertEquals(6, s.count("addr:postcode", "1016 GV"))
            assertEquals(2, s.count("addr:housenumber", "263"))
            assertEquals(11, s.count("addr:housenumber", null))
            assertEquals(1, s.byTag("addr:place", "Westermarkt").size)
            assertEquals(3, s.byTag("addr:housenumber", null, limit = 3).size)
            val box = BBox(4.8835, 52.3750, 4.8845, 52.3760)
            assertEquals(5, s.addresses(box).size)
            assertEquals(6, s.count("addr:housenumber", null, box))
            assertEquals(s.addresses(box).size, s.byTag("addr:housenumber", null, box).count { it.isAddressPoint })
            // a box holds the address points as well as the places
            assertEquals(s.addresses(box).map { it.place.key }.toSet(), s.inBox(box).filter { it.isAddressPoint }.map { it.place.key }.toSet())
            assertEquals(s.size, s.inBox(BBox(4.0, 52.0, 5.0, 53.0)).size)
            assertEquals(3, s.inBox(BBox(4.0, 52.0, 5.0, 53.0), limit = 3).size)
            // what is the address here
            val near = s.nearestAddress(LatLon(52.37605, 4.88505))!!
            assertEquals("Westermarkt", near.tags["addr:place"])
            assertTrue(Geo.distance(near.location, LatLon(52.3760, 4.8850)) < 2)
            assertNull(s.nearestAddress(LatLon(51.0, 4.0), 500.0))
            // an address point's record: its tags, its position and its element's id (though get() does not find it)
            val one = s.byTag("addr:housenumber", "261").single()
            assertEquals(mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "261", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam"), one.tags)
            assertEquals(OsmId(OsmType.NODE, 1), one.id)
            assertEquals("n1", one.place.key)
            assertNotNull(one.preset)
            assertEquals(s.estimate("addr:housenumber", null), 11)
        }
    }

    @Test
    fun aPlaceAddedAgainReplacesItsIndexes() {
        PlaceStore.inMemory().use { s ->
            s.importPlaces(listOf(place(1, 52.37, 4.88, "amenity" to "cafe", "name" to "Oud", "cuisine" to "pizza;pasta", "opening_hours" to "24/7")))
            assertEquals(1, s.count("amenity", "cafe"))
            assertEquals(1, s.text("oud").size)
            s.importPlaces(listOf(place(1, 52.38, 4.90, "amenity" to "restaurant", "name" to "Nieuw", "cuisine" to "sushi")))
            assertEquals(1, s.size)
            assertEquals(0, s.count("amenity", "cafe"))
            assertEquals(0, s.byTag("cuisine", "pizza").size)
            assertEquals(0, s.byTag("opening_hours", "24/7").size)
            assertEquals(0, s.text("oud").size)
            assertEquals("Nieuw", s.text("nieuw").single().tags["name"])
            assertEquals(1, s.byTag("cuisine", "sushi").size)
            assertTrue(s.inBox(BBox.around(LatLon(52.37, 4.88), 50.0)).isEmpty())
            assertEquals(1, s.inBox(BBox.around(LatLon(52.38, 4.90), 50.0)).size)
            assertEquals(0, s.estimate("amenity", "cafe"))
            assertEquals(1, s.estimate("amenity", "restaurant"))
            assertEquals(LatLon(52.38, 4.90).toString(), s.bounds!!.center.toString())
        }
    }

    @Test
    fun aRollbackForgetsTheIdsItHandedOut() {
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                w.add(place(1, 52.37, 4.88, "amenity" to "cafe", "operator" to "Kept"))
                w.commit()
                w.add(place(2, 52.37, 4.88, "amenity" to "cafe", "operator" to "Lost", "access" to "lost"))
                w.rollback()
                // new values after the rollback must not reuse what the dictionary thought it had
                w.add(place(3, 52.37, 4.88, "amenity" to "cafe", "operator" to "Third", "access" to "third"))
                w.finish()
            }
            assertEquals(2, s.size)
            assertNull(s.get(OsmId(OsmType.NODE, 2)))
            assertEquals("Kept", s.get(OsmId(OsmType.NODE, 1))!!.tags["operator"])
            assertEquals(mapOf("amenity" to "cafe", "operator" to "Third", "access" to "third"), s.get(OsmId(OsmType.NODE, 3))!!.tags)
        }
    }

    @Test
    fun aRollbackForgetsTheStreetsReadersSaw() {
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                w.add(node(1, 52.37, 4.88, "addr:street" to "Kept", "addr:housenumber" to "1"))
                w.commit()
                w.add(node(2, 52.38, 4.88, "addr:street" to "Lost", "addr:housenumber" to "2"))
                // a search meanwhile reads the street that is about to be rolled back
                assertEquals("Lost", s.byTag("addr:housenumber", "2").single().tags["addr:street"])
                w.rollback()
                // its id is handed out again, to another street, which a search finds while the import goes on
                w.add(node(3, 52.38, 4.88, "addr:street" to "Found", "addr:housenumber" to "2"))
                w.commit()
                assertEquals("Found", s.byTag("addr:housenumber", "2").single().tags["addr:street"])
                w.finish()
            }
            assertEquals("Found", s.byTag("addr:housenumber", "2").single().tags["addr:street"])
            assertEquals(OsmId(OsmType.NODE, 3), s.byTag("addr:housenumber", "2").single().id)
        }
    }

    @Test
    fun treesKeepTheirKindAndHeight() {
        PlaceStore.inMemory().use { s ->
            amsterdam(s)
            val trees = s.trees(BBox(4.8836, 52.3745, 4.8840, 52.3748)).sortedBy { it.location.lat }
            assertEquals(2, trees.size)
            assertEquals("broadleaved", trees[0].leafType)
            assertEquals("Ulmus", trees[0].genus)
            assertEquals(12.5, trees[0].height!!, 1e-9)
            assertEquals(LatLon(52.3746, 4.8839).toString(), trees[0].location.toString())
            assertEquals(Morton.encode(E7.of(52.3746), E7.of(4.8839)), trees[0].id)
            assertEquals("needleleaved", trees[1].leafType)
            assertNull(trees[1].genus)
            assertNull(trees[1].height)
            assertEquals(1, s.trees(BBox(4.8838, 52.3746, 4.8840, 52.3746)).size)
            assertEquals(1, s.trees(BBox(4.0, 52.0, 5.0, 53.0), limit = 1).size)
        }
    }

    @Test
    fun lookupsWithoutAnIndexStillAnswer() {
        PlaceStore.inMemory().use { s ->
            amsterdam(s)
            // a key the lookup table does not hold: through the box, or every place
            assertEquals("Café Molen", s.byTag("wikidata", "Q42").single().tags["name"])
            assertEquals(1, s.byTag("wikidata", null, BBox.around(Fixtures.westerkerk, 100.0)).size)
            assertEquals(0, s.byTag("wikidata", null, BBox(5.0, 53.0, 5.1, 53.1)).size)
            // a name: through the text index, checked exactly
            assertEquals(1, s.byTag("name", "Café Molen").size)
            assertEquals(0, s.byTag("name", "Café").size)
            assertEquals(1, s.count("name", "Café Molen"))
            assertTrue(s.estimate("name", "Café Molen") >= 1)
            assertEquals(1, s.byPreset(listOf("amenity/cafe")).size)
            assertEquals(1, s.byTag("cuisine", "cake").size)
            assertEquals(0, s.byTag("never", "seen").size)
            assertEquals(0, s.count("never", null))
            // kinds by their words, and the vocabulary that forgives typos
            assertEquals("Café Molen", s.text("koffie").single().tags["name"])
            assertTrue(s.knows("prinsengracht") && s.knows("boekh", prefix = true) && s.knows("koffie"))
            assertFalse(s.knows("zzzz"))
            assertTrue("prinsengracht" in s.fuzzyWords("prinsengrcht"))
            assertTrue("boekhandel" in s.fuzzyWords("boekhandl"))
        }
    }

    @Test
    fun placesFromElsewhereKeepWhatTheyCarry() {
        PlaceStore.inMemory().use { s ->
            s.importPlaces(
                listOf(
                    Place(null, LatLon(52.37, 4.88), mapOf("amenity" to "charging_station", "name" to "Hub", "source" to "kept"), source = "ocm", extra = mapOf("rating" to "4.5", "timestamp" to "2026-01-02T03:04:05.123Z")),
                    Place(OsmId(OsmType.NODE, 7), LatLon(52.371, 4.881), mapOf("amenity" to "cafe"), extra = mapOf("timestamp" to "2026-01-02T03:04:05Z")),
                    // a box across the antimeridian cannot go into the R*Tree: the place is kept at its pin
                    Place(OsmId(OsmType.RELATION, 8), LatLon(52.372, 4.882), mapOf("place" to "island", "name" to "Wide"), bbox = BBox(170.0, 50.0, -170.0, 53.0)),
                ),
            )
            val hub = s.text("hub").single()
            assertEquals("ocm", hub.place.source)
            assertEquals("kept", hub.tags["source"])
            assertEquals(mapOf("rating" to "4.5", "timestamp" to "2026-01-02T03:04:05.123Z"), hub.place.extra)
            assertEquals(mapOf("timestamp" to "2026-01-02T03:04:05Z"), s.get(OsmId(OsmType.NODE, 7))!!.place.extra)
            val wide = s.get(OsmId(OsmType.RELATION, 8))!!
            assertNull(wide.place.bbox)
            assertEquals(1, s.inBox(BBox.around(LatLon(52.372, 4.882), 10.0)).size)
        }
    }

    @Test
    fun aPackOfAnotherSchemaIsRefusedAndARegionIsRebuilt() {
        val pack = File(tmp.root, "old.places.db")
        val region = File(tmp.root, "old-region.db")
        for ((f, packer) in listOf(pack to true, region to false)) {
            PlaceStore.open(f).use { s ->
                s.importPlaces(listOf(place(1, 52.37, 4.88, "amenity" to "cafe")))
                s.locked {
                    s.exec("UPDATE meta SET value = '1' WHERE key = 'schema'")
                    if (packer) s.exec("INSERT INTO meta(key, value) VALUES ('packer', 'atlas-packer/1')")
                }
            }
        }
        try {
            PlaceStore.open(pack).close()
            fail("a pack of another schema must be refused")
        } catch (e: StoreVersionException) {
            assertTrue(e.message!!, e.message!!.contains("download the region again"))
        }
        PlaceStore.open(region).use { s ->
            assertEquals(0, s.size)
            assertEquals(StoreSchema.VERSION.toString(), s.meta("schema"))
            s.importPlaces(listOf(place(2, 52.37, 4.88, "amenity" to "bar")))
            assertEquals(1, s.size)
        }
    }

    @Test
    fun theTextIndexOfAnotherVersionIsRebuilt() {
        PlaceStore.inMemory().use { s ->
            amsterdam(s)
            s.locked {
                s.exec("INSERT INTO place_fts(place_fts) VALUES ('delete-all')")
                s.exec("UPDATE meta SET value = '0' WHERE key = 'text'")
            }
            assertTrue(s.text("molen").isEmpty())
            s.importPlaces(listOf(place(200, 52.375, 4.884, "amenity" to "bar", "name" to "Kroeg")))
            assertEquals("Café Molen", s.text("molen").single().tags["name"])
            assertEquals("Kroeg", s.text("kroeg").single().tags["name"])
            assertEquals(StoreSchema.TEXT_VERSION.toString(), s.meta("text"))
        }
    }

    @Test
    fun aStoreCompactsClearsAndFillsAgain() {
        val f = File(tmp.root, "a.db")
        PlaceStore.open(f).use { s ->
            amsterdam(s)
            assertTrue(s.compact())
            assertEquals(2, s.placeCount)
            assertEquals(10, s.addressCount)
            assertEquals("Grachten", s.name)
            s.clear()
            assertEquals(0, s.size)
            assertEquals(0, s.addressCount)
            assertTrue(s.trees(BBox(4.0, 52.0, 5.0, 53.0)).isEmpty())
            assertTrue(s.text("prinsengracht 263").isEmpty())
            amsterdam(s)
            assertEquals(2, s.placeCount)
            assertEquals(10, s.addressCount)
        }
        PlaceStore.open(f).use { s ->
            assertEquals(12, s.size)
            assertEquals(2, s.text("prinsengracht 263").count { it.tags["addr:housenumber"] == "263" })
        }
    }

    /**
     * Address points stacked at one position (an import's flats, "one house number for each apartment") are told
     * apart by their elements' ids: every lookup returns each one, under a key of its own.
     */
    @Test
    fun stackedAddressPointsKeepKeysOfTheirOwn() {
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                for ((i, hn) in listOf("2", "10", "11", "12", "14", "16", "18B", "12;14").withIndex()) {
                    w.add(node(11_800_086_560L + i, 49.8376148, 6.2866238, "addr:street" to "Résidence Le Manoir", "addr:housenumber" to hn, "addr:postcode" to "6313", "addr:city" to "Beaufort"))
                }
                // a building way at the same pin
                w.add(OverpassElement(OsmType.WAY, 42, mapOf("building" to "house", "addr:street" to "Résidence Le Manoir", "addr:housenumber" to "20"), center = LatLon(49.8376148, 6.2866238)))
                w.finish()
            }
            val all = s.byTag("addr:street", "Résidence Le Manoir")
            assertEquals(9, all.size)
            assertEquals(9, all.map { it.place.key }.toSet().size)
            assertTrue(OsmId(OsmType.WAY, 42) in all.map { it.id })
            assertEquals(9, s.inBox(BBox.around(LatLon(49.8376148, 6.2866238), 5.0)).map { it.place.key }.toSet().size)
            // typing a number finds every number it begins, not one per position
            val typing = s.text("residence le manoir 1").mapNotNull { it.tags["addr:housenumber"] }.toSet()
            assertEquals(setOf("10", "11", "12", "14", "16", "18B", "12;14"), typing)
            // a number is counted as it is found: alone or in a list
            assertEquals(2, s.count("addr:housenumber", "12"))
            assertEquals(2, s.byTag("addr:housenumber", "12").size)
            assertEquals(s.count("addr:housenumber", "14"), s.byTag("addr:housenumber", "14").size)
            // as the search box shows them: one line each
            val hits = OfflineEvaluator(s).run(QueryParser.parse("addr:street=\"Résidence Le Manoir\"").query, EvalContext(limit = 100)).hits
            assertEquals(9, hits.map { it.place.key }.toSet().size)
        }
    }

    /**
     * A query is looked up through its narrowest index: a tag no index holds (payment, shelter) is checked on what
     * the indexed part finds rather than found by reading every place, and an address's street before its number.
     */
    @Test
    fun queriesStartFromAnIndexedTag() {
        PlaceStore.inMemory().use { s ->
            s.writer().use { w ->
                for (i in 0 until 40) {
                    val tags = arrayListOf("amenity" to "restaurant", "name" to "R$i")
                    if (i < 3) tags += "payment:credit_cards" to "yes"
                    w.add(place(1_000L + i, 52.37 + i * 1e-4, 4.88, *tags.toTypedArray()))
                }
                for (i in 0 until 400) w.add(place(5_000L + i, 52.30 + i * 1e-4, 4.80, "amenity" to "bench"))
                for (i in 0 until 30) w.add(node(9_000L + i, 52.36 + i * 1e-4, 4.87, "addr:street" to "Kerkstraat", "addr:housenumber" to "$i"))
                for (i in 0 until 30) w.add(node(9_100L + i, 52.35 + i * 1e-4, 4.86, "addr:street" to "Dorpsstraat", "addr:housenumber" to "$i"))
                w.finish()
            }
            assertTrue(s.indexes("amenity", "restaurant"))
            assertFalse(s.indexes("payment:credit_cards", "yes"))
            assertTrue(s.indexes("addr:street", "Kerkstraat"))
            assertFalse(s.indexes("addr:housenumber", "12"))
            val ev = OfflineEvaluator(s)
            val paying = ev.run(QueryParser.parse("amenity=restaurant and payment:credit_cards=yes").query, EvalContext(limit = 100))
            assertEquals(3, paying.total)
            // the 40 restaurants were looked at, from the lookup table; not a scan for the rare payment tag
            assertEquals(40, paying.examined)
            val one = ev.run(QueryParser.parse("addr:housenumber=12 and addr:street=Kerkstraat").query, EvalContext(limit = 100))
            assertEquals(listOf("12"), one.hits.map { it.tags["addr:housenumber"] })
            // the street's 30 points, not every 12 of the region and its places
            assertEquals(30, one.examined)
        }
    }

}
