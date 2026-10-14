package app.atlas.guide

import app.atlas.model.ManeuverType
import app.atlas.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vibration vocabulary: distinct, countable, in Android's waveform form. */
class HapticsTest {
    /** The on-pulse lengths of a pattern. */
    private fun ons(h: Cue.Haptic) = h.pattern.indices.filter { h.amplitudes[it] > 0 }.map { h.pattern[it] }

    @Test fun countingIsTheCode() {
        assertEquals(2, Haptics.left().pulses)
        assertEquals(3, Haptics.right().pulses)
        assertEquals(listOf(450L), ons(Haptics.straight()))
        assertEquals(listOf(400L, 90L, 400L), ons(Haptics.arrive()))
        assertEquals(5, Haptics.offRoute().pulses)
        assertEquals(listOf(90L, 400L, 90L), ons(Haptics.uTurn()))
    }

    @Test fun everyMeaningFeelsDifferent() {
        val all = listOf(Haptics.left(), Haptics.right(), Haptics.keepLeft(), Haptics.keepRight(), Haptics.straight(), Haptics.uTurn(), Haptics.arrive(), Haptics.offRoute(), Haptics.hazard())
        for (i in all.indices) for (j in all.indices) if (i != j) assertNotEquals(all[i], all[j])
        // keep is the same count as the turn, felt softer
        assertTrue(Haptics.keepLeft().amplitudes.max() < Haptics.left().amplitudes.max())
        assertEquals(Haptics.left().pulses, Haptics.keepLeft().pulses)
    }

    @Test fun closeToTheTurnTheTapsComeFaster() {
        assertTrue(Haptics.right(urgent = true).totalMillis < Haptics.right().totalMillis)
        assertEquals(3, Haptics.right(urgent = true).pulses)
    }

    @Test fun androidWaveformShape() {
        for (h in listOf(Haptics.left(), Haptics.arrive(), Haptics.offRoute())) {
            assertEquals(h.pattern.size, h.amplitudes.size)
            assertEquals(0L, h.pattern[0])
            assertEquals(0, h.amplitudes[0])
            assertTrue(h.amplitudes.all { it in 0..255 })
        }
    }

    @Test fun maneuversPickTheirPattern() {
        assertEquals(HapticMeaning.LEFT, Haptics.forManeuver(ManeuverType.TURN, Turn.SHARP_LEFT, false).meaning)
        assertEquals(HapticMeaning.KEEP_RIGHT, Haptics.forManeuver(ManeuverType.RAMP_OFF, Turn.SLIGHT_RIGHT, false).meaning)
        assertEquals(HapticMeaning.KEEP_LEFT, Haptics.forManeuver(ManeuverType.KEEP, Turn.LEFT, false).meaning)
        assertEquals(HapticMeaning.U_TURN, Haptics.forManeuver(ManeuverType.TURN, Turn.U_TURN, false).meaning)
        assertEquals(HapticMeaning.STRAIGHT, Haptics.forManeuver(ManeuverType.CONTINUE, Turn.STRAIGHT, false).meaning)
        assertEquals(HapticMeaning.ARRIVE, Haptics.forManeuver(ManeuverType.ARRIVE, Turn.STRAIGHT, false).meaning)
    }

    @Test fun earconsRiseAndFallWithTheTurn() {
        val step = { type: ManeuverType, turn: Turn ->
            app.atlas.model.Step(app.atlas.model.Maneuver(type, turn), 0, WESTERKERK, 0.0, 0.0)
        }
        assertEquals(EarconKind.TURN_LEFT, Announcer.earcon(step(ManeuverType.TURN, Turn.LEFT)))
        assertEquals(EarconKind.KEEP_RIGHT, Announcer.earcon(step(ManeuverType.TURN, Turn.SLIGHT_RIGHT)))
        assertEquals(EarconKind.STRAIGHT, Announcer.earcon(step(ManeuverType.CONTINUE, Turn.STRAIGHT)))
        assertEquals(EarconKind.U_TURN, Announcer.earcon(step(ManeuverType.U_TURN, Turn.U_TURN)))
        assertEquals(-0.7f, Announcer.pan(step(ManeuverType.TURN, Turn.LEFT), Side.UNKNOWN))
        assertEquals(0.7f, Announcer.pan(step(ManeuverType.ARRIVE, Turn.STRAIGHT), Side.RIGHT))
        assertEquals("<speak>Turn left.<break time=\"300ms\"/> Then &amp; now</speak>", Announcer.ssml("Turn left. Then & now"))
    }
}
