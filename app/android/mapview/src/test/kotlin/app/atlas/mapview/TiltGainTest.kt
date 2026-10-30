package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tilt's gain: fine for slow, small moves, fast for a sweep, and nothing but the gain hangs on the speed. */
class TiltGainTest {
    /** Moves [dp] dp at [speed] dp a second in events [step] ms apart from [t0]; the degrees, and the end time. */
    private fun TiltGain.drag(dp: Double, speed: Double, t0: Long, step: Long = 8): Pair<Double, Long> {
        val per = (speed * step / 1000.0).toFloat()
        var moved = 0.0
        var deg = 0.0
        var t = t0
        while (moved < dp - 1e-9) {
            t += step
            val d = minOf(per.toDouble(), dp - moved).toFloat()
            deg += degrees(d, t)
            moved += d
        }
        return deg to t
    }

    @Test
    fun theGainRisesWithTheSpeedFromFineToFast() {
        assertEquals(TiltGain.FINE, TiltGain.gain(0.0), 0.0)
        assertEquals(TiltGain.FINE, TiltGain.gain(TiltGain.SLOW), 1e-12)
        assertEquals(TiltGain.FAST, TiltGain.gain(TiltGain.QUICK), 1e-12)
        assertEquals(TiltGain.FAST, TiltGain.gain(10_000.0), 0.0)
        var last = 0.0
        for (v in 1..2000) {
            val g = TiltGain.gain(v.toDouble())
            assertTrue("at $v dp/s", g >= last - 1e-12)
            last = g
        }
        // halfway up the speeds' logarithm, halfway up the gain
        assertEquals((TiltGain.FINE + TiltGain.FAST) / 2, TiltGain.gain(Math.sqrt(TiltGain.SLOW * TiltGain.QUICK)), 1e-9)
    }

    @Test
    fun aSlowDragSetsTheTiltToAFractionOfADegree() {
        val g = TiltGain()
        g.reset(0)
        // 12.5 dp at 30 dp/s (about 5 mm a second on a phone): one degree
        val (deg, _) = g.drag(12.5, 30.0, 0)
        assertEquals(1.0, deg, 1e-6)
    }

    @Test
    fun aQuickSweepCrossesTheWholeRange() {
        val g = TiltGain()
        g.reset(0)
        // 190 dp (about 3 cm) at 1000 dp a second, starting 50 ms after the tilt did: past the 70° the map allows
        val (deg, _) = g.drag(190.0, 1000.0, 50)
        assertTrue("swept $deg°", deg >= 70.0)
    }

    @Test
    fun theFirstMoveIsTakenOverTheTimeSinceTheTiltBegan() {
        val g = TiltGain()
        g.reset(1000)
        // 10 dp gathered over 400 ms while the fingers said what they do: 25 dp a second, fine
        assertEquals(10 * TiltGain.FINE, g.degrees(10f, 1400), 1e-9)
        // and the next moves go by their own speed, not that gathering's: 8 dp in 8 ms, a sweep
        assertEquals(8 * TiltGain.FAST, g.degrees(8f, 1408), 1e-9)
        val h = TiltGain()
        h.reset(1000)
        // the same 10 dp in 10 ms: a sweep at once
        assertEquals(10 * TiltGain.FAST, h.degrees(10f, 1010), 1e-9)
    }

    @Test
    fun aSweepAfterARestIsQuickAtOnce() {
        val g = TiltGain()
        g.reset(0)
        val (_, t) = g.drag(5.0, 20.0, 0)
        // half a second still, then 10 dp in each 8 ms frame (1250 dp a second): the second frame is at full speed
        g.degrees(10f, t + 508)
        assertEquals(10 * TiltGain.FAST, g.degrees(10f, t + 516), 1e-9)
    }

    @Test
    fun downTiltsBackAndTheMoveIsTheGainTimesTheDp() {
        val g = TiltGain()
        g.reset(0)
        val up = g.degrees(0.5f, 100)
        val down = g.degrees(-0.5f, 200)
        assertTrue(up > 0 && down < 0)
        assertEquals(-0.5 * TiltGain.FINE, down, 1e-9)
    }
}
