package app.atlas.mapview.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PaletteCodeTest {
    private val canalEvening = Palette(
        Preset.TODAY,
        mapOf(Slot.RED_TILES to 0x9E5038, Slot.LAWN to 0x6E9448, Slot.WATER to 0x2D4F6A),
        tone = 1.35f,
        flags = PaletteFlags.GRASS or PaletteFlags.MAPPED_FOLLOW,
    )

    @Test
    fun aPaletteComesBackFromItsCode() {
        for (p in listOf(canalEvening, Palette(), Palette(Preset.SEPIA), Palette(Preset.INK, mapOf(Slot.HAZE to 0xFFFFFF), 0f, 0))) {
            assertEquals(PaletteCode.Read.Ok(p), PaletteCode.read(PaletteCode.write(p)))
        }
    }

    @Test
    fun theCodeNamesItsPresetAndComesInFives() {
        val code = PaletteCode.write(canalEvening)
        assertTrue(code, code.startsWith("AT1-TODAY-"))
        val groups = code.removePrefix("AT1-TODAY-").split('-')
        assertTrue(code, groups.dropLast(1).all { it.length == 5 } && groups.last().length in 1..5)
        // three colours, the Tone and the switches: short enough to read out
        assertTrue(code, code.length < 50)
        assertEquals("AT1-A22-", PaletteCode.write(Palette(Preset.ALPHA_22)).take(8))
    }

    @Test
    fun aMistypedCodeIsRefusedRatherThanReadAsOtherColours() {
        val code = PaletteCode.write(canalEvening)
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        for (i in "AT1-TODAY-".length until code.length) {
            val ch = code[i]
            if (ch == '-') continue
            val other = alphabet[(alphabet.indexOf(ch) + 1) % alphabet.length]
            val typo = code.substring(0, i) + other + code.substring(i + 1)
            assertTrue(typo, PaletteCode.read(typo) is PaletteCode.Read.Bad)
        }
        assertEquals(PaletteCode.Read.Bad(PaletteCode.Fault.FORM), PaletteCode.read(code.replace("AT1", "AT2")))
        assertEquals(PaletteCode.Read.Bad(PaletteCode.Fault.PRESET), PaletteCode.read(code.replace("TODAY", "TOMORROW")))
        assertEquals(PaletteCode.Read.Bad(PaletteCode.Fault.FORM), PaletteCode.read("AT1-TODAY"))
        assertEquals(PaletteCode.Read.Bad(PaletteCode.Fault.LETTER), PaletteCode.read("AT1-TODAY-U0000"))
    }

    @Test
    fun readingTakesLowerCaseSpacesAndTheLookAlikes() {
        val code = PaletteCode.write(canalEvening)
        val want = PaletteCode.Read.Ok(canalEvening)
        assertEquals(want, PaletteCode.read(code.lowercase()))
        assertEquals(want, PaletteCode.read("  " + code.replace('-', ' ') + " "))
        val groups = code.removePrefix("AT1-TODAY-")
        assertEquals(want, PaletteCode.read("AT1-TODAY-" + groups.replace('0', 'O').replace('1', 'I')))
        assertEquals(want, PaletteCode.read("AT1-TODAY-" + groups.replace('1', 'L')))
    }

    @Test
    fun todaysPresetIsTodaysMapAndTheOthersKeepTheirAnchorsAndTheLight() {
        assertTrue(Palette().isToday)
        assertEquals(0, Palette().changed)
        for (slot in Slot.entries) assertEquals(slot.name, slot.today, Preset.TODAY.colour(slot))
        val a22 = Palette(Preset.ALPHA_22)
        assertFalse(a22.isToday)
        assertEquals(0x9E5038, a22.colour(Slot.RED_TILES))
        assertEquals(0f, a22.toneStrength)
        assertEquals(PaletteFlags.FLAT_GREYS, a22.switches)
        assertEquals(PaletteFlags.GRASS, Preset.REAL.flags)
        for (p in Preset.entries) {
            for ((slot, c) in p.anchors) assertEquals("$p $slot", c, p.colour(slot))
            for (slot in Slot.entries.filter { it.family == Family.NIGHT }) assertEquals("$p $slot", slot.today, p.colour(slot))
        }
        // Ink's other colours are nearly grey; Quiet's lighter than today's
        for (slot in Slot.entries.filter { it.family != Family.NIGHT && it !in Preset.INK.anchors }) {
            val (_, a, b) = Oklab.of(Preset.INK.colour(slot))
            assertTrue("INK $slot", hypot(a, b) < 0.02f)
            assertTrue("QUIET $slot", Oklab.of(Preset.QUIET.colour(slot)).first > Oklab.of(slot.today).first)
        }
        assertEquals(3, canalEvening.changed)
    }

    @Test
    fun oklabComesBackToTheSameColour() {
        for (slot in Slot.entries) {
            val (l, a, b) = Oklab.of(slot.today)
            assertEquals(slot.name, slot.today, Oklab.rgb(l, a, b))
        }
        assertTrue(Oklab.distance(0x7A4434, 0x7A4434) == 0f)
        assertTrue(Oklab.distance(0x7A4434, 0x7B4434) < 0.02f)
    }

    @Test
    fun everySlotHasItsOwnCode() {
        assertEquals(Slot.entries.size, Slot.entries.map { it.code }.toSet().size)
        assertTrue(Slot.entries.all { it.code in 1..254 })
        assertEquals(Preset.entries.size, Preset.entries.map { it.code }.toSet().size)
    }
}
