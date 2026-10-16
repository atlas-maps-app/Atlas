package app.atlas.mapview

import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.PackLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** EngineFrames.close() against races and failures, and the cancellable raster: what a closing map shows, keeps and frees. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EngineFramesRaceTest {
    private val made = Collections.synchronizedList(ArrayList<EngineFrames>())
    private val dirs = ArrayList<File>()

    @After
    fun stop() {
        for (f in made.toList()) {
            f.close()
            assertTrue("a frame thread stopped", f.awaitStopped(60_000))
        }
        EngineTestKit.unwire()
        MapEngine.forgetCacheDir()
        for (d in dirs) d.deleteRecursively()
    }

    private fun dir(): File = Files.createTempDirectory("engine-break").toFile().also { dirs += it }

    private val stages = listOf("choosing the region", "downloading", "unpacking", "loading", "rendering", "parsing", "rasterising")

    /** Whether [f] has reached [stage], gone past it, or finished its frame. */
    private fun reached(f: EngineFrames, stage: String): Boolean =
        stages.indexOf(f.stage) >= stages.indexOf(stage) || (f.stage == "" && f.latest != null)

    /** onChange calls, and the count when close() returned. */
    private class Told {
        val n = AtomicInteger()
        @Volatile
        var atClose = -1
        val late = AtomicInteger()

        fun change() {
            n.incrementAndGet()
            if (atClose >= 0) late.incrementAndGet()
        }
    }

    private fun frames(told: Told, clock: () -> Long = { System.nanoTime() / 1_000_000 }): EngineFrames =
        EngineFrames(fonts = { EngineTestKit.fonts }, onChange = { told.change() }, clock = clock).also { made += it }

    private val view: LiveView = HomeView.westerkerk.let { c -> LiveView(c.target.lat, c.target.lon, c.zoom, c.bearing, c.pitch, 412, 915, 440.0) }

    private fun request(density: Float = 2f, moment: String = "sunny") = EngineFrames.Request(view, Moment.Named(moment), density, MapEngine.options, night = false)

    private fun waitFor(what: String, timeoutMs: Long = 120_000, pollMs: Long = 1, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("no $what in $timeoutMs ms")
            Thread.sleep(pollMs)
        }
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Regions with no pack, whose online() runs [online]: the first thing a frame's stateFor asks. */
    private fun regions(online: () -> Boolean): LiveRegions =
        LiveRegions(PackLibrary(File(dir(), "packs"), emptyList()), Executor { it.run() }, online = online)

    private fun throwables(): List<Throwable> = listOf(IllegalStateException("a broken pack"), OutOfMemoryError("test heap"), StackOverflowError("test stack"))

    private fun expected(e: Throwable) = when (e) {
        is OutOfMemoryError -> MapStatus.NoData("not enough memory for this region")
        is Exception -> MapStatus.NoData("the engine could not draw this place: ${e.message ?: e}")
        else -> MapStatus.NoData("the engine stopped: $e")
    }

    /** An open map's failures: counted, kept, said, and told, exactly as before the change. */
    @Test
    fun failuresOfAnOpenMapAreAsBefore() {
        for (e in throwables()) {
            MapEngine.regions = regions { throw e }
            val told = Told()
            val f = frames(told)
            f.submit(request())
            waitFor("failure") { f.failures == 1 && !f.busy }
            waitFor("idle stage") { f.stage == "" }
            assertSame(e, f.lastError)
            assertEquals(expected(e), f.status)
            assertTrue("told ${told.n.get()}", told.n.get() >= 2)
            // and it keeps making frames afterwards
            MapEngine.regions = null
            f.submit(request(moment = "grey"))
            waitFor("next frame") { f.status == MapStatus.NoData("no map data") && !f.busy }
            assertEquals(1, f.failures)
            f.close()
            assertTrue(f.awaitStopped(10_000))
        }
    }

    /** A failure thrown while the map closes is counted and kept (and logged), but nothing is said or told after close(). */
    @Test
    fun aFailureAsTheMapClosesIsKeptButShowsNothing() {
        for (e in throwables()) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            MapEngine.regions = regions {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
                throw e
            }
            val told = Told()
            val f = frames(told)
            f.submit(request())
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            val stage = f.stage
            f.close()
            told.atClose = told.n.get()
            release.countDown()
            assertTrue(f.awaitStopped(10_000))
            Thread.sleep(1200)
            assertEquals("told after close ($e)", 0, told.late.get())
            assertEquals(1, f.failures)
            assertSame(e, f.lastError)
            assertEquals(MapStatus.Loading(null), f.status)
            assertEquals("choosing the region", stage)
            assertEquals(stage, f.stage)
            assertFalse(f.busy)
        }
    }

    /** An out-of-memory error drops the engine's scenes (LiveEngine.forget()) even as the map closes: the memory is the process's. */
    @Test
    fun anOutOfMemoryAsTheMapClosesStillDropsTheScenes() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        regions.stateFor(view)
        val f0 = frames(Told())
        f0.submit(request())
        waitFor("first frame", 240_000) { f0.latest != null && !f0.busy }
        f0.close()
        assertTrue(f0.awaitStopped(10_000))
        assertEquals("scenes kept after a frame", 1, LiveEngine.sceneCount)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        MapEngine.regions = regions(online = {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            throw OutOfMemoryError("test heap")
        })
        val f = frames(Told())
        f.submit(request())
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        f.close()
        release.countDown()
        assertTrue(f.awaitStopped(10_000))
        assertEquals("scenes after an OOM", 0, LiveEngine.sceneCount)
    }

    /**
     * close() racing submit() and invalidate() from three threads, 300 times, with frames that start at once: no frame
     * starts once close() has returned (at most the one already taken reaches its regions), nothing is told after it.
     */
    @Test
    fun closeRacingRequestsStartsNothingAfterIt() {
        val rnd = Random(17)
        var maxAfter = 0
        var withFrames = 0
        repeat(300) { round ->
            // each frame asks online() exactly three times on an empty library: frame k is calls 3k … 3k + 2
            val calls = ArrayList<Long>()
            MapEngine.regions = regions {
                synchronized(calls) { calls += System.nanoTime() }
                false
            }
            val tick = AtomicLong()
            val told = Told()
            val f = frames(told, clock = { tick.addAndGet(1000) })
            val stop = AtomicBoolean()
            val spam = (0 until 3).map { k ->
                Thread {
                    while (!stop.get()) {
                        when (k) {
                            0 -> f.invalidate()
                            1 -> f.submit(request())
                            else -> f.submit(request(moment = "grey"))
                        }
                        if (rnd.nextInt(4) == 0) Thread.yield()
                    }
                }.apply { start() }
            }
            Thread.sleep(rnd.nextLong(0, 15))
            f.close()
            val closedAt = System.nanoTime()
            told.atClose = told.n.get()
            Thread.sleep(rnd.nextLong(0, 8))
            stop.set(true)
            spam.forEach { it.join() }
            assertTrue(f.awaitStopped(10_000))
            val all = synchronized(calls) { ArrayList(calls) }
            assertEquals("online() calls come in threes (round $round)", 0, all.size % 3)
            val after = (all.indices step 3).count { all[it] > closedAt }
            if (all.isNotEmpty()) withFrames++
            maxAfter = maxOf(maxAfter, after)
            assertTrue("round $round: $after frames reached their regions after close()", after <= 1)
            assertEquals("round $round: told after close()", 0, told.late.get())
            assertFalse(f.busy)
            made.remove(f)
        }
        println("close racing requests: 300 rounds, $withFrames with frames, at most $maxAfter frame(s) reached their regions after close()")
    }

    private fun westerkerkDocs(regions: LiveRegions): Pair<List<SvgDocument>, app.atlas.render.Raster?> {
        // the first ask unpacks the pack (the kit's worker is this thread); the next finds it ready
        var st = regions.stateFor(view)
        if (st !is LiveRegions.State.Ready) st = regions.stateFor(view)
        val region = (st as LiveRegions.State.Ready).region
        val res = LiveEngine.render(region, view, Moment.Named("sunny"), MapEngine.options)!!
        return res.layers.map { SvgParser.parse(it) } to res.raster
    }

    /**
     * A real frame closed at every stage, with the app's shared noise cache (MapEngine.useCacheDir), cold at new
     * scales so tiles are being made when the raster is cancelled: each thread stops, nothing is published or told
     * after close(), nothing fails; afterwards a whole frame from the same cache is byte for byte the exact raster.
     */
    @Test
    fun closingARealFrameAtEveryStageLeavesNothingBehind() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        MapEngine.useCacheDir(dir(), warm = false)
        regions.stateFor(view)
        val f0 = frames(Told())
        f0.submit(request())
        waitFor("first frame", 240_000) { f0.latest != null && !f0.busy }
        f0.invalidate()
        waitFor("second frame", 240_000) { f0.latest!!.number == 2L && !f0.busy }
        val warm = f0.latest!!.timings
        println("westerkerk warm frame: $warm")
        f0.close()
        assertTrue(f0.awaitStopped(10_000))

        class Trial(val label: String, val density: Float, val stage: String?, val after: Long, val cold: Boolean = false)
        val r = warm.raster
        val trials = listOf(
            Trial("at once", 2f, null, 0),
            Trial("choosing", 2f, "choosing the region", 0),
            Trial("loading", 2f, "loading", 200, cold = true),
            Trial("rendering", 2f, "rendering", 0),
            Trial("rendering+", 2f, "rendering", warm.render / 2),
            Trial("parsing", 2f, "parsing", 0),
            Trial("parsing+", 2f, "parsing", maxOf(1, warm.parse / 2)),
            Trial("rasterising", 2f, "rasterising", 0),
            Trial("raster 10%", 2f, "rasterising", r / 10),
            Trial("raster 50%", 2f, "rasterising", r / 2),
            Trial("raster 90%", 2f, "rasterising", r * 9 / 10),
            Trial("raster 120%", 2f, "rasterising", r * 12 / 10),
            Trial("cold ×2.625 5%", 2.625f, "rasterising", r / 20),
            Trial("cold ×2.625 30%", 2.625f, "rasterising", r * 3 / 10),
            Trial("cold ×3 20%", 3f, "rasterising", r / 5),
            Trial("cold ×1.5 60%", 1.5f, "rasterising", r * 6 / 10),
        )
        for (round in 0 until 3) for (t in trials) {
            if (round > 0 && t.density != 2f) continue
            if (t.cold) LiveEngine.forget()
            val told = Told()
            val f = frames(told)
            f.submit(request(t.density))
            // a stage may pass in microseconds (choosing the region, a warm parse): wait until the frame has reached it, gone
            // past it or finished, and close there; the line printed below names the stage the close met
            if (t.stage != null) waitFor("${t.label}: stage ${t.stage}", 240_000) { reached(f, t.stage) }
            if (t.after > 0) Thread.sleep(t.after)
            val stageThen = f.stage
            val c0 = System.nanoTime()
            f.close()
            val closing = ms(c0)
            val where = Thread.getAllStackTraces().entries.filter { it.key.name == "atlas-engine" && it.key.isAlive }
                .joinToString(" | ") { e -> e.value.take(40).filter { it.className.startsWith("app.atlas") }.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } }
            told.atClose = told.n.get()
            val latest = f.latest
            val status = f.status
            val frame = f.frame
            val stage = f.stage
            assertTrue("${t.label}: stopped", f.awaitStopped(60_000))
            val stopped = ms(c0)
            Thread.sleep(1100)
            println("close at ${t.label} (stage '$stageThen'): close() ${closing} ms, thread ended ${stopped} ms after; shown ${latest != null}, status $status; at close: $where")
            assertTrue("${t.label}: close() took $closing ms", closing < 100)
            assertEquals("${t.label}: told after close()", 0, told.late.get())
            assertSame(latest, f.latest)
            assertEquals(status, f.status)
            assertSame(frame, f.frame)
            assertEquals(stage, f.stage)
            assertEquals("${t.label}: failures (${f.lastError})", 0, f.failures)
            assertNull(f.lastError)
            assertFalse(f.busy)
            latest?.let { assertFalse("a shown frame's bitmap is never recycled", it.image.isRecycled) }
            made.remove(f)
        }

        // the shared cache after all those cancels, at every scale they used: a whole frame is the exact raster
        val (docs, raster) = westerkerkDocs(regions)
        for (scale in listOf(2f, 2.625f, 3f, 1.5f)) {
            val ref = SvgCanvas.premultipliedPixels(EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, bands = 1, noise = null))
            val f = frames(Told())
            f.submit(request(scale))
            waitFor("whole frame ×$scale", 240_000) { f.latest != null && !f.busy }
            val got = SvgCanvas.premultipliedPixels(f.latest!!.image)
            var differ = 0
            for (i in got.indices) if (got[i] != ref[i]) differ++
            println("whole frame ×$scale from the cache after the cancels: $differ of ${got.size} pixels differ from the exact raster")
            assertEquals("×$scale differing pixels", 0, differ)
            assertEquals(ref.size, got.size)
        }
    }

    /**
     * Two rasters at once from one cold cache, as two maps do: one cancelled part-way, the other left alone. The one left
     * alone is the exact raster (a tile the cancelled one began is made whole for it), and so is the next from the cache.
     */
    @Test
    fun twoRastersShareACacheAndOneIsCancelled() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        val (docs, raster) = westerkerkDocs(regions)
        val scale = 2.625f
        val ref = SvgCanvas.premultipliedPixels(EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, bands = 1, noise = null))
        val asks = AtomicInteger()
        EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, noise = TurbulenceCache(dir()), cancelled = { asks.incrementAndGet(); false })
        println("a whole raster ×$scale asks ${asks.get()} times")
        val pool = Executors.newFixedThreadPool(2)
        try {
            for (share in doubleArrayOf(0.05, 0.2, 0.45, 0.8)) {
                val cache = TurbulenceCache(dir())
                val n = AtomicInteger()
                val at = (asks.get() * share).toInt()
                val a = pool.submit<Throwable?> {
                    runCatching {
                        EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, noise = cache, cancelled = { n.incrementAndGet() >= at })
                    }.exceptionOrNull()
                }
                val b = pool.submit<IntArray> { SvgCanvas.premultipliedPixels(EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, noise = cache)) }
                val thrown = a.get(5, TimeUnit.MINUTES)
                val got = b.get(5, TimeUnit.MINUTES)
                assertTrue("cancelled at $at: $thrown", thrown is CancellationException)
                var differ = 0
                for (i in got.indices) if (got[i] != ref[i]) differ++
                val next = SvgCanvas.premultipliedPixels(EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, scale, SvgCanvas.FilterMode.CPU, noise = cache))
                var differNext = 0
                for (i in next.indices) if (next[i] != ref[i]) differNext++
                println("two rasters, one cancelled at ask $at of ${asks.get()}: the other $differ pixels differ, the next from the cache $differNext; tiles ${cache.stats()}")
                assertEquals(0, differ)
                assertEquals(0, differNext)
                cache.clear()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The app's threads: onChange posts to a "main" thread, which reads busy, status and latest (busy takes the lock)
     * and asks for frames, and closes the map there while the engine thread is in each stage. Never a deadlock.
     */
    @Test
    fun closingOnTheMainThreadWhileTheEngineThreadPostsToItNeverDeadlocks() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        regions.stateFor(view)
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            for ((k, stage) in listOf("loading", "rendering", "parsing", "rasterising", "rasterising").withIndex()) {
                if (stage == "loading") LiveEngine.forget()
                val reads = AtomicInteger()
                lateinit var f: EngineFrames
                f = EngineFrames(fonts = { EngineTestKit.fonts }, onChange = {
                    main.execute {
                        reads.incrementAndGet()
                        f.busy
                        f.status
                        f.latest
                        if (reads.get() % 3 == 0) f.invalidate()
                    }
                }).also { made += it }
                f.submit(request())
                waitFor("stage $stage", 240_000) { f.stage == stage }
                if (k == 4) Thread.sleep(300)
                val done = main.submit { f.close() }
                done.get(10, TimeUnit.SECONDS)
                assertTrue(f.awaitStopped(60_000))
                main.submit { f.busy }.get(10, TimeUnit.SECONDS)
                println("closed on the fake main thread at $stage: ${reads.get()} posts read")
            }
        } finally {
            main.shutdownNow()
        }
    }

    /** What the checks cost: a whole raster's asks, against what one ask of the map's own kind (a volatile read) costs. */
    @Test
    fun theChecksCostNothingMeasurable() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        val (docs, raster) = westerkerkDocs(regions)
        val cache = TurbulenceCache(dir())
        EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, 2f, SvgCanvas.FilterMode.CPU, noise = cache)
        val asks = AtomicInteger()
        val t0 = System.nanoTime()
        EngineFrame.rasterise(docs, raster, EngineTestKit.fonts, 2f, SvgCanvas.FilterMode.CPU, noise = cache, cancelled = { asks.incrementAndGet(); false })
        val whole = ms(t0)
        // the map's own check: a lambda reading a volatile field, through stopIf's call
        val flag = VolatileFlag()
        val check: () -> Boolean = { flag.closed }
        var sink = 0
        repeat(5) {
            for (i in 0 until 2_000_000) if (check()) sink++
        }
        val n = 20_000_000
        val c0 = System.nanoTime()
        for (i in 0 until n) if (check()) sink++
        val perAsk = (System.nanoTime() - c0).toDouble() / n
        println("checks: a warm raster asks ${asks.get()} times in $whole ms; one ask ${"%.2f".format(perAsk)} ns, so ${"%.3f".format(asks.get() * perAsk / 1e6)} ms a raster ($sink)")
        // a time on a shared machine is no measurement: gated only on a quiet one, asked for with ATLAS_TIMED=1
        if (System.getenv("ATLAS_TIMED") == "1") assertTrue(asks.get() * perAsk / 1e6 < whole * 0.001)
    }

    private class VolatileFlag {
        @Volatile
        var closed = false
    }
}
