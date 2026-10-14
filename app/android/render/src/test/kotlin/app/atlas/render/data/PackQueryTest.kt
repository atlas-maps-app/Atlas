package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/** The index finds exactly the elements whose box meets the query, in the extract's order, touching few chunks. */
class PackQueryTest {
    /** The box the index keeps: `bounds` when the element has them, else the extent of its positions. */
    private fun boxOf(e: OsmElement): DoubleArray? {
        e.bounds?.let { return doubleArrayOf(it.minLat, it.minLon, it.maxLat, it.maxLon) }
        var a = Double.POSITIVE_INFINITY; var b = a; var c = Double.NEGATIVE_INFINITY; var d = c
        fun add(la: Double, lo: Double) { if (la < a) a = la; if (la > c) c = la; if (lo < b) b = lo; if (lo > d) d = lo }
        if (!e.lat.isNaN()) add(e.lat, e.lon)
        e.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
        e.members?.forEach { m ->
            if (m.hasPosition) add(m.lat, m.lon)
            m.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
        }
        return if (a > c) null else doubleArrayOf(a, b, c, d)
    }

    private fun meets(b: DoubleArray, s: Double, w: Double, n: Double, e: Double) = b[0] <= n && b[2] >= s && b[1] <= e && b[3] >= w

    @Test
    fun queriesMatchABruteForceScan() {
        assumeTrue(Repo.ams.isFile)
        val src = OverpassJsonFile(Repo.ams)
        val all = src.elements().toList()
        val reader = PackReader.open(PackWriter.toBytes(PackSpec("ams", "Amsterdam", BBox(4.0, 52.0, 5.0, 53.0), LatLon(52.37, 4.88), null, null, JsonObject(emptyMap()), src)))
        val boxes = all.map { boxOf(it) }
        // the extract's own extent
        val s0 = boxes.filterNotNull().minOf { it[0] }
        val w0 = boxes.filterNotNull().minOf { it[1] }
        val n0 = boxes.filterNotNull().maxOf { it[2] }
        val e0 = boxes.filterNotNull().maxOf { it[3] }
        val rnd = Random(7)
        var withHits = 0
        repeat(150) { q ->
            val cs = s0 + (n0 - s0) * rnd.nextDouble()
            val cw = w0 + (e0 - w0) * rnd.nextDouble()
            val span = (if (q % 3 == 0) 0.0002 else if (q % 3 == 1) 0.002 else 0.05) * rnd.nextDouble()
            val s = cs - span; val n = cs + span; val w = cw - span; val e = cw + span
            val expected = all.indices.filter { boxes[it]?.let { b -> meets(b, s, w, n, e) } == true }.map { all[it] }
            val got = reader.query(s, w, n, e)
            assertEquals("query $q ($s,$w,$n,$e)", expected.size, got.size)
            for (i in expected.indices) assertTrue("query $q: element $i differs", expected[i] == got[i])
            if (got.isNotEmpty()) withHits++
        }
        assertTrue("some queries found something", withHits > 30)
        // a small view touches only a few chunks
        val fresh = PackReader.open(PackWriter.toBytes(PackSpec("ams", "Amsterdam", BBox(4.0, 52.0, 5.0, 53.0), LatLon(52.37, 4.88), null, null, JsonObject(emptyMap()), src)))
        val near = fresh.around(LatLon(52.3746, 4.8840), 60.0)
        assertTrue(near.isNotEmpty())
        assertTrue("a 60 m view decodes ${fresh.cachedChunks} of ${fresh.chunkCount} chunks", fresh.cachedChunks < fresh.chunkCount / 4)
    }

    @Test
    fun anEmptyQueryAndATouchingEdge() {
        val els = listOf(
            OsmElement(ElementType.NODE, 1, 10.0, 20.0),
            OsmElement(ElementType.NODE, 2, 10.5, 20.5, mapOf("a" to "b")),
            OsmElement(ElementType.WAY, 3, tags = mapOf("highway" to "path"), bounds = Bounds(11.0, 21.0, 12.0, 22.0), nodes = longArrayOf(5, 6), geometry = Points(doubleArrayOf(11.0, 12.0), doubleArrayOf(21.0, 22.0))),
        )
        val reader = PackReader.open(PackWriter.toBytes(PackSpec("t", "T", BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), ListElementSource(els)), chunkSize = 2))
        assertEquals(emptyList<OsmElement>(), reader.query(50.0, 50.0, 51.0, 51.0))
        assertEquals(listOf(3L), reader.query(12.0, 22.0, 13.0, 23.0).map { it.id })   // touching at a corner counts
        assertEquals(listOf(1L, 2L), reader.query(9.0, 19.0, 10.6, 20.6).map { it.id })
        assertEquals(els, reader.elements().toList())
    }
}
