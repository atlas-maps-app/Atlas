package app.atlas.mapview

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fly-to: exact ends, a zoom-out arc on long jumps, barely any on short ones, steady progress. */
class FlyToTest {
    private val amsterdam = CameraPosition(Fixtures.westermarkt, 16.0, 20.0, 45.0)
    private val paris = CameraPosition(LatLon(48.8584, 2.2945), 16.5, 340.0, 30.0)

    @Test
    fun endsExactly() {
        val f = FlyTo(amsterdam, paris, 915.0)
        assertEquals(amsterdam, f.at(0.0))
        assertEquals(paris, f.at(1.0))
        val almost = f.at(0.999)
        assertTrue(Geo.distance(almost.target, paris.target) < 5.0)
        assertEquals(paris.zoom, almost.zoom, 0.05)
    }

    @Test
    fun longJumpsZoomOutAndBackIn() {
        val f = FlyTo(amsterdam, paris, 915.0)
        var minZoom = Double.MAX_VALUE
        var lastDistance = Geo.distance(amsterdam.target, paris.target)
        for (i in 1..99) {
            val c = f.at(i / 100.0)
            minZoom = minOf(minZoom, c.zoom)
            val d = Geo.distance(c.target, paris.target)
            assertTrue("went back at $i", d <= lastDistance + 1.0)
            lastDistance = d
        }
        assertTrue("lowest zoom $minZoom", minZoom < 9.0)
        // Bearing 20 → 340 the short way: through north, never through south.
        val mid = f.at(0.5).bearing
        assertTrue("bearing $mid", CameraOps.turn(0.0, mid).let { it in -21.0..21.0 })
    }

    @Test
    fun shortHopsStayClose() {
        val next = amsterdam.copy(target = LatLon(52.3760, 4.8860))
        val f = FlyTo(amsterdam, next, 915.0)
        for (i in 0..20) assertTrue(f.at(i / 20.0).zoom > 15.4)
    }

    @Test
    fun zoomOnlyChangesNoPlace() {
        val z = amsterdam.copy(zoom = 18.0)
        val f = FlyTo(amsterdam, z, 915.0)
        val mid = f.at(0.5)
        assertTrue(Geo.distance(mid.target, amsterdam.target) < 0.01)
        assertEquals(17.0, mid.zoom, 0.01)
    }

    @Test
    fun easeIsSymmetric() {
        assertEquals(0.5, FlyTo.ease(0.5), 1e-12)
        assertEquals(1.0, FlyTo.ease(0.3) + FlyTo.ease(0.7), 1e-12)
    }
}
