package app.atlas.ui.mine

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import app.atlas.Services
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Colour the map's second landing to look at (`colourmap_*.png` in the shots): the sliders as they say a colour, the
 * hex field given no colour, the save sheet given another palette's name, a palette renamed to another's, and a tap
 * where no map is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ColourMapShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(): Services = MineFixtures.withPalettes(testServices(places = true).apply { keepFixtureForecast() }, drawn = false)

    private fun show(dest: Dest, services: Services) {
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home(), Dest.MapLook(), dest).distinct())) }
        compose.settle(30_000)
    }

    private fun capture(name: String): File {
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        compose.settle()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun lightness() = compose.onNode(hasContentDescription("Lightness", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    /** The roofs, red tiles picked: the sliders and their numbers. */
    @Test
    fun sliders() {
        show(Dest.ColourMap, phone())
        compose.onNodeWithText("Roofs").performClick()
        compose.settle()
        capture("colourmap_sliders")
    }

    /** "#12G" typed in the hex field, then Done. */
    @Test
    fun hexNoColour() {
        show(Dest.ColourMap, phone())
        val field = compose.onNode(hasContentDescription("Hex colour"))
        field.performTextReplacement("#12G")
        field.performImeAction()
        compose.settle()
        capture("colourmap_hex_bad")
    }

    /** A colour changed, Save, and the name of the palette already saved typed in. */
    @Test
    fun saveUnderATakenName() {
        show(Dest.ColourMap, phone())
        lightness().performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.settle()
        compose.onNodeWithText("Save").performClick()
        compose.settle()
        compose.onNode(hasContentDescription("Name of the palette")).performTextReplacement("Canal evening")
        compose.settle()
        capture("colourmap_save_taken")
    }

    /** A second palette's sheet, its name typed over with the first one's. */
    @Test
    fun renameToATakenName() {
        val s = phone()
        runBlocking {
            s.settings.edit {
                it[SettingsKeys.MAP_PALETTES] = it[SettingsKeys.MAP_PALETTES] + "\n" + PaletteCode.write(Palette(Preset.INK, mapOf(Slot.WATER to 0x123456))) + "\tMine"
            }
        }
        show(Dest.MapLook(), s)
        compose.onNode(hasContentDescription("Share Mine")).performScrollTo().performClick()
        compose.settle()
        compose.onNode(hasContentDescription("Name of the palette")).performTextReplacement("Canal evening")
        compose.settle()
        capture("colourmap_rename_taken")
    }

    /** A tap on the map where it draws nothing to pick from (no pack here). */
    @Test
    fun tapWithNoMap() {
        show(Dest.ColourMap, phone())
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.3f)) }
        // the map answers a tap once its wait for a second tap is over
        val said = androidx.compose.ui.test.hasText("No map to pick from here", substring = true)
        runCatching { compose.waitUntil(10_000) { compose.onAllNodes(said).fetchSemanticsNodes().isNotEmpty() } }
        capture("colourmap_tap_nothing")
    }
}
