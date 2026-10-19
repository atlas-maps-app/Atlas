package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * The board's label sprites (plan-vector D13, K13) are LabelPainter's own pixels: each label's sprite, put down where
 * the vector engine puts it (its anchor on the text's centre and baseline, rounded as LabelPainter rounds them), equals
 * LabelPainter's drawing of it pixel for pixel, in the boards' Instrument Serif italic at three densities; and the
 * Main board's labels set by LabelPainter over a frame equal their sprites put down at LabelPainter's places.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LabelSpritesTest {
    private val face: Typeface by lazy {
        Typeface.createFromFile(File(EngineTestKit.repo, "app/android/app/src/main/res/font/instrument_serif_italic.ttf"))
    }

    private val names = listOf(
        LabelKind.WATER to "IJ", LabelKind.WATER to "Het IJ", LabelKind.WATER to "Amstel", LabelKind.WATER to "Oosterdok",
        LabelKind.AREA to "Jordaan", LabelKind.AREA to "De Pijp", LabelKind.AREA to "Grachtengordel-West",
        LabelKind.AREA to "Bedrijventerrein Sloterdijk", LabelKind.AREA to "Oud-Zuid", LabelKind.AREA to "Haarlemmerbuurt",
        LabelKind.WATER to "Nieuwe Meer", LabelKind.AREA to "Spaarndammer- en Zeeheldenbuurt",
    )

    private fun differing(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    @Test
    fun eachSpriteIsLabelPaintersPixels() {
        var compared = 0L
        var sprites = 0
        for (density in floatArrayOf(2f, 2.625f, 3.5f)) {
            val painter = LabelPainter(density).also { it.typeface = face }
            val maker = LabelSprites(painter)
            for ((i, n) in names.withIndex()) {
                val (kind, text) = n
                val sprite = maker.sprite(MapLabel("t$i", text, kind, listOf(LatLon(52.37, 4.88))), i)
                val w = sprite.width + 80
                val h = sprite.height + 80
                // at two whole-pixel anchors, so the frame's pixel grid plays no part
                for ((x, y) in listOf(w / 2 to h / 2, w / 2 + 7 to h / 2 + 3)) {
                    val direct = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    painter.drawAt(Canvas(direct), kind, text, x, y)
                    val put = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(put).drawBitmap(LabelSprites.bitmapOf(sprite), (x - sprite.anchorX).toFloat(), (y - sprite.anchorY).toFloat(), null)
                    val a = LabelSprites.pixelsOf(direct)
                    val b = LabelSprites.pixelsOf(put)
                    assertEquals("$text at density $density: pixels differ", 0, differing(a, b))
                    compared += a.size
                }
                assertTrue("$text: the sprite holds pixels", sprite.pixels.any { it != 0 })
                sprites++
            }
        }
        println("label sprites: $sprites sprites at 3 densities equal LabelPainter's drawing, $compared pixels compared")
    }

    @Test
    fun theBoardsLabelsAreTheirSpritesWhereLabelPainterSetsThem() {
        val density = 2.625f
        val w = (412 * density).roundToInt()
        val h = (915 * density).roundToInt()
        val camera = MapCamera(LatLon(52.3765, 4.8890), 14.6, bearing = 18.0, pitch = 46.0, width = w, height = h, density = density)
        val g = GroundView(camera)
        val labels = listOf(
            MapLabel("water:IJ", "IJ", LabelKind.WATER, listOf(LatLon(52.3830, 4.8990), LatLon(52.3810, 4.9100)), 9),
            MapLabel("area:Jordaan", "Jordaan", LabelKind.AREA, listOf(LatLon(52.3745, 4.8810)), 7),
            MapLabel("area:Grachtengordel", "Grachtengordel-West", LabelKind.AREA, listOf(LatLon(52.3720, 4.8870)), 6),
            MapLabel("water:Oosterdok", "Oosterdok", LabelKind.WATER, listOf(LatLon(52.3740, 4.9080)), 5),
            MapLabel("area:Haarlemmerbuurt", "Haarlemmerbuurt", LabelKind.AREA, listOf(LatLon(52.3815, 4.8870)), 5),
            MapLabel("area:Westelijke Eilanden", "Westelijke Eilanden", LabelKind.AREA, listOf(LatLon(52.3870, 4.8880)), 4),
            MapLabel("area:De Wallen", "De Wallen", LabelKind.AREA, listOf(LatLon(52.3730, 4.8990)), 4),
        )
        val keepOut = floatArrayOf(0f, 0f, w.toFloat(), 120f * density, 0f, h - 150f * density, w.toFloat(), h.toFloat())
        val painter = LabelPainter(density).also { it.typeface = face }
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(frame), g, labels, keepOut, 2, FloatArray(0), 0)
        assertTrue("the board sets labels", painter.placedCount >= 4)

        // The same places, found as LabelPainter finds them, and each label's sprite put down there.
        val maker = LabelSprites(painter)
        val placement = LabelPlacement()
        val m = FloatArray(4)
        val pt = FloatArray(2)
        val at = FloatArray(2)
        val put = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(put)
        var placed = 0
        for ((i, label) in labels.sortedByDescending { it.priority }.withIndex()) {
            painter.measures(label.kind, label.text, m)
            var found = false
            for (p in label.points) {
                if (!g.project(p, pt)) continue
                if (placement.tryPoint(m[0], m[1], m[2], pt[0], pt[1], g.width, g.height, keepOut, 2, FloatArray(0), 0, at)) {
                    found = true
                    break
                }
            }
            if (!found) continue
            val sprite = maker.sprite(label, i)
            val x = at[0].roundToInt() - sprite.anchorX
            val y = (at[1] - m[3]).roundToInt() - sprite.anchorY
            canvas.drawBitmap(LabelSprites.bitmapOf(sprite), x.toFloat(), y.toFloat(), null)
            placed++
        }
        assertEquals("the same labels placed", painter.placedCount, placed)
        val a = LabelSprites.pixelsOf(frame)
        val b = LabelSprites.pixelsOf(put)
        assertEquals("the board's labels: pixels differ", 0, differing(a, b))
        println("label sprites: the board's $placed labels at density $density, ${a.size} pixels equal LabelPainter's")
    }
}
