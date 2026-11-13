package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.SettingsKeys
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The look search and the categories share with What's here (the owner, 12 November: "Can they look closer to the
 * whats nearby and places card? Especially the search results more like the nearest row"): their places as "NEAREST"
 * rows in warm panels, where a phone or a language is harder on them than [SearchScreensTest]'s are: at night, in
 * Dutch, on a narrow phone, with a long name. Each to `find_<name>.png`. In a class of their own, so the order of
 * [SearchScreensTest]'s screens, and with it what is warm when each runs, stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class NearestLookTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(dest: Dest, services: Services = testServices(places = true), dark: Boolean = false): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = dark) }
        compose.settle(15_000)
        return nav
    }

    /** Lets the map draw: capture until three frames in a row come out the same, as [SearchScreensTest] does. */
    private fun settleMap(timeoutMillis: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        var last: android.graphics.Bitmap? = null
        var same = 0
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            if (last != null && now.sameAs(last)) {
                if (++same >= 3) return
            } else {
                same = 0
            }
            last = now
            Thread.sleep(120)
        }
    }

    private fun capture(name: String): File {
        settleMap()
        val f = File(shotsDir, "find_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun waitFor(text: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    /**
     * The results wear the look of What's here's "NEAREST" rows (the owner, 12 November: "Especially the search results
     * more like the nearest row"): at night too, the tiles in their kinds' colours on the night's panel.
     */
    @Test
    fun searchCafesAtNight() {
        open(Dest.Search("cafe"), dark = true)
        waitFor("Kalkhoven")
        assertTrue("places found", compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().size >= 3)
        capture("search_cafe_dark")
    }

    /** The same rows in Dutch, whose words run longer: the line under a name gives way before the minutes do. */
    @Test
    @Config(qualifiers = "nl-w412dp-h915dp-xhdpi")
    fun categoryInDutch() {
        open(Dest.Category("drinks"))
        waitFor("Brandon", 20_000)
        // the rows' own words in Dutch
        waitFor("open tot")
        capture("category_drinks_nl")
    }

    /**
     * On a narrow phone (360 dp) the panel's rows keep their tile, their name and their minutes, a chain's deck its
     * count; a row opens its place, and the way back comes back to the kind's places.
     */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun groceriesOnANarrowPhone() {
        val nav = open(Dest.Category("groceries"))
        waitFor("Albert Heijn", 20_000)
        compose.onNode(hasTestTag(DECK_TAG) and hasText("Albert Heijn") and hasText("2 near", substring = true)).assertExists()
        capture("category_groceries_narrow")
        compose.onAllNodes(hasTestTag(PLACE_TAG)).onFirst().performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.Place)
        compose.runOnUiThread { nav.back() }
        waitFor("Albert Heijn", 20_000)
        assertTrue("back on ${nav.current}", nav.current is Dest.Category)
    }

    /** A long name on a narrow phone is cut at its row's end, its minutes still whole beside it. */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun searchLongNamesOnANarrowPhone() {
        open(Dest.Search("sandwich"))
        waitFor("Smoked crispy", 20_000)
        compose.onNodeWithText("Smoked crispy bacon sandwich house").assertExists()
        capture("search_long_names")
    }

    /** A chain's deck at night: the edges of its lower panels show under it on the night's paper as they do by day. */
    @Test
    fun groceriesAtNight() {
        open(Dest.Category("groceries"), dark = true)
        waitFor("Albert Heijn", 20_000)
        compose.onNode(hasTestTag(DECK_TAG) and hasText("Albert Heijn") and hasText("2 near", substring = true)).assertExists()
        capture("category_groceries_dark")
    }

    /**
     * The grid's kinds as a list (the owner, 13 November: "Only the every kind list gets the list view but the cards stay
     * the default"): cards until "Show as a list" is chosen under "Every kind"'s key, kept from then on; then each kind
     * is a row of one panel, and a row opens its kind as its card does.
     */
    @Test
    fun everyKindAsAList() {
        val services = testServices(places = true)
        open(Dest.Search(), services)
        waitFor("Every kind")
        compose.onNodeWithTag(GRID_TAG).performScrollToKey("kinds")
        compose.onNodeWithContentDescription("How the kinds are shown").performClick()
        compose.onNodeWithText("Show as a list").performClick()
        compose.waitUntil(5_000) { runBlocking { services.settings.data.first()[SettingsKeys.SEARCH_KINDS_LIST] } == true }
        // a row's line, which no card has: "146 near · 43 open"
        waitFor("near · ")
        capture("grid_kinds_list")
        compose.onNodeWithText("Eat & drink").performClick()
        waitFor("All filters", 20_000)
    }
}
