package app.atlas.ui.home

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.atlas.places.Categories
import app.atlas.providers.Sky
import app.atlas.ui.kit.CurrentWeather
import app.atlas.testing.shotsDir
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Map home on the fixture tiles at the Westerkerk: as it opens, with a category's results, and at night. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String): File {
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    /** The kept forecast is read off the main thread: wait until the chip shows a temperature. */
    private fun waitForWeather() =
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun home() {
        val services = testServices().apply { keepFixtureForecast() }
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        compose.settle()
        waitForWeather()
        // Every control the board has is there and says what it does.
        for (d in listOf("Search", "A near B", "Search by voice", "North up", "Map layers", "Show my location", "Zoom out to the globe", "Drinking water", "Food", "Toilet")) {
            compose.onNodeWithContentDescription(d, substring = true, useUnmergedTree = true).assertExists()
        }
        capture("home")
    }

    @Test
    fun homeWithCategory() {
        // The Amsterdam place store is on the phone: eat comes from it, with every tag, so open and closed show.
        val services = testServices(places = true).apply { keepFixtureForecast() }
        val nav = BackStackNavigator(listOf(Dest.Home()))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        val eat = Categories.byId("eat")!!.name("en")
        compose.onNodeWithContentDescription(eat).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Close the list")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        waitForWeather()
        capture("home_eat")
        // A row opens its place.
        val first = compose.onAllNodes(hasClickAction() and hasText("′", substring = true)).fetchSemanticsNodes().first()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher("first row") { it.id == first.id }).performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.Place)
        // Back closes the place, and back again clears the category.
        compose.runOnUiThread { nav.back() }
        compose.waitForIdle()
        assertTrue(nav.current is Dest.Home)
    }

    @Test
    fun theBottomBarSwitchesTabsOverTheMap() {
        val services = testServices()
        val nav = BackStackNavigator(listOf(Dest.Home()))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.onNodeWithText("Mine").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Dest.Home(), Dest.Mine), nav.stack.toList())
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Go, nav.current)
        compose.onNodeWithText("Map").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Dest>(Dest.Home()), nav.stack.toList())
    }

    @Test
    fun homeAsksForTheLocation() {
        // The phone's own location source without a permission yet: the card explains with symbols and asks.
        val services = testServices(demo = false)
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Allow").assertExists()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test
    fun theTopLeftAsOnTheBoard() {
        // The cluster of the Main board: sun and 20° with the area offline-ready, three moments, the quick needs.
        compose.setContent {
            app.atlas.ui.kit.AtlasTheme(dark = false) {
                androidx.compose.foundation.layout.Column(
                    Modifier.background(Color(0xFFD9D2C3)).padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    WeatherChip(CurrentWeather(Sky.CLEAR, 20.4, isDay = true, kept = false), OfflineState.READY, onClick = {})
                    MomentsChip(3, onClick = {})
                    NeedsPill({}, {}, {})
                    WeatherChip(CurrentWeather(Sky.RAIN, 11.0, isDay = true, kept = true), OfflineState.NONE, onClick = {})
                    WeatherChip(null, OfflineState.ONLINE, onClick = {})
                }
            }
        }
        compose.onNodeWithContentDescription("20°", substring = true).assertExists()
        capture("home_chips")
    }

    @Test
    fun homeDark() {
        val services = testServices().apply { keepFixtureForecast() }
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = true) }
        compose.waitForIdle()
        waitForWeather()
        capture("home_dark")
    }
}
