package app.atlas.mapview.svg

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * [TurbulenceCache] against [Turbulence.colorWebKit] computed afresh: bit-identical on random rectangles (negative
 * offsets included), filter scales, channel masks and noise types, in memory and in mapped files, read back by a new
 * cache from disk, after damage to a file, under eviction, from many threads at once and after a cancelled fill.
 */
class TurbulenceCacheTest {
    private val dirs = ArrayList<File>()

    private fun dir(): File = Files.createTempDirectory("atlas-noise-test").toFile().also { dirs.add(it) }

    @After
    fun cleanUp() {
        for (d in dirs) d.deleteRecursively()
    }

    /**
     * The noise as the filters computed it before the cache: [Turbulence.colorWebKit] at each filter pixel's far corner,
     * on a generator of its own.
     */
    private fun direct(f: NoiseField, left: Int, top: Int, w: Int, h: Int): IntArray {
        val t = Turbulence(f.seed)
        val ix = 1f / f.scaleX
        val iy = 1f / f.scaleY
        return IntArray(w * h) { i ->
            t.colorWebKit((left + i % w + 1).toFloat() * ix, (top + i / w + 1).toFloat() * iy, f.baseX, f.baseY, f.octaves, f.fractal, f.channels)
        }
    }

    private fun cached(c: TurbulenceCache, f: NoiseField, left: Int, top: Int, w: Int, h: Int, map: NoiseMap? = null): IntArray {
        val out = IntArray(w * h)
        c.fill(f, left, top, w, h, out, map = map)
        return out
    }

    private fun randomField(r: Random): NoiseField {
        val scales = floatArrayOf(2f, 2.625f, 2.6251373f, 3.4951f, 0.43f, 1f / 3f, 1f)
        val freqs = floatArrayOf(0.0026f, 0.0055f, 0.012f, 0.02f, 0.16f, 0.26f, 0.55f, 0.75f, 0.9f, 1.4f, 0f)
        val s = scales[r.nextInt(scales.size)]
        return NoiseField(
            seed = floatArrayOf(3f, 4f, 5f, 8f, 11f, 13f, 17f, 23f, 31f, -7f, 0f)[r.nextInt(11)],
            baseX = freqs[r.nextInt(freqs.size - 1)],
            baseY = freqs[r.nextInt(freqs.size)],
            octaves = 1 + r.nextInt(4),
            fractal = r.nextBoolean(),
            scaleX = s,
            scaleY = if (r.nextInt(4) == 0) scales[r.nextInt(scales.size)] else s,
            channels = 1 + r.nextInt(15),
        )
    }

    @Test
    fun theFillAlwaysMatchesItsDefinition() {
        // fillWebKit is colorWebKit at each pixel's far corner, as SvgFilterRaster has always sampled it
        val t = Turbulence(13f)
        val out = IntArray(7 * 5)
        t.fillWebKit(-3, 41, 7, 5, 1f / 2.625f, 1f / 2.625f, 0.16f, 0.75f, 2, true, 7, out)
        for (y in 0 until 5) for (x in 0 until 7) {
            val expected = t.colorWebKit((x - 3 + 1).toFloat() * (1f / 2.625f), (41 + y + 1).toFloat() * (1f / 2.625f), 0.16f, 0.75f, 2, true, 7)
            assertEquals(expected, out[y * 7 + x])
        }
    }

    @Test
    fun randomRectanglesAreBitIdenticalInMemoryAndOnDisk() {
        val r = Random(9)
        for (c in listOf(TurbulenceCache(null), TurbulenceCache(dir()))) {
            repeat(60) {
                val f = randomField(r)
                val left = r.nextInt(-700, 700)
                val top = r.nextInt(-700, 700)
                val w = 1 + r.nextInt(if (r.nextBoolean()) 20 else 260)
                val h = 1 + r.nextInt(if (r.nextBoolean()) 20 else 260)
                // twice: made, then read
                repeat(2) { assertArrayEquals("$f at $left, $top, $w × $h", direct(f, left, top, w, h), cached(c, f, left, top, w, h)) }
            }
            val s = c.stats()
            assertTrue("$s", s.tilesMade > 100 && s.tilesRead >= 2 * s.tilesMade)
            c.clear()
        }
    }

