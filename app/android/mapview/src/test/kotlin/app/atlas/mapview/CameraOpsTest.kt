package app.atlas.mapview

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The camera maths behind the gestures: whatever the pitch and bearing, the ground stays under the fingers. */
class CameraOpsTest {
    private val phone = Viewport(824, 1830, 2f)
    private val pitched = CameraPosition(Fixtures.westermarkt, 16.3, -20.0, 50.0)
    private val flat = CameraPosition(Fixtures.westermarkt, 15.0)

    private fun near(a: LatLon?, b: LatLon?, metres: Double) {
        assertNotNull(a); assertNotNull(b)
        val d = Geo.distance(a!!, b!!)
        assertTrue("off by $d m", d < metres)
    }

    @Test
    fun panKeepsTheGroundUnderTheFinger() {
        for (p in listOf(flat, pitched, pitched.copy(bearing = 133.0, pitch = 68.0))) {
            val before = CameraOps.groundAt(p, phone, 300f, 1200f)
            val moved = CameraOps.pan(p, phone, 300f, 1200f, 520f, 900f)
            near(before, CameraOps.groundAt(moved, phone, 520f, 900f), 0.05)
            assertEquals(p.zoom, moved.zoom, 0.0)
            assertEquals(p.pitch, moved.pitch, 0.0)
        }
    }

    @Test
    fun panAboveTheHorizonStillMoves() {
        // Content padding moves the principal point down, which brings the horizon into view.
        val padded = phone.copy(offsetY = 400.0)
        val steep = pitched.copy(pitch = 70.0)
        assertNull(CameraOps.groundAt(steep, padded, 400f, 5f))
        val moved = CameraOps.pan(steep, padded, 400f, 5f, 400f, 205f)
        assertTrue(Geo.distance(moved.target, steep.target) > 1.0)
    }

    @Test
    fun theHazeNearTheHorizonDoesNotThrowTheCameraAcrossTheCountry() {
        // Steep, with padding that brings the horizon into view: just under it the ground lies hundreds of km away.
        val padded = phone.copy(offsetY = 400.0)
        val steep = pitched.copy(pitch = 70.0)
        val cam = CameraOps.camera(steep, padded)
        val y = (cam.horizonY + 3).toFloat()
        assertTrue("the band is on screen", y > 0f && y < cam.farY)
        val far = CameraOps.groundAt(steep, padded, 400f, y)!!
        assertTrue(Geo.distance(far, steep.target) > 50_000.0)
        // A drag, a double tap and a pinch there move the camera by about what the finger did, not to the horizon.
        val panned = CameraOps.pan(steep, padded, 400f, y, 400f, y + 60f)
        val moved = Geo.distance(panned.target, steep.target)
        assertTrue("panned $moved m", moved > 1.0 && moved < 2_000.0)
        val zoomed = CameraOps.zoomAround(steep, padded, 400f, y, 1.0)
        assertEquals(steep.zoom + 1.0, zoomed.zoom, 1e-9)
        assertTrue("zoomed ${Geo.distance(zoomed.target, steep.target)} m", Geo.distance(zoomed.target, steep.target) < 2_000.0)
        val pinched = CameraOps.transform(steep, padded, 400f, y, 410f, y + 20f, 1.2, 5.0)
        assertTrue("pinched ${Geo.distance(pinched.target, steep.target)} m", Geo.distance(pinched.target, steep.target) < 2_000.0)
        // Below the haze the ground still stays under the finger.
        val yGround = (cam.farY + 40).toFloat()
        val before = CameraOps.groundAt(steep, padded, 400f, yGround)
        near(before, CameraOps.groundAt(CameraOps.pan(steep, padded, 400f, yGround, 420f, yGround + 80f), padded, 420f, yGround + 80f), 0.5)
    }

    @Test
    fun overlaysProjectExactlyAsTheEngineDoes() {
        for (p in listOf(flat, pitched, pitched.copy(bearing = 211.0, pitch = 66.0))) {
            val cam = CameraOps.camera(p, phone.copy(offsetY = 120.0))
            val g = GroundView(cam)
            val out = FloatArray(2)
            for (k in 0 until 50) {
                val q = LatLon(p.target.lat + (k % 7 - 3) * 0.0011, p.target.lon + (k / 7 - 3) * 0.0017)
                val s = cam.project(q)
                assertEquals(s != null, g.project(q, out) || (s != null && s.depth < g.nearClip))
                if (s != null && s.depth >= g.nearClip) {
                    assertEquals(s.x, out[0], 0.01f)
                    assertEquals(s.y, out[1], 0.01f)
                    assertEquals(cam.focal / s.depth, g.pixelsPerMetre(s.depth.toDouble()), 1e-6)
                }
            }
        }
    }

    @Test
    fun zoomAroundKeepsTheFocus() {
        val p = pitched
        val before = CameraOps.groundAt(p, phone, 600f, 1500f)
        val z = CameraOps.zoomAround(p, phone, 600f, 1500f, 0.8)
        assertEquals(p.zoom + 0.8, z.zoom, 1e-9)
        near(before, CameraOps.groundAt(z, phone, 600f, 1500f), 0.05)
    }

