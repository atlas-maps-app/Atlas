package app.atlas.engine

import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Scenes from hand-made tiles, for cases the fixtures do not hold. */
class SyntheticTileTest {
    private val id = TileId(14, 8414, 5384)

    @Test
    fun chartWaterAlongALongCoastStaysQuick() {
        // A sea polygon with a ragged 3,000-vertex coast across the whole tile, as coastal tiles have.
        val n = 3000
        val ring = IntArray(2 * (n + 3))
        for (i in 0 until n) {
            val x = -64 + (4224.0 * i / (n - 1))
            val y = 1800 + 600 * sin(x / 300.0) + 90 * cos(i * 2.7) + 40 * sin(i * 0.9)
            ring[2 * i] = x.toInt(); ring[2 * i + 1] = y.toInt()
        }
        // Down the east side, along the bottom, back up the west: clockwise on screen.
        ring[2 * n] = 4160; ring[2 * n + 1] = 4160
        ring[2 * n + 2] = -64; ring[2 * n + 3] = 4160
        ring[2 * n + 4] = -64; ring[2 * n + 5] = 4160
        val vt = MiniMvt().layer("water") { polygon(ring.copyOf(2 * (n + 2)), props = mapOf("class" to "ocean")) }.build()
        SceneBuilder.build(vt, id, Look.CHART)
        val t0 = System.nanoTime()
        val s = SceneBuilder.build(vt, id, Look.CHART)
        val ms = (System.nanoTime() - t0) / 1e6
        println("chart coast: ${s.stats} (timed ${"%.0f".format(ms)} ms)")
        assertTrue("chart water took $ms ms", ms < 2500)
        val g = s.ground
        var biggest = 0
        for (f in 0 until g.faceCount) biggest = maxOf(biggest, g.faceVertexStart[f + 1] - g.faceVertexStart[f])
        assertTrue("a face of $biggest vertices", biggest <= 8192)
        // Shallow by the shore, deep far out.
        val shallow = Theme.CHART.waterShallow!!
        assertTrue(g.colours.count { it == shallow } > 1000)
        assertTrue(g.colours.count { it == Theme.CHART.water } > 1000)
    }

    @Test
    fun openWaterHasNoShoalsWhereTilesWereCut() {
        // Open water over the whole tile, cut only at the tile's buffer: no bank anywhere, so no shallows either,
        // in the tile's own scene or in an overzoomed one (cut again 60 m outside its square).
        val sea = intArrayOf(-64, -64, 4160, -64, 4160, 4160, -64, 4160)
        val island = intArrayOf(1800, 1800, 1800, 2300, 2300, 2300, 2300, 1800)
        val vt = MiniMvt().layer("water") { polygon(sea, props = mapOf("class" to "ocean")) }.build()
        val shallow = setOf(Theme.CHART.waterShallow!!, Theme.CHART.waterShallow2!!)
        for (scene in listOf(id, TileId(16, id.x * 4 + 1, id.y * 4 + 2))) {
            val g = SceneBuilder.build(vt, scene, Look.CHART, source = id).ground
            assertEquals("shallows in $scene", 0, g.colours.count { it in shallow })
            assertTrue(g.colours.count { it == Theme.CHART.water } > 3)
        }
        // A real island in it does shoal.
        val withIsland = MiniMvt().layer("water") { polygon(sea, island, props = mapOf("class" to "ocean")) }.build()
        val g = SceneBuilder.build(withIsland, id, Look.CHART).ground
        assertTrue(g.colours.count { it == Theme.CHART.waterShallow } > 50)
    }

    @Test
    fun runwaysMappedAsLinesAreDrawn() {
        // Most runways are mapped as lines (with a width), not areas: the aeroway layer carries them so.
        val vt = MiniMvt().layer("aeroway") {
            line(intArrayOf(500, 2000, 3500, 2000), props = mapOf("class" to "runway"))
            line(intArrayOf(2000, 500, 2000, 1500), props = mapOf("class" to "taxiway"))
        }.build()
        val s = SceneBuilder.build(vt, id, Look.DAY)
        val k = s.size / 4096
        val runway = Theme.DAY.runway
        // Across the runway at x = 2000 units: covered 22.5 m either side of its centreline, not beyond.
        assertEquals(runway, colourAt(s.ground, 2000 * k, 2000 * k + 20f))
        assertEquals(runway, colourAt(s.ground, 2000 * k, 2000 * k - 20f))
        assertEquals(Theme.DAY.earth, colourAt(s.ground, 2600 * k, 2000 * k + 26f))
        // The taxiway: 18 m wide.
        assertEquals(runway, colourAt(s.ground, 2000 * k + 8f, 1000 * k))
        assertEquals(Theme.DAY.earth, colourAt(s.ground, 2000 * k + 11f, 1000 * k))
    }

