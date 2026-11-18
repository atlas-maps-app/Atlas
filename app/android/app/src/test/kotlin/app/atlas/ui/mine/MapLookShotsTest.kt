package app.atlas.ui.mine

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import app.atlas.Services
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
 * The Map look's and Colour the map's moments to look at, at a phone's size (`maplook_*.png` in the shots): the page as
 * it opens, a preset tapped over the user's own Tone and guesses, the Tone on Map look and on the Layers sheet for the
 * same state, the editor left with a change, adding a code, and the page in Dutch on a narrow phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapLookShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(): Services = MineFixtures.withPalettes(testServices(places = true).apply { keepFixtureForecast() }, drawn = false)

    private fun show(dest: Dest, services: Services): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav) }
        compose.settle(30_000)
        return nav
    }

    private fun capture(name: String): File {
        repeat(4) { compose.waitForIdle(); Thread.sleep(120) }
        compose.settle()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    /** The user's own state: the Tone on at 140 %, Atlas's guesses off. */
    private fun own(s: Services) = s.also {
        runBlocking {
            it.settings.edit { m ->
                m[SettingsKeys.MAP_TONE_ON] = true
                m[SettingsKeys.MAP_TONE] = 1.4f
                MapLookWrites.switch(m, app.atlas.mapview.palette.PaletteFlags.NO_GUESSES, true)
            }
        }
    }

    @Test
    fun opens() {
        show(Dest.MapLook(), phone())
        capture("maplook_opens")
    }

    /** Ink tapped over the Tone at 140 % and the guesses off. */
    @Test
    fun presetTapped() {
        show(Dest.MapLook(), own(phone()))
        compose.onAllNodesWithText("Ink").onFirst().performScrollTo().performClick()
        compose.settle()
        capture("maplook_preset_tapped")
    }

    /** The Tone switched on at a strength of 0: Map look's card. */
    @Test
    fun toneAtNothingOnMapLook() {
        val s = phone()
        runBlocking { s.settings.edit { it[SettingsKeys.MAP_TONE_ON] = true; it[SettingsKeys.MAP_TONE] = 0f } }
        show(Dest.MapLook(), s)
        capture("maplook_tone_zero")
    }

    /** The same state on the Layers sheet's Tone row. */
    @Test
    fun toneAtNothingOnLayers() {
        val s = phone()
        runBlocking { s.settings.edit { it[SettingsKeys.MAP_TONE_ON] = true; it[SettingsKeys.MAP_TONE] = 0f } }
        show(Dest.Layers, s)
        compose.onNode(hasContentDescription("Tone") and androidx.compose.ui.test.isToggleable()).performScrollTo()
        capture("maplook_layers_tone_zero")
    }

    /** Colour the map: a colour changed, then the close button. */
    @Test
    fun editorLeftWithAChange() {
        show(Dest.ColourMap, phone())
        compose.onNode(hasContentDescription("Lightness", substring = true)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.settle()
        compose.onNode(hasContentDescription("Leave without saving").or(hasContentDescription("Close"))).performClick()
        compose.settle()
        capture("maplook_editor_leave")
    }

    /** Add from a code, tapped with nothing on the clipboard. */
    @Test
    fun addACode() {
        show(Dest.MapLook(), phone())
        compose.onNode(hasText("Add from a code")).performScrollTo().performClick()
        compose.settle()
        capture("maplook_add")
    }

    /** In Dutch on a narrow phone. */
    @Test
    @Config(qualifiers = "nl-w360dp-h780dp-xhdpi")
    fun dutchNarrow() {
        show(Dest.MapLook(), phone())
        capture("maplook_nl_narrow")
    }
}
