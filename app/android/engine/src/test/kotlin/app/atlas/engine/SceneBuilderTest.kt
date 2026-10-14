package app.atlas.engine

import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Scenes from the real OpenFreeMap tiles around the Westerkerk. */
class SceneBuilderTest {
    @Test
    fun centreTileAtZoom14() {
        val vt = TestKit.tile(TestKit.centre)
        // Warm up once, then time a build.
        val trees = TestKit.amsterdamTrees
        SceneBuilder.build(vt, TestKit.centre, Look.DAY, trees)
        val t0 = System.nanoTime()
        val s = SceneBuilder.build(vt, TestKit.centre, Look.DAY, trees)
        val ms = (System.nanoTime() - t0) / 1e6
        println("z14 centre scene: ${s.stats} (timed ${"%.0f".format(ms)} ms)")
        assertTrue(s.stats.buildings > 6000)
        val gabled = s.buildings.count { it.roof == RoofShape.GABLED }
        assertTrue("gabled $gabled of ${s.buildings.size}", gabled > s.buildings.size / 2)
        assertTrue(s.ground.vertexCount > 10_000)
        assertTrue(s.objects.mesh.vertexCount > 100_000)
        assertTrue(s.shadows.vertexCount > 10_000)
        // Every vertex of the ground lies on the tile's square.
        for (v in 0 until s.ground.vertexCount) {
            assertTrue(s.ground.x(v) >= -1e-3f && s.ground.x(v) <= s.size + 1e-3f)
            assertTrue(s.ground.y(v) >= -1e-3f && s.ground.y(v) <= s.size + 1e-3f)
        }
        // Water lies below the quays.
        assertTrue((0 until s.ground.vertexCount).any { abs(s.ground.z(it) - GroundBuilder.WATER_Z) < 1e-4f })
        val names = s.labels.map { it.text }.toSet()
        assertTrue("Jordaan" in names)
        assertTrue("Prinsengracht" in names)
        assertTrue(s.labels.filter { it.text == "Jordaan" }.all { it.style == LabelStyle.SERIF_ITALIC })
        // z14 scenes carry few points of interest; real trees come from the extras.
        assertTrue(s.trees.count > 100)
        assertTrue(s.trees.mapped.count { it } > 50)
        // Its size, for a cache's budget: the object mesh alone is 300k vertices of 16 bytes and more.
        println("z14 centre scene holds ${s.bytes / 1024} KiB")
        assertTrue(s.bytes > s.objects.mesh.vertexCount * 16L && s.bytes < 64L * 1024 * 1024)
    }

    @Test
    fun overzoomedChildrenShareTheBuildingsOnce() {
        val parent = TestKit.centre
        val vt = TestKit.tile(parent)
        val whole = SceneBuilder.build(vt, parent, Look.DAY)
        var sum = 0
        for (c in parent.children()) {
            val s = SceneBuilder.build(vt, c, Look.DAY, source = parent)
            sum += s.stats.buildings
            assertEquals(c, s.id)
            assertEquals(parent, s.source)
            assertTrue(s.size > 700f && s.size < 800f)
        }
        // Each building belongs to the one child holding its centroid; buildings the tile itself cut are cut again
        // at the children's edges, so a few count twice.
        assertTrue("children $sum vs parent ${whole.stats.buildings}", abs(sum - whole.stats.buildings) < whole.stats.buildings * 0.02)
    }

    @Test
    fun detailGrowsWithZoom() {
        val parent = TestKit.centre
        val vt = TestKit.tile(parent)
        val z16 = TileId(16, parent.x * 4 + 1, parent.y * 4 + 1)
        val z18 = TileId(18, z16.x * 4 + 1, z16.y * 4 + 1)
        val s16 = SceneBuilder.build(vt, z16, Look.DAY, source = parent)
        val s18 = SceneBuilder.build(vt, z18, Look.DAY, source = parent)
        val per16 = s16.objects.mesh.vertexCount.toDouble() / s16.stats.buildings
        val per18 = s18.objects.mesh.vertexCount.toDouble() / s18.stats.buildings
        assertTrue("vertices per building $per16 at z16, $per18 at z18", per18 > per16 * 1.5)
        // House numbers only close up.
        assertTrue(s18.labels.any { it.kind == LabelKind.NUMBER })
        assertTrue(s16.labels.none { it.kind == LabelKind.NUMBER })
    }

    @Test
    fun nightLightsWindows() {
        val z16 = TileId(16, TestKit.centre.x * 4 + 1, TestKit.centre.y * 4 + 1)
        val day = SceneBuilder.build(TestKit.tile(TestKit.centre), z16, Look.DAY, source = TestKit.centre)
        val night = SceneBuilder.build(TestKit.tile(TestKit.centre), z16, Look.NIGHT, source = TestKit.centre)
        val lit = Theme.NIGHT.windowLit.toSet()
        assertTrue(night.objects.mesh.colours.count { it in lit } > 1000)
        assertTrue(day.objects.mesh.colours.none { it in lit })
        assertEquals(0, night.shadows.vertexCount)
    }

    @Test
    fun lowZoomScenesDrawFlatFootprints() {
        val z13 = TileId(13, 4207, 2692)
        val s = SceneBuilder.build(TestKit.tile(z13), z13, Look.DAY)
        assertEquals(0, s.stats.buildings)
        assertTrue(s.ground.colours.count { it == Theme.DAY.footprint } > 1000)
    }

    @Test
    fun chartWaterShoalsAtTheShore() {
        val s = SceneBuilder.build(TestKit.tile(TestKit.centre), TestKit.centre, Look.CHART)
        val shallow = Theme.CHART.waterShallow!!
        assertTrue(s.ground.colours.count { it == shallow } > 100)
        // Chart buildings wear the chart palette.
        assertTrue(s.buildings.all { it.wallColour in Theme.CHART.wallPalette!! })
    }
}
