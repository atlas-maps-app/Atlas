package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import app.atlas.mapcore.state.RouteBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteOrder

/**
 * The routes and areas for the vector engine (ENGINE.md step 6): a route's chip is OverlayPainter.routeChip's own
 * pixels, put down where the core puts it (its centre on a whole pixel), but for a few pixels of its pill's edge (by a
 * sub-sample or two of Skia's antialiasing), at three densities, a chip drawn once for the hand-overs after; and the block carries the routes in the painter's order (the alternatives first) with their looks
 * as the painter sets them (an alternative's quieter fill and narrower width, the faded colour), their points with the
 * distances along them RouteRibbons measures, and the areas as they are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RouteChipsTest {
    private val a = LatLon(52.37, 4.88)
    private val b = LatLon(52.375, 4.885)
    private val c = LatLon(52.38, 4.885)

    @Test
    fun aChipIsThePaintersPixels() {
        for (density in floatArrayOf(2f, 2.625f, 3.5f)) {
            val painter = OverlayPainter(density)
            val chips = RouteChips(OverlayPainter(density))
            for ((label, quiet) in listOf("6′" to false, "1 h 05′" to false, "12′" to true)) {
                val image = chips.chip(label, 0xFF2E5BFF.toInt(), quiet)
                assertSame("kept for the next hand-over", image, chips.chip(label, 0xFF2E5BFF.toInt(), quiet))
                val w = image.width + 80
                val h = image.height + 80
                val x = image.anchorX + 40
                val y = image.anchorY + 40
                val drawn = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                painter.routeChip(Canvas(drawn), label, x.toFloat(), y.toFloat(), 0xFF2E5BFF.toInt(), quiet)
                val want = LabelSprites.pixelsOf(drawn)
                drawn.recycle()
                val got = IntArray(w * h)
                for (row in 0 until image.height) {
                    for (col in 0 until image.width) got[(y - image.anchorY + row) * w + x - image.anchorX + col] = image.pixels[row * image.width + col]
                }
                // a sub-sample or two of Skia's antialiasing on the pill's edge at most, where Skia computes its path's
                // points in float at the place it draws them (MarkerSpritesTest's bound); the text exactly
                val most = want.indices.maxOf { i -> (0 until 4).maxOf { k -> kotlin.math.abs(((want[i] ushr (8 * k)) and 0xFF) - ((got[i] ushr (8 * k)) and 0xFF)) } }
                assertTrue("$label at density $density: $most levels", most <= 32)
                assertTrue("$label at density $density", want.indices.count { want[it] != got[it] } * 200 < image.pixels.size)
            }
        }
    }

    @Test
    fun theBlockCarriesThePaintersRoutes() {
        val main = RouteLine("main", listOf(a, b, c), colour = 0xFF2E5BFF, travelled = 120.0, label = "6′")
        val alt = RouteLine("alt", listOf(a, c), colour = 0xFF2E5BFF, alternative = true, label = "8′")
        val walk = RouteLine("walk", listOf(b, c), colour = 0xFF1F9D55, dashed = true)
        val area = AreaOverlay("area", a, 200.0)
        val block = RouteChips(OverlayPainter(2f)).block(MapOverlays(routes = listOf(main, alt, walk), areas = listOf(area)))
            .duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(RouteBlock.MAGIC, block.int)
        assertEquals(2f, block.float)
        assertEquals(1, block.int)
        assertEquals(3, block.int)
        assertEquals(2, block.int)
        assertEquals(52.37, block.double, 0.0)
        assertEquals(4.88, block.double, 0.0)
        assertEquals(200f, block.float)
        assertEquals(0x332E5BFF, block.int)
        assertEquals(0xFF2E5BFF.toInt(), block.int)
        // the alternative first, quieter and narrower, its chip the first; then the main routes in their order
        fun route(r: RouteLine, chip: Int) {
            assertEquals(r.points.size, block.int)
            assertEquals(OverlayPainter.widthOf(r), block.float)
            val fill = OverlayPainter.fillOf(r)
            assertEquals(fill, block.int)
            assertEquals(OverlayPainter.fadedOf(fill), block.int)
            assertEquals(-1, block.int)
            assertEquals(if (r.dashed) 1 else 0, block.get().toInt())
            repeat(3) { assertEquals(0, block.get().toInt()) }
            assertEquals(r.travelled, block.double, 0.0)
            assertEquals(chip, block.int)
            val along = RouteRibbons.along(r.points)
            for (i in r.points.indices) {
                assertEquals(r.points[i].lat, block.double, 0.0)
                assertEquals(r.points[i].lon, block.double, 0.0)
                assertEquals(along[i], block.double, 0.0)
            }
        }
        route(alt, 0)
        route(main, 1)
        route(walk, RouteBlock.NO_CHIP)
        assertEquals(0.82f * alt.widthDp, OverlayPainter.widthOf(alt))
        repeat(2) {
            val w = block.int
            val h = block.int
            block.int
            block.int
            block.position(block.position() + 4 * w * h)
        }
        assertEquals(0, block.remaining())
        // a route's distances along it: true metres between its points (0.005° north is 557 m, and east 340 m here)
        val along = RouteRibbons.along(listOf(a, b, c))
        assertEquals(0.0, along[0], 0.0)
        assertEquals(652.0, along[1], 2.0)
        assertEquals(556.6, along[2] - along[1], 1.0)
    }
}
