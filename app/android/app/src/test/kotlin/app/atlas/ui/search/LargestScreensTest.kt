package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.search.MemoryPlaces
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The largest greens around Amsterdam and of the country (the user, 9 November: "Is it somewhere possible to see the
 * largest green areas in your country or around you?"): Largest in the nature reserves' order menu, the reserves by
 * their size with the national parks beyond the circle, and "largest green areas" typed, each to
 * `find_largest_<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class LargestScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var ids = 0L

    private fun area(name: String, box: BBox, vararg tags: Pair<String, String>) =
        Place(OsmId(OsmType.WAY, ++ids), box.center, tags.toMap() + ("name" to name), box)

    private val places = listOf(
        Place(OsmId(OsmType.NODE, 900), LatLon(52.3745, 4.8830), mapOf("amenity" to "cafe", "name" to "Café Kalkhoven", "opening_hours" to "Mo-Su 08:00-23:00")),
        area("Westerpark", BBox(4.8600, 52.3835, 4.8790, 52.3885), "leisure" to "park"),
        area("Vondelpark", BBox(4.8555, 52.3550, 4.8815, 52.3610), "leisure" to "park"),
        area("Erasmuspark", BBox(4.8510, 52.3760, 4.8600, 52.3800), "leisure" to "park"),
        area("Amsterdamse Bos", BBox(4.8150, 52.2950, 4.8750, 52.3350), "leisure" to "park"),
        area("Diemer Vijfhoek", BBox(4.9600, 52.3350, 4.9850, 52.3460), "leisure" to "nature_reserve"),
        area("Ilperveld", BBox(4.9200, 52.4200, 4.9750, 52.4600), "leisure" to "nature_reserve"),
        area("Nationaal Park Zuid-Kennemerland", BBox(4.5300, 52.3600, 4.6200, 52.4500), "boundary" to "national_park"),
        // beyond the widest circle a view reads: the region's, once Largest is chosen
        area("Nationaal Park Utrechtse Heuvelrug", BBox(5.2500, 51.9700, 5.5500, 52.1200), "boundary" to "national_park"),
        area("Nationaal Park De Hoge Veluwe", BBox(5.7600, 52.0400, 5.9000, 52.1600), "boundary" to "national_park"),
    )

    private fun open(dest: Dest, dark: Boolean = false): BackStackNavigator {
        val services: Services = testServices(stores = listOf(MemoryPlaces(places)))
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
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
        val f = File(shotsDir, "find_largest_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun waitFor(text: String, timeout: Long = 20_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(text: String) = compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty()

    /** The nature reserves, Largest chosen from their order menu. */
    private fun largestReserves(shoot: Boolean) {
        waitFor("Ilperveld")
        assertTrue(gone("Hoge Veluwe"))
        compose.onNodeWithText("Best now").performClick()
        waitFor("Largest")
        if (shoot) capture("menu")
        compose.onNodeWithText("Largest").performClick()
        waitFor("Hoge Veluwe")
    }

    @Test
    fun theNatureReservesByTheirSize() {
        open(Dest.Search(category = "nature_reserve"))
        largestReserves(shoot = true)
        // the national parks however far, each with how far round it is, and named a national park, not the reserves
        waitFor("km lap")
        waitFor("National Park")
        capture("reserves")
    }

    @Test
    fun theNatureReservesByTheirSizeDark() {
        open(Dest.Search(category = "nature_reserve"), dark = true)
        largestReserves(shoot = false)
        waitFor("km lap")
        capture("reserves_dark")
    }

    @Test
    fun aGreensRowOpensItsParkPage() {
        val nav = open(Dest.Search("largest green areas"))
        waitFor("Vondelpark")
        compose.onAllNodes(hasText("Vondelpark", substring = true)).onFirst().performClick()
        compose.waitUntil(15_000) { nav.current is Dest.Parkland }
        assertEquals(Dest.Parkland.of(places.first { it.tags["name"] == "Vondelpark" }), nav.current)
    }

    @Test
    fun largestGreenAreasTyped() {
        open(Dest.Search("largest green areas"))
        waitFor("Utrechtse Heuvelrug")
        waitFor("Vondelpark")
        capture("greens")
    }
}
