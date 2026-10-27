package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The map's markers for the core (ENGINE.md step 6; `MapCore.markers`): each look the app drew once as the board's
 * painter draws a marker as itself (premultiplied RGBA8 at the screen's density, the marker's place on a whole pixel of
 * it), each marker with what the core places it by, and the clusters' counts drawn in their inks, written one after
 * another into a direct buffer as the core reads it (`CP/markers/Markers.hpp`'s read_marker_block), little-endian: the
 * magic, the density (f32) and the counts of looks, markers and count images (u32 each); each look: its width and
 * height (u32), anchor (i32 x, y: where the marker's place lies in it) and pixels (width × height, R in the low byte,
 * rows top to bottom); each marker: its latitude and longitude (f64), priority (i32), style, chosen and value flags (u8
 * each) and a zero byte, its count (u32), its chip's text's and its name's widths (f32, the name's negative for none),
 * its colour and its count's ink (u32 ARGB), and its looks (u32: as itself, and a named pin's with its name on the
 * left; [NO_LOOK] for a cluster); each count image: its ink and key (u32, [countKey]) and an image as a look's. Plain
 * Kotlin on `java.nio`, so the JVM tests compile it too.
 */
object MarkerBlock {
    /** The block's first word: 'MRK1' little-endian (`marker_block_magic`). */
    const val MAGIC = 0x314B524D

    /** A cluster's look: the core's own (its bubble and its count). */
    const val NO_LOOK = -1

    /** MarkerStyle's order, as the core numbers the styles. */
    const val PIN = 0
    const val DOT = 1
    const val BRAND = 2
    const val CLUSTER = 3
    const val CHIP = 4

    /** One image: [width] × [height] premultiplied pixels, R in the low byte, and where the marker's place lies in it. */
    class Image(val width: Int, val height: Int, val anchorX: Int, val anchorY: Int, val pixels: IntArray) {
        init {
            require(pixels.size == width * height) { "the pixels are width × height" }
        }
    }

    /** One marker, with its looks' indices in the block's list. */
    class Marker(
        val lat: Double,
        val lon: Double,
        val priority: Int,
        val style: Int,
        val selected: Boolean,
        val value: Boolean,
        val count: Int,
        val chipText: Float,
        val name: Float,
        val colour: Int,
        val ink: Int,
        val look: Int,
        val lookLeft: Int,
    )

    /** A cluster's count drawn in [ink] ([key]: [countKey] of the counts it is written for). */
    class Count(val ink: Int, val key: Int, val image: Image)

    /**
     * What a cluster's count's text depends on (MarkerPainter.countLabel): the count itself below 1,000, its hundreds
     * below 10,000, its thousands (at most 999) above.
     */
    fun countKey(n: Int): Int = when {
        n < 1000 -> n
        n < 10_000 -> n / 100 * 100
        else -> minOf(n / 1000, 999) * 1000
    }

    private fun bytes(image: Image) = 16 + 4 * image.pixels.size

    /** The bytes of a block. */
    fun size(looks: List<Image>, markers: List<Marker>, counts: List<Count>): Int =
        20 + looks.sumOf { bytes(it) } + 52 * markers.size + counts.sumOf { 8 + bytes(it.image) }

    /** [markers] with their [looks] and the clusters' [counts], drawn at [density], as a block in a new direct buffer. */
    fun write(density: Float, looks: List<Image>, markers: List<Marker>, counts: List<Count>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(size(looks, markers, counts)).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putFloat(density).putInt(looks.size).putInt(markers.size).putInt(counts.size)
        for (image in looks) put(b, image)
        for (m in markers) {
            b.putDouble(m.lat).putDouble(m.lon).putInt(m.priority)
            b.put(m.style.toByte()).put(if (m.selected) 1 else 0).put(if (m.value) 1 else 0).put(0)
            b.putInt(m.count).putFloat(m.chipText).putFloat(m.name).putInt(m.colour).putInt(m.ink)
            b.putInt(m.look).putInt(m.lookLeft)
        }
        for (c in counts) {
            b.putInt(c.ink).putInt(c.key)
            put(b, c.image)
        }
        b.flip()
        return b
    }

    private fun put(b: ByteBuffer, image: Image) {
        b.putInt(image.width).putInt(image.height).putInt(image.anchorX).putInt(image.anchorY)
        for (p in image.pixels) b.putInt(p)
    }
}
