package app.atlas.search

import app.atlas.model.OsmId
import app.atlas.model.OsmType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** The store's compact formats: varints, tag blobs, 1e-7 degree integers, Morton codes, keys, and what is kept. */
class StoreFormatTest {
    @Test
    fun varintsRoundTripAndRefuseTruncation() {
        val values = longArrayOf(0, 1, 127, 128, 300, 16_383, 16_384, 1L shl 35, Long.MAX_VALUE)
        val sink = ByteSink(2)
        for (v in values) sink.varint(v)
        for (v in longArrayOf(-1, 1, -64, 63, Long.MIN_VALUE)) sink.zigzag(v)
        val src = ByteSource(sink.toByteArray())
        for (v in values) assertEquals(v, src.varint())
        for (v in longArrayOf(-1, 1, -64, 63, Long.MIN_VALUE)) assertEquals(v, src.zigzag())
        assertFalse(src.hasMore)
        // a byte under 128 is one byte; 128 takes two
        assertEquals(1, ByteSink().apply { varint(127) }.size)
        assertEquals(2, ByteSink().apply { varint(128) }.size)
        try {
            ByteSource(byteArrayOf(0x80.toByte())).varint()
            fail("a varint cut short must not read on")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("past the end"))
        }
        try {
            ByteSink().varint(-1)
            fail("varints are unsigned")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun tagBlobsAreSmallAndRoundTrip() {
        PlaceStore.inMemory().use { s ->
            s.locked {
                val tags = linkedMapOf(
                    "amenity" to "cafe", "name" to "Café \"De Molen\"", "cuisine" to "coffee_shop;cake", "outdoor_seating" to "yes",
                    "opening_hours" to "Mo-Fr 08:00-18:00; Sa 09:00-17:00", "website" to "https://molen.example", "wheelchair" to "limited",
                    "name:ja" to "カフェ", "empty" to "", "operator" to "Molen B.V.", "addr:street" to "Westerstraat", "addr:housenumber" to "12A",
                )
                val blob = TagBlob.encode(tags, s.dict)
                assertEquals(tags, TagBlob.decode(blob, s.dict))
                // a cafe's dozen tags in about a hundred bytes, where its JSON took three hundred
                assertTrue("${blob.size} bytes", blob.size < 140)
                assertTrue(TagBlob.hasKey(blob, s.dict.findKey("website")))
                assertFalse(TagBlob.hasKey(blob, s.dict.internKey("never")))
                val keys = ArrayList<String>()
                TagBlob.forEachCode(blob) { k, _ -> keys += s.dict.key(k) }
                assertEquals(tags.keys.toList(), keys)
                // repeated values are dictionary ids; names and websites are written inline
                assertTrue(s.dict.findValue("cafe") > 0)
                assertEquals(-1, s.dict.findValue("https://molen.example"))
                assertEquals(-1, s.dict.findValue("Café \"De Molen\""))
                val extra = mapOf("rating" to "4.5", "source_id" to "ocm:1234")
                assertEquals(extra, TagBlob.decode(TagBlob.encodeInline(extra, s.dict), s.dict))
                assertEquals(emptyMap<String, String>(), TagBlob.decode(ByteArray(0), s.dict))
            }
        }
    }

    @Test
    fun degreesAsIntegersAreExact() {
        for (d in listOf("52.3745001", "-33.8567844", "151.2152967", "0.0000001", "-180.0", "89.9999999")) {
            // seven decimals in, the very double a parser makes of them out
            assertEquals(d.toDouble(), E7.deg(E7.of(d.toDouble())), 0.0)
        }
        // a box's edges: the integers inside it, whatever the double
        assertEquals(523745001, E7.atLeast(52.3745001))
        assertEquals(523745001, E7.atMost(52.3745001))
        assertEquals(523745002, E7.atLeast(52.37450011))
        assertEquals(523745001, E7.atMost(52.37450019))
        assertEquals(Int.MIN_VALUE, E7.atLeast(-1e9))
        assertEquals(Int.MAX_VALUE, E7.atMost(1e9))
    }

    @Test
    fun mortonCodesRoundTripAndRangesCoverBoxes() {
        val rnd = Random(7)
        repeat(1000) {
            val lat = rnd.nextInt(-900_000_000, 900_000_000)
            val lon = rnd.nextInt(-1_800_000_000, 1_800_000_000)
            val code = Morton.encode(lat, lon)
            assertTrue(code >= 0)
            assertEquals(lat, Morton.latE7(code))
            assertEquals(lon, Morton.lonE7(code))
        }
        // every point of a box falls in one of its ranges, and points far outside in none
        repeat(20) {
            val s = 520_000_000 + rnd.nextInt(10_000_000)
            val w = 48_000_000 + rnd.nextInt(10_000_000)
            val n = s + rnd.nextInt(1, 2_000_000)
            val e = w + rnd.nextInt(1, 2_000_000)
            val ranges = Morton.ranges(s, w, n, e)
            assertTrue(ranges.size <= 2 * 48 * 4)
            for (i in 2 until ranges.size step 2) assertTrue(ranges[i] > ranges[i - 1])
            fun covered(c: Long): Boolean {
                var i = 0
                while (i < ranges.size) { if (c in ranges[i]..ranges[i + 1]) return true; i += 2 }
                return false
            }
            repeat(200) {
                val lat = rnd.nextInt(s, n + 1)
                val lon = rnd.nextInt(w, e + 1)
                assertTrue(covered(Morton.encode(lat, lon)))
            }
            assertFalse(covered(Morton.encode(s - 50_000_000, w - 50_000_000)))
        }
        assertEquals(0, Morton.ranges(10, 10, 5, 20).size)
    }

    @Test
    fun keysPackAndTheTextRowsCarryImportance() {
        val id = OsmId(OsmType.NODE, 13_000_000_000)
        val key = StoreSchema.pack(id)
        assertTrue(key <= StoreSchema.KEY_MASK)
        for (imp in listOf(0, 1, 130, 255, 300, -5)) assertEquals(key, StoreSchema.keyOfTextRow(StoreSchema.textRow(key, imp)))
        // more important rows come first
        assertTrue(StoreSchema.textRow(key, 250) < StoreSchema.textRow(StoreSchema.pack(OsmId(OsmType.NODE, 1)), 30))
        val foreign = StoreSchema.foreignKey("ocm:52.37,4.88")
        assertEquals(3L, foreign and 3L)
        assertTrue(foreign in 0..StoreSchema.KEY_MASK)
        assertNull(StoreSchema.unpack(foreign))
        try {
            StoreSchema.pack(OsmId(OsmType.NODE, StoreSchema.MAX_OSM_ID + 1))
            fail("an id beyond the key's bits must be refused")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun importanceRanksTownsStationsAndNamedPlaces() {
        val city = Importance.of(mapOf("place" to "city", "name" to "Amsterdam", "wikidata" to "Q727"))
        val station = Importance.of(mapOf("railway" to "station", "name" to "Centraal"))
        val cafe = Importance.of(mapOf("amenity" to "cafe", "name" to "Molen"))
        val bench = Importance.of(mapOf("amenity" to "bench"))
        assertTrue(city > station && station > cafe && cafe > bench)
        assertEquals(255, city)
    }

    @Test
    fun storedTagsDropBookkeeping() {
        val poi = mapOf(
            "amenity" to "cafe", "name" to "Molen", "source" to "survey", "source:name" to "sign", "note" to "x", "fixme" to "y",
            "created_by" to "JOSM", "ref:bag" to "0363", "ref:IFOPT" to "NL:Q:1", "check_date" to "2025-01-01", "check_date:surface" to "2020",
        )
        assertEquals(setOf("amenity", "name", "ref:IFOPT", "check_date"), StoredTags.filter(poi).keys)
        val road = mapOf("highway" to "residential", "name" to "Prinsengracht", "check_date" to "2025-01-01", "source:maxspeed" to "NL:urban")
        assertEquals(setOf("highway", "name"), StoredTags.filter(road).keys)
        // places from elsewhere keep all they came with
        assertEquals(poi, StoredTags.filter(poi, source = "ocm"))
        assertTrue(StoredTags.inline("name", "Molen"))
        assertFalse(StoredTags.inline("brand", "Albert Heijn"))
        assertTrue(StoredTags.inline("amenity", "x".repeat(StoredTags.MAX_DICT_VALUE + 1)))
    }

    @Test
    fun theLookupTableHoldsKindsAndFacets() {
        assertTrue(TagIndex.byValue("amenity") && TagIndex.byValue("cuisine") && TagIndex.byValue("diet:vegan") && TagIndex.byValue("socket:type2"))
        assertFalse(TagIndex.holds("name") || TagIndex.holds("website") || TagIndex.holds("addr:street"))
        assertTrue(TagIndex.holds("opening_hours") && !TagIndex.byValue("opening_hours"))
        assertTrue(TagIndex.holdsValue("opening_hours", "24/7"))
        assertFalse(TagIndex.holdsValue("opening_hours", "Mo-Fr 09:00-17:00"))
        PlaceStore.inMemory().use { s ->
            s.locked {
                val ids = TagIndex.valueIds("cuisine", "pizza;pasta", s.dict)
                assertEquals(listOf("pizza;pasta", "pizza", "pasta"), ids.map { s.dict.value(it) })
                assertArrayEquals(intArrayOf(TagIndex.PRESENT), TagIndex.valueIds("amenity", "x".repeat(TagIndex.MAX_VALUE + 1), s.dict))
                assertArrayEquals(intArrayOf(TagIndex.PRESENT), TagIndex.valueIds("opening_hours", "Mo 09:00-12:00", s.dict))
                assertEquals(2, TagIndex.valueIds("opening_hours", "24/7", s.dict).size)
                assertEquals(0, TagIndex.valueIds("name", "Molen", s.dict).size)
            }
        }
    }

    @Test
    fun addressPointsAreOnlyAnAddress() {
        val plain = Addresses.parts(mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam", "building" to "house", "roof:shape" to "gabled", "building:levels" to "4"))!!
        assertEquals("Prinsengracht", plain.street)
        assertEquals(Addresses.STREET, plain.kind)
        assertEquals("1016 GV", plain.postcode)
        assertEquals(Addresses.PLACE, Addresses.parts(mapOf("addr:place" to "Bokrijk", "addr:housenumber" to "2"))!!.kind)
        assertEquals(Addresses.NONE, Addresses.parts(mapOf("addr:housenumber" to "2", "addr:city" to "Dorp"))!!.kind)
        // anything more keeps it a place
        for (more in listOf("name" to "Huis", "shop" to "bakery", "building" to "church", "addr:unit" to "2", "addr:place" to "X")) {
            assertNull("$more", Addresses.parts(mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263") + more))
        }
        assertNull(Addresses.parts(mapOf("addr:street" to "Prinsengracht")))
        assertEquals(
            mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam"),
            Addresses.tags(plain.street, plain.kind, plain.housenumber, plain.postcode, plain.city, plain.country),
        )
        // a cell's corner is within three bytes of every point in it
        val rnd = Random(3)
        repeat(1000) {
            val lat = rnd.nextInt(-850_000_000, 850_000_000)
            val lon = rnd.nextInt(-1_800_000_000, 1_800_000_000)
            val cell = Addresses.cellOf(lat, lon)
            val dLat = lat - Addresses.cellSouth(cell)
            val dLon = lon - Addresses.cellWest(cell)
            assertTrue("$dLat", dLat in 0 until 440_000)
            assertTrue("$dLon", dLon in 0 until 880_000)
        }
        assertEquals("prinsengracht amsterdam 1016 gv 1016gv", Addresses.streetText("Prinsengracht", "Amsterdam", "1016 GV"))
    }

    @Test
    fun addressesAreScoredByNumberAndStreet() {
        assertEquals(1.0, Addresses.numberFit("263", "263", false), 0.0)
        assertEquals(1.0, Addresses.numberFit("12a", "12A", false), 0.0)
        assertTrue(Addresses.numberFit("263h", "263-H", false) > 0.9)
        assertTrue(Addresses.numberFit("12", "12;14", false) > 0.9)
        assertTrue(Addresses.numberFit("26", "263", true) > 0.7)
        assertEquals(0.0, Addresses.numberFit("26", "263", false), 0.0)
        assertEquals(0.0, Addresses.numberFit("12", "8", true), 0.0)
        assertEquals(listOf("2449"), Addresses.queryWords(listOf("l", "2449")))
        assertEquals(listOf("a", "straat"), Addresses.queryWords(listOf("a", "straat")))
        val prinsengracht = mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam")
        assertEquals(1.0, Addresses.relevance(listOf("prinsengracht", "263"), prinsengracht), 1e-9)
        assertEquals(1.0, Addresses.relevance(listOf("263", "prinsengracht"), prinsengracht), 1e-9)
        assertTrue(Addresses.relevance(listOf("prinsengracht", "263", "amsterdam"), prinsengracht) > 0.7)
        assertTrue(Addresses.relevance(listOf("1016", "gv", "263"), prinsengracht) > 0.7)
        assertTrue(Addresses.relevance(listOf("1016gv"), prinsengracht) > 0.8)
        // the number must be this one: "rue de luxembourg 12" is not number 8 of a street in Luxembourg, postcode 1247
        val other = mapOf("addr:street" to "Rue de la Boucherie", "addr:housenumber" to "8", "addr:postcode" to "1247", "addr:city" to "Luxembourg")
        assertEquals(0.0, Addresses.relevance(listOf("rue", "de", "luxembourg", "12"), other), 0.0)
        assertEquals(0.0, Addresses.relevance(listOf("keizersgracht", "263"), prinsengracht), 0.0)
        assertTrue(Addresses.looksLikeNumber("263a") && !Addresses.looksLikeNumber("a263"))
    }

    @Test
    fun queryWordsAreCutAsTheTokenizerCutsThem() {
        assertEquals(listOf("cafe", "molen"), SearchText.indexWords("Café-Molen"))
        assertEquals(listOf("globallimit", "3cuisine", "pizza", "pasta", "200"), SearchText.indexWords("☕globallimit:3cuisine~^(pizza|pasta)\$200"))
        assertEquals(listOf("m2"), SearchText.indexWords("m²"))
    }
}
