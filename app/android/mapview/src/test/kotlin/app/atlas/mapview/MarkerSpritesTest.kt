package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import app.atlas.mapcore.state.MarkerBlock
import app.atlas.places.Categories
import app.atlas.places.Palette
import app.atlas.places.PlaceFamily
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
        // closed places, lighter
        Marker("closed", LatLon(52.372, 4.882), "cafe", 0xFFB8461A, faded = true),
        Marker("closed dot", at, "cafe", 0xFF1E7A46, style = MarkerStyle.DOT, faded = true),
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
        assertEquals(3 * 10, compared)
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
        // the cluster of 12 and the dots can make every count up to all the markers' (nine of one and the cluster's 12)
        val keys = read.counts.filter { it.ink == -1 }.map { it.key }
        assertEquals((1..21).toList(), keys)
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

    /**
     * A closed place's marker ([Marker.faded]) sinks into the map: by day all of it half way to white, its colour lighter
     * (Palette.closed) and its ring white still; by night its colour its own and its ring half way into the night's
     * ground. The core's looks and bubbles are the painter's, by day and by night. Draws markers-closed.png for judging:
     * every family's pin and dot, open then closed, by day and by night.
     */
    @Test
    fun aClosedPlaceSinksIntoTheMap() {
        val density = 2f
        val colour = 0xFFB8461A.toInt()
        val open = Marker("open", at, "cafe", colour.toLong() and 0xFFFFFFFFL)
        val closed = open.copy(faded = true)
        // the pin's pixel 10 dp right of its centre (its 13 dp disc, clear of the 7 dp symbol) and 14 dp (its 2 dp ring)
        fun disc(m: Marker, night: Boolean): Pair<Int, Int> {
            val b = bitmap(100, 100)
            MarkerPainter(density).also { it.night = night }.draw(Canvas(b), m, 50f, 50f, 0)
            val got = b.getPixel(50 + (10 * density).toInt(), 50) to b.getPixel(50 + (14 * density).toInt(), 50)
            b.recycle()
            return got
        }
        val white = 0xFFFFFFFF.toInt()
        val sunk = Colours.mix(white, MapController.NIGHT_EARTH, Palette.CLOSED_FADE)
        assertEquals(colour to white, disc(open, night = false))
        assertEquals(colour to white, disc(open, night = true))
        assertEquals(Palette.closed(colour) to white, disc(closed, night = false))
        assertEquals(colour to sunk, disc(closed, night = true))
        // the core's: each closed look is the painter's by night as by day, its bubble and count as the painter's
        for (night in listOf(false, true)) {
            val faded = markers.filter { it.faded }
            val read = looksArePainters(faded, density, night)
            for ((i, m) in faded.withIndex()) {
                val own = m.colour.toInt()
                assertEquals(if (night) own else Palette.closed(own), read.markers[i].colour)
                assertEquals(if (night) sunk else white, read.markers[i].ink)
            }
        }
        sheet(density)
    }

    /**
     * A place of a kind people come to its category for most (the owner, 6 November: "A black outline?", then "Yes, map
     * only") wears a 2 dp ring round its white ring, its ring of hours inside it: the boards' ink by day, white by night,
     * and lighter on a closed place like the rest of it; never round the chosen pin, which stands out already. It is
     * drawn, not placed by: its box is the plain marker's, as the core lays it out. The core's looks are the painter's.
     */
    @Test
    fun whatPeopleComeForMostIsRinged() {
        val density = 2f
        val mm = MarkerMetrics(density)
        val pin = Marker("own", at, "grocery", 0xFF1E7A46, outlined = true)
        val dot = pin.copy(id = "own dot", style = MarkerStyle.DOT)
        val brand = pin.copy(id = "own brand", style = MarkerStyle.BRAND, logo = "nobrand", label = "Albert Heijn")
        val hours = pin.copy(id = "own pin with hours", ring = 0.6f)
        val chosen = pin.copy(id = "own chosen", selected = true)
        // the pixel right of the centre, half the outline's width past the white ring (and past a ring of hours)
        fun rim(m: Marker, night: Boolean = false): Int {
            val b = bitmap(100, 100)
            MarkerPainter(density).also { it.night = night }.draw(Canvas(b), m, 50f, 50f, 0)
            val half = when (m.style) {
                MarkerStyle.DOT -> mm.dotDiameter(false)
                MarkerStyle.BRAND -> mm.brandSide(false)
                else -> mm.pinDiameter(false)
            } / 2 + (if (m.ring != null) mm.ring else 0f)
            val got = b.getPixel(50 + (half + mm.outline / 2).toInt(), 50)
            b.recycle()
            return got
        }
        val ink = MarkerPainter.INK
        val white = 0xFFFFFFFF.toInt()
        for (m in listOf(pin, dot, brand, hours)) {
            assertEquals(m.id, ink, rim(m))
            assertEquals(m.id, white, rim(m, night = true))
            assertNotEquals(m.id, ink, rim(m.copy(outlined = false)))
            assertEquals(m.id, Palette.closed(ink), rim(m.copy(faded = true)))
            assertEquals(m.id, Colours.mix(white, MapController.NIGHT_EARTH, Palette.CLOSED_FADE), rim(m.copy(faded = true), night = true))
        }
        // the chosen pin as it was
        fun drawn(m: Marker): IntArray {
            val b = bitmap(160, 160)
            MarkerPainter(density).draw(Canvas(b), m, 80f, 120f, 0)
            return LabelSprites.pixelsOf(b).also { b.recycle() }
        }
        assertEquals(0, differing(drawn(chosen.copy(outlined = false)), drawn(chosen)))
        // placed as the plain marker is, by the painter's layout as by the core's
        val painter = MarkerPainter(density)
        for (m in listOf(pin, dot, brand, hours, chosen)) {
            val a = FloatArray(4)
            val b = FloatArray(4)
            MarkerLayout.boxOf(m, 0f, 0f, mm, painter.chipWidth, a)
            MarkerLayout.boxOf(m.copy(outlined = false), 0f, 0f, mm, painter.chipWidth, b)
            assertTrue(m.id, a.contentEquals(b))
        }
        for (night in listOf(false, true)) looksArePainters(listOf(pin, dot, brand, hours, pin.copy(id = "own closed", faded = true)), density, night)
    }

    /**
     * Each of [ms]'s looks in the core's block, put down where the core puts it, is the painter's drawing of it by
     * [night] or by day, but for a few levels of antialiasing on a path's edges.
     */
    private fun looksArePainters(ms: List<Marker>, density: Float, night: Boolean): Read {
        val read = read(MarkerSprites(MarkerPainter(density)).block(ms, night))
        val reference = MarkerPainter(density).also { it.night = night }
        for ((i, m) in ms.withIndex()) {
            val image = read.looks[read.markers[i].look]
            val w = image.width + 80
            val h = image.height + 80
            val drawn = bitmap(w, h)
            reference.draw(Canvas(drawn), m, image.anchorX + 40f, image.anchorY + 40f, 0)
            val most = largest(LabelSprites.pixelsOf(drawn), putDown(image, w, h, image.anchorX + 40, image.anchorY + 40))
            drawn.recycle()
            assertTrue("${m.id} by ${if (night) "night" else "day"}: $most levels at most", most <= 32)
        }
        return read
    }

    /** Every family's pin and dot, open then closed, by day on the day's ground and by night on the night's. */
    private fun sheet(density: Float) {
        val families = PlaceFamily.entries.map { f -> (Categories.byId(f.id)?.symbol ?: "cafe") to f.colour }
        Icons.warm(families.map { it.first })
        val cell = (44 * density).toInt()
        val w = cell * 4 * 2
        val h = cell * families.size
        val b = bitmap(w, h)
        val canvas = Canvas(b)
        for ((half, night) in listOf(false, true).withIndex()) {
            val fill = android.graphics.Paint().apply { color = if (night) MapController.NIGHT_EARTH else MapController.DAY_EARTH }
            canvas.drawRect(half * w / 2f, 0f, (half + 1) * w / 2f, h.toFloat(), fill)
            val painter = MarkerPainter(density).also { it.night = night }
            for ((row, family) in families.withIndex()) {
                val (symbol, colour) = family
                for (k in 0 until 4) {
                    val m = Marker("f$row$k", at, symbol, colour.toLong() and 0xFFFFFFFFL, if (k < 2) MarkerStyle.PIN else MarkerStyle.DOT, faded = k % 2 == 1)
                    painter.draw(canvas, m, (half * 4 + k) * cell + cell / 2f, row * cell + cell / 2f, 0)
                }
            }
        }
        val out = java.io.File(System.getProperty("atlas.shots") ?: System.getProperty("java.io.tmpdir"), "markers-closed.png")
        out.parentFile.mkdirs()
        val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val argb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        image.setRGB(0, 0, w, h, argb, 0, w)
        javax.imageio.ImageIO.write(image, "png", out)
        b.recycle()
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
        assertEquals(9, read.looks.size)
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
