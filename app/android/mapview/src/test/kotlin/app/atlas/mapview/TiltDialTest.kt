package app.atlas.mapview

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tilt dial's graduations, figures, detents, readout and place. */
class TiltDialTest {
    @Test
    fun graduationsEveryDegreeLongerEveryFiveAndTenFiguredEveryThirty() {
        assertEquals(TiltDial.Mark.TEN, TiltDial.mark(0))
        assertEquals(TiltDial.Mark.DEGREE, TiltDial.mark(1))
        assertEquals(TiltDial.Mark.FIVE, TiltDial.mark(5))
        assertEquals(TiltDial.Mark.DEGREE, TiltDial.mark(37))
        assertEquals(TiltDial.Mark.FIVE, TiltDial.mark(45))
        assertEquals(TiltDial.Mark.TEN, TiltDial.mark(70))
        assertEquals(TiltDial.Mark.TEN, TiltDial.mark(90))
        assertEquals(listOf(0, 30, 60), TiltDial.figures(70.0))
        // under zoom 8 the limit falls: no figure past it
        assertEquals(listOf(0, 30), TiltDial.figures(CameraOps.maxPitch(6.0)))
        assertEquals(listOf(0, 30), TiltDial.figures(CameraOps.maxPitch(3.0)))
        assertEquals(listOf(0), TiltDial.figures(20.0))
    }

    @Test
    fun detentsAtEachDegreeEachTenAndTheLimits() {
        assertEquals(TiltDial.Detent.NONE, TiltDial.detent(10.2, 10.8, 70.0))
        assertEquals(TiltDial.Detent.DEGREE, TiltDial.detent(10.8, 11.2, 70.0))
        assertEquals(TiltDial.Detent.DEGREE, TiltDial.detent(11.2, 10.8, 70.0))
        assertEquals(TiltDial.Detent.TEN, TiltDial.detent(9.6, 10.3, 70.0))
        assertEquals(TiltDial.Detent.TEN, TiltDial.detent(10.3, 9.6, 70.0))
        assertEquals("a sweep past a tens", TiltDial.Detent.TEN, TiltDial.detent(5.5, 25.5, 70.0))
        assertEquals("landing on a degree", TiltDial.Detent.DEGREE, TiltDial.detent(11.6, 12.0, 70.0))
        assertEquals("leaving it", TiltDial.Detent.NONE, TiltDial.detent(12.0, 12.4, 70.0))
        assertEquals(TiltDial.Detent.NONE, TiltDial.detent(12.0, 12.0, 70.0))
        // the limits: reached, not left, and not again while pushed against
        assertEquals(TiltDial.Detent.LIMIT, TiltDial.detent(69.0, 70.0, 70.0))
        assertEquals(TiltDial.Detent.LIMIT, TiltDial.detent(0.5, 0.0, 70.0))
        assertEquals(TiltDial.Detent.NONE, TiltDial.detent(70.0, 69.5, 70.0))
        assertEquals(TiltDial.Detent.NONE, TiltDial.detent(0.0, 0.4, 70.0))
        assertEquals(TiltDial.Detent.LIMIT, TiltDial.detent(50.0, 54.0, 54.0))
    }

    @Test
    fun theReadoutHoldsItsFigureAgainstJitter() {
        assertEquals(37, TiltDial.readout(null, 37.4))
        assertEquals(38, TiltDial.readout(null, 37.5))
        assertEquals(37, TiltDial.readout(37, 37.55))
        assertEquals(38, TiltDial.readout(37, 37.61))
        assertEquals(38, TiltDial.readout(38, 37.45))
        assertEquals(37, TiltDial.readout(38, 37.39))
        assertEquals(0, TiltDial.readout(0, 0.0))
    }

    @Test
    fun theDialStandsAwayFromTheFingersAndTheChrome() {
        // the Pixel's 1080 × 2424 view at 2.625: 411 × 923 dp
        val d = 2.625f
        val out = FloatArray(2)
        val w = (TiltDial.WIDTH_DP * d)
        val h = (TiltDial.HEIGHT_DP * d)
        val m = TiltDial.MARGIN_DP * d
        // fingers right of the middle (a right hand): on the left, in the middle of the height
        TiltDial.place(1080, 2424, d, Insets(), 700f, emptyList(), out)
        assertEquals(m, out[0], 0.01f)
        assertEquals((2424 - h) / 2, out[1], 1f)
        // fingers on the left: on the right
        TiltDial.place(1080, 2424, d, Insets(), 300f, emptyList(), out)
        assertEquals(1080 - m - w, out[0], 0.01f)
        // the chrome over the left middle: the nearest place clear of it, a margin off
        val chrome = Rect(0f, 300f, 200f, 560f)
        TiltDial.place(1080, 2424, d, Insets(), 700f, listOf(chrome), out)
        assertEquals(m, out[0], 0.01f)
        val top = out[1] / d
        val bottom = top + TiltDial.HEIGHT_DP
        val clear = bottom + TiltDial.MARGIN_DP / 2 <= chrome.top || top - TiltDial.MARGIN_DP / 2 >= chrome.bottom
        assertEquals("at $top..$bottom dp", true, clear)
        // a whole side taken: the other
        TiltDial.place(1080, 2424, d, Insets(), 700f, listOf(Rect(0f, 0f, 160f, 923f)), out)
        assertEquals(1080 - m - w, out[0], 0.01f)
        // inside the content padding
        TiltDial.place(1080, 2424, d, Insets(left = 40f, top = 300f, bottom = 900f), 700f, emptyList(), out)
        assertEquals(40f + m, out[0], 0.01f)
        assertEquals((300f + m + (2424 - 900f - m - h)) / 2, out[1], 1f)
    }
}
