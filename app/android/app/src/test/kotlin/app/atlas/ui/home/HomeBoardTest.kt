package app.atlas.ui.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.places.Categories
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The whole Map home at the Main board's size (412 × 915 dp at density 2) on real data: the fixture tiles, the
 * Amsterdam place store, the kept Open-Meteo forecast, the demo location at the Westerkerk, on Thursday 15 October
 * 2026 at 16:00 (the board's "Thursday at 16:00"), with the boards' frosted glass on (the app's is off by default).
 * Its overlays must sit where `Main.dc.html` puts them, to a dp (but the category buttons, which keep their 44 dp and
 * scroll where the board narrows them to fit); the screenshot is then compared with the board's picture when it is on
 * this machine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeBoardTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File(shotsDir, "board").also { it.mkdirs() }

    @Test
    fun theHomeIsTheBoards() {
        val services = testServices(places = true, clock = THURSDAY).apply {
            keepFixtureForecast()
            keepFixtureRegion()
        }
        runBlocking { services.settings.set(SettingsKeys.FROSTED_GLASS, true) }
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settleScreen()

        // The canal ring is on the phone: the pill says so.
        compose.onNodeWithContentDescription("works offline", substring = true).fetchSemanticsNode()
        // The top left: the weather pill at 16, 48 (44 high), the moments pill under it, the needs at 16, 146 when
        // there are moments (44 high, 106 wide).
        val weather = dp(compose.onNodeWithContentDescription("°", substring = true))
        near("weather left", weather.left, 16f)
        near("weather top", weather.top, 48f)
        near("weather height", weather.height, 44f)
        val water = dp(compose.onNodeWithContentDescription("Drinking water", useUnmergedTree = true))
        val toilet = dp(compose.onNodeWithContentDescription("Toilet", useUnmergedTree = true))
        near("needs left", water.left, 16f)
        near("needs right", toilet.right, 122f)
        near("needs height", water.height, 44f)
        // The rail: 48 wide at 16 from the right, the compass 52 high, then four 47 dp rows (a hairline and 46).
        val compass = dp(compose.onNodeWithContentDescription("North up"))
        near("rail right", compass.right, 396f)
        near("rail top", compass.top, 48f)
        near("compass height", compass.height, 52f)
        val globe = dp(compose.onNodeWithContentDescription("Zoom out to the globe"))
        near("rail bottom", globe.bottom, 48f + 52f + 4 * 47f)
        // The bottom: the search bar 12 dp in from each side, 58 high, its foot 90 dp above the screen's; the category
        // row 10 dp above it, eight 44 dp buttons 8 dp apart from 12 dp in, the last past the screen's edge (the board
        // narrows them to 41.5 dp to fit; the user, 30 October: "they shouldnt be squeezed on smaller phones").
        val mic = dp(compose.onNodeWithContentDescription("Search by voice"))
        near("mic right", mic.right, 412f - 12f - 7f)
        near("mic centre", mic.center.y, 915f - 90f - 29f)
        val eat = whole(compose.onNodeWithContentDescription(Categories.byId("eat")!!.name("en")))
        val water2 = whole(compose.onNodeWithContentDescription(Categories.byId("water")!!.name("en")))
        near("row left", eat.left, 12f)
        near("row right", water2.right, 12f + 8 * 44f + 7 * 8f)
        near("row top", eat.top, 915f - 90f - 58f - 10f - 44f)
        near("row height", eat.height, 44f)
        near("row button", eat.width, 44f)
        compose.onNodeWithText("Search or ask").assertExistsCompat()

        val shot = File(out, "home_board.png")
        compose.onRoot().captureRoboImage(shot.absolutePath)
        assertTrue(shot.isFile && shot.length() > 100_000)
        assumeTrue("the board's picture is on the owner's machine only", BoardShots.reference.isFile)
        val lines = BoardShots.compare(BoardShots.read(BoardShots.reference), BoardShots.read(shot), out)
        println(lines.joinToString("\n"))
    }

    private fun dp(node: SemanticsNodeInteraction): Rect {
        val b = node.fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    /** [node]'s whole box in dp, past the screen's edge too (its semantics bounds stop at the edge). */
    private fun whole(node: SemanticsNodeInteraction): Rect {
        val n = node.fetchSemanticsNode()
        val d = compose.density.density
        return Rect(n.positionInRoot.x / d, n.positionInRoot.y / d, (n.positionInRoot.x + n.size.width) / d, (n.positionInRoot.y + n.size.height) / d)
    }

    private fun near(what: String, actual: Float, board: Float, tolerance: Float = 1f) =
        assertTrue("$what: $actual dp, the board has $board", abs(actual - board) <= tolerance)

    private fun SemanticsNodeInteraction.assertExistsCompat() { fetchSemanticsNode() }

    /** Puts the canal ring on the phone as a complete offline region, as a download would: its region pack and its record. */
    private fun Services.keepFixtureRegion() {
        val id = "amsterdam"
        val target = mine.files.atlas(id).also { it.parentFile?.mkdirs() }
        app.atlas.testing.BundledPack.file.copyTo(target, overwrite = true)
        val box = app.atlas.testing.BundledPack.features.sources.first().coverage
        val now = clock.millis()
        val map = app.atlas.ui.mine.offline.LayerRecord(app.atlas.ui.mine.offline.MapKind.ATLAS.id, target.length(), now)
        mine.files.save(
            app.atlas.ui.mine.offline.RegionMeta(id, "Amsterdam", box, now, now, layers = mapOf(app.atlas.services.RegionLayer.MAP to map), wanted = setOf(app.atlas.services.RegionLayer.MAP)),
        )
    }

    companion object {
        /** Thursday 15 October 2026, 16:00 in Amsterdam: within the kept forecast, 20° by its hour. */
        val THURSDAY: Clock = Clock.fixed(Instant.parse("2026-10-15T14:00:00Z"), ZoneId.of("Europe/Amsterdam"))
    }
}
