package app.atlas.ui.mine

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.atlas.Services
import app.atlas.mapview.MapEngine
import app.atlas.mapview.Theme
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteFlags
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * The Map look and Colour the map as a person uses them, through the real navigation: each of the lead's eight
 * decisions of 17 November held by what the screens do (a preset's tap, the two Tones, the editor's leave, its draft,
 * Save, a code typed in, day and night, the words).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapLookScreenTest {
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

    /** Waits until [check] holds (a write reaches the settings on its own thread, then the screen), then checks it once more. */
    private fun soon(check: () -> Unit) {
        runCatching { compose.waitUntil(10_000) { runCatching(check).isSuccess } }
        check()
    }

    /** The hex field's text: the picked colour as the editor holds it. */
    private fun hex(): String = compose.onNode(hasContentDescription("Hex colour")).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun switch(name: String) = compose.onNode(hasContentDescription(name) and isToggleable())

    private fun lightness() = compose.onNode(hasContentDescription("Lightness", substring = true))

    /** The Lightness slider's words (its name and value), as TalkBack reads them. */
    private fun lightnessSaid(): String = lightness().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    private fun change() {
        lightness().performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        compose.settle()
    }

    // 1 and 2: a look is whole, a tap shows it; the guesses are the user's.
    @Test
    fun aPresetsTapBringsItsWholeLookAndTheControlsMoveButTheGuessesStay() {
        val s = phone()
        s.edit {
            it[SettingsKeys.MAP_TONE_ON] = true
            it[SettingsKeys.MAP_TONE] = 1.4f
            MapLookWrites.switch(it, PaletteFlags.NO_GUESSES, true)
        }
        show(Dest.MapLook(), s)
        // Ink brings the Tone off, and the strength the user had stays for the switch to bring back
        compose.onAllNodesWithText("Ink").onFirst().performScrollTo().performClick()
        compose.settle()
        soon { switch("Tone").assertIsOff() }
        soon { compose.onNodeWithText("Paper").assertIsSelected() }
        soon { switch("Atlas’s guesses").assertIsOff() }
        switch("Tone").performClick()
        compose.settle()
        soon { assertEquals(true, s.prefs()[SettingsKeys.MAP_TONE_ON]) }
        soon { assertEquals(1.4f, s.prefs()[SettingsKeys.MAP_TONE]) }
        // Real colour brings its grass and its Tone; the guesses stay off
        compose.onAllNodesWithText("Real colour").onFirst().performScrollTo().performClick()
        compose.settle()
        soon { switch("Tone").assertIsOn() }
        soon { assertEquals(1f, s.prefs()[SettingsKeys.MAP_TONE]) }
        soon { compose.onNodeWithText("Grass").assertIsSelected() }
        soon { switch("Atlas’s guesses").assertIsOff() }
        soon { assertEquals(Preset.REAL, MapLookModel.current(s.prefs()[SettingsKeys.MAP_PALETTE]).base) }
        val real = MapLookModel.current(s.prefs()[SettingsKeys.MAP_PALETTE])
        assertEquals(Preset.REAL, real.base)
        assertEquals(PaletteFlags.GRASS or PaletteFlags.NO_GUESSES, real.switches)
        assertTrue(real.colours.isEmpty())
        // a tap on the look the map draws changes nothing
        soon { assertEquals(Preset.REAL, MapLookModel.current(s.prefs()[SettingsKeys.MAP_PALETTE]).base) }
        val before = s.prefs()
        compose.onAllNodesWithText("Real colour").onFirst().performScrollTo().performClick()
        compose.settle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(before, s.prefs())
    }

    // 3: one Tone, shown alike on both.
    @Test
    fun theToneReadsTheSameOnTheMapLookAndOnTheLayersSheet() {
        val s = phone()
        val nav = show(Dest.MapLook(), s)
        for ((on, strength, said) in listOf(Triple(true, 0f, "Off"), Triple(false, 1.4f, "Off"), Triple(true, 1.4f, "140 %"))) {
            s.edit { it[SettingsKeys.MAP_TONE_ON] = on; it[SettingsKeys.MAP_TONE] = strength }
            compose.settle()
            soon { assertTrue("Map look, $on $strength", compose.onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty()) }
            soon { if (said == "Off") switch("Tone").assertIsOff() else switch("Tone").assertIsOn() }
            compose.runOnUiThread { nav.go(Dest.Layers) }
            compose.settle(30_000)
            switch("Tone").performScrollTo()
            soon { assertTrue("Layers, $on $strength", compose.onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty()) }
            soon { if (said == "Off") switch("Tone").assertIsOff() else switch("Tone").assertIsOn() }
            compose.runOnUiThread { nav.back() }
            compose.settle()
        }
    }

    // 3: the Map look's strength shows on the map while the finger drags, and is kept when it lifts; off is 0.
    @Test
    fun theMapLooksToneDragsTheMapAlong() {
        val s = phone()
        s.edit { it[SettingsKeys.MAP_TONE_ON] = true; it[SettingsKeys.MAP_TONE] = 1f }
        show(Dest.MapLook(), s)
        val track = compose.onNode(hasContentDescription("Tone 100 %"))
        track.performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.5f, 0f))
            moveTo(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.62f, 0f))
            moveTo(centerLeft + androidx.compose.ui.geometry.Offset(width * 0.75f, 0f))
        }
        compose.waitForIdle()
        assertEquals(1.5f, MapEngine.tone, 0.051f)
        soon { assertEquals(1f, s.prefs()[SettingsKeys.MAP_TONE]) }
        compose.onNode(hasContentDescription("Tone", substring = true) and hasContentDescription("%", substring = true)).performTouchInput {
            moveTo(centerLeft)
            up()
        }
        compose.settle()
        // dragged to the off end: off, and the strength kept is the one it had
        soon { assertEquals(false, s.prefs()[SettingsKeys.MAP_TONE_ON]) }
        soon { assertEquals(1f, s.prefs()[SettingsKeys.MAP_TONE]) }
        soon { switch("Tone").assertIsOff() }
    }

    // 3: the Layers sheet writes the Tone the same way.
    @Test
    fun theLayersSheetsToneSwitchBringsTheLastStrengthBack() {
        val s = phone()
        s.edit { it[SettingsKeys.MAP_TONE_ON] = true; it[SettingsKeys.MAP_TONE] = 0f }
        show(Dest.Layers, s)
        // a strength of 0 is off; the switch brings today's back where there was no other
        switch("Tone").performScrollTo().assertIsOff().performClick()
        compose.settle()
        soon { assertEquals(true, s.prefs()[SettingsKeys.MAP_TONE_ON]) }
        soon { assertEquals(1f, s.prefs()[SettingsKeys.MAP_TONE]) }
    }

    // 4: the ring and the line tell the truth.
    @Test
    fun theLineSaysAPresetIsChangedOnceItsToneIs() {
        val s = phone()
        show(Dest.MapLook(), s)
        compose.onAllNodesWithText("Quiet").onFirst().performScrollTo().performClick()
        compose.settle()
        soon { compose.onNode(hasText("Quiet") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected() }
        switch("Tone").performClick()
        compose.settle()
        soon { assertTrue(compose.onAllNodesWithText("Quiet · changed").fetchSemanticsNodes().isNotEmpty()) }
        assertTrue(compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and hasText("Quiet")).fetchSemanticsNodes().isEmpty())
    }

    // 5: Colour the map never loses work silently.
    @Test
    fun leavingTheEditorWithAChangeAsks() {
        val s = phone()
        val nav = show(Dest.ColourMap, s)
        change()
        compose.onNode(hasContentDescription("Close")).performClick()
        compose.settle()
        assertEquals(Dest.ColourMap, nav.stack.last())
        compose.onNodeWithText("Keep editing").performClick()
        compose.settle()
        assertEquals(Dest.ColourMap, nav.stack.last())
        compose.onNode(hasContentDescription("Close")).performClick()
        compose.settle()
        compose.onNodeWithText("Discard").performClick()
        compose.settle()
        soon { assertEquals(Dest.MapLook(), nav.stack.last()) }
        assertEquals(MapLookModel.current(null), MapEngine.palette)
        assertEquals(1, s.saved().size)
    }

    @Test
    fun theEditorsDraftSurvivesTheAppBeingKilled() {
        val s = phone()
        val nav = BackStackNavigator(listOf(Dest.Home(), Dest.MapLook(), Dest.ColourMap))
        val restore = StateRestorationTester(compose)
        restore.setContent { AtlasRoot(s, nav) }
        compose.settle(30_000)
        val opened = hex()
        change()
        val changed = hex()
        assertNotEquals(opened, changed)
        restore.emulateSavedInstanceStateRestore()
        compose.settle(30_000)
        assertEquals(changed, hex())
        compose.onNode(hasContentDescription("Close")).performClick()
        compose.settle()
        compose.onNodeWithText("Keep editing").assertExists()
    }

    @Test
    fun saveSavesOnceUnderTheNameGivenAndSaysSo() {
        val s = phone(palettes = false)
        val nav = show(Dest.ColourMap, s)
        change()
        compose.onNodeWithText("Save").performClick()
        compose.settle()
        compose.onNode(hasContentDescription("Name of the palette")).performTextReplacement("Brighter")
        // a double tap: both in one frame, before the first can close anything
        val tap = compose.onNode(hasTestTag("save-sheet")).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread {
            tap()
            tap()
        }
        compose.settle()
        soon { assertEquals(listOf("Brighter"), s.saved().map { it.name }) }
        soon { assertEquals(Dest.MapLook(), nav.stack.last()) }
        soon { assertEquals("Saved as Brighter", ShadowToast.getTextOfLatestToast()) }
    }

    @Test
    fun holdingBeforeShowsWhatTheMapDrewAsTheEditorOpened() {
        val s = phone()
        s.edit { MapLookWrites.take(it, Palette(Preset.QUIET)) }
        show(Dest.ColourMap, s)
        change()
        assertNotEquals(Palette(Preset.QUIET), MapEngine.palette)
        val before = compose.onNode(hasContentDescription("Hold to see the map as it was"))
        before.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(Palette(Preset.QUIET), MapEngine.palette)
        before.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertNotEquals(Palette(Preset.QUIET), MapEngine.palette)
    }

    // 6: day and night in the header.
    @Test
    fun theHeaderChoosesDayOrNight() {
        val s = phone()
        show(Dest.MapLook(), s)
        compose.onNode(hasContentDescription("Night")).performClick()
        compose.settle()
        soon { assertEquals(Theme.NIGHT.name, s.prefs()[SettingsKeys.MAP_THEME]) }
        soon { compose.onNode(hasContentDescription("Night")).assertIsSelected() }
        compose.onNode(hasContentDescription("Auto")).performClick()
        compose.settle()
        soon { assertEquals(Theme.AUTO.name, s.prefs()[SettingsKeys.MAP_THEME]) }
    }

    // 8: a code typed or pasted, with the right words for each failure.
    @Test
    fun aCodeTypedInSaysWhatIsWrongWithIt() {
        val s = phone()
        show(Dest.MapLook(), s)
        compose.onNode(hasText("Add from a code")).performScrollTo().performClick()
        compose.settle()
        val field = compose.onNode(hasContentDescription("Palette code"))
        val good = PaletteCode.write(Palette(Preset.SEPIA, mapOf(Slot.WATER to 0x123456)))
        val wrong = good.dropLast(1) + (if (good.last() == '0') '1' else '0')
        val canal = s.saved().single().code
        for ((text, said) in listOf(
            "hello" to "That isn’t a palette code: they start with AT1-",
            "AT1-SEPIA" to "A letter is wrong or missing: check the code",
            wrong to "A letter is wrong or missing: check the code",
            "AT2-SEPIA-K7QF2-M4XRB" to "This palette is from a newer Atlas: update Atlas to add it",
            "AT1-NOPE-K7QF2-M4XRB" to "This palette is from a newer Atlas: update Atlas to add it",
            canal to "Already yours, as Canal evening",
        )) {
            field.performTextReplacement(text)
            compose.onNodeWithText("Add").performClick()
            compose.settle()
            soon { assertTrue("$text: $said", compose.onAllNodesWithText(said).fetchSemanticsNodes().isNotEmpty()) }
        }
        assertEquals(1, s.saved().size)
        field.performTextReplacement("Grey canal\n$good")
        compose.onNodeWithText("Add").performClick()
        compose.settle()
        soon { assertEquals(listOf("Canal evening", "Grey canal"), s.saved().map { it.name }) }
        soon { assertEquals("Added Grey canal", ShadowToast.getTextOfLatestToast()) }
    }

    @Test
    fun aSavedPaletteThisAtlasCantReadIsShownAndCanBeDeleted() {
        val s = phone()
        s.edit { it[SettingsKeys.MAP_PALETTES] = it[SettingsKeys.MAP_PALETTES] + "\nAT1-NOPE-K7QF2-M4XRB\tFrom the future" }
        show(Dest.MapLook(), s)
        compose.onNodeWithText("From the future").performScrollTo()
        compose.onNodeWithText("From a newer Atlas: update Atlas to see it").assertExists()
        compose.onNode(hasContentDescription("Delete From the future")).performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.settle()
        soon { assertEquals(listOf("Canal evening"), s.saved().map { it.name }) }
    }
}
