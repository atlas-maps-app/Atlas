package app.atlas.render.live

import app.atlas.render.data.HttpPost
import app.atlas.render.data.OverpassClient
import app.atlas.render.py.PyCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor

/**
 * Outside every pack: the live map builds a pack for the view's area from Overpass (here Overpass's recorded answer to
 * the renders' query around Washington Square, `data/regions/newyork/raw.json`, on the owner's machine), with a
 * configuration made from the box alone (no views, no weather, no tradition), and the engine draws it, at a named moment
 * and at the live one.
 */
class BuildHereTest {
    private val repo = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    private val answer = File(repo, "data/regions/newyork/raw.json")

    @Test
    fun `a place no pack covers is built from Overpass and drawn`() {
        assumeTrue("a recorded Overpass answer (local only)", answer.isFile)
        PyCompat.minor = 9
        val dir = Files.createTempDirectory("here").toFile()
        LiveEngine.workDir = File(dir, "work")
        val asked = ArrayList<String>()
        val post = HttpPost { url, form, _ -> asked += url + " " + form.getValue("data").take(40); answer.inputStream() }
        val lib = PackLibrary(File(dir, "packs"), emptyList())
        val regions = LiveRegions(lib, Executor { it.run() }, online = { true }, overpass = OverpassClient(post, pause = {}), onChange = {})
        val lat = 40.7308
        val lon = -73.9973
        val view = LiveView(lat, lon, LiveCamera.zoom(lat, 900.0, 915.0), 30.0, 50.0, 412, 915, 440.0)
        var st = regions.stateFor(view)
        var rounds = 0
        while (st !is LiveRegions.State.Ready && rounds++ < 5) st = regions.stateFor(view)
        val region = (st as LiveRegions.State.Ready).region
        assertTrue(region.id.startsWith("here-"))
        assertEquals(LiveRegion.Kind.REGION, region.kind)
        assertTrue("one Overpass request: $asked", asked.size == 1)
        assertTrue(lib.local(region.id)!!.built)
        val named = LiveEngine.render(region, view, Moment.Named("sunny"))!!
        assertTrue(named.g.length > 10_000 && named.o.length > 10_000)
        val live = LiveEngine.render(region, view, Moment.Live(Instant.parse("2026-10-15T16:00:00Z"), null))!!
        assertEquals("grey", live.moment)
        println("BUILD here: ${region.id}, ${lib.local(region.id)!!.file.length() / 1024} KB pack; render ${named.renderMillis} ms, ${named.g.length + named.o.length} chars")
        dir.deleteRecursively()
    }
}
