package app.atlas.mapview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import app.atlas.mapview.vk.VK_HONEST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The map's states around the core (plan-vector K11, T2), through the real [AtlasMap] on the JVM (no core here): where
 * the phone cannot run the core (the debug switch atlas.vk.off, a refused start) T2's honest state stands in the map's
 * place, today's map is never asked (it is no fallback), and the gestures still move the camera; while the core starts,
 * the map waits with its paper alone, no "Loading" and no request of today's map either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AtlasMapCoreTest {
    val compose = createComposeRule()
    private val threads = MapFrameThreads()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(threads).around(compose)

    @After
    fun canvasAgain() = MapEngine.coreStateForTests(CoreState.Canvas)

    private fun show(state: MapState, moves: MutableList<CameraPosition> = ArrayList()) {
        compose.setContent {
            AtlasMap(state, Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY), onCameraMove = { c, _ -> moves += c })
        }
        compose.waitForIdle()
        threads.track(state)
    }

    private fun stateDescription(): String =
        compose.onAllNodes(hasContentDescription("Map")).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.StateDescription) ?: ""

    @Test
    fun withoutAUsableCoreTheHonestStateStandsInTheMapsPlace() {
        MapEngine.coreStateForTests(CoreState.Off("the debug switch atlas.vk.off is on"))
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        val moves = ArrayList<CameraPosition>()
        show(state, moves)
        compose.onNodeWithText(VK_HONEST).assertExists()
        compose.onNodeWithText("the debug switch atlas.vk.off is on").assertExists()
        assertEquals("no map data: the debug switch atlas.vk.off is on", stateDescription())
        val frames = checkNotNull(state.controller).frames
        assertFalse("today's map is no fallback: never asked", frames.busy)
        assertEquals("", frames.stage)
        // The rest of the app works: the camera still moves under the fingers, and the screens hear it.
        val before = state.camera
        compose.onRoot().performTouchInput { swipe(Offset(412f, 1700f), Offset(412f, 1300f), 100) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("the camera moved", state.camera != before)
        assertTrue("the move was reported", moves.isNotEmpty())
        assertFalse(frames.busy)
    }

    @Test
    fun whileTheCoreStartsTheMapWaitsWithItsPaperAlone() {
        MapEngine.coreStateForTests(CoreState.Starting)
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        show(state)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val frames = checkNotNull(state.controller).frames
        assertFalse("no scene of today's map is loaded where the core will draw", frames.busy)
        assertEquals("", frames.stage)
        assertEquals(MapStatus.Loading(null), state.status)
        compose.onNodeWithText("Loading the map…").assertDoesNotExist()
        compose.onNodeWithText(VK_HONEST).assertDoesNotExist()
    }

    @Test
    fun withNoCoreInTheProcessAfterAllTodaysMapIsAskedAtOnce() {
        MapEngine.regions = null
        MapEngine.coreStateForTests(CoreState.Starting)
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        show(state)
        val frames = checkNotNull(state.controller).frames
        assertFalse(frames.busy)
        // The start found no core's library (MapEngine's start on the JVM): today's map alone, asked for its frame at
        // once, which answers with its own state (no regions are wired here: "no map data").
        MapEngine.coreStateForTests(CoreState.Canvas)
        // Today's map answers on its own thread, which Compose's idling does not see (as settleMap waits for it).
        val end = System.currentTimeMillis() + 60_000
        while (state.status !is MapStatus.NoData && System.currentTimeMillis() < end) {
            compose.waitForIdle()
            Thread.sleep(40)
        }
        assertEquals(MapStatus.NoData("no map data"), state.status)
        compose.onNodeWithText(VK_HONEST).assertDoesNotExist()
    }
}
