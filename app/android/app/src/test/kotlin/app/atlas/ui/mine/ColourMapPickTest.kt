package app.atlas.ui.mine

import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteFace
import app.atlas.mapview.palette.PaletteFlags
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.services.SavedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tap in Colour the map picks, where its map opens, the sliders' plain numbers, undo's colour and the names' words. */
class ColourMapPickTest {
    // 3: a tap picks only a colour the map shows as the palette paints it
    @Test
    fun aTapPicksOnlyAColourThePaletteShowsThere() {
        val red = Slot.RED_TILES.code
        assertEquals(Slot.RED_TILES, ColourMapModel.picks(PaletteFace.of(red, 0)))
        // a guessed roof drawn plain while the guesses are off: nothing to pick
        assertNull(ColourMapModel.picks(PaletteFace.of(red, PaletteFlags.NO_GUESSES)))
        // a mapped colour kept as mapped picks nothing; once mapped colours follow, its nearest slot
        assertNull(ColourMapModel.picks(PaletteFace.of(red or PaletteFace.MAPPED, 0)))
        assertEquals(Slot.RED_TILES, ColourMapModel.picks(PaletteFace.of(red or PaletteFace.MAPPED, PaletteFlags.MAPPED_FOLLOW)))
        // no colour of the palette goes with it
        assertNull(ColourMapModel.picks(PaletteFace.of(0, 0)))
    }

    // 4: the editor opens where the map last was, near enough to see the buildings, else here
    @Test
    fun theEditorOpensWhereTheMapLastWas() {
        val here = LatLon(52.3745, 4.8838)
        val last = LatLon(52.0907, 5.1214)
        assertEquals(CameraPosition(last, 17.0, 30.0, 45.0), ColourMapModel.start(SavedCamera(last, 17.0, 30.0, 0.0), here))
        assertEquals(15.0, ColourMapModel.start(SavedCamera(last, 6.0), here).zoom, 0.0)
        assertEquals(here, ColourMapModel.start(null, here).target)
    }

    // 13: the sliders say 0 to 100, and reach white and black
    @Test
    fun theSlidersSayZeroToAHundredAndReachWhite() {
        val white = ColourMapModel.lch(0xFFFFFF)
        assertEquals(100, ColourMapModel.shown(white).first)
        assertEquals(0, ColourMapModel.shown(white).second)
        assertEquals(0xFFFFFF, ColourMapModel.rgb(ColourMapModel.Lch(ColourMapModel.L_MAX, 0f, 0f)))
        assertEquals(0x000000, ColourMapModel.rgb(ColourMapModel.Lch(ColourMapModel.L_MIN, 0f, 0f)))
        val (l, c, h) = ColourMapModel.shown(ColourMapModel.lch(0x9E5038))
        assertEquals(true, l in 0..100 && c in 0..100 && h in 0..100)
    }

    // 10: undo brings the colour it changes back into view
    @Test
    fun undoKnowsTheColourItBringsBack() {
        val p = Palette(Preset.ALPHA_22)
        assertEquals(Slot.LAWN, ColourMapModel.differing(ColourMapModel.withColour(p, Slot.LAWN, 0x123456), p))
        assertNull(ColourMapModel.differing(p, p))
    }

    // 11: Reset goes back to what the editor opened with, not to the preset
    @Test
    fun resetGoesBackToWhatTheEditorOpenedWith() {
        val opened = ColourMapModel.withColour(Palette(Preset.ALPHA_22), Slot.PAPER, 0x123456)
        val now = ColourMapModel.withColour(opened, Slot.PAPER, 0x654321)
        assertEquals(0x123456, ColourMapModel.reset(now, Slot.PAPER, opened).colour(Slot.PAPER))
    }

    // 17: what a name says, and the next free one
    @Test
    fun aNameIsFineBlankOrTaken() {
        assertEquals(MapLookModel.NameSays.Fine, MapLookModel.nameSays("Ours", listOf("Mine")))
        assertEquals(MapLookModel.NameSays.Blank, MapLookModel.nameSays(" \t ", listOf("Mine")))
        assertEquals(MapLookModel.NameSays.Taken("mine"), MapLookModel.nameSays(" mine ", listOf("Mine")))
        assertEquals("Mine 3", MapLookModel.unique("Mine", listOf("Mine", "Mine 2")))
        assertEquals(MapLookModel.NAME_MAX, MapLookModel.unique("x".repeat(40), listOf("x".repeat(40))).length)
    }
}
