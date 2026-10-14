package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Painter's order, near-plane clipping and hit tests on hand-made scenes. */
class PainterTest {
    private val id = TestKit.centre

    @Test
    fun farBuildingDrawsFirstAndNearOneWinsTheTap() {
        // Looking north: a 60 m block in front (south) of a 90 m tower.
        val near = Synthetic.box(700.0, 760.0, 720.0, 780.0, 60.0, id = 1)
        val far = Synthetic.box(700.0, 700.0, 720.0, 720.0, 90.0, id = 2)
        val scene = Synthetic.scene(id, listOf(near, far))
        val camera = Camera(scene.latLon(710f, 740f), 17.0, 0.0, 50.0, 800, 1400, 2f)
        val r = Renderer()
        val frame = r.frame(camera, listOf(scene), 0L)
        val h = frame.hits
        val order = (0 until h.count).map { h.objectIndex[it] }.distinct()
        assertEquals("the far building is drawn first", listOf(1, 0), order)
        // The near building's roof: a tap there hits it.
        val roof = camera.project(scene.latLon(710f, 770f), 60.0)!!
        assertEquals(near.featureId, (r.hitTest(frame, roof.x, roof.y) as Hit.Building).info.featureId)
        // The far tower's foot lies behind the near block on screen: the tap hits the near one.
        val farBase = camera.project(scene.latLon(710f, 720f), 1.0)!!
        val nearTop = camera.project(scene.latLon(710f, 760f), 60.0)!!
        assertTrue("precondition: the block hides the tower's foot", nearTop.y < farBase.y)
        assertEquals(near.featureId, (r.hitTest(frame, farBase.x, farBase.y) as Hit.Building).info.featureId)
        // The tower's top shows above the block.
        val farRoof = camera.project(scene.latLon(710f, 710f), 90.0)!!
        assertTrue(farRoof.y < nearTop.y)
        assertEquals(far.featureId, (r.hitTest(frame, farRoof.x, farRoof.y) as Hit.Building).info.featureId)
        // Bare ground hits nothing.
        val ground = camera.project(scene.latLon(600f, 740f), 0.0)!!
        assertEquals(null, r.hitTest(frame, ground.x, ground.y))
        val hit = r.hitTest(frame, roof.x, roof.y) as Hit.Building
        assertEquals(4, hit.footprint.size)
        assertEquals(scene.id, hit.tile)
    }

    @Test
    fun backWallsAreCulled() {
        val b = Synthetic.box(700.0, 700.0, 720.0, 720.0, 20.0)
        val scene = Synthetic.scene(id, listOf(b))
        val camera = Camera(scene.latLon(710f, 760f), 17.0, 0.0, 45.0, 800, 1400, 2f)
        val frame = Renderer().frame(camera, listOf(scene), 0L)
        // Looking north at a box from the south: its south wall and roof show; the east and west walls are edge-on
        // or hidden; the north wall never shows.
        assertTrue(frame.stats.facesDrawn in 2..4)
    }

    @Test
    fun trianglesAcrossTheNearPlaneAreClipped() {
        // One huge ground triangle reaching far behind the camera.
        val mb = MeshBuilder()
        mb.begin()
        val c = Colours.parse("#808080")
        mb.tri(mb.v(-5000f, 5000f, 0f, c), mb.v(5000f, 5000f, 0f, c), mb.v(700f, -5000f, 0f, c))
        mb.end()
        val scene = Synthetic.scene(id, emptyList(), ground = mb.build())
        for (pitch in listOf(30.0, 60.0, 72.0)) {
            val camera = Camera(scene.latLon(700f, 700f), 19.0, 0.0, pitch, 800, 1400, 2f)
            val frame = Renderer().frame(camera, listOf(scene), 0L)
            val batch = frame.batches.filterIsInstance<Batch.Triangles>().first { it.layer == 0 }
            // The clipped triangle (one or two), and at most the sky and haze quads.
            assertTrue(batch.indexCount in 3..18)
            for (v in 0 until batch.vertexCount) {
                assertTrue(batch.positions[2 * v].isFinite() && batch.positions[2 * v + 1].isFinite())
                assertTrue(kotlin.math.abs(batch.positions[2 * v]) < 1e7f)
            }
            // The ground under the bottom of the screen is covered.
            var covered = false
            var k = 0
            while (k < batch.indexCount) {
                val a = batch.indices[k].toInt() * 2; val b = batch.indices[k + 1].toInt() * 2; val cc = batch.indices[k + 2].toInt() * 2
                val p = batch.positions
                if (inTri(400f, 1390f, p[a], p[a + 1], p[b], p[b + 1], p[cc], p[cc + 1])) covered = true
                k += 3
            }
            assertTrue("pitch $pitch", covered)
        }
    }

    @Test
    fun shadowsGoInOneTranslucentLayer() {
        val scene = TestKit.scene(id, Look.DAY)
        assertNotNull(scene)
        val camera = Camera(scene!!.latLon(700f, 700f), 16.0, 0.0, 40.0, 800, 1400, 2f)
        val frame = Renderer().frame(camera, listOf(scene), 0L)
        val layers = frame.batches.map { it as Batch.Triangles }.filter { it.layer != 0 }
        assertTrue(layers.isNotEmpty())
        assertTrue(layers.all { it.layerAlpha == Look.DAY.theme.shadowOpacity })
        // Ground first, then the shadow layer, then the buildings: one run of layer batches in the middle.
        val kinds = frame.batches.map { (it as Batch.Triangles).layer }
        assertEquals(1, kinds.zipWithNext().count { (a, b) -> a == 0 && b != 0 })
    }

