package app.atlas.ui.home

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.places.Categories
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import app.atlas.ui.kit.AtlasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Duration

/**
 * The Map home's chrome and the bottom bar: solid by default, their tint opaque and their shapes only keep-outs for the
 * map's core (no glass for it to blur under them); the boards' glass with the Look settings' "Frosted glass" on. And the
 * category buttons in one row that scrolls: each at the board's 44 dp, never narrowed, on the usual phone and at 320 and
 * 360 dp with text at 1.0 and 1.3, the first lined up with the rest of the chrome.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeChromeTest {
    @get:Rule
    val compose = createComposeRule()

    private val categories = HOME_CATEGORIES.mapNotNull { Categories.byId(it)?.name("en") }

    /** The map's log tag (mapview's EngineFrames.TAG), which the chrome's shapes log under. */
    private val MAP_TAG = "AtlasMap"

    private fun show(glass: Boolean) {
        val services = testServices().apply { keepFixtureForecast() }
        if (glass) runBlocking { services.settings.set(SettingsKeys.FROSTED_GLASS, true) }
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settleScreen()
    }

    /** The chrome's shapes as the map last logged them (ChromeShapes.describe: `vk chrome <n> shapes: …`). */
    private fun chromeLine(): String {
        // the shapes log themselves a moment after they last moved
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return ShadowLog.getLogsForTag(MAP_TAG).map { it.msg }.last { it.startsWith("vk chrome") }
    }

    /** One dp of the board's 412 across the screen, px (the app lays every screen out in board pixels). */
    private fun boardDp(): Float = compose.onRoot().fetchSemanticsNode().size.width / 412f

    /** [node]'s whole box in board dp, past the screen's edge too (its semantics bounds stop at the edge). */
    private fun box(node: SemanticsNodeInteraction, d: Float = boardDp()): androidx.compose.ui.geometry.Rect {
        val n = node.fetchSemanticsNode()
        val p = n.positionInRoot
        return androidx.compose.ui.geometry.Rect(p.x / d, p.y / d, (p.x + n.size.width) / d, (p.y + n.size.height) / d)
    }

    /** The colour inside the weather pill, left of its sky: the pill's own fill over whatever lies under it. */
    private fun weatherFill(): Int {
        val pill = compose.onNodeWithContentDescription("°", substring = true).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        return image.getPixel((pill.left + 5 * boardDp()).toInt(), pill.center.y.toInt())
    }

    @Test
    fun theChromeIsSolidByDefaultAndTheCoreBlursNothingUnderIt() {
        show(glass = false)
        val line = chromeLine()
        // every pill, button and the bar a keep-out, none frosted
        assertTrue(line, Regex("vk chrome (\\d+) shapes").find(line)!!.groupValues[1].toInt() >= 12)
        assertFalse(line, line.contains(" frosted"))
        assertTrue(line, line.contains(" clear"))
        assertEquals("the weather pill opaque white", 0xFFFFFFFF.toInt(), weatherFill())
        compose.onRoot().captureRoboImage(File(shotsDir, "home_solid.png").absolutePath)
    }

    @Test
    fun theGlassSettingBringsTheBoardsGlassBack() {
        show(glass = true)
        val line = chromeLine()
        assertTrue(line, line.contains(" frosted"))
        assertFalse(line, line.contains(" clear"))
        assertNotEquals("the weather pill white at 80 % over the map", 0xFFFFFFFF.toInt(), weatherFill())
        compose.onRoot().captureRoboImage(File(shotsDir, "home_glass.png").absolutePath)
    }

    /** The category buttons at their own size, the first lined up with the search bar, the row past the right edge. */
    private fun theRowIsNotSqueezed(name: String) {
        show(glass = false)
        val boxes = categories.map { box(compose.onNodeWithContentDescription(it)) }
        for ((i, b) in boxes.withIndex()) {
            assertEquals("${categories[i]} wide", 44f, b.width, 0.6f)
            assertEquals("${categories[i]} high", 44f, b.height, 0.6f)
        }
        // the first lined up with the search bar and the bar, 12 dp in
        assertEquals(12f, boxes.first().left, 0.6f)
        for (i in 1 until boxes.size) assertEquals("8 dp apart", 8f, boxes[i].left - boxes[i - 1].right, 0.6f)
        assertTrue("the row runs on past the screen's edge: ${boxes.last().right}", boxes.last().right > 412f)
        compose.onRoot().captureRoboImage(File(shotsDir, "home_row_$name.png").absolutePath)
    }

    @Test
    fun theRowAtTheUsualWidth() = theRowIsNotSqueezed("412")

    @Test
    @Config(qualifiers = "w320dp-h711dp-xhdpi")
    fun theRowOnASmallPhone() = theRowIsNotSqueezed("320")

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun theRowOnA360Phone() = theRowIsNotSqueezed("360")

    @Test
    @Config(qualifiers = "w320dp-h711dp-xhdpi")
    fun theRowOnASmallPhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        theRowIsNotSqueezed("320_text130")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun theRowOnA360PhoneWithLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        theRowIsNotSqueezed("360_text130")
    }

    @Test
    fun aChosenButtonPastTheEdgeScrollsIntoView() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            AtlasTheme { Box(Modifier.width(412.dp).padding(horizontal = 12.dp)) { CategoryRow(selected, onSelect = { selected = it }) } }
        }
        compose.waitForIdle()
        val water = Categories.byId("water")!!.name("en")
        val d = compose.density.density
        val before = box(compose.onNodeWithContentDescription(water), d)
        assertTrue("water starts past the edge: ${before.right}", before.right > 412f)
        compose.runOnIdle { selected = "water" }
        compose.waitForIdle()
        val after = box(compose.onNodeWithContentDescription(water), d)
        assertEquals("water in view, the margin beside it", 412f - 12f, after.right, 1f)
        assertEquals(44f, after.width, 0.6f)
    }
}
