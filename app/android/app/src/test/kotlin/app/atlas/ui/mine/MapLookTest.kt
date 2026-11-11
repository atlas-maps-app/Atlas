package app.atlas.ui.mine

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.atlas.mapview.palette.Family
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteFlags
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.nav.Dest
import app.atlas.nav.Links
import app.atlas.services.SettingsKeys
import io.nayuki.qrcodegen.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The Map look's palette as the settings keep it, its switches and Tone, and the saved palettes' lines and names. */
class MapLookModelTest {
    private val red = Palette(colours = mapOf(Slot.RED_TILES to 0x9E5038))

    @Test
    fun theDefaultLookIsKeptAsNothingAndAnyOtherAsItsCodeWithoutTheTone() {
        // the default look is alpha 22's, for now (the user, 4 November: "Alpha 22s look by default for now")
        val a22 = Palette(Preset.ALPHA_22)
        assertNull(MapLookModel.keep(a22))
        assertNull(MapLookModel.keep(a22.copy(tone = 1.5f)))
        assertEquals(a22, MapLookModel.current(null))
        assertEquals(a22, MapLookModel.current("AT1-NOPE-00000"))
        // today's look is a pick like any other
        assertEquals(Palette(), MapLookModel.current(MapLookModel.keep(Palette(tone = 1.5f))))
        val quiet = Palette(Preset.QUIET, mapOf(Slot.WATER to 0x123456), tone = 0.5f)
        assertEquals(quiet.copy(tone = null), MapLookModel.current(MapLookModel.keep(quiet)))
    }

    @Test
    fun aSwitchOrTheToneAsTheBaseHasItIsNoneOfThePalettesOwn() {
        val a22 = Palette(Preset.ALPHA_22)
        val off = MapLookModel.withSwitch(a22, PaletteFlags.FLAT_GREYS, false)
        assertEquals(0, off.flags)
        assertFalse(MapLookModel.on(off, PaletteFlags.FLAT_GREYS))
        assertNull(MapLookModel.withSwitch(off, PaletteFlags.FLAT_GREYS, true).flags)
        assertNull(MapLookModel.withTone(Palette(), true, 1f).tone)
        assertEquals(0f, MapLookModel.withTone(Palette(), false, 1.4f).tone)
        assertEquals(1.4f, MapLookModel.withTone(Palette(), true, 1.4f).tone)
        // alpha 22 brings the Tone off, and taking it turns it off at today's strength
        assertEquals(false to 1f, MapLookModel.toneOf(a22))
        assertEquals(true to 1.5f, MapLookModel.toneOf(Palette(tone = 1.5f)))
    }

    @Test
    fun theGlyphsThirdRoofIsAFlatGreyWhereTheFlatGreysAreOn() {
        val roof = MapLookModel.GLYPH.indexOf(Slot.BITUMEN)
        assertEquals(Slot.BITUMEN.today, MapLookModel.glyph(Palette())[roof])
        assertEquals(Slot.FLAT_GREY_1.today, MapLookModel.glyph(Palette(Preset.ALPHA_22))[roof])
        assertEquals(0x9E5038, MapLookModel.glyph(Palette(Preset.ALPHA_22))[MapLookModel.GLYPH.indexOf(Slot.RED_TILES)])
    }

    @Test
    fun savedPalettesAreALineEachAndTheirNamesOneLine() {
        val saved = listOf(SavedPalette("Mine", PaletteCode.write(red)), SavedPalette("Two\tlines\nhere", PaletteCode.write(Palette(Preset.INK))))
        val text = MapLookModel.textOf(saved)!!
        assertEquals(listOf(saved[0], saved[1].copy(name = "Two lines here")), MapLookModel.savedOf(text))
        assertNull(MapLookModel.textOf(emptyList()))
        assertEquals(emptyList<SavedPalette>(), MapLookModel.savedOf(null))
        assertEquals("x".repeat(MapLookModel.NAME_MAX), MapLookModel.clean("  " + "x".repeat(60)))
        assertEquals("Quiet 1", MapLookModel.newName("Quiet", emptyList()))
        assertEquals("Quiet 3", MapLookModel.newName("Quiet", listOf("Quiet 1", "Quiet 2", "Sepia 3")))
        // the saved palette the map draws, whatever its Tone
        assertEquals(0, MapLookModel.savedIndex(saved, red.copy(tone = 0.4f)))
        assertEquals(-1, MapLookModel.savedIndex(saved, Palette()))
    }

