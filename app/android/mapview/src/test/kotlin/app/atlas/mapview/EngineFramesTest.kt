package app.atlas.mapview

import app.atlas.render.live.LiveRegions
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.PackLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * [EngineFrames.close]: at once and without a word after it, idle, twice, with a request waiting or asked for after
 * it, and in the middle of a real frame of the app's bundled Westerkerk, whose raster stops within its filter bands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EngineFramesTest {
    private val made = ArrayList<EngineFrames>()
    private val dirs = ArrayList<File>()

    /** Calls of the frames' onChange. */
    private val told = AtomicInteger()

    @After
    fun stop() {
        for (f in made) {
            f.close()
            assertTrue("the frame thread stopped", f.awaitStopped(STOP_MS))
        }
        EngineTestKit.unwire()
        for (d in dirs) d.deleteRecursively()
    }

    private fun frames(clock: () -> Long = { System.nanoTime() / 1_000_000 }): EngineFrames =
        EngineFrames(fonts = { EngineTestKit.fonts }, onChange = { told.incrementAndGet() }, clock = clock).also { made += it }

    /** The Westerkerk's living map on the boards' 412 × 915 dp screen at density 2. */
    private val view: LiveView = HomeView.westerkerk.let { c -> LiveView(c.target.lat, c.target.lon, c.zoom, c.bearing, c.pitch, 412, 915, 440.0) }

    private fun request() = EngineFrames.Request(view, Moment.Named("sunny"), 2f, MapEngine.options, night = false)

    /** Regions with no pack at all whose every [LiveRegions.stateFor] counts in [asked]: a frame that starts asks it first. */
    private fun probe(asked: AtomicInteger): LiveRegions {
        val dir = Files.createTempDirectory("engine-frames").toFile().also { dirs += it }
        return LiveRegions(PackLibrary(File(dir, "packs"), emptyList()), Executor { it.run() }, online = { asked.incrementAndGet(); false })
    }

    private fun waitFor(what: String, timeoutMs: Long = 120_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("no $what in $timeoutMs ms")
            Thread.sleep(5)
        }
    }

    private fun millisSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test
    fun closingWhileIdleStopsBothThreadsAtOnce() {
        val f = frames()
        val t0 = System.nanoTime()
        f.close()
        val closing = millisSince(t0)
        assertTrue(f.awaitStopped(STOP_MS))
        val stopped = millisSince(t0)
        println("idle: close() took $closing ms, the threads ended after $stopped ms")
        assertTrue("close() took $closing ms", closing < 100)
        // the ticker sleeps a second at a time: it is woken, not waited for
        assertTrue("the threads ended after $stopped ms", stopped < 500)
        assertFalse(f.busy)
        assertEquals(0, told.get())
    }

    @Test
    fun closingTwiceIsClosingOnce() {
        MapEngine.regions = null
        val f = frames()
        // a frame made first: with no map data it is a status, at once
        f.submit(request())
        waitFor("status") { f.status is MapStatus.NoData }
        waitFor("idle frame thread", 10_000) { !f.busy }
        f.close()
        val after = told.get()
        f.close()
        assertTrue(f.awaitStopped(STOP_MS))
        f.close()
        assertTrue(f.awaitStopped(0))
        assertEquals(MapStatus.NoData("no map data"), f.status)
        assertEquals(after, told.get())
    }

    @Test
    fun aFrameAskedForAfterCloseNeverStarts() {
        val asked = AtomicInteger()
        MapEngine.regions = probe(asked)
        val f = frames()
        f.close()
        f.submit(request())
        f.invalidate()
        assertTrue(f.awaitStopped(STOP_MS))
        assertEquals("regions asked", 0, asked.get())
        assertFalse(f.busy)
        assertEquals(MapStatus.Loading(null), f.status)
        assertEquals("", f.stage)
        assertEquals(0, told.get())
    }

    @Test
    fun aRequestWaitingWhenClosedNeverStarts() {
        val asked = AtomicInteger()
        MapEngine.regions = probe(asked)
        // a clock that stands still: the camera never looks still long enough, so the request waits until the close
        val f = frames(clock = { 1_000_000L })
        f.submit(request())
        Thread.sleep(3 * EngineFrames.QUIET_MS)
        assertTrue(f.busy)
        f.close()
        assertFalse("the waiting request is dropped", f.busy)
        assertTrue(f.awaitStopped(STOP_MS))
        assertEquals("regions asked", 0, asked.get())
        assertEquals(MapStatus.Loading(null), f.status)
        assertEquals(0, told.get())
    }

    /**
     * Real frames of the app's bundled Westerkerk (load, render, parse, a raster of seconds with its noise made as it
     * goes), then the same frame once more, closed while it is rasterised: its thread ends at once (within its filter
     * bands under way), the frame before stays the newest, nothing is told after the close, and nothing failed.
     */
    @Test
    fun closingDuringARasterStopsItWithinItsBandsAndShowsNothing() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        val regions = EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
        // the noise is made in the frame, as on a first launch: the longest raster
        MapEngine.forgetCacheDir()
        // the pack unpacked here (the kit's worker is the calling thread), so the frame starts on the region
        regions.stateFor(view)
        val f = frames()
        f.submit(request())
        waitFor("first frame", 240_000) { f.latest != null }
        waitFor("idle frame thread", 10_000) { !f.busy }
        val cold = f.latest!!.timings
        // the same frame again, warm: how long a whole raster takes
        f.invalidate()
        waitFor("second frame", 120_000) { f.latest!!.number == 2L && !f.busy }
        val first = f.latest!!
        val raster = first.timings.raster
        f.invalidate()
        waitFor("raster") { f.stage == "rasterising" }
        // into its filters
        Thread.sleep((raster / 3).coerceIn(20, 1000))
        assertEquals("still rasterising", "rasterising", f.stage)
        val t0 = System.nanoTime()
        f.close()
        val closing = millisSince(t0)
        val after = told.get()
        assertTrue("the frame thread stopped", f.awaitStopped(STOP_MS))
        val stopped = millisSince(t0)
        // a tick of the ticker, had it outlived the close
        Thread.sleep(1200)
        println("westerkerk: frames $cold, then warm ${first.timings}; closed while rasterising: close() took $closing ms, the thread ended after $stopped ms")
        assertEquals("told after the close", after, told.get())
        assertSame("the frame before is still the newest", first, f.latest)
        assertEquals(MapStatus.Ready, f.status)
        assertEquals("failures", 0, f.failures)
        assertFalse(f.busy)
        assertTrue("close() took $closing ms", closing < 100)
        // a raster left to run on would take about two thirds of a whole raster more; a stopped one waits only for the
        // bands under way, whose noise (no cache here) is one call each: a third leaves room for a loaded machine
        assertTrue("the thread ended $stopped ms after the close, against $raster ms for a whole raster", stopped < raster / 3)
    }

    private companion object {
        /** Long enough for any thread to end once closed. */
        const val STOP_MS = 30_000L
    }
}
