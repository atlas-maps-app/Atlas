package app.atlas.render

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Shade.kt against render/shade.py on the New York extract (data/regions/newyork, local only: on CI this test skips). shade.py's own
 * data (Williamsburg's osm/) is not in the repo; the driver points it at the New York extract, as RealRenderTest does for real.py.
 * Compared: the graph, and plan() for three parks at four moments: the sun, both paths node by node, their length, sun and shade,
 * and every sample segment (by md5 of Python's JSON).
 */
class ShadeTest {
    private val extract = File(Oracle.repo, "data/regions/newyork/raw.json")

    private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `shade plans match shade py`() {
        assumeTrue("data/regions/newyork/raw.json is local only", extract.isFile)
        Oracle.configure()
        val work = File(Oracle.out, "shade").also { it.mkdirs() }
        val out = File(work, "plan.json")
        Oracle.run("shade_oracle.py", "plan", Oracle.repo.path, work.path, out.path)
        val py = Oracle.py(Json.parseToJsonElement(out.readText())) as Map<String, Any?>
        val t0 = System.nanoTime()
        val S = Real.load(File(work, "osm"))
        val g = Shade.loadGraph(File(work, "osm/raw.json"))
        assertEquals(py["nodes"], g.adj.size)
        assertEquals(py["edges"], g.adj.values.sumOf { it.size })
        assertEquals(PyDumps.dumps(py["parks"]), PyDumps.dumps(g.parks.mapValues { it.value.size }))
        assertEquals(PyDumps.dumps(py["adj_order"]), PyDumps.dumps(g.adj.keys.take(50)))
        var n = 0; var lastBit = 0
        for (p in py["plans"] as List<Map<String, Any?>>) {
            val m = (p["moment"] as List<Any?>).map { (it as Number).toInt() }.toIntArray()
            val P = Shade.plan(m, S, g, p["dest"] as String)
            val what = "${p["dest"]} ${m.toList()}"
            // the sun's position runs through libm's trig (macOS: faithful, not correctly rounded): equal to the last bit or so
            assertEquals("$what sun azimuth", (p["az"] as Number).toDouble(), P.az, 1e-9)
            assertEquals("$what sun elevation", (p["el"] as Number).toDouble(), P.el, 1e-9)
            if (PyDumps.dumps(listOf(p["az"], p["el"])) != PyDumps.dumps(listOf(P.az, P.el))) lastBit++
            assertEquals("$what ends", PyDumps.dumps(listOf(p["src"], p["dst"], p["weight"])), PyDumps.dumps(listOf(P.src, P.dst, P.weight)))
            assertEquals("$what fast path", PyDumps.dumps(p["fast_path"]), PyDumps.dumps(P.fastPath))
            assertEquals("$what shady path", PyDumps.dumps(p["shady_path"]), PyDumps.dumps(P.shadyPath))
            for ((k, s) in listOf("fast" to P.fast, "shady" to P.shady)) {
                val e = p[k] as Map<String, Any?>
                assertEquals("$what $k score", PyDumps.dumps(listOf(e["length"], e["sun"], e["shade"], e["n"])), PyDumps.dumps(listOf(s.length, s.sun, s.shade, s.segs.size)))
                assertEquals("$what $k segments", e["segs"], md5(PyDumps.dumps(s.segs.map { listOf(it.first, it.second, it.third) })))
            }
            n++
        }
        println("shade: $n plans equal ($lastBit with the sun's position off in its last bit); Kotlin %.1f s, Python %.1f s".format((System.nanoTime() - t0) / 1e9, (py["seconds"] as Number).toDouble()))
    }
}
