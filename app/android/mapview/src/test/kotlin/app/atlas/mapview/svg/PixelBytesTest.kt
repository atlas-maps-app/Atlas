package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filters hand pixels to Skia and back as raw bytes. Android holds them R, G, B, A; Robolectric's Skia on CI's x86
 * Linux holds them B, G, R, A, and red and blue came out swapped there (the canopy probe, 15 October). Each colour must
 * arrive as itself on whatever machine runs this.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PixelBytesTest {
    @Test
    fun coloursCrossTheBytesAsThemselves() {
        val px = intArrayOf(0xFF102030.toInt(), 0x80402010.toInt(), 0x00000000, 0xFFFFFFFF.toInt())
        val b = SvgCanvas.bitmapOfPremultiplied(px, 2, 2)
        // Opaque pixels read back through Android's own accessor as given.
        assertEquals(0xFF102030.toInt(), b.getPixel(0, 0))
        assertEquals(0xFFFFFFFF.toInt(), b.getPixel(1, 1))
        // And the raw round trip keeps premultiplied pixels exactly.
        assertArrayEquals(px, SvgCanvas.premultipliedPixels(b))
        // Drawn half-transparent over grey, red stays red: 0x80 premultiplied red over 0x808080.
        val dst = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(dst)
        c.drawColor(0xFF808080.toInt())
        c.drawBitmap(SvgCanvas.bitmapOfPremultiplied(intArrayOf(0x80800000.toInt()), 1, 1), 0f, 0f, Paint())
        val out = dst.getPixel(0, 0)
        assertEquals(0xC0, (out shr 16) and 0xFF, 1.0)
        assertEquals(0x40, out and 0xFF, 1.0)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), tolerance)
}
