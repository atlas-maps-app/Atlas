package app.atlas.mapview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Gestures, taps, animations and following through the real [AtlasMap], with injected touches (no region data: the camera alone). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapGestureTest {
    val compose = createComposeRule()
    private val threads = MapFrameThreads()

    /** The compose rule's teardown closes the maps; their frame threads are waited for after it. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(threads).around(compose)

    private val hits = ArrayList<MapHit>()
    private val longPresses = ArrayList<LatLon>()
    private val moves = ArrayList<Pair<CameraPosition, Boolean>>()
    private lateinit var scope: CoroutineScope
    private var overlays by mutableStateOf(MapOverlays())

    private fun show(state: MapState, overlays: MapOverlays = MapOverlays(), settle: Boolean = true) {
        this.overlays = overlays
        compose.setContent {
            scope = rememberCoroutineScope()
            AtlasMap(
                state, Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY), this.overlays,
                onTap = { hits += it }, onLongPress = { longPresses += it }, onCameraMove = { c, u -> moves += c to u },
            )
        }
        compose.waitForIdle()
        threads.track(state)
        if (settle) compose.settleMap(state)
    }

    private fun stateAt(c: CameraPosition) = MapState(c).apply { time = Fixtures.afternoon }

    /** Lets the double-tap timeout pass and any animation finish. */
    private fun pass(ms: Long = 600) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun aDragMovesTheGroundWithTheFinger() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0))
        show(state)
        val ground = state.screenToLatLon(400f, 1300f)!!
        compose.onRoot().performTouchInput {
            down(Offset(400f, 1300f))
            for (i in 1..20) moveTo(Offset(400f + 10f * i, 1300f - 30f * i))
            // Rest before lifting: no fling.
            advanceEventTime(300)
            moveTo(Offset(600f, 700f))
            up()
        }
        pass()
        val now = state.screenToLatLon(600f, 700f)!!
        assertTrue("off by ${Geo.distance(ground, now)} m", Geo.distance(ground, now) < 2.0)
        assertTrue(moves.isNotEmpty() && moves.last().second)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun aFlingCoastsOn() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        val ground = state.screenToLatLon(412f, 1400f)!!
        compose.onRoot().performTouchInput { swipe(Offset(412f, 1400f), Offset(412f, 900f), 100) }
        pass(3000)
        // The ground went further than the finger did.
        val s = state.latLonToScreen(ground)!!
        assertTrue("at ${s.second}", s.second < 800f)
        assertEquals(412f, s.first, 3f)
    }

    @Test
    fun doubleTapZoomsInAroundTheTap() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 15.5))
        show(state)
        val ground = state.screenToLatLon(300f, 700f)!!
        compose.onRoot().performTouchInput { doubleClick(Offset(300f, 700f)) }
        pass()
        assertEquals(16.5, state.camera.zoom, 1e-6)
        assertTrue(Geo.distance(ground, state.screenToLatLon(300f, 700f)!!) < 1.0)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun twoFingerTapZoomsOut() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        compose.onRoot().performTouchInput {
            down(0, Offset(300f, 800f))
            down(1, Offset(520f, 1000f))
            advanceEventTime(60)
            up(0)
            up(1)
        }
        pass()
        assertEquals(15.0, state.camera.zoom, 1e-6)
    }

    @Test
    fun pinchZoomsByTheSpread() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 15.0))
        show(state)
        compose.onRoot().performTouchInput {
            pinch(Offset(312f, 900f), Offset(112f, 900f), Offset(512f, 900f), Offset(712f, 900f), 400)
        }
        pass()
        // The span went from 200 to 600 px: three times, log2(3) levels.
        assertEquals(15.0 + Math.log(3.0) / Math.log(2.0), state.camera.zoom, 0.02)
        assertEquals(0.0, state.camera.bearing, 1e-6)
    }

    @Test
    fun twoFingersTurnTheMapPastTheThreshold() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        compose.onRoot().performTouchInput {
            val c = Offset(412f, 900f)
            val r = 200f
            down(0, Offset(c.x - r, c.y))
            down(1, Offset(c.x + r, c.y))
            for (d in 1..60) {
                val a = Math.toRadians(d.toDouble())
                val dx = (r * cos(a)).toFloat(); val dy = (r * sin(a)).toFloat()
                updatePointerTo(0, Offset(c.x - dx, c.y - dy))
                updatePointerTo(1, Offset(c.x + dx, c.y + dy))
                move()
            }
            up(0)
            up(1)
        }
        pass()
        // 60° turned clockwise, the first 14° or so only unlocking the turn.
        val turned = -CameraOps.turn(0.0, state.camera.bearing)
        assertTrue("turned $turned", turned in 40.0..47.0)
    }

    @Test
    fun aSmallTurnSnapsBackToNorth() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, bearing = 3.0))
        show(state)
        compose.onRoot().performTouchInput {
            val c = Offset(412f, 900f)
            val r = 200f
            down(0, Offset(c.x - r, c.y))
            down(1, Offset(c.x + r, c.y))
            for (d in 1..19) {
                val a = Math.toRadians(d.toDouble())
                val dx = (r * cos(a)).toFloat(); val dy = (r * sin(a)).toFloat()
                updatePointerTo(0, Offset(c.x - dx, c.y - dy))
                updatePointerTo(1, Offset(c.x + dx, c.y + dy))
                move()
            }
            up(0)
            up(1)
        }
        pass()
        assertEquals(0.0, state.camera.bearing, 1e-6)
    }

    @Test
    fun twoFingersDraggedUpSlowlyTiltByAFewExactDegrees() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        val c = state.controller!!
        val feels = ArrayList<Int>()
        c.haptic = { feels += it }
        compose.onRoot().performTouchInput {
            down(0, Offset(300f, 1200f))
            down(1, Offset(520f, 1190f))
            // 1 px a 16 ms frame (31 dp a second) for 80 px: 40 dp
            for (i in 1..80) {
                updatePointerTo(0, Offset(300f, 1200f - i))
                updatePointerTo(1, Offset(520f, 1190f - i))
                move()
            }
        }
        compose.waitForIdle()
        assertTrue("the dial shows while the fingers tilt", c.dial != null)
        // fine: 40 dp at 0.08° a dp
        assertEquals(40 * TiltGain.FINE, state.camera.pitch, 0.05)
        assertEquals(16.0, state.camera.zoom, 1e-9)
        assertEquals(0.0, state.camera.bearing, 1e-9)
        assertEquals("a tick at each of 1°, 2° and 3°", listOf(TiltDial.DEGREE_FEEL, TiltDial.DEGREE_FEEL, TiltDial.DEGREE_FEEL), feels)
        compose.onRoot().performTouchInput { up(0); up(1) }
        pass(500)
        assertTrue("it lingers", c.dial != null)
        pass(1000)
        assertEquals(null, c.dial)
        assertEquals("a tilt this small is no 3D view to bring back", null, state.chosenTilt)
    }

    @Test
    fun aQuickTwoFingerSweepCrossesTheRange() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        val c = state.controller!!
        val feels = ArrayList<Int>()
        c.haptic = { feels += it }
        compose.onRoot().performTouchInput {
            down(0, Offset(300f, 1500f))
            down(1, Offset(520f, 1480f))
            // 20 px each 8 ms (1250 dp a second) for 400 px: 200 dp, more than the range takes
            for (i in 1..20) {
                updatePointerTo(0, Offset(300f, 1500f - 20f * i))
                updatePointerTo(1, Offset(520f, 1480f - 20f * i))
                move(8)
            }
            up(0)
            up(1)
        }
        pass()
        assertEquals(CameraOps.maxPitch(16.0), state.camera.pitch, 1e-9)
        assertEquals(16.0, state.camera.zoom, 1e-9)
        assertTrue("a click at the limit: $feels", feels.last() == TiltDial.LIMIT_FEEL)
        assertEquals("the 3D button's tilt from now on", CameraOps.maxPitch(16.0), state.chosenTilt!!, 1e-9)
    }

    @Test
    fun realTwoFingerTiltsThroughTheMap() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 35.0))
        show(state)
        val rng = java.util.Random(412)
        repeat(12) { n ->
            compose.runOnIdle { state.camera = CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 35.0) }
            pass(100)
            val up = n % 2 == 0
            val trace = TouchTraces.twoFingerTilt(rng, 824f, 1830f, up)
            replay(trace)
            pass(100)
            val p = state.camera
            assertTrue("$trace: pitch ${p.pitch}", if (up) p.pitch > 37.0 else p.pitch < 33.0)
            assertEquals("$trace", 16.0, p.zoom, 1e-9)
            assertEquals("$trace", 0.0, p.bearing, 1e-9)
            // the finger left after the first lifts pans on, by its jitter at most
            assertTrue("$trace", Geo.distance(p.target, Fixtures.westermarkt) < 5.0)
        }
    }

    @Test
    fun threeFingersTiltAtOnceAndTheDialGoesAfter() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        val c = state.controller!!
        compose.onRoot().performTouchInput {
            down(0, Offset(250f, 1200f))
            down(1, Offset(412f, 1170f))
            down(2, Offset(574f, 1195f))
        }
        compose.waitForIdle()
        assertTrue("three fingers show the dial at once", c.dial != null)
        compose.onRoot().performTouchInput {
            for (i in 1..20) {
                updatePointerTo(0, Offset(250f, 1200f - 3f * i))
                updatePointerTo(1, Offset(412f, 1170f - 3f * i))
                updatePointerTo(2, Offset(574f, 1195f - 3f * i))
                move()
            }
            up(1)
            up(0)
            up(2)
        }
        compose.waitForIdle()
        assertTrue("pitch ${state.camera.pitch}", state.camera.pitch in 2.0..20.0)
        assertEquals(16.0, state.camera.zoom, 1e-9)
        assertEquals(0.0, state.camera.bearing, 1e-9)
        pass(2000)
        assertEquals(null, c.dial)
        assertTrue(c.gestures.isIdle)
    }

    @Test
    fun aCancelledTiltPutsThePitchBack() {
        val start = CameraPosition(Fixtures.westermarkt, 16.0, 20.0, 30.0)
        val state = stateAt(start)
        show(state)
        compose.onRoot().performTouchInput {
            down(0, Offset(250f, 700f))
            down(1, Offset(412f, 670f))
            down(2, Offset(574f, 695f))
            // three fingers swept down, as an OEM's screenshot gesture starts, and the system takes them
            for (i in 1..12) {
                updatePointerTo(0, Offset(250f, 700f + 25f * i))
                updatePointerTo(1, Offset(412f, 670f + 25f * i))
                updatePointerTo(2, Offset(574f, 695f + 25f * i))
                move(8)
            }
        }
        compose.waitForIdle()
        assertTrue("it tilted: ${state.camera.pitch}", state.camera.pitch < 25.0)
        compose.onRoot().performTouchInput { cancel() }
        pass(2000)
        assertEquals(start, state.camera)
        val c = state.controller!!
        assertTrue(c.gestures.isIdle)
        assertEquals(null, c.dial)
        // and the map takes the next gesture as ever
        compose.onRoot().performTouchInput { doubleClick(Offset(300f, 900f)) }
        pass()
        assertEquals(17.0, state.camera.zoom, 1e-6)
    }

    @Test
    fun theThreeDButtonsTiltShowsOnTheDial() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        val c = state.controller!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { state.tiltTo(38.0) } }
        compose.mainClock.advanceTimeBy(300)
        val shown = compose.runOnIdle { c.dial }
        assertTrue("the dial shows the glide", shown != null)
        assertTrue("on the right, by the map's controls: ${shown!!.left}", shown.left > 824f / 2)
        val mid = compose.runOnIdle { state.camera.pitch }
        assertTrue("mid-glide $mid", mid > 0.0 && mid < 38.0)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(38.0, state.camera.pitch, 1e-9)
        pass(2000)
        assertEquals(null, c.dial)
        compose.runOnIdle { scope.launch { state.tiltTo(0.0) } }
        pass(2000)
        assertEquals(0.0, state.camera.pitch, 1e-9)
        assertEquals(null, c.dial)
    }

    @Test
    fun followingTheCourseKeepsTheUsersOwnTilt() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        val phone = LatLon(52.3722, 4.8870)
        show(state, MapOverlays(location = UserLocation(phone, 10.0, heading = 120.0)))
        compose.runOnIdle { state.follow = Follow.COURSE }
        pass(1500)
        assertTrue(state.camera.pitch >= MapController.COURSE_PITCH - 0.01)
        // three fingers tilt it down to look nearer at the map
        compose.onRoot().performTouchInput {
            down(0, Offset(250f, 700f))
            down(1, Offset(412f, 670f))
            down(2, Offset(574f, 695f))
            for (i in 1..10) {
                updatePointerTo(0, Offset(250f, 700f + 15f * i))
                updatePointerTo(1, Offset(412f, 670f + 15f * i))
                updatePointerTo(2, Offset(574f, 695f + 15f * i))
                move()
            }
            up(0); up(1); up(2)
        }
        pass()
        val mine = state.camera.pitch
        assertTrue("tilted to $mine", mine < MapController.COURSE_PITCH - 5)
        assertEquals("still following", Follow.COURSE, state.follow)
        // the next fix moves the camera along, keeping that tilt
        val next = LatLon(52.3718, 4.8880)
        compose.runOnIdle { overlays = MapOverlays(location = UserLocation(next, 10.0, heading = 125.0)) }
        pass(1500)
        assertTrue(Geo.distance(state.camera.target, next) < 0.5)
        assertEquals(mine, state.camera.pitch, 1e-6)
    }

    /** Plays [trace] into the map as its touches, at their own times. */
    private fun replay(trace: TouchTraces.Trace) {
        compose.onRoot().performTouchInput {
            var now = 0L
            for (e in trace.events) {
                if (e.time > now) advanceEventTime(e.time - now)
                now = e.time
                var moved = false
                for (t in e.touches) {
                    val at = Offset(t.x, t.y)
                    when (t.kind) {
                        TouchTraces.Kind.DOWN -> down(t.id.toInt(), at)
                        TouchTraces.Kind.MOVE -> { updatePointerTo(t.id.toInt(), at); moved = true }
                        TouchTraces.Kind.UP -> { updatePointerTo(t.id.toInt(), at); up(t.id.toInt()) }
                    }
                }
                if (moved && e.touches.none { it.kind != TouchTraces.Kind.MOVE }) move(0)
            }
        }
    }

    @Test
    fun tapsFindMarkersThenGround() {
        val camera = CameraPosition(Fixtures.westermarkt, 17.0)
        val state = stateAt(camera)
        val v = Viewport(824, 1830, 2f)
        val pin = Marker("pin", CameraOps.groundAt(camera, v, 412f, 600f)!!, "cafe")
        show(state, MapOverlays(markers = listOf(pin)))
        // The pin's disc stands above its anchor.
        compose.onRoot().performTouchInput { click(Offset(412f, 600f - 40f)) }
        pass()
        assertEquals(MapHit.OnMarker(pin, pin.at), hits.single())
        // Bare ground somewhere else (the engine's own places are not hit-tested yet).
        compose.onRoot().performTouchInput { click(Offset(100f, 1700f)) }
        pass()
        val last = hits.last()
        assertTrue(last is MapHit.Empty)
        assertTrue(Geo.distance(last.at, state.screenToLatLon(100f, 1700f)!!) < 0.5)
    }

    @Test
    fun aLongPressReportsTheGround() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 30.0))
        show(state)
        compose.onRoot().performTouchInput { longClick(Offset(500f, 1100f), 700) }
        pass()
        assertEquals(1, longPresses.size)
        assertTrue(Geo.distance(longPresses[0], state.screenToLatLon(500f, 1100f)!!) < 0.5)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun animateToAndFitMoveTheCamera() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 15.0))
        show(state)
        val target = CameraPosition(LatLon(52.3702, 4.8952), 17.0, 45.0, 30.0)
        // Frame by frame: halfway there the camera is between the two.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { scope.launch { state.animateTo(target, 600) } }
        compose.mainClock.advanceTimeBy(300)
        val mid = compose.runOnIdle { state.camera }
        assertTrue("mid-flight $mid", mid != target && Geo.distance(mid.target, Fixtures.westermarkt) > 10.0)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(target, state.camera)
        assertTrue(moves.last().first == target && !moves.last().second)
        val box = BBox(4.880, 52.370, 4.886, 52.376)
        compose.runOnIdle { scope.launch { state.fit(box) } }
        pass(1000)
        val b = state.visibleBounds!!
        assertTrue(b.west <= box.west && b.east >= box.east && b.south <= box.south && b.north >= box.north)
    }

    @Test
    fun followModesTrackThePhone() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        val phone = LatLon(52.3722, 4.8870)
        show(state, MapOverlays(location = UserLocation(phone, 10.0, heading = 120.0)))
        compose.runOnIdle { state.follow = Follow.POSITION }
        pass(1500)
        assertTrue(Geo.distance(state.camera.target, phone) < 0.5)
        assertEquals(0.0, state.camera.bearing, 1e-6)
        compose.runOnIdle { state.follow = Follow.HEADING }
        pass(1500)
        assertEquals(120.0, state.camera.bearing, 0.01)
        compose.runOnIdle { state.follow = Follow.COURSE }
        pass(1500)
        assertTrue(state.camera.pitch >= MapController.COURSE_PITCH - 0.01)
        // Looking ahead: the phone sits low on the screen.
        val s = state.latLonToScreen(phone)!!
        assertTrue("puck at ${s.second}", s.second > 1830 * 0.6f)
        // A new fix moves the camera along.
        val next = LatLon(52.3718, 4.8880)
        compose.runOnIdle { overlays = MapOverlays(location = UserLocation(next, 10.0, heading = 125.0)) }
        pass(1500)
        assertTrue(Geo.distance(state.camera.target, next) < 0.5)
        // Moving, the map turns with the course the fixes make, not the compass.
        assertEquals(Geo.bearing(phone, next), state.camera.bearing, 0.5)
        // Panning hands the camera back to the user.
        compose.onRoot().performTouchInput {
            down(Offset(400f, 900f))
            for (i in 1..10) moveTo(Offset(400f, 900f + 20f * i))
            up()
        }
        pass()
        assertEquals(Follow.NONE, state.follow)
    }

    @Test
    fun theCourseIgnoresGpsWanderWhileStandingStill() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        val phone = LatLon(52.3722, 4.8870)
        show(state, MapOverlays(location = UserLocation(phone, 15.0, heading = 30.0)))
        compose.runOnIdle { state.follow = Follow.COURSE }
        pass(1500)
        // Not moving yet: the compass.
        assertEquals(0.0, CameraOps.turn(30.0, state.camera.bearing), 0.5)
        // The fix wanders 6 m east, well within its 15 m accuracy, while the phone stands still.
        val wander = Geo.destination(phone, 90.0, 6.0)
        compose.runOnIdle { overlays = MapOverlays(location = UserLocation(wander, 15.0, heading = 30.0, speed = 0.1)) }
        pass(1500)
        assertEquals(0.0, CameraOps.turn(30.0, state.camera.bearing), 0.5)
        // Walking 40 m north-west: the map turns with the way the phone goes.
        val walked = Geo.destination(phone, 315.0, 40.0)
        compose.runOnIdle { overlays = MapOverlays(location = UserLocation(walked, 15.0, heading = 30.0, speed = 1.4)) }
        pass(1500)
        assertEquals(0.0, CameraOps.turn(Geo.bearing(phone, walked), state.camera.bearing), 1.0)
    }

    @Test
    fun theCompassTurnsTheMapNorthUp() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, 140.0, 30.0))
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                AtlasMap(state, Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY))
                MapCompass(state, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
            }
        }
        compose.waitForIdle()
        threads.track(state)
        compose.settleMap(state)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Compass", substring = true)).performClick()
        pass(1000)
        assertEquals(0.0, state.camera.bearing, 1e-6)
        assertEquals(30.0, state.camera.pitch, 1e-6)
    }

    @Test
    fun theTapeMeasuresBetweenTwoRestingFingersAndThenGoes() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        compose.onRoot().performTouchInput {
            down(0, Offset(200f, 700f))
            down(1, Offset(600f, 1300f))
        }
        pass(500)
        val tape = state.controller!!.tape
        assertNotNull(tape)
        assertEquals(state.camera, CameraPosition(Fixtures.westermarkt, 16.0))
        compose.onRoot().performTouchInput { up(0); up(1) }
        pass(1000)
        assertNotNull("it lingers", state.controller!!.tape)
        pass(3000)
        assertEquals(null, state.controller!!.tape)
    }
}