    @Test
    fun aFieldReadBackFromDiskIsBitIdenticalAndNotMadeAgain() {
        val d = dir()
        val f = NoiseField(13f, 0.16f, 0.75f, 2, true, 2.625f, 2.625f, 15)
        val first = TurbulenceCache(d)
        val a = cached(first, f, -5, -9, 300, 200)
        first.close()
        assertEquals(1, d.listFiles()!!.count { it.name.endsWith(".noise") })
        val second = TurbulenceCache(d)
        assertEquals(listOf(f), second.kept())
        val b = cached(second, f, -5, -9, 300, 200)
        assertArrayEquals(direct(f, -5, -9, 300, 200), b)
        assertArrayEquals(a, b)
        val s = second.stats()
        assertEquals("$s", 0L, s.tilesMade)
        // 6 × 4 tiles: columns −1 … 4, rows −1 … 2
        assertEquals("$s", 6L * 4, s.tilesFromDisk)
        // a wider rectangle reuses what is kept and makes only the rest (columns −2 … 5)
        assertArrayEquals(direct(f, -70, -9, 400, 200), cached(second, f, -70, -9, 400, 200))
        assertEquals(2L * 4, second.stats().tilesMade)
        second.clear()
    }

    @Test
    fun aDamagedOrCutFileIsMadeGoodAgain() {
        val d = dir()
        val f = NoiseField(23f, 0.0026f, 0.0058f, 3, true, 2f, 2f, 1)
        TurbulenceCache(d).apply { cached(this, f, 0, 0, 256, 256) }.close()
        val file = d.listFiles()!!.single { it.name.endsWith(".noise") }
        // a byte inside some tile's pixels, and a cut through the last chunk
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(64 + 3 * (16 + 64 * 64 * 4) + 16 + 1000)
            val b = raf.read()
            raf.seek(64 + 3 * (16 + 64 * 64 * 4) + 16 + 1000)
            raf.write(b xor 0x5A)
        }
        val c = TurbulenceCache(d)
        assertArrayEquals(direct(f, 0, 0, 256, 256), cached(c, f, 0, 0, 256, 256))
        assertEquals(1L, c.stats().tilesRepaired)
        c.close()
        RandomAccessFile(file, "rw").use { raf -> raf.setLength(raf.length() - 5000) }
        val again = TurbulenceCache(d)
        assertArrayEquals(direct(f, 0, 0, 256, 256), cached(again, f, 0, 0, 256, 256))
        assertTrue(again.stats().tilesMade == 16L)
        again.clear()
    }

    @Test
    fun aFieldWithStepsKeepsTheMappedNoiseAndItsNoise() {
        val d = dir()
        val f = NoiseField(31f, 0.0055f, 0.017f, 4, true, 2f, 2f, 15)
        val stepped = f.copy(steps = "test;xor")
        val map = NoiseMap { p -> (p xor 0x00FF00FF) or (0x40 shl 24) }
        val c = TurbulenceCache(d)
        val expected = direct(f, 10, 20, 200, 150).map { map.map(it) }.toIntArray()
        assertArrayEquals(expected, cached(c, stepped, 10, 20, 200, 150, map))
        // the noise it was made from is kept too
        assertArrayEquals(direct(f, 10, 20, 200, 150), cached(c, f, 10, 20, 200, 150))
        assertEquals(2 * 4 * 3L, c.stats().tilesMade)
        c.close()
        val back = TurbulenceCache(d)
        assertEquals(setOf(f, stepped), back.kept().toSet())
        assertArrayEquals(expected, cached(back, stepped, 10, 20, 200, 150, map))
        assertEquals(0L, back.stats().tilesMade)
        back.clear()
    }

    @Test
    fun theLeastRecentlyUsedFieldsAreLetGoWithinTheBudget() {
        val d = dir()
        // room for two chunks (64 tiles each) at a time
        val c = TurbulenceCache(d, maxBytes = 2L * 64 * (16 + 64 * 64 * 4))
        val fields = (1..6).map { NoiseField(it.toFloat(), 0.5f, 0.5f, 1, true, 2f, 2f, 15) }
        for (f in fields) assertArrayEquals(direct(f, 0, 0, 300, 300), cached(c, f, 0, 0, 300, 300))
        val s = c.stats()
        assertTrue("$s", s.bytes <= c.maxBytes && s.fields < fields.size)
        // the first ones were let go; they come back from their files, the same
        for (f in fields) assertArrayEquals(direct(f, 0, 0, 300, 300), cached(c, f, 0, 0, 300, 300))
        assertTrue(c.stats().tilesFromDisk > 0)
        c.clear()
    }

    @Test
    fun theDiskBudgetDropsTheOldestFiles() {
        val d = dir()
        val one = 64L + 64 * (16 + 64 * 64 * 4)
        val c = TurbulenceCache(d, maxDiskBytes = 3 * one)
        for (k in 1..6) {
            val f = NoiseField(k.toFloat(), 0.5f, 0.5f, 1, true, 2f, 2f, 15)
            cached(c, f, 0, 0, 64, 64)
            c.close()
            Thread.sleep(5)
        }
        val left = d.listFiles()!!.filter { it.name.endsWith(".noise") }
        assertTrue("${left.size} files", left.size in 3..4)
        assertTrue(TurbulenceCache(d).kept().any { it.seed == 6f })
    }

    @Test
    fun manyThreadsAtOnceGetTheSameNoiseAndMakeEachTileOnce() {
        val f = NoiseField(11f, 0.012f, 0.26f, 2, true, 2.625f, 2.625f, 3)
        val c = TurbulenceCache(dir())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 32).map { k ->
                pool.submit<Boolean> {
                    val top = (k % 4) * 40 - 30
                    direct(f, -20, top, 500, 230).contentEquals(cached(c, f, -20, top, 500, 230))
                }
            }
            assertTrue(jobs.all { it.get() })
        } finally {
            pool.shutdown()
        }
        // the union is 9 tiles wide (−64 … 511) and 6 high (−64 … 319)
        assertEquals(9L * 6, c.stats().tilesMade)
        c.clear()
    }

    @Test
    fun warmUpReadiesTheScreenWideFieldsKeptBefore() {
        val d = dir()
        val screen = NoiseField(8f, 0.9f, 0.9f, 1, true, 2f, 2f, 15)
        val small = NoiseField(4f, 0.55f, 0.55f, 2, true, 2f, 2f, 3)
        val other = NoiseField(8f, 0.9f, 0.9f, 1, true, 3f, 3f, 15)
        TurbulenceCache(d).apply {
            cached(this, screen, 0, 0, 824, 1200)
            cached(this, small, 100, 100, 50, 50)
            cached(this, other, 0, 0, 824, 1830)
        }.close()
        val c = TurbulenceCache(d)
        assertEquals(listOf(screen), c.warmUp(824, 1830, 2f, 2f))
        // the kept tiles were read and checked (13 × 19), nothing made; a frame then makes only the rest of the screen
        assertEquals(0L, c.stats().tilesMade)
        assertEquals(13L * 19, c.stats().tilesFromDisk)
        assertArrayEquals(direct(screen, 0, 0, 824, 1830), cached(c, screen, 0, 0, 824, 1830))
        assertEquals(13L * 29 - 13 * 19, c.stats().tilesMade)
        assertEquals(13L * 19, c.stats().tilesFromDisk)
        c.clear()
    }

    @Test
    fun anUnwritableDirectoryKeepsTheNoiseOnTheHeap() {
        val d = dir()
        assertTrue(d.setWritable(false))
        try {
            val f = NoiseField(13f, 0.16f, 0.75f, 2, true, 2f, 2f, 15)
            val c = TurbulenceCache(d)
            repeat(2) { assertArrayEquals(direct(f, 0, 0, 200, 100), cached(c, f, 0, 0, 200, 100)) }
            assertEquals(4L * 2, c.stats().tilesMade)
            assertTrue(d.listFiles()!!.isEmpty())
        } finally {
            d.setWritable(true)
        }
    }

    /**
     * A fill cancelled part-way stops between tiles: those it made are whole (the noise exactly, from memory and read
     * back from disk), and the next fill makes only the rest.
     */
    @Test
    fun aCancelledFillStopsBetweenTilesAndKeepsOnlyWholeOnes() {
        val d = dir()
        val f = NoiseField(13f, 0.16f, 0.75f, 2, true, 2f, 2f, 15)
        val c = TurbulenceCache(d)
        // 10 × 10 tiles to make; the 31st it asks about is cancelled
        val asked = AtomicInteger()
        val e = runCatching { c.fill(f, 0, 0, 640, 640, IntArray(640 * 640), cancelled = { asked.incrementAndGet() > 30 }) }.exceptionOrNull()
        assertTrue("thrown: $e", e is CancellationException)
        assertEquals("tiles made before the cancel", 30L, c.stats().tilesMade)
        assertArrayEquals(direct(f, 0, 0, 640, 640), cached(c, f, 0, 0, 640, 640))
        assertEquals("tiles made in all", 100L, c.stats().tilesMade)
        c.close()
        val back = TurbulenceCache(d)
        assertArrayEquals(direct(f, 0, 0, 640, 640), cached(back, f, 0, 0, 640, 640))
        assertEquals(0L, back.stats().tilesMade)
        back.clear()
    }

    @Test
    fun differentScalesAndChannelsAreDifferentFields() {
        val a = NoiseField(13f, 0.16f, 0.75f, 2, true, 2.625f, 2.625f, 15)
        val b = a.copy(scaleX = 2.6251373f, scaleY = 2.6251373f)
        val m = a.copy(channels = 7)
        val c = TurbulenceCache(dir())
        for (f in listOf(a, b, m)) assertArrayEquals(direct(f, 0, 0, 100, 100), cached(c, f, 0, 0, 100, 100))
        assertTrue(TurbulenceCache.name(a) != TurbulenceCache.name(b) && TurbulenceCache.name(a) != TurbulenceCache.name(m))
        assertEquals(3, c.kept().size)
        c.clear()
    }
}
