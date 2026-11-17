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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The map's states around the core (plan-vector K11, T2), through the real [AtlasMap] on the JVM (no core here): where
 * the phone cannot run the core (the debug switch atlas.vk.off, a refused start) T2's honest state stands in the map's
 * place, and the gestures still move the camera; while the core starts, the map waits with its paper alone, no
 * "Loading"; with no core in the process after all, the map says so (no other map draws: today's map left the phone
 * on 5 November).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AtlasMapCoreTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun noCoreAgain() = MapEngine.coreStateForTests(CoreState.None)

    private fun show(state: MapState, moves: MutableList<CameraPosition> = ArrayList()) {
        compose.setContent {
            AtlasMap(state, Modifier.fillMaxSize(), MapStyle(theme = Theme.DAY), onCameraMove = { c, _ -> moves += c })
        }
        compose.waitForIdle()
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
        // The rest of the app works: the camera still moves under the fingers, and the screens hear it.
        val before = state.camera
        compose.onRoot().performTouchInput { swipe(Offset(412f, 1700f), Offset(412f, 1300f), 100) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue("the camera moved", state.camera != before)
        assertTrue("the move was reported", moves.isNotEmpty())
    }

    @Test
    fun whileTheCoreStartsTheMapWaitsWithItsPaperAlone() {
        MapEngine.coreStateForTests(CoreState.Starting)
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        show(state)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(MapStatus.Loading(null), state.status)
        compose.onNodeWithText("Loading the map…").assertDoesNotExist()
        compose.onNodeWithText(VK_HONEST).assertDoesNotExist()
    }

    @Test
    fun withNoCoreInTheProcessAfterAllTheMapSaysSo() {
        MapEngine.coreStateForTests(CoreState.Starting)
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        show(state)
        assertEquals(MapStatus.Loading(null), state.status)
        // The start found no core's library (MapEngine's start on the JVM): the paper and the overlays alone, and the
        // map's status says why.
        MapEngine.coreStateForTests(CoreState.None)
        compose.waitForIdle()
        assertEquals(MapStatus.NoData(MapController.NO_CORE), state.status)
        compose.onNodeWithText(VK_HONEST).assertDoesNotExist()
    }
}
