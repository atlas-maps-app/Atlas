package app.atlas.render.py

import app.atlas.render.Amsterdam
import app.atlas.render.Oracle
import app.atlas.render.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What a loaded scene keeps on the heap, and how long its renders take, for the canal ring's Main board (grachten_home, local
 * only: the extract is not in git) and the Westerkerk (data/ams). The retained bytes are the heap after a full collection with the
 * scene held, less the heap before the load. Times are the best of a few renders after one to warm up.
 *
 * The Main board is also loaded and rendered in a JVM of its own with a phone's heap (384 MB, the plan's budget of a 512 MB large
 * heap), and its layers must still be the oracle's (cache/oracle/regions/grachten_home.json, written by RegionsTest).
 */
class SceneFootprintTest {
    private val repo = Oracle.repo
    private val grachten = File(repo, "data/regions/grachten/raw.json")

    private fun usedAfterGc(): Long {
        val mx = ManagementFactory.getMemoryMXBean()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return mx.heapMemoryUsage.used
    }

    private fun bestOf(n: Int, f: () -> Unit): Double {
        f()
        var best = Double.MAX_VALUE
        repeat(n) { val t0 = System.nanoTime(); f(); best = minOf(best, (System.nanoTime() - t0) / 1e6) }
        return best
    }

    /** The oracle's md5 of grachten_home's sunny g, o and o2, when RegionsTest has run the Python here. */
    @Suppress("UNCHECKED_CAST")
    private fun oracleSunny(): List<String>? {
        val f = File(Oracle.out, "regions/grachten_home.json").takeIf { it.isFile } ?: return null
        val files = (PyJson.load(f) as Map<String, Any?>)["files"] as Map<String, Map<String, Any?>>
        return listOf("g", "o", "o2").map { l -> files.values.first { it["moment"] == "sunny" && it["layer"] == l }["md5"] as String }
    }

    @Test
    fun `the Main board's scene fits in 135 MB`() {
        assumeTrue("the grachten extract is local only", grachten.exists())
        Fork.configure()
        val before = usedAfterGc()
        val t0 = System.nanoTime()
        var S: Dict? = Regions.load("grachten_home")
        val load = (System.nanoTime() - t0) / 1e6
        val kept = usedAfterGc() - before
        val cfg = Regions.cfgFor("grachten_home")
        val times = listOf("sunny", "grey", "wet", "eve").associateWith { m -> bestOf(3) { Regions.render(S!!, m, cfg) } }
        val sums = Fork.md5s(Regions.render(S!!, "sunny", cfg))
        S = null
        println(
            "scene footprint grachten_home: load %.0f ms, kept %.1f MB; render %s; sunny md5 %s".format(
                load, kept / 1048576.0, times.entries.joinToString(", ") { "%s %.0f ms".format(it.key, it.value) }, sums,
            ),
        )
        oracleSunny()?.let { assertEquals("grachten_home sunny g, o, o2 against the oracle", it, sums) }
        assertTrue("grachten_home keeps ${kept / 1048576} MB", kept <= 135L * 1048576)
    }

