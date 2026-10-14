package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The gesture state machine, fed with synthetic pointer streams at a phone's density (2.0). */
class GestureMachineTest {
    private class Recorder : GestureListener {
        val events = ArrayList<String>()
        var panX = 0f
        var panY = 0f
        var scale = 1f
        var rotation = 0f
        var tilt = 0f
        var dragZoom = 0f
        var fling: Pair<Float, Float>? = null
        var zoomStep: Triple<Float, Float, Boolean>? = null
        var tap: Pair<Float, Float>? = null
        var longPress: Pair<Float, Float>? = null
        var measure: FloatArray? = null
        var measureEnded = false
        var rotated: Boolean? = null

        override fun onGestureStart() { events += "start" }
        override fun onPan(x0: Float, y0: Float, x1: Float, y1: Float) { panX += x1 - x0; panY += y1 - y0; events += "pan" }
        override fun onTransform(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, rotationDeg: Float) {
            panX += x1 - x0; panY += y1 - y0; this.scale *= scale; rotation += rotationDeg; events += "transform"
        }
        override fun onTilt(dy: Float) { tilt += dy; events += "tilt" }
        override fun onZoomStep(x: Float, y: Float, zoomIn: Boolean) { zoomStep = Triple(x, y, zoomIn); events += "zoomStep" }
        override fun onDragZoom(x: Float, y: Float, dy: Float) { dragZoom += dy; events += "dragZoom" }
        override fun onFling(vx: Float, vy: Float) { fling = vx to vy; events += "fling" }
        override fun onTap(x: Float, y: Float) { tap = x to y; events += "tap" }
        override fun onLongPress(x: Float, y: Float) { longPress = x to y; events += "longPress" }
        override fun onMeasure(x0: Float, y0: Float, x1: Float, y1: Float) { measure = floatArrayOf(x0, y0, x1, y1); events += "measure" }
        override fun onMeasureEnd() { measureEnded = true; events += "measureEnd" }
        override fun onGestureEnd(rotated: Boolean) { this.rotated = rotated; events += "end" }
    }

    private val config = GestureConfig.forDensity(2f)
    private val r = Recorder()
    private val g = GestureMachine(config, r)

    /** Moves pointer [id] from (x0, y0) to (x1, y1) in [steps] events [dt] ms apart, starting at [t0]; returns the end time. */
    private fun drag(id: Long, x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, steps: Int, dt: Long): Long {
        var t = t0
        for (i in 1..steps) {
            t += dt
            val f = i / steps.toFloat()
            g.move(id, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, t)
            g.frame(t)
        }
        return t
    }

    @Test
    fun oneFingerPansByWhatTheFingerMoved() {
        g.down(1, 100f, 100f, 0)
        val t = drag(1, 100f, 100f, 300f, 160f, 0, 20, 16)
        g.up(1, 300f, 160f, t + 200)
        assertEquals(200f, r.panX, 0.01f)
        assertEquals(60f, r.panY, 0.01f)
        assertNull("a slow lift after a pause is no fling", r.fling)
        assertEquals("end", r.events.last())
        assertNull(r.tap)
    }

    @Test
    fun aQuickSwipeFlings() {
        g.down(1, 100f, 800f, 0)
        // 600 px in 150 ms: 4000 px/s.
        val t = drag(1, 100f, 800f, 100f, 200f, 0, 10, 15)
        g.up(1, 100f, 200f, t)
        val (vx, vy) = r.fling!!
        assertEquals(0f, vx, 1f)
        assertEquals(-4000f, vy, 200f)
    }

    @Test
    fun flingsAreCappedAtTheMaximumVelocity() {
        g.down(1, 0f, 0f, 0)
        val t = drag(1, 0f, 0f, 3000f, 0f, 0, 5, 10)
        g.up(1, 3000f, 0f, t)
        assertEquals(config.maxFlingVelocity, r.fling!!.first, 1f)
    }

