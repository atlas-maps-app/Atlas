package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.PbfRelation
import app.atlas.search.PlaceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

class PlacePackerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val d = 0.001

    // A few streets of a made-up Amsterdam: the ids say what each element is for.
    private val nodes = buildList {
        add(TNode(1, 52.3700, 4.8900, mapOf("amenity" to "cafe", "name" to "Café Rood"), time = 1_700_000_000))
        add(TNode(3, 52.3702, 4.8902, mapOf("natural" to "tree", "leaf_type" to "broadleaved", "genus" to "Ulmus")))
        add(TNode(4, 52.3703, 4.8903, mapOf("created_by" to "JOSM")))
        add(TNode(5, 52.3704, 4.8904, mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263")))
        // the church's corners
        add(TNode(10, 52.3740, 4.8830))
        add(TNode(11, 52.3740, 4.8850))
        add(TNode(12, 52.3750, 4.8850))
        add(TNode(13, 52.3750, 4.8830))
        // a street
        add(TNode(20, 52.3600, 4.8800))
        add(TNode(21, 52.3600, 4.8800 + 2 * d / Math.cos(Math.toRadians(52.36))))
        add(TNode(22, 52.3600 + 2 * d, 4.8800 + 2 * d / Math.cos(Math.toRadians(52.36))))
        // the park: a square in two halves
        add(TNode(30, 52.3580, 4.8600))
        add(TNode(31, 52.3580, 4.8700))
        add(TNode(32, 52.3620, 4.8700))
        add(TNode(33, 52.3620, 4.8600))
        // a neighbourhood with a label
        add(TNode(40, 52.3760, 4.8790))
        add(TNode(41, 52.3760, 4.8850))
        add(TNode(42, 52.3820, 4.8850))
        add(TNode(43, 52.3820, 4.8790))
        add(TNode(50, 52.3790, 4.8830, mapOf("place" to "neighbourhood")))
        // far away, for the clip
        add(TNode(60, 10.0, 10.0))
        add(TNode(61, 10.001, 10.0))
    }

    private val ways = listOf(
        TWay(100, listOf(10, 11, 12, 13, 10), mapOf("amenity" to "place_of_worship", "building" to "church", "name" to "Westerkerk"), time = 1_710_000_000),
        TWay(101, listOf(20, 21, 22), mapOf("highway" to "residential", "name" to "Prinsengracht")),
        TWay(102, listOf(30, 31, 32)),
        TWay(103, listOf(30, 33, 32)),
        TWay(104, listOf(40, 41, 42, 43, 40)),
        TWay(105, listOf(60, 61), mapOf("highway" to "track", "name" to "Far away")),
        TWay(106, listOf(990, 991), mapOf("name" to "Nowhere")),
        TWay(107, listOf(10, 11), mapOf("highway" to "footway")),
    )

    private val relations = listOf(
        TRelation(200, listOf(TMember(PbfRelation.WAY, 102, "outer"), TMember(PbfRelation.WAY, 103, "outer")), mapOf("type" to "multipolygon", "leisure" to "park", "name" to "Vondelpark")),
        TRelation(201, listOf(TMember(PbfRelation.WAY, 104, "outer"), TMember(PbfRelation.NODE, 50, "label")), mapOf("type" to "boundary", "boundary" to "administrative", "admin_level" to "10", "name" to "Jordaan")),
        TRelation(202, listOf(TMember(PbfRelation.WAY, 101, "")), mapOf("type" to "route", "route" to "bus", "name" to "Lijn 21")),
        TRelation(203, listOf(TMember(PbfRelation.RELATION, 200, ""), TMember(PbfRelation.NODE, 1, "")), mapOf("type" to "site", "name" to "Museumkwartier")),
        TRelation(204, listOf(TMember(PbfRelation.RELATION, 205, "")), mapOf("type" to "site", "name" to "Loop")),
        TRelation(205, listOf(TMember(PbfRelation.RELATION, 204, ""), TMember(PbfRelation.NODE, 4, ""))),
    )

    private fun pbf(name: String = "a.osm.pbf", block: TestPbf.() -> Unit = {}): File =
        TestPbf(nodes, ways, relations).apply { blockSize = 5 }.apply(block).write(tmp.newFile(name))

    private fun pack(options: PackOptions = PackOptions(threads = 2), file: File = pbf()): Pair<PlaceStore, PackStats> {
        val store = PlaceStore.inMemory()
        return store to PlacePacker(options).pack(file, store, "Test")
    }

    private fun id(type: OsmType, id: Long) = OsmId(type, id)

    @Test
    fun keepsWhatRegionImportKeepsAndPlacesIt() {
        val (store, stats) = pack()
        store.use {
            // cafe, address, church, street, park, neighbourhood, site, loop; not the tree (no name), JOSM node, footway, route
            val kept = listOf(
                id(OsmType.NODE, 1), id(OsmType.NODE, 5), id(OsmType.WAY, 100), id(OsmType.WAY, 101), id(OsmType.WAY, 105),
                id(OsmType.RELATION, 200), id(OsmType.RELATION, 201), id(OsmType.RELATION, 203), id(OsmType.RELATION, 204),
            )
            for (k in kept) assertNotNull("$k", store.get(k))
            for (k in listOf(id(OsmType.NODE, 3), id(OsmType.NODE, 4), id(OsmType.WAY, 107), id(OsmType.RELATION, 202), id(OsmType.RELATION, 205), id(OsmType.WAY, 106))) {
                assertNull("$k", store.get(k))
            }
            assertEquals(kept.size, store.size)
            assertEquals(kept.size, stats.places)
            assertEquals(1, stats.trees)
            assertEquals(1L, stats.unlocated)
            assertEquals(2L, stats.missingNodes)
            assertEquals(nodes.size.toLong(), stats.nodes)
            assertEquals(ways.size.toLong(), stats.ways)
            assertEquals(relations.size.toLong(), stats.relations)
            assertEquals("Test", store.name)

            val cafe = store.get(id(OsmType.NODE, 1))!!
            assertEquals(52.37, cafe.location.lat, 1e-9)
            assertEquals("Café Rood", cafe.tags["name"])
            assertNull(cafe.place.bbox)
            assertTrue(cafe.place.extra.isEmpty())

            val tree = store.trees(BBox(4.88, 52.36, 4.90, 52.38)).single()
            assertEquals("Ulmus", tree.genus)
            assertEquals("broadleaved", tree.leafType)
        }
    }

    @Test
    fun waysSitAtTheirCentreOrHalfwayAlong() {
        val (store, _) = pack()
        store.use {
            val church = store.get(id(OsmType.WAY, 100))!!
            assertEquals(52.3745, church.location.lat, 1e-7)
            assertEquals(4.8840, church.location.lon, 1e-7)
            assertEquals(BBox(4.8830, 52.3740, 4.8850, 52.3750).toString(), church.place.bbox.toString())
            val street = store.get(id(OsmType.WAY, 101))!!
            // 2 units east then 2 north: halfway is the corner
            assertEquals(52.36, street.location.lat, 1e-6)
            assertEquals(4.88 + 2 * d / Math.cos(Math.toRadians(52.36)), street.location.lon, 1e-6)
        }
    }

    @Test
    fun relationsSitAtTheirLabelRingsOrBox() {
        val (store, _) = pack()
        store.use {
            val park = store.get(id(OsmType.RELATION, 200))!!
            assertEquals(52.36, park.location.lat, 1e-6)
            assertEquals(4.865, park.location.lon, 1e-6)
            assertEquals(BBox(4.86, 52.358, 4.87, 52.362).toString(), park.place.bbox.toString())
            val jordaan = store.get(id(OsmType.RELATION, 201))!!
            assertEquals(LatLon(52.379, 4.883).toString(), jordaan.location.toString())
            val site = store.get(id(OsmType.RELATION, 203))!!
            // the park's box and the cafe
            val box = site.place.bbox!!
            assertEquals(4.86, box.west, 1e-9)
            assertEquals(4.89, box.east, 1e-9)
            assertEquals(52.37, box.north, 1e-9)
            assertEquals(box.center.toString(), site.location.toString())
            // a loop of relations ends; the box comes from the one node reachable
            val loop = store.get(id(OsmType.RELATION, 204))!!
            assertEquals(52.3703, loop.location.lat, 1e-9)
        }
    }

    @Test
    fun theStoreAnswersSearches() {
        val (store, _) = pack()
        store.use {
            assertEquals(OsmId(OsmType.RELATION, 200), store.text("vondelpark", null, null, 5).first().id)
            assertEquals(OsmId(OsmType.WAY, 100), store.text("westerkerk", null, null, 5).first().id)
            assertEquals(OsmId(OsmType.NODE, 1), store.byTag("amenity", "cafe", null, 10).single().id)
            assertEquals(OsmId(OsmType.NODE, 5), store.byTag("addr:housenumber", "263", null, 10).single().id)
            // the cafe, the address, and the two sites whose boxes reach in
            assertEquals(setOf(OsmId(OsmType.NODE, 1), OsmId(OsmType.NODE, 5), OsmId(OsmType.RELATION, 203), OsmId(OsmType.RELATION, 204)), store.inBox(BBox(4.88, 52.369, 4.891, 52.3705), 10).map { it.id }.toSet())
        }
    }

    @Test
    fun aClipBoxKeepsOnlyWhatIsInside() {
        val (store, stats) = pack(PackOptions(clip = BBox(4.0, 52.0, 5.0, 53.0), threads = 1))
        store.use {
            assertNull(store.get(id(OsmType.WAY, 105)))
            assertNotNull(store.get(id(OsmType.WAY, 100)))
            assertEquals(1L, stats.clipped)
        }
    }

    @Test
    fun editTimesGoToExtra() {
        val (store, _) = pack(PackOptions(editTimes = true))
        store.use {
            assertEquals("2023-11-14T22:13:20Z", store.get(id(OsmType.NODE, 1))!!.place.extra["timestamp"])
            assertEquals("2024-03-09T16:00:00Z", store.get(id(OsmType.WAY, 100))!!.place.extra["timestamp"])
        }
    }

    @Test
    fun nodesAfterWaysAreRefused() {
        val f = pbf("unsorted.osm.pbf") { order = listOf("ways", "nodes", "relations") }
        try {
            pack(file = f)
            fail("an unsorted file must fail")
        } catch (e: PbfFormatException) {
            assertTrue(e.message!!.contains("sorted"))
        }
    }

    @Test
    fun relationsBeforeWaysStillGetTheirShapes() {
        val f = pbf("relfirst.osm.pbf") { order = listOf("nodes", "relations", "ways") }
        val (store, _) = pack(file = f)
        store.use { assertEquals(4.865, store.get(id(OsmType.RELATION, 200))!!.location.lon, 1e-6) }
    }

    @Test
    fun pinsAndBoxesAreTheDecimalsOsmWrites() {
        // 1e-7 degree integers turned into the doubles an Overpass import parses from the same decimals, to the bit
        val opera = listOf(
            TNode(1, -33.8567844, 151.2152967, mapOf("amenity" to "theatre", "name" to "Sydney Opera House")),
            TNode(2, -33.8575471, 151.2143181),
            TNode(3, -33.8558127, 151.2161573),
        )
        val f = TestPbf(opera, listOf(TWay(10, listOf(2, 3), mapOf("highway" to "footway", "name" to "Bennelong Walk")))).write(tmp.newFile("syd.osm.pbf"))
        val (store, _) = pack(file = f)
        store.use {
            val node = store.get(id(OsmType.NODE, 1))!!
            assertEquals("-33.8567844", node.location.lat.toString())
            assertEquals("151.2152967", node.location.lon.toString())
            val box = store.get(id(OsmType.WAY, 10))!!.place.bbox!!
            assertEquals(listOf("151.2143181", "-33.8575471", "151.2161573", "-33.8558127"), listOf(box.west, box.south, box.east, box.north).map { it.toString() })
        }
    }

    @Test
    fun locationsOnWaysPlaceWaysWithoutTheirNodes() {
        // `osmium add-locations-to-ways` leaves untagged nodes out: the ways' own positions must place them
        val f = pbf("low.osm.pbf") {
            wayLocations = this@PlacePackerTest.nodes.associateBy { it.id }
            optional = listOf("Sort.Type_then_ID", "LocationsOnWays")
        }
        val lean = TestPbf(nodes.filter { it.tags.isNotEmpty() }, ways, relations).apply {
            blockSize = 5
            wayLocations = this@PlacePackerTest.nodes.associateBy { it.id }
            optional = listOf("Sort.Type_then_ID", "LocationsOnWays")
        }.write(tmp.newFile("lean.osm.pbf"))
        val (full, fullStats) = pack()
        val (low, _) = pack(file = f)
        val (thin, thinStats) = pack(file = lean)
        try {
            for (s in listOf(low, thin)) assertEquals(full.size, s.size)
            for (k in listOf(id(OsmType.WAY, 100), id(OsmType.WAY, 101), id(OsmType.WAY, 105), id(OsmType.RELATION, 200), id(OsmType.RELATION, 201))) {
                val want = full.get(k)!!
                for (s in listOf(low, thin)) {
                    val got = s.get(k)!!
                    assertEquals("$k", want.location.lat, got.location.lat, 1e-9)
                    assertEquals("$k", want.location.lon, got.location.lon, 1e-9)
                    assertEquals("$k", want.place.bbox, got.place.bbox)
                }
            }
            assertEquals(fullStats.unlocated, thinStats.unlocated)
            assertEquals(fullStats.missingNodes, thinStats.missingNodes)
        } finally {
            for (s in listOf(full, low, thin)) s.close()
        }
    }

    @Test
    fun aPackIsSealedGzippedAndDescribed() {
        val out = File(tmp.newFolder("out"), "test.places.db")
        val pack = PlacesPackBuilder.build(pbf(), out, PackOptions(threads = 2), name = "Testland")
        assertEquals("test.places.db.gz", pack.file.name)
        assertFalse(out.exists())
        assertFalse(File(out.path + "-wal").exists())
        assertEquals(File(out.parentFile, "test.places.json"), pack.sidecar)
        val side = Json.parseToJsonElement(pack.sidecar.readText()).jsonObject
        assertEquals("2025-10-09T08:53:20Z", side["osm_timestamp"]!!.jsonPrimitive.content)
        assertEquals(9L, side["places"]!!.jsonPrimitive.long)
        assertEquals(pack.rawBytes, side["raw_bytes"]!!.jsonPrimitive.long)
        assertEquals("test.places.db.gz", side["file"]!!.jsonPrimitive.content)

        val back = File(tmp.root, "back.db")
        PackFiles.gunzip(pack.file, back)
        assertEquals(pack.rawBytes, back.length())
        PlaceStore.open(back).use { s ->
            assertEquals(9, s.size)
            assertEquals("Testland", s.name)
            assertEquals("2025-10-09T08:53:20Z", s.meta("osm_timestamp"))
            assertEquals("4936", s.meta("osm_sequence"))
            assertEquals(PlacesPackBuilder.VERSION, s.meta("packer"))
            assertEquals("4.0,52.0,5.0,53.0", s.meta("bbox"))
            assertFalse(s.isStale)
            assertEquals(OsmId(OsmType.RELATION, 200), s.text("vondel", null, null, 3).first().id)
        }
    }

    @Test
    fun aPlainStoreCanBeKept() {
        val out = File(tmp.newFolder("plain"), "x.places.db")
        val pack = PlacesPackBuilder.build(pbf(), out, PackOptions(threads = 1), gzip = false, vacuum = false)
        assertEquals(out, pack.file)
        assertTrue(out.isFile)
        assertEquals("x.places.db", Json.parseToJsonElement(pack.sidecar.readText()).jsonObject["file"]!!.jsonPrimitive.content)
    }
}

class RelationIndexTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun neededWaysFollowKeptRelationsDownAFewLevels() {
        val rels = listOf(
            TRelation(1, listOf(TMember(1, 10), TMember(2, 2), TMember(0, 5)), mapOf("name" to "kept")),
            TRelation(2, listOf(TMember(1, 11), TMember(1, 10), TMember(2, 3))),
            TRelation(3, listOf(TMember(1, 12), TMember(2, 4))),
            TRelation(4, listOf(TMember(1, 13), TMember(2, 1))),
            TRelation(6, listOf(TMember(1, 99))),
        )
        val f = TestPbf(relations = rels).write(tmp.newFile("r.osm.pbf"))
        val index = RelationIndex()
        app.atlas.packer.pbf.PbfReader(f).read(object : app.atlas.packer.pbf.OsmHandler {
            override fun relation(relation: PbfRelation) = index.add(relation, relation.tags.isNotEmpty())
        })
        assertEquals(5, index.size)
        assertEquals(listOf(10L, 11L, 12L, 13L), index.neededWays().toList())
        assertEquals(listOf(10L, 11L), index.neededWays(maxDepth = 1).toList())
        val s = index.slot(2)
        assertEquals(3, index.memberCount(s))
        assertEquals(3L, index.memberId(s, 2))
        assertEquals(PbfRelation.RELATION, index.memberType(s, 2))
        assertEquals(-1, index.slot(77))
    }
}
