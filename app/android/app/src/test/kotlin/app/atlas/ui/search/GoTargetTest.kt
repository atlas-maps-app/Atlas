package app.atlas.ui.search

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Where a screen over the map moves the camera (the user, 1 November): a long press keeps the view, moving only what the
 * sheet would cover; a search's result goes to the place, at the streets' distance from further out.
 */
class GoTargetTest {
    /** A view of flat ground, north up: the camera's target drawn at (206, 440) px, [k] px a degree. */
    private class Flat(val target: LatLon, val k: Double = 100_000.0) {
        fun toScreen(p: LatLon) = Offset((206 + (p.lon - target.lon) * k).toFloat(), (440 - (p.lat - target.lat) * k).toFloat())
        fun toGround(o: Offset) = LatLon(target.lat - (o.y - 440) / k, target.lon + (o.x - 206) / k)
    }

    private val band = Rect(32f, 156f, 380f, 424f)
    private val target = LatLon(52.37, 4.88)
    private val noZoom: (CameraPosition, Offset) -> CameraPosition? = { _, _ -> error("the zoom stays") }

    private fun go(camera: CameraPosition, place: LatLon, go: Boolean, placing: (CameraPosition, Offset) -> CameraPosition? = noZoom) =
        Flat(camera.target).let { v -> goTarget(camera, place, band, go, v::toScreen, v::toGround, placing) }

    @Test
    fun aLongPressAboveTheSheetMovesNothing() {
        assertNull(go(CameraPosition(target, 17.0, 30.0, 45.0), LatLon(52.3712, 4.8812), go = false))
    }

    @Test
    fun aLongPressUnderTheSheetIsTheLeastPanWithTheViewKept() {
        val camera = CameraPosition(target, 17.0, 30.0, 45.0)
        val place = LatLon(52.369, 4.8812) // 540 px down, under the sheet
        val to = go(camera, place, go = false)!!
        assertEquals(camera.copy(target = to.target), to)
        val at = Flat(to.target).toScreen(place)
        assertEquals(326f, at.x, 0.05f)
        assertEquals(424f, at.y, 0.05f)
    }

    @Test
    fun aLongPressFarOutKeepsTheZoom() {
        assertNull(go(CameraPosition(target, 8.0, 0.0, 0.0), LatLon(52.3712, 4.8812), go = false))
    }

    @Test
    fun aSearchFromFarOutGoesToTheStreetsWithTheBearingAndTiltKept() {
        val camera = CameraPosition(target, 8.0, 30.0, 20.0)
        val there = CameraPosition(LatLon(52.08, 4.40), GO_ZOOM, 30.0, 20.0)
        var asked: Pair<CameraPosition, Offset>? = null
        val to = go(camera, LatLon(52.08, 4.40), go = true) { from, o -> asked = from to o; there }
        assertSame(there, to)
        // the place in the band's middle, from the camera at the streets' distance
        assertEquals(camera.copy(zoom = GO_ZOOM), asked!!.first)
        assertEquals(band.center, asked!!.second)
    }

    @Test
    fun aSearchNearbyIsTheLeastPan() {
        assertNull(go(CameraPosition(target, NEAR_ZOOM, 0.0, 50.0), LatLon(52.3712, 4.8812), go = true))
        val to = go(CameraPosition(target, 17.0, 0.0, 50.0), LatLon(52.369, 4.8812), go = true)!!
        assertEquals(17.0, to.zoom, 0.0)
    }
}
