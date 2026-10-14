package app.atlas.mapview

import app.atlas.engine.ApproximateMeasurer
import app.atlas.engine.Look
import app.atlas.engine.SceneBuilder
import app.atlas.engine.TileCover
import app.atlas.engine.TileScene
import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileSource
import kotlinx.coroutines.runInterruptible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** The tile pipeline on the real fixtures: the scene cache, loads and cancellation, and the frame loop. */
class PipelineTest {
    private val phone = Viewport(824, 1830, 2f)

    private fun scene(id: TileId, look: Look = Look.DAY): TileScene =
        SceneBuilder.build(Mvt.decode(File(Fixtures.testdata, "omt/${id.z}/${id.x}/${id.y}.pbf").readBytes()), id, look)

    private fun waitFor(timeoutMs: Long = 60_000, what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun cacheEvictsTheLeastRecentlyUsedWithinItsBudget() {
        val a = scene(TileId(10, 525, 336))
        val b = scene(TileId(12, 2103, 1346))
        val c = scene(TileId(13, 4207, 2692))
        val cache = SceneCache(a.bytes + b.bytes + c.bytes - 1)
        cache.put(SceneCache.Key(a.id, "day"), a)
        cache.put(SceneCache.Key(b.id, "day"), b)
        assertNotNull(cache.get(a.id, "day")) // a is now the most recent
        cache.put(SceneCache.Key(c.id, "day"), c)
        assertNull("b was the least recently used", cache.get(b.id, "day"))
        assertNotNull(cache.get(a.id, "day"))
        assertTrue(cache.bytes <= cache.budgetBytes)
    }

    @Test
    fun pinnedScenesStayOverBudget() {
        val a = scene(TileId(10, 525, 336))
        val b = scene(TileId(12, 2103, 1346))
        val cache = SceneCache(1)
        cache.pinTiles(listOf(a.id, b.id), "day")
        cache.put(SceneCache.Key(a.id, "day"), a)
        cache.put(SceneCache.Key(b.id, "day"), b)
        assertEquals(2, cache.size)
        cache.pinTiles(listOf(b.id), "day")
        assertEquals(1, cache.size)
        assertSame(b, cache.get(b.id, "day"))
    }

    @Test
    fun anotherLookStandsInUntilItsOwnIsBuilt() {
        val a = scene(TileId(10, 525, 336))
        val cache = SceneCache(1L shl 30)
        cache.put(SceneCache.Key(a.id, "day"), a)
        assertNull(cache.get(a.id, "night"))
        assertSame(a, cache.any(a.id))
    }

    @Test
    fun pipelineBuildsTheCoverAndNothingTwice() {
        val changes = AtomicInteger()
        val tiles = FixtureTiles()
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = { changes.incrementAndGet() })
        try {
            pipeline.setSource(tiles)
            val cover = TileCover.cover(CameraOps.camera(CameraPosition(Fixtures.westermarkt, 15.0), phone))
            assertTrue(cover.size >= 4)
            assertTrue(pipeline.want(cover, Look.DAY, "day") > 0)
            waitFor(what = "the cover's scenes") { pipeline.pending == 0 }
            for (id in cover) assertNotNull("$id", pipeline.cache.get(id, "day"))
            assertEquals(cover.size, pipeline.built)
            // onChange runs just after a job leaves the list, so the last one may land a moment after pending reads 0.
            waitFor(what = "a change per scene") { changes.get() >= cover.size }
            val reads = tiles.reads
            assertEquals(0, pipeline.want(cover, Look.DAY, "day"))
            assertEquals(reads, tiles.reads)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun tilesThatLeaveTheViewAreCancelled() {
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = {}, builders = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
        try {
            pipeline.setSource(FixtureTiles())
            val near = TileCover.cover(CameraOps.camera(CameraPosition(Fixtures.westermarkt, 16.5, 0.0, 60.0), phone))
            pipeline.want(near, Look.DAY, "day")
            // The view jumps to the zoomed-out map: the close tiles are not wanted any more.
            val far = listOf(TileId(10, 525, 336))
            pipeline.want(far, Look.DAY, "day")
            waitFor(what = "the far tile") { pipeline.pending == 0 }
            assertNotNull(pipeline.cache.get(far[0], "day"))
            val built = near.count { pipeline.cache.get(it, "day") != null }
            assertTrue("$built of ${near.size} close tiles were built anyway", built < near.size)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun aTileWithNoDataIsNotAskedForAgainAtOnce() {
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = {})
        try {
            pipeline.setSource(FixtureTiles())
            // The Sahara: no fixture tile, nor any ancestor within reach.
            val nowhere = listOf(TileId(14, 8500, 7000))
            pipeline.want(nowhere, Look.DAY, "day")
            waitFor(what = "the empty load") { pipeline.pending == 0 }
            assertNull(pipeline.cache.get(nowhere[0], "day"))
            assertEquals(0, pipeline.want(nowhere, Look.DAY, "day"))
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun aNewSourceDropsTheOld() {
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = {})
        try {
            val tiles = FixtureTiles()
            pipeline.setSource(tiles)
            pipeline.want(listOf(TileId(10, 525, 336)), Look.DAY, "day")
            waitFor(what = "the scene") { pipeline.pending == 0 }
            pipeline.setSource(tiles)
            assertEquals(1, pipeline.cache.size)
            pipeline.setSource(null)
            assertEquals(0, pipeline.cache.size)
            assertEquals(0, pipeline.want(listOf(TileId(10, 525, 336)), Look.DAY, "day"))
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun frameLoopMakesFramesAndWaitsForThemToBeDrawn() {
        val frames = AtomicInteger()
        lateinit var loop: FrameLoop
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = { loop.invalidate() })
        loop = FrameLoop(ApproximateMeasurer, pipeline, onFrame = { frames.incrementAndGet() })
        try {
            pipeline.setSource(FixtureTiles())
            val position = CameraPosition(Fixtures.westermarkt, 15.0, 0.0, 30.0)
            val look = Looks.resolve(MapStyle(theme = Theme.DAY), Fixtures.afternoon, position.target)
            loop.submit(RenderInput(CameraOps.camera(position, phone), look, Looks.frameStyle(MapStyle(), position.zoom)))
            waitFor(what = "the first frame") { loop.latest != null }
            val first = loop.latest!!
            assertEquals(1L, first.number)
            // More scenes arrive, but nothing new is made until the view drew the first frame.
            Thread.sleep(300)
            assertEquals(1L, loop.latest!!.number)
            // Draw every frame as it comes until the picture settles.
            waitFor(what = "the settled picture") {
                val f = loop.latest!!
                loop.drawn(f.number)
                pipeline.pending == 0 && !loop.busy && !f.fading && f.loading == 0
            }
            val f = loop.latest!!
            assertTrue("frames ${f.number}", f.number > 1)
            assertTrue(f.frame.stats.triangles > 1000)
            assertEquals(f.wanted, f.drawnTiles)
            assertFalse(f.fading)
            // onFrame runs just after the frame is published, so the last call may land a moment later.
            waitFor(what = "an onFrame per frame") { frames.get() >= f.number.toInt() }
        } finally {
            loop.close()
            pipeline.close()
        }
    }

    @Test
    fun parentsStandInWhileChildrenLoad() {
        val gate = CountDownLatch(1)
        val fixture = FixtureTiles()
        // The finest data (z14) waits at the gate; everything coarser comes at once.
        val gated = object : TileSource {
            override val minZoom = 0
            override val maxZoom = 14
            override val attribution = ""
            override suspend fun tile(id: TileId): ByteArray? {
                if (id.z >= 14) runInterruptible { gate.await() }
                return fixture.tile(id)
            }
        }
        lateinit var loop: FrameLoop
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = { loop.invalidate() })
        loop = FrameLoop(ApproximateMeasurer, pipeline, onFrame = {})
        fun drawUntil(what: String, ok: (DrawnFrame) -> Boolean): DrawnFrame {
            var result: DrawnFrame? = null
            waitFor(what = what) {
                val f = loop.latest ?: return@waitFor false
                loop.drawn(f.number)
                ok(f).also { if (it) result = f }
            }
            return result!!
        }
        try {
            pipeline.setSource(gated)
            val look = Looks.resolve(MapStyle(theme = Theme.DAY), Fixtures.afternoon, Fixtures.westermarkt)
            fun input(p: CameraPosition) = RenderInput(CameraOps.camera(p, phone), look, Looks.frameStyle(MapStyle(), p.zoom))
            // Zoomed out first: the coarse scenes get built.
            loop.submit(input(CameraPosition(Fixtures.westermarkt, 12.6)))
            drawUntil("the zoomed-out map") { pipeline.pending == 0 && !loop.busy && it.loading == 0 && !it.fading }
            // Zoomed in: the close tiles wait for their data, and the coarse scenes stand in for them.
            loop.submit(input(CameraPosition(Fixtures.westermarkt, 15.2)))
            val waiting = drawUntil("a frame of the zoomed-in camera") { it.camera.zoom == 15.2 }
            assertTrue(waiting.loading > 0)
            assertTrue("drew ${waiting.drawnTiles} stand-ins", waiting.drawnTiles > 0)
            assertTrue(waiting.frame.stats.triangles > 1000)
            gate.countDown()
            val done = drawUntil("the zoomed-in map") { pipeline.pending == 0 && !loop.busy && it.loading == 0 && !it.fading }
            assertEquals(done.wanted, done.drawnTiles)
        } finally {
            gate.countDown()
            loop.close()
            pipeline.close()
        }
    }

    @Test
    fun looksFollowTheSunAndTheLayers() {
        val day = Looks.resolve(MapStyle(), Fixtures.afternoon, Fixtures.westermarkt)
        assertFalse(day.night)
        assertEquals(214.4, day.look.light.azimuth, 1.5)
        assertEquals(38.4, day.look.light.elevation, 1.5)
        val night = Looks.resolve(MapStyle(), Fixtures.night, Fixtures.westermarkt)
        assertTrue(night.night)
        assertTrue(night.key != day.key)
        val forcedDay = Looks.resolve(MapStyle(theme = Theme.DAY), Fixtures.night, Fixtures.westermarkt)
        assertFalse(forcedDay.night)
        assertTrue("daylight never goes flat", forcedDay.look.light.elevation >= 8.0)
        val noShadows = Looks.resolve(MapStyle(layers = MapStyle().layers - MapLayer.SHADOWS), Fixtures.afternoon, Fixtures.westermarkt)
        assertEquals(0f, noShadows.look.theme.shadowOpacity, 0f)
        assertTrue(noShadows.key != day.key)
        // Ten minutes and a few streets later the scenes are the same.
        val later = Looks.resolve(MapStyle(), Fixtures.afternoon.plusSeconds(120), LatLon(52.36, 4.90))
        assertEquals(day.key, later.key)
        val chart = Looks.resolve(MapStyle(theme = Theme.CHART), Instant.EPOCH, Fixtures.westermarkt)
        assertTrue(chart.look.theme.chart)
    }

    @Test
    fun frameStylesFollowTheLayers() {
        val s = Looks.frameStyle(MapStyle(layers = setOf(MapLayer.LABELS)), 16.0)
        assertTrue(s.labels)
        assertFalse(s.pois)
        assertNull(s.calm)
        val calm = Looks.frameStyle(MapStyle(calm = true), 16.0)
        assertTrue(calm.calm!! > 0.1f)
        assertEquals(0f, Looks.calmFor(18.5), 0f)
    }
}
