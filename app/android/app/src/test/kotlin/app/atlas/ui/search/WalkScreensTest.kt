package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.route.RouteResult
import app.atlas.route.Router
import app.atlas.search.MemoryPlaces
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import app.atlas.ui.go.GoFixtures
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A walk from its chip to the places worth walking to and, second, a round through the park: Amsterdam's parks and
 * sights as they are, the real routing data where this machine has it, each step to `find_walk_<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class WalkScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun park(id: Long, name: String, at: LatLon, box: BBox) =
        Place(OsmId(OsmType.WAY, id), at, mapOf("leisure" to "park", "name" to name), box)

    private fun spot(id: Long, name: String, at: LatLon, vararg tags: Pair<String, String>) =
        Place(OsmId(OsmType.NODE, id), at, tags.toMap() + ("name" to name) + ("opening_hours" to "Mo-Su 08:00-23:00"))

    private val places = listOf(
        park(1, "Westerpark", LatLon(52.3862, 4.8760), BBox(4.8600, 52.3835, 4.8790, 52.3885)),
        park(2, "Vondelpark", LatLon(52.3580, 4.8685), BBox(4.8555, 52.3550, 4.8815, 52.3610)),
        park(3, "Erasmuspark", LatLon(52.3780, 4.8555), BBox(4.8510, 52.3760, 4.8600, 52.3800)),
        park(4, "Postzegelpark", LatLon(52.3760, 4.8830), BBox(4.8826, 52.3758, 4.8834, 52.3762)),
        spot(10, "Café Kalkhoven", LatLon(52.3745, 4.8830), "amenity" to "cafe"),
        spot(11, "De Bolhoed", LatLon(52.3790, 4.8850), "amenity" to "restaurant"),
        spot(12, "'t Smalle", LatLon(52.3770, 4.8850), "amenity" to "pub"),
        // places to walk to that are no green
        Place(OsmId(OsmType.WAY, 20), LatLon(52.3753, 4.8846), mapOf("historic" to "memorial", "memorial" to "sculpture", "name" to "Homomonument"), BBox(4.8843, 52.3751, 4.8849, 52.3755)),
        Place(OsmId(OsmType.NODE, 21), LatLon(52.3743, 4.8836), mapOf("tourism" to "artwork", "artwork_type" to "statue", "historic" to "memorial", "name" to "Anne Frank")),
        Place(OsmId(OsmType.NODE, 22), LatLon(52.3731, 4.8926), mapOf("place" to "square", "name" to "Dam")),
        Place(OsmId(OsmType.NODE, 23), LatLon(52.3840, 4.9020), mapOf("tourism" to "viewpoint", "name" to "A'DAM Lookout")),
        Place(OsmId(OsmType.NODE, 24), LatLon(52.3667, 4.9264), mapOf("man_made" to "windmill", "name" to "De Gooyer")),
    )

    /** Straight lines at a walking pace, where this machine has no routing data. */
    private val straight = Router { req ->
        val d = req.waypoints.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
        RouteResult.Found(listOf(Route(req.mode, req.waypoints, distance = d, duration = d / 1.3, steps = emptyList())))
    }

    private val realOrStraight: Router get() = GoFixtures.router ?: straight

    private fun open(router: Router, dark: Boolean = false): BackStackNavigator {
        val services: Services = testServices(stores = listOf(MemoryPlaces(places)), router = router)
        val nav = BackStackNavigator(listOf(Dest.Home(), Dest.Search()))
        compose.setContent { AtlasRoot(services, nav, dark = dark) }
        compose.settle(15_000)
        return nav
    }

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
        val f = File(shotsDir, "find_walk_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun waitFor(text: String, timeout: Long = 20_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForSaid(said: String, timeout: Long = 60_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasContentDescription(said)).fetchSemanticsNodes().isNotEmpty() }

    /** Opens the walk from its chip and waits for its places. */
    private fun intoTheWalk() {
        waitFor("A walk")
        compose.onNodeWithText("A walk").performClick()
        waitFor("Homomonument")
    }

    /** Down the walk's list to its second part, the round walk. */
    private fun toTheRound() {
        compose.onNodeWithTag(KIND_LIST_TAG).performScrollToKey(ROUND_KEY)
        waitFor("Round walk")
    }

    @Test
    fun fromTheChipToARoundThroughThePark() {
        val nav = open(realOrStraight)
        waitFor("A walk")
        // the chip names the nearest park and the minutes to it, said as one
        waitForSaid("Westerpark is 18 min away", 30_000)
        capture("chip")
        compose.onNodeWithText("A walk").performClick()
        // the places worth walking to first, the parks among them
        waitFor("Homomonument")
        capture("list")
        // a pocket garden is no walk, nor yet a square
        compose.onNodeWithText("Postzegelpark").assertDoesNotExist()
        compose.onNodeWithTag(KIND_LIST_TAG).performScrollToNode(hasText("Westerpark"))
        compose.onNodeWithText("Westerpark").assertExists()
        waitFor("lap")
        compose.onNodeWithText("Dam").assertDoesNotExist()
        // the round walk second, after them: pulled up, the sheet shows the list's end
        compose.onNodeWithTag(KIND_LIST_TAG).performTouchInput { swipeUp(startY = height * 0.5f, endY = 0f) }
        compose.settle(1_000)
        toTheRound()
        capture("list_end")
        compose.onNodeWithContentDescription("60 min").performClick()
        waitForSaid("Start the walk")
        // asked for, it leaves the list's end for the top, in sight as the sheet comes down to show the way on the map
        compose.onNodeWithContentDescription("Start the walk").assertIsDisplayed()
        capture("round")
        compose.onNodeWithContentDescription("Start the walk").performClick()
        compose.waitUntil(20_000) { nav.current is Dest.Navigate }
        capture("going")
    }

    @Test
    fun eachParkLinksToItsPlan() {
        val nav = open(realOrStraight)
        intoTheWalk()
        compose.onNodeWithTag(KIND_LIST_TAG).performScrollToNode(hasText("Westerpark"))
        compose.onAllNodes(hasContentDescription("The park's plan")).onFirst().performClick()
        compose.waitUntil(20_000) { nav.current is Dest.Parkland }
    }

    @Test
    fun theDataARoundNeedsIsOffered() {
        open(Router { RouteResult.NoData(listOf("E0_N50.rd5")) })
        intoTheWalk()
        toTheRound()
        compose.onNodeWithContentDescription("40 min").performClick()
        waitFor("No routing data here yet")
        compose.onNodeWithText("E0_N50", substring = true).assertExists()
        capture("needs_data")
    }

    @Test
    fun inTheDark() {
        open(realOrStraight, dark = true)
        intoTheWalk()
        capture("list_dark")
        toTheRound()
        compose.onNodeWithContentDescription("20 min").performClick()
        waitForSaid("Start the walk")
        capture("round_dark")
    }
}
