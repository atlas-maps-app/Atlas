package app.atlas.mapview

import app.atlas.geo.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scale bar's round distances, and the measuring tape's ticks and walking times. */
class ScalesTest {
    @Test
    fun metricRoundsDownToOneTwoOrFive() {
        val s = Scales.choose(1.2, 96f, UnitSystem.METRIC)
        assertEquals("100 m", s.label)
        assertEquals(100 / 1.2, s.widthDp.toDouble(), 0.01)
        assertEquals("2 km", Scales.choose(30.0, 96f, UnitSystem.METRIC).label)
        assertEquals("5 m", Scales.choose(0.06, 96f, UnitSystem.METRIC).label)
    }

    @Test
    fun imperialUsesFeetThenMiles() {
        assertEquals("200 ft", Scales.choose(1.0, 96f, UnitSystem.IMPERIAL).label)
        assertEquals("1 mi", Scales.choose(20.0, 96f, UnitSystem.IMPERIAL).label)
        assertEquals("100 yd", Scales.choose(1.0, 96f, UnitSystem.IMPERIAL_UK).label)
        assertEquals("0.5 nm", Scales.choose(10.0, 96f, UnitSystem.NAUTICAL).label)
    }

    @Test
    fun everyScaleFits() {
        var m = 0.01
        while (m < 50_000) {
            for (u in UnitSystem.entries) {
                val s = Scales.choose(m, 96f, u)
                assertTrue("$m $u ${s.widthDp}", s.widthDp <= 96.01f && s.widthDp >= 96f / 5.1f)
            }
            m *= 1.37
        }
    }

    @Test
    fun tapeTicksAreRound() {
        assertEquals(10.0, OverlayPainter.tickStep(320.0, 40f), 1e-9)
        assertEquals(0.5, OverlayPainter.tickStep(18.0, 40f), 1e-9)
        assertEquals(1.0, OverlayPainter.tickStep(18.0, 30f), 1e-9)
        assertEquals(0.0, OverlayPainter.tickStep(0.0, 36f), 0.0)
    }

    @Test
    fun walkingTimeRoundsToMinutes() {
        assertEquals("1′", OverlayPainter.walkLabel(20.0))
        assertEquals("4′", OverlayPainter.walkLabel(320.0))
        assertEquals("1 h 03′", OverlayPainter.walkLabel(5000.0))
    }
}
