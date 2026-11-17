package app.atlas.mapview

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.render.live.RegionFrame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gestures in a region's frame hold the ground under the finger. (Until 5 November this also held the overlays to the
 * pixel where today's map, the renderer's port on the canvas, drew the same ground; the core's camera is the overlays'
 * now, MapCameraSourceTest.)
 */
class EngineCameraTest {
    private val frame = RegionFrame.region(LatLon(52.372, 4.891))

    /** A 1080 × 2400 px phone at 2.625 (411 × 914 dp), the target 120 px above the centre. */
    private val phone = Viewport(1080, 2400, 2.625f, offsetY = -120.0, frame = frame)

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
