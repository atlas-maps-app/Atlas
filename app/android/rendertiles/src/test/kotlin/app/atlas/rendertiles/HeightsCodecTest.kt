package app.atlas.rendertiles

import app.atlas.rendertiles.codec.HeightsCodec
import app.atlas.rendertiles.codec.LeSink
import app.atlas.rendertiles.codec.LeSource
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.HeightRaster
import app.atlas.rendertiles.model.Heights
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleVersion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The heights layer (ENGINE.md part 6, terrain): the writer's tile is, byte for byte, the tile the core's tests read
 * (`mapcore/cpp/tests/tiles/data/heights-17.tile`, written by neither: heights_test.cpp reads the same samples from
 * it), a raster reads back as written, the reader refuses what [Heights] says it must, and the packer's rules for a
 * raster's side and step hold what the plan measured.
 */
class HeightsCodecTest {
    /** The surface of the data file, in whole steps: heights_test.cpp's. */
    private fun surface(r: Int, c: Int): Long = -40L + (3 * r * r + 5 * c * c + 7 * r * c) % 97 + 2 * (r + 8) + (c + 8)

    private fun raster(): HeightRaster {
        val w = 33
        val steps = LongArray(w * w) { surface(it / w - 8, it % w - 8) }
        val base = steps.min()
        return HeightRaster(17, 8, 0.0625, base, IntArray(steps.size) { (steps[it] - base).toInt() }, Heights.FLAG_FILLED, listOf("ahn4", "glo30"), listOf(1080L, 4L))
    }

    private val golden: File get() = File(System.getProperty("atlas.repo"), "app/android/mapcore/cpp/tests/tiles/data/heights-17.tile")

    @Test
    fun theWriterWritesTheTileTheCoreReads() {
        val bytes = HeightsCodec.encodeTile(14, 8414, 5384, raster())
        assertArrayEquals("the writer's tile is not mapcore/cpp/tests/tiles/data/heights-17.tile", golden.readBytes(), bytes)
        val d = HeightsCodec.decodeTile(bytes)
        assertEquals(listOf(14, 8414, 5384), listOf(d.level, d.x, d.y))
        val r = d.raster
        assertEquals(17, r.side)
        assertEquals(8, r.apron)
        assertEquals(0.0625, r.step, 0.0)
        assertEquals(Heights.FLAG_FILLED, r.flags)
        assertEquals(listOf("ahn4", "glo30"), r.sources)
        assertEquals(listOf(1080L, 4L), r.shares)
        for (row in -8 until 25) for (c in -8 until 25) assertEquals("sample $row, $c", surface(row, c), r.steps(row, c))
        assertEquals(0.0625 * surface(3, 5), r.metres(3, 5), 0.0)
        // the tile's one feature is the raster, in its first cell; its reach is the tile's box and the raster's top
        val e = HeightsCodec.encoded(14, 8414, 5384, raster())
        assertEquals(1, e.featureCount)
        assertArrayEquals(Grid.tileBox(14, 8414, 5384), e.reach.box)
        assertEquals(Math.ceil(0.0625 * (r.base + r.max) * 100).toInt(), e.reach.topCm)
        assertEquals(mapOf("heights" to 1), e.layerCounts)
    }

    @Test
    fun theHeightsLayerIsNoLayerOfTheMapsSchema() {
        // so a map pack's style version does not move with it, and the map's readers skip it as a layer they do not know
        assertThrows(UnsupportedOperationException::class.java) { Schema.columns(Layer.HEIGHTS) }
        assertEquals(16, Layer.HEIGHTS.id)
        assertTrue(StyleVersion.ID.startsWith("s${Format.PAYLOAD}."))
    }

    @Test
    fun aRasterIsRoundedOnTheLatticeOfWholeSteps() {
        val w = 3 + 2
        val heights = FloatArray(w * w) { -1.27f + it * 0.31f }
        val r = HeightRaster.of(heights, 3, 1, 0.125, 0, listOf("a"), listOf(25L))
        for (i in heights.indices) assertEquals(Math.rint(heights[i].toDouble() / 0.125).toLong(), r.base + r.samples[i])
        assertEquals(0, r.samples.min())
        val back = HeightsCodec.decodeTile(HeightsCodec.encodeTile(12, 2103, 1346, r)).raster
        assertArrayEquals(r.samples, back.samples)
        assertEquals(r.base, back.base)
    }