    @Test
    fun aTapWaitsForTheDoubleTapTimeout() {
        g.down(1, 50f, 60f, 0)
        g.up(1, 51f, 61f, 80)
        assertNull(r.tap)
        assertEquals(80 + config.doubleTapTimeout, g.deadline)
        g.timeout(80 + config.doubleTapTimeout)
        assertEquals(51f to 61f, r.tap)
        assertTrue(g.isIdle)
    }

    @Test
    fun doubleTapZoomsInAtTheFirstTap() {
        g.down(1, 200f, 300f, 0)
        g.up(1, 200f, 300f, 60)
        g.down(2, 210f, 305f, 200)
        g.up(2, 210f, 305f, 260)
        assertEquals(Triple(200f, 300f, true), r.zoomStep)
        assertNull(r.tap)
        assertNull(g.deadline)
    }

    @Test
    fun doubleTapThenDragZooms() {
        g.down(1, 200f, 300f, 0)
        g.up(1, 200f, 300f, 60)
        g.down(2, 200f, 300f, 150)
        val t = drag(2, 200f, 300f, 200f, 420f, 150, 12, 16)
        g.up(2, 200f, 420f, t)
        assertEquals(120f, r.dragZoom, 0.01f)
        assertNull(r.zoomStep)
        assertEquals(0f, r.panY, 0f)
    }

    @Test
    fun aSecondTapFarAwayIsTwoTaps() {
        g.down(1, 100f, 100f, 0)
        g.up(1, 100f, 100f, 50)
        g.down(2, 700f, 1500f, 150)
        assertEquals(100f to 100f, r.tap)
        g.up(2, 700f, 1500f, 200)
        g.timeout(200 + config.doubleTapTimeout)
        assertEquals(700f to 1500f, r.tap)
    }

    @Test
    fun holdingStillIsALongPress() {
        g.down(1, 400f, 500f, 0)
        g.move(1, 402f, 503f, 200); g.frame(200)
        assertEquals(config.longPressTimeout, g.deadline)
        g.timeout(config.longPressTimeout)
        assertEquals(402f to 503f, r.longPress)
        // Moving afterwards pans nothing, and the lift is no tap.
        drag(1, 402f, 503f, 600f, 700f, 600, 5, 16)
        g.up(1, 600f, 700f, 800)
        assertEquals(0f, r.panX, 0f)
        assertNull(r.tap)
        assertTrue(g.isIdle)
    }

    @Test
    fun twoFingerTapZoomsOut() {
        g.down(1, 300f, 800f, 0)
        g.down(2, 500f, 900f, 20)
        g.up(1, 300f, 800f, 120)
        g.up(2, 500f, 900f, 130)
        assertEquals(Triple(400f, 850f, false), r.zoomStep)
        assertFalse(r.events.contains("measure"))
    }

    @Test
    fun aSecondFingerBrushingAPanIsNoZoomOut() {
        g.down(1, 100f, 800f, 0)
        var t = drag(1, 100f, 800f, 300f, 800f, 0, 10, 16)
        // A second finger touches for a moment while the first is panning.
        g.down(2, 600f, 900f, t + 5)
        g.up(2, 600f, 900f, t + 60)
        assertNull(r.zoomStep)
        // The first finger pans on.
        val before = r.panX
        t = drag(1, 300f, 800f, 400f, 800f, t + 60, 5, 16)
        assertEquals(100f, r.panX - before, 0.01f)
        g.up(1, 400f, 800f, t + 200)
        assertTrue(g.isIdle)
    }

    @Test
    fun pinchScalesBySpanChange() {
        g.down(1, 300f, 900f, 0)
        g.down(2, 500f, 900f, 10)
        var t = 10L
        for (i in 1..20) {
            t += 16
            val half = 100f + 100f * i / 20
            g.move(1, 400f - half, 900f, t)
            g.move(2, 400f + half, 900f, t)
            g.frame(t)
        }
        g.up(1, 200f, 900f, t + 10)
        g.up(2, 600f, 900f, t + 20)
        assertEquals(2f, r.scale, 0.001f)
        assertEquals(0f, r.rotation, 0f)
        assertEquals(false, r.rotated)
        assertTrue(r.events.contains("transform"))
        assertFalse(r.events.contains("tilt"))
    }