    @Test
    fun `the Main board loads and renders in a phone's 384 MB heap`() {
        assumeTrue("the grachten extract is local only", grachten.exists())
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cmd = listOf(java, "-Xmx384m", "-Datlas.repo=${repo.path}", "-cp", System.getProperty("java.class.path"), Fork::class.java.name)
        val out = File.createTempFile("footprint", ".out")
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out).also { it.environment()["PYTHONHASHSEED"] = "0" }.start()
            check(p.waitFor(15, TimeUnit.MINUTES)) { p.destroyForcibly(); "the 384 MB fork timed out" }
            val text = out.readText()
            println(text.trim())
            assertEquals("the 384 MB fork failed:\n$text", 0, p.exitValue())
            val sums = Regex("md5 \\[([0-9a-f, ]+)]").find(text)!!.groupValues[1].split(", ")
            oracleSunny()?.let { assertEquals("grachten_home sunny in 384 MB against the oracle", it, sums) }
        } finally {
            out.delete()
        }
    }

    @Test
    fun `the Westerkerk's scene and render`() {
        Oracle.configure()
        Amsterdam.amsRaw = File(repo, "data/ams/raw.json")
        Amsterdam.TONE = false
        Amsterdam.resetThemes()
        Regions.resetPlace()
        val cfg: Map<String, Any?> = linkedMapOf(
            "W" to 412, "H" to 915, "target" to listOf(-10, 0, -30), "yaw" to 52, "pitch" to 38, "dist" to 430, "fov" to 36, "cy" to 440, "radius" to 380,
        )
        val before = usedAfterGc()
        val t0 = System.nanoTime()
        val S = Amsterdam.load()
        val load = (System.nanoTime() - t0) / 1e6
        val kept = usedAfterGc() - before
        val times = listOf("sunny", "grey", "wet", "night").associateWith { m -> bestOf(6) { Amsterdam.render(S, m, cfg, null, layer = "split") } }
        println(
            "scene footprint westerkerk: load %.0f ms, kept %.1f MB; render (split) %s".format(
                load, kept / 1048576.0, times.entries.joinToString(", ") { "%s %.0f ms".format(it.key, it.value) },
            ),
        )
        assertTrue("the Westerkerk keeps ${kept / 1048576} MB", kept <= 12L * 1048576)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the Westerkerk's tags are compact dicts sharing one copy of each string`() {
        Oracle.configure()
        Amsterdam.amsRaw = File(repo, "data/ams/raw.json")
        Amsterdam.resetThemes()
        Regions.resetPlace()
        val S = Amsterdam.load()
        val canon = HashMap<String, String>()
        var dicts = 0; var compact = 0; var strings = 0
        fun one(s: String) {
            strings++
            val first = canon.getOrPut(s) { s }
            assertTrue("two copies of '$s'", first === s)
            if (s.length <= 24) assertTrue("'$s' is the JVM's interned copy", s === s.intern())
        }
        for (list in listOf("buildings", "parts", "roads", "areas", "nodes")) {
            for (o in S[list] as List<Map<String, Any?>>) {
                val t = o["tags"] as Map<String, Any?>
                dicts++
                if (t is CompactDict<*, *>) compact++
                for ((k, v) in t) { one(k); if (v is String) one(v) }
            }
        }
        val records = (S["buildings"] as List<Any?>) + (S["nodes"] as List<Any?>)
        assertTrue("the records are compact dicts", records.all { it is CompactDict<*, *> })
        assertEquals("every tags dict is compact", dicts, compact)
        assertSame("tag keys meet the code's literals", "building", (S["buildings"] as List<Map<String, Any?>>).flatMap { (it["tags"] as Map<String, Any?>).keys }.first { it == "building" })
        println("westerkerk tags: $dicts compact dicts, $strings strings in ${canon.size} copies")
    }

    /** The 384 MB run: loads grachten_home, renders it sunny, and prints its md5s and the heap's peak. */
    object Fork {
        fun configure() {
            Oracle.configure()
            Regions.root = File(Oracle.repo, "data/regions")
            Regions.parkRoot = File(Oracle.repo, "data/park")
            Regions.streetCfgDir = File(Oracle.repo, "cache/render")
        }

        fun md5s(r: Regions.Layers): List<String> = listOf(r.g, r.o, r.o2).map { s ->
            if (s == null) "-" else MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            configure()
            val t0 = System.nanoTime()
            val S = Regions.load("grachten_home")
            val t1 = System.nanoTime()
            val r = Regions.render(S, "sunny", Regions.cfgFor("grachten_home"))
            val t2 = System.nanoTime()
            val peak = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used }
            println(
                "grachten_home in %d MB of heap: load %.0f ms, render %.0f ms, pools' peaks %.0f MB; md5 %s".format(
                    Runtime.getRuntime().maxMemory() / 1048576, (t1 - t0) / 1e6, (t2 - t1) / 1e6, peak / 1048576.0, md5s(r),
                ),
            )
        }
    }
}
