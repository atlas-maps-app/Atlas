package app.atlas.ui.mine

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Colour the map's second landing as a person uses it (17 November): undo keeps a step only for a change and shows
 * what it brings back, Reset goes back to what the editor opened with, "All" stays with its group, the sliders say 0 to
 * 100 and reach white, the hex field says when it is no colour, and no name is doubled (saving, renaming).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ColourMapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(palettes: Boolean = true): Services =
        testServices(places = false).apply { keepFixtureForecast() }.let { if (palettes) MineFixtures.withPalettes(it, drawn = false) else it }

    private fun show(dest: Dest, services: Services): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), Dest.MapLook(), dest).distinct())
        compose.setContent { AtlasRoot(services, nav) }
        compose.settle(30_000)
        return nav
    }

    private fun Services.edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) = runBlocking { settings.edit(block) }
    private fun Services.prefs() = runBlocking { settings.data.first() }
    private fun Services.saved() = MapLookModel.savedOf(prefs()[SettingsKeys.MAP_PALETTES])

    private fun soon(check: () -> Unit) {
        runCatching { compose.waitUntil(10_000) { runCatching(check).isSuccess } }
        check()
    }

    private fun hexField() = compose.onNode(hasContentDescription("Hex colour"))
    private fun hex(): String = hexField().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun slider(name: String) = compose.onNode(hasContentDescription(name, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    /** The saturation slider, by its name today ("Saturation 12") or before ("Colour 0.03"). */
    private fun saturation() = compose.onNode(
        (hasContentDescription("Saturation", substring = true) or hasContentDescription("Colour 0.", substring = true))
            and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
    )

    private fun set(node: androidx.compose.ui.test.SemanticsNodeInteraction, at: Float) {
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(at) }
        compose.settle()
    }

    private fun undo() = compose.onNode(hasContentDescription("Undo"))

    private fun tab(name: String) = compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    // 10: a drag that changes nothing keeps no step
    @Test
    fun undoKeepsAStepOnlyForAChange() {
        show(Dest.ColourMap, phone())
        // a grey: its hue changes nothing
        set(saturation(), 0f)
        slider("Hue").performTouchInput { swipeRight() }
        compose.settle()
        undo().assertIsEnabled().performClick()
        compose.settle()
        soon { undo().assertIsNotEnabled() }
    }

    // 10: undo shows the colour it brings back
    @Test
    fun undoShowsTheColourItBringsBack() {
        show(Dest.ColourMap, phone())
        val paper = hex()
        set(slider("Lightness"), 0.25f)
        tab("Water").performClick()
        compose.settle()
        undo().performClick()
        compose.settle()
        soon { assertEquals(paper, hex()) }
        soon { compose.onNode(hasText("Paper")).assertExists() }
    }

    // 11: Reset goes back to what the editor opened with
    @Test
    fun resetGoesBackToWhatTheEditorOpenedWith() {
        val s = phone()
        s.edit { it[SettingsKeys.MAP_PALETTE] = PaletteCode.write(Palette(Preset.ALPHA_22, mapOf(Slot.PAPER to 0x123456))) }
        show(Dest.ColourMap, s)
        assertEquals("#123456", hex())
        set(slider("Lightness"), 0.9f)
        compose.onNodeWithText("Reset").performClick()
        compose.settle()
        soon { assertEquals("#123456", hex()) }
    }

    // 11: "All" is the group's, and goes off with another group
    @Test
    fun allStaysWithItsGroup() {
        show(Dest.ColourMap, phone())
        compose.onNodeWithText("All ground").performClick()
        compose.settle()
        tab("Water").performClick()
        compose.settle()
        compose.onNode(hasText("All water")).assertIsNotSelected()
    }

    // 13: the sliders reach white, and say 0 to 100
    @Test
    fun theSlidersReachWhiteAndSayZeroToAHundred() {
        show(Dest.ColourMap, phone())
        set(saturation(), 0f)
        set(slider("Lightness"), 1f)
        soon { assertEquals("#FFFFFF", hex()) }
        set(slider("Hue"), 0.5f)
        for (n in listOf(slider("Lightness"), saturation(), slider("Hue"))) {
            val said = n.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
            val value = said.substringAfterLast(' ')
            assertTrue(said, value.toIntOrNull()?.let { it in 0..100 } == true)
        }
    }

    // 13: the hex field says when it is no colour, and keeps what was typed
    @Test
    fun theHexFieldSaysWhenItIsNoColour() {
        show(Dest.ColourMap, phone())
        val was = hex()
        hexField().performTextReplacement("#12G")
        hexField().performImeAction()
        compose.settle()
        soon { compose.onNode(hasText("Not a colour", substring = true)).assertExists() }
        assertEquals("#12G", hex())
        hexField().performTextReplacement("#A0522D")
        hexField().performImeAction()
        compose.settle()
        soon { assertEquals("#A0522D", hex()) }
        assertTrue(was != "#A0522D")
    }

    // 17: saving under another palette's name is refused, and the sheet says why
    @Test
    fun saveRefusesANameAnotherPaletteHas() {
        val s = phone()
        show(Dest.ColourMap, s)
        set(slider("Lightness"), 0.25f)
        compose.onNodeWithText("Save").performClick()
        compose.settle()
        compose.onNode(hasContentDescription("Name of the palette")).performTextReplacement("Canal evening")
        compose.settle()
        compose.onNode(hasTestTag("save-sheet")).performClick()
        compose.settle()
        soon { compose.onNode(hasText("You already have a palette called Canal evening")).assertExists() }
        assertEquals(listOf("Canal evening"), s.saved().map { it.name })
    }

    // 17: renaming to another palette's name or to nothing is refused, and the sheet says why
    @Test
    fun renameRefusesATakenOrBlankName() {
        val s = phone()
        s.edit {
            it[SettingsKeys.MAP_PALETTES] = it[SettingsKeys.MAP_PALETTES] + "\n" + PaletteCode.write(Palette(Preset.INK, mapOf(Slot.WATER to 0x123456))) + "\tMine"
        }
        show(Dest.MapLook(), s)
        compose.onNode(hasContentDescription("Share Mine")).performScrollTo().performClick()
        compose.settle()
        val name = compose.onNode(hasContentDescription("Name of the palette"))
        name.performTextReplacement("Canal evening")
        compose.settle()
        soon { compose.onNode(hasText("You already have a palette called Canal evening")).assertExists() }
        name.performTextReplacement("")
        compose.settle()
        soon { compose.onNode(hasText("A palette needs a name")).assertExists() }
        name.performTextReplacement("Canal evening")
        compose.onNode(hasContentDescription("Close")).performClick()
        compose.settle()
        soon { assertEquals(listOf("Canal evening", "Mine"), s.saved().map { it.name }) }
    }

    // 4: a tap where no map is drawn to pick from says so
    @Test
    fun aTapWithNoMapToPickFromSaysSo() {
        show(Dest.ColourMap, phone())
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * 0.3f)) }
        compose.settle()
        soon { compose.onNode(hasText("No map to pick from here", substring = true)).assertExists() }
    }
}
