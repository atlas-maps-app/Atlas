package app.atlas.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The least pan that brings an opened place out from under its sheet and buttons, the rest of the view kept. */
class RevealTargetTest {
    /** A view of flat ground, north up: the camera's target drawn at (206, 440) px, [k] px a degree. */
    private class Flat(val target: LatLon, val k: Double = 100_000.0) {
        fun toScreen(p: LatLon) = Offset((206 + (p.lon - target.lon) * k).toFloat(), (440 - (p.lat - target.lat) * k).toFloat())
        fun toGround(o: Offset) = LatLon(target.lat - (o.y - 440) / k, target.lon + (o.x - 206) / k)
    }

    private val band = Rect(32f, 156f, 380f, 424f)
    private val target = LatLon(52.37, 4.88)

    private fun reveal(view: Flat, place: LatLon) = revealTarget(view.target, place, band, view::toScreen, view::toGround)

    private fun assertLands(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.05f)
        assertEquals(expected.y, actual.y, 0.05f)
    }

    @Test
    fun aPlaceAboveTheSheetMovesNothing() {
        assertNull(reveal(Flat(target), LatLon(52.3712, 4.8812)))
    }

    @Test
    fun aPlaceUnderTheSheetComesUpToItsEdgeAndNoFurther() {
        val place = LatLon(52.369, 4.8812) // 540 px down, under the sheet
        val to = reveal(Flat(target), place)!!
        // the same view moved: the place on the band's foot, straight up from where it was
        assertLands(Offset(206f + 120f, 424f), Flat(to).toScreen(place))
    }

    @Test
    fun aPlaceUnderTheButtonsComesDownAndInFromTheSide() {
        val place = LatLon(52.3732, 4.87804) // 120 px down, 10 px from the left
        assertLands(Offset(32f, 156f), Flat(reveal(Flat(target), place)!!).toScreen(place))
    }

    @Test
    fun aPlaceTheViewDoesNotShowLandsInTheMiddle() {
        val place = LatLon(52.40, 4.90)
        val to = revealTarget(target, place, band, { null }, Flat(target)::toGround)!!
        assertLands(band.center, Flat(to).toScreen(place))
    }

    @Test
    fun beforeTheFirstFrameNothingMoves() {
        assertNull(revealTarget(target, LatLon(52.369, 4.88), band, { Offset(206f, 540f) }, { null }))
    }
}
