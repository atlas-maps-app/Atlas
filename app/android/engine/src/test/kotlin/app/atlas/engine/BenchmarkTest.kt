package app.atlas.engine

import app.atlas.geo.LatLon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scene build and frame times on the real fixtures. */
class BenchmarkTest {
    @Test
    fun frameTime() {
        val camera = Camera(LatLon(52.3746, 4.8826), 16.3, -20.0, 50.0, 824, 1830, 2f)
        val cover = TileCover.cover(camera)
        val scenes = cover.mapNotNull { id -> SceneCache.get(id, Look.DAY, TestKit.amsterdamTrees)?.let { id to it } }.toMap()
        val plan = TileCover.plan(cover) { scenes[it] }
        val r = Renderer(ApproximateMeasurer)
        var f = r.frame(camera, plan, 0)
        repeat(150) { f = r.frame(camera, plan, it * 16L) }
        val times = LongArray(100)
        for (i in times.indices) {
            val t0 = System.nanoTime()
            f = r.frame(camera, plan, 3000L + i * 16)
            times[i] = System.nanoTime() - t0
        }
        times.sort()
        println("steady frame: median ${"%.2f".format(times[50] / 1e6)} ms, p90 ${"%.2f".format(times[90] / 1e6)} ms; ${f.stats}")
        // The Main board's view: a quarter of a million vertices, the order kept while the camera holds still. (Times
        // are printed, not asserted: this machine is shared.)
        assertTrue(f.stats.vertices > 150_000)
        assertTrue(f.stats.objectsDrawn > 3000 && f.stats.treesDrawn > 100)
        assertTrue(f.stats.sortReused)
        assertTrue(f.batches.all { (it as Batch.Triangles).vertexCount <= 65_535 })
        // Moving camera: the order is recomputed every frame.
        val moving = LongArray(60)
        for (i in moving.indices) {
            val c = camera.copy(bearing = -20.0 + i * 0.7)
            val t0 = System.nanoTime()
            f = r.frame(c, plan, 6000L + i * 16)
            moving[i] = System.nanoTime() - t0
        }
        moving.sort()
        println("moving frame: median ${"%.2f".format(moving[30] / 1e6)} ms; ${f.stats}")
        assertFalse(f.stats.sortReused)
        assertTrue(f.stats.objectsDrawn > 1000)
    }
}
