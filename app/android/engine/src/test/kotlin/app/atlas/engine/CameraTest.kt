package app.atlas.engine

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTest {
    private val ams = LatLon(52.3746, 4.8826)

    @Test
    fun targetProjectsToThePrincipalPoint() {
        for (pitch in listOf(0.0, 30.0, 50.0, 72.0)) for (bearing in listOf(0.0, -20.0, 135.0)) {
            val c = Camera(ams, 16.3, bearing, pitch, 824, 1830, 2f, offsetY = 120.0)
            val p = c.project(ams)!!
            assertEquals(c.principalX, p.x.toDouble(), 1e-3)
            assertEquals(c.principalY, p.y.toDouble(), 1e-3)
            assertEquals(c.distance, p.depth.toDouble(), 1e-3)
        }
    }

    @Test
    fun projectAndUnprojectRoundTrip() {
        for (pitch in listOf(0.0, 25.0, 50.0, 65.0)) for (bearing in listOf(0.0, -20.0, 90.0, 211.0)) {
            val c = Camera(ams, 16.0, bearing, pitch, 800, 1600, 2f)
            for (sx in listOf(10.0, 400.0, 790.0)) for (sy in listOf(c.height - 5.0, 900.0, maxOf(c.horizonY + 40, 30.0))) {
                val g = c.unproject(sx, sy) ?: continue
                val back = c.project(g)!!
                assertEquals("x at pitch $pitch bearing $bearing", sx, back.x.toDouble(), 0.05)
                assertEquals("y at pitch $pitch bearing $bearing", sy, back.y.toDouble(), 0.05)
            }
        }
    }

    @Test
    fun straightDownTheGroundScaleIsTheMercatorScale() {
        val c = Camera(ams, 15.0, 0.0, 0.0, 1000, 1000, 2f)
        val a = c.unproject(500.0, 500.0)!!
        val b = c.unproject(600.0, 500.0)!!
        val metres = Geo.distance(a, b)
        assertEquals(Mercator.metresPerPixel(ams.lat, 15.0) / 2 * 100, metres, metres * 0.002)
        assertEquals(c.metresPerPixel, c.metresPerPixelAt(500.0, 500.0)!!, 1e-6)
        // North is up when the bearing is 0.
        assertTrue(c.unproject(500.0, 100.0)!!.lat > a.lat)
    }

    @Test
    fun bearingTurnsTheView() {
        val c = Camera(ams, 16.0, 90.0, 0.0, 1000, 1000)
        // Looking east, the top of the screen is east.
        val top = c.unproject(500.0, 100.0)!!
        assertTrue(top.lon > ams.lon)
        assertEquals(ams.lat, top.lat, 1e-6)
    }

    @Test
    fun horizonAndSky() {
        val flat = Camera(ams, 16.0, 0.0, 0.0, 800, 1600)
        assertTrue(flat.horizonY.isInfinite())
        val steep = Camera(ams, 16.0, 0.0, 72.0, 800, 1600)
        // With an 18° half field of view, 72° of pitch just shows the horizon at the top edge.
        assertEquals(0.0, steep.horizonY, 2.0)
        assertNull(steep.unproject(400.0, steep.horizonY - 5))
        assertNotNull(steep.unproject(400.0, steep.horizonY + 20))
        assertTrue(steep.farY > steep.horizonY)
    }

    @Test
    fun groundPolygonHoldsTheTargetAndWidensAway() {
        val c = Camera(ams, 16.3, -20.0, 50.0, 824, 1830, 2f)
        val g = c.groundPolygon()
        val tx = c.targetX; val ty = c.targetY
        // The target lies inside the quadrilateral.
        assertTrue(TileCover.intersects(g, Mercator.tileAt(ams, 18)))
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = g[2 * i]; val yi = g[2 * i + 1]; val xj = g[2 * j]; val yj = g[2 * j + 1]
            if ((yi > ty) != (yj > ty) && tx < (xj - xi) * (ty - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        assertTrue(inside)
        val bottomWidth = Math.hypot(g[2] - g[0], g[3] - g[1])
        val topWidth = Math.hypot(g[4] - g[6], g[5] - g[7])
        assertTrue("far edge wider than near edge", topWidth > bottomWidth * 1.5)
    }

    @Test
    fun tileMatrixAgreesWithProjection() {
        val c = Camera(ams, 16.3, -20.0, 50.0, 824, 1830, 2f)
        val tile = Mercator.tileAt(ams, 14)
        val mpu = Mercator.metresPerUnit(Mercator.lat(tile.unitY + tile.unitSize / 2))
        val m = FloatArray(12)
        c.tileMatrix(tile.unitX, tile.unitY, mpu, m)
        val out = DoubleArray(3)
        for ((x, y, z) in listOf(Triple(100f, 200f, 0f), Triple(700f, 900f, 25f), Triple(1200f, 400f, 80f))) {
            val w = m[8] * x + m[9] * y + m[10] * z + m[11]
            val sx = (m[0] * x + m[1] * y + m[2] * z + m[3]) / w
            val sy = (m[4] * x + m[5] * y + m[6] * z + m[7]) / w
            val ux = tile.unitX + x / mpu; val uy = tile.unitY + y / mpu
            assertTrue(c.projectLocal(c.localX(ux), c.localY(uy), z.toDouble(), out))
            assertEquals(out[0], sx.toDouble(), 0.05)
            assertEquals(out[1], sy.toDouble(), 0.05)
            assertEquals(out[2], w.toDouble(), 0.05)
        }
        // And the eye in tile metres projects nowhere (it is the eye).
        val eye = FloatArray(3)
        c.eyeInTile(tile.unitX, tile.unitY, mpu, eye)
        val w = m[8] * eye[0] + m[9] * eye[1] + m[10] * eye[2] + m[11]
        assertEquals(0.0, w.toDouble(), 0.5)
    }

    @Test
    fun pitchIsClamped() {
        assertEquals(Camera.MAX_PITCH, Camera(ams, 16.0, 0.0, 85.0, 100, 100).pitch, 0.0)
        assertEquals(340.0, Camera(ams, 16.0, -20.0, 10.0, 100, 100).bearing, 1e-9)
    }
}
