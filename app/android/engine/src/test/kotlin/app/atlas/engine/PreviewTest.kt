package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Renders the real fixtures (OpenFreeMap tiles around the Westerkerk) to PNG through the whole pipeline: cover, scene
 * builds (overzoomed through the tile stack), frame, Java2D. The pictures land in build/…/preview/.
 */
class PreviewTest {
    private val renderer = Renderer(Java2dMeasurer)
    private val sink = Java2dSink(2)

    private fun phone(target: LatLon, zoom: Double, bearing: Double, pitch: Double) =
        Camera(target, zoom, bearing, pitch, width = 824, height = 1830, density = 2f)

    private val westermarkt = LatLon(52.3746, 4.8826)

    private fun render(name: String, camera: Camera, look: Look, extras: Extras? = TestKit.amsterdamTrees, style: FrameStyle = FrameStyle()): Frame {
        val cover = TileCover.cover(camera)
        val scenes = HashMap<TileId, TileScene>()
        var buildMs = 0.0
        for (id in cover) {
            val s = SceneCache.get(id, look, extras) ?: continue
            scenes[id] = s
            buildMs += s.stats.buildMillis
        }
        val plan = TileCover.plan(cover) { scenes[it] }
        var frame = renderer.frame(camera, plan, 0L, style)
        // Warm up, then time a steady frame.
        val times = LongArray(12)
        for (i in times.indices) {
            val t0 = System.nanoTime()
            frame = renderer.frame(camera, plan, 1000L + i * 16, style)
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        val file = File(TestKit.previewDir, "$name.png")
        sink.write(frame, file, look.theme.earth)
        println(
            "$name: ${cover.size} tiles (z ${cover.minOf { it.z }}..${cover.maxOf { it.z }}), scenes built in ${"%.0f".format(buildMs)} ms; " +
                "frame median ${"%.2f".format(times[times.size / 2] / 1e6)} ms; ${frame.stats} -> $file",
        )
        assertTrue("$name drew nothing", frame.stats.triangles > 1000)
        return frame
    }

    @Test
    fun a_main() {
        val f = render("a_main_day", phone(westermarkt, 16.3, -20.0, 50.0), Look.DAY)
        assertTrue(f.stats.objectsDrawn > 500)
    }

    @Test
    fun b_topDown() {
        render("b_top_z15", phone(westermarkt, 15.0, 0.0, 0.0), Look.DAY)
    }

    @Test
    fun c_close() {
        val f = render("c_close_z18", phone(LatLon(52.37455, 4.88395), 18.0, -20.0, 60.0), Look.DAY)
        assertTrue(f.stats.objectsDrawn > 20)
    }

    @Test
    fun d_night() {
        render("d_night", phone(westermarkt, 16.3, -20.0, 50.0), Look.NIGHT)
    }

    @Test
    fun e_chart() {
        render("e_chart", phone(LatLon(52.3795, 4.8930), 15.6, 10.0, 48.0), Look.CHART)
    }

    @Test
    fun f_zoomedOut() {
        render("f_calm_z12", phone(LatLon(52.372, 4.893), 12.0, 0.0, 20.0), Look.DAY)
    }

    @Test
    fun h_horizon() {
        // The steepest view: sky above the horizon, haze over the far ground.
        render("h_horizon_p72", phone(LatLon(52.3722, 4.8851), 17.0, 20.0, 72.0), Look.DAY)
    }

    @Test
    fun i_board() {
        // The Main board's framing: the Westerkerk low in the screen, the canal ring and the IJ beyond it, at phone scale.
        val camera = Camera(LatLon(52.37452, 4.88389), 15.5, 22.0, 54.0, width = 824, height = 1830, density = 2f, offsetY = 320.0)
        render("i_board", camera, Look.DAY)
    }

    @Test
    fun g_gray() {
        render("g_gray", phone(westermarkt, 16.3, -20.0, 50.0), Look.GRAY)
    }
}

/** Scenes shared by the tests in one run (building them is the slow part). */
object SceneCache {
    private val cache = HashMap<Triple<TileId, String, Boolean>, TileScene?>()

    fun get(id: TileId, look: Look, extras: Extras?): TileScene? = synchronized(cache) {
        cache.getOrPut(Triple(id, look.theme.name, extras != null)) { TestKit.scene(id, look, extras) }
    }
}
