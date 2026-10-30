package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * The gesture state machine, fed with synthetic pointer streams at a phone's density (2.0), and with the touch streams
 * of [TouchTraces] at the Pixel's (2.625): fingers landing apart, lagging, drifting and jittering as real ones do.
 */
class GestureMachineTest {
    private class Recorder : GestureListener {
        val events = ArrayList<String>()
        var panX = 0f
        var panY = 0f
        var scale = 1f
        var rotation = 0f
        var tilt = 0f
        var tiltStarts = 0
        var tiltFingers = 0
        var tiltEnd: Boolean? = null
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
        override fun onTiltStart(fingers: Int) { tiltStarts++; tiltFingers = fingers; events += "tiltStart" }
        override fun onTilt(dy: Float) { tilt += dy; events += "tilt" }
        override fun onTiltEnd(cancelled: Boolean) { tiltEnd = cancelled; events += "tiltEnd" }
        override fun onZoomStep(x: Float, y: Float, zoomIn: Boolean) { zoomStep = Triple(x, y, zoomIn); events += "zoomStep" }
        override fun onDragZoom(x: Float, y: Float, dy: Float) { dragZoom += dy; events += "dragZoom" }
        override fun onFling(vx: Float, vy: Float) { fling = vx to vy; events += "fling" }
        override fun onTap(x: Float, y: Float) { tap = x to y; events += "tap" }
        override fun onLongPress(x: Float, y: Float) { longPress = x to y; events += "longPress" }
        override fun onMeasure(x0: Float, y0: Float, x1: Float, y1: Float) { measure = floatArrayOf(x0, y0, x1, y1); events += "measure" }
        override fun onMeasureEnd() { measureEnded = true; events += "measureEnd" }
        override fun onGestureEnd(rotated: Boolean) { this.rotated = rotated; events += "end" }

        val tilted get() = "tilt" in events
        val transformed get() = "transform" in events
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
    fun twoFingersThatMovedAndLiftQuicklyAreNoTap() {
        g.down(1, 300f, 800f, 0)
        g.down(2, 520f, 800f, 20)
        // the first finger alone moves on past the slop: not yet a tilt, nor anything else
        g.move(1, 300f, 780f, 60); g.frame(60)
        g.up(1, 300f, 780f, 100)
        g.up(2, 520f, 800f, 110)
        assertNull(r.zoomStep)
        assertFalse(r.tilted)
        assertTrue(g.isIdle)
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
        assertTrue(r.transformed)
        assertFalse(r.tilted)
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
        assertEquals("their whole move since the second finger's landing", -100f, r.tilt, 0.01f)
        assertFalse(r.transformed)
        assertEquals(2, r.tiltFingers)
        assertTrue(g.isTilting)
        // where they are, for the readout
        val at = FloatArray(6)
        assertEquals(2, g.fingers(at))
        assertEquals(listOf(300f, 900f, 520f, 910f), at.take(4))
        g.up(1, 300f, 900f, t + 5)
        assertEquals(false, r.tiltEnd)
        // The finger left behind pans on.
        drag(2, 520f, 910f, 520f, 1010f, t + 5, 5, 16)
        assertEquals(100f, r.panY, 0.01f)
    }

    @Test
    fun theFingerLeftOfATiltNeitherPansNorFlingsOnItsJitter() {
        g.down(1, 300f, 1000f, 0)
        g.down(2, 520f, 1010f, 10)
        var t = 10L
        for (i in 1..6) {
            t += 8
            g.move(1, 300f, 1000f - 20f * i, t)
            g.move(2, 520f, 1010f - 20f * i, t)
            g.frame(t)
        }
        assertTrue(r.tilted)
        g.up(1, 300f, 880f, t + 8)
        // the other jitters a pixel either way, quickly, and lifts
        g.move(2, 521f, 890f, t + 12); g.frame(t + 12)
        g.move(2, 519f, 891f, t + 16); g.frame(t + 16)
        g.up(2, 521f, 889f, t + 20)
        assertEquals(0f, r.panX, 0f)
        assertEquals(0f, r.panY, 0f)
        assertNull(r.fling)
        assertTrue(g.isIdle)
    }

    @Test
    fun aLaggingFingerWaitsForTheOtherThenTiltsWithAllOfIt() {
        g.down(1, 300f, 1000f, 0)
        g.down(2, 560f, 960f, 30)
        // the first finger leads by four frames; the second follows
        var t = 30L
        for (i in 1..14) {
            t += 8
            g.move(1, 300f + 0.3f * i, 1000f - 6f * i, t)
            if (i > 4) g.move(2, 560f - 0.2f * i, 960f - 6f * (i - 4), t)
            g.frame(t)
            if (i <= 4) assertFalse("one finger ahead is no pinch or turn yet", r.transformed || r.tilted)
        }
        assertTrue(r.tilted)
        assertFalse(r.transformed)
        // nothing of the move was lost while it waited
        assertEquals(-(84f + 60f) / 2, r.tilt, 0.01f)
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
        assertFalse(r.transformed)
        g.up(2, 650f, 1350f, 600)
        assertTrue(r.measureEnded)
        g.up(1, 202f, 701f, 610)
        assertTrue(g.isIdle)
        assertEquals(0f, r.panX, 0f)
    }

