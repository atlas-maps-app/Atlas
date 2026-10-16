package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackManifest
import app.atlas.render.data.PackReader
import app.atlas.render.data.TiledPackWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * `packer pbf --tiled`: Geofabrik's Monaco extract streamed into a tiled pack holds exactly what [PbfRegion] cuts for a
 * box around the whole file, the same bytes every time and whatever the memory, and answers box queries as a scan does.
 * With Luxembourg downloaded to cache/packs, the build also runs in a heap scaled from the Netherlands' 6 GB.
 */
class TiledPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val monaco: File by lazy { File(TiledPackTest::class.java.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI()) }
    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))
    private val world = BBox(-180.0, -90.0, 180.0, 90.0)

    private fun boxOf(e: OsmElement): DoubleArray? {
        e.bounds?.let { return doubleArrayOf(it.minLat, it.minLon, it.maxLat, it.maxLon) }
        if (!e.lat.isNaN()) return doubleArrayOf(e.lat, e.lon, e.lat, e.lon)
        return null
    }

    @Test
    fun monacoBecomesATiledPackHoldingWhatPbfRegionCuts() {
        val out = File(tmp.root, "monaco.atlas")
        val r = TiledPack.build(monaco, out, "monaco", "Monaco", threads = 2)
        val want = PbfRegion.extract(monaco, world, threads = 2).elements
        PackReader.open(out).use { p ->
            assertEquals(PackManifest.TILED, p.version)
            assertEquals(emptyList<String>(), p.verify())
            assertEquals("monaco", p.id)
            assertEquals(want.size, p.elementCount)
            val got = p.elements().toList()
            assertEquals(want.size, got.size)
            for (i in want.indices) if (want[i] != got[i]) throw AssertionError("element $i differs\n  cut:  ${want[i].toJson().take(400)}\n  pack: ${got[i].toJson().take(400)}")
            assertTrue(got.any { it.type == app.atlas.render.data.ElementType.RELATION && it.members!!.any { m -> m.geometry != null } })
            assertEquals("2026-10-13T20:21:31Z", p.manifest.dataTime)
            // box queries against a scan of the cut
            val boxes = want.mapNotNull(::boxOf)
            val s0 = boxes.minOf { it[0] }; val w0 = boxes.minOf { it[1] }; val n0 = boxes.maxOf { it[2] }; val e0 = boxes.maxOf { it[3] }
            val rnd = Random(2)
            var hits = 0
            repeat(200) { q ->
                val cs = s0 + (n0 - s0) * rnd.nextDouble()
                val cw = w0 + (e0 - w0) * rnd.nextDouble()
                val span = (if (q % 2 == 0) 0.0005 else 0.005) * rnd.nextDouble()
                val s = cs - span; val n = cs + span; val w = cw - span; val e = cw + span
                val expected = want.filter { el -> boxOf(el)?.let { b -> e >= b[1] && n >= b[0] && w <= b[3] && s <= b[2] } == true }
                val found = p.query(s, w, n, e)
                assertEquals("query $q", expected, found)
                if (found.isNotEmpty()) hits++
            }
            assertTrue(hits > 100)
            println("TILEDPACK monaco: ${r.stats.elements} elements (${r.stats.nodes} nodes, ${r.stats.ways} ways, ${r.stats.relations} relations) in ${r.stats.chunks} tiles, ${out.length() / 1024} KiB from a ${monaco.length() / 1024} KiB PBF in ${r.millis} ms; old generation peak ${r.peakHeap?.let { it shr 20 }} MiB")
        }
    }

    @Test
    fun twoBuildsGiveTheSameBytesWhateverTheMemory() {
        val a = File(tmp.root, "a.atlas")
        val b = File(tmp.root, "b.atlas")
        val c = File(tmp.root, "c.atlas")
        TiledPack.build(monaco, a, "monaco", "Monaco", threads = 1)
        TiledPack.build(monaco, b, "monaco", "Monaco", threads = 3)
        val spilled = TiledPack.build(monaco, c, "monaco", "Monaco", threads = 2, memory = 32L shl 10)
        assertTrue("the small budget spilled to disk", spilled.spilledBytes > 0)
        assertArrayEquals(a.readBytes(), b.readBytes())
        assertArrayEquals(a.readBytes(), c.readBytes())
        val hashes = PackReader.open(a).use { p -> p.manifest.contentSha256 }
        assertEquals(hashes, PackReader.open(c).use { it.manifest.contentSha256 })
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.endsWith(".work") })
    }

    @Test
    fun theCommandLineMakesTiledPacksOfTheFileAndOfABox() {
        val dir = tmp.newFolder("cli")
        val whole = File(dir, "mc.atlas")
        val lines = ArrayList<String>()
        assertEquals(0, TiledPack.run(listOf("pbf", monaco.path, "--tiled", "--name", "Monaco", "--out", whole.path, "--threads", "2"), lines::add))
        assertTrue(lines.last(), lines.last().startsWith(whole.path) && lines.last().contains("tiles"))
        lines.clear()
        assertEquals(0, run(listOf("packinfo", whole.path), lines::add))
        assertTrue(lines.any { it.contains("all sections intact") })
        assertTrue(lines.any { it.contains("\"layout\": \"tiles\"") })
        // a box: the elements PbfRegion cuts for it, tiled
        val boxed = File(dir, "port.atlas")
        val box = "7.415,43.728,7.43,43.74"
        assertEquals(0, TiledPack.run(listOf("pbf", monaco.path, box, "--tiled", "--name", "Port Hercule", "--country", "FR", "--out", boxed.path), lines::add))
        val cut = PbfRegion.extract(monaco, parseBox(box), threads = 1).elements
        PackReader.open(boxed).use { p ->
            assertEquals(cut, p.elements().toList())
            assertEquals("port-hercule", p.id)
            assertEquals("Europe/Paris", p.manifest.timezone)
        }
        for (bad in listOf(listOf("pbf", monaco.path, "--tiled", "--frobnicate"), listOf("pbf", monaco.path), listOf("pbf", "--tiled"), listOf("pbf", monaco.path, "--tiled", "--threads", "0"))) {
            assertTrue(bad.toString(), runCatching { TiledPack.run(bad) {} }.exceptionOrNull() is UsageException)
        }
    }

    @Test
    fun tagKeysSortInUtf8ByteOrder() {
        val rnd = Random(4)
        val alphabet = "aAzZ09_:é€�".toCharArray().map { it.toString() } + listOf("😀", "𐀀", "􏿿")
        repeat(20000) {
            val a = (0 until rnd.nextInt(4)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            val b = (0 until rnd.nextInt(4)).joinToString("") { alphabet[rnd.nextInt(alphabet.size)] }
            assertEquals("'$a' '$b'", Integer.signum(PbfRegion.compareUtf8(a, b)), Integer.signum(TiledPack.utf8Order(a, b)))
        }
    }

    /**
     * The Netherlands must pack in a 6 GB heap. The heap a build needs grows with the nodes (their locations, 12 bytes
     * each) and little else, so Luxembourg must pack in 6 GB scaled by its share of the Netherlands' nodes, plus a
     * fixed allowance for the JVM and the writer's buffers.
     */
    @Test
    fun luxembourgPacksInAHeapScaledFromTheNetherlands() {
        val lux = File(repo, "cache/packs/luxembourg.osm.pbf")
        assumeTrue("download Luxembourg to $lux to run this", lux.isFile)
        val nodes = app.atlas.packer.pbf.PbfReader(lux).count().nodes
        val heapMb = (6144.0 * nodes / NETHERLANDS_NODES + FIXED_MB).toInt()
        val out = File(tmp.root, "lux.atlas")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-Xmx${heapMb}m", "-cp", System.getProperty("java.class.path"), TiledPackFork::class.java.name, lux.path, out.path, (TiledPackWriter.DEFAULT_MEMORY shr 20).toString())
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = p.waitFor()
        println("TILEDPACK luxembourg: $nodes nodes, -Xmx${heapMb}m (6 GB × ${"%.4f".format(nodes / NETHERLANDS_NODES)} + $FIXED_MB MB)\n${text.lines().takeLast(14).joinToString("\n")}")
        assertEquals("the build in -Xmx${heapMb}m failed:\n$text", 0, code)
        PackReader.open(out).use { r ->
            assertEquals(PackManifest.TILED, r.version)
            assertTrue(r.elementCount > 100_000)
        }
    }

    companion object {
        /**
         * Nodes in Geofabrik's Netherlands extract (1.41 GB on 16 October 2026): taginfo's Netherlands database (data
         * until 14 October) counts 158 M objects, of which 18.7 M ways and 0.22 M relations, so 139 M nodes; rounded up.
         */
        const val NETHERLANDS_NODES = 140_000_000.0

        /** The JVM, the reader's inflaters and the writer's buffers, which do not grow with the extract. */
        const val FIXED_MB = 160
    }
}

/** Builds a tiled pack in a JVM of its own, for the heap test: args are the extract, the pack and the buffer in MiB. */
object TiledPackFork {
    @JvmStatic
    fun main(args: Array<String>) {
        val r = TiledPack.build(File(args[0]), File(args[1]), "fork", "Fork", memory = args[2].toLong() shl 20) { println(it) }
        println("built ${r.stats.elements} elements in ${r.stats.chunks} tiles, ${r.file.length() shr 20} MiB, ${r.millis / 1000} s, old generation peak ${r.peakHeap?.let { it shr 20 }} MiB, max heap ${Runtime.getRuntime().maxMemory() shr 20} MiB")
    }
}
