package app.atlas.packer

import app.atlas.packer.windows.ClipPolygon
import app.atlas.packer.windows.LeftOut
import app.atlas.packer.windows.Window
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * `packer windows --shard I/N` and `packer windows-join`: a run in shards of frame cells' columns writes the whole
 * run's files byte for byte, over an extract built to catch a shard that misses what its windows need: a way kept by
 * a segment alone, its nodes outside the shard's cells; relations whose member ways lie in the other shard; a way
 * lacking a node and a relation lacking a member by the border. The plan shares the columns out by their nodes, and
 * the join refuses shards that are not one run's.
 */
class WindowShardsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aRunInShardsWritesTheWholeRunsFilesByteForByte() {
        val (pbf, poly) = across()
        val polygon = ClipPolygon.read(poly)
        val whole = WindowPacks.build(pbf, tmp.newFolder("whole"), polygon, "test", threads = 2, boxes = ::tileBoxes)
        assertTrue("${whole.listed} windows", whole.listed > 40)
        // the extract leaves windows out on both sides of the border, by a way and by a relation
        assertTrue(whole.leftOut.toString(), whole.leftOut.getValue(LeftOut.WAY) > 0 && whole.leftOut.getValue(LeftOut.RELATION) > 0)

        for (n in listOf(2, 3)) {
            val dirs = (1..n).map { tmp.newFolder("shards$n-$it") }
            val shards = dirs.mapIndexed { i, dir -> WindowPacks.build(pbf, dir, polygon, "test", threads = 2, boxes = ::tileBoxes, shard = i + 1, shards = n) }
            val parts = shards.map { it.shard!! }
            // the border's columns, 525 and 526, each a shard's; with three shards the last one has none
            assertEquals(if (n == 2) listOf(525..525, 526..526) else listOf(525..525, 526..526, IntRange.EMPTY), parts[0].plan)
            for (s in parts) assertEquals(parts[0].plan, s.plan)
            assertEquals(whole.listed, shards.sumOf { it.listed })
            assertEquals(whole.listed + whole.leftOut.values.sum() - whole.leftOut.getValue(LeftOut.BEYOND), parts.sumOf { it.windows })
            for (k in LeftOut.values().filter { it != LeftOut.BEYOND }) assertEquals("$k", whole.leftOut.getValue(k), shards.sumOf { it.leftOut.getValue(k) })
            // the east shard's windows are left out by the broken way and relation too, and it holds less than the whole
            val east = shards[1]
            assertTrue(east.leftOut.toString(), east.leftOut.getValue(LeftOut.WAY) > 0 && east.leftOut.getValue(LeftOut.RELATION) > 0)
            for (s in shards) assertTrue("${s.extract} of ${whole.extract}", ways(s) < ways(whole))
            if (n == 3) assertEquals(0, shards[2].listed)

            if (n == 2) {
                // not one run's shards: out of order, one missing, or of another plan
                refused { WindowsJoin.join(tmp.newFolder(), dirs.reversed(), "test") }
                refused { WindowsJoin.join(tmp.newFolder(), dirs.take(1), "test") }
                val other = tmp.newFolder("other")
                WindowPacks.build(pbf, other, polygon, "test", threads = 2, boxes = ::tileBoxes, shard = 2, shards = 3)
                refused { WindowsJoin.join(tmp.newFolder(), listOf(dirs[0], other), "test") }
            }
            val joined = WindowsJoin.join(tmp.newFolder("joined$n"), dirs, "test", remove = n == 3)
            assertEquals(whole.listed, joined.windows)
            assertArrayEquals(whole.data.readBytes(), joined.data.readBytes())
            assertArrayEquals(whole.index.readBytes(), joined.index.readBytes())
            if (n == 3) for (dir in dirs) assertFalse("$dir removed", dir.exists())
        }
    }

    @Test
    fun theCommandsCutAndJoinTheShards() {
        val (pbf, poly) = across()
        val (x0, y0, x1, y1) = WindowPacks.tileRange(ClipPolygon.read(poly))
        // the core's tool's stand-in, as in WindowPacksTest
        val tool = File(tmp.root, "atlas_stagep")
        val lines = tileBoxes(x0, y0, x1, y1).joinToString("\n") { "${it.x} ${it.y} ${it.south} ${it.west} ${it.north} ${it.east}" }
        tool.writeText("#!/bin/sh\n[ \"\$1 \$2 \$3 \$4 \$5\" = \"window-boxes $x0 $y0 $x1 $y1\" ] || exit 2\ncat <<'EOF'\n$lines\nEOF\n")
        tool.setExecutable(true)
        val said = ArrayList<String>()
        fun windows(out: File, vararg more: String) =
            run(listOf("windows", pbf.path, out.path, "--poly", poly.path, "--core", tool.path, "--id", "test", "--threads", "2") + more, said::add)
        val whole = tmp.newFolder("whole")
        assertEquals(0, windows(whole))
        val one = tmp.newFolder("one")
        val two = tmp.newFolder("two")
        assertEquals(0, windows(one, "--shard", "1/2"))
        assertEquals(0, windows(two, "--shard", "2/2"))
        assertTrue(said.joinToString("\n"), said.any { it.startsWith("shard 2 of 2: L10 columns 526..526") })
        val out = tmp.newFolder("out")
        assertEquals(0, run(listOf("windows-join", out.path, one.path, two.path, "--id", "test", "--remove"), said::add))
        for (name in listOf("test.windows", "test.windows.idx")) assertArrayEquals(name, File(whole, name).readBytes(), File(out, name).readBytes())
        assertEquals(setOf("test.windows", "test.windows.idx"), out.list()!!.toSet())
        for (bad in listOf("0/2", "3/2", "1/65", "1", "a/b")) {
            try {
                windows(tmp.newFolder(), "--shard", bad)
                fail("--shard $bad")
            } catch (_: UsageException) {}
        }
        try {
            run(listOf("windows-join", out.path, one.path), said::add)
            fail("a join without --id")
        } catch (_: UsageException) {}
    }

    @Test
    fun theColumnsShareOutByTheirNodes() {
        // L10 columns 100 to 103, sixteen windows' columns each
        val windows = (1600 until 1664).map { Window(it, 7, 0.0, 0.0, 0.0, 0.0) }
        val even = LongArray(1024)
        assertEquals(listOf(100..103), WindowPacks.plan(windows, even, 1))
        assertEquals(listOf(100..101, 102..103), WindowPacks.plan(windows, even, 2))
        assertEquals(listOf(100..100, 101..101, 102..102, 103..103), WindowPacks.plan(windows, even, 4))
        assertEquals(listOf(100..101, 102..102, 103..103), WindowPacks.plan(windows, even, 3))
        // more shards than columns: the last ones get none
        assertEquals(List(4) { (100 + it)..(100 + it) } + List(4) { IntRange.EMPTY }, WindowPacks.plan(windows, even, 8))
        // a column of many nodes: a shard of its own, the others shared out among the rest
        val dense = LongArray(1024).also { it[100] = 10; it[101] = 10; it[102] = 10; it[103] = 1000 }
        assertEquals(listOf(100..102, 103..103), WindowPacks.plan(windows, dense, 2))
        assertEquals(listOf(100..101, 102..102, 103..103), WindowPacks.plan(windows, dense, 3))
        // Noord-Holland's way: two heavy columns between light ones, three shards, none empty and the heaviest least
        val holland = LongArray(1024).also { it[100] = 2; it[101] = 8; it[102] = 45; it[103] = 40; it[104] = 5 }
        val five = (1600 until 1680).map { Window(it, 7, 0.0, 0.0, 0.0, 0.0) }
        assertEquals(listOf(100..101, 102..102, 103..104), WindowPacks.plan(five, holland, 3))
        // a shard's file reads back as written
        val s = WindowPacks.Shard(2, 3, listOf(100..101, IntRange.EMPTY, 102..103), 32, 64)
        val back = WindowPacks.Shard.parse(s.text(), File("x"))
        assertEquals(listOf(2, 3, 32, 64), listOf(back.index, back.count, back.windows, back.of))
        assertEquals(s.plan, back.plan)
        refused { WindowPacks.Shard.parse("shard 4/3\nplan - - -\nwindows 0/0\n", File("x")) }
    }

    /**
     * The same on a real extract where one is given (-Datlas.world.shards.pbf=<extract>, .poly=<its polygon> and
     * .dir=<a folder for some GB of data>; -Datlas.world.heap for the heap), else skipped: the run whole against three
     * shards joined, at gzip level 1, with the test's boxes for the core's.
     */
    @Test
    fun aRealExtractInShardsIsTheWholeRun() {
        val pbf = System.getProperty("atlas.world.shards.pbf")?.let(::File)
        val poly = System.getProperty("atlas.world.shards.poly")?.let(::File)
        val dir = System.getProperty("atlas.world.shards.dir")?.let(::File)
        Assume.assumeTrue("no -Datlas.world.shards.pbf, .poly and .dir", pbf != null && poly != null && dir != null)
        val polygon = ClipPolygon.read(poly!!)
        val work = File(dir!!, "shards-test").apply { deleteRecursively(); mkdirs() }
        val log = { line: String -> println(line) }
        try {
            val whole = WindowPacks.build(pbf!!, File(work, "whole"), polygon, "real", threads = 2, level = 1, boxes = ::tileBoxes, log = log)
            WindowPacks.report(whole, log)
            val dirs = (1..3).map { File(work, "shard$it") }
            for ((i, d) in dirs.withIndex()) {
                WindowPacks.report(WindowPacks.build(pbf, d, polygon, "real", threads = 2, level = 1, boxes = ::tileBoxes, log = log, shard = i + 1, shards = 3), log)
            }
            val joined = WindowsJoin.join(File(work, "joined"), dirs, "real", remove = true)
            println("joined: ${joined.windows} windows, ${joined.dataBytes} bytes")
            assertEquals(whole.listed, joined.windows)
            assertTrue("the joined data is the whole run's", same(whole.data, joined.data))
            assertArrayEquals(whole.index.readBytes(), joined.index.readBytes())
        } finally {
            work.deleteRecursively()
        }
    }

    private fun same(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered(1 shl 20).use { x ->
            b.inputStream().buffered(1 shl 20).use { y ->
                val p = ByteArray(1 shl 16)
                val q = ByteArray(1 shl 16)
                while (true) {
                    val n = x.readNBytes(p, 0, p.size)
                    if (n != y.readNBytes(q, 0, q.size) || !p.copyOf(n).contentEquals(q.copyOf(n))) return false
                    if (n == 0) return true
                }
            }
        }
    }

    private fun refused(f: () -> Unit) {
        try {
            f()
            fail("not refused")
        } catch (_: IOException) {}
    }

    /** The ways a build's extract held, from its report. */
    private fun ways(r: WindowPacks.Result): Int = Regex("([0-9]+) ways").find(r.extract)!!.groupValues[1].toInt()

    /**
     * A region across the border of L10 columns 525 and 526 (4.921875° E): a polygon of 4.80 to 5.05° E by 52.25 to
     * 52.40° N, a grid of nodes with a way between each two neighbours across it, and the cases a shard could miss.
     */
    private fun across(): Pair<File, File> {
        val nodes = ArrayList<TNode>()
        val ways = ArrayList<TWay>()
        fun node(id: Long, latE7: Int, lonE7: Int, tags: Map<String, String> = emptyMap()) {
            nodes += TNode(id, latE7 / 1e7, lonE7 / 1e7, tags)
        }
        for (i in 0 until 16) for (j in 0 until 24) {
            val id = 1L + i * 24 + j
            node(id, 522620000 + i * 80000, 48050000 + j * 100000, if ((i + j) % 3 == 0) mapOf("amenity" to "bench", "n" to "$i/$j") else emptyMap())
            if (j > 0) ways += TWay(2000 + id, listOf(id - 1, id), mapOf("highway" to "path"))
        }
        // a multipolygon of a ring in each column
        node(1001, 523250000, 48350000); node(1002, 523250000, 48450000); node(1003, 523350000, 48450000); node(1004, 523350000, 48350000)
        node(1005, 523250000, 49950000); node(1006, 523250000, 50050000); node(1007, 523350000, 50050000); node(1008, 523350000, 49950000)
        // a protected area of a fence in the west column and a member node in the east
        node(1009, 523600000, 48300000); node(1010, 523600000, 48400000); node(1011, 523600000, 49900000)
        // a multipolygon in the east column lacking its other member
        node(1012, 522800000, 50100000); node(1013, 522800000, 50200000)
        // a track by the border lacking its middle node
        node(1014, 523500000, 49170000); node(1015, 523500000, 49050000)
        // a ferry across the east column whose nodes lie outside its cells, the second outside the polygon
        node(1016, 523000000, 48500000); node(1017, 523040000, 52000000)
        ways += TWay(7001, listOf(1016, 1017), mapOf("route" to "ferry"))
        ways += TWay(7002, listOf(1014, 888888, 1015), mapOf("highway" to "track"))
        ways += TWay(7003, listOf(1001, 1002, 1003, 1004, 1001))
        ways += TWay(7004, listOf(1005, 1006, 1007, 1008, 1005))
        ways += TWay(7005, listOf(1009, 1010), mapOf("barrier" to "fence"))
        ways += TWay(7006, listOf(1012, 1013))
        val relations = listOf(
            TRelation(9001, listOf(TMember(1, 7003, "outer"), TMember(1, 7004, "outer")), mapOf("type" to "multipolygon", "natural" to "water")),
            TRelation(9002, listOf(TMember(0, 1011, "label"), TMember(1, 7005, "outer")), mapOf("boundary" to "protected_area", "name" to "Twee")),
            TRelation(9003, listOf(TMember(1, 7006, "outer"), TMember(1, 99999, "outer")), mapOf("type" to "multipolygon")),
            TRelation(9004, listOf(TMember(1, 7001, "")), mapOf("type" to "route", "route" to "ferry")),
        )
        val pbf = TestPbf(nodes, ways.sortedBy { it.id }, relations).write(tmp.newFile("across.osm.pbf"))
        val poly = File(tmp.root, "across.poly").apply {
            writeText("across\n1\n   4.80E+00   5.225E+01\n   5.05E+00   5.225E+01\n   5.05E+00   5.240E+01\n   4.80E+00   5.240E+01\n   4.80E+00   5.225E+01\nEND\nEND\n")
        }
        return pbf to poly
    }

    /** Boxes like the core's, as WindowPacksTest's: each L14 tile's, grown by about 200 m. */
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
}
