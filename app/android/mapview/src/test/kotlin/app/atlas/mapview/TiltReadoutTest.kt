package app.atlas.mapview

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tilt readout's stops, words, figure and place: above the fingers, beside them near the top, inside the view. */
class TiltReadoutTest {
    @Test
    fun theStopsAreFlatAndTheZoomsSteepest() {
        assertEquals(TiltReadout.Stop.FLAT, TiltReadout.stop(0.0, 70.0))
        assertEquals(TiltReadout.Stop.NONE, TiltReadout.stop(0.4, 70.0))
        assertEquals(TiltReadout.Stop.NONE, TiltReadout.stop(69.6, 70.0))
        assertEquals(TiltReadout.Stop.MAX, TiltReadout.stop(70.0, 70.0))
        // under zoom 8 the steepest falls, to 30° at world scale
        assertEquals(TiltReadout.Stop.MAX, TiltReadout.stop(CameraOps.maxPitch(6.0), CameraOps.maxPitch(6.0)))
        assertEquals(TiltReadout.Stop.MAX, TiltReadout.stop(30.0, CameraOps.maxPitch(2.0)))
        assertNull(TiltReadout.words(TiltReadout.Stop.NONE))
        assertEquals("flat", TiltReadout.words(TiltReadout.Stop.FLAT))
        assertEquals("max", TiltReadout.words(TiltReadout.Stop.MAX))
    }

    @Test
    fun aStopIsFeltWhenReachedNotWhenLeft() {
        assertTrue(TiltReadout.reached(69.0, 70.0, 70.0))
        assertTrue(TiltReadout.reached(0.4, 0.0, 70.0))
        assertFalse(TiltReadout.reached(70.0, 70.0, 70.0))
        assertFalse(TiltReadout.reached(70.0, 69.0, 70.0))
        assertFalse(TiltReadout.reached(0.0, 0.5, 70.0))
        assertFalse(TiltReadout.reached(30.0, 31.0, 70.0))
        // from one stop straight to the other (a big step at world scale)
        assertTrue(TiltReadout.reached(0.0, 30.0, 30.0))
    }

    @Test
    fun theFigureHoldsAgainstJitter() {
        assertEquals(37, TiltReadout.figure(null, 37.4))
        assertEquals(38, TiltReadout.figure(null, 37.5))
        assertEquals(37, TiltReadout.figure(37, 37.55))
        assertEquals(38, TiltReadout.figure(37, 37.61))
        assertEquals(38, TiltReadout.figure(38, 37.45))
        assertEquals(37, TiltReadout.figure(38, 37.39))
    }

    @Test
    fun itStandsAboveTheFingersThenBesideThemNearTheTop() {
        // the Pixel's 1080 × 2424 view at 2.625
        val d = 2.625f
        val out = FloatArray(2)
        val fingers = floatArrayOf(450f, 1500f, 700f, 1470f)
        // above the higher finger, centred on the pair
        var align = TiltReadout.place(fingers, 2, 1080, 2424, d, Insets(), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.CENTER, align)
        assertEquals(575f, out[0], 0.01f)
        assertEquals(1470f - TiltReadout.ABOVE_DP * d, out[1], 0.01f)
        // near the left edge it stays inside the view
        align = TiltReadout.place(floatArrayOf(40f, 1500f, 120f, 1500f), 2, 1080, 2424, d, Insets(), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.CENTER, align)
        assertEquals((TiltReadout.MARGIN_DP + TiltReadout.WIDTH_DP / 2) * d, out[0], 0.01f)
        // near the top: beside the pair, on the side with more room (here the left), at their height
        val high = floatArrayOf(600f, 150f, 850f, 130f)
        align = TiltReadout.place(high, 2, 1080, 2424, d, Insets(), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.RIGHT, align)
        assertEquals(600f - TiltReadout.BESIDE_DP * d, out[0], 0.01f)
        assertEquals(140f, out[1], 0.01f)
        // and there it stays for the rest of the tilt, though the fingers come down again
        align = TiltReadout.place(fingers, 2, 1080, 2424, d, Insets(), align, out)
        assertEquals(Paint.Align.RIGHT, align)
        assertEquals(450f - TiltReadout.BESIDE_DP * d, out[0], 0.01f)
        // with no room on the left, the right
        align = TiltReadout.place(floatArrayOf(150f, 150f, 300f, 130f), 2, 1080, 2424, d, Insets(), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.LEFT, align)
        assertEquals(300f + TiltReadout.BESIDE_DP * d, out[0], 0.01f)
        // three fingers: above the highest, centred on all three
        align = TiltReadout.place(floatArrayOf(300f, 1500f, 540f, 1440f, 780f, 1490f), 3, 1080, 2424, d, Insets(), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.CENTER, align)
        assertEquals(540f, out[0], 0.01f)
        assertEquals(1440f - TiltReadout.ABOVE_DP * d, out[1], 0.01f)
        // inside the content padding: the status bar's band counts as the top
        align = TiltReadout.place(floatArrayOf(450f, 260f, 700f, 250f), 2, 1080, 2424, d, Insets(top = 120f), Paint.Align.CENTER, out)
        assertEquals(Paint.Align.RIGHT, align)
    }
}
