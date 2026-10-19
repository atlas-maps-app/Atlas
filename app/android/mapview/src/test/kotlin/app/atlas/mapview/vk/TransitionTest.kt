package app.atlas.mapview.vk

import app.atlas.mapview.vk.Transition.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The transitional rule (plan-vector §0.1, K11): the core where it has tiles, today's map elsewhere, a 300 ms
 * cross-fade, and never a blank frame: the path switched to fades in only once it has its picture, and the one switched
 * from stays whole until then.
 */
class TransitionTest {
    private val lines = ArrayList<String>()
    private fun rule() = Transition { lines += it }

    @Test
    fun theStartsPaperGoesOnceTheCoresFirstPictureIsComplete() {
        val t = rule()
        assertEquals(1f, t.canvas)
        assertFalse(t.coreHidden) // the core draws under the paper
        assertTrue(t.update(0, Path.CORE, coreReady = false, canvasReady = false, where = "amsterdam"))
        assertEquals(1f, t.canvas) // no picture yet: the paper stays whole
        assertTrue(t.update(500, Path.CORE, coreReady = true, canvasReady = false, where = "amsterdam"))
        assertEquals(1f, t.canvas) // the fade begins now
        assertTrue(t.update(650, Path.CORE, coreReady = true, canvasReady = false, where = "amsterdam"))
        assertEquals(0.5f, t.canvas, 1e-6f)
        assertFalse(t.update(800, Path.CORE, coreReady = true, canvasReady = false, where = "amsterdam"))
        assertEquals(0f, t.canvas)
        assertEquals(Path.CORE, t.shown)
        assertTrue(t.started)
        assertTrue(t.canvasHidden)
        assertTrue("the start is not a switch", lines.isEmpty())
    }

    @Test
    fun aSwitchWaitsForItsPictureFadesAndIsLogged() {
        val t = rule()
        t.update(0, Path.CORE, coreReady = true, canvasReady = false, where = "amsterdam")
        t.update(400, Path.CORE, coreReady = true, canvasReady = false, where = "amsterdam")
        assertEquals(0f, t.canvas)
        // To a place without the core's tiles: the core's picture held until today's map has its own.
        assertTrue(t.update(1_000, Path.CANVAS, coreReady = true, canvasReady = false, where = "utrecht"))
        assertEquals(0f, t.canvas)
        assertFalse(t.coreHidden) // the core still draws, its picture the one seen
        assertTrue(t.update(3_000, Path.CANVAS, coreReady = true, canvasReady = true, where = "utrecht"))
        assertEquals(listOf("vk transitional utrecht (1 this session)", "vk transition core>canvas utrecht: today's map ready after 2000 ms, the core's picture held until then; fading 300 ms"), lines)
        assertFalse(t.update(3_300, Path.CANVAS, coreReady = true, canvasReady = true, where = "utrecht"))
        assertEquals(1f, t.canvas)
        assertTrue(t.coreHidden)
        // And back: today's map held until the core's picture is complete there.
        assertTrue(t.update(4_000, Path.CORE, coreReady = false, canvasReady = true, where = "grachten"))
        assertEquals(1f, t.canvas)
        assertFalse(t.coreHidden) // the core draws (under today's map) to make its picture
        assertTrue(t.update(4_700, Path.CORE, coreReady = true, canvasReady = true, where = "grachten"))
        assertFalse(t.update(5_000, Path.CORE, coreReady = true, canvasReady = true, where = "grachten"))
        assertEquals(0f, t.canvas)
        assertEquals("vk transition canvas>core grachten: the core's picture complete after 700 ms, today's map held until then; fading 300 ms", lines.last())
        assertEquals(1, t.transitional)
    }

    @Test
    fun aSwitchBackMidFadeTurnsRoundFromWhereItIs() {
        val t = rule()
        t.update(0, Path.CORE, coreReady = true, canvasReady = false, where = "a")
        t.update(300, Path.CORE, coreReady = true, canvasReady = false, where = "a")
        t.update(1_000, Path.CANVAS, coreReady = true, canvasReady = true, where = "b")
        t.update(1_150, Path.CANVAS, coreReady = true, canvasReady = true, where = "b")
        assertEquals(0.5f, t.canvas, 1e-6f)
        t.update(1_200, Path.CORE, coreReady = true, canvasReady = true, where = "a")
        t.update(1_260, Path.CORE, coreReady = true, canvasReady = true, where = "a")
        assertEquals(0.3f, t.canvas, 1e-6f)
        assertFalse(t.update(1_400, Path.CORE, coreReady = true, canvasReady = true, where = "a"))
        assertEquals(0f, t.canvas)
    }

    @Test
    fun noFrameEverShowsAPathWithoutItsPicture() {
        // Random places, pictures that come late or go (the core incomplete while it builds tiles, today's map busy with
        // a new request), random frame times: today's map only ever covers more of the core while its picture is
        // there, and the core only ever shows more while its own is complete; with both there, a switch always ends.
        val rnd = Random(11)
        repeat(200) {
            val t = rule()
            var now = 0L
            var wanted = Path.CORE
            repeat(400) {
                now += 4 + rnd.nextInt(30)
                if (rnd.nextInt(40) == 0) wanted = if (wanted == Path.CORE) Path.CANVAS else Path.CORE
                val coreReady = rnd.nextInt(3) != 0
                val canvasReady = rnd.nextInt(3) != 0
                val before = t.canvas
                t.update(now, wanted, coreReady, canvasReady, "x")
                if (t.canvas > before) assertTrue("today's map faded in without its picture", canvasReady)
                if (t.canvas < before) assertTrue("the core faded in without its picture", coreReady)
                assertTrue(t.canvas in 0f..1f)
            }
            repeat(40) {
                now += 16
                t.update(now, wanted, coreReady = true, canvasReady = true, where = "x")
            }
            assertEquals(wanted, t.shown)
        }
    }
}
