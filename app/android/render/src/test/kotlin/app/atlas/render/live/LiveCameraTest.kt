package app.atlas.render.live

import app.atlas.geo.LatLon
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.V3
import app.atlas.render.py.Dict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map camera in the engine's terms: zoom and distance, the frame, the snap to a region's views, the ladder between them. */
class LiveCameraTest {
    @Suppress("UNCHECKED_CAST")
    private val grachten = Regions.PLACES.getValue("grachten")["views"] as Map<String, Dict>
    private val ladder = ViewLadder("grachten", grachten)
    private val frame = RegionFrame.region(LatLon(52.372, 4.891))

    private fun at(x: Double, z: Double, dist: Double, bearing: Double, pitch: Double, cy: Double = 440.0): LiveView {
        val lat = frame.lat(x, z)
        val lon = frame.lon(x, z)
        return LiveView(lat, lon, LiveCamera.zoom(lat, dist, 915.0), bearing, pitch, 412, 915, cy)
    }

    private fun board() = at(-177.0, -521.0, 2300.0, 52.0, 46.0)

    private fun num(v: Any?) = (v as Number).toDouble()

    @Test
    fun `zoom and distance are inverses, and the board's zoom is about 15_84`() {
        for (lat in listOf(0.0, 40.73, 52.37, 60.4)) for (dist in listOf(150.0, 430.0, 2300.0, 40_000.0)) {
            val z = LiveCamera.zoom(lat, dist, 915.0)
            assertEquals(dist, LiveCamera.distance(lat, z, 915.0), dist * 1e-12)
        }
        assertEquals(15.84, board().zoom, 0.01)
        assertEquals(2300.0, board().distance, 1e-6)
    }

    @Test
    fun `the region frame is amsterdam py's XZ`() {
        Regions.resetPlace()
        Amsterdam.LAT0 = 52.372; Amsterdam.LON0 = 4.891; Amsterdam.KX = frame.kx
        for ((lat, lon) in listOf(52.37452 to 4.88377, 52.379 to 4.900, 52.36 to 4.872)) {
            val p = Amsterdam.XZ(lat, lon)
            assertEquals(p.x, frame.x(lat, lon), 0.0)
            assertEquals(p.y, frame.z(lat, lon), 0.0)
            assertEquals(lat, frame.lat(p.x, p.y), 1e-12)
            assertEquals(lon, frame.lon(p.x, p.y), 1e-12)
        }
        Regions.resetPlace()
        assertEquals(Amsterdam.KX, RegionFrame.living().kx, 0.0)
    }

    @Test
    fun `the board's camera is the grachten home view exactly, with cfg_for's settings`() {
        val placed = ladder.place(frame, board())
        assertEquals("grachten_home", placed.view)
        val want = Regions.cfgFor("grachten_home")
        assertEquals(want.toString(), placed.cfg.toString())
        assertEquals(want.keys.toList(), placed.cfg.keys.toList())
        // and a different screen keeps the view but takes its own size
        val wide = ladder.place(frame, board().copy(widthDp = 600))
        assertEquals("grachten_home", wide.view)
        assertEquals(600, wide.cfg["W"])
    }

    @Test
    fun `the engine's camera puts the target at the principal point and looks the map's way`() {
        val v = at(120.0, -80.0, 900.0, 133.0, 30.0, cy = 400.0)
        val cfg = ladder.place(frame, v).cfg
        val cam = Amsterdam.cameraOf(cfg)
        val p = cam.proj(V3(120.0, 0.0, -80.0))!!
        assertEquals(206.0, p[0], 1e-6)
        assertEquals(400.0, p[1], 1e-6)
        // a point to the east of the target, with the map turned 133°, sits up and to the left on screen
        val east = cam.proj(V3(220.0, 0.0, -80.0))!!
        assertTrue(east[0] < 206.0 && east[1] < 400.0)
        assertEquals(60.0, num(cfg["pitch"]), 1e-12)
        assertEquals(133.0, num(cfg["yaw"]), 1e-12)
    }

    @Test
    fun `a camera a little off a view does not snap, and its settings stay those of the view`() {
        val placed = ladder.place(frame, board().copy(bearing = 52.5))
        assertNull(placed.view)
        assertEquals(1500.0, num(placed.cfg["radius"]), 1e-9)
        assertEquals(2600.0, num(placed.cfg["ground_r"]), 1e-9)
        assertEquals(2300 * 0.85, num(placed.cfg["haze0"]), 1e-6)
        assertEquals(2300 * 2.2, num(placed.cfg["haze1"]), 1e-6)
        assertEquals(44.0, num(placed.cfg["pitch"]), 1e-9)
        assertEquals(true, placed.cfg["no_fin"])
        assertEquals(1900000, placed.cfg["obj_split"])
    }

    @Test
    fun `between views the settings follow the distance smoothly, and scale beyond the ends`() {
        // the eat view (1600 m: radius 1400, ground 2400) and the home view (2300 m: 1500, 2600)
        val mid = ladder.place(frame, at(0.0, 0.0, 1950.0, 10.0, 45.0)).cfg
        assertEquals(1450.0, num(mid["radius"]), 1e-9)
        assertEquals(2500.0, num(mid["ground_r"]), 1e-9)
        var last = 0.0
        for (d in 1000..6000 step 50) {
            val r = num(ladder.place(frame, at(0.0, 0.0, d.toDouble(), 10.0, 45.0)).cfg["radius"])
            assertTrue("radius grows with the distance ($d m)", r >= last)
            last = r
        }
        // nearer than the nearest view: in proportion
        val close = ladder.place(frame, at(0.0, 0.0, 800.0, 10.0, 45.0)).cfg
        assertEquals(1400.0 * 800 / 1600, num(close["radius"]), 1e-9)
        assertEquals(390.0, num(close["win_d"]), 0.0)
    }

    @Test
    fun `the living map's ladder keeps amsterdam py's camera and defaults`() {
        val living = ViewLadder("amsterdam", mapOf("" to ViewLadder.livingView()), living = true)
        val f = RegionFrame.living()
        val lat = f.lat(-10.0, -30.0)
        val lon = f.lon(-10.0, -30.0)
        val exact = living.place(f, LiveView(lat, lon, LiveCamera.zoom(lat, 430.0, 915.0), 52.0, 52.0, 412, 915, 440.0))
        assertEquals("amsterdam", exact.view)
        assertEquals(ViewLadder.livingView().toString(), exact.cfg.toString())
        val far = living.place(f, LiveView(lat, lon, LiveCamera.zoom(lat, 860.0, 915.0), 52.0, 52.0, 412, 915, 440.0)).cfg
        assertEquals(760.0, num(far["radius"]), 1e-9)
        assertEquals(760.0, num(far["haze0"]), 1e-9)
        assertTrue("no_fin" !in far)
    }
}
