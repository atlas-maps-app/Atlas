package app.atlas.mapcore.state

import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteBlockTest {
    @Test
    fun theBlockHoldsTheSwitchesAndEachChangedSlotAtItsCode() {
        val b = PaletteBlock.allocate()
        PaletteBlock.write(b, 8, mapOf(80 to 0x9E5038, 1 to 0x86A060))
        assertEquals(PaletteBlock.VERSION, b.getInt(PaletteBlock.VERSION_AT))
        assertEquals(8, b.getInt(PaletteBlock.FLAGS_AT))
        assertEquals(0L, b.getLong(8))
        assertEquals(0x019E5038, b.getInt(PaletteBlock.COLOURS_AT + 4 * 80))
        assertEquals(0x0186A060, b.getInt(PaletteBlock.COLOURS_AT + 4 * 1))
        assertEquals(0, b.getInt(PaletteBlock.COLOURS_AT + 4 * 81))
        // today's look: nothing but the version
        PaletteBlock.write(b, 0, emptyMap())
        assertEquals(PaletteBlock.SIZE - 4, (4 until PaletteBlock.SIZE).count { b.get(it).toInt() == 0 })
    }
}
