package app.atlas.render.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.atlas.geo.BBox
import app.atlas.geo.LatLon

/** Height grids and the small documents round-trip exactly, against Python's reading of the same files. */
class PackDemTest {
    private fun digest(d: Dem): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (r in 0 until d.height) {
            for (c in 0 until d.width) {
                val v = d.at(r, c)
                md.update((if (v.isNaN()) "n," else "${Math.round(v * 1e6)},").toByteArray())
            }
            md.update('\n'.code.toByte())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun everyLocalGridRoundTrips() {
        val files = Repo.regions.listFiles().orEmpty().flatMap { r -> r.listFiles { f -> f.name.matches(Regex("dem5?\\.json")) }.orEmpty().toList() }
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val dem = Dem.read(f)
            val py = Repo.py("digest_dem.py", f.path).split(" ")
            assertEquals("${f.parentFile.name}/${f.name} width", py[0].toInt(), dem.width)
            assertEquals(py[1].toInt(), dem.height)
            assertEquals(py[2].toInt(), dem.z.count { it.isNaN() })
            assertEquals("${f.name} grid digest", py[3], digest(dem))
            val src = ListElementSource(emptyList())
            val bytes = PackWriter.toBytes(PackSpec("t", "T", BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), src, dems = mapOf("dem" to dem)))
            val back = PackReader.open(bytes).dem("dem")!!
            assertEquals(dem, back)
            println("DEMSIZE ${f.parentFile.name}/${f.name}: ${dem.width}x${dem.height}, json ${f.length()}, pack ${bytes.size}")
        }
    }

    @Test
    fun auxDocumentsKeepTheirNumbersAsWritten() {
        val doc = kotlinx.serialization.json.Json.parseToJsonElement("""{"hourly":{"time":["2026-10-01T00:00"],"temperature_2m":[12.0,11.50,-0.0],"is_day":[0,1]}}""")
        val bytes = PackWriter.toBytes(PackSpec("t", "T", BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), ListElementSource(emptyList()), aux = mapOf("wx" to doc)))
        val r = PackReader.open(bytes)
        assertEquals(doc, r.aux("wx"))
        assertEquals(doc.toString(), r.aux("wx").toString())
        assertEquals(null, r.aux("sea"))
        assertEquals(null, r.dem("dem"))
        assertEquals(listOf("wx"), r.auxNames)
    }

    @Test
    fun aGridWithoutADecimalScaleKeepsItsDoubles() {
        val z = doubleArrayOf(0.1 + 0.2, Double.NaN, 1.0 / 3, -0.0, 5.0, 5.000000000001)
        val dem = Dem(JsonObject(mapOf("crs" to JsonPrimitive("wgs84"), "W" to JsonPrimitive(3), "H" to JsonPrimitive(2))), 3, 2, z)
        val bytes = PackWriter.toBytes(PackSpec("t", "T", BBox(0.0, 0.0, 1.0, 1.0), LatLon(0.5, 0.5), null, null, JsonObject(emptyMap()), ListElementSource(emptyList()), dems = mapOf("d" to dem)))
        val back = PackReader.open(bytes).dem("d")!!
        assertEquals(dem, back)
        assertTrue(back.at(0, 0) == 0.1 + 0.2)
    }
}