    @Test
    fun aCodeIsFoundAloneInItsLinkOrInAMessageWithItsName() {
        val code = PaletteCode.write(red)
        assertEquals(code, MapLookModel.codeIn(code))
        assertEquals(code, MapLookModel.codeIn(MapLookModel.link(code)))
        assertEquals(code, MapLookModel.codeIn(MapLookModel.message("Canal red", code)))
        assertEquals(code.lowercase(), MapLookModel.codeIn("look: ${code.lowercase()}."))
        assertNull(MapLookModel.codeIn("AT1 is a code"))
        assertNull(MapLookModel.codeIn(null))
        assertEquals("Canal red", MapLookModel.nameIn(MapLookModel.message("Canal red", code)))
        assertEquals("Canal red", MapLookModel.nameIn("Canal red: $code"))
        assertNull(MapLookModel.nameIn(code))
        assertNull(MapLookModel.nameIn(MapLookModel.link(code)))
    }

    @Test
    fun aPalettesLinkOrAMessageHoldingItsCodeOpensTheMapLook() {
        val code = PaletteCode.write(red)
        assertEquals(listOf(Dest.Home(), Dest.MapLook(MapLookModel.link(code))), Links.forUri(MapLookModel.link(code)))
        val message = MapLookModel.message("Canal red", code)
        assertEquals(listOf(Dest.Home(), Dest.MapLook(message)), Links.forText(message))
        assertEquals(Dest.MapLook(code), Dest.parse(Dest.MapLook(code).route))
        assertEquals(Dest.MapLook(), Dest.parse(Dest.MapLook().route))
    }

    @Test
    fun theQrCodeHoldsTheSchemeAsBytesAndTheCodeAsLettersAndDigits() {
        val link = MapLookModel.link(PaletteCode.write(Palette(Preset.SEPIA, mapOf(Slot.WATER to 0x123456, Slot.LAWN to 0x6E9448))))
        val qr = PaletteQr.encode(link)
        // the code's letters and digits as alphanumerics take fewer modules than the whole link as bytes
        assertTrue(qr.size <= QrCode.encodeText(link, QrCode.Ecc.MEDIUM).size)
        assertEquals(QrCode.Ecc.MEDIUM, qr.errorCorrectionLevel)
    }
}

/** The Map look's writes: what the map takes, and the saved palettes kept with it. */
class MapLookWritesTest {
    private val red = Palette(colours = mapOf(Slot.RED_TILES to 0x9E5038))