    @Test
    fun theReaderRefusesABadLengthRangeLowestAndHighestSample() {
        val r = raster()
        fun samples(change: (IntArray) -> Unit = {}, width: Int = 33): ByteArray = HeightsCodec.med(r.samples.copyOf().also(change), width)
        fun refused(bytes: ByteArray, width: Int = 33, max: Long = r.max.toLong()): String =
            assertThrows(TileFormatException::class.java) { HeightsCodec.unmed(LeSource(bytes), width, max) }.message!!
        assertArrayEquals(r.samples, HeightsCodec.unmed(LeSource(samples()), 33, r.max.toLong()))
        // lengths
        assertTrue(refused(samples(), width = 31).contains("its side and apron give 31"))
        assertTrue(refused(samples().copyOf(600)).contains("samples in"))
        assertTrue(refused(samples().let { it.copyOf(it.size - 1) }).contains("ends early"))
        assertTrue(refused(samples() + 0.toByte()).contains("after the last sample"))
        // ranges: a sample above the highest the tile names, one below the base, a residual that wraps
        assertTrue(refused(samples({ it[1000] = r.max + 1 })).contains("outside 0 to ${r.max}"))
        val wrap = LeSink(64).also { s -> s.varU(33); s.varU(33); repeat(33 * 33) { s.varZ(Long.MAX_VALUE) } }.toByteArray()
        assertTrue(refused(wrap).contains("outside 0 to"))
        // the lowest is the base, the highest the one the tile names
        assertTrue(refused(samples(), max = r.max + 1L).contains("not the ${r.max + 1}"))
        assertTrue(refused(HeightsCodec.med(IntArray(r.samples.size) { r.samples[it] + 1 }, 33), max = r.max + 1L).contains("not 0"))
        // a tile's own checks: cut short, another payload, no heights layer
        val tile = HeightsCodec.encodeTile(14, 8414, 5384, r)
        assertThrows(TileFormatException::class.java) { HeightsCodec.decodeTile(tile.copyOf(tile.size - 3)) }
        assertThrows(TileFormatException::class.java) { HeightsCodec.decodeTile(tile.copyOf().also { it[4] = 2 }) }
        assertThrows(TileFormatException::class.java) { HeightsCodec.decodeTile(tile.copyOf().also { it[Format.HEADER_BYTES] = 15 }) }
        // a raster the model refuses: a side that is no 2^k + 1, shares past the samples
        assertThrows(IllegalArgumentException::class.java) { HeightRaster(18, 0, 1.0, 0, IntArray(18 * 18), 0, emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HeightRaster(3, 0, 1.0, 0, IntArray(9), 0, listOf("a"), listOf(10L)) }
    }

    @Test
    fun aRastersSideAndStepFollowItsSourceAndItsSpacing() {
        // L14 at Amsterdam: samples 5.9 m apart, a step of 1/16 m
        val ams = Grid.row(Grid.round(52.37), 14)
        assertEquals(5.88, Heights.spacing(14, ams, 257), 0.05)
        assertEquals(0.0625, Heights.step(Heights.spacing(14, ams, 257)), 0.0)
        assertEquals(0.25, Heights.step(Heights.spacing(12, Grid.row(Grid.round(52.37), 12), 257)), 0.0)
        // TINITALY's 10 m stops at L13; GLO-30's 30 m at L12; AHN read at 4 m and Kartverket's 1 m reach L14
        val tuscany = { z: Int -> Grid.row(Grid.round(43.47), z) }
        assertEquals(257, Heights.sideFor(13, tuscany(13), 10.0))
        assertEquals(129, Heights.sideFor(14, tuscany(14), 10.0))
        assertEquals(257, Heights.sideFor(12, tuscany(12), 30.0))
        assertEquals(129, Heights.sideFor(13, tuscany(13), 30.0))
        assertEquals(257, Heights.sideFor(14, ams, 4.0))
        assertEquals(257, Heights.sideFor(14, Grid.row(Grid.round(60.39), 14), 1.0))
        assertNull(Heights.sideFor(20, 0, 90.0))
        // a level's samples lie on one lattice: a tile's last column is the next tile's first, every second sample the level above's
        assertEquals(Heights.lonOf(14, 8414, 256, 257), Heights.lonOf(14, 8415, 0, 257), 0.0)
        assertEquals(Heights.latOf(14, 5384, 256, 257), Heights.latOf(14, 5385, 0, 257), 0.0)
        assertEquals(Heights.latOf(13, 2692, 37, 257), Heights.latOf(14, 5384, 74, 257), 0.0)
        assertEquals(Grid.deg(Grid.tileBox(14, 8414, 5384)[2]), Heights.latOf(14, 5384, 0, 257), 1e-7)
        assertEquals(Grid.deg(Grid.tileBox(14, 8414, 5384)[1]), Heights.lonOf(14, 8414, 0, 257), 1e-7)
    }
}
