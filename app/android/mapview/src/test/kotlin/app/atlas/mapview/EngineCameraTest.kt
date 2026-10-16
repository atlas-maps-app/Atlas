package app.atlas.mapview

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.render.Regions
import app.atlas.render.live.LiveView
import app.atlas.render.live.RegionFrame
import app.atlas.render.live.ViewLadder
import app.atlas.render.py.Dict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's camera is the engine's: overlays projected by the map land on the pixel where the engine drew the same
 * ground, and gestures in a region's frame hold the ground under the finger.
 */
class EngineCameraTest {
    private val frame = RegionFrame.region(LatLon(52.372, 4.891))

    @Suppress("UNCHECKED_CAST")
    private val ladder = ViewLadder("grachten", Regions.PLACES.getValue("grachten")["views"] as Map<String, Dict>)

    /** A 1080 × 2400 px phone at 2.625 (411 × 914 dp), the target 120 px above the centre. */
    private val phone = Viewport(1080, 2400, 2.625f, offsetY = -120.0, frame = frame)

    private fun liveView(c: CameraPosition, v: Viewport): LiveView {
        val wDp = Math.round(v.width / v.density)
        val hDp = Math.round(v.height / v.density)
        return LiveView(c.target.lat, c.target.lon, c.zoom, c.bearing, c.pitch, wDp, hDp, (v.height / 2.0 + v.offsetY) * hDp / v.height)
    }

    @Test
    fun `overlays land where the engine drew the ground`() {
        for ((cam, pts) in listOf(
            HomeView.camera to listOf(LatLon(52.37452, 4.88377), LatLon(52.3790, 4.9001), LatLon(52.3700, 4.8820)),
            CameraPosition(LatLon(52.3735, 4.8930), 17.2, 300.0, 35.0) to listOf(LatLon(52.3735, 4.8930), LatLon(52.3741, 4.8941)),
            CameraPosition(LatLon(52.3660, 4.9100), 14.5, 10.0, 0.0) to listOf(LatLon(52.3700, 4.9000), LatLon(52.3620, 4.9200)),
        )) {
            val map = CameraOps.camera(cam, phone)
            val lv = liveView(cam, phone)
            val engine = EngineFrames.cameraOf(ladder.place(frame, lv).cfg)
            // the engine's frame is lv.widthDp × lv.heightDp, stretched over the screen's pixels
            val sx = phone.width / lv.widthDp.toDouble()
            val sy = phone.height / lv.heightDp.toDouble()
            for (p in pts) {
                val a = map.project(p) ?: continue
                val b = engine.proj(frame.x(p.lat, p.lon), 0.0, frame.z(p.lat, p.lon)) ?: continue
                assertEquals("x of $p under $cam", b[0] * sx, a.x.toDouble(), 1.0)
                assertEquals("y of $p under $cam", b[1] * sy, a.y.toDouble(), 1.0)
            }
        }
    }

    @Test
    fun `a pan in the region's frame holds the ground under the finger`() {
        val cam = CameraPosition(LatLon(52.3746, 4.8840), 16.5, 52.0, 50.0)
        val ground = CameraOps.groundAt(cam, phone, 400f, 1500f)!!
        val moved = CameraOps.pan(cam, phone, 400f, 1500f, 700f, 1000f)
        val now = CameraOps.groundAt(moved, phone, 700f, 1000f)!!
        assertTrue("off by ${Geo.distance(ground, now)} m", Geo.distance(ground, now) < 0.05)
        // and a turn about a point keeps that point
        val turned = CameraOps.rotateAround(cam, phone, 540f, 1200f, 30.0)
        val a = CameraOps.groundAt(cam, phone, 540f, 1200f)!!
        val b = CameraOps.groundAt(turned, phone, 540f, 1200f)!!
        assertTrue(Geo.distance(a, b) < 0.05)
    }
}
