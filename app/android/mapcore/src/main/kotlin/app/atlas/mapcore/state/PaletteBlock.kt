package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The user's palette as the core reads it (style/Palette.hpp's read_palette_block): the version, the Map look's
 * switches and each slot's colour by its code (mapview's palette.Slot), 0 where it is today's, else 0x01RRGGBB.
 * Today's look is the block of no colours and no switches.
 */
object PaletteBlock {
    const val SIZE = 1040
    const val VERSION = 1
    const val VERSION_AT = 0
    const val FLAGS_AT = 4
    const val COLOURS_AT = 16
    const val SLOTS = 256

    /** A direct little-endian buffer for one block. */
    fun allocate(): ByteBuffer = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /** Writes the block of [flags] and [colours] (slot code to 0xRRGGBB, the colours that differ from today's). */
    fun write(out: ByteBuffer, flags: Int, colours: Map<Int, Int>) {
        require(out.order() == ByteOrder.LITTLE_ENDIAN && out.limit() >= SIZE) { "a little-endian buffer of $SIZE bytes" }
        require(flags and 0xF.inv() == 0) { "an unknown switch in $flags" }
        for (i in 0 until SIZE step 8) out.putLong(i, 0L)
        out.putInt(VERSION_AT, VERSION)
        out.putInt(FLAGS_AT, flags)
        for ((code, rgb) in colours) {
            require(code in 0 until SLOTS && rgb in 0..0xFFFFFF) { "slot $code's colour $rgb" }
            out.putInt(COLOURS_AT + 4 * code, 0x01000000 or rgb)
        }
    }
}
