package app.atlas.mapview.vk

import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How often the chrome's shapes wake the core ([ChromeShapes.onChange], which the map answers with
 * MapCameraSource.chromeMoved and MapCore.requestFrame: MapController.chromeMoved). The user, 11 November: "on the home
 * screen when the categories come up and the categiry chips dissapear, it lags": the home row's eight chips, keep-outs
 * alone with the glass off, woke the core eight times on each frame the search sheet moved them, 192 times a glide of 24
 * frames, and the core drew the whole map on each. Shapes the core does not frost now wake it once, when they have come
 * to rest or gone; a shape it draws the glass under wakes it on every move, as before.
 *
 * A glide here is the sheet's from half to full: 24 frames at 90 Hz move eight chips, then the UI rests. Each frame runs
 * as the phone's does: the frame callback asked for ([ChromeShapes.frame]), the layout placing the shapes, then the
 * core's vsync ([MapCameraSource.camera]).
 */
class ChromeWakesTest {
    private val frameNanos = 11_111_111L
    private val out: ByteBuffer = ByteBuffer.allocateDirect(CameraWriter.SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun still(revision: Long) = MapCameraSource.Samples(
        revision = revision, t0 = 1L, lat0 = 52.37, lon0 = 4.88, distance0 = 430.0, bearing0 = 10.0, pitch0 = 52.0, principalY0 = 440.0,
        t1 = 1L, lat1 = 52.37, lon1 = 4.88, distance1 = 430.0, bearing1 = 10.0, pitch1 = 52.0, principalY1 = 440.0,
        principalXDp = 206.0, widthPx = 1080, heightPx = 2400, widthDp = 412, heightDp = 916, density = 1080.0 / 412.0,
        rest = true, follow = false, pack = 2L, profile = CameraWriter.PROFILE_REGION, draw = true,
    )

    /** The home's chrome over a resting map: eight chips, the UI's frames and the core's vsyncs, and the wakes counted. */
    private inner class Home(val frosted: Boolean, coreDraws: Boolean) {
        val shapes = ChromeShapes()
        val source = MapCameraSource()
        var wakes = 0
        var drawn = 0
        var t = 100 * frameNanos
        private var asked = false
        val chips: List<Int>

        init {
            source.chrome = shapes
            // as MapController.chromeMoved: the source's revision and the core's frame, one each a wake
            shapes.onChange = {
                source.chromeMoved()
                wakes++
            }
            shapes.postFrame = { asked = true }
            shapes.coreDraws = coreDraws
            chips = List(8) { shapes.add() }
            source.publish(still(1))
            frame { place(HALF) }
            repeat(3) { frame {} }
            wakes = 0
            drawn = 0
        }

        fun place(top: Float) {
            for ((i, slot) in chips.withIndex()) {
                val left = 30f + i * 130f
                shapes.place(slot, left, top - 120f, left + 110f, top - 20f, 50f, 52.5f, 1.8f, frosted, t)
            }
        }

        /** One frame: the callback asked for, the UI's [layout], the core's vsync. The wakes it made. */
        fun frame(layout: () -> Unit): Int {
            t += frameNanos
            val before = wakes
            if (asked) {
                asked = false
                shapes.frame(t)
            }
            layout()
            shapes.laidOut(t)
            if (source.camera(t + frameNanos, out)) drawn++
            return wakes - before
        }

        /** The sheet's glide from half to full, eased: the wakes of each of its 24 moving frames. */
        fun glide(): List<Int> = (1..GLIDE).map { i ->
            val u = i / GLIDE.toFloat()
            frame { place(HALF + (FULL - HALF) * (u * u * (3f - 2f * u))) }
        }

        /** The first chip's top as the core read it last, px. */
        fun writtenTop(): Float = out.getFloat(CameraWriter.KEEP_OUT_AT + 4) * (1080f / 412f)
    }

    @Test
    fun shapesTheCoreDoesNotFrostWakeItOnceWhenTheyRest() {
        val home = Home(frosted = false, coreDraws = true)
        val moving = home.glide()
        assertEquals("no wake on a frame that moves keep-outs alone", List(GLIDE) { 0 }, moving)
        assertEquals("the core draws none of the glide's frames", 0, home.drawn)
        // the frame after the last move may still move; the one after that settles, once
        val rest = List(6) { home.frame {} }
        assertEquals(listOf(0, 1, 0, 0, 0, 0), rest)
        assertEquals("one frame, the settled one", 1, home.drawn)
        assertEquals("with the chips where they came to rest", FULL - 120f, home.writtenTop(), 0.01f)
        println("WAKES keep-outs alone (the glass off): moving frames $moving (sum ${moving.sum()}), rest frames $rest, the core draws ${home.drawn} frame")
    }

    @Test
    fun aShapeTheCoreFrostsWakesItOnEveryMoveAsBefore() {
        val home = Home(frosted = true, coreDraws = true)
        val moving = home.glide()
        assertEquals("the glass follows its chrome: each shape's move asks for the frame", List(GLIDE) { 8 }, moving)
        assertEquals(GLIDE, home.drawn)
        val rest = List(6) { home.frame {} }
        assertEquals("and no wake at rest", List(6) { 0 }, rest)
        assertEquals("one frame more, the glass's at rest", GLIDE + 1, home.drawn)
        assertEquals(FULL - 120f, home.writtenTop(), 0.01f)
        println("WAKES frosted, the core draws the glass: moving frames $moving (sum ${moving.sum()}), rest frames $rest, the core draws ${home.drawn} frames")
    }

    @Test
    fun theCoresGlassRestsWhereItsChromeDoes() {
        // The core's vsync comes before the UI's layout of it, so a moving shape is written a frame's movement on
        // (ChromeShapes.write). The move's last frame is written so too, 30 px past where the chips stop, and nothing
        // wakes the core after it: the source draws one frame more itself (MapCameraSource.camera), with them at rest.
        val home = Home(frosted = true, coreDraws = true)
        var top = HALF
        repeat(5) {
            home.frame {
                top -= 30f
                home.place(top)
            }
        }
        assertEquals(5, home.drawn)
        assertEquals("the last moving frame, carried on", top - 30f - 120f, home.writtenTop(), 0.01f)
        assertEquals("no wake", 0, home.frame {})
        assertEquals("and one frame more", 6, home.drawn)
        assertEquals("the glass where its chrome stopped", top - 120f, home.writtenTop(), 0.01f)
        assertEquals(0, (1..4).sumOf { home.frame {} })
        assertEquals("then the core rests", 6, home.drawn)
        // keep-outs alone are carried too and ask for no frame more: theirs is the one their rest wakes
        val clear = Home(frosted = false, coreDraws = true)
        repeat(5) { i ->
            clear.frame {
                clear.place(HALF - 30f * (i + 1))
                if (i == 2) clear.source.publish(still(2))
            }
        }
        assertEquals("the camera's own frame alone", 1, clear.drawn)
    }

    @Test
    fun frostedShapesWhoseGlassIsNotTheCoresAreKeepOutsAloneToIt() {
        // the map on a TextureView, or under the start's paper: HWUI draws the glass, the core none
        val home = Home(frosted = true, coreDraws = false)
        val moving = home.glide()
        assertEquals(List(GLIDE) { 0 }, moving)
        val rest = List(6) { home.frame {} }
        assertEquals(listOf(0, 1, 0, 0, 0, 0), rest)
        assertEquals(1, home.drawn)
        println("WAKES frosted, the glass not the core's: moving frames $moving (sum ${moving.sum()}), rest frames $rest, the core draws ${home.drawn} frame")
    }

    @Test
    fun aFrameTheCoreDrawsAnywayTakesTheKeepOutsWhereTheyAre() {
        val home = Home(frosted = false, coreDraws = true)
        // mid-glide the camera moves (a new publication): that frame carries the chips as laid out, with no wake
        var top = 0f
        val wakes = (1..12).sumOf { i ->
            home.frame {
                top = HALF - 30f * i
                home.place(top)
                if (i == 6) home.source.publish(still(2))
            }
        }
        assertEquals(0, wakes)
        assertEquals("the camera's own frame", 1, home.drawn)
        home.frame {}
        assertEquals(1, home.frame {})
        assertEquals(top - 120f, home.writtenTop(), 0.01f)
    }

    @Test
    fun aPauseSettlesAndMovingOnIsQuietAgain() {
        val home = Home(frosted = false, coreDraws = true)
        assertEquals(0, (1..5).sumOf { i -> home.frame { home.place(HALF - 40f * i) } })
        assertEquals(listOf(0, 1, 0), List(3) { home.frame {} })
        assertEquals(0, (6..10).sumOf { i -> home.frame { home.place(HALF - 40f * i) } })
        assertEquals(listOf(0, 1, 0), List(3) { home.frame {} })
        assertEquals(2, home.drawn)
    }

    @Test
    fun keepOutsReleasedTogetherWakeTheCoreOnce() {
        val home = Home(frosted = false, coreDraws = true)
        assertEquals("the chips go in one frame", 0, home.frame { home.chips.forEach(home.shapes::remove) })
        assertEquals(listOf(0, 1, 0), List(3) { home.frame {} })
        assertEquals("the core's frame has none of them", 0, out.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
        // a frosted one's glass goes at once
        val glass = Home(frosted = true, coreDraws = true)
        assertEquals(8, glass.frame { glass.chips.forEach(glass.shapes::remove) })
    }

    @Test
    fun aChangeOfGlassIsToldAtOnce() {
        val home = Home(frosted = false, coreDraws = true)
        val slot = home.chips[0]
        // a keep-out becomes frosted where it lies, then clear again: the core draws its glass, then takes it away
        assertEquals(1, home.frame { home.shapes.place(slot, 30f, HALF - 120f, 140f, HALF - 20f, 50f, 52.5f, 1.8f, true, home.t) })
        assertEquals(1, home.frame { home.shapes.place(slot, 30f, HALF - 120f, 140f, HALF - 20f, 50f, 52.5f, 1.8f, false, home.t) })
        // its fade is the glass's too, a keep-out's alone is not
        assertEquals(1, home.frame { home.shapes.place(slot, 30f, HALF - 120f, 140f, HALF - 20f, 50f, 52.5f, 1.8f, true, home.t) })
        assertEquals(1, home.frame { home.shapes.fade(slot, 0.5f) })
        assertEquals(0, home.frame { home.shapes.fade(home.chips[1], 0.5f) })
        // and the core's glass coming or going wakes it, whatever the shapes
        assertEquals(1, home.frame { home.shapes.coreDraws = false })
        assertTrue(home.drawn >= 5)
    }

    @Test
    fun withNoFrameToWaitForEveryChangeIsToldAtOnce() {
        // no window watched and no frames given: nothing would settle the keep-outs later
        val shapes = ChromeShapes()
        var wakes = 0
        shapes.onChange = { wakes++ }
        val slot = shapes.add()
        assertTrue(shapes.place(slot, 0f, 0f, 100f, 100f, 10f, 20f, 1f, false, 1L))
        assertEquals(1, wakes)
        assertTrue(shapes.place(slot, 0f, 10f, 100f, 110f, 10f, 20f, 1f, false, 2L))
        assertEquals(2, wakes)
        assertFalse(shapes.place(slot, 0f, 10f, 100f, 110f, 10f, 20f, 1f, false, 2L))
        shapes.remove(slot)
        assertEquals(3, wakes)
    }

    private companion object {
        const val GLIDE = 24

        /** The sheet's top at half and at full, px. */
        const val HALF = 1200f
        const val FULL = 300f
    }
}
