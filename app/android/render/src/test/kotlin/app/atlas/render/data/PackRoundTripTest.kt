package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The pack is lossless: every local extract, packed and read back, equals the extract. */
class PackRoundTripTest {
    private fun spec(id: String, raw: File): PackSpec {
        val src = OverpassJsonFile(raw)
        return PackSpec(id, id, BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), src, listOf("test"))
    }

    private fun check(id: String, raw: File) {
        val t0 = System.nanoTime()
        val bytes = PackWriter.toBytes(spec(id, raw))
        val t1 = System.nanoTime()
        val reader = PackReader.open(bytes)
        val t2 = System.nanoTime()
        // 1. against the extract, element by element, by streaming both
        val a = OverpassJsonFile(raw).elements().iterator()
        val b = reader.elements().iterator()
        var n = 0
        while (a.hasNext()) {
            assertTrue("$id: the pack has fewer elements than the extract ($n)", b.hasNext())
            val x = a.next()
            val y = b.next()
            if (x != y) throw AssertionError("$id: element $n differs\n  extract: ${x.toJson().take(600)}\n  pack:    ${y.toJson().take(600)}")
            n++
        }
        assertTrue("$id: the pack has more elements", !b.hasNext())
        val t3 = System.nanoTime()
        assertEquals(n, reader.elementCount)
        assertEquals(OverpassJsonFile(raw).header, reader.header)
        assertTrue(reader.verify().isEmpty())
        // 2. against Python's own reading of the JSON, through a canonical digest
        val py = Repo.py("digest_elements.py", raw.path).split(" ", limit = 3)
        val mine = Repo.digest(reader.elements())
        assertEquals("$id: element count", py[0].toInt(), mine.first)
        assertEquals("$id: canonical digest", py[1], mine.second)
        val json = raw.length()
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { g -> raw.inputStream().use { it.copyTo(g) } } }.size()
        println("PACKSIZE $id: ${n} elements, json $json, gzip $gz, pack ${bytes.size} (${"%.1f".format(100.0 * bytes.size / json)}% of json, ${"%.1f".format(100.0 * bytes.size / gz)}% of gzip); write ${(t1 - t0) / 1_000_000} ms, open ${(t2 - t1) / 1_000_000} ms, read all ${(t3 - t2) / 1_000_000} ms")
    }

    @Test
    fun amsterdamExtractRoundTrips() {
        assumeTrue(Repo.ams.isFile)
        check("amsterdam", Repo.ams)
    }

    @Test
    fun everyLocalRegionExtractRoundTrips() {
        val names = Repo.localRegions()
        assumeTrue(names.isNotEmpty())
        for (name in names) check(name, File(Repo.regions, "$name/raw.json"))
    }
}
