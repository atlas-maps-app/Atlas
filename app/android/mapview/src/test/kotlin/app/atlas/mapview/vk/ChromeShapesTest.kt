package app.atlas.mapview.vk

import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [ChromeShapes]: the chrome's keep-out shapes written into the camera state as the core reads them (dp from the map's
 * surface at the state's density, frosted only while the core draws the glass), a moving shape carried on to the vsync
 * by at most one frame and landing exactly where it rests, each shape's chrome alpha beside its frosting, shapes the
 * core's reader would refuse left out or cut, a consistent snapshot under placements on another thread, and nothing
 * allocated by [ChromeShapes.write].
 */
class ChromeShapesTest {
    private val frame = 16_666_667L

    /** A camera state with its frame written: 1080 × 2400 px at a density of 2.5 (432 × 960 dp). */
    private fun state(): ByteBuffer = CameraWriter.allocate().also {
        CameraWriter.header(it, 1, 0)
        CameraWriter.frame(it, 1080, 2400, 432, 960, 2.5)
    }

    private data class KeepOut(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float, val blur16: Int, val saturation: Int, val flags: Int)

    private fun keepOuts(b: ByteBuffer): List<KeepOut> = (0 until b.getInt(CameraWriter.KEEP_OUT_COUNT_AT)).map { i ->
        val o = CameraWriter.KEEP_OUT_AT + i * CameraWriter.KEEP_OUT_SIZE
        KeepOut(
            b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8), b.getFloat(o + 12), b.getFloat(o + 16),
            b.getShort(o + 20).toInt() and 0xFFFF, b.get(o + 22).toInt() and 0xFF, b.get(o + 23).toInt() and 0xFF,
        )
    }

    @Test
    fun theShapesGoInDpFromTheSurfaceAtTheStatesDensityFrostedOnlyWhileTheCoreDraws() {
        val shapes = ChromeShapes()
        var changes = 0
        shapes.onChange = { changes++ }
        val pill = shapes.add()
        val bar = shapes.add()
        assertTrue(shapes.place(pill, 40f, 120f, 1040f, 250f, 65f, 52.5f, 1.8f, true, frame))
        assertTrue(shapes.place(bar, 30f, 2100f, 1050f, 2260f, 80f, 60f, 1.6f, false, frame))
        shapes.surfaceAt(0f, 20f)
        val out = state()
        assertFalse(shapes.write(out, 2 * frame))
        val k = keepOuts(out)
        assertEquals(2, k.size)
        assertEquals(KeepOut(16f, 40f, 416f, 92f, 26f, 336, 180, 0), k[0])
        assertEquals(KeepOut(12f, 832f, 420f, 896f, 32f, 384, 160, 0), k[1])
        // While the core draws under the chrome, the frosted shapes are frosted; the clear one stays clear.
        shapes.coreDraws = true
        shapes.write(out, 2 * frame)
        assertEquals(listOf(1, 0), keepOuts(out).map { it.flags })
        assertTrue(changes >= 4)
        // A shape that goes takes its slot with it, and the slots past the count are cleared.
        shapes.remove(pill)
        shapes.write(out, 3 * frame)
        assertEquals(1, out.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
        assertEquals(0L, out.getLong(CameraWriter.KEEP_OUT_AT + CameraWriter.KEEP_OUT_SIZE))
        assertTrue(shapes.describe().startsWith("vk chrome 1 shapes: 30.0 2100.0 1050.0 2260.0 r 80.0 blur 60.0 sat 1.60 clear; surface 0.0 20.0; core draws the glass"))
    }

    @Test
    fun aMovingShapeIsCarriedOnToTheVsyncByAFrameAtMostAndLandsWhereItRests() {
        val shapes = ChromeShapes()
        val s = shapes.add()
        fun x(out: ByteBuffer) = keepOuts(out)[0].left * 2.5f
        val out = state()
        shapes.place(s, 0f, 100f, 200f, 200f, 10f, 50f, 1f, true, 0)
        shapes.place(s, 10f, 100f, 210f, 200f, 10f, 50f, 1f, true, frame)
        // One move is no movement yet: nothing carried.
        assertFalse(shapes.write(out, 2 * frame))
        assertEquals(10f, x(out), 1e-3f)
        shapes.place(s, 20f, 100f, 220f, 200f, 10f, 50f, 1f, true, 2 * frame)
        // Two moves, and the core asks at the next vsync before its layout: carried on by one frame's movement.
        assertTrue(shapes.write(out, 3 * frame))
        assertEquals(30f, x(out), 1e-3f)
        // Half a frame on, half the movement; at the layout's own vsync, exactly where it lies.
        assertTrue(shapes.write(out, 2 * frame + frame / 2))
        assertEquals(25f, x(out), 1e-3f)
        assertFalse(shapes.write(out, 2 * frame))
        assertEquals(20f, x(out), 1e-3f)
        // Never further than a frame: two frames on, it lies where its last layout put it.
        assertFalse(shapes.write(out, 4 * frame))
        assertEquals(20f, x(out), 1e-3f)
        // Once the UI has laid the vsync's frame out, nothing is carried: the shape did not move in it.
        shapes.laidOut(3 * frame)
        assertFalse(shapes.write(out, 3 * frame))
        assertEquals(20f, x(out), 1e-3f)
        // It moves again, then stops: the layout that shows it still puts it exactly at rest, and so does every vsync
        // after, at once.
        shapes.place(s, 30f, 100f, 230f, 200f, 10f, 50f, 1f, true, 4 * frame)
        shapes.place(s, 40f, 100f, 240f, 200f, 10f, 50f, 1f, true, 5 * frame)
        assertTrue(shapes.write(out, 6 * frame))
        assertEquals(50f, x(out), 1e-3f)
        assertFalse(shapes.place(s, 40f, 100f, 240f, 200f, 10f, 50f, 1f, true, 6 * frame))
        assertFalse(shapes.write(out, 6 * frame))
        assertEquals(40f, x(out), 1e-3f)
        assertFalse(shapes.write(out, 7 * frame))
        assertEquals(40f, x(out), 1e-3f)
        // Layouts too far apart are not one movement a frame: nothing carried.
        shapes.place(s, 50f, 100f, 250f, 200f, 10f, 50f, 1f, true, 7 * frame)
        shapes.place(s, 60f, 100f, 260f, 200f, 10f, 50f, 1f, true, 7 * frame + ChromeShapes.MAX_STEP_NANOS + 1)
        assertFalse(shapes.write(out, 7 * frame + ChromeShapes.MAX_STEP_NANOS + 2))
        assertEquals(60f, x(out), 1e-3f)
        // No vsync given: as it lies.
        assertFalse(shapes.write(out))
        assertEquals(60f, x(out), 1e-3f)
    }

    @Test
    fun eachShapeCarriesItsChromesAlphaBesideItsFrosting() {
        val shapes = ChromeShapes()
        shapes.coreDraws = true
        val pill = shapes.add()
        val bar = shapes.add()
        shapes.place(pill, 40f, 120f, 1040f, 250f, 65f, 52.5f, 1.8f, true, frame)
        shapes.place(bar, 30f, 2100f, 1050f, 2260f, 80f, 60f, 1.6f, false, frame)
        val out = state()
        // A shape comes opaque.
        shapes.write(out)
        assertEquals(listOf(1, 0), keepOuts(out).map { it.flags })
        var changes = 0
        shapes.onChange = { changes++ }
        // Bits 1-7 hold the fade, 127 × (1 - alpha) rounded; a placement keeps it, and a NaN changes nothing.
        shapes.fade(pill, 0.5f)
        shapes.fade(bar, 0f)
        shapes.place(pill, 40f, 130f, 1040f, 260f, 65f, 52.5f, 1.8f, true, 2 * frame)
        shapes.fade(pill, Float.NaN)
        shapes.write(out)
        assertEquals(listOf(1 or (64 shl 1), 127 shl 1), keepOuts(out).map { it.flags })
        assertEquals(3, changes)
        assertTrue(shapes.describe(), shapes.describe().contains("sat 1.60 clear alpha 0.00;"))
        // Past 1 and under 0 it is cut; the same alpha again is no change.
        shapes.fade(pill, 2f)
        shapes.fade(bar, -1f)
        shapes.write(out)
        assertEquals(listOf(1, 127 shl 1), keepOuts(out).map { it.flags })
        assertEquals(4, changes)
        // A slot given back and taken again comes opaque.
        shapes.remove(bar)
        val again = shapes.add()
        shapes.place(again, 30f, 2100f, 1050f, 2260f, 80f, 60f, 1.6f, true, 3 * frame)
        shapes.write(out)
        assertEquals(listOf(1, 1), keepOuts(out).map { it.flags })
    }

    @Test
    fun shapesTheCoresReaderWouldRefuseAreLeftOutOrCut() {
        val shapes = ChromeShapes()
        val out = state()
        val far = shapes.add()
        val flat = shapes.add()
        val round = shapes.add()
        shapes.place(far, 3000f, 100f, 3100f, 200f, 0f, 20f, 1f, true, frame) // more than a frame's width off it
        shapes.place(flat, 100f, 100f, 100f, 200f, 0f, 20f, 1f, true, frame) // no area
        shapes.place(round, 100f, 100f, 300f, 150f, 400f, 2000f, 3f, true, frame) // corner, blur, saturation too large
        shapes.write(out)
        val k = keepOuts(out)
        assertEquals(1, k.size)
        assertEquals(10f, k[0].radius, 1e-4f) // half of the shorter side: 50 px is 20 dp
        assertEquals(256 * 16, k[0].blur16)
        assertEquals(255, k[0].saturation)
        // A frame without a size takes no shapes.
        val empty = CameraWriter.allocate()
        shapes.write(empty)
        assertEquals(0, empty.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
    }

    @Test
    fun theSlotsRunOutAtSixteenAndComeBack() {
        val shapes = ChromeShapes()
        val slots = (0 until ChromeShapes.MAX_SHAPES).map { shapes.add() }
        assertEquals((0 until 16).toList(), slots)
        assertEquals(-1, shapes.add())
        shapes.remove(5)
        assertEquals(5, shapes.add())
        // A freed slot holds nothing until placed again.
        val out = state()
        shapes.write(out)
        assertEquals(0, out.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
    }

    @Test
    fun theRenderThreadNeverReadsHalfAPlacement() {
        // The main thread moves sixteen shapes, each along x and y at once, generation after generation; the render
        // thread writes the camera state as fast as it can. Each shape's box must come from one placement: its width and
        // height stay whole (a box made of two placements would not), and every shape lies at a generation the main
        // thread placed, never further on.
        val shapes = ChromeShapes()
        val slots = (0 until ChromeShapes.MAX_SHAPES).map { shapes.add() }
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)
        val reader = Thread {
            val out = state()
            var reads = 0
            while (!stop.get() || reads < 1000) {
                shapes.write(out)
                for (k in keepOuts(out)) {
                    if (k.right - k.left != 40f || k.bottom - k.top != 20f || k.top != k.left % 50f + 40f) {
                        failure.compareAndSet(null, "a torn read: $k")
                    }
                }
                reads++
            }
        }
        reader.start()
        for (generation in 1..20_000) {
            val g = (generation % 300).toFloat()
            for (s in slots) shapes.place(s, g * 2.5f, (g % 50f + 40f) * 2.5f, (g + 40f) * 2.5f, (g % 50f + 60f) * 2.5f, 4f, 20f, 1f, true, generation.toLong())
        }
        stop.set(true)
        reader.join()
        assertEquals(null, failure.get())
    }

    @Test
    fun writingAllocatesNothing() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("the JVM counts a thread's allocations", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val shapes = ChromeShapes()
        for (i in 0 until 12) {
            val s = shapes.add()
            shapes.place(s, i * 10f, 100f, i * 10f + 100f, 200f, 20f, 50f, 1.6f, true, 0)
            shapes.place(s, i * 10f + 5f, 100f, i * 10f + 105f, 200f, 20f, 50f, 1.6f, true, frame)
            shapes.place(s, i * 10f + 10f, 100f, i * 10f + 110f, 200f, 20f, 50f, 1.6f, true, 2 * frame)
        }
        shapes.coreDraws = true
        val out = state()
        repeat(1000) { shapes.write(out, 3 * frame) } // warm
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(100_000) { shapes.write(out, 3 * frame) }
        val after = bean.getThreadAllocatedBytes(id)
        // The measurement itself may allocate a little: nowhere near a byte a write.
        assertTrue("allocated ${after - before} bytes over 100,000 writes", after - before < 10_000)
        assertEquals(12, out.getInt(CameraWriter.KEEP_OUT_COUNT_AT))
        assertEquals(8f, keepOuts(out)[0].radius, 1e-4f)
    }
}
