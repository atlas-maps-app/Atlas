package app.atlas.mapview

import app.atlas.engine.Look
import app.atlas.engine.SceneBuilder
import app.atlas.geo.TileId
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileSource
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** The pipeline step by step on a dispatcher the test drives: what a change of source does to work in flight. */
class PipelineSourceTest {
    /** Runs what is dispatched to it only when asked, one task at a time, on the test's thread. */
    private class Steps : Executor {
        private val tasks = ArrayDeque<Runnable>()

        @Synchronized
        override fun execute(command: Runnable) { tasks.addLast(command) }

        fun one(): Boolean {
            val r = synchronized(this) { tasks.removeFirstOrNull() } ?: return false
            r.run()
            return true
        }

        fun all() { while (one()) Unit }
    }

    private val id = TileId(14, 8414, 5384)
    private val neighbour = TileId(14, 8413, 5383)

    private fun bytes(t: TileId) = File(Fixtures.testdata, "omt/${t.z}/${t.x}/${t.y}.pbf").readBytes()

    /** A source that answers every tile with [t]'s data. */
    private fun always(t: TileId) = object : TileSource {
        override val minZoom = 0
        override val maxZoom = 14
        override val attribution = ""
        override suspend fun tile(id: TileId): ByteArray = bytes(t)
    }

    @Test
    fun aDecodeOfTheOldSourceNeverServesTheNewOne() {
        val steps = Steps()
        val dispatcher = steps.asCoroutineDispatcher()
        val pipeline = ScenePipeline(SceneCache(1L shl 30), onChange = {}, builders = dispatcher, io = dispatcher)
        try {
            pipeline.setSource(always(id))
            pipeline.want(listOf(id), Look.DAY, "day")
            // The load fetches the tile and starts decoding it, then waits for the decode.
            steps.one()
            // The source changes while that decode is still to run.
            pipeline.setSource(always(neighbour))
            steps.all()
            pipeline.want(listOf(id), Look.DAY, "day")
            steps.all()
            val scene = pipeline.cache.get(id, "day")
            assertNotNull(scene)
            val fromNew = SceneBuilder.build(Mvt.decode(bytes(neighbour)), id, Look.DAY)
            val fromOld = SceneBuilder.build(Mvt.decode(bytes(id)), id, Look.DAY)
            assertNotEquals("the two tiles differ", fromOld.buildings.size, fromNew.buildings.size)
            assertEquals("built from the new source's data", fromNew.buildings.size, scene!!.buildings.size)
        } finally {
            pipeline.close()
        }
    }
}
