package app.atlas.ui.shop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

/** Every shop screen on the real shops, errands and needs around the Westerkerk (OpenStreetMap), Tuesday evening 19:30. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ShopScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dest: Dest, services: Services = ShopFixtures.services()): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.settle(30_000)
        return nav
    }

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
    fun pantry() {
        show(Dest.Groceries())
        settleWork()
        capture("shop_pantry")
    }

    @Test
    fun aisleSupermarkets() {
        show(Dest.Groceries("super"))
        settleWork()
        capture("shop_aisle_super")
    }

    @Test
    fun aisleBakeries() {
        show(Dest.Groceries("bakery"))
        settleWork()
        capture("shop_aisle_bakery")
    }

    /** The pad of the board: sourdough, saffron, limes, feta, oat milk, and eggs already at home. */
    private fun boardList(services: Services) {
        val list = ShopRepository.of(services).list
        listOf("sourdough", "saffron", "limes", "feta", "oat milk", "eggs").forEach { list.add(it) }
        list.items.value.first { it.text == "eggs" }.let { list.toggleHome(it) }
    }

    @Test
    fun list() {
        val services = ShopFixtures.services()
        boardList(services)
        show(Dest.Groceries("list"), services)
        settleWork()
        capture("shop_list")
    }

    @Test
    fun listEmpty() {
        show(Dest.Groceries("list"))
        settleWork()
        capture("shop_list_empty")
    }

    @Test
    fun compare() {
        val services = ShopFixtures.services()
        ShopRepository.of(services, FakePrices())
        boardList(services)
        show(Dest.Groceries("compare"), services)
        settleWork()
        capture("shop_basket")
    }

    @Test
    fun compareWithNoPrices() {
        val services = ShopFixtures.services()
        boardList(services)
        show(Dest.Groceries("compare"), services)
        settleWork()
        capture("shop_basket_empty")
    }

    @Test
    fun refill() {
        show(Dest.Groceries("refill"))
        settleWork()
        capture("shop_refill")
    }

    @Test
    fun findPictureHooks() {
        show(Dest.Groceries("find:picture hooks"))
        settleWork()
        capture("shop_find")
    }

    @Test
    fun findGroceriesOpenNow() {
        show(Dest.Groceries("find:oat milk and saffron, open now"))
        settleWork()
        capture("shop_find_groceries")
    }

    @Test
    fun errands() {
        show(Dest.Groceries("errands"))
        settleWork()
        capture("shop_errands")
    }

    @Test
    fun help() {
        show(Dest.Groceries("help"))
        settleWork()
        capture("shop_help")
    }

    @Test
    fun needToilet() {
        show(Dest.Needs("toilet"))
        settleWork()
        capture("shop_need_toilet")
    }

    @Test
    fun needWater() {
        show(Dest.Needs("water"))
        settleWork()
        capture("shop_need_water")
    }

    @Test
    fun needFood() {
        show(Dest.Needs("food"))
        settleWork()
        capture("shop_need_food")
    }

    @Test
    fun highStreet() {
        show(Dest.Groceries("highstreet"))
        settleWork()
        capture("shop_highstreet")
    }

    @Test
    fun streetFashion() {
        show(Dest.Groceries("street:fashion"))
        settleWork()
        capture("shop_street_fashion")
    }

    @Test
    fun bins() {
        show(Dest.Groceries("bins"))
        settleWork()
        capture("shop_bins")
    }

    @Test
    fun dropPaper() {
        show(Dest.Groceries("drop:paper"))
        settleWork()
        capture("shop_drop")
    }

    @Test
    fun stroll() {
        show(Dest.Groceries("stroll"))
        settleWork()
        capture("shop_stroll")
    }

    /** No downloaded area: only the map tiles' names and kinds, and the pantry says so. */
    @Test
    fun pantryFromTheTilesOnly() {
        show(Dest.Groceries(), ShopFixtures.services(places = false))
        settleWork()
        capture("shop_pantry_tiles")
    }

    @Test
    fun aisleFromTheTilesOnly() {
        show(Dest.Groceries("super"), ShopFixtures.services(places = false))
        settleWork()
        capture("shop_aisle_tiles")
    }

    /** "Best": the specialists for each item, so the run has several stops, as the board's receipt does. */
    @Test
    fun listPlannedByBest() {
        val services = ShopFixtures.services()
        boardList(services)
        show(Dest.Groceries("list"), services)
        compose.onNodeWithText("Best").performClick()
        settleWork()
        capture("shop_list_best")
    }
}
