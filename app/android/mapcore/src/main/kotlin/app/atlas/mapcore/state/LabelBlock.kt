package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The map's labels for the core (ENGINE.md step 2, design point 8; `MapCore.labels`): each label's sprite, which the app
 * draws once exactly as the board's painter draws the label (its text and halos, premultiplied RGBA8 at the screen's
 * density), with what the core places it by, written one after another into a direct buffer as the core reads it
 * (`CP/labels/Sprites.hpp`'s read_label_block), little-endian: the magic and the count (u32 each); per sprite its id
 * and priority (i32), its measures (f32: half its text's width plus the pad, the pad, its text's height, and its
 * baseline's offset, (ascent + descent) / 2), its width and height (u32), its anchor (i32 x, y: where the painter's
 * drawText point lies in it), its points' count (u32), its points (f64 latitude, longitude each, the likeliest first),
 * its pixels (width × height, R in the low byte, rows top to bottom). Plain Kotlin on `java.nio`, so the JVM tests
 * compile it too.
 */
object LabelBlock {
    /** The block's first word: 'LBL1' little-endian (`label_block_magic`). */
    const val MAGIC = 0x314C424C

    /** One label's sprite and what the core places it by. */
    class Sprite(
        val id: Int,
        val priority: Int,
        val half: Float,
        val pad: Float,
        val textHeight: Float,
        val baseline: Float,
        val width: Int,
        val height: Int,
        val anchorX: Int,
        val anchorY: Int,
        /** Latitude and longitude of each point, in turn. */
        val points: DoubleArray,
        val pixels: IntArray,
    ) {
        init {
            require(points.size % 2 == 0) { "a point is a latitude and a longitude" }
            require(pixels.size == width * height) { "the pixels are width × height" }
        }
    }

    /** The bytes of a block of [sprites]. */
    fun size(sprites: List<Sprite>): Int = 8 + sprites.sumOf { 44 + 8 * it.points.size + 4 * it.pixels.size }

    /** [sprites] as a block, in a new direct buffer (the core reads it before `MapCore.labels` returns). */
    fun write(sprites: List<Sprite>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(size(sprites)).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(sprites.size)
        for (s in sprites) {
            b.putInt(s.id).putInt(s.priority)
            b.putFloat(s.half).putFloat(s.pad).putFloat(s.textHeight).putFloat(s.baseline)
            b.putInt(s.width).putInt(s.height).putInt(s.anchorX).putInt(s.anchorY)
            b.putInt(s.points.size / 2)
            for (v in s.points) b.putDouble(v)
            for (p in s.pixels) b.putInt(p)
        }
        b.flip()
        return b
    }
}
