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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
        assertNull(MapLookModel.withTone(Palette(), 1f).tone)
        assertEquals(0f, MapLookModel.withTone(Palette(), 0f).tone)
        assertEquals(1.4f, MapLookModel.withTone(Palette(), 1.4f).tone)
    }

    @Test
    fun aLookIsItsPresetItsChangedColoursItsToneAndItsSwitchesButNeverTheGuesses() {
        val quiet = Palette(Preset.QUIET)
        assertEquals(Look(Preset.QUIET, emptyMap(), 20, 0), MapLookModel.look(quiet))
        // a colour kept that is the preset's own changes nothing
        assertEquals(MapLookModel.look(quiet), MapLookModel.look(quiet.copy(colours = mapOf(Slot.WATER to Preset.QUIET.colour(Slot.WATER)))))
        assertEquals(MapLookModel.look(quiet), MapLookModel.look(MapLookModel.withSwitch(quiet, PaletteFlags.NO_GUESSES, true)))
        assertNotEquals(MapLookModel.look(quiet), MapLookModel.look(quiet, 1.4f))
        assertNotEquals(MapLookModel.look(quiet), MapLookModel.look(MapLookModel.withSwitch(quiet, PaletteFlags.GRASS, true)))
        assertEquals(Preset.QUIET, MapLookModel.ringed(MapLookModel.look(quiet)))
        assertNull(MapLookModel.ringed(MapLookModel.look(quiet, 0f)))
        assertNull(MapLookModel.ringed(MapLookModel.look(red)))
    }

    @Test
    fun theLineNamesWhatTheMapDrawsAndSaysWhenItIsChanged() {
        val saved = listOf(SavedPalette("Mine", PaletteCode.write(red.copy(tone = 1.4f))))
        val mine = MapLookModel.look(red, 1.4f)
        assertEquals(MapLookModel.Line.Saved("Mine"), MapLookModel.line(mine, saved, saved[0].code))
        assertEquals(MapLookModel.Line.Changed("Mine", Preset.TODAY, 1), MapLookModel.line(MapLookModel.look(red, 1f), saved, saved[0].code))
        assertEquals(MapLookModel.Line.Changed(null, Preset.TODAY, 1), MapLookModel.line(MapLookModel.look(red, 1f), saved, null))
        assertEquals(MapLookModel.Line.Exact(Preset.ALPHA_22, isDefault = true), MapLookModel.line(MapLookModel.DEFAULT_LOOK, saved, null))
        assertEquals(MapLookModel.Line.Exact(Preset.INK, isDefault = false), MapLookModel.line(MapLookModel.look(Palette(Preset.INK)), saved, null))
        assertEquals(MapLookModel.Line.Changed(null, Preset.INK, 0), MapLookModel.line(MapLookModel.look(Palette(Preset.INK), 1f), saved, null))
    }

    @Test
    fun aCodeTypedInIsAPaletteOrSaysWhatIsWrong() {
        val code = PaletteCode.write(red)
        assertEquals(MapLookModel.Found.Ok(red, null), MapLookModel.find(code))
        assertEquals(MapLookModel.Found.Ok(red, "Canal red"), MapLookModel.find(MapLookModel.message("Canal red", code)))
        assertEquals(MapLookModel.Found.Ok(red, null), MapLookModel.find(MapLookModel.link(code)))
        assertEquals(MapLookModel.Found.Ok(red, null), MapLookModel.find(" " + code.lowercase().replace('-', ' ') + " "))
        assertEquals(MapLookModel.Found.Empty, MapLookModel.find("  "))
        assertEquals(MapLookModel.Found.Empty, MapLookModel.find(null))
        assertEquals(MapLookModel.Found.NotCode, MapLookModel.find("hello"))
        assertEquals(MapLookModel.Found.Mistyped, MapLookModel.find("AT1"))
        assertEquals(MapLookModel.Found.Mistyped, MapLookModel.find("AT1-TODAY"))
        assertEquals(MapLookModel.Found.Mistyped, MapLookModel.find(code.dropLast(1)))
        assertEquals(MapLookModel.Found.Mistyped, MapLookModel.find(code.dropLast(1) + (if (code.last() == '0') '1' else '0')))
        assertEquals(MapLookModel.Found.Newer, MapLookModel.find(code.replace("AT1", "AT2")))
        assertEquals(MapLookModel.Found.Newer, MapLookModel.find(code.replace("TODAY", "DUSK")))
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
        // the saved palette that draws the look, its Tone with it
        assertEquals(0, MapLookModel.savedIndex(saved, MapLookModel.look(red)))
        assertEquals(-1, MapLookModel.savedIndex(saved, MapLookModel.look(red, 0.4f)))
        assertEquals(-1, MapLookModel.savedIndex(saved, MapLookModel.look(Palette())))
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

/** The Map look's writes: what the map takes, the one Tone, and the saved palettes kept with it. */
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
    fun aLookTakenKeepsTheUsersGuessesAndTheToneTheyHadForTheSwitch() {
        val m = mutablePreferencesOf()
        MapLookWrites.guesses(m, false)
        MapLookWrites.toneTo(m, 1.4f)
        MapLookWrites.take(m, Palette(Preset.INK))
        assertTrue(MapLookModel.on(MapLookModel.current(m[SettingsKeys.MAP_PALETTE]), PaletteFlags.NO_GUESSES))
        assertEquals(Tone(false, 1.4f), MapLookModel.tone(m))
        assertEquals(MapLookModel.look(Palette(Preset.INK)), MapLookModel.drawn(m))
        MapLookWrites.toneSwitch(m, true)
        assertEquals(Tone(true, 1.4f), MapLookModel.tone(m))
        // a tap on what the map draws changes nothing
        val before = m.toPreferences()
        assertFalse(MapLookWrites.pick(m, Palette(Preset.INK, tone = 1.4f)))
        assertEquals(before, m.toPreferences())
        assertTrue(MapLookWrites.pick(m, Palette(Preset.INK)))
        assertEquals(Tone(false, 1.4f), MapLookModel.tone(m))
    }

    @Test
    fun theToneIsOneStateOffIsNothingAndTheSwitchBringsTheLastBack() {
        val m = mutablePreferencesOf()
        MapLookWrites.toneTo(m, 1.43f)
        assertEquals(Tone(true, 1.45f), MapLookModel.tone(m))
        MapLookWrites.toneTo(m, 0f)
        assertEquals(Tone(false, 1.45f), MapLookModel.tone(m))
        assertEquals(0f, MapLookModel.tone(m).drawn)
        MapLookWrites.toneSwitch(m, true)
        assertEquals(1.45f, MapLookModel.tone(m).drawn)
        // a strength of 0 kept while on, as the slider once wrote it, is off; on again brings today's
        m[SettingsKeys.MAP_TONE] = 0f
        m[SettingsKeys.MAP_TONE_ON] = true
        assertEquals(Tone(false, 0f), MapLookModel.tone(m))
        MapLookWrites.toneSwitch(m, true)
        assertEquals(Tone(true, 1f), MapLookModel.tone(m))
    }

    @Test
    fun addingSavesOnceUnderANameAndTheMapTakesIt() {
        val m = mutablePreferencesOf()
        assertEquals(MapLookWrites.Added.New("Sunlit 1"), MapLookWrites.add(m, red, null, "Sunlit"))
        assertEquals(MapLookWrites.Added.Yours("Sunlit 1"), MapLookWrites.add(m, red, "Other", "Sunlit"))
        assertEquals(MapLookWrites.Added.New("Ink"), MapLookWrites.add(m, Palette(Preset.INK), " Ink ", "Ink"))
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        assertEquals(listOf("Sunlit 1", "Ink"), saved.map { it.name })
        assertEquals(Palette(Preset.INK), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        assertEquals(saved[1].code, m[SettingsKeys.MAP_LOOK_FROM])
        // a shared palette's guesses are none of it
        val bare = MapLookModel.withSwitch(Palette(Preset.SEPIA), PaletteFlags.NO_GUESSES, true)
        MapLookWrites.add(m, bare, "Print", "Sepia")
        assertEquals(Palette(Preset.SEPIA), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).last().palette)
        assertFalse(MapLookModel.on(MapLookModel.current(m[SettingsKeys.MAP_PALETTE]), PaletteFlags.NO_GUESSES))
    }

    @Test
    fun aSwitchOrTheToneChangesWhatTheMapDrawsAndNoSavedPalette() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Sunlit")
        val code = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code
        MapLookWrites.switch(m, PaletteFlags.GRASS, true)
        MapLookWrites.toneTo(m, 1.5f)
        assertEquals(code, MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code)
        assertEquals(MapLookModel.withSwitch(red, PaletteFlags.GRASS, true), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        assertEquals(MapLookModel.Line.Changed("Mine", Preset.TODAY, 1), MapLookModel.line(MapLookModel.drawn(m), saved, m[SettingsKeys.MAP_LOOK_FROM]))
        // the map's own palette never carries the Tone
        assertNull(MapLookModel.current(m[SettingsKeys.MAP_PALETTE]).tone)
    }

    @Test
    fun savingKeepsTheNameGivenReplacingThePaletteItCameFromOnlyUnderItsName() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Sunlit")
        val from = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code
        MapLookWrites.toneTo(m, 1.5f)
        val bluer = red.copy(colours = red.colours + (Slot.WATER to 0x2244AA))
        assertEquals("Mine", MapLookWrites.save(m, bluer, " Mine ", from))
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        assertEquals(listOf("Mine"), saved.map { it.name })
        assertEquals(bluer.copy(tone = 1.5f), saved.single().palette)
        assertEquals(bluer, MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
        assertEquals(MapLookModel.Line.Saved("Mine"), MapLookModel.line(MapLookModel.drawn(m), saved, m[SettingsKeys.MAP_LOOK_FROM]))
        // under another name: a new one, the first kept
        assertEquals("Bluer", MapLookWrites.save(m, bluer, "Bluer", saved.single().code.takeIf { false }))
        assertEquals(listOf("Mine", "Bluer"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        // a palette's guesses are none of what it saves
        MapLookWrites.guesses(m, false)
        val noGuess = MapLookModel.current(m[SettingsKeys.MAP_PALETTE])
        MapLookWrites.save(m, noGuess, "Plain", null)
        assertFalse(MapLookModel.on(MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).last().palette!!, PaletteFlags.NO_GUESSES))
        assertTrue(MapLookModel.on(MapLookModel.current(m[SettingsKeys.MAP_PALETTE]), PaletteFlags.NO_GUESSES))
    }

    @Test
    fun removingAndRenaming() {
        val m = mutablePreferencesOf()
        MapLookWrites.add(m, red, "Mine", "Sunlit")
        MapLookWrites.add(m, Palette(Preset.INK), "Ink", "Ink")
        MapLookWrites.rename(m, 0, "  Canal\tred ")
        MapLookWrites.rename(m, 1, "   ")
        MapLookWrites.rename(m, 5, "Nowhere")
        assertEquals(listOf("Canal red", "Ink"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        MapLookWrites.remove(m, 0)
        assertEquals(listOf("Ink"), MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).map { it.name })
        assertEquals(MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code, m[SettingsKeys.MAP_LOOK_FROM])
        MapLookWrites.remove(m, 0)
        assertNull(m[SettingsKeys.MAP_PALETTES])
        assertNull(m[SettingsKeys.MAP_LOOK_FROM])
        // the map draws what it drew
        assertEquals(Palette(Preset.INK), MapLookModel.current(m[SettingsKeys.MAP_PALETTE]))
    }
}

/**
 * The Map look's and Colour the map's words in all four languages: none of OpenStreetMap's tags, the palette review's
 * working names or the colour scientists' units (the user, 17 November: the screens "dont make sense").
 */
class MapLookWordsTest {
    @Test
    fun theWordsArePlain() {
        val res = File("src/main/res")
        val words = Regex("""<(?:string|item)[^>]*>([^<]*)<""")
        val key = Regex("""name="(mine_(?:look|colour|slot)_[a-z0-9_]+)"""")
        for (lang in listOf("values", "values-nl", "values-de", "values-fr")) {
            val text = File(res, "$lang/strings_mine.xml").readText()
            val plurals = Regex("""<plurals name="mine_look_[a-z_]+">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(text).flatMap { it.groupValues[1].lines() }
            val ours = text.lines().filter { key.containsMatchIn(it) } + plurals.filter { it.contains("<item") }
            assertTrue(lang, ours.size > 150)
            for (line in ours) {
                val said = words.find(line)?.groupValues?.get(1) ?: continue
                for (bad in listOf("=", ":colour", "review", "Review", "Alpha 22", "OKLCH", "New look")) {
                    assertFalse("$lang: $line", said.contains(bad))
                }
            }
        }
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
