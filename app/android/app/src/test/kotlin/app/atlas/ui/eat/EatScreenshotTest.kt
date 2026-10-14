package app.atlas.ui.eat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime

/** Every eat & drink screen on the real places around the Westerkerk (OpenStreetMap), on Tuesday evening at 19:30. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class EatScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dest: Dest, services: Services = EatFixtures.services()): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.settle(30_000)
        return nav
    }

    /** Screens work out their lists off the main thread: let that finish after a tap, then settle. */
    private fun settleWork() {
        repeat(6) {
            compose.waitForIdle()
            Thread.sleep(150)
        }
        compose.settle()
    }

    private fun capture(name: String): File {
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    @Test
    fun menus() {
        show(Dest.Eat())
        compose.onNodeWithText("Dinner").assertExists()
        capture("eat_menus")
    }

    @Test
    fun menusWithTheWaitersAnswer() {
        show(Dest.Eat())
        compose.onNodeWithContentDescription("ask").performTextInput("pizza open now")
        compose.onNodeWithContentDescription("ask").performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pizza", substring = true).fetchSemanticsNodes().size > 1 }
        compose.settle()
        capture("eat_menus_waiter")
    }

    @Test
    fun carte() {
        show(Dest.Eat("carte"))
        compose.onNodeWithText("La Carte").assertExists()
        capture("eat_carte")
    }

    @Test
    fun cuisinePage() {
        show(Dest.Eat("carte:italian"))
        capture("eat_carte_italian")
    }

    @Test
    fun order() {
        val services = EatFixtures.services()
        EatRepository.of(services).orders.set(
            OrderPad(
                time = LocalTime.of(19, 30), party = 2, minutes = 120, cuisines = listOf("italian", "japanese"), not = listOf(OrderPad.FAST_FOOD),
                outside = true, wishes = setOf(Wish.DOGS, Wish.QUIET), maxWalk = 10,
            ),
        )
        show(Dest.Eat("order"), services)
        capture("eat_order")
        compose.onNodeWithText("Serve").performClick()
        settleWork()
        capture("eat_order_served")
    }

    @Test
    fun bar() {
        show(Dest.Eat("bar"))
        compose.onNodeWithText("THE BAR").assertExists()
        capture("eat_bar")
    }

    @Test
    fun barPubsOnTheTerrace() {
        show(Dest.Eat("bar"))
        compose.onNodeWithText("PUBS").performClick()
        compose.onNodeWithContentDescription("Terrace").performClick()
        settleWork()
        compose.onNodeWithText("Pulitzer", substring = true, ignoreCase = true).assertDoesNotExist()
        capture("eat_bar_pubs")
    }

    @Test
    fun cafe() {
        show(Dest.Eat("cafe"))
        capture("eat_cafe")
    }

    @Test
    fun ramen() {
        show(Dest.Eat("ramen"))
        capture("eat_ramen")
    }

    @Test
    fun ramenPrintsATicket() {
        val services = EatFixtures.services()
        show(Dest.Eat("ramen"), services)
        val area = runBlocking { withTimeout(30_000) { EatRepository.of(services).area(Services.WESTERKERK).first { !it.loading } } }
        val world = CuisineWorlds.JAPAN
        val machine = WorldModel.machine(world, world.plankIndex("ramen"), area, LocalDateTime.now(services.clock), emptySet(), AreaRatings.OFFLINE)
        val first = machine.buttons.first { it.openness.isOpen }
        compose.onNodeWithText(first.place.name).performClick()
        settleWork()
        compose.onNodeWithText("No. ", substring = true).assertExists()
        repeat(5) { compose.mainClock.advanceTimeBy(200); compose.waitForIdle() }
        capture("eat_ramen_ticket")
    }

    @Test
    fun late() {
        show(Dest.Eat("late"))
        capture("eat_late")
    }

    @Test
    fun sweets() {
        show(Dest.Eat("sweets"))
        capture("eat_sweets")
    }

    @Test
    fun market() {
        show(Dest.Eat("market"))
        capture("eat_market")
    }

    @Test
    fun eatMap() {
        show(Dest.Eat("map"))
        // The base map itself is drawn by frames Robolectric does not run (as on the Map home's shots): pins, sheet and cards are what this checks.
        compose.onNodeWithText("Trending").assertExists()
        capture("eat_map")
    }

    @Test
    fun tilesOnlyTheTableStillCounts() {
        show(Dest.Eat(), EatFixtures.services(places = false))
        capture("eat_menus_tiles")
    }
}
