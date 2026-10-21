package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The map's labels for the core (ENGINE.md step 2, design point 8; `MapCore.labels`): each label's sprite, which the app
 * draws exactly as the board's painter draws the label (its text and halos, premultiplied RGBA8 at the screen's
 * density), once at its own size and once at the band's top ([BAND_TOP] times it, which the core draws a grown label
 * from, so it stays sharp), with what the core places it by, written one after another into a direct buffer as the core
 * reads it (`CP/labels/Sprites.hpp`'s read_label_block), little-endian: the magic and the count (u32 each) and the
 * large images' scale (f32, 0 for none); per sprite its id and priority (i32), its measures (f32: half its text's width
 * plus the pad, the pad, its text's height, and its baseline's offset, (ascent + descent) / 2), its own image's width
 * and height (u32) and anchor (i32 x, y: where the painter's drawText point lies in it), its points' count (u32), its
 * points (f64 latitude, longitude each, the likeliest first), its own image's pixels (width × height, R in the low byte,
 * rows top to bottom), then its large image: width and height (u32), anchor (i32 x, y) and pixels (0 × 0 and none for
 * none). Plain Kotlin on `java.nio`, so the JVM tests compile it too.
 */
object LabelBlock {
    /** The block's first word: 'LBL2' little-endian (`label_block_magic`). */
    const val MAGIC = 0x324C424C

    /**
     * The band's top: the largest a label grows to with the map before the core places it anew (`CP/labels/Placement.hpp`'s
     * band_high), the size the app draws each label's large image at.
     */
    const val BAND_TOP = 1.5f

    /** One image of a label: [width] × [height] premultiplied pixels, R in the low byte, and where its anchor lies in it. */
    class Image(val width: Int, val height: Int, val anchorX: Int, val anchorY: Int, val pixels: IntArray) {
        init {
            require(pixels.size == width * height) { "the pixels are width × height" }
        }
    }

    /** One label's sprite: its [own] image and its [large] one (null for none), and what the core places it by. */
    class Sprite(
        val id: Int,
        val priority: Int,
        val half: Float,
        val pad: Float,
        val textHeight: Float,
        val baseline: Float,
        val own: Image,
        val large: Image?,
        /** Latitude and longitude of each point, in turn. */
        val points: DoubleArray,
    ) {
        init {
            require(points.size % 2 == 0) { "a point is a latitude and a longitude" }
        }
    }

    /** The bytes of a block of [sprites]. */
    fun size(sprites: List<Sprite>): Int =
        12 + sprites.sumOf { 44 + 8 * it.points.size + 4 * it.own.pixels.size + 16 + 4 * (it.large?.pixels?.size ?: 0) }

    /**
     * [sprites] as a block, their large images drawn at [largeScale] times their own size (0 when none has one), in a
     * new direct buffer (the core reads it before `MapCore.labels` returns).
     */
    fun write(sprites: List<Sprite>, largeScale: Float = BAND_TOP): ByteBuffer {
        val b = ByteBuffer.allocateDirect(size(sprites)).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(sprites.size).putFloat(if (sprites.any { it.large != null }) largeScale else 0f)
        for (s in sprites) {
            b.putInt(s.id).putInt(s.priority)
            b.putFloat(s.half).putFloat(s.pad).putFloat(s.textHeight).putFloat(s.baseline)
            b.putInt(s.own.width).putInt(s.own.height).putInt(s.own.anchorX).putInt(s.own.anchorY)
            b.putInt(s.points.size / 2)
            for (v in s.points) b.putDouble(v)
            for (p in s.own.pixels) b.putInt(p)
            val large = s.large
            if (large == null) {
                b.putInt(0).putInt(0).putInt(0).putInt(0)
            } else {
                b.putInt(large.width).putInt(large.height).putInt(large.anchorX).putInt(large.anchorY)
                for (p in large.pixels) b.putInt(p)
            }
        }
        b.flip()
        return b
    }
}
