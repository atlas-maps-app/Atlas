package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.render.data.ElementType
import app.atlas.render.data.OsmElement
import app.atlas.render.data.OverpassJsonFile
import app.atlas.render.data.PackReader
import app.atlas.render.data.PackWriter
import app.atlas.render.data.RegionBuilder
import app.atlas.render.data.RegionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A box cut from a PBF gives the elements Overpass gave for it: the renders' own extracts, written as a PBF and cut again. */
class PbfRegionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))

    /** The extract as a PBF: tagged nodes, every way's nodes from its geometry, member ways that stand alone in no way element. */
    private fun toPbf(elements: List<OsmElement>, file: File): File {
        val nodes = sortedMapOf<Long, TNode>()
        fun put(id: Long, lat: Double, lon: Double, tags: Map<String, String> = emptyMap()) {
            val old = nodes[id]
            if (old == null || (old.tags.isEmpty() && tags.isNotEmpty())) nodes[id] = TNode(id, lat, lon, tags)
        }
        val ways = sortedMapOf<Long, TWay>()
        var synthetic = 1_000_000_000_000L
        for (e in elements) when (e.type) {
            ElementType.NODE -> put(e.id, e.lat, e.lon, e.tags.orEmpty())
            ElementType.WAY -> {
                val g = e.geometry!!
                for (i in e.nodes!!.indices) put(e.nodes!![i], g.lat[i], g.lon[i])
                ways[e.id] = TWay(e.id, e.nodes!!.toList(), e.tags.orEmpty())
            }
            else -> {}
        }
        val relations = ArrayList<TRelation>()
        for (e in elements) if (e.type == ElementType.RELATION) {
            val members = ArrayList<TMember>()
            for (m in e.members!!) {
                when (m.type) {
                    ElementType.WAY -> {
                        if (m.ref !in ways && m.geometry != null) {
                            val refs = ArrayList<Long>()
                            for (i in 0 until m.geometry!!.size) { val id = synthetic++; put(id, m.geometry!!.lat[i], m.geometry!!.lon[i]); refs += id }
                            ways[m.ref] = TWay(m.ref, refs)
                        }
                        members += TMember(1, m.ref, m.role)
                    }
                    ElementType.NODE -> { put(m.ref, m.lat, m.lon); members += TMember(0, m.ref, m.role) }
                    else -> members += TMember(2, m.ref, m.role)
                }
            }
            relations += TRelation(e.id, members, e.tags.orEmpty())
        }
        return TestPbf(nodes.values.toList(), ways.values.toList(), relations).also { it.blockSize = 2000 }.write(file)
    }

    private fun inBox(lat: Double, lon: Double, b: BBox) = lat >= b.south && lat <= b.north && lon >= b.west && lon <= b.east

    private fun check(name: String) {
        val raw = File(repo, "data/regions/$name/raw.json")
        assumeTrue(raw.isFile)
        val box = RegionConfig.bboxOf(Renders.places(repo)[name] as kotlinx.serialization.json.JsonObject)!!
        val all = OverpassJsonFile(raw).elements().toList()
        val pbf = toPbf(all, tmp.newFile("$name.osm.pbf"))
        val t0 = System.nanoTime()
        val cut = PbfRegion.extract(pbf, box, threads = 2)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val crossing = all.filter { e -> e.type == ElementType.WAY && (0 until e.geometry!!.size).none { inBox(e.geometry!!.lat[it], e.geometry!!.lon[it], box) } }.map { it.id }.toSet()
        val expected = all
        val got = cut.elements
        val byKey = got.associateBy { it.type to it.id }
        var wrong = 0
        for (e in expected) {
            val g = byKey[e.type to e.id]
            if (g == null || g != e) {
                if (wrong++ < 3) println("DIFF $name ${e.type} ${e.id}\n  expected ${e.toJson().take(500)}\n  got      ${g?.toJson()?.take(500)}")
            }
        }
        val expectedKeys = expected.map { it.type to it.id }.toSet()
        val extra = got.filter { (it.type to it.id) !in expectedKeys }
        extra.take(3).forEach { println("EXTRA $name ${it.type} ${it.id} ${it.toJson().take(300)}") }
        println("PBFREGION $name: ${all.size} elements in the extract, ${got.size} cut from the pbf in $ms ms, ${crossing.size} ways cross the box with no node in it, $wrong different, ${extra.size} extra")
        assertEquals("$name: elements that differ from Overpass's", 0, wrong)
        assertEquals("$name: elements Overpass did not give", 0, extra.size)
        // Overpass's order: nodes, ways, relations, ascending ids
        assertEquals(got.sortedWith(OsmElement.CANONICAL).map { it.type to it.id }, got.map { it.type to it.id })
    }

    @Test fun sanGimignanoCutsTheSameElements() = check("sangimignano")
    @Test fun nottingHillCutsTheSameElements() = check("nottinghill")
    @Test fun newYorkCutsTheSameElementsWaysCrossingTheBoxIncluded() = check("newyork")

    @Test
    fun aPbfPackReadsBackAndFindsItsBox() {
        val raw = File(repo, "data/regions/nottinghill/raw.json")
        assumeTrue(raw.isFile)
        val box = RegionConfig.bboxOf(Renders.places(repo)["nottinghill"] as kotlinx.serialization.json.JsonObject)!!
        val pbf = toPbf(OverpassJsonFile(raw).elements().toList(), tmp.newFile("n.osm.pbf"))
        val cut = PbfRegion.extract(pbf, box, threads = 1)
        val spec = RegionBuilder.spec("nh", RegionConfig.forBox("Notting Hill", box, "GB"), cut.source())
        val file = tmp.newFile("nh.atlas")
        PackWriter.write(spec, file)
        PackReader.open(file).use { r ->
            assertEquals(cut.elements.size, r.elementCount)
            assertEquals(cut.elements, r.elements().toList())
            assertEquals("Europe/London", r.manifest.timezone)
            assertEquals("gb_terrace", r.manifest.tradition)
            assertTrue(r.query(box).isNotEmpty())
        }
    }

    @Test
    fun aBoxWithNothingInItGivesNoElements() {
        val pbf = TestPbf(listOf(TNode(1, 52.0, 4.0, mapOf("amenity" to "cafe")), TNode(2, 52.001, 4.001)), listOf(TWay(10, listOf(1, 2), mapOf("highway" to "path")))).write(tmp.newFile("e.osm.pbf"))
        val cut = PbfRegion.extract(pbf, BBox(10.0, 10.0, 10.1, 10.1), threads = 1)
        assertTrue(cut.elements.isEmpty())
        assertNull(cut.header["osm3s"]?.let { null })
    }

    @Test
    fun tagsComeInKeyOrderAndCoordinatesAtSevenDecimals() {
        val pbf = TestPbf(listOf(TNode(1, 52.3746123, 4.8841987, linkedMapOf("name" to "A", "amenity" to "cafe", "Zeta" to "1")))).write(tmp.newFile("t.osm.pbf"))
        val e = PbfRegion.extract(pbf, BBox(4.8, 52.3, 4.9, 52.4), threads = 1).elements.single()
        assertEquals(listOf("Zeta", "amenity", "name"), e.tags!!.keys.toList())
        assertEquals(52.3746123, e.lat, 0.0)
        assertEquals(4.8841987, e.lon, 0.0)
    }

    @Test
    fun thePbfAndPackinfoCommandsRunEndToEnd() {
        val pbf = TestPbf(
            listOf(TNode(1, 49.61, 6.13, mapOf("amenity" to "pharmacy", "name" to "Pharmacie du Globe")), TNode(2, 49.62, 6.14), TNode(3, 49.62, 6.15), TNode(4, 49.9, 6.9)),
            listOf(TWay(10, listOf(2, 3), mapOf("highway" to "residential", "name" to "Rue du Fort")), TWay(11, listOf(3, 4), mapOf("highway" to "primary"))),
        ).write(tmp.newFile("lux.osm.pbf"))
        val out = File(tmp.newFolder("out"), "lux.atlas")
        val lines = ArrayList<String>()
        assertEquals(0, run(listOf("pbf", pbf.path, "6.0,49.5,6.3,49.7", "--name", "Luxembourg centre", "--country", "LU", "--out", out.path), lines::add))
        assertTrue(lines.last { it.startsWith(out.path) }.contains("3 elements"))
        lines.clear()
        assertEquals(0, run(listOf("packinfo", out.path), lines::add))
        assertTrue(lines.any { it.contains("Europe/Luxembourg") })
        assertTrue(lines.any { it.contains("all sections intact") })
        PackReader.open(out).use { r ->
            // the pharmacy, both ways (the second leaves the box, whole), no more
            assertEquals(listOf("node 1", "way 10", "way 11"), r.elements().map { it.toString() }.toList().take(3))
            assertEquals(3, r.elements().count())
            assertEquals("luxembourg-centre", r.id)
        }
    }
}
