package app.atlas.ui.home

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.places.Brands
import app.atlas.places.CategoryTree
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import app.atlas.ui.search.EVERY_TAG
import app.atlas.ui.search.GRID_TAG
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The home's own row through the real navigation, on the Amsterdam extract at the Westerkerk (the owner, 6 November:
 * "Favorite re order customize?", then "Yes, build it"): a kind's star puts it in the row, where its button opens it,
 * lit; a long press on the row changes it, "+" opens the list of every kind to add from and the kind pressed comes into
 * the row; a chain's star puts its sign in the row, which opens its page, and back from it closes the sheet. Each look
 * to `row_<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeRowScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun services(): Services = testServices(places = true).apply { keepFixtureForecast() }

    private fun open(dest: Dest, services: Services): BackStackNavigator {
        val nav = BackStackNavigator(if (dest == Dest.Home()) listOf(dest) else listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.settle(15_000)
        return nav
    }

    /** The home, its weather in and the map still. */
    private fun home(services: Services): BackStackNavigator {
        val nav = open(Dest.Home(), services)
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settleScreen()
        return nav
    }

    private fun capture(name: String) {
        compose.settleScreen()
        compose.onRoot().captureRoboImage(File(shotsDir, "row_$name.png").absolutePath)
    }

    private fun kept(services: Services): List<String> = runBlocking { HomeRow.ids(services.settings.flow(SettingsKeys.HOME_ROW).first()) }

    private fun until(timeout: Long = 15_000, done: () -> Boolean) = compose.waitUntil(timeout) { done() }

    private fun shows(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun isOn(description: String) =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    @Test
    fun aKindsStarPutsItInTheRow() {
        val services = services()
        val drinks = CategoryTree.category("drinks")!!.name("en")
        val nav = open(Dest.Category("drinks"), services)
        until { compose.onAllNodes(hasText("open now", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(isOn("In your row"))
        capture("star_off")
        val off = compose.onNodeWithContentDescription("In your row").captureToImage().asAndroidBitmap()
        compose.onNodeWithContentDescription("In your row").performClick()
        until { "cat:drinks" in kept(services) }
        compose.waitForIdle()
        assertTrue(isOn("In your row"))
        // drawn round, then filled: the star shows what it says
        assertFalse("the star filled", off.sameAs(compose.onNodeWithContentDescription("In your row").captureToImage().asAndroidBitmap()))
        capture("star_on")
        assertEquals(HOME_CATEGORIES + "cat:drinks", kept(services))
        // home: its button at the row's end, which opens it, lit
        compose.runOnIdle { nav.back() }
        compose.waitUntil(20_000) { shows(drinks) }
        compose.onNodeWithContentDescription(drinks).performScrollTo().performClick()
        until { isOn(drinks) }
        capture("home_drinks")
    }

    @Test
    fun theRowIsChangedFromTheHome() {
        val services = services()
        home(services)
        val eat = CategoryTree.category("eat")!!.name("en")
        val cafe = CategoryTree.preset("amenity/cafe")!!.name("en")
        compose.onNodeWithContentDescription(eat).performTouchInput { longClick() }
        until { shows("Done") }
        capture("home_editing")
        compose.onNodeWithContentDescription("Add to your row").performScrollTo().performClick()
        until { compose.onAllNodes(hasText("Add to your row")).fetchSemanticsNodes().isNotEmpty() }
        // the kinds around come in after the families
        until(20_000) { runCatching { compose.onNodeWithTag(EVERY_TAG).performScrollToNode(hasText(cafe)) }.isSuccess }
        compose.onNodeWithTag(EVERY_TAG).performScrollToNode(hasText(eat))
        capture("pick")
        compose.onNodeWithTag(EVERY_TAG).performScrollToNode(hasText(cafe))
        compose.onNode(hasText(cafe)).performClick()
        until { "preset:amenity/cafe" in kept(services) }
        // the list closes, back to the row as it is being changed, the new one in view at its end
        until { shows(cafe) && shows("Done") }
        capture("home_added")
        // the row hid under the full sheet and comes back where it was, then on to the new one, whole in view
        val added = compose.onNodeWithContentDescription(cafe).fetchSemanticsNode()
        assertEquals("$cafe in view, whole", added.size.width.toFloat(), added.boundsInRoot.width, 1f)
        compose.onNodeWithContentDescription("Done").performClick()
        until { !shows("Done") }
        assertTrue(shows(cafe))
    }

    @Test
    fun aChainsStarPutsItsSignInTheRow() {
        val services = services()
        val ah = Brands.byId("albertheijn-1cf55d")!!
        val nav = open(Dest.Brand(ah.id), services)
        compose.waitUntil(20_000) { shows("In your row") }
        compose.onNodeWithContentDescription("In your row").performClick()
        until { HomeRow.idOf(ah) in kept(services) }
        capture("star_chain")
        compose.runOnIdle { nav.back() }
        compose.waitUntil(20_000) { shows("Albert Heijn") }
        compose.onNodeWithContentDescription("Albert Heijn").performScrollTo().performClick()
        until { isOn("Albert Heijn") }
        capture("home_chain")
        // back from the chain the row opened closes the sheet, as from a kind it opened
        compose.onNodeWithContentDescription("Back").performClick()
        until { !isOn("Albert Heijn") }
        assertTrue("the search bar is back", shows("Search or ask") || compose.onAllNodes(hasText("Search or ask")).fetchSemanticsNodes().isNotEmpty())
    }

    /** The star in what a long press opens, where a kind's band can have a star of its own behind it. */
    private val heldStar = hasContentDescription("In your row") and hasAnyAncestor(isDialog())

    private fun heldStarIsOn() = compose.onNode(heldStar).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    /**
     * A long press says what a kind is and holds its star (the owner, 9 November: "Yes to the long press"): a card of the
     * sheet goes into the row without being opened, and out again.
     */
    @Test
    fun aHeldCardGoesIntoTheRowUnopened() {
        val services = services()
        val nav = open(Dest.Search(), services)
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("′ spare", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val card = SemanticsMatcher("Sport's card") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.substringBefore(", ") == "Sport" }
        }
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(card)
        compose.onNode(card).performTouchInput { longClick() }
        until { compose.onAllNodes(heldStar).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(heldStarIsOn())
        val id = HomeRow.idOf(CategoryTree.shelf("sport")!!)
        compose.onNode(heldStar).performClick()
        until { id in kept(services) }
        compose.waitForIdle()
        assertTrue(heldStarIsOn())
        assertEquals(HOME_CATEGORIES + id, kept(services))
        assertEquals("nothing opened", Dest.Search(), nav.current)
        compose.onNode(isDialog()).captureRoboImage(File(shotsDir, "row_held_star.png").absolutePath)
        compose.onNode(heldStar).performClick()
        until { id !in kept(services) }
    }

    /** A kind's chip too: held in Drinks, the pubs go into the row, its band's own star staying the drinks'. */
    @Test
    fun aHeldChipGoesIntoTheRowUnopened() {
        val services = services()
        val nav = open(Dest.Category("drinks"), services)
        until { compose.onAllNodes(hasText("Pub")).fetchSemanticsNodes().isNotEmpty() }
        // the chip, above the deck of pubs that goes by the same name
        compose.onAllNodes(hasText("Pub")).onFirst().performTouchInput { longClick() }
        until { compose.onAllNodes(heldStar).fetchSemanticsNodes().isNotEmpty() }
        val pub = HomeRow.idOf(CategoryTree.byId("preset:amenity/pub")!!)
        compose.onNode(heldStar).performClick()
        until { pub in kept(services) }
        assertFalse("the drinks are not starred with it", "cat:drinks" in kept(services))
        assertEquals("nothing opened", Dest.Category("drinks"), nav.current)
    }
}
