package app.atlas.mapview.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PaletteThemeTest {
    private val day = linkedMapOf<String, Any?>(
        "name" to "live", "ground" to "#D6CFC2", "road" to "#C9C1B4", "water" to "#4F7590", "shadow" to "#27305A", "amb_k" to 0.62,
    )
    private val night = linkedMapOf<String, Any?>("night" to true, "ground" to "#12151F", "water" to "#070C14", "road" to "#1B1F2C")

    @Test
    fun todaysPaletteLeavesTheThemeAsItIs() {
        assertSame(day, PaletteTheme.recolour(day, Palette()))
        assertSame(night, PaletteTheme.recolour(night, Palette()))
        // a palette whose changes the theme does not carry leaves it too
        assertSame(day, PaletteTheme.recolour(day, Palette(colours = mapOf(Slot.RED_TILES to 0x9E5038))))
    }

    @Test
    fun theLiveDayTakesTheColourExactlyAndOtherMomentsMoveWithIt() {
        val grass = Palette(colours = mapOf(Slot.PAPER to 0x86A060, Slot.STREET to 0x55585C))
        val d = PaletteTheme.recolour(day, grass)
        assertEquals("#86A060", d["ground"])
        assertEquals("#55585C", d["road"])
        assertEquals("#4F7590", d["water"])
        assertEquals(0.62, d["amb_k"])
        // at night the ground is the night ground's slot, unchanged here; the road moves by the street's ratio
        val n = PaletteTheme.recolour(night, grass)
        assertEquals("#12151F", n["ground"])
        assertEquals("#%06X".format(PaletteTheme.move(0x1B1F2C, 0xC9C1B4, 0x55585C)), n["road"])
        assertEquals(0x86A060, PaletteTheme.move(0xD6CFC2, 0xD6CFC2, 0x86A060))
        assertEquals(0x102030, PaletteTheme.move(0x102030, 0x405060, 0x405060))
    }
}
