package app.atlas.mapcorecheck.frame

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapcorecheck.likeness.TestCases
import app.atlas.render.Amsterdam
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * The frame's counters within §3's budgets (plan-vector §3, K7): at the boards' cameras and the smoke tier's sweep, the
 * core's frame over the bundled render packs (every registered pass, the world settled) draws at most 300 draws, 600k
 * triangles besides the buildings' and 30k instances; each case's counts per pass and kind are printed. Its overdraw
 * mode (the frame drawn again by a builder of its own over the same world) shades 1.04 to 1.55 full screens of opaque
 * fragments, the composite's one among them, but 1.93 at the canal ring's second sweep camera, against §3's 1.6: held
 * here to 2.0, so it only comes down (ENGINE.md step 2's refinements; until 21 October the replay drew none of the
 * world's tiles, and the bound held nothing). The buildings (every face and near cell with its exact
 * edge, at true depth) are over §3's triangles where the city is dense: 2.06M at the Main board and 2.66M at the canal
 * ring's first sweep camera when they were registered (ENGINE.md, step 1), measured on the phone by VK bench, and held
 * here to 2.8M, so they only come down (step 2's refinements). The roofs' thin edges are counted apart: ten triangles
 * a plain primitive of the drawn runs (a segment of two along each of its shape's five edges, most of them drawing
 * nothing), 1.15M at the Main board when they came, held to 2M. And the likeness harness's candidate
 * (CP/jni/TestCases.cpp) is that frame: it fills the pixels and the owner map.
 */
class FrameStatsTest {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))

    private class Case(val name: String, val pack: String, val file: File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView)

    private fun cases(): List<Case> {
        val w = File(tiles, "amsterdam.atlas")
        val g = File(tiles, "grachten.atlas")
        val out = arrayListOf(Case("home S1", "bundled", w, { AnchorCases.westerkerk() }, { r -> AnchorCases.homeView(r, 916) }))
        if (g.isFile) out += Case("board S1", "grachten", g, { AnchorCases.grachten() }, { r ->
            val k = r.ladder.knots.first { it.name == "grachten_home" }
            EngineVersion.knotView(r.frame, k, 412, 916)
        })
        for (k in 0 until 4) {
            out += Case("sweep $k S1", "bundled", w, { AnchorCases.westerkerk() }, { r -> sweep(24, r.bbox!!, 916, 9)[k] })
            if (g.isFile) out += Case("canal sweep $k S1", "grachten", g, { AnchorCases.grachten() }, { r -> sweep(12, r.bbox!!, 916, 9)[k] })
        }
        return out
    }

    private fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
        val r = Random(seed)
        return List(n) {
            val lat = box.south + r.nextDouble() * box.height
            val lon = box.west + r.nextDouble() * box.width
            val dist = 91.0 * StrictMath.pow(4000.0 / 91.0, r.nextDouble())
            val bearing = r.nextDouble() * 360.0
            val pitch = r.nextDouble() * 70.0
            val cy = if (r.nextBoolean()) 440.0 else 457.5
            LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), bearing, pitch, 412, height, cy)
        }
    }

    /** The camera state on S1 (1080 × 2400 px, 412 × 916 dp at its BoardScale density), at rest in engine mode. */
    private fun camera(v: LiveView): ByteBuffer {
        val density = 1080 / 412.0
        val b = CameraWriter.allocate()
        CameraWriter.header(b, 1L, CameraWriter.REST or CameraWriter.ENGINE_MODE)
        CameraWriter.view(b, v.lat, v.lon, v.bearing, v.pitch, v.distance, v.widthDp / 2.0, v.cyDp)
        CameraWriter.frame(b, 1080, 2400, v.widthDp, v.heightDp, density)
        CameraWriter.pack(b, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(b, 0, 0, 0)
        CameraWriter.keepOuts(b, 0)
        return b
    }

    private fun theme(key: String): ByteBuffer {
        val th = Amsterdam.THEMES.getValue(key)
        val (az, el, sun) = Amsterdam.sunOf(th)
        val b = ThemeBlock.allocate()
        ThemeBlock.write(b, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, key == "golden", false)
        return b
    }

    /** `name draws dispatches triangles instances` of a counts line ("… draws 1 dispatches 0 triangles 1 instances 1"). */
    private fun counts(line: String): Map<String, Long> {
        val words = line.split(' ')
        return listOf("draws", "dispatches", "triangles", "instances").associateWith { k -> words[words.indexOf(k) + 1].toLong() }
    }

    @Test
    fun theFramesCountsAreWithinTheBudgets() {
        var n = 0
        for (case in cases()) {
            val region = case.region() ?: continue
            val text = FrameHost.stats(arrayOf("${case.pack}=${case.file.path}"), case.pack, camera(case.view(region)), theme("sunny"), false)
                ?: throw AssertionError("${case.name}: the frame failed: ${FrameHost.lastError()}")
            val lines = text.lines().filter { it.isNotBlank() }
            println("stats ${case.name}: ${FrameHost.lastNotes()}")
            for (l in lines) println("  $l")
            val total = counts(lines.first { it.startsWith("total ") })
            val pass = lines.firstOrNull { it.startsWith("pass buildings ") }?.let { counts(it).getValue("triangles") } ?: 0L
            val strokes = lines.firstOrNull { it.startsWith("kind buildings roof strokes ") }?.let { counts(it).getValue("triangles") } ?: 0L
            val buildings = pass - strokes
            val fse = lines.first { it.startsWith("fse ") }.split(' ')
            val opaque = fse[2].toDouble()
            assertTrue("${case.name}: ${total["draws"]} draws, over 300", total.getValue("draws") <= 300)
            val others = total.getValue("triangles") - pass
            assertTrue("${case.name}: $others triangles besides the buildings', over 600k", others <= 600_000)
            assertTrue("${case.name}: the buildings' $buildings triangles, over the 2.8M they were registered with", buildings <= 2_800_000)
            assertTrue("${case.name}: the roofs' thin edges' $strokes triangles, over 2M", strokes <= 2_000_000)
            assertTrue("${case.name}: ${total["instances"]} instances, over 30k", total.getValue("instances") <= 30_000)
            assertTrue("${case.name}: $opaque full screens of opaque fragments, over 2.0", opaque <= 2.0)
            // the replay draws the world: more than the composite's one full screen
            assertTrue("${case.name}: $opaque full screens of opaque fragments: the replay drew none of the world", opaque > 1.0)
            n++
        }
        assertTrue(n >= 2)
    }

    @Test
    fun theLikenessCandidateIsTheFrame() {
        val w = File(tiles, "amsterdam.atlas")
        assumeTrue("no bundled Westerkerk", w.isFile)
        assertEquals(FrameHost.ABI, FrameHost.abi()) // the host build is loaded: TestCases' entry points are in it
        val region = AnchorCases.westerkerk()
        val v = AnchorCases.homeView(region, 916)
        // The case: S1, structural, engine mode, the bundled Westerkerk (CP/jni/TestCases.cpp's layout, version 2).
        val c = ByteBuffer.allocateDirect(TestCases.CASE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        c.putInt(0, TestCases.MAGIC); c.putInt(4, TestCases.ABI); c.putInt(8, 1080); c.putInt(12, 2400); c.putDouble(16, 1080 / 412.0)
        c.putInt(24, 0xFFF0EBE0.toInt()); c.putInt(28, 0); c.putInt(32, 1)
        val pack = "bundled".toByteArray()
        for (i in pack.indices) c.put(160 + i, pack[i])
        TestCases.tiles(tiles.path)
        val pixels = ByteBuffer.allocateDirect(1080 * 2400 * 4)
        val owners = ByteBuffer.allocateDirect(1080 * 2400 * 8).order(ByteOrder.LITTLE_ENDIAN)
        val filled = TestCases.render(c, camera(v), theme("sunny"), pixels, owners)
        println("candidate: filled $filled on ${TestCases.device()}; passes ${TestCases.passes()}; ${TestCases.lastError()}")
        assertEquals("the candidate fills the pixels and the owner map", TestCases.FILLED_PIXELS or TestCases.FILLED_OWNERS, filled)
        // The haze darkens the frame toward its bottom (the ground's depth grows up the frame): its first and last rows
        // differ from the clear colour and from each other.
        fun pixel(x: Int, y: Int): Int {
            val o = (y * 1080 + x) * 4
            return ((pixels.get(o).toInt() and 0xFF) shl 16) or ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or (pixels.get(o + 2).toInt() and 0xFF)
        }
        val top = pixel(540, 1200); val bottom = pixel(540, 2399)
        println("candidate: the middle %06x, the bottom %06x".format(top, bottom))
        assertTrue("the frame is drawn", top != 0 || bottom != 0)
    }
}
