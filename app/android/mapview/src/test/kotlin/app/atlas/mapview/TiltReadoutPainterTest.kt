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

/**
 * The tilt readout as the canvas draws it, at the Pixel's density over busy map-like ground by day and by night:
 * between the stops, at both stops (the steepest below zoom 8 too) and fading. Its ink reads on its halo whatever lies
 * under it; the strip is kept as a PNG to judge how quietly it sits on the map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TiltReadoutPainterTest {
    private val density = 2.625f
    private val fonts = MapEngine.fonts(RuntimeEnvironment.getApplication().assets)
    private val painter = TiltReadoutPainter(density) { w -> fonts.typeface("Geist", w, false) }

    /** Streets, blocks, water and a label in the day's or the night's colours: the worst ground for text to read on. */
    private fun ground(c: Canvas, x: Float, w: Float, h: Float, night: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (night) MapController.NIGHT_EARTH else MapController.DAY_EARTH
        c.drawRect(x, 0f, x + w, h, p)
        p.color = if (night) 0xFF1C2433.toInt() else 0xFF9DB9D3.toInt()
        c.drawRect(x, h * 0.42f, x + w, h * 0.62f, p)
        p.strokeWidth = 7f * density
        p.color = if (night) 0xFF3A3F4B.toInt() else 0xFFFFFFFF.toInt()
        var k = -h
        while (k < w) {
            c.drawLine(x + k, h, x + k + h, 0f, p)
            k += 30f * density
        }
        p.color = if (night) 0xFF5A4A3A.toInt() else 0xFFB46A4E.toInt()
        c.drawRect(x + 0.6f * w, h * 0.05f, x + 0.9f * w, h * 0.35f, p)
    }

    @Test
    fun theReadoutReadsOverAnyGroundDayAndNight() {
        val cell = 150f * density
        val h = 90f * density
        // between the stops by day; flat by day; the steepest at night; zoom 6's steepest at night; fading by day
        val cases = listOf(
            Triple(38.2, 70.0, false), Triple(0.0, 70.0, false), Triple(70.0, 70.0, true),
            Triple(CameraOps.maxPitch(6.0), CameraOps.maxPitch(6.0), true), Triple(24.0, 70.0, false),
        )
        val b = Bitmap.createBitmap((cell * cases.size).toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        for ((i, case) in cases.withIndex()) {
            val (pitch, max, night) = case
            val x = i * cell
            c.save()
            c.clipRect(x, 0f, x + cell, h)
            ground(c, x, cell, h, night)
            c.restore()
            painter.reset()
            painter.draw(c, TiltReadoutShown(x + cell / 2, h / 2, Paint.Align.CENTER, if (i == 4) 0.5f else 1f), pitch, max, night)
            assertEquals(Math.round(pitch).toInt(), painter.figure)
            if (i == 4) continue
            // the figure's ink is there, in the middle, on its halo
            var inked = 0
            val ink = if (night) 0xF2 else 0x14
            for (px in (x + cell / 2 - 40 * density).toInt()..(x + cell / 2 + 10 * density).toInt()) {
                for (py in (h / 2 - 6 * density).toInt()..(h / 2 + 6 * density).toInt()) {
                    val r = (b.getPixel(px, py) shr 16) and 0xFF
                    if (kotlin.math.abs(r - ink) < 24) inked++
                }
            }
            assertTrue("case $i: $inked ink pixels", inked > 60)
        }
        val out = File(Fixtures.shots, "tilt_readout.png")
        FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 10_000)
    }
}
