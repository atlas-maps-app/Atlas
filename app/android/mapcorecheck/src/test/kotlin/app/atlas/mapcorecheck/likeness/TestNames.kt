package app.atlas.mapcorecheck.likeness

import app.atlas.mapcore.state.LabelBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The world's names' sprites for the likeness host (ENGINE.md step 5, "Names"), where no Android draws them: the core
 * asks for a name by its id, look and text (`LookHost.namesWanted`), and here each letter is a 5 × 7 pattern of its
 * code point's bits on a plate, at the look's size and again at the band's top, the same pixels on every platform. So
 * the world's references hold which names the core picks, where it places them, their sizes, fades and order pixel
 * for pixel; the letters themselves are Android's on a phone (`:mapview`'s tests, and smoke's eight scripts).
 */
internal object TestNames {
    /** A name the core asks for: its id, its look (`MapCore.NameWant`'s) and its text. */
    class Want(val id: Int, val look: Int, val text: String)

    /** The readable floor the names are handed with, dp: 12 sp at Android's default text size. */
    const val FLOOR_DP = 12f

    /** `LookHost.namesWanted`'s bytes as its names. */
    fun wants(bytes: ByteArray): List<Want> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 4) return emptyList()
        return List(b.getInt()) {
            val id = b.getInt()
            val look = b.getInt()
            val text = ByteArray(b.getInt())
            b.get(text)
            Want(id, look, String(text, Charsets.UTF_8))
        }
    }

    /** The sprites of [wants] at [density], as `LabelBlock` writes them, without points. */
    fun block(wants: List<Want>, density: Double): ByteBuffer = LabelBlock.write(wants.map { sprite(it, density) })

    /** A look's text size, dp (the core's `scene::name_size_dp`, LabelPainter's sizes). */
    fun sizeDp(look: Int): Double = when (look) {
        1 -> 15.0
        2 -> 13.0
        3 -> 12.0
        4 -> 21.0
        else -> 17.0
    }

    private fun sprite(want: Want, density: Double): LabelBlock.Sprite {
        val size = sizeDp(want.look) * density
        // a dot of the pattern, px: a letter is 5 by 7 dots, seven tenths of the text's size tall
        val dot = max(1, (size / 10.0).roundToInt())
        val own = image(want, dot)
        val large = image(want, max(1, (size * LabelBlock.BAND_TOP / 10.0).roundToInt()))
        val pad = (4.0 * density).toFloat()
        val letters = want.text.codePointCount(0, want.text.length)
        val width = (letters * 6 - 1) * dot
        return LabelBlock.Sprite(
            want.id, 0, width / 2f + pad, pad, 9f * dot, -3.5f * dot, own, large, DoubleArray(0),
        )
    }

    /** [want]'s letters at [dot] px a dot on its plate, its anchor the text's centre on its baseline (the dots' foot). */
    private fun image(want: Want, dot: Int): LabelBlock.Image {
        val points = want.text.codePoints().toArray()
        val margin = 2 * dot
        val w = (points.size * 6 - 1) * dot + 2 * margin
        val h = 7 * dot + 2 * margin
        // premultiplied, R in the low byte: the Main board's ink on a white plate (a water's in the board's water blue);
        // on the globe white on its night
        val globe = want.look == 3
        val plate = if (globe) premultiplied(0x05, 0x07, 0x0D, 0xB0) else premultiplied(0xFF, 0xFF, 0xFF, 0xB0)
        val ink = when (want.look) {
            3 -> premultiplied(0xFF, 0xFF, 0xFF, 0xFF)
            4 -> premultiplied(0x2F, 0x5A, 0x84, 0xFF)
            else -> premultiplied(0x3A, 0x3F, 0x4B, 0xFF)
        }
        val pixels = IntArray(w * h) { plate }
        for ((n, cp) in points.withIndex()) {
            val bits = pattern(cp)
            for (row in 0 until 7) {
                for (col in 0 until 5) {
                    if ((bits shr (row * 5 + col)) and 1L == 0L) continue
                    val x0 = margin + (n * 6 + col) * dot
                    val y0 = margin + row * dot
                    for (y in y0 until y0 + dot) for (x in x0 until x0 + dot) pixels[y * w + x] = ink
                }
            }
        }
        return LabelBlock.Image(w, h, w / 2, margin + 7 * dot, pixels)
    }

    /** A code point's 35 dots: its bits mixed (SplitMix64's finaliser), with the left column whole so letters read apart. */
    private fun pattern(cp: Int): Long {
        var z = cp.toLong() * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        var bits = z and 0x7_FFFF_FFFFL
        for (row in 0 until 7) bits = bits or (1L shl (row * 5))
        return bits
    }

    private fun premultiplied(r: Int, g: Int, b: Int, a: Int): Int =
        (r * a / 255) or ((g * a / 255) shl 8) or ((b * a / 255) shl 16) or (a shl 24)
}
