package app.atlas.mapview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's one map ([MapStage]): a screen that opens over the map (a long press's point, a place) takes on the one
 * shown, its camera, look and padding, and the map is not rebuilt (the user, 1 November: "when long pressing it shouldnt
 * open that view it should stay on the map").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapStageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aScreenOverTheMapTakesOnTheOneShownAndNothingIsRebuilt() {
        val stage = MapStage()
        val home = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        val look = MapStyle(theme = Theme.DAY, calm = true)
        val padding = PaddingValues(top = 120.dp, bottom = 300.dp)
        // 0 the home's map, 1 a screen over it, 2 a screen without a map
        var screen by mutableStateOf(0)
        var over: ShownMap? = null
        assertNull(stage.shown())
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MapStageHost(stage, cover = Color.White)
                CompositionLocalProvider(LocalMapStage provides stage) {
                    when (screen) {
                        0 -> StageMap(home, Modifier.fillMaxSize(), style = look, contentPadding = padding)
                        1 -> {
                            val shown = remember { stage.shown() }
                            over = shown
                            if (shown != null) StageMap(shown.state, Modifier.fillMaxSize(), style = shown.style, contentPadding = shown.contentPadding)
                        }
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
        val controller = home.controller
        assertNotNull(controller)
        val camera = home.camera

        compose.runOnIdle { screen = 1 }
        compose.waitForIdle()
        val shown = over!!
        assertSame(home, shown.state)
        assertEquals(look, shown.style)
        assertEquals(padding, shown.contentPadding)
        // the same map drawing, where it was
        assertSame(controller, home.controller)
        assertEquals(camera, home.camera)

        // a screen without a map keeps the map under its cover, and one over that takes it on again
        compose.runOnIdle { screen = 2 }
        compose.waitForIdle()
        assertSame(home, stage.shown()?.state)
        compose.runOnIdle { screen = 1 }
        compose.waitForIdle()
        assertSame(home, over?.state)
        assertSame(controller, home.controller)
        assertEquals(camera, home.camera)
    }

    @Test
    fun aScreenWithItsOwnCameraKeepsTheOneMap() {
        val stage = MapStage()
        val home = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 30.0, 40.0)).apply { time = Fixtures.afternoon }
        // a view of its own (the fill-in's of a building): close up, steeper, north up
        val own = MapState(CameraPosition(Fixtures.westermarkt, 18.2, 0.0, 55.0)).apply { time = Fixtures.afternoon }
        var screen by mutableStateOf(0)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MapStageHost(stage, cover = Color.White)
                CompositionLocalProvider(LocalMapStage provides stage) {
                    if (screen == 0) StageMap(home, Modifier.fillMaxSize()) else StageMap(own, Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        val controller = home.controller
        assertNotNull(controller)
        val camera = home.camera

        // the screen's camera drawn by the same map (the user, 1 November: "There is too much re loading the map"): the
        // state handed over, its view drawn at once, the home's left as it was
        compose.runOnIdle { screen = 1 }
        compose.waitForIdle()
        assertSame(controller, own.controller)
        assertNull(home.controller)
        assertSame(own, controller!!.state)
        assertNotNull(own.visibleBounds)
        assertEquals(camera, home.camera)

        // and back: the home's camera, where the user left it, on the same map
        compose.runOnIdle { screen = 0 }
        compose.waitForIdle()
        assertSame(controller, home.controller)
        assertNull(own.controller)
        assertEquals(camera, home.camera)
    }
}