    @Test
    fun rotateTurnsTheMapClockwiseAroundTheFocus() {
        val p = flat.copy(bearing = 10.0)
        val before = CameraOps.groundAt(p, phone, 200f, 400f)
        val r = CameraOps.rotateAround(p, phone, 200f, 400f, 30.0)
        // The map turns clockwise on screen: the top of the screen now faces 30° further anticlockwise.
        assertEquals(340.0, r.bearing, 1e-9)
        near(before, CameraOps.groundAt(r, phone, 200f, 400f), 0.05)
    }

    @Test
    fun transformMovesScalesAndTurns() {
        val p = pitched
        val before = CameraOps.groundAt(p, phone, 400f, 1000f)
        val t = CameraOps.transform(p, phone, 400f, 1000f, 450f, 1100f, 2.0, 15.0)
        assertEquals(p.zoom + 1.0, t.zoom, 1e-9)
        assertEquals(CameraOps.clamp(p.copy(bearing = p.bearing - 15.0)).bearing, t.bearing, 1e-9)
        near(before, CameraOps.groundAt(t, phone, 450f, 1100f), 0.05)
    }

    @Test
    fun tiltStaysWithinThePitchTheZoomAllows() {
        assertEquals(70.0, CameraOps.tilt(pitched, 40.0).pitch, 0.0)
        assertEquals(0.0, CameraOps.tilt(pitched, -90.0).pitch, 0.0)
        assertEquals(30.0, CameraOps.tilt(CameraPosition(Fixtures.westermarkt, 2.0), 60.0).pitch, 0.0)
        assertEquals(46.0, CameraOps.maxPitch(5.0), 1e-9)
    }

    @Test
    fun clampWrapsAndLimits() {
        val c = CameraOps.clamp(CameraPosition(LatLon(89.0, 190.0), 25.0, -30.0, 80.0))
        assertEquals(Fixtures.westermarkt.let { 85.05112878 }, c.target.lat, 1e-6)
        assertEquals(-170.0, c.target.lon, 1e-9)
        assertEquals(CameraOps.MAX_ZOOM, c.zoom, 0.0)
        assertEquals(330.0, c.bearing, 1e-9)
        assertEquals(CameraOps.MAX_PITCH, c.pitch, 0.0)
    }

    @Test
    fun fitShowsTheWholeBoxInsideThePadding() {
        val box = BBox(4.875, 52.368, 4.895, 52.380)
        val pad = floatArrayOf(40f, 300f, 40f, 400f)
        for (p in listOf(flat, pitched)) {
            val f = CameraOps.fit(p, phone, box, pad)
            val cam = CameraOps.camera(f, phone)
            for (q in listOf(LatLon(box.north, box.west), LatLon(box.north, box.east), LatLon(box.south, box.east), LatLon(box.south, box.west))) {
                val s = cam.project(q)!!
                assertTrue("$q at $s", s.x >= pad[0] - 2 && s.x <= phone.width - pad[2] + 2 && s.y >= pad[1] - 2 && s.y <= phone.height - pad[3] + 2)
            }
            if (p.pitch == 0.0) {
                // Looking down, the box fills the padded width or height and sits in its middle.
                val c = cam.project(box.center)!!
                assertEquals(40f + (824f - 80f) / 2, c.x, 3f)
                assertEquals(300f + (1830f - 700f) / 2, c.y, 3f)
            }
        }
    }

    @Test
    fun aPitchedFitFillsThePaddedRegion() {
        val box = BBox(4.875, 52.368, 4.895, 52.380)
        val pad = floatArrayOf(40f, 300f, 40f, 400f)
        val f = CameraOps.fit(pitched, phone, box, pad)
        val cam = CameraOps.camera(f, phone)
        val s = listOf(LatLon(box.north, box.west), LatLon(box.north, box.east), LatLon(box.south, box.east), LatLon(box.south, box.west)).map { cam.project(it)!! }
        val w = s.maxOf { it.x } - s.minOf { it.x }
        val h = s.maxOf { it.y } - s.minOf { it.y }
        // Tight: one side of the trapezoid's box meets the padding (within a bisection step's worth).
        assertTrue("w $w h $h", w > (824 - 80) * 0.97 || h > (1830 - 700) * 0.97)
        assertEquals(40f + (824f - 80f) / 2, (s.maxOf { it.x } + s.minOf { it.x }) / 2, 4f)
        assertEquals(300f + (1830f - 700f) / 2, (s.maxOf { it.y } + s.minOf { it.y }) / 2, 4f)
    }

    @Test
    fun lerpTurnsTheShortWay() {
        val a = flat.copy(bearing = 350.0)
        val b = flat.copy(bearing = 10.0, zoom = 17.0)
        val m = CameraOps.lerp(a, b, 0.5)
        assertEquals(0.0, abs(CameraOps.turn(0.0, m.bearing)), 1e-9)
        assertEquals(16.0, m.zoom, 1e-9)
        assertEquals(-20.0, CameraOps.turn(10.0, 350.0), 1e-9)
    }

    @Test
    fun visibleBoundsHoldTheTarget() {
        val b = CameraOps.visibleBounds(pitched, phone)!!
        assertTrue(Fixtures.westermarkt in b)
        assertNull(CameraOps.visibleBounds(pitched, Viewport(0, 0, 2f)))
    }
}
