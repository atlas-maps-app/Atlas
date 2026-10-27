package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import app.atlas.mapcore.state.MarkerBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The markers' sprites for the vector engine (ENGINE.md step 6) are MarkerPainter's own pixels: each look, put down
 * where the core puts it (the marker's place on a whole pixel), equals MarkerPainter's drawing of the marker there
 * where nothing cuts it, but for a few pixels (fewer than one in 200, by a sub-sample or two of Skia's antialiasing) on
 * a path's edges, where Skia computes the path's points in float at the place it draws them; a named pin's with its
 * name right of the pin and left of it, at three densities; a cluster's count equals MarkerPainter's count over its bubble; markers that look alike share a look; and
 * the block carries what the core places the markers by (MarkerLayout's inputs) and every count the markers can make.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MarkerSpritesTest {
    private val at = LatLon(52.37, 4.88)

    private val markers = listOf(
        Marker("pin", at, "cafe"),
        Marker("chosen", LatLon(52.371, 4.881), "cafe", 0xFFE8612B, selected = true, value = "4′", badge = "check", ring = 0.6f),
        Marker("named", at, "cafe", 0xFF2E5BFF, label = "De Belhamel"),
        Marker("dot", at, "cafe", 0xFFF2F2F2, style = MarkerStyle.DOT),
        Marker("brand", at, "cafe", 0xFF00704A, style = MarkerStyle.BRAND, logo = "nobrand", label = "Bagels & Beans"),
        Marker("chip", at, "charging_station", 0xFF1F9D55, style = MarkerStyle.CHIP, value = "150", ring = 0.3f),
        Marker("cluster", at, "cafe", 0xFF2E5BFF, style = MarkerStyle.CLUSTER, count = 12),
        Marker("pin again", LatLon(52.38, 4.9), "cafe"),
    )

    /** A block read back as the core reads it (`CP/markers/Markers.hpp`'s read_marker_block). */
    private class Read(val density: Float, val looks: List<MarkerBlock.Image>, val markers: List<MarkerBlock.Marker>, val counts: List<MarkerBlock.Count>)

    private fun read(block: ByteBuffer): Read {
        val b = block.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(MarkerBlock.MAGIC, b.int)
        val density = b.float
        val nLooks = b.int
        val nMarkers = b.int
        val nCounts = b.int
        fun image(): MarkerBlock.Image {
            val w = b.int
            val h = b.int
            val ax = b.int
            val ay = b.int
            return MarkerBlock.Image(w, h, ax, ay, IntArray(w * h) { b.int })
        }
        val looks = List(nLooks) { image() }
        val markers = List(nMarkers) {
            val lat = b.double
            val lon = b.double
            val priority = b.int
            val style = b.get().toInt()
            val selected = b.get().toInt() == 1
            val value = b.get().toInt() == 1
            assertEquals(0, b.get().toInt())
            MarkerBlock.Marker(lat, lon, priority, style, selected, value, b.int, b.float, b.float, b.int, b.int, b.int, b.int)
        }
        val counts = List(nCounts) { MarkerBlock.Count(b.int, b.int, image()) }
        assertEquals(0, b.remaining())
        return Read(density, looks, markers, counts)
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** [image] put down with its anchor at ([x], [y]) on a clear [w] × [h] bitmap's pixels. */
    private fun putDown(image: MarkerBlock.Image, w: Int, h: Int, x: Int, y: Int): IntArray {
        val out = IntArray(w * h)
        for (row in 0 until image.height) {
            for (col in 0 until image.width) {
                val px = x - image.anchorX + col
                val py = y - image.anchorY + row
                if (px in 0 until w && py in 0 until h) out[py * w + px] = image.pixels[row * image.width + col]
            }
        }
        return out
    }

    private fun differing(a: IntArray, b: IntArray): Int = a.indices.count { a[it] != b[it] }

    /** The largest difference of a channel between [a] and [b], levels. */
    private fun largest(a: IntArray, b: IntArray): Int = a.indices.maxOf { i ->
        (0 until 4).maxOf { k -> kotlin.math.abs(((a[i] ushr (8 * k)) and 0xFF) - ((b[i] ushr (8 * k)) and 0xFF)) }
    }

    /** Where [a] and [b] differ, from the place at ([x], [y]) on a [w] px wide bitmap: the box and the first few. */
    private fun where(a: IntArray, b: IntArray, w: Int, x: Int, y: Int): String {
        val at = a.indices.filter { a[it] != b[it] }
        if (at.isEmpty()) return "none"
        val xs = at.map { it % w - x }
        val ys = at.map { it / w - y }
        val first = at.take(4).joinToString { "(${it % w - x}, ${it / w - y}) %08x/%08x".format(a[it], b[it]) }
        return "x ${xs.min()}..${xs.max()}, y ${ys.min()}..${ys.max()}: $first"
    }

    @Test
    fun eachLookIsMarkerPaintersPixels() {
        var compared = 0
        var edges = 0
        var pixels = 0
        for (density in floatArrayOf(2f, 2.625f, 3.5f)) {
            val painter = MarkerPainter(density)
            val read = read(MarkerSprites(painter).block(markers))
            assertEquals(density, read.density)
            val reference = MarkerPainter(density)
            for ((i, m) in markers.withIndex()) {
                if (m.style == MarkerStyle.CLUSTER) continue
                val looks = if (m.label != null && m.style == MarkerStyle.PIN) listOf(false, true) else listOf(false)
                for (left in looks) {
                    val image = read.looks[if (left) read.markers[i].lookLeft else read.markers[i].look]
                    // with room all round, so nothing is cut (Skia blurs a shadow the bitmap cuts another way); the name
                    // on the side the sprite took (MarkerPainter picks it by the view's width)
                    val w = image.width + 80
                    val h = image.height + 80
                    val x = image.anchorX + 40
                    val y = image.anchorY + 40
                    val drawn = bitmap(w, h)
                    reference.viewWidth = if (left) 0f else Float.MAX_VALUE
                    reference.draw(Canvas(drawn), m, x.toFloat(), y.toFloat(), 0)
                    val want = LabelSprites.pixelsOf(drawn)
                    drawn.recycle()
                    val got = putDown(image, w, h, x, y)
                    val most = largest(want, got)
                    val what = "${m.id} at density $density${if (left) ", its name left" else ""}: $most levels at most, ${where(want, got, w, x, y)}"
                    // a sub-sample of Skia's antialiasing is 16 levels: an edge's pixel may flip two
                    assertTrue(what, most <= 32)
                    edges += differing(want, got)
                    pixels += image.pixels.size
                    compared++
                }
            }
        }
        assertEquals(3 * 8, compared)
        // the few that differ (none at density 2; at 2.625 and 3.5 up to 14 levels) are a path's edges: Skia computes a
        // path's points in float where it draws them, so a circle or an arc's cap drawn elsewhere covers a few of its
        // edge's sub-samples otherwise
        assertTrue("$edges of $pixels differ", edges * 200 < pixels)
    }

    @Test
    fun aClustersCountIsMarkerPaintersCount() {
        val density = 2.625f
        val painter = MarkerPainter(density)
        val read = read(MarkerSprites(painter).block(markers))
        // the cluster of 12 and the dot can make every count up to all the markers' (seven of one and the cluster's 12)
        val keys = read.counts.filter { it.ink == -1 }.map { it.key }
        assertEquals((1..19).toList(), keys)
        // the dot is light: its count would be written in ink
        assertTrue(read.counts.any { it.ink == MarkerPainter.INK })
        val reference = MarkerPainter(density)
        for (c in read.counts) {
            val drawn = bitmap(120, 120)
            reference.drawCount(Canvas(drawn), c.key, 60f, 60f, c.ink)
            val want = LabelSprites.pixelsOf(drawn)
            drawn.recycle()
            assertEquals("count ${c.key}", 0, differing(want, putDown(c.image, 120, 120, 60, 60)))
        }
    }

    @Test
    fun theBlockCarriesWhatTheCorePlacesBy() {
        val density = 2f
        val painter = MarkerPainter(density)
        val read = read(MarkerSprites(painter).block(markers))
        val mm = read.markers
        assertEquals(markers.size, mm.size)
        // the pins that look alike share a look; the cluster has none; the named pin a second
        assertEquals(mm[0].look, mm[7].look)
        assertEquals(MarkerBlock.NO_LOOK, mm[6].look)
        assertNotEquals(mm[2].look, mm[2].lookLeft)
        assertEquals(mm[0].look, mm[0].lookLeft)
        assertEquals(7, read.looks.size)
        with(mm[1]) {
            assertEquals(MarkerBlock.PIN, style)
            assertTrue(selected)
            assertTrue(value)
            assertEquals(painter.chipTextWidth(markers[1]), chipText)
            assertEquals(-1f, name)
            assertEquals(0xFFE8612B.toInt(), colour)
        }
        assertEquals(painter.nameWidth(markers[2]), mm[2].name)
        assertEquals(MarkerBlock.CHIP, mm[5].style)
        assertEquals(painter.chipTextWidth(markers[5]), mm[5].chipText)
        assertEquals(12, mm[6].count)
        assertEquals(MarkerPainter.onColour(0xFFF2F2F2.toInt()), mm[3].ink)
        assertEquals(52.371, mm[1].lat, 0.0)
        assertEquals(4.881, mm[1].lon, 0.0)
    }

    @Test
    fun theCountsKeysAreOneForEachText() {
        assertEquals(listOf(1, 2, 3), MarkerSprites.countKeys(3))
        val k = MarkerSprites.countKeys(1234)
        // 999 of them, then "1k", "1.1k" and "1.2k"
        assertEquals(1002, k.size)
        assertEquals(listOf(999, 1000, 1100, 1200), k.subList(998, 1002))
        // every text once, up to MAX_COUNTS of them
        assertEquals(MarkerSprites.MAX_COUNTS, MarkerSprites.countKeys(Int.MAX_VALUE).size)
        for (n in listOf(7, 999, 1000, 1234, 9999, 10_000, 15_400)) {
            assertEquals(MarkerPainter.countLabel(n), MarkerPainter.countLabel(MarkerBlock.countKey(n)))
        }
    }
}
