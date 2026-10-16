package app.atlas.render.speed

import app.atlas.render.Amsterdam
import app.atlas.render.Oracle
import app.atlas.render.PyDumps
import app.atlas.render.Regions
import app.atlas.render.data.RawSource
import app.atlas.render.py.PyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** RawSource: the file through PyJson by default; a registered source in its place, for the path it was registered for. */
class RawSourceTest {
    private val ams = File(Oracle.repo, "data/ams/raw.json")

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `without a source the file is read as PyJson reads it`() {
        val want = (PyJson.load(ams) as Map<String, Any?>)["elements"]
        assertEquals(PyDumps.dumps(want), PyDumps.dumps(RawSource.elements(ams)))
        assertFalse(RawSource.registered(ams))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a registered source stands for its path, and the load reads it`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "atlas-rawsource-${ProcessHandle.current().pid()}")
        val path = File(dir, "../${dir.name}/raw.json") // not normalised, and the file is never written
        val all = (PyJson.load(ams) as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
        var calls = 0
        RawSource.register(path) { calls++; (PyJson.load(ams) as Map<String, Any?>)["elements"] as List<Map<String, Any?>> }
        try {
            assertTrue(RawSource.registered(File(dir, "raw.json")))
            assertFalse(File(dir, "raw.json").exists())
            assertEquals(all.size, RawSource.elements(File(dir, "raw.json")).size)
            val S = Amsterdam.load(File(dir, "raw.json"))
            Amsterdam.amsRaw = ams
            val ref = Amsterdam.load(ams)
            assertEquals(2, calls)
            for (k in listOf("buildings", "parts", "roads", "trees", "nodes", "pois", "labels")) {
                assertEquals(k, (ref[k] as List<*>).size, (S[k] as List<*>).size)
            }
        } finally {
            RawSource.unregister(path)
        }
        assertFalse(RawSource.registered(File(dir, "raw.json")))
    }

    /** Regions.load reads its extract twice (Amsterdam.load's scene, then its own pass): both from the source, to the same frame. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `a region's load reads both passes from its source and draws the same frame`() {
        Regions.root = File(Oracle.repo, "data/regions")
        Regions.parkRoot = File(Oracle.repo, "data/park")
        Regions.streetCfgDir = File(Oracle.repo, "cache/render")
        val raw = File(Regions.root, "newyork/raw.json")
        assumeTrue("the region extracts are local only", raw.exists())
        Oracle.configure()
        fun frame(): List<String?> = Regions.load("newyork").let { S -> Regions.render(S, "grey", Regions.cfgFor("newyork")).let { listOf(it.g, it.o, it.o2) } }
        val want = frame()
        var calls = 0
        RawSource.register(raw) { calls++; (PyJson.load(raw) as Map<String, Any?>)["elements"] as List<Map<String, Any?>> }
        try {
            assertEquals(want, frame())
            assertEquals(2, calls)
        } finally {
            RawSource.unregister(raw)
        }
    }
}
