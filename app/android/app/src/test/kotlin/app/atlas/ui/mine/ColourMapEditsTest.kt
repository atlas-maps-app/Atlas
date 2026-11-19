package app.atlas.ui.mine

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteTheme
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.nav.Dest
import app.atlas.nav.Links
import app.atlas.services.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second landing's edits to Colour the map and the saved palettes, as their model does them (17 November): "All" a
 * group moves each colour from where it is, names are never doubled, and the shared message carries the link.
 */
class ColourMapEditsTest {
    private val p = Palette(Preset.ALPHA_22)

    // 11: "All <group>" shifts each colour from where it is, so an earlier change of one is moved, not wiped
    @Test
    fun allAGroupMovesEachColourFromWhereItIs() {
        val dark = ColourMapModel.withColour(p, Slot.DARK_TILES_1, 0x2A3A6A)
        val all = ColourMapModel.withGroup(dark, Slot.RED_TILES, 0x3E6E9E)
        assertEquals(0x3E6E9E, all.colour(Slot.RED_TILES))
        assertEquals(PaletteTheme.move(0x2A3A6A, p.colour(Slot.RED_TILES), 0x3E6E9E), all.colour(Slot.DARK_TILES_1))
        // and from the picked colour as it is: a second shift goes on from the first
        val red = ColourMapModel.withColour(p, Slot.RED_TILES, 0xB04030)
        val again = ColourMapModel.withGroup(red, Slot.RED_TILES, 0x3E6E9E)
        assertEquals(PaletteTheme.move(p.colour(Slot.SLATE), 0xB04030, 0x3E6E9E), again.colour(Slot.SLATE))
        // to where it is already: nothing moves
        assertEquals(dark, ColourMapModel.withGroup(dark, Slot.DARK_TILES_1, 0x2A3A6A))
    }

    // 18: the shared message carries the link that opens it in Atlas, and Atlas still reads its name and code
    @Test
    fun theSharedMessageCarriesTheLink() {
        val red = Palette(colours = mapOf(Slot.RED_TILES to 0x9E5038))
        val code = PaletteCode.write(red)
        val message = MapLookModel.message("Canal red", code)
        assertTrue(message, message.contains(MapLookModel.link(code)))
        assertEquals("Canal red", message.lineSequence().first())
        assertEquals(MapLookModel.Found.Ok(red, "Canal red"), MapLookModel.find(message))
        assertEquals(listOf(Dest.Home(), Dest.MapLook(message)), Links.forText(message))
    }

    // 17: names are never doubled: adding, saving and renaming
    @Test
    fun aPaletteAddedUnderANameAnotherHasIsNumbered() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, Palette(colours = mapOf(Slot.WATER to 0x123456)), "Mine", "Sunlit")
        MapLookWrites.add(m, Palette(colours = mapOf(Slot.WATER to 0x654321)), "mine", "Sunlit")
        assertEquals(listOf("Mine", "mine 2"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
    }

    @Test
    fun aPaletteSavedUnderAnotherPalettesNameIsNumberedButKeepingItsOwnReplacesIt() {
        val m = mutablePreferencesOf()
        MapLookWrites.save(m, Palette(colours = mapOf(Slot.WATER to 0x123456)), "Mine", null)
        val mine = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code
        MapLookWrites.save(m, Palette(colours = mapOf(Slot.WATER to 0x222222)), "Mine", null)
        assertEquals(listOf("Mine", "Mine 2"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        MapLookWrites.save(m, Palette(colours = mapOf(Slot.WATER to 0x333333)), "Mine", mine)
        assertEquals(listOf("Mine", "Mine 2"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
    }

    @Test
    fun aRenameToABlankNameOrAnotherPalettesIsRefused() {
        val m = mutablePreferencesOf()
        MapLookWrites.save(m, Palette(colours = mapOf(Slot.WATER to 0x123456)), "Mine", null)
        MapLookWrites.save(m, Palette(colours = mapOf(Slot.WATER to 0x222222)), "Yours", null)
        MapLookWrites.rename(m, 1, "MINE")
        MapLookWrites.rename(m, 1, "   ")
        assertEquals(listOf("Mine", "Yours"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        MapLookWrites.rename(m, 1, "Ours")
        assertEquals(listOf("Mine", "Ours"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
    }
}
