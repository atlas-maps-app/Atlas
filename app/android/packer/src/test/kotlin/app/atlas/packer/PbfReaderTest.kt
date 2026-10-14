package app.atlas.packer

import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

class PbfReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Everything a handler saw, as text, so two reads can be compared whole. */
    private class Recorder : OsmHandler {
        val lines = ArrayList<String>()
        override fun node(node: PbfNode) {
            lines += "n${node.id} ${node.latE7} ${node.lonE7} v${node.version} t${node.timestampMillis} ${node.tags.toMap()}"
        }
        override fun way(way: PbfWay) {
            lines += "w${way.id} ${(0 until way.refCount).map { way.ref(it) }} v${way.version} t${way.timestampMillis} ${way.tags.toMap()}"
        }
        override fun relation(relation: PbfRelation) {
            val m = (0 until relation.memberCount).map { "${relation.memberType(it)}:${relation.memberId(it)}:${relation.role(it)}" }
            lines += "r${relation.id} $m v${relation.version} t${relation.timestampMillis} ${relation.tags.toMap()}"
        }
    }

    private val nodes = listOf(
        TNode(1, 52.3740300, 4.8896900, mapOf("amenity" to "cafe", "name" to "De Koffieschenkerij"), time = 1_600_000_000, version = 3),
        TNode(2, 52.3741000, 4.8898000),
        TNode(5, -33.8567844, 151.2152967, mapOf("tourism" to "attraction"), time = 1_600_000_100),
        TNode(9_000_000_000, 0.0000001, -0.0000001),
        TNode(9_000_000_001, -89.9999999, -179.9999999, mapOf("name" to "Zuid; ;pool")),
    )
    private val ways = listOf(
        TWay(10, listOf(1, 2, 5, 1), mapOf("building" to "yes", "name" to "Huis"), time = 1_700_000_000, version = 7),
        TWay(11, listOf(9_000_000_001, 2)),
    )
    private val relations = listOf(
        TRelation(20, listOf(TMember(1, 10, "outer"), TMember(0, 1, "label"), TMember(2, 21, "")), mapOf("type" to "multipolygon", "name" to "Erf")),
        TRelation(21, listOf(TMember(1, 11, "inner"))),
    )

    private fun expected(): List<String> {
        val lines = ArrayList<String>()
        for (n in nodes) lines += "n${n.id} ${Math.round(n.lat * 1e7)} ${Math.round(n.lon * 1e7)} v${n.version} t${n.time * 1000} ${n.tags}"
        for (w in ways) lines += "w${w.id} ${w.refs} v${w.version} t${w.time * 1000} ${w.tags}"
        for (rel in relations) lines += "r${rel.id} ${rel.members.map { "${it.type}:${it.id}:${it.role}" }} v${rel.version} t${rel.time * 1000} ${rel.tags}"
        return lines
    }

    private fun read(file: File, threads: Int = 3, kinds: Int = PbfReader.ALL): List<String> {
        val r = Recorder()
        PbfReader(file, threads).read(r, kinds)
        return r.lines
    }

    @Test
    fun denseNodesWaysAndRelationsReadBackExactly() {
        val f = TestPbf(nodes, ways, relations).apply { blockSize = 2 }.write(tmp.newFile("a.osm.pbf"))
        assertEquals(expected(), read(f))
        assertEquals(expected(), read(f, threads = 1))
    }

    @Test
    fun plainNodesRawBlobsAndCustomGranularity() {
        val coarse = nodes.map { it.copy(lat = Math.round(it.lat * 1e6) / 1e6, lon = Math.round(it.lon * 1e6) / 1e6) }
        val pbf = TestPbf(coarse, ways, relations).apply {
            dense = false
            zlib = false
            granularity = 1000
            latOffset = 20_000L
            lonOffset = -30_000L
            dateGranularity = 500
            packedRefs = false
        }
        val got = read(pbf.write(tmp.newFile("b.osm.pbf")))
        assertEquals(nodes.size + ways.size + relations.size, got.size)
        val first = got[0].split(' ')
        // stored as 1e-6 degree steps from the offsets, read back as the same positions and times
        assertEquals(523740300, first[1].toInt())
        assertEquals(48896900, first[2].toInt())
        assertEquals("t1600000000000", first[4])
        assertEquals(coarse.map { "${Math.round(it.lat * 1e7)} ${Math.round(it.lon * 1e7)}" }, got.take(nodes.size).map { it.split(' ').let { p -> "${p[1]} ${p[2]}" } })
        assertEquals(expected().drop(nodes.size), got.drop(nodes.size))
    }

    @Test
    fun locationsOnWaysAreRead() {
        // osmium add-locations-to-ways: positions beside the refs, here with offsets and a coarse granularity
        val coarse = nodes.map { it.copy(lat = Math.round(it.lat * 1e6) / 1e6, lon = Math.round(it.lon * 1e6) / 1e6) }
        val at = coarse.associateBy { it.id } - 5L
        val f = TestPbf(coarse, ways, relations).apply {
            wayLocations = at
            granularity = 1000
            latOffset = 20_000L
            lonOffset = -30_000L
            optional = listOf("Sort.Type_then_ID", "LocationsOnWays")
        }.write(tmp.newFile("low.osm.pbf"))
        val seen = ArrayList<String>()
        PbfReader(f).read(object : OsmHandler {
            override fun way(way: PbfWay) {
                assertTrue(way.hasLocations)
                for (i in 0 until way.refCount) {
                    val ref = way.ref(i)
                    if (!way.hasLocation(i)) { seen += "w${way.id} $ref unknown"; continue }
                    val n = at.getValue(ref)
                    assertEquals("w${way.id} $ref", Math.round(n.lat * 1e7).toInt(), way.latE7(i))
                    assertEquals("w${way.id} $ref", Math.round(n.lon * 1e7).toInt(), way.lonE7(i))
                    seen += "w${way.id} $ref"
                }
            }
        }, PbfReader.WAYS)
        assertEquals(listOf("w10 1", "w10 2", "w10 5 unknown", "w10 1", "w11 9000000001", "w11 2"), seen)
        // the same ways without locations say so
        val plain = TestPbf(nodes, ways).write(tmp.newFile("plain.osm.pbf"))
        PbfReader(plain).read(object : OsmHandler {
            override fun way(way: PbfWay) = assertFalse(way.hasLocations || way.hasLocation(0))
        })
    }

    @Test
    fun kindsAreFiltered() {
        val f = TestPbf(nodes, ways, relations).write(tmp.newFile("c.osm.pbf"))
        assertEquals(expected().filter { it.startsWith("r") }, read(f, kinds = PbfReader.RELATIONS))
        assertEquals(expected().filter { it.startsWith("w") }, read(f, kinds = PbfReader.WAYS))
        assertEquals(expected().filter { !it.startsWith("w") }, read(f, kinds = PbfReader.NODES or PbfReader.RELATIONS))
    }

    @Test
    fun headerSaysWhatTheFileIs() {
        val f = TestPbf(nodes).write(tmp.newFile("h.osm.pbf"))
        val h = PbfReader(f).header
        assertEquals(Instant.ofEpochSecond(1_760_000_000), h.replicationTime)
        assertEquals(4936L, h.replicationSequence)
        assertEquals("atlas-test", h.writingProgram)
        assertTrue(h.sortedByTypeThenId)
        assertEquals(4.0, h.bbox!!.west, 1e-9)
        assertEquals(53.0, h.bbox!!.north, 1e-9)

        val bare = TestPbf(nodes).apply { bbox = null; replicationTime = 0; optional = emptyList() }.write(tmp.newFile("bare.osm.pbf"))
        val hb = PbfReader(bare).header
        assertNull(hb.bbox)
        assertNull(hb.replicationTime)
        assertFalse(hb.sortedByTypeThenId)
    }

    @Test
    fun countsAndOrder() {
        val f = TestPbf(nodes, ways, relations).write(tmp.newFile("n.osm.pbf"))
        val c = PbfReader(f).count()
        assertEquals(5, c.nodes)
        assertEquals(2, c.ways)
        assertEquals(2, c.relations)
        assertEquals(3, c.taggedNodes)
        assertEquals(6, c.wayRefs)
        assertEquals(4, c.members)
        assertEquals(9_000_000_001, c.maxNodeId)
        assertTrue(c.sorted)
        assertEquals(1_700_000_000_000, c.lastEdit)
        val unsorted = TestPbf(nodes.reversed(), ways, relations).write(tmp.newFile("u.osm.pbf"))
        assertFalse(PbfReader(unsorted).count().sorted)
        val waysFirst = TestPbf(nodes, ways, relations).apply { order = listOf("ways", "nodes", "relations") }.write(tmp.newFile("v.osm.pbf"))
        assertFalse(PbfReader(waysFirst).count().sorted)
    }

    @Test
    fun manyBlocksInParallelKeepFileOrder() {
        val many = (1L..20_000L).map { TNode(it, 52 + it * 1e-6, 4 + it * 1e-6, if (it % 7 == 0L) mapOf("k" to "v$it") else emptyMap()) }
        val f = TestPbf(many).apply { blockSize = 500 }.write(tmp.newFile("m.osm.pbf"))
        val seq = read(f, threads = 1)
        assertEquals(20_000, seq.size)
        assertEquals(seq, read(f, threads = 6))
        assertTrue(seq[6].endsWith("{k=v7}"))
    }

    @Test
    fun historyFilesAndUnknownFeaturesAreRefused() {
        val f = TestPbf(nodes).apply { required = listOf("OsmSchema-V0.6", "HistoricalInformation") }.write(tmp.newFile("hist.osm.pbf"))
        failsWith(f, "HistoricalInformation")
    }

    @Test
    fun otherCompressionsFailClearly() {
        val f = TestPbf(nodes).apply { compressionOverride = 7 }.write(tmp.newFile("zstd.osm.pbf"))
        failsWith(f, "Zstandard")
    }

    @Test
    fun truncatedAndForeignFilesFail() {
        val f = TestPbf(nodes, ways, relations).write(tmp.newFile("t.osm.pbf"))
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 10) }
        failsWith(f, "truncated")
        val junk = tmp.newFile("junk.osm.pbf").apply { writeBytes(byteArrayOf(0, 0, 0, 5, 1, 2, 3, 4, 5)) }
        failsWith(junk, "")
        val empty = tmp.newFile("empty.osm.pbf")
        failsWith(empty, "empty")
    }

    private fun failsWith(f: File, text: String) {
        try {
            PbfReader(f).read(Recorder())
            fail("expected a failure reading ${f.name}")
        } catch (e: PbfFormatException) {
            assertTrue("${e.message} should mention $text", e.message!!.contains(text))
        } catch (e: java.io.IOException) {
            assertTrue("${e.message} should mention $text", text.isEmpty() || e.message!!.contains(text))
        }
    }
}