    @Test
    fun takingAPaletteSetsTheMapsPaletteAndTheToneItBrings() {
        val m = mutablePreferencesOf()
        MapLookWrites.take(m, Palette(Preset.ALPHA_22))
        assertEquals(Palette(Preset.ALPHA_22), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        assertEquals(false, m[SettingsKeys.MAP_TONE_ON])
        // the default look is kept as nothing, and today's as its code
        assertNull(m[SettingsKeys.MAP_PALETTE])
        MapLookWrites.take(m, Palette())
        assertEquals(Palette(), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        assertEquals(true, m[SettingsKeys.MAP_TONE_ON])
        assertEquals(1f, m[SettingsKeys.MAP_TONE])
    }

    @Test
    fun addingSavesOnceUnderANameAndTheMapTakesIt() {
        val m = mutablePreferencesOf()
        assertEquals("Today 1", MapLookWrites.add(m, red, null, "Today"))
        assertEquals("Today 1", MapLookWrites.add(m, red.copy(tone = 0.3f), "Other", "Today"))
        assertEquals("Ink", MapLookWrites.add(m, Palette(Preset.INK), " Ink ", "Ink"))
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        assertEquals(listOf("Today 1", "Ink"), saved.map { it.name })
        assertEquals(Palette(Preset.INK), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
    }

    @Test
    fun aSwitchOrTheToneChangedWhileASavedPaletteIsDrawnChangesItToo() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Today")
        MapLookWrites.switch(m, PaletteFlags.GRASS, true)
        val grass = MapLookModel.withSwitch(red, PaletteFlags.GRASS, true)
        assertEquals(grass, MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        assertEquals(grass, MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().palette?.copy(tone = null))
        MapLookWrites.tone(m, true, 1.5f)
        assertEquals(1.5f, MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().palette?.tone)
        // the map's own palette never carries the Tone
        assertNull(MapLookModel.current(m[SettingsKeys.MAP_PALETTE]).tone)
    }

    @Test
    fun savingReplacesThePaletteItWasOpenedOnElseSavesAChangedOne() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Today")
        val from = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code
        val bluer = red.copy(colours = red.colours + (Slot.WATER to 0x2244AA))
        MapLookWrites.save(m, bluer, from, "Today")
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        assertEquals(listOf("Mine"), saved.map { it.name })
        assertEquals(bluer, saved.single().palette?.copy(tone = null))
        assertEquals(bluer, MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        // from a preset: a palette that changes a colour is saved, one that changes none is not
        MapLookWrites.save(m, Palette(Preset.QUIET), null, "Quiet")
        assertEquals(1, MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).size)
        assertEquals(Palette(Preset.QUIET), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        MapLookWrites.save(m, Palette(Preset.QUIET, mapOf(Slot.LAWN to 0x00FF00)), null, "Quiet")
        assertEquals(listOf("Mine", "Quiet 1"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
    }

    @Test
    fun removingAndRenaming() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Today")
        MapLookWrites.add(m, Palette(Preset.INK), "Ink", "Ink")
        MapLookWrites.rename(m, 0, "  Canal\tred ")
        MapLookWrites.rename(m, 1, "   ")
        MapLookWrites.rename(m, 5, "Nowhere")
        assertEquals(listOf("Canal red", "Ink"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        MapLookWrites.remove(m, 0)
        assertEquals(listOf("Ink"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        MapLookWrites.remove(m, 0)
        assertNull(m[SettingsKeys.MAP_PALETTES])
        // the map draws what it drew
        assertEquals(Palette(Preset.INK), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
    }
}

/** Colour the map's colours: OKLCH and hex, the board's light, and a colour changed alone or with its group. */
class ColourMapModelTest {
    @Test
    fun oklchRoundTripsAndHexReads() {
        for (rgb in listOf(0xAE573F, 0x4F7590, 0xD6CFC2, 0x000000, 0xFFFFFF, 0x1E9E5A)) {
            val back = ColourMapModel.rgb(ColourMapModel.lch(rgb))
            for (shift in intArrayOf(16, 8, 0)) assertTrue(abs(((back shr shift) and 0xFF) - ((rgb shr shift) and 0xFF)) <= 1)
        }
        assertEquals("#AE573F", ColourMapModel.hex(0xAE573F))
        assertEquals(0xAABBCC, ColourMapModel.hexOf("#abc"))
        assertEquals(0x123456, ColourMapModel.hexOf(" 123456 "))
        assertNull(ColourMapModel.hexOf("#12345"))
        assertNull(ColourMapModel.hexOf("#GGGGGG"))
    }

    @Test
    fun theLightIsTheBoards() {
        // Colour.dc.html's light(h, lam, night): in sun (0.62), in shade, at night
        val board = mapOf(
            0xAE573F to listOf(0xED8361, 0x8E4F40, 0x352529),
            0xE8E2D4 to listOf(0xFBFAF5, 0xB9B6AF, 0x414349),
            0x4F7590 to listOf(0x7BABC7, 0x47657D, 0x202C3B),
            0xFFFFFF to listOf(0xFDFDFC, 0xCACCCF, 0x464A53),
        )
        for ((rgb, lit) in board) {
            assertEquals(lit[0], ColourMapModel.light(rgb, 0.62, false))
            assertEquals(lit[1], ColourMapModel.light(rgb, 0.0, false))
            assertEquals(lit[2], ColourMapModel.light(rgb, 0.0, true))
        }
    }

    @Test
    fun aColourAloneOrWithItsGroup() {
        val p = Palette()
        val one = ColourMapModel.withColour(p, Slot.RED_TILES, 0x9E5038)
        assertEquals(mapOf(Slot.RED_TILES to 0x9E5038), one.colours)
        assertTrue(ColourMapModel.changed(one, Slot.RED_TILES))
        // back to the preset's colour: none of its own
        assertEquals(emptyMap<Slot, Int>(), ColourMapModel.withColour(one, Slot.RED_TILES, Slot.RED_TILES.today).colours)
        assertEquals(emptyMap<Slot, Int>(), ColourMapModel.reset(one, Slot.RED_TILES).colours)
        // the group moves with it: the colour exactly, the others by the palette's move, none left as they were
        val all = ColourMapModel.withGroup(p, Slot.RED_TILES, 0x3E6E9E)
        assertEquals(0x3E6E9E, all.colour(Slot.RED_TILES))
        assertEquals(ColourMapModel.slots(Family.ROOFS).toSet(), all.colours.keys)
        assertTrue(ColourMapModel.slots(Family.ROOFS).all { ColourMapModel.changed(all, it) })
        assertEquals(p.colours, ColourMapModel.withGroup(p, Slot.RED_TILES, Slot.RED_TILES.today).colours)
    }

    @Test
    fun textOnALightColourIsDark() {
        assertTrue(ColourMapModel.darkText(0xFFFFFF))
        assertTrue(ColourMapModel.darkText(Slot.PAPER.today))
        assertFalse(ColourMapModel.darkText(Slot.OLD_BRICK_1.today))
    }
}
