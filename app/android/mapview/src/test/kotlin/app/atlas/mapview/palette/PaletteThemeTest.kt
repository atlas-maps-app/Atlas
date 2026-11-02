package app.atlas.mapview.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun grassOnBareGroundMakesTheDaysGroundTheLawns() {
        val grass = Palette(flags = PaletteFlags.GRASS)
        assertEquals("#A9C38A", PaletteTheme.recolour(day, grass)["ground"])
        // the lawns' own colour where the palette changes it, and the night's ground stays the night ground's
        assertEquals("#607850", PaletteTheme.recolour(day, grass.copy(colours = mapOf(Slot.LAWN to 0x607850)))["ground"])
        assertEquals("#12151F", PaletteTheme.recolour(night, grass)["ground"])
        // real colour brings grass: its lawns' green
        assertEquals("#6E9448", PaletteTheme.recolour(day, Palette(base = Preset.REAL))["ground"])
        // another moment's ground (a rain's darker paper) is the same tone of the lawns'
        val rain = linkedMapOf<String, Any?>("ground" to "#B8B2A6")
        assertEquals("#%06X".format(PaletteTheme.move(0xB8B2A6, 0xD6CFC2, 0xA9C38A)), PaletteTheme.recolour(rain, grass)["ground"])
    }

    @Test
    fun aGreyGreysWhatGoesWithItAndAToneStaysATone() {
        fun spread(c: Int) = listOf(16, 8, 0).map { (c shr it) and 0xFF }.let { it.max() - it.min() }
        // a grey sky's lighter horizon is a lighter grey (a ratio per channel made it peach)
        val horizon = PaletteTheme.move(0xDCE8F0, 0x7FA6DA, 0xA0A0A0)
        assertTrue("%06X".format(horizon), spread(horizon) <= 2 && (horizon and 0xFF) > 0xA0)
        // dusk's darker water stays darker than the new water, in the new water's hue
        val dusk = PaletteTheme.move(0x2E4A60, 0x4F7590, 0x2D6A4F)
        assertTrue("%06X".format(dusk), (dusk shr 8 and 0xFF) < 0x6A && (dusk shr 8 and 0xFF) > (dusk shr 16 and 0xFF))
        // black stays black
        assertEquals(0, PaletteTheme.move(0, 0x4F7590, 0x2D6A4F))
    }
}