    /** The colour of the last ground triangle (in draw order) with area under a point. */
    private fun colourAt(m: FaceMesh, x: Float, y: Float): Int? {
        var c: Int? = null
        var k = 0
        while (k < m.indexCount) {
            val a = m.indices[k]; val b = m.indices[k + 1]; val d = m.indices[k + 2]
            k += 3
            val area = (m.x(b) - m.x(a)) * (m.y(d) - m.y(a)) - (m.x(d) - m.x(a)) * (m.y(b) - m.y(a))
            if (kotlin.math.abs(area) < 1e-6f) continue
            val d1 = (x - m.x(b)) * (m.y(a) - m.y(b)) - (m.x(a) - m.x(b)) * (y - m.y(b))
            val d2 = (x - m.x(d)) * (m.y(b) - m.y(d)) - (m.x(b) - m.x(d)) * (y - m.y(d))
            val d3 = (x - m.x(a)) * (m.y(d) - m.y(a)) - (m.x(d) - m.x(a)) * (y - m.y(a))
            if (!((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))) c = m.colours[a]
        }
        return c
    }

    @Test
    fun squaresLieFlatInEveryTheme() {
        val square = intArrayOf(1000, 1000, 2000, 1000, 2000, 2000, 1000, 2000)
        val pier = intArrayOf(2500, 2500, 2700, 2500, 2700, 3500, 2500, 3500)
        val vt = MiniMvt().layer("transportation") {
            polygon(square, props = mapOf("class" to "path", "subclass" to "pedestrian"))
            polygon(pier, props = mapOf("class" to "pier"))
        }.build()
        for (look in listOf(Look.DAY, Look.NIGHT, Look.GRAY)) {
            val g = SceneBuilder.build(vt, id, look).ground
            val size = TileFrame(id, id).size
            val k = size / 4096
            var squareZ = -1f; var pierZ = -1f
            for (v in 0 until g.vertexCount) {
                val x = g.x(v) / k; val y = g.y(v) / k
                if (x >= 999 && x <= 2001 && y >= 999 && y <= 2001) squareZ = maxOf(squareZ, g.z(v))
                if (x >= 2499 && x <= 2701 && y >= 2499 && y <= 3501) pierZ = maxOf(pierZ, g.z(v))
            }
            assertEquals("square in ${look.theme.name}", 0.02f, squareZ, 1e-4f)
            assertEquals("pier in ${look.theme.name}", 0.25f, pierZ, 1e-4f)
        }
    }

    /** A rectangle in tile units, clockwise on screen. */
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    @Test
    fun aNeighboursBuildingShadesTheWallsBeside() {
        // Scene 16/…+1/…+1 covers source units 1024..2048 both ways. A 40 m tower just across its west edge (owned by
        // the scene west of it) stands in the afternoon sun's way of a low house inside it.
        val vt = MiniMvt().layer("building") {
            polygon(rect(960, 1500, 1060, 1600), props = mapOf("render_height" to 40.0, "render_min_height" to 0.0), id = 12L)
            polygon(rect(1080, 1450, 1120, 1560), props = mapOf("render_height" to 10.0, "render_min_height" to 0.0), id = 22L)
        }.build()
        val scene = TileId(16, id.x * 4 + 1, id.y * 4 + 1)
        val s = SceneBuilder.build(vt, scene, Look.DAY, source = id)
        assertEquals("the tower is its neighbour's to draw", 1, s.buildings.size)
        val house = s.buildings[0]
        val shading = Look.DAY.shading
        val m = s.objects.mesh
        var west = -1
        for (f in s.objects.faceStart[0] until s.objects.faceStart[1]) {
            if (m.faceNormal[3 * f] < -0.99f && m.faceGroup[f] == FaceMesh.GROUP_WALL) west = f
        }
        assertTrue("the house has a west wall", west >= 0)
        val colour = m.colours[m.faceVertexStart[west]]
        assertEquals("in the tower's shadow", shading.shade(house.wallColour, -1f, 0f, 0f, false), colour)
        assertTrue(colour != shading.shade(house.wallColour, -1f, 0f, 0f, true))
    }

