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
    @get:Rule
    val compose = createComposeRule()

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
                state, FixtureTiles(), Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY), this.overlays,
                onTap = { hits += it }, onLongPress = { longPresses += it }, onCameraMove = { c, u -> moves += c to u },
            )
        }
        if (settle) compose.settleMap(state) else compose.waitForIdle()
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
    fun twoFingersDraggedUpTilt() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0))
        show(state)
        compose.onRoot().performTouchInput {
            down(0, Offset(300f, 1200f))
            down(1, Offset(520f, 1200f))
            for (i in 1..20) {
                updatePointerTo(0, Offset(300f, 1200f - 10f * i))
                updatePointerTo(1, Offset(520f, 1200f - 10f * i))
                move()
            }
            up(0)
            up(1)
        }
        pass()
        // 200 px is 100 dp, at 0.32° a dp.
        assertEquals(32.0, state.camera.pitch, 0.5)
        assertEquals(16.0, state.camera.zoom, 1e-6)
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
                AtlasMap(state, FixtureTiles(), Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY))
                MapCompass(state, Modifier.align(androidx.compose.ui.Alignment.TopEnd))
            }
        }
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