    @Test
    fun aListChangedInPlaceIsNotMistakenForTheLastOne() {
        // The view may keep one list of tiles and change it between frames: the cached draw order must not outlive it.
        val many = Synthetic.scene(id, (0 until 6).map { Synthetic.box(600.0 + it * 30, 700.0, 620.0 + it * 30, 720.0, 20.0, id = it + 1L) })
        val one = Synthetic.scene(id, listOf(Synthetic.box(700.0, 700.0, 720.0, 720.0, 20.0, id = 9)))
        val camera = Camera(many.latLon(700f, 760f), 16.5, 0.0, 45.0, 800, 1400, 2f)
        val r = Renderer()
        val tiles = mutableListOf(DrawTile(many))
        assertEquals(6, r.frame(camera, tiles, 0L).stats.objectsDrawn)
        tiles[0] = DrawTile(one)
        val f = r.frame(camera, tiles, 16L)
        assertEquals(1, f.stats.objectsDrawn)
        val roof = camera.project(one.latLon(710f, 710f), 20.0)!!
        assertEquals(92L, (r.hitTest(f, roof.x, roof.y) as Hit.Building).info.featureId)
    }

    @Test
    fun aResizedViewSortsItsObjectsAgain() {
        // A row of houses 600 m long. A narrow view sees a few; widening it (split screen, a fold opening) keeps the eye
        // where it was, but the cached draw order culled for the narrow view must not hide the rest.
        val houses = (0 until 30).map { Synthetic.box(400.0 + it * 20, 700.0, 410.0 + it * 20, 710.0, 20.0, id = it + 1L) }
        val scene = Synthetic.scene(id, houses)
        val narrow = Camera(scene.latLon(700f, 760f), 17.0, 0.0, 45.0, 200, 1400, 2f)
        val r = Renderer()
        val few = r.frame(narrow, listOf(scene), 0L).stats.objectsDrawn
        for (changed in listOf(narrow.copy(width = 1600), narrow.copy(width = 1600, offsetY = 300.0), narrow.copy(width = 1600, fov = 50.0))) {
            val f = r.frame(changed, listOf(scene), 16L)
            val fresh = Renderer().frame(changed, listOf(scene), 0L)
            assertEquals(fresh.stats.objectsDrawn, f.stats.objectsDrawn)
        }
        assertTrue("a narrow view shows only some ($few)", few < 30)
        assertEquals(30, r.frame(narrow.copy(width = 1600), listOf(scene), 32L).stats.objectsDrawn)
        // Holding still, the order is reused.
        assertTrue(r.frame(narrow.copy(width = 1600), listOf(scene), 48L).stats.sortReused)
    }

    @Test
    fun aViewNotYetLaidOutDrawsNothing() {
        // Before layout the view is 0 × 0: no tiles, an empty frame, no tap targets, and no exception.
        val scene = Synthetic.scene(id, listOf(Synthetic.box(700.0, 700.0, 720.0, 720.0, 20.0)))
        val camera = Camera(scene.latLon(710f, 710f), 16.0, 0.0, 45.0, 0, 0)
        assertTrue(TileCover.cover(camera).isEmpty())
        val r = Renderer()
        val f = r.frame(camera, listOf(scene), 0L)
        assertEquals(0, f.stats.triangles)
        assertEquals(null, r.hitTest(f, 0f, 0f))
    }

    @Test
    fun aTriangleShrunkToAPointHitsNothingElsewhere() {
        // A building whose only face is a triangle with three equal corners (a sliver shrunk to a point).
        val mb = MeshBuilder()
        mb.begin()
        val c = Colours.parse("#808080")
        mb.tri(mb.v(700f, 700f, 10f, c), mb.v(700f, 700f, 10f, c), mb.v(700f, 700f, 10f, c))
        mb.end()
        val b = Synthetic.box(700.0, 700.0, 701.0, 701.0, 10.0, id = 5)
        val base = Synthetic.scene(id, listOf(b))
        val scene = TileScene(
            base.id, base.source, base.look, base.originX, base.originY, base.metresPerUnit, base.size, base.ground, base.shadows,
            ObjectSet(mb.build(), 1, intArrayOf(0, 1), floatArrayOf(700f, 700f, 5f), floatArrayOf(10f), intArrayOf(0)),
            base.trees, base.labels, base.pois, base.buildings, base.stats,
        )
        val camera = Camera(scene.latLon(700f, 760f), 17.0, 0.0, 45.0, 800, 1400, 2f)
        val r = Renderer()
        val f = r.frame(camera, listOf(scene), 0L)
        assertEquals(1, f.stats.objectsDrawn)
        // Far from the point it was drawn at, a tap finds nothing.
        assertEquals(null, r.hitTest(f, 50f, 1300f))
    }

    private fun inTri(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }
}
