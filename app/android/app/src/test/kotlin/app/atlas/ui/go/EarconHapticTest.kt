package app.atlas.ui.go

import app.atlas.guide.Cue
import app.atlas.guide.EarconKind
import app.atlas.guide.Haptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The earcons synthesised in code, and the guide's vibrations as composition primitives. */
class EarconHapticTest {
    @Test
    fun rightRisesLeftFalls() {
        val right = EarconSynth.notes(EarconKind.TURN_RIGHT)
        val left = EarconSynth.notes(EarconKind.TURN_LEFT)
        assertTrue(right.first().freq < right.last().freq)
        assertTrue(left.first().freq > left.last().freq)
        val keepRight = EarconSynth.notes(EarconKind.KEEP_RIGHT).single()
        assertTrue(keepRight.glideTo!! > keepRight.freq)
        val keepLeft = EarconSynth.notes(EarconKind.KEEP_LEFT).single()
        assertTrue(keepLeft.glideTo!! < keepLeft.freq)
        // Arrival climbs a major arpeggio to the octave.
        val arrive = EarconSynth.notes(EarconKind.ARRIVE).map { it.freq }
        assertEquals(arrive.sorted(), arrive)
        assertEquals(2.0, arrive.last() / arrive.first(), 0.01)
    }

    @Test
    fun theBeaconsPitchFollowsTheAsk() {
        val base = EarconSynth.notes(EarconKind.BEACON).single().freq
        assertEquals(base * 1.5, EarconSynth.notes(EarconKind.BEACON, 1.5f).single().freq, 1e-6)
    }

    @Test
    fun pcmIsStereoPannedAndClickFree() {
        for (kind in EarconKind.entries) {
            val pcm = EarconSynth.pcm(kind, pan = 0f)
            val frames = pcm.size / 2
            assertEquals("$kind length", EarconSynth.lengthMs(kind) * EarconSynth.SAMPLE_RATE / 1000 + 1, frames)
            // Starts and ends silent: no click.
            assertTrue(abs(pcm[0].toInt()) < 400 && abs(pcm[pcm.size - 1].toInt()) < 400)
            // Loud enough to hear, never clipped.
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$kind peak $peak", peak in 12_000..30_000)
        }
        fun energy(pcm: ShortArray, channel: Int) = (channel until pcm.size step 2).sumOf { pcm[it].toDouble() * pcm[it] }
        val right = EarconSynth.pcm(EarconKind.TURN_RIGHT, pan = 0.7f)
        assertTrue(energy(right, 1) > 4 * energy(right, 0))
        val left = EarconSynth.pcm(EarconKind.TURN_LEFT, pan = -0.7f)
        assertTrue(energy(left, 0) > 4 * energy(left, 1))
        val middle = EarconSynth.pcm(EarconKind.STRAIGHT, pan = 0f)
        assertEquals(1.0, energy(middle, 0) / energy(middle, 1), 0.01)
    }

    @Test
    fun turnsAreCountedTaps() {
        val left = HapticPlan.of(Haptics.left())
        val right = HapticPlan.of(Haptics.right())
        assertEquals(List(2) { HapticPlan.CLICK }, left.steps.map { it.primitive })
        assertEquals(List(3) { HapticPlan.CLICK }, right.steps.map { it.primitive })
        // The first tap at once, the next after the pause between them.
        assertEquals(0, right.steps[0].delayMs)
        assertTrue(right.steps[1].delayMs in 100..200)
        assertEquals(1f, right.steps[0].scale)
        // A keep is the same count, softer.
        val keep = HapticPlan.of(Haptics.keepRight())
        assertEquals(3, keep.steps.size)
        assertTrue(keep.steps.all { it.scale < 0.5f })
        // Near the turn the taps come faster.
        assertTrue(HapticPlan.of(Haptics.right(urgent = true)).steps[1].delayMs < right.steps[1].delayMs)
    }

    @Test
    fun longPulsesSwellAndFall() {
        val straight = HapticPlan.of(Haptics.straight())
        assertEquals(listOf(HapticPlan.SLOW_RISE, HapticPlan.QUICK_FALL), straight.steps.map { it.primitive })
        val arrive = HapticPlan.of(Haptics.arrive())
        assertEquals(
            listOf(HapticPlan.SLOW_RISE, HapticPlan.QUICK_FALL, HapticPlan.CLICK, HapticPlan.SLOW_RISE, HapticPlan.QUICK_FALL),
            arrive.steps.map { it.primitive },
        )
        // The long pulse is waited out before the short one: its length less the primitive's own.
        assertTrue(arrive.steps[2].delayMs > 400)
        assertEquals(setOf(HapticPlan.SLOW_RISE, HapticPlan.QUICK_FALL, HapticPlan.CLICK), arrive.primitives.toSet())
    }

    @Test
    fun anEmptyPatternPlansNothing() {
        assertTrue(HapticPlan.of(Cue.Haptic(longArrayOf(100), intArrayOf(0), app.atlas.guide.HapticMeaning.STRAIGHT)).steps.isEmpty())
    }
}
