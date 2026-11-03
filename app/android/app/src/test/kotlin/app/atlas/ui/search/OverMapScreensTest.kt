package app.atlas.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.atlas.Services
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapStage
import app.atlas.mapview.MapStageHost
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.StageMap
import app.atlas.mapview.Theme
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.services.SettingsKeys
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens with places on the map keep the one map as they open and close (the user, 1 November: "There is too much
 * re loading the map"): a search's places, a category's and a brand's, the eat map, charging and the drop-off points
 * take on the map on screen, its camera and look, and glide it; the fill-in keeps its own view of the building on that
 * map ([app.atlas.mapview.MapStageTest]: the same map draws it), in the user's look rather than the live sky, and the
 * quests' street card is that map too, at the card's place with its own camera, rather than a second map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OverMapScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screensWithPlacesTakeOnTheMapOnScreen() {
        val services = testServices(places = true)
        runBlocking { services.settings.edit { it[SettingsKeys.MAP_THEME] = Theme.DAY.name } }
        val stage = MapStage()
        val home = MapState(CameraPosition(Services.WESTERKERK, 16.0, 30.0, 40.0))
        // the home's look: the user's sky, its own markers for places (the tiles' points of interest off)
        val look = MapStyle(theme = Theme.DAY, layers = MapStyle().layers - MapLayer.POIS, calm = true)
        val nav = BackStackNavigator(listOf(Dest.Home()))
        var screen by mutableStateOf<Dest?>(null)
        compose.setContent {
            ProvideServices(services) {
                Box(Modifier.fillMaxSize()) {
                    MapStageHost(stage, cover = Color.White)
                    CompositionLocalProvider(LocalMapStage provides stage) {
                        when (val d = screen) {
                            null -> StageMap(home, Modifier.fillMaxSize(), style = look, contentPadding = PaddingValues(top = 120.dp, bottom = 300.dp))
                            else -> d.Content(nav)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val over = listOf(
            Dest.Query("cafe near bicycle rental 200m"), Dest.Category("drinks"), Dest.Brand("albertheijn-1cf55d"),
            Dest.Eat("map"), Dest.Charge(), Dest.Groceries("drop"),
        )
        for (d in over) {
            compose.runOnIdle { screen = d }
            compose.waitForIdle()
            // the home's own state: its camera goes on from where the user left it, in the home's look
            val shown = checkNotNull(stage.shown()) { "no map under $d" }
            assertSame("$d", home, shown.state)
            assertEquals("$d", look, shown.style)
            compose.runOnIdle { screen = null }
            compose.waitForIdle()
            assertSame("back from $d", home, stage.shown()?.state)
        }

        // the fill-in's own view of the building, in the user's look with the layers that view needs
        compose.runOnIdle { screen = Dest.FillIn("way", 1L, Services.WESTERKERK.lat, Services.WESTERKERK.lon) }
        compose.waitForIdle()
        assertNotSame("the fill-in's own map", home, stage.shown()?.state)
        // the user's look comes with the settings' first read: the live sky (AUTO) until then
        compose.waitUntil(30_000) { stage.shown()?.style?.theme == Theme.DAY }
        assertEquals(MapStyle(theme = Theme.DAY, calm = true), stage.shown()?.style)
        compose.runOnIdle { screen = null }
        compose.waitForIdle()
        assertSame(home, stage.shown()?.state)

        // the quests' street card: the one map at the card's place, with the card's own camera
        compose.runOnIdle { screen = Dest.Quests() }
        compose.waitForIdle()
        assertNotSame("the quests' card", home, stage.shown()?.state)
        compose.runOnIdle { screen = null }
        compose.waitForIdle()
        assertSame(home, stage.shown()?.state)
    }
}