    @Test
    fun theTapeFollowsOneFingerAndGivesWayToBothDraggedUp() {
        g.down(1, 300f, 1200f, 0)
        g.down(2, 560f, 1180f, 30)
        g.timeout(30 + config.measureDelay)
        assertTrue(g.isMeasuring)
        // A slop up together is still the tape, moving with the fingers.
        g.move(1, 300f, 1186f, 500); g.move(2, 560f, 1166f, 500); g.frame(500)
        assertTrue(g.isMeasuring)
        assertFalse(r.tilted)
        // Two slops up together: they tilt, and the tape goes.
        g.move(1, 301f, 1166f, 520); g.move(2, 561f, 1146f, 520); g.frame(520)
        assertTrue(g.isTilting)
        assertTrue(r.measureEnded)
        assertTrue(r.events.indexOf("measureEnd") < r.events.indexOf("tiltStart"))
        assertEquals(-34f, r.tilt, 0.01f)
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
        assertTrue(r.transformed)
    }

    @Test
    fun threeFingersTiltFromTheirFirstPixel() {
        g.down(1, 300f, 1200f, 0)
        g.down(2, 450f, 1170f, 15)
        g.down(3, 600f, 1195f, 30)
        assertTrue("three fingers are a tilt at once", g.isTilting)
        assertEquals(3, r.tiltFingers)
        assertNull("no tape for three", g.deadline)
        // three pixels up between them, and the map tilts by as much
        g.move(1, 300f, 1197f, 38); g.move(2, 451f, 1167f, 38); g.move(3, 600f, 1192f, 38); g.frame(38)
        assertEquals(-3f, r.tilt, 0.01f)
        // a fourth finger is ignored
        g.down(4, 700f, 900f, 40)
        g.move(4, 700f, 600f, 46); g.frame(46)
        assertEquals(-3f, r.tilt, 0.01f)
        // one lifts: the other two tilt on
        g.up(2, 451f, 1167f, 60)
        assertTrue(g.isTilting)
        assertNull(r.tiltEnd)
        g.move(1, 300f, 1187f, 68); g.move(3, 600f, 1182f, 68); g.frame(68)
        assertEquals(-13f, r.tilt, 0.01f)
        // another lifts: the tilt is over, the last finger pans
        g.up(1, 300f, 1187f, 80)
        assertEquals(false, r.tiltEnd)
        g.move(3, 600f, 1232f, 90); g.frame(90)
        assertEquals(50f, r.panY, 0.01f)
        assertFalse(r.transformed)
        assertNull(r.zoomStep)
    }