    @Test
    fun noTreeGrowsInsideANeighboursBuilding() {
        // A garden over the whole scene and a building across its east edge, owned by the scene east of it.
        val vt = MiniMvt()
            .layer("landcover") { polygon(rect(900, 900, 2200, 2200), props = mapOf("class" to "grass", "subclass" to "garden")) }
            .layer("building") { polygon(rect(1950, 1300, 2200, 1700), props = mapOf("render_height" to 12.0, "render_min_height" to 0.0), id = 32L) }
            .build()
        val scene = TileId(16, id.x * 4 + 1, id.y * 4 + 1)
        val s = SceneBuilder.build(vt, scene, Look.DAY, source = id)
        assertEquals(0, s.buildings.size)
        val k = TileFrame(scene, id).scale(4096)
        // The building's west part in the scene's metres (the scene starts at source unit 1024).
        val x0 = (1950 - 1024) * k; val y0 = (1300 - 1024) * k; val y1 = (1700 - 1024) * k
        val t = s.trees
        assertTrue("${t.count} trees", t.count > 50)
        for (i in 0 until t.count) {
            val inside = t.x[i] > x0 && t.y[i] > y0 && t.y[i] < y1
            assertTrue("a tree at ${t.x[i]}, ${t.y[i]} stands in the building", !inside)
        }
    }

    @Test
    fun treeShadowsKeepTheirTrunkWhateverTheSun() {
        // The trunk's shadow is a strip 0.36 m wide from the foot of the tree to its crown's shadow, also when the sun
        // stands due east or west (the shadow then runs along x).
        for (azimuth in listOf(90.0, 180.0, 215.0, 270.0)) {
            val look = Look(Theme.DAY, Light(azimuth, 30.0))
            val planter = TreePlanter(TileFrame(id, id), look.theme, look.shading, ShadowCaster(emptyList(), look.light))
            val trees = TreeSet(1, floatArrayOf(500f), floatArrayOf(500f), FloatArray(1), floatArrayOf(12f), floatArrayOf(3f), IntArray(1), IntArray(1), IntArray(1), 0, BooleanArray(1))
            val mb = MeshBuilder()
            planter.shadows(trees, mb, Colours.BLACK)
            val m = mb.build()
            val len = kotlin.math.hypot(look.light.shadowDx(0.72f * 12).toDouble(), look.light.shadowDy(0.72f * 12).toDouble())
            // The strip is the face's last two triangles.
            var area = 0.0
            for (t in m.triangleCount - 2 until m.triangleCount) {
                val a = m.indices[3 * t]; val b = m.indices[3 * t + 1]; val c = m.indices[3 * t + 2]
                area += kotlin.math.abs((m.x(b) - m.x(a)) * (m.y(c) - m.y(a)) - (m.x(c) - m.x(a)) * (m.y(b) - m.y(a))) / 2.0
            }
            assertEquals("sun at $azimuth°", 0.36 * len, area, 0.36 * len * 0.01)
        }
    }

    @Test
    fun anInterruptedBuildStops() {
        val vt = TestKit.tile(id)
        // Interrupted before it starts: no scene, and the interrupt is cleared, as InterruptedException promises.
        Thread.currentThread().interrupt()
        val early = runCatching { SceneBuilder.build(vt, id, Look.DAY) }
        assertTrue(early.exceptionOrNull() is InterruptedException)
        assertTrue(!Thread.currentThread().isInterrupted)
        // Interrupted while building (a coroutine cancelled inside runInterruptible): it stops promptly.
        var caught: Throwable? = null
        var built = 0
        val worker = Thread {
            try {
                repeat(10_000) { SceneBuilder.build(vt, id, Look.DAY); built++ }
            } catch (e: Throwable) {
                caught = e
            }
        }
        worker.start()
        Thread.sleep(300)
        val t0 = System.nanoTime()
        worker.interrupt()
        worker.join(5_000)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue("still building", !worker.isAlive)
        assertTrue("$caught after $built builds", caught is InterruptedException)
        println("an interrupted scene build stopped in ${"%.1f".format(ms)} ms")
        assertTrue("stopped in $ms ms", ms < 1000)
    }

    @Test
    fun growableListsStartEmpty() {
        val f = FloatList(0)
        repeat(100) { f.add(it.toFloat()) }
        assertEquals(100, f.size)
        assertEquals(99f, f[99], 0f)
        val i = IntList(0)
        repeat(100) { i.add(it) }
        assertEquals(99, i[99])
    }
}
