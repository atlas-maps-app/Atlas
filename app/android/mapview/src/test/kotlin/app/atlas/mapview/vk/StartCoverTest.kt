package app.atlas.mapview.vk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start's paper (ENGINE.md step 3): drawn once while it waits, then faded over [StartCover.FADE_MS] (the audit, B1). */
class StartCoverTest {
    private val step = 16L

    @Test
    fun waitingForThePictureAsksForNoFrame() {
        val cover = StartCover()
        assertFalse(cover.update(0, coreReady = false))
        assertFalse(cover.update(5_000, coreReady = false))
        assertEquals(1f, cover.canvas)
        assertFalse(cover.started)
    }

    @Test
    fun theFadeStartsAtThePictureAndTakesItsTime() {
        val cover = StartCover()
        assertFalse(cover.update(0, coreReady = false))
        // the first step starts the fade, however long the paper waited
        assertTrue(cover.update(4_000, coreReady = true))
        assertEquals(1f, cover.canvas)
        var now = 4_000L
        var frames = 0
        while (cover.update(now + step, coreReady = true)) {
            now += step
            frames++
        }
        assertTrue(cover.started)
        assertEquals(0f, cover.canvas)
        // 300 ms of 16 ms frames
        assertEquals((StartCover.FADE_MS / step).toInt(), frames)
    }

    @Test
    fun aFadeThatWaitsForThePictureAgainGoesOnFromWhereItStopped() {
        val cover = StartCover()
        assertTrue(cover.update(0, coreReady = true))
        assertTrue(cover.update(100, coreReady = true))
        val at = cover.canvas
        assertEquals(1f - 100f / StartCover.FADE_MS, at, 1e-6f)
        // the picture incomplete: nothing is due, and the paper stays as it is
        assertFalse(cover.update(116, coreReady = false))
        assertEquals(at, cover.canvas)
        // complete again two seconds later: no jump by the wait, then the fade's steps again
        assertTrue(cover.update(2_116, coreReady = true))
        assertEquals(at, cover.canvas)
        assertTrue(cover.update(2_132, coreReady = true))
        assertEquals(at - step.toFloat() / StartCover.FADE_MS, cover.canvas, 1e-6f)
    }
}
