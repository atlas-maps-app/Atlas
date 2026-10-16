package app.atlas.mapview

import android.graphics.Bitmap
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.ViewLadder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The exact raster made faster without a byte changed. [EngineFrame.rasterise] with its large filters in bands on the
 * filter workers and their noise kept in a [TurbulenceCache] (made cold, read warm, and read back from disk by a new
 * cache) against the single-thread raster with no cache: the 12 filter probes, the Westerkerk home by day (at the
 * boards' ×2 and a phone's ×2.625), by night and in the rain, from the port, and the Main board (Python's render, on the
 * owner's machine). Then the second frame's filters against the first's, and a raster cancelled part-way.
 *
 * Bands are only in the filters: Skia's anti-aliasing changes where a path is cut at a band's edge, so a frame drawn in
 * bands of shapes differs from the one-pass frame (measured 16 October: 9,763 of the Westerkerk's 1.5 M pixels at ×2,
 * by up to 18 levels; 8,607 with filters off), and the shapes stay one pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ParallelRasterTest {
    private val dirs = ArrayList<File>()

    private fun dir(): File = Files.createTempDirectory("atlas-raster-test").toFile().also { dirs.add(it) }

    @After
    fun cleanUp() {
        MapEngine.forgetCacheDir()
        for (d in dirs) d.deleteRecursively()
    }

    private fun raster(
        docs: List<SvgDocument>, scale: Float, bands: Int, noise: TurbulenceCache?, stats: EngineFrame.RasterStats? = null, cancelled: () -> Boolean = { false },
    ): Bitmap = EngineFrame.rasterise(docs, null, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, bands = bands, noise = noise, stats = stats, cancelled = cancelled)

    private fun differing(a: IntArray, b: Bitmap): Int {
        val p = SvgCanvas.premultipliedPixels(b)
        if (p.size != a.size) return -1
        var n = 0
        for (i in p.indices) if (p[i] != a[i]) n++
        return n
    }

    /**
     * [docs] at [scale] in every way the map can make them, each against the one-band raster without a cache; returns
     * the lines to print.
     */
    private fun allTheSame(name: String, docs: List<SvgDocument>, scale: Float): List<String> {
        val ref = SvgCanvas.premultipliedPixels(raster(docs, scale, 1, null))
        val d = dir()
        val cache = TurbulenceCache(d)
        val lines = ArrayList<String>()
        fun check(label: String, bands: Int, noise: TurbulenceCache?) {
            val stats = EngineFrame.RasterStats()
            val n = differing(ref, raster(docs, scale, bands, noise, stats))
            lines.add("%-22s %-18s %d bytes differ; %d filtered, filters %.0f ms".format(name, label, n, stats.filtered, stats.filterMillis))
            assertEquals("$name, $label: differing pixels", 0, n)
        }
        check("bands", SvgCanvas.DEFAULT_BANDS, null)
        check("bands, cold cache", SvgCanvas.DEFAULT_BANDS, cache)
        check("bands, warm cache", SvgCanvas.DEFAULT_BANDS, cache)
        cache.close()
        check("one band, from disk", 1, TurbulenceCache(d))
        return lines
    }

    @Test
    fun theProbesAreByteForByteTheSame() {
        val dir = File(javaClass.getResource("/svg/probes")!!.toURI())
        val svgs = dir.listFiles { f -> f.name.endsWith(".svg") }!!.sortedBy { it.name }
        assertEquals(12, svgs.size)
        val lines = svgs.flatMap { allTheSame("probe " + it.name.removeSuffix(".svg"), listOf(SvgParser.parse(it)), 2f) }
        lines.forEach(::println)
    }

    private fun westerkerk(moments: List<String>): Map<String, List<SvgDocument>> {
        val raw = File(EngineTestKit.repo, "data/ams/raw.json")
        assumeTrue("the Westerkerk's Overpass extract", raw.isFile)
        Regions.resetPlace()
        val s = Amsterdam.load(raw)
        return moments.associateWith { m ->
            val r = Amsterdam.render(s, m, LinkedHashMap(ViewLadder.livingView()), null, layer = "split")
            (listOf(r.svg) + r.objs!!).map { SvgParser.parse(it) }
        }
    }

    @Test
    fun theWesterkerkByDayNightAndRainIsByteForByteTheSame() {
        val w = westerkerk(listOf("sunny", "night", "wet"))
        val lines = allTheSame("westerkerk sunny", w.getValue("sunny"), 2f) +
            allTheSame("westerkerk sunny ×2.625", w.getValue("sunny"), 2.625f) +
            allTheSame("westerkerk night", w.getValue("night"), 2f) +
            allTheSame("westerkerk wet", w.getValue("wet"), 2f)
        lines.forEach(::println)
    }

    @Test
    fun theMainBoardIsByteForByteTheSame() {
        val oracle = listOf("g", "o", "o2").map { File(EngineTestKit.repo, "cache/oracle/v2_grachten_home_sunny_$it.svg") }
        assumeTrue("Python's Main board (the owner's machine)", oracle.all { it.isFile })
        allTheSame("main board", oracle.map { SvgParser.parse(it) }, 2f).forEach(::println)
    }

    /**
     * The app's wiring: the map's frames keep their noise under [MapEngine.useCacheDir]'s directory, and at the next
     * launch [MapEngine.warmUp] for the screen readies exactly those fields (the warm-up's filter scale is the frames'),
     * so the first frame makes no noise and is the same.
     */
    @Test
    fun theNextLaunchWarmsUpTheNoiseTheLastOneKept() {
        val sunny = westerkerk(listOf("sunny")).getValue("sunny")
        // the frames' scale on a phone of density 2.625, as the app rasterises them (the screen's density, up to the cap)
        val scale = minOf(2.625f, MapEngine.maxRasterScale)
        val ref = SvgCanvas.premultipliedPixels(raster(sunny, scale, 1, null))
        val d = dir()
        MapEngine.useCacheDir(d, warm = false)
        val kept = TurbulenceCache.shared!!
        raster(sunny, scale, SvgCanvas.DEFAULT_BANDS, kept)
        kept.close()
        val screens = TurbulenceCache(File(d, "noise")).kept().filter { it.steps.isEmpty() }
        assertTrue("$screens", screens.size >= 4)
        // the next launch: a new cache over the same files, warmed for the 412 × 915 dp screen at density 2.625
        MapEngine.forgetCacheDir()
        MapEngine.useCacheDir(d, warm = false)
        MapEngine.warmUp(412f, 915f, 2.625f)!!.join()
        val warmed = TurbulenceCache.shared!!
        val s = warmed.stats()
        assertTrue("$s", s.tilesFromDisk > 1000 && s.tilesMade == 0L)
        assertEquals(0, differing(ref, raster(sunny, scale, SvgCanvas.DEFAULT_BANDS, warmed)))
        assertEquals("the first frame makes no noise", 0L, warmed.stats().tilesMade)
        println("warm-up from disk: ${warmed.stats()}")
    }

    /**
     * plan-anywhere's gate for the home's raster: the second frame's filters take at most 30 % of the first's. Both
     * frames run the filters in bands; the first makes the noise, the second reads it. Each is the best of three, after a
     * frame that warms the JIT.
     */
    @Test
    fun theSecondFramesFiltersTakeAtMostAThirdOfTheFirsts() {
        val sunny = westerkerk(listOf("sunny")).getValue("sunny")
        fun millis(since: Long) = (System.nanoTime() - since) / 1e6
        for (scale in floatArrayOf(2f, 2.625f)) {
            raster(sunny, scale, 1, null)
            val plain = EngineFrame.RasterStats()
            var t = System.nanoTime()
            raster(sunny, scale, 1, null, plain)
            val plainAll = millis(t)
            raster(sunny, scale, SvgCanvas.DEFAULT_BANDS, null)
            var first = Double.MAX_VALUE
            var second = Double.MAX_VALUE
            var firstAll = Double.MAX_VALUE
            var secondAll = Double.MAX_VALUE
            repeat(3) {
                val cache = TurbulenceCache(dir())
                val a = EngineFrame.RasterStats()
                t = System.nanoTime()
                raster(sunny, scale, SvgCanvas.DEFAULT_BANDS, cache, a)
                firstAll = minOf(firstAll, millis(t))
                val made = cache.stats().tilesMade
                val b = EngineFrame.RasterStats()
                t = System.nanoTime()
                raster(sunny, scale, SvgCanvas.DEFAULT_BANDS, cache, b)
                secondAll = minOf(secondAll, millis(t))
                assertEquals("the second frame makes no noise", made, cache.stats().tilesMade)
                first = minOf(first, a.filterMillis)
                second = minOf(second, b.filterMillis)
                cache.clear()
            }
            println(
                (
                    "westerkerk sunny ×%s, %d workers: one band without a cache: raster %.0f ms, filters %.0f ms; " +
                        "first frame: raster %.0f ms, filters %.0f ms; second: raster %.0f ms, filters %.0f ms (%.0f %% of the first's)"
                    ).format(scale, SvgCanvas.DEFAULT_BANDS, plainAll, plain.filterMillis, firstAll, first, secondAll, second, 100 * second / first),
            )
            assertTrue("second frame's filters %.0f ms against the first's %.0f ms".format(second, first), second <= 0.30 * first)
        }
    }

    /**
     * The map's frame thread closed while it rasterises: the raster cancelled early and late (by how many times it has
     * asked), anywhere or in a filter band on the workers, stops at once, within the bands under way, and leaves its
     * noise cache sound: the next raster from that cache is byte for byte the one-band raster without one.
     */
    @Test
    fun aRasterCancelledPartWayStopsAtOnceAndLeavesItsCacheSound() {
        val sunny = westerkerk(listOf("sunny")).getValue("sunny")
        val ref = SvgCanvas.premultipliedPixels(raster(sunny, 2f, 1, null))
        val drawing = Thread.currentThread()
        // how many times a whole raster asks, and how many of those in the bands, its noise made into a cache as it goes
        val asks = AtomicInteger()
        val inBands = AtomicInteger()
        val t = System.nanoTime()
        raster(sunny, 2f, SvgCanvas.DEFAULT_BANDS, TurbulenceCache(dir()), cancelled = {
            asks.incrementAndGet()
            if (Thread.currentThread() !== drawing) inBands.incrementAndGet()
            false
        })
        val whole = (System.nanoTime() - t) / 1e6
        assertTrue("asks in the bands: ${inBands.get()}", inBands.get() > 100)
        for (bands in listOf(false, true)) for (share in doubleArrayOf(0.2, 0.7)) {
            val at = ((if (bands) inBands else asks).get() * share).toInt()
            val cache = TurbulenceCache(dir())
            val n = AtomicInteger()
            val stop = AtomicBoolean()
            val since = AtomicLong()
            val thrown = runCatching {
                raster(sunny, 2f, SvgCanvas.DEFAULT_BANDS, cache, cancelled = {
                    if (!stop.get() && (!bands || Thread.currentThread() !== drawing) && n.incrementAndGet() == at) {
                        since.set(System.nanoTime())
                        stop.set(true)
                    }
                    stop.get()
                })
            }.exceptionOrNull()
            val stopped = (System.nanoTime() - since.get()) / 1e6
            val where = if (bands) "ask %d of the bands' %d".format(at, inBands.get()) else "ask %d of %d".format(at, asks.get())
            assertTrue("cancelled at $where: $thrown", thrown is CancellationException)
            val differ = differing(ref, raster(sunny, 2f, SvgCanvas.DEFAULT_BANDS, cache))
            println("westerkerk sunny cancelled at %s: stopped %.1f ms later (a whole raster %.0f ms); the next raster from its cache: %d bytes differ".format(where, stopped, whole, differ))
            assertEquals("the next raster from the cache: differing pixels", 0, differ)
            assertTrue("stopped %.1f ms after the cancel, a whole raster takes %.0f ms".format(stopped, whole), stopped < whole / 4)
            cache.clear()
        }
    }
}
