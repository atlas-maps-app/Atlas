package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * The tilt dial as the canvas draws it, at the Pixel's density over a busy map-like ground, by day and by night, at the
 * limit and fading: its card reads over anything, its needle stands at the pitch. The cards are kept as a PNG.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TiltDialPainterTest {
    private val density = 2.625f
    private val fonts = MapEngine.fonts(RuntimeEnvironment.getApplication().assets)
    private val painter = TiltDialPainter(density) { w -> fonts.typeface("Geist", w, false) }

    /** Streets, blocks and water in the day's or the night's colours: the worst ground for a card to read on. */
    private fun ground(c: Canvas, x: Float, y: Float, w: Float, h: Float, night: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (night) MapController.NIGHT_EARTH else MapController.DAY_EARTH
        c.drawRect(x, y, x + w, y + h, p)
        p.color = if (night) 0xFF1C2433.toInt() else 0xFF9DB9D3.toInt()
        c.drawRect(x, y + h * 0.55f, x + w, y + h * 0.7f, p)
        p.strokeWidth = 6f * density
        p.color = if (night) 0xFF3A3F4B.toInt() else 0xFFFFFFFF.toInt()
        var k = -h
        while (k < w) {
            c.drawLine(x + k, y + h, x + k + h, y, p)
            k += 26f * density
        }
        p.color = if (night) 0xFFE0B05A.toInt() else 0xFF14161B.toInt()
        p.textSize = 14f * density
        c.drawText("Prinsengracht", x + 8f * density, y + 40f * density, p)
    }

    @Test
    fun theDialReadsOverAnyGroundDayAndNight() {
        val cell = 168f * density
        val b = Bitmap.createBitmap((cell * 4).toInt(), (cell * 1.25f).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // by day at 46°; by night at 12° with zoom 6's limit; at the limit; fading
        val cases = listOf(
            Triple(46.0, 70.0, false), Triple(12.0, CameraOps.maxPitch(6.0), true),
            Triple(70.0, 70.0, false), Triple(30.0, 70.0, true),
        )
        for ((i, case) in cases.withIndex()) {
            val (pitch, max, night) = case
            val x = i * cell
            c.save()
            c.clipRect(x, 0f, x + cell, b.height.toFloat())
            ground(c, x, 0f, cell, b.height.toFloat(), night)
            c.restore()
            val left = x + (cell - TiltDial.WIDTH_DP * density) / 2
            val top = (b.height - TiltDial.HEIGHT_DP * density) / 2
            painter.reset()
            painter.draw(c, TiltDialShown(left, top, if (i == 3) 0.5f else 1f), pitch, max, night)
            assertEquals(Math.round(pitch).toInt(), painter.figure)
            if (i == 3) continue
            // the card under the figures is the card's own colour, whatever the ground
            val card = b.getPixel((left + TiltDial.WIDTH_DP * density * 0.62f).toInt(), (top + 41f * density).toInt())
            assertEquals("case $i", if (night) 0xFF1B1D22.toInt() else 0xFFFFFFFF.toInt(), card)
            // the needle's blue halfway along it, at the pitch
            val a = Math.toRadians(pitch)
            val r = (TiltDial.WIDTH_DP - 27f) * density
            val nx = left + 15f * density + (sin(a) * r * 0.6).toFloat()
            val ny = top + 46f * density + (cos(a) * r * 0.6).toFloat()
            val blue = b.getPixel(nx.toInt(), ny.toInt())
            assertTrue("case $i: ${Integer.toHexString(blue)}", (blue and 0xFF) > 0xC0 && ((blue shr 16) and 0xFF) < 0x70)
        }
        val out = File(Fixtures.shots, "tilt_dial_cards.png")
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 10_000)
    }
}
