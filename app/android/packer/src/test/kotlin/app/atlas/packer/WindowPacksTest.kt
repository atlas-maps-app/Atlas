package app.atlas.packer

import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.TagView
import app.atlas.packer.windows.Answer
import app.atlas.packer.windows.AnswerJson
import app.atlas.packer.windows.ClipPolygon
import app.atlas.packer.windows.Extract
import app.atlas.packer.windows.JsonOut
import app.atlas.packer.windows.LeftOut
import app.atlas.packer.windows.OverpassBounds
import app.atlas.packer.windows.OverpassBox
import app.atlas.packer.windows.Window
import app.atlas.packer.windows.WindowCut
import app.atlas.tiles.WindowsIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * `packer windows` on synthetic extracts: each rule of Overpass's window query as the cut ports it (a node by Overpass's
 * integers, a way by its segments, a relation by its members; the kinds asked for), the answer's bytes (the order, the
 * tags' byte order, seven decimals, the escapes, the bounds, members whole), the windows left out and why, the polygon
 * rule, and the files: deterministic, the index read back as the phone reads it.
 */
class WindowPacksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tagged = mapOf("amenity" to "bench")

    private fun node(id: Long, latE7: Int, lonE7: Int, tags: Map<String, String> = emptyMap()) = TNode(id, latE7 / 1e7, lonE7 / 1e7, tags)

    private fun extract(nodes: List<TNode>, ways: List<TWay> = emptyList(), relations: List<TRelation> = emptyList()): Extract =
        Extract.read(TestPbf(nodes, ways, relations).write(tmp.newFile()), threads = 1)

    private fun answer(e: Extract, w: Window): Answer = WindowCut(e, listOf(w), DATE).answer(w)

    private fun json(a: Answer): String {
        assertNull("left out: ${a.leftOut}", a.leftOut)
        return String(GZIPInputStream(a.gzip!!.inputStream()).readBytes(), Charsets.UTF_8)
    }

    private fun elements(a: Answer): List<JsonObject> = Json.parseToJsonElement(json(a)).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }

    private fun ids(a: Answer, type: String): List<Long> =
        elements(a).filter { it["type"]!!.jsonPrimitive.content == type }.map { it["id"]!!.jsonPrimitive.long }

    @Test
    fun aNodeOnTheBoxsEdgeIsInOrOutByOverpasssIntegers() {
        // S·1e7 is 523700000.4, which Overpass rounds to 523700000: a node there is in, though its double lies south of S;
        // N·1e7 is 523800000.6, rounded to 523800001; W's 48800000.4 to 48800000; E's 48900000.6 to 48900001
        val w = Window(1, 1, 52.37000004, 4.88000004, 52.38000006, 4.89000006)
        val e = extract(
            listOf(
                node(1, 523699999, 48850000, tagged), node(2, 523700000, 48850000, tagged),
                node(3, 523800001, 48850000, tagged), node(4, 523800002, 48850000, tagged),
                node(5, 523750000, 48799999, tagged), node(6, 523750000, 48800000, tagged),
                node(7, 523750000, 48900001, tagged), node(8, 523750000, 48900002, tagged),
                // untagged: no element of its own
                node(9, 523750000, 48850000),
            ),
        )
        assertEquals(listOf(2L, 3, 6, 7), ids(answer(e, w), "node"))
        // west of Greenwich an edge rounds half away from zero, then toward zero: W -1000000.6 to -1000001, E -999999.4 to -999999
        val west = Window(2, 2, 51.49, -0.10000006, 51.51, -0.09999994)
        val f = extract(
            listOf(
                node(1, 515000000, -1000002, tagged), node(2, 515000000, -1000001, tagged),
                node(3, 515000000, -999999, tagged), node(4, 515000000, -999998, tagged),
            ),
        )
        assertEquals(listOf(2L, 3), ids(answer(f, west), "node"))
    }

    @Test
    fun aWayIsInWhenASegmentMeetsTheBox() {
        val w = Window(1, 1, 52.37, 4.88, 52.38, 4.89)
        val e = extract(
            listOf(
                node(1, 523750000, 48700000), node(2, 523750000, 49000000), // across the box, no node inside
                node(3, 523690000, 48805000), node(4, 523705000, 48790000), // past the south-west corner, outside it
                node(5, 523690000, 48815000), node(6, 523715000, 48790000), // across the corner, just inside it
                node(7, 523750000, 48850000), // a way of one node inside
                node(8, 523850000, 48850000), // a way of one node outside
                node(9, 523760000, 48860000), node(10, 523850000, 48860000), // one node inside
            ),
            listOf(
                TWay(10, listOf(1, 2)), TWay(11, listOf(3, 4), mapOf("highway" to "path")), TWay(12, listOf(5, 6), mapOf("highway" to "path")),
                TWay(13, listOf(7)), TWay(14, listOf(8)), TWay(15, listOf(9, 10)),
            ),
        )
        val a = answer(e, w)
        assertEquals(listOf(10L, 12, 13, 15), ids(a, "way"))
        assertEquals(emptyList<Long>(), ids(a, "node"))
    }

    @Test
    fun aRelationOfTheKindsAskedForIsInByAMemberNodeOrWay() {
        val w = Window(1, 1, 52.37, 4.88, 52.38, 4.89)
        val mp = mapOf("type" to "multipolygon")
        val e = extract(
            listOf(
                node(1, 523750000, 48850000), node(2, 523900000, 48850000), node(3, 523950000, 48850000),
                node(4, 523750000, 48700000), node(5, 523750000, 49000000),
            ),
            listOf(TWay(10, listOf(4, 5)), TWay(11, listOf(2, 3))),
            listOf(
                TRelation(20, listOf(TMember(0, 1, "label")), mp), // by its member node
                TRelation(21, listOf(TMember(1, 10, "outer")), mp), // by its member way, which meets the box with no node inside
                TRelation(22, listOf(TMember(1, 11, "outer"), TMember(0, 2)), mp), // neither
                TRelation(23, listOf(TMember(0, 1)), mapOf("type" to "boundary", "boundary" to "protected_area")),
                TRelation(24, listOf(TMember(1, 10)), mapOf("type" to "boundary", "boundary" to "national_park")),
                TRelation(25, listOf(TMember(0, 1)), mapOf("type" to "boundary", "boundary" to "administrative")),
                TRelation(26, listOf(TMember(1, 10)), mapOf("type" to "route", "route" to "bus")),
                TRelation(27, listOf(TMember(0, 1)), mapOf("type" to "boundary", "boundary" to "national_parks")),
                // a relation member does not count, even one that is in
                TRelation(28, listOf(TMember(2, 20)), mp),
            ),
        )
        val a = answer(e, w)
        assertEquals(listOf(20L, 21, 23, 24), ids(a, "relation"))
        assertEquals(listOf(10L), ids(a, "way"))
    }

    @Test
    fun theAnswerIsOverpasssOutGeomInItsOrderWithItsDecimalsEscapesAndBounds() {
        val w = Window(1, 1, 52.37, 4.88, 52.38, 4.89)
        val e = extract(
            listOf(
                node(1, 523750000, 48850000, mapOf("name" to "A \"b\" \\ c\nd\te\rf\u0001g é 😀", "amenity" to "cafe")),
                node(2, 523750000, 48860000), node(3, 523760000, 48870000),
                // keys in UTF-8's byte order: ！ (U+FF01) before 😀 (U+1F600), which UTF-16's order puts first
                node(4, 523770000, 48880000, mapOf("😀" to "1", "！" to "2", "é" to "3", "~" to "4", "a" to "5", "B" to "6")),
            ),
            listOf(TWay(11, listOf(2)), TWay(10, listOf(2, 3), mapOf("highway" to "footway"))).sortedBy { it.id },
            listOf(
                TRelation(20, listOf(TMember(1, 10, "outer"), TMember(0, 1, "label"), TMember(2, 21)), mapOf("type" to "multipolygon", "natural" to "water")),
                TRelation(21, listOf(TMember(1, 11, "inner")), mapOf("type" to "multipolygon")),
            ),
        )
        // output_json.cc's layout, as osm/Diff.cpp writes an element anew: so a window's answer is Overpass's byte for byte
        val want = """
            |{
            |  "version": 0.6,
            |  "generator": "Atlas packer from an OpenStreetMap PBF extract",
            |  "osm3s": {
            |    "timestamp_osm_base": "$DATE",
            |    "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."
            |  },
            |  "elements": [
            |
            |{
            |  "type": "node",
            |  "id": 1,
            |  "lat": 52.3750000,
            |  "lon": 4.8850000,
            |  "tags": {
            |    "amenity": "cafe",
            |    "name": "A \"b\" \\ c\nd\te\rf?g é ${"\uD83D\uDE00"}"
            |  }
            |},
            |{
            |  "type": "node",
            |  "id": 4,
            |  "lat": 52.3770000,
            |  "lon": 4.8880000,
            |  "tags": {
            |    "B": "6",
            |    "a": "5",
            |    "~": "4",
            |    "é": "3",
            |    "${"\uFF01"}": "2",
            |    "${"\uD83D\uDE00"}": "1"
            |  }
            |},
            |{
            |  "type": "way",
            |  "id": 10,
            |  "bounds": {
            |    "minlat": 52.3750000,
            |    "minlon": 4.8860000,
            |    "maxlat": 52.3760000,
            |    "maxlon": 4.8870000
            |  },
            |  "nodes": [
            |    2,
            |    3
            |  ],
            |  "geometry": [
            |    { "lat": 52.3750000, "lon": 4.8860000 },
            |    { "lat": 52.3760000, "lon": 4.8870000 }
            |  ],
            |  "tags": {
            |    "highway": "footway"
            |  }
            |},
            |{
            |  "type": "way",
            |  "id": 11,
            |  "bounds": {
            |    "minlat": 52.3750000,
            |    "minlon": 4.8860000,
            |    "maxlat": 52.3750000,
            |    "maxlon": 4.8860000
            |  },
            |  "nodes": [
            |    2
            |  ],
            |  "geometry": [
            |    { "lat": 52.3750000, "lon": 4.8860000 }
            |  ]
            |},
            |{
            |  "type": "relation",
            |  "id": 20,
            |  "bounds": {
            |    "minlat": 52.3750000,
            |    "minlon": 4.8850000,
            |    "maxlat": 52.3760000,
            |    "maxlon": 4.8870000
            |  },
            |  "members": [
            |    {
            |      "type": "way",
            |      "ref": 10,
            |      "role": "outer",
            |      "geometry": [
            |         { "lat": 52.3750000, "lon": 4.8860000 },
            |         { "lat": 52.3760000, "lon": 4.8870000 }
            |      ]
            |    },
            |    {
            |      "type": "node",
            |      "ref": 1,
            |      "role": "label",
            |      "lat": 52.3750000,
            |      "lon": 4.8850000
            |    },
            |    {
            |      "type": "relation",
            |      "ref": 21,
            |      "role": ""
            |    }
            |  ],
            |  "tags": {
            |    "natural": "water",
            |    "type": "multipolygon"
            |  }
            |},
            |{
            |  "type": "relation",
            |  "id": 21,
            |  "bounds": {
            |    "minlat": 52.3750000,
            |    "minlon": 4.8860000,
            |    "maxlat": 52.3750000,
            |    "maxlon": 4.8860000
            |  },
            |  "members": [
            |    {
            |      "type": "way",
            |      "ref": 11,
            |      "role": "inner",
            |      "geometry": [
            |      ]
            |    }
            |  ],
            |  "tags": {
            |    "type": "multipolygon"
            |  }
            |}
            |
            |  ]
            |}
            |""".trimMargin()
        // the relation's bounds cover its member way and node, its relation member has none; a member way of one node is a
        // point to Overpass: it has bounds, and no points are printed
        val a = answer(e, w)
        assertEquals(want, json(a))
        assertEquals(6, a.elements)
        assertEquals(want.toByteArray().size, a.jsonBytes)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(a.gzip), a.sha256)
    }

    /**
     * A window's answer is a real Overpass server's byte for byte, but for the generator and the date: overpass.osm.ch's
     * answer of 28 October 2026 to the phone's window query for 14/8628/5754 (east of Vaduz: 198 tagged nodes, 90 ways,
     * two multipolygons, names in German), its elements written as an extract (each way's nodes; a member way that is no
     * element of the answer with nodes of its own at its points) and cut again for the same box.
     */
    @Test
    fun aWindowIsARealOverpassAnswerByteForByte() {
        val fixture = String(GZIPInputStream(javaClass.getResourceAsStream("/overpass-liechtenstein-14-8628-5754.json.gz")!!).readBytes(), Charsets.UTF_8)
        val elements = Json.parseToJsonElement(fixture).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
        fun JsonObject.tags() = (this["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val nodes = sortedMapOf<Long, TNode>()
        val ways = sortedMapOf<Long, TWay>()
        val relations = ArrayList<TRelation>()
        for (o in elements) if (o.str("type") == "node") nodes[o.str("id").toLong()] = TNode(o.str("id").toLong(), o.str("lat").toDouble(), o.str("lon").toDouble(), o.tags())
        for (o in elements) if (o.str("type") == "way") {
            val refs = o["nodes"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
            val points = o["geometry"]!!.jsonArray.map { it.jsonObject }
            for (i in refs.indices) nodes.putIfAbsent(refs[i], TNode(refs[i], points[i].str("lat").toDouble(), points[i].str("lon").toDouble()))
            ways[o.str("id").toLong()] = TWay(o.str("id").toLong(), refs, o.tags())
        }
        var synthetic = 1L shl 50
        for (o in elements) if (o.str("type") == "relation") {
            val members = o["members"]!!.jsonArray.map { it.jsonObject }.map { m ->
                val ref = m.str("ref").toLong()
                when (m.str("type")) {
                    "node" -> {
                        nodes.putIfAbsent(ref, TNode(ref, m.str("lat").toDouble(), m.str("lon").toDouble()))
                        TMember(0, ref, m.str("role"))
                    }
                    "way" -> {
                        if (ref !in ways) {
                            val refs = m["geometry"]!!.jsonArray.map { it.jsonObject }.map { p ->
                                val id = synthetic++
                                nodes[id] = TNode(id, p.str("lat").toDouble(), p.str("lon").toDouble())
                                id
                            }
                            ways[ref] = TWay(ref, refs)
                        }
                        TMember(1, ref, m.str("role"))
                    }
                    else -> TMember(2, ref, m.str("role"))
                }
            }
            relations += TRelation(o.str("id").toLong(), members, o.tags())
        }
        assertEquals(listOf(198, 90, 2), listOf("node", "way", "relation").map { t -> elements.count { it.str("type") == t } })
        val e = extract(nodes.values.toList(), ways.values.toList(), relations)
        // the box the phone asks for: the core's load::window_box of 14/8628/5754, as the stage P tool prints it
        val w = Window(8628, 5754, 47.113190500199025, 9.577437320335338, 47.13176009980098, 9.604691579664662)
        val theirs = fixture.replace("\"generator\": \"Overpass API 0.7.62.4 2390de5a\"", "\"generator\": \"${AnswerJson.GENERATOR}\"")
            .replace("\"timestamp_osm_base\": \"118046\"", "\"timestamp_osm_base\": \"$DATE\"")
        assertEquals(theirs, json(answer(e, w)))
    }

    @Test
    fun coordinatesHaveSevenDecimalsSignsIncluded() {
        val w = Window(1, 1, -0.001, -0.001, 0.001, 0.001)
        val e = extract(
            listOf(node(1, -1, -5, tagged), node(2, 0, 0, tagged), node(3, 9, 12)),
            listOf(TWay(10, listOf(1, 3))),
        )
        val text = json(answer(e, w))
        assertTrue(text, text.contains("{\n  \"type\": \"node\",\n  \"id\": 1,\n  \"lat\": -0.0000001,\n  \"lon\": -0.0000005,\n  \"tags\": {\n    \"amenity\": \"bench\"\n  }\n}"))
        assertTrue(text, text.contains("{\n  \"type\": \"node\",\n  \"id\": 2,\n  \"lat\": 0.0000000,\n  \"lon\": 0.0000000,\n"))
        assertTrue(text, text.contains("\"bounds\": {\n    \"minlat\": -0.0000001,\n    \"minlon\": -0.0000005,\n    \"maxlat\": 0.0000009,\n    \"maxlon\": 0.0000012\n  }"))
    }

    @Test
    fun aWindowWhoseAnswerTheExtractDoesNotHoldWholeIsLeftOut() {
        val mp = mapOf("type" to "multipolygon")
        val e = extract(
            listOf(
                node(1, 523050000, 48050000), node(2, 523060000, 48060000),
                node(5, 524050000, 48050000, tagged), node(6, 525500000, 48050000), node(7, 525050000, 48050000, tagged),
            ),
            // way 10's node 99 is not in the extract
            listOf(TWay(10, listOf(1, 2, 99), mapOf("highway" to "path"))),
            listOf(
                // relation 20 is in window b by its node; its way 77 is not in the extract
                TRelation(20, listOf(TMember(0, 5), TMember(1, 77, "outer")), mp),
                // relation 21 lacks a way too, but no member of it it holds is in window c
                TRelation(21, listOf(TMember(0, 6), TMember(1, 78, "outer")), mp),
            ),
        )
        assertEquals(1, e.brokenWays)
        assertEquals(2, e.brokenRelations)
        val a = Window(1, 1, 52.30, 4.80, 52.31, 4.81)
        val b = Window(1, 2, 52.40, 4.80, 52.41, 4.81)
        val c = Window(1, 3, 52.50, 4.80, 52.51, 4.81)
        val d = Window(1, 4, 52.60, 4.80, 52.61, 4.81)
        val cut = WindowCut(e, listOf(a, b, c, d), DATE)
        assertEquals(LeftOut.WAY, cut.answer(a).leftOut)
        assertEquals(LeftOut.RELATION, cut.answer(b).leftOut)
        assertEquals(listOf(7L), ids(cut.answer(c), "node"))
        // nothing there: listed all the same, an answer without elements
        val empty = cut.answer(d)
        assertEquals(0, empty.elements)
        assertTrue(json(empty).endsWith("\"elements\": [\n\n\n\n  ]\n}\n"))
    }

    @Test
    fun anElementWithAKeyTwiceLeavesItsWindowsOut() {
        // what no element of OpenStreetMap's database holds, and the core refuses: its tags are not written
        val view = TagView().apply {
            strings = arrayOf("", "name", "a", "b")
            add(1, 2)
            add(1, 3)
        }
        val out = JsonOut()
        assertFalse(AnswerJson.tags(view, out))
        assertEquals(0, out.size)
        val e = extract(listOf(node(1, 523750000, 48850000, tagged)), listOf(TWay(10, listOf(1))))
        assertEquals(0, e.twice)
        val w = Window(1, 1, 52.37, 4.88, 52.38, 4.89)
        assertEquals(listOf(10L), ids(answer(e, w), "way"))
        e.wayTwice.set(0)
        assertEquals(LeftOut.TWICE, answer(e, w).leftOut)
    }

    @Test
    fun overpasssSegmentTestAndBoundsAcrossTheAntimeridian() {
        // a box west of the line: the segment from 179.9 to -179.9 is taken eastward across it, to 180.1
        val wrapped = OverpassBox(51.0, 179.95, 53.0, -179.95)
        assertTrue(wrapped.intersects(52.0, 179.9, 52.0, -179.9))
        assertTrue(OverpassBox(51.0, -179.95, 53.0, 179.95).intersects(52.0, 179.9, 52.0, -179.9))
        // Overpass's own answer for a box beside the line that the segment crosses: no (geometry.cc's last case)
        assertFalse(OverpassBox(51.0, 179.95, 53.0, 180.0).intersects(52.0, 179.9, 52.0, -179.9))
        assertFalse(OverpassBox(51.0, 4.0, 52.0, 5.0).intersects(52.5, 4.5, 53.0, 4.5))
        assertTrue(OverpassBox(51.0, 4.0, 52.0, 5.0).intersects(50.0, 3.0, 53.0, 6.0))
        val b = OverpassBounds.ofPoints(2, { doubleArrayOf(10.0, 11.0)[it] }, { doubleArrayOf(179.9, -179.9)[it] })
        assertEquals(listOf(10.0, 179.9, 11.0, -179.9), listOf(b.south, b.west, b.north, b.east))
        val round = OverpassBounds.ofPoints(4, { 10.0 }, { doubleArrayOf(0.0, 90.0, -90.0, 179.0)[it] })
        assertEquals(listOf(-180.0, 180.0), listOf(round.west, round.east))
        assertEquals(OverpassBox.ilat(52.37000004), 1_433_700_000L)
        assertEquals(OverpassBox.ilon(-0.10000006), -1_000_001L)
        assertEquals(OverpassBox.ilon(0.0), 0L)
    }

    @Test
    fun aPolygonHoldsABoxWhollyInsideAndNotAcrossItsEdgeOrHole() {
        val p = ClipPolygon.parse(
            listOf(
                "test", "outer", "4.0 52.0", "5.0 52.0", "5.0 53.0", "4.0 53.0", "4.0 52.0", "END",
                "!hole", "4.4 52.4", "4.6 52.4", "4.6 52.6", "4.4 52.6", "END", "END",
            ),
        )
        assertTrue(p.holdsBox(52.1, 4.1, 52.2, 4.2))
        assertFalse(p.holdsBox(52.1, 3.9, 52.2, 4.2)) // across the edge
        assertFalse(p.holdsBox(52.45, 4.45, 52.5, 4.5)) // in the hole
        assertFalse(p.holdsBox(52.3, 4.3, 52.7, 4.7)) // around the hole
        assertTrue(p.holds(52.1, 4.1))
        assertFalse(p.holds(52.5, 4.5))
        try {
            ClipPolygon.parse(listOf("test", "outer", "4.0 52.0", "END"))
            fail("a ring of one corner")
        } catch (_: IOException) {}
    }

    @Test
    fun theFilesListTheWindowsInsideThePolygonAndReadBackAsThePhoneReadsThem() {
        val (pbf, poly) = region()
        val polygon = ClipPolygon.read(poly)
        val one = WindowPacks.build(pbf, tmp.newFolder("one"), polygon, "test", threads = 3, boxes = ::tileBoxes)
        val two = WindowPacks.build(pbf, tmp.newFolder("two"), polygon, "test", threads = 1, boxes = ::tileBoxes)
        // the same input, the same bytes, on any number of threads
        assertArrayEquals(one.data.readBytes(), two.data.readBytes())
        assertArrayEquals(one.index.readBytes(), two.index.readBytes())

        val all = WindowPacks.tileRange(polygon).let { (x0, y0, x1, y1) -> tileBoxes(x0, y0, x1, y1) }
        val inside = all.filter { w -> polygon.holdsBox(w.south - 0.01, w.west - 0.015, w.north + 0.01, w.east + 0.015) }
        assertTrue("${one.listed} windows", one.listed in inside.size..all.size && one.listed > 10)
        assertEquals(all.size - one.listed, one.leftOut[LeftOut.BEYOND])
        assertTrue(one.empty in 1 until one.listed)

        val index = WindowsIndex.read(one.index.readBytes())
        assertEquals(WindowPacks.LEVEL, index.level)
        assertEquals(WindowsIndex.QUERY, index.query)
        assertEquals(java.time.Instant.parse(DATE), index.date)
        assertEquals(one.data.length(), index.dataBytes)
        assertEquals(one.listed, index.size)
        val data = one.data.readBytes()
        var at = 0L
        for (i in 0 until index.size) {
            val entry = index[i]
            assertEquals(at, entry.offset)
            at += entry.length
            val member = data.copyOfRange(entry.offset.toInt(), (entry.offset + entry.length).toInt())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(member), entry.sha256)
            val answer = Json.parseToJsonElement(String(GZIPInputStream(member.inputStream()).readBytes(), Charsets.UTF_8)).jsonObject
            assertEquals(DATE, answer["osm3s"]!!.jsonObject["timestamp_osm_base"]!!.jsonPrimitive.content)
            assertTrue(index.find(entry.x, entry.y) != null)
        }
        assertEquals(data.size.toLong(), at)
    }

    @Test
    fun aRegionCutFromABiggerExtractListsTheWindowsMeetingItsBox() {
        val (pbf, poly) = region()
        val polygon = ClipPolygon.read(poly)
        val whole = WindowPacks.build(pbf, tmp.newFolder("whole"), polygon, "test", threads = 2, boxes = ::tileBoxes)
        val clip = app.atlas.geo.BBox(4.70, 52.25, 4.85, 52.45)
        val half = WindowPacks.build(pbf, tmp.newFolder("half"), polygon, "test", threads = 2, clip = clip, boxes = ::tileBoxes)
        val index = WindowsIndex.read(half.index.readBytes())
        assertTrue("${half.listed} of ${whole.listed}", half.listed in 1 until whole.listed)
        val boxes = WindowPacks.tileRange(polygon).let { (x0, y0, x1, y1) -> tileBoxes(x0, y0, x1, y1) }.associateBy { it.x to it.y }
        for (i in 0 until index.size) assertTrue(boxes.getValue(index[i].x to index[i].y).west <= clip.east)
    }

    @Test
    fun theCommandRunsTheCoresToolAndTheManifestListsBothFiles() {
        val (pbf, poly) = region()
        val (x0, y0, x1, y1) = WindowPacks.tileRange(ClipPolygon.read(poly))
        // a stand-in for the core's tool: the boxes of the range the packer asks for, as the tool prints them
        val tool = File(tmp.root, "atlas_stagep")
        val lines = tileBoxes(x0, y0, x1, y1).joinToString("\n") { "${it.x} ${it.y} ${it.south} ${it.west} ${it.north} ${it.east}" }
        tool.writeText("#!/bin/sh\n[ \"\$1 \$2 \$3 \$4 \$5\" = \"window-boxes $x0 $y0 $x1 $y1\" ] || exit 2\ncat <<'EOF'\n$lines\nEOF\n")
        tool.setExecutable(true)
        val out = tmp.newFolder("out")
        val said = ArrayList<String>()
        assertEquals(0, run(listOf("windows", pbf.path, out.path, "--poly", poly.path, "--core", tool.path, "--id", "test", "--threads", "2"), said::add))
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("test.windows: ") })
        // no box of its own for the region: the manifest takes the date from the index
        val list = Manifest.build(out, "https://example.org/releases/download/packs-20261101", splitBytes = 4096)
        val files = list["packs"]!!.jsonArray.single().jsonObject["files"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("windows", "windows-index"), files.map { it["kind"]!!.jsonPrimitive.content })
        val (windows, index) = files
        assertTrue("the data in parts", windows["parts"]!!.jsonArray.size > 1)
        assertNull("the index whole", index["parts"])
        assertEquals("https://example.org/releases/download/packs-20261101/test.windows.idx", index["url"]!!.jsonPrimitive.content)
        assertEquals(File(out, "test.windows.idx").length(), index["bytes"]!!.jsonPrimitive.long)
        assertEquals(DATE, list["packs"]!!.jsonArray.single().jsonObject["osm_timestamp"]!!.jsonPrimitive.content)
        try {
            run(listOf("windows", pbf.path, out.path, "--poly", poly.path, "--core", tool.path, "--level", "11"), said::add)
            fail("a gzip level of 11")
        } catch (_: UsageException) {}
    }

    @Test
    fun anExtractWithoutADateOrOutOfOrderIsRefused() {
        val (pbf, poly) = region(date = false)
        try {
            WindowPacks.build(pbf, tmp.newFolder(), ClipPolygon.read(poly), "test", boxes = ::tileBoxes)
            fail("an extract without its replication time")
        } catch (x: IOException) {
            assertTrue(x.message, x.message!!.contains("replication time"))
        }
        try {
            extract(listOf(node(2, 0, 0), node(1, 0, 0)))
            fail("nodes out of order")
        } catch (x: PbfFormatException) {
            assertTrue(x.message, x.message!!.contains("osmium sort"))
        }
    }

    /** A region of 0.3° by 0.2° by Haarlem: a polygon, and nodes, ways and a multipolygon across it, a part of it empty. */
    private fun region(date: Boolean = true): Pair<File, File> {
        val nodes = ArrayList<TNode>()
        val ways = ArrayList<TWay>()
        var id = 1L
        for (i in 0 until 40) for (j in 0 until 20) {
            val lat = 522500000 + i * 50000
            val lon = 47000000 + j * 100000
            // the north-east quarter empty
            if (i >= 20 && j >= 10) continue
            nodes += node(id, lat, lon, if ((i + j) % 3 == 0) mapOf("amenity" to "bench", "n" to "$i/$j") else emptyMap())
            if (j > 0 && nodes.size > 1 && nodes[nodes.size - 2].id == id - 1) ways += TWay(1000 + id, listOf(id - 1, id), mapOf("highway" to "path"))
            id++
        }
        val relations = listOf(TRelation(5000, listOf(TMember(1, ways[3].id, "outer"), TMember(1, ways[40].id, "outer")), mapOf("type" to "multipolygon")))
        val pbf = TestPbf(nodes, ways, relations).also { if (!date) it.replicationTime = 0 }.write(tmp.newFile("region.osm.pbf"))
        val poly = File(tmp.root, "region.poly").apply {
            writeText("region\n1\n   4.70E+00   5.225E+01\n   5.00E+00   5.225E+01\n   5.00E+00   5.245E+01\n   4.70E+00   5.245E+01\n   4.70E+00   5.225E+01\nEND\nEND\n")
        }
        return pbf to poly
    }

    /** Boxes like the core's: each L14 tile's, grown by about 200 m. */
    private fun tileBoxes(x0: Int, y0: Int, x1: Int, y1: Int): List<Window> {
        val n = 16384.0
        fun lat(t: Int) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * t / n))))
        val out = ArrayList<Window>()
        for (x in x0..x1) for (y in y0..y1) {
            val dlat = 200 / 110540.0
            val dlon = 200 / (111320 * Math.cos(Math.toRadians(lat(y))))
            out += Window(x, y, lat(y + 1) - dlat, x / n * 360 - 180 - dlon, lat(y) + dlat, (x + 1) / n * 360 - 180 + dlon)
        }
        return out
    }

    companion object {
        /** TestPbf's replication time. */
        const val DATE = "2025-10-09T08:53:20Z"
    }
}