    @Test
    fun rotationWaitsForItsThresholdThenFollows() {
        val cx = 400f; val cy = 900f; val rad = 150f
        g.down(1, cx - rad, cy, 0)
        g.down(2, cx + rad, cy, 10)
        var t = 10L
        fun turnTo(deg: Double) {
            t += 16
            val a = Math.toRadians(deg)
            val dx = (rad * cos(a)).toFloat(); val dy = (rad * sin(a)).toFloat()
            g.move(1, cx - dx, cy - dy, t)
            g.move(2, cx + dx, cy + dy, t)
            g.frame(t)
        }
        for (d in 1..10) turnTo(d.toDouble())
        assertEquals("under the threshold nothing turns", 0f, r.rotation, 0f)
        for (d in 11..40) turnTo(d.toDouble())
        // Unlocked after 14°: the rest turns the map, without a jump.
        assertTrue("rotation ${r.rotation}", r.rotation in 24f..27f)
        assertEquals(1f, r.scale, 0.001f)
        g.up(1, 0f, 0f, t + 5)
        g.up(2, 0f, 0f, t + 10)
        assertEquals(true, r.rotated)
    }

    @Test
    fun twoFingersDraggedUpTogetherTilt() {
        g.down(1, 300f, 1000f, 0)
        g.down(2, 520f, 1010f, 10)
        var t = 10L
        for (i in 1..10) {
            t += 16
            g.move(1, 300f, 1000f - 10f * i, t)
            g.move(2, 520f, 1010f - 10f * i, t)
            g.frame(t)
        }
        assertEquals(-100f, r.tilt, 0.01f)
        assertFalse(r.events.contains("transform"))
        g.up(1, 300f, 900f, t + 5)
        // The finger left behind pans on.
        drag(2, 520f, 910f, 520f, 1010f, t + 5, 5, 16)
        assertEquals(100f, r.panY, 0.01f)
    }

    @Test
    fun twoRestingFingersMeasure() {
        g.down(1, 200f, 700f, 0)
        g.down(2, 600f, 1300f, 30)
        assertEquals(30 + config.measureDelay, g.deadline)
        g.move(1, 202f, 701f, 200); g.frame(200)
        g.timeout(30 + config.measureDelay)
        assertTrue(g.isMeasuring)
        assertEquals(202f, r.measure!![0], 0f)
        g.move(2, 650f, 1350f, 500); g.frame(500)
        assertEquals(650f, r.measure!![2], 0f)
        assertFalse(r.events.contains("transform"))
        g.up(2, 650f, 1350f, 600)
        assertTrue(r.measureEnded)
        g.up(1, 202f, 701f, 610)
        assertTrue(g.isIdle)
        assertEquals(0f, r.panX, 0f)
    }

    @Test
    fun measuringCanBeTurnedOff() {
        g.config = GestureConfig.forDensity(2f, measure = false)
        g.down(1, 200f, 700f, 0)
        g.down(2, 600f, 1300f, 30)
        assertNull(g.deadline)
        g.timeout(1000)
        assertFalse(g.isMeasuring)
        assertNull(r.measure)
    }

    @Test
    fun movingFingersDoNotMeasure() {
        g.down(1, 200f, 700f, 0)
        g.down(2, 600f, 700f, 30)
        g.move(1, 150f, 700f, 100); g.move(2, 650f, 700f, 100); g.frame(100)
        assertNull(g.deadline)
        assertTrue(r.events.contains("transform"))
    }

    @Test
    fun cancelEndsTheGesture() {
        g.down(1, 100f, 100f, 0)
        drag(1, 100f, 100f, 200f, 100f, 0, 4, 16)
        g.cancel()
        assertTrue(g.isIdle)
        assertEquals("end", r.events.last())
    }

    @Test
    fun velocityTrackerFitsALine() {
        val v = VelocityTracker()
        for (i in 0..10) v.add(i * 10L, i * 20f, 5f)
        assertEquals(2000f, v.vx(), 1f)
        assertEquals(0f, abs(v.vy()), 1e-3f)
    }
}