    @Test
    fun aThirdFingerTurnsAPinchOrTheTapeIntoATilt() {
        g.down(1, 300f, 900f, 0)
        g.down(2, 500f, 900f, 10)
        drag(2, 500f, 900f, 560f, 900f, 10, 4, 16)
        assertTrue(r.transformed)
        g.down(3, 400f, 1100f, 100)
        assertTrue(g.isTilting)
        g.up(1, 300f, 900f, 120); g.up(2, 560f, 900f, 121); g.up(3, 400f, 1100f, 122)
        assertTrue(g.isIdle)

        val m = Recorder()
        val h = GestureMachine(config, m)
        h.down(1, 200f, 700f, 0)
        h.down(2, 600f, 1300f, 30)
        h.timeout(30 + config.measureDelay)
        assertTrue(h.isMeasuring)
        h.down(3, 400f, 1000f, 600)
        assertTrue(m.measureEnded)
        assertTrue(h.isTilting)
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
    fun aCancelledTiltSaysSo() {
        g.down(1, 300f, 1200f, 0)
        g.down(2, 450f, 1170f, 15)
        g.down(3, 600f, 1195f, 30)
        g.move(1, 300f, 1300f, 60); g.move(2, 450f, 1270f, 60); g.move(3, 600f, 1295f, 60); g.frame(60)
        g.cancel()
        assertEquals(true, r.tiltEnd)
        assertTrue(g.isIdle)
        assertEquals(listOf("tiltEnd", "end"), r.events.takeLast(2))
        // and the next touch starts afresh
        g.down(1, 100f, 100f, 200)
        g.up(1, 100f, 100f, 260)
        g.timeout(260 + config.doubleTapTimeout)
        assertEquals(100f to 100f, r.tap)
    }

    @Test
    fun velocityTrackerFitsALine() {
        val v = VelocityTracker()
        for (i in 0..10) v.add(i * 10L, i * 20f, 5f)
        assertEquals(2000f, v.vx(), 1f)
        assertEquals(0f, abs(v.vy()), 1e-3f)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Real fingers, as TouchTraces makes them, on the Pixel's screen (1080 × 2424 at 2.625).

    private val phone = GestureConfig.forDensity(PIXEL)

    private fun played(trace: TouchTraces.Trace): Recorder {
        val rec = Recorder()
        TouchTraces.play(GestureMachine(phone, rec), trace)
        return rec
    }

    @Test
    fun realTwoFingerTiltsTiltByAllTheirMove() {
        val rng = Random(20261030)
        repeat(400) { n ->
            val trace = TouchTraces.twoFingerTilt(rng, W, H, up = n % 2 == 0)
            val rec = played(trace)
            assertTrue("$trace: ${rec.events.distinct()}", rec.tilted && !rec.transformed && rec.measure == null)
            // from the second finger's landing to the first lift (they rest before it)
            val second = trace.fingers.maxOf { it.down }
            val first = trace.fingers.minOf { it.up }
            val moved = trace.meanDy(second, first)
            assertEquals("$trace", moved, rec.tilt, 4f + 0.02f * abs(moved))
            assertEquals(false, rec.tiltEnd)
        }
    }

    @Test
    fun realTwoFingerTiltsAfterTheTapeShows() {
        val rng = Random(31)
        repeat(100) { n ->
            val trace = TouchTraces.twoFingerTilt(rng, W, H, up = n % 2 == 0, rest = 450L + rng.nextInt(400))
            val rec = played(trace)
            assertTrue("$trace: ${rec.events.distinct()}", rec.measure != null && rec.measureEnded && rec.tilted && !rec.transformed)
            // the tape gave way within the first two slops and a little; the tilt has the rest, and those too
            val second = trace.fingers.maxOf { it.down }
            val moved = trace.meanDy(second, trace.fingers.minOf { it.up })
            assertEquals("$trace", moved, rec.tilt, 4f + 0.02f * abs(moved))
        }
    }

    @Test
    fun realThreeFingerTiltsTiltFromTheThirdFinger() {
        val rng = Random(3)
        repeat(200) { n ->
            val trace = TouchTraces.threeFingerTilt(rng, W, H, up = n % 2 == 0)
            val rec = played(trace)
            assertTrue("$trace: ${rec.events.distinct()}", rec.tilted && !rec.transformed && rec.tiltFingers == 3)
            val third = trace.fingers.maxOf { it.down }
            // the three fingers' mean move from the third's landing until the first lifts, the two's after it until the
            // next does
            val ups = trace.fingers.map { it.up }.sorted()
            val three = trace.meanDy(third, ups[0])
            assertTrue("$trace: tilted ${rec.tilt} of $three", abs(rec.tilt - three) <= 6f + 0.03f * abs(three))
        }
    }

    @Test
    fun whatElseTwoRealFingersDoIsNoTilt() {
        val rng = Random(1030)
        for (kind in TouchTraces.Other.entries) {
            repeat(300) {
                val trace = TouchTraces.other(rng, W, H, kind)
                val rec = played(trace)
                assertFalse("$trace: ${rec.events.distinct()}", rec.tilted)
                // fingers that start slowly after a rest are still within the slop at 400 ms: the tape, as ever
                if (rec.measure != null) return@repeat
                assertTrue("$trace: ${rec.events.distinct()}", rec.transformed)
                val a = trace.fingers[0]
                val b = trace.fingers[1]
                val start = maxOf(a.down, b.down)
                val end = minOf(a.up, b.up)
                fun span(t: Long) = hypotOf(a.at(t), b.at(t))
                when (kind) {
                    TouchTraces.Other.PINCH, TouchTraces.Other.ANCHORED_PINCH -> {
                        val want = span(end) / span(start)
                        assertEquals("$trace: zoom", ln(want.toDouble()), ln(rec.scale.toDouble()), 0.06)
                    }
                    TouchTraces.Other.TWIST, TouchTraces.Other.ANCHORED_TURN -> {
                        val turned = TouchTraces.turnOf(
                            floatArrayOf(a.at(start).first, a.at(start).second, b.at(start).first, b.at(start).second),
                            floatArrayOf(a.at(end).first, a.at(end).second, b.at(end).first, b.at(end).second),
                        )
                        // the first 14° unlock the turn; the rest turns the map
                        assertEquals("$trace: turn", turned - config.rotateThreshold, abs(rec.rotation).toDouble(), 6.0)
                        assertEquals(true, rec.rotated)
                    }
                    TouchTraces.Other.PAN -> {
                        val dx = (a.at(end).first - a.at(start).first + b.at(end).first - b.at(start).first) / 2
                        val dy = (a.at(end).second - a.at(start).second + b.at(end).second - b.at(start).second) / 2
                        assertEquals("$trace: pan x", dx, rec.panX, 6f)
                        assertEquals("$trace: pan y", dy, rec.panY, 6f)
                    }
                }
            }
        }
    }

    private fun hypotOf(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.hypot(b.first - a.first, b.second - a.second)

    private companion object {
        const val PIXEL = 2.625f
        const val W = 1080f
        const val H = 2424f
    }
}
