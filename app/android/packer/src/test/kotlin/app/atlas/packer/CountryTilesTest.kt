package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.packer.country.WindowPack
import app.atlas.packer.windows.ClipPolygon
import app.atlas.render.data.PackFormatException
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleTables
import app.atlas.tiles.TilesIndex
import app.atlas.tiles.WindowsIndex
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * `packer country` on a small windows' data: `packer windows` of a synthetic extract in Haarlem cut for the core's own
 * boxes, four windows, two of them with houses, a street, benches and trees, two with nothing in them. Each window is
 * built by the core's stage P tool (-Datlas.stagep, the host-stagep preset's; the tests that need it are skipped without
 * it), and its pack in the tiles file is the core's own, built from the same answer as the phone builds it, but for its
 * chunks' and style's compression: the same manifest, reach and head byte for byte, the same tiles and style once
 * decompressed (read here by the container's own rules, the tiles decoded by Format3Reader), the head naming the
 * window, its answer's SHA-256 and the index's version. The index's ranges tile the file, each held to its SHA-256; an
 * empty window is listed with no bytes; a window the tool cannot build fails the run, named.
 */
class CountryTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tool = System.getProperty("atlas.stagep")?.let(::File)

    private fun needTool() = assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())

    /** The windows' data and its index. */
    private class Data(val data: File, val index: File) {
        val windows: WindowsIndex = WindowsIndex.read(index.readBytes())

        /** Window ([x], [y])'s answer, unzipped. */
        fun answer(x: Int, y: Int): ByteArray {
            val e = windows.find(x, y)!!
            val member = data.readBytes().copyOfRange(e.offset.toInt(), e.offset.toInt() + e.length)
            return GZIPInputStream(member.inputStream()).readBytes()
        }
    }

    @Test
    fun eachWindowsPackIsTheCoresOwnWithItsChunksAndStyleCompressed() {
        needTool()
        val d = windowsData(tool!!)
        assertEquals(listOf(A, C, B, D), (0 until d.windows.size).map { d.windows[it].x to d.windows[it].y })
        val out = tmp.newFolder("out")
        val said = ArrayList<String>()
        assertEquals(0, run(listOf("country", d.data.path, d.index.path, out.path, "--core", tool.path, "--id", "test", "--threads", "2", "--batch", "3"), said::add))
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("test.tiles: 4 windows (2 with a tile, 2 empty") })
        // the two files, and nothing of the batches' left beside them
        assertEquals(listOf("test.tiles", "test.tiles.idx"), out.list()!!.sorted())
        val tiles = File(out, "test.tiles").readBytes()
        val index = TilesIndex.read(File(out, "test.tiles.idx").readBytes())
        assertEquals(14, index.level)
        assertEquals(d.windows.date, index.date)
        assertEquals(tiles.size.toLong(), index.tilesBytes)
        assertEquals(4, index.size)

        // the core's own packs of the same answers, as the phone builds them
        val core = coresPacks(d, listOf(A, B, C, D))
        val version = core.values.map { Read(it).version }.distinct().single()
        assertEquals(version, index.version)
        assertTrue(version, Regex("core-p\\d+ s\\d+\\.\\d+-[0-9a-f]{16}").matches(version))
        assertTrue(said.any { it.contains(version) })

        var end = 0L
        for ((x, y) in listOf(A, B, C, D)) {
            val e = index.find(x, y)!!
            val theirs = Read(core.getValue(x to y))
            if (x to y in listOf(C, D)) {
                // nothing to draw: listed with no bytes, so the phone asks no one for it
                assertTrue(theirs.chunks.isEmpty())
                assertFalse(e.drawn)
                assertEquals(0, e.length)
                assertArrayEquals(ByteArray(32), e.sha256)
                continue
            }
            assertTrue("14/$x/$y", e.drawn)
            // the windows lie in the file's order (one frame cell here: x, then y), end to end
            assertEquals("14/$x/$y", end, e.offset)
            end = e.offset + e.length
            val stored = tiles.copyOfRange(e.offset.toInt(), end.toInt())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(stored), e.sha256)
            val ours = Read(stored)
            assertTrue("14/$x/$y: ${stored.size} bytes stored, ${theirs.bytes.size} as the core wrote them", stored.size < theirs.bytes.size)
            // every byte the core wrote but the compressed ones
            assertEquals(String(theirs.manifest), String(ours.manifest))
            for (s in listOf(Format.SECTION_REACH, Format.SECTION_HEAD)) assertArrayEquals(s, theirs.stored(s), ours.stored(s))
            assertEquals(theirs.sections.keys.toList(), ours.sections.keys.toList())
            // the same tiles and style once decompressed
            assertEquals(1, ours.chunks.size)
            val (c, o) = theirs.chunks.single() to ours.chunks.single()
            assertEquals(listOf(c.z, c.x, c.y, c.raw, c.count), listOf(o.z, o.x, o.y, o.raw, o.count))
            assertArrayEquals(c.sha256, o.sha256)
            val tile = ours.tile(o)
            assertArrayEquals(theirs.tile(c), tile)
            assertArrayEquals(theirs.raw(Format.SECTION_STYLE), ours.raw(Format.SECTION_STYLE))
            assertTrue(ours.sections.getValue(Format.SECTION_STYLE).stored < theirs.sections.getValue(Format.SECTION_STYLE).stored)
            // a tile of the window, its head the window's from its answer, the style the core's tables
            val t = Format3Reader.decodeTile(tile)
            assertEquals(listOf(14, x, y, o.count), listOf(t.level, t.x, t.y, t.featureCount))
            val head = RenderHead.fromBytes(ours.stored(Format.SECTION_HEAD))
            assertEquals("14/$x/$y", head.region)
            assertEquals(index.version, "${head.engineVersion} ${head.styleVersion}")
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(d.answer(x, y))), head.source)
            assertEquals("14/$x/$y", Json.parseToJsonElement(String(ours.manifest)).jsonObject["window"]!!.jsonPrimitive.content)
            StyleTables.fromBytes(ours.raw(Format.SECTION_STYLE))
        }
        assertEquals(tiles.size.toLong(), end)
        // the houses of A are many more features than B's benches
        assertTrue(index.find(A.first, A.second)!!.length > index.find(B.first, B.second)!!.length)

        // the same windows' data gives the same files, whatever the threads and batches
        val again = tmp.newFolder("again")
        assertEquals(0, run(listOf("country", d.data.path, d.index.path, again.path, "--core", tool.path, "--id", "test", "--threads", "1"), {}))
        assertArrayEquals(tiles, File(again, "test.tiles").readBytes())
        assertArrayEquals(File(out, "test.tiles.idx").readBytes(), File(again, "test.tiles.idx").readBytes())
    }

    @Test
    fun aWindowTheCoreCannotBuildFailsTheRunNamedAndLeavesNoTiles() {
        needTool()
        val d = windowsData(tool!!)
        // C's answer cut short: the core does not read it
        val broken = withAnswer(d, C, "{\n  \"version\": 0.6,\n  \"elements\": [\n".toByteArray())
        val out = tmp.newFolder("out")
        try {
            run(listOf("country", broken.data.path, broken.index.path, out.path, "--core", tool.path, "--id", "test", "--threads", "2", "--batch", "2"), {})
            fail("a window the core cannot build")
        } catch (x: IOException) {
            assertTrue(x.message, x.message!!.contains("could not build 1 of the 4 windows: 14/${C.first}/${C.second}: "))
        }
        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    @Test
    fun aDamagedPackIsRefused() {
        needTool()
        val d = windowsData(tool!!)
        val pack = coresPacks(d, listOf(A)).getValue(A)
        val good = WindowPack.read(pack)
        assertEquals("14/${A.first}/${A.second}", good.window)
        // a byte of the tile's frame: the chunks' CRC-32
        val r = Read(pack)
        val c = r.chunks.single()
        val damaged = pack.copyOf().also { it[(c.offset + c.stored - 1).toInt()] = (it[(c.offset + c.stored - 1).toInt()] + 1).toByte() }
        try {
            WindowPack.read(damaged)
            fail("a damaged chunk")
        } catch (x: PackFormatException) {
            assertTrue(x.message, x.message!!.contains("chunks is damaged"))
        }
        // and with its CRC-32 made good, the tile no longer its SHA-256's
        Read(damaged, checkCrc = false).fixCrc(Format.SECTION_CHUNKS)
        val read = WindowPack.read(damaged)
        try {
            ZstdCompressCtx().use { read.recompressed(it.setLevel(CountryTiles.ZSTD_LEVEL)) }
            fail("a tile that is not its SHA-256's")
        } catch (x: PackFormatException) {
            assertTrue(x.message, x.message!!.contains("is not its SHA-256's"))
        }
        // a pack cut short
        try {
            WindowPack.read(pack.copyOf(pack.size - 1))
            fail("a pack cut short")
        } catch (_: PackFormatException) {}
    }

    @Test
    fun aToolThatFailsOrSaysNothingFailsTheRun() {
        // stand-ins for the core's tool: one that builds nothing, naming each window; one that says nothing; one refused
        val d = handWrittenData(mapOf(A to "{}", B to "{}", C to "{}"))
        fun standIn(name: String, body: String) = File(tmp.root, name).apply {
            writeText("#!/bin/sh\n[ \"\$1\" = windows ] || exit 2\n$body\n")
            setExecutable(true)
        }
        val failing = standIn("failing", "while read -r x y path; do echo \"\$x \$y failed no data in \$path\"; done < \"\$2\"\nexit 1")
        val silent = standIn("silent", "exit 0")
        val refusing = standIn("refusing", "exit 2")
        val out = tmp.newFolder("out")
        fun failure(tool: File): String = try {
            run(listOf("country", d.data.path, d.index.path, out.path, "--core", tool.path, "--batch", "2"), {})
            "no failure"
        } catch (x: IOException) {
            x.message!!
        }
        val all = failure(failing)
        assertTrue(all, all.startsWith("the core's tool could not build 3 of the 3 windows: "))
        for ((x, y) in listOf(A, B, C)) assertTrue(all, all.contains("14/$x/$y: no data in "))
        assertTrue(failure(silent).contains("said nothing of 14/"))
        assertTrue(failure(refusing).contains("failed (exit 2)"))
        // nothing left behind, the batches' folder included
        assertEquals(emptyList<String>(), out.list()!!.toList())
        fun usage(vararg args: String): String = try {
            run(listOf("country") + args, {})
            "no error"
        } catch (x: UsageException) {
            x.message!!
        }
        assertTrue(usage(d.data.path, d.index.path, out.path).contains("--core"))
        assertTrue(usage(d.data.path, d.index.path, out.path, "--core", failing.path, "--batch", "0").contains("--batch"))
        assertTrue(usage(d.data.path, d.index.path, out.path, "--core", failing.path, "--id", "Not An Id").contains("--id"))
        assertTrue(usage(d.data.path, d.index.path, "--core", failing.path).contains("the output folder"))
    }

    /** The core's own packs of [windows], built by the tool from their answers, as the phone builds them. */
    private fun coresPacks(d: Data, windows: List<Pair<Int, Int>>): Map<Pair<Int, Int>, ByteArray> {
        val dir = tmp.newFolder()
        val list = File(dir, "list.txt")
        list.writeText(windows.joinToString("") { (x, y) -> "$x $y ${File(dir, "$x-$y.json").apply { writeBytes(d.answer(x, y)) }.path}\n" })
        val p = ProcessBuilder(tool!!.path, "windows", list.path, dir.path, "1").redirectErrorStream(true).start()
        val said = p.inputStream.bufferedReader().readText()
        assertEquals(said, 0, p.waitFor())
        return windows.associateWith { (x, y) -> File(dir, "14-$x-$y.atlas").readBytes() }
    }

    /**
     * The windows' data of an extract in Haarlem, `packer windows` cut for the core's boxes: A with twenty houses on a
     * street, benches and trees; B east of it with a house, a footway and benches; C and D south of them, empty.
     */
    private fun windowsData(tool: File): Data {
        val nodes = ArrayList<TNode>()
        val ways = ArrayList<TWay>()
        var nodeId = 1L
        var wayId = 1000L
        fun node(lat: Double, lon: Double, tags: Map<String, String> = emptyMap()): Long {
            nodes += TNode(nodeId, lat, lon, tags)
            return nodeId++
        }
        fun way(refs: List<Long>, tags: Map<String, String>) {
            ways += TWay(wayId++, refs, tags)
        }
        fun house(lat: Double, lon: Double, tags: Map<String, String>) {
            val a = node(lat, lon)
            val b = node(lat, lon + 0.00012)
            val c = node(lat + 0.00008, lon + 0.00012)
            val d = node(lat + 0.00008, lon)
            way(listOf(a, b, c, d, a), tags)
        }
        val (latA, lonA) = middle(A)
        for (i in 0 until 4) for (j in 0 until 5) {
            house(
                latA + i * 0.0003, lonA + j * 0.0002,
                mapOf("building" to "house", "building:levels" to "${2 + (i + j) % 2}", "addr:housenumber" to "${1 + i * 5 + j}") +
                    if (j % 2 == 0) mapOf("roof:shape" to "gabled") else emptyMap(),
            )
        }
        way(listOf(node(latA - 0.00012, lonA - 0.0004), node(latA - 0.00012, lonA + 0.0006), node(latA - 0.00012, lonA + 0.0012)), mapOf("highway" to "residential", "name" to "Teststraat"))
        for (k in 0 until 3) node(latA - 0.0002, lonA + k * 0.0004, mapOf("amenity" to "bench"))
        for (k in 0 until 5) node(latA + 0.0013, lonA + k * 0.0002, mapOf("natural" to "tree"))
        val (latB, lonB) = middle(B)
        house(latB, lonB, mapOf("building" to "yes"))
        way(listOf(node(latB - 0.0002, lonB - 0.0003), node(latB - 0.0002, lonB + 0.0004)), mapOf("highway" to "footway"))
        for (k in 0 until 2) node(latB - 0.0003, lonB + k * 0.0003, mapOf("amenity" to "bench"))
        val pbf = TestPbf(nodes, ways).write(tmp.newFile("haarlem.osm.pbf"))
        val poly = File(tmp.root, "haarlem.poly").apply {
            writeText("haarlem\n1\n   4.55E+00   5.230E+01\n   4.75E+00   5.230E+01\n   4.75E+00   5.247E+01\n   4.55E+00   5.247E+01\n   4.55E+00   5.230E+01\nEND\nEND\n")
        }
        // the four windows' boxes meet the clip, 400 m inside their tiles' outer edges; no other window's does
        val (s, w) = tileBox(C.first, C.second).let { it[0] to it[1] }
        val (n, e) = tileBox(B.first, B.second).let { it[2] to it[3] }
        val clip = BBox(w + 0.006, s + 0.0036, e - 0.006, n - 0.0036)
        val r = WindowPacks.build(
            pbf, tmp.newFolder("windows"), ClipPolygon.read(poly), "haarlem", threads = 2, clip = clip,
            boxes = { x0, y0, x1, y1 -> WindowPacks.coreBoxes(tool, x0, y0, x1, y1) },
        )
        return Data(r.data, r.index)
    }

    /** A windows' data of [answers] as `packer windows` writes one: each gzipped, back to back, with their index. */
    private fun handWrittenData(answers: Map<Pair<Int, Int>, String>): Data =
        written(answers.mapValues { gzip(it.value.toByteArray()) }, Instant.parse(WindowPacksTest.DATE))

    /** [d] with window [w]'s answer [json] in place of its own. */
    private fun withAnswer(d: Data, w: Pair<Int, Int>, json: ByteArray): Data {
        val bytes = d.data.readBytes()
        val members = (0 until d.windows.size).map { d.windows[it] }.associate { e ->
            (e.x to e.y) to if (e.x to e.y == w) gzip(json) else bytes.copyOfRange(e.offset.toInt(), e.offset.toInt() + e.length)
        }
        return written(members, d.windows.date)
    }

    private fun written(members: Map<Pair<Int, Int>, ByteArray>, date: Instant): Data {
        val dir = tmp.newFolder()
        val data = File(dir, "test.windows")
        val entries = ArrayList<WindowsIndex.Entry>()
        var offset = 0L
        data.outputStream().use { o ->
            for ((k, m) in members) {
                o.write(m)
                entries += WindowsIndex.Entry(k.first, k.second, offset, m.size, MessageDigest.getInstance("SHA-256").digest(m))
                offset += m.size
            }
        }
        val index = File(dir, "test.windows.idx")
        index.outputStream().use { WindowsIndex.of(14, WindowsIndex.QUERY, date, offset, entries).write(it) }
        return Data(data, index)
    }

    /**
     * A format-3 pack read by the container's own rules ([Format]), not the packer's: the header, the manifest, the
     * directory (each section held to its CRC-32), the chunk table and the chunks' frames.
     */
    private class Read(val bytes: ByteArray, checkCrc: Boolean = true) {
        /** A section of the directory; [crcAt] is where its CRC-32 lies in the pack. */
        class Section(val crcAt: Int, val offset: Long, val stored: Long, val raw: Long, val method: Int)
        class Chunk(val z: Int, val x: Int, val y: Int, val offset: Long, val stored: Int, val raw: Int, val count: Int, val sha256: ByteArray)

        val manifest: ByteArray
        val sections = LinkedHashMap<String, Section>()
        val chunks = ArrayList<Chunk>()
        private var at = 0

        private fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                val b = bytes[at++].toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return v
                shift += 7
            }
        }

        init {
            val b = ByteBuffer.wrap(bytes)
            assertEquals("ATLASPCK", String(bytes, 0, 8, Charsets.US_ASCII))
            assertEquals(Format.VERSION, bytes[9].toInt())
            val ml = b.getInt(12)
            val dl = b.getInt(16)
            manifest = bytes.copyOfRange(20, 20 + ml)
            at = 20 + ml
            while (at < 20 + ml + dl) {
                val length = varint().toInt()
                val name = String(bytes, at, length, Charsets.UTF_8)
                at += length
                val s = Section(at + 24, b.getLong(at), b.getLong(at + 8), b.getLong(at + 16), bytes[at + 28].toInt())
                val crc = CRC32().also { it.update(bytes, s.offset.toInt(), s.stored.toInt()) }.value
                if (checkCrc) assertEquals(name, b.getInt(s.crcAt).toLong() and 0xFFFFFFFFL, crc)
                sections[name] = s
                at += 29
            }
            val tiles = sections.getValue(Format.SECTION_TILES)
            at = tiles.offset.toInt()
            var frame = sections.getValue(Format.SECTION_CHUNKS).offset
            repeat(varint().toInt()) {
                val z = bytes[at++].toInt()
                val c = Chunk(z, varint().toInt(), varint().toInt(), frame, varint().toInt(), varint().toInt(), varint().toInt(), bytes.copyOfRange(at, at + 32))
                at += 32
                frame += c.stored
                chunks += c
            }
            assertEquals(tiles.offset + tiles.stored, at.toLong())
        }

        fun stored(name: String): ByteArray = sections.getValue(name).let { bytes.copyOfRange(it.offset.toInt(), (it.offset + it.stored).toInt()) }

        /** A section's raw bytes: a zstd one's frame decompressed. */
        fun raw(name: String): ByteArray {
            val s = sections.getValue(name)
            return if (s.method == Format.METHOD_ZSTD) Zstd.decompress(stored(name), s.raw.toInt()) else stored(name)
        }

        /** Chunk [c]'s tile: one frame that says its size, held to its SHA-256. */
        fun tile(c: Chunk): ByteArray {
            val frame = bytes.copyOfRange(c.offset.toInt(), c.offset.toInt() + c.stored)
            assertEquals(frame.size.toLong(), Zstd.findFrameCompressedSize(frame))
            assertEquals(c.raw.toLong(), Zstd.getFrameContentSize(frame))
            return Zstd.decompress(frame, c.raw).also { assertArrayEquals(c.sha256, MessageDigest.getInstance("SHA-256").digest(it)) }
        }

        /** The core's version: the manifest's engine_version and style_version. */
        val version: String
            get() = Json.parseToJsonElement(String(manifest)).jsonObject.let { "${it["engine_version"]!!.jsonPrimitive.content} ${it["style_version"]!!.jsonPrimitive.content}" }

        /** Writes section [name]'s CRC-32 of its stored bytes into the directory. */
        fun fixCrc(name: String) {
            val s = sections.getValue(name)
            ByteBuffer.wrap(bytes).putInt(s.crcAt, CRC32().also { it.update(bytes, s.offset.toInt(), s.stored.toInt()) }.value.toInt())
        }
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(b) } }.toByteArray()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        /** The windows: A and B side by side in Haarlem's centre, C and D south of them. */
        val A = 8403 to 5384
        val B = 8404 to 5384
        val C = 8403 to 5385
        val D = 8404 to 5385

        /** An L14 tile's box: south, west, north, east. */
        fun tileBox(x: Int, y: Int): DoubleArray {
            val n = 16384.0
            fun lat(t: Int) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * t / n))))
            return doubleArrayOf(lat(y + 1), x / n * 360 - 180, lat(y), (x + 1) / n * 360 - 180)
        }

        /** A little south-west of a window's middle, so what is put there lies well inside it. */
        fun middle(w: Pair<Int, Int>): Pair<Double, Double> = tileBox(w.first, w.second).let { b -> (b[0] + b[2]) / 2 - 0.0006 to (b[1] + b[3]) / 2 - 0.0006 }
    }
}
