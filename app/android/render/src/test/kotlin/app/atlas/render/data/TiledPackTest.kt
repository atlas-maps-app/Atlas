package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * Format 2, the tile pyramid: lossless on the renders' extracts, its queries equal format 1's on the same data, every
 * element in the one tile the rule names, chunks that change only where the data does, and the same bytes from the
 * in-memory and the spilling paths.
 */
class TiledPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun spec(id: String, src: ElementSource) =
        PackSpec(id, id, BBox(4.8, 52.3, 5.0, 52.4), LatLon(52.37, 4.88), "Europe/Amsterdam", "nl_amsterdam", JsonObject(mapOf("title" to JsonPrimitive(id))), src, listOf("test"))

    private fun tiled(id: String, src: ElementSource, levels: IntArray = PackTiles.LEVELS): PackReader = PackReader.open(PackWriter.tiledBytes(spec(id, src), levels))

    private fun canonical(list: List<OsmElement>): List<OsmElement> = list.sortedWith(OsmElement.CANONICAL)

    private fun meets(b: DoubleArray, s: Double, w: Double, n: Double, e: Double) = e >= b[1] && n >= b[0] && w <= b[3] && s <= b[2]

    /** Random boxes over the extent of [all]: street-sized, district-sized and bigger, a few around single elements. */
    private fun boxes(all: List<OsmElement>, count: Int, seed: Long): List<DoubleArray> {
        val b = DoubleArray(4)
        val withBox = all.filter { PackReader.boxOf(it, DoubleArray(4)) }
        var s0 = Double.POSITIVE_INFINITY; var w0 = s0; var n0 = Double.NEGATIVE_INFINITY; var e0 = n0
        for (e in withBox) {
            PackReader.boxOf(e, b)
            s0 = minOf(s0, b[0]); w0 = minOf(w0, b[1]); n0 = maxOf(n0, b[2]); e0 = maxOf(e0, b[3])
        }
        val rnd = Random(seed)
        return List(count) { q ->
            if (q % 7 == 6) {
                // exactly an element's own box: its edges touch
                PackReader.boxOf(withBox[rnd.nextInt(withBox.size)], b)
                doubleArrayOf(b[0], b[1], b[2], b[3])
            } else {
                val cs = s0 + (n0 - s0) * rnd.nextDouble()
                val cw = w0 + (e0 - w0) * rnd.nextDouble()
                val span = (if (q % 3 == 0) 0.0002 else if (q % 3 == 1) 0.003 else 0.04) * rnd.nextDouble()
                doubleArrayOf(cs - span, cw - span, cs + span, cw + span)
            }
        }
    }

    @Test
    fun theWesterkerkRoundTripsLosslessAndAnswersAsFormatOne() {
        assumeTrue(Repo.ams.isFile)
        val src = OverpassJsonFile(Repo.ams)
        val all = src.elements().toList()
        // Overpass's own order is canonical, so the tiled pack gives the extract back as it is
        assertEquals("data/ams is in canonical order", canonical(all).map { it.type to it.id }, all.map { it.type to it.id })
        val t0 = System.nanoTime()
        val bytes = PackWriter.tiledBytes(spec("ams", src))
        val t1 = System.nanoTime()
        val v2 = PackReader.open(bytes)
        val v1 = PackReader.open(PackWriter.toBytes(spec("ams", src)))
        assertEquals(PackManifest.TILED, v2.version)
        assertTrue(v2.isTiled && v2.manifest.isTiled)
        assertEquals(PackManifest.LAYOUT_TILES, v2.manifest.layout)
        assertArrayEquals(PackTiles.LEVELS, v2.manifest.levels)
        assertEquals(all.size, v2.elementCount)
        assertEquals(src.header, v2.header)
        assertEquals(src.dataTime, v2.manifest.dataTime)
        assertEquals(emptyList<String>(), v2.verify())
        val back = v2.elements().toList()
        assertEquals(all.size, back.size)
        for (i in all.indices) if (all[i] != back[i]) throw AssertionError("element $i differs\n  extract: ${all[i].toJson().take(400)}\n  pack:    ${back[i].toJson().take(400)}")
        // Python's own reading of the JSON, through the canonical digest
        val py = Repo.py("digest_elements.py", Repo.ams.path).split(" ", limit = 3)
        val mine = Repo.digest(v2.elements())
        assertEquals(py[0].toInt(), mine.first)
        assertEquals(py[1], mine.second)

        // queries: the same elements as format 1, in the same order
        var withHits = 0
        var t = 0L
        for ((q, b) in boxes(all, 400, 11).withIndex()) {
            val want = v1.query(b[0], b[1], b[2], b[3])
            val s = System.nanoTime()
            val got = v2.query(b[0], b[1], b[2], b[3])
            t += System.nanoTime() - s
            assertEquals("query $q (${b.toList()})", want.map { it.toString() }, got.map { it.toString() })
            for (i in want.indices) assertTrue("query $q element $i", want[i] == got[i])
            if (got.isNotEmpty()) withHits++
        }
        assertTrue(withHits > 200)
        val near = v2.around(LatLon(52.3746, 4.8840), 60.0)
        assertEquals(v1.around(LatLon(52.3746, 4.8840), 60.0), near)
        val counts = v2.tileChunks().groupingBy { it.z }.eachCount()
        println("TILED ams: ${all.size} elements, v2 ${bytes.size} bytes in ${v2.chunkCount} tiles $counts, v1 ${PackWriter.toBytes(spec("ams", src)).size} bytes; write ${(t1 - t0) / 1_000_000} ms; 400 queries ${t / 1_000_000} ms")
    }

    @Test
    fun everyLocalRegionRoundTripsAndQueriesAsFormatOne() {
        val names = Repo.localRegions()
        assumeTrue(names.isNotEmpty())
        for (name in names) {
            val raw = File(Repo.regions, "$name/raw.json")
            val src = OverpassJsonFile(raw)
            val all = src.elements().toList()
            val v2 = tiled(name, src)
            val v1 = PackReader.open(PackWriter.toBytes(spec(name, src)))
            val sorted = canonical(all)
            val back = v2.elements().toList()
            assertEquals("$name: count", sorted.size, back.size)
            for (i in sorted.indices) if (sorted[i] != back[i]) throw AssertionError("$name: element $i differs: ${sorted[i]} / ${back[i]}")
            assertEquals(emptyList<String>(), v2.verify())
            for ((q, b) in boxes(all, 60, name.hashCode().toLong()).withIndex()) {
                // format 1 answers in the extract's order (three pairs of grachten's merged parts are out of order)
                val want = canonical(v1.query(b[0], b[1], b[2], b[3]))
                val got = v2.query(b[0], b[1], b[2], b[3])
                assertEquals("$name query $q", want.map { it.toString() }, got.map { it.toString() })
                for (i in want.indices) assertTrue("$name query $q element $i", want[i] == got[i])
            }
            println("TILED $name: ${all.size} elements, ${raw.length() / 1000} KB of json, v2 ${v2.size / 1000} KB in ${v2.chunkCount} tiles, v1 ${v1.size / 1000} KB")
        }
    }

    /** A way along the given points, its box implied. */
    private fun way(id: Long, vararg pts: Double, tags: Map<String, String>? = mapOf("highway" to "residential")): OsmElement {
        val la = DoubleArray(pts.size / 2) { pts[2 * it] }
        val lo = DoubleArray(pts.size / 2) { pts[2 * it + 1] }
        return OsmElement(ElementType.WAY, id, tags = tags, bounds = Bounds(la.min(), lo.min(), la.max(), lo.max()), nodes = LongArray(la.size) { id * 100 + it }, geometry = Points(la, lo))
    }

    private fun synthetic(): List<OsmElement> {
        val rnd = Random(3)
        val out = ArrayList<OsmElement>()
        // nodes scattered over a few tiles, some exactly on tile edges
        val z14 = 1 shl 14
        for (i in 0 until 400) {
            val lat = (523000 + rnd.nextInt(2000)) / 1e4
            val lon = (48000 + rnd.nextInt(2000)) / 1e4
            out += OsmElement(ElementType.NODE, 1000L + i, lat, lon, if (i % 3 == 0) mapOf("amenity" to "bench") else mapOf("shop" to "bakery", "name" to "B$i"))
        }
        val edgeLon = Math.round((8414.0 / z14 * 360.0 - 180.0) * 1e7) / 1e7
        out += OsmElement(ElementType.NODE, 5000, 52.37, edgeLon, mapOf("on" to "edge"))
        // short ways (z14), a few kilometres (z12 or z10), tens of kilometres (z10 or z6), a continent (world)
        out += way(10, 52.370, 4.880, 52.371, 4.882)
        out += way(11, 52.30, 4.70, 52.33, 4.75)
        out += way(12, 52.0, 4.0, 52.4, 4.9)
        out += way(13, 51.0, 3.0, 53.5, 7.0)
        out += way(14, 40.0, -5.0, 60.0, 20.0)
        out += way(15, -10.0, -179.9, 10.0, 179.9)
        // beyond Mercator's limit
        out += OsmElement(ElementType.NODE, 6000, 89.5, 10.0, mapOf("place" to "pole"))
        out += OsmElement(ElementType.NODE, 6001, -89.5, -10.0, mapOf("place" to "pole"))
        // a relation with geometry, one with only a node member, one with no positions (it goes to the world tile)
        out += OsmElement(
            ElementType.RELATION, 20, tags = mapOf("type" to "multipolygon", "landuse" to "grass"), bounds = Bounds(52.36, 4.87, 52.372, 4.885),
            members = listOf(Member(ElementType.WAY, 10, "outer", geometry = Points(doubleArrayOf(52.36, 52.372), doubleArrayOf(4.87, 4.885))), Member(ElementType.RELATION, 99, "subarea")),
        )
        out += OsmElement(ElementType.RELATION, 21, tags = mapOf("type" to "multipolygon"), members = listOf(Member(ElementType.NODE, 7, "label", 52.38, 4.89)))
        out += OsmElement(ElementType.RELATION, 22, tags = mapOf("type" to "multipolygon"), members = listOf(Member(ElementType.WAY, 404, "outer")))
        // a way with a gap Overpass left as null, and bounds that differ from its points
        out += OsmElement(
            ElementType.WAY, 30, tags = mapOf("natural" to "water"), bounds = Bounds(52.35, 4.85, 52.39, 4.95), nodes = longArrayOf(1, 2, 3),
            geometry = Points(doubleArrayOf(52.36, Double.NaN, 52.38), doubleArrayOf(4.86, Double.NaN, 4.94)),
        )
        // out of canonical order on purpose
        out.shuffle(Random(5))
        return out
    }

    @Test
    fun everyElementSitsInTheTileTheRuleNamesAndQueriesFindItsNeighbours() {
        val els = synthetic()
        val r = tiled("syn", ListElementSource(els))
        assertEquals(canonical(els), r.elements().toList())
        val b = DoubleArray(4)
        val levels = PackTiles.LEVELS
        for (c in r.tileChunks()) for (e in r.chunkElements(c)) {
            val want = if (PackReader.boxOf(e, b)) PackTiles.place(b[0], b[1], b[2], b[3], levels) else PackTiles.world(levels)
            assertEquals("$e in $c", levels[PackTiles.levelOf(want)], c.z)
            assertEquals("$e in $c", PackTiles.xOf(want), c.x)
            assertEquals("$e in $c", PackTiles.yOf(want), c.y)
        }
        val zs = r.tileChunks().map { it.z }.toSet()
        assertEquals("every level is used", levels.toSet(), zs)
        // the relation without positions is kept but no query finds it
        assertTrue(r.elements().any { it.id == 22L })
        assertFalse(r.query(BBox(-180.0, -90.0, 180.0, 90.0)).any { it.id == 22L })
        // brute force over random boxes, the world and boxes touching elements' edges
        val boxes = boxes(els, 300, 9) + listOf(doubleArrayOf(-90.0, -180.0, 90.0, 180.0), doubleArrayOf(52.37, 4.88, 52.37, 4.88), doubleArrayOf(52.371, 4.882, 52.5, 5.0), doubleArrayOf(89.0, 9.0, 90.0, 11.0))
        for ((q, x) in boxes.withIndex()) {
            val want = canonical(els.filter { PackReader.boxOf(it, b) && meets(b, x[0], x[1], x[2], x[3]) })
            val got = r.query(x[0], x[1], x[2], x[3])
            assertEquals("query $q ${x.toList()}", want, got)
            // the chunks a download fetches hold every hit
            val fetched = r.chunksFor(BBox(x[1], x[0], x[3], x[2])).flatMap { r.chunkElements(it) }.toSet()
            assertTrue("query $q", fetched.containsAll(got))
        }
        // a small box reads one tile and its neighbours at each level, not the whole pack
        val small = r.chunksFor(BBox(4.881, 52.3705, 4.881, 52.3705))
        assertTrue("${small.size} of ${r.chunkCount} chunks", small.size < r.chunkCount)
        assertTrue(small.all { it.z == 0 || it.z == 14 && it.x in 8413..8415 || it.z < 14 })
    }

    @Test
    fun theSpillingPathWritesTheSameBytesAsMemoryAndTwoBuildsAgree() {
        assumeTrue(Repo.ams.isFile)
        val src = OverpassJsonFile(Repo.ams)
        val digits = PackWriter.coordinateDigits(src)
        val spec = spec("ams", src)
        val inMemory = PackWriter.tiledBytes(spec)
        assertArrayEquals("a second build", inMemory, PackWriter.tiledBytes(spec))
        val work = tmp.newFolder("work")
        val out = File(tmp.root, "spilled.atlas")
        val w = TiledPackWriter(work, digits, memory = 64L shl 10)
        var sawFiles = 0
        for ((i, e) in src.elements().withIndex()) {
            w.add(e)
            if (i % 5000 == 0) sawFiles = maxOf(sawFiles, work.listFiles().orEmpty().size)
        }
        assertTrue("the writer spilled to disk", w.spilledBytes > 0 && sawFiles > 0)
        w.finish(spec, out)
        assertArrayEquals("spilled and in-memory packs", inMemory, out.readBytes())
        assertTrue("work files are removed", !work.exists() || work.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aChangeTouchesOnlyItsOwnTileAndTheContentHashFollowsTheData() {
        assumeTrue(Repo.ams.isFile)
        val all = OverpassJsonFile(Repo.ams).elements().toList()
        val a = tiled("a", ListElementSource(all))
        // one building gone, one café renamed: two small edits in one place
        val building = all.first { it.type == ElementType.WAY && it.tag("building") != null && it.id % 7 == 3L }
        val cafe = all.first { it.type == ElementType.NODE && it.tag("amenity") == "cafe" }
        val edited = all.filter { it !== building }.map { if (it === cafe) OsmElement(it.type, it.id, it.lat, it.lon, it.tags!! + ("name" to "Renamed")) else it }
        val b = tiled("b", ListElementSource(edited))
        val ca = a.tileChunks().associateBy { Triple(it.z, it.x, it.y) }
        val cb = b.tileChunks().associateBy { Triple(it.z, it.x, it.y) }
        assertEquals(ca.keys, cb.keys)
        val changed = ca.keys.filter { ca.getValue(it).sha256Hex != cb.getValue(it).sha256Hex }
        val box = DoubleArray(4)
        fun tileOf(e: OsmElement): Triple<Int, Int, Int> {
            PackReader.boxOf(e, box)
            val p = PackTiles.place(box[0], box[1], box[2], box[3], PackTiles.LEVELS)
            return Triple(PackTiles.LEVELS[PackTiles.levelOf(p)], PackTiles.xOf(p), PackTiles.yOf(p))
        }
        assertEquals(setOf(tileOf(building), tileOf(cafe)), changed.toSet())
        assertNotEquals(a.manifest.contentSha256, b.manifest.contentSha256)
        // the same data under another name and box: same chunks, same content hash
        val again = PackReader.open(PackWriter.tiledBytes(PackSpec("other", "Other", BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), ListElementSource(all))))
        assertEquals(a.manifest.contentSha256, again.manifest.contentSha256)
        assertEquals(a.tileChunks().map { it.sha256Hex }, again.tileChunks().map { it.sha256Hex })
        println("TILED change: ${changed.size} of ${ca.size} chunks differ after two edits")
    }

    @Test
    fun damageIsFoundChunkByChunkAndFormatOneStillReads() {
        assumeTrue(Repo.ams.isFile)
        val src = OverpassJsonFile(Repo.ams)
        val bytes = PackWriter.tiledBytes(spec("ams", src))
        val r = PackReader.open(bytes)
        val victim = r.tileChunks().maxBy { it.stored }
        val bad = bytes.copyOf()
        bad[(victim.offset + victim.stored / 2).toInt()] = (bad[(victim.offset + victim.stored / 2).toInt()].toInt() xor 0x5A).toByte()
        val damaged = PackReader.open(bad).verify()
        assertEquals(listOf("chunks", "tile ${victim.z}/${victim.x}/${victim.y}"), damaged)
        // the chunk table names each chunk's byte range: contiguous, in file order, inside the file
        assertEquals(r.chunkCount, r.manifest.tileChunkCount)
        val ranges = r.tileChunks()
        for (i in 1 until ranges.size) assertEquals(ranges[i - 1].offset + ranges[i - 1].stored, ranges[i].offset)
        assertTrue(ranges.last().offset + ranges.last().stored <= r.size)
        assertEquals(r.tileChunks().sumOf { it.count }, r.elementCount)
        // format 1 packs: still version 1, still read the same
        val v1 = PackReader.open(PackWriter.toBytes(spec("ams", src)))
        assertEquals(PackManifest.VERSION, v1.version)
        assertFalse(v1.isTiled)
        assertTrue(v1.tileChunks().isEmpty())
        assertEquals(src.elements().toList(), v1.elements().toList())
        // a newer format is refused clearly
        val newer = bytes.copyOf().also { it[9] = 3 }
        val e = runCatching { PackReader.open(newer) }.exceptionOrNull()
        assertTrue("$e", e is PackFormatException && e.message!!.contains("format 3"))
    }

    @Test
    fun theTileMathIsMonotoneAndTheHilbertCurveNests() {
        // columns and rows agree with core's Mercator away from tile edges, and never go backwards
        val rnd = Random(1)
        var lastX = -1
        var lon = -180.0
        while (lon <= 180.0) {
            val x = PackTiles.tileX(lon, 14)
            assertTrue(x >= lastX)
            lastX = x
            lon += 0.0137
        }
        var lastY = Int.MAX_VALUE
        var lat = -90.0
        while (lat <= 90.0) {
            val y = PackTiles.tileY(lat, 14)
            assertTrue(y <= lastY)
            lastY = y
            lat += 0.0091
        }
        repeat(2000) {
            val p = LatLon(-80 + 160 * rnd.nextDouble(), -179 + 358 * rnd.nextDouble())
            val t = app.atlas.geo.Mercator.tileAt(p, 14)
            assertTrue(kotlin.math.abs(t.x - PackTiles.tileX(p.lon, 14)) <= 1)
            assertTrue(kotlin.math.abs(t.y - PackTiles.tileY(p.lat, 14)) <= 1)
        }
        assertEquals(0, PackTiles.tileX(-180.0, 14))
        assertEquals((1 shl 14) - 1, PackTiles.tileX(180.0, 14))
        assertEquals(0, PackTiles.tileY(90.0, 14))
        assertEquals((1 shl 14) - 1, PackTiles.tileY(-90.0, 14))
        // the Hilbert curve visits each coarse tile's children together, and every tile once
        for (z in listOf(1, 3, 6)) {
            val n = 1 shl z
            val seen = HashSet<Long>()
            for (x in 0 until n) for (y in 0 until n) {
                val h = PackTiles.hilbert(z, x, y)
                assertTrue(seen.add(h))
                for (c in 0..z) assertEquals(PackTiles.hilbert(c, x shr (z - c), y shr (z - c)), h ushr (2 * (z - c)))
            }
            assertEquals((0 until n * n).map { it.toLong() }.toSet(), seen)
        }
        repeat(500) {
            val x = rnd.nextInt(1 shl 14)
            val y = rnd.nextInt(1 shl 14)
            assertEquals(PackTiles.hilbert(11, x shr 3, y shr 3), PackTiles.hilbert(14, x, y) ushr 6)
        }
        // the rule: a box within the home tile's 3 × 3 block stays at z14, one a little bigger moves up
        val z14 = PackTiles.place(52.370, 4.880, 52.372, 4.884, PackTiles.LEVELS)
        assertEquals(0, PackTiles.levelOf(z14))
        assertEquals(PackTiles.tileX(4.882, 14), PackTiles.xOf(z14))
        val wide = PackTiles.place(52.370, 4.80, 52.372, 4.90, PackTiles.LEVELS)
        assertEquals(1, PackTiles.levelOf(wide))
        assertEquals(PackTiles.world(PackTiles.LEVELS), PackTiles.place(-60.0, -170.0, 70.0, 170.0, PackTiles.LEVELS))
        for (bad in listOf(intArrayOf(14, 12), intArrayOf(12, 14, 0), intArrayOf(30, 0), intArrayOf())) {
            assertTrue(runCatching { PackTiles.checkLevels(bad) }.exceptionOrNull() is PackFormatException)
        }
    }

    @Test
    fun otherLevelsWorkAndOffGridCoordinatesAreRefused() {
        val els = synthetic()
        val r = tiled("syn", ListElementSource(els), intArrayOf(16, 13, 9, 0))
        assertArrayEquals(intArrayOf(16, 13, 9, 0), r.levels)
        assertEquals(canonical(els), r.elements().toList())
        val b = DoubleArray(4)
        for (x in boxes(els, 100, 4)) {
            assertEquals(canonical(els.filter { PackReader.boxOf(it, b) && meets(b, x[0], x[1], x[2], x[3]) }), r.query(x[0], x[1], x[2], x[3]))
        }
        // finer coordinates choose a finer grid, as format 1 does
        val fine = listOf(OsmElement(ElementType.NODE, 1, 52.123456789, 4.1, mapOf("a" to "b")))
        val f = tiled("fine", ListElementSource(fine))
        assertEquals(9, (f.manifest.json["coord_digits"] as JsonPrimitive).content.toInt())
        assertEquals(fine, f.elements().toList())
        // a writer on a fixed grid refuses what is off it
        val w = TiledPackWriter(tmp.newFolder("w"), 7)
        val e = runCatching { w.add(fine[0]) }.exceptionOrNull()
        assertTrue("$e", e is PackFormatException)
        w.close()
        // an empty pack is a pack
        val empty = tiled("empty", ListElementSource(emptyList()))
        assertEquals(0, empty.chunkCount)
        assertEquals(emptyList<OsmElement>(), empty.query(BBox(-180.0, -90.0, 180.0, 90.0)))
        assertEquals(emptyList<String>(), empty.verify())
    }

    /** Reads ranges of a pack held in memory and records them, as a phone reading over the network would. */
    private class CountingInput(private val bytes: ByteArray) : PackInput {
        val reads = ArrayList<LongRange>()
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            reads += offset until offset + length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        override fun close() {}
    }

    @Test
    fun aQueryReadsOnlyTheTablesAndTheChunksItNames() {
        // Schiphol's extract spans 27 tiles (the Westerkerk's only 6, all within a tile of any point in it)
        val raw = File(Repo.regions, "schiphol/raw.json")
        assumeTrue(raw.isFile)
        val src = OverpassJsonFile(raw)
        val bytes = PackWriter.tiledBytes(spec("schiphol", src))
        val input = CountingInput(bytes)
        val r = PackReader.open(input)
        val at = src.elements().first { it.type == ElementType.NODE }
        val box = BBox.around(LatLon(at.lat, at.lon), 80.0)
        val tables = input.reads.size
        val hits = r.query(box)
        assertTrue(hits.isNotEmpty())
        val chunkReads = input.reads.drop(tables + 1)
        val named = r.chunksFor(box).map { it.offset until it.offset + it.stored }
        assertEquals(named.toSet(), chunkReads.toSet())
        assertTrue("${named.size} of ${r.chunkCount} chunks", named.size < r.chunkCount)
        // the same query again comes from the cache
        val before = input.reads.size
        assertEquals(hits, r.query(box))
        assertEquals(before, input.reads.size)
        println("TILED range reads: ${chunkReads.sumOf { it.last - it.first + 1 }} chunk bytes of ${bytes.size} for an 80 m query")
    }

    @Test
    fun aQueryCanDropElementsAsItGoes() {
        assumeTrue(Repo.ams.isFile)
        val src = OverpassJsonFile(Repo.ams)
        val v2 = tiled("ams", src)
        val v1 = PackReader.open(PackWriter.toBytes(spec("ams", src)))
        val box = BBox.around(LatLon(52.3746, 4.8840), 400.0)
        val notBuildings: (OsmElement) -> Boolean = { it.tag("building") == null && it.tag("building:part") == null }
        assertEquals(v1.query(box, notBuildings), v2.query(box, notBuildings))
        assertTrue(v2.query(box, notBuildings).size < v2.query(box).size)
    }
}
