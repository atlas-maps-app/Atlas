package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.tiles.GeomType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where scenes meet: buildings across tile edges drawn exactly once, ids only where the tile kept a building apart,
 * quays only where they show, and trees and lit windows that look the same at every zoom.
 */
class SceneSeamsTest {
    /** The footprints a scene draws, indexed in unit Mercator for point queries. */
    private fun drawn(s: TileScene): RingIndex {
        val rings = s.buildings.map { b -> DoubleArray(b.footprint.size) { k -> if (k % 2 == 0) s.unitX(b.footprint[k]) else s.unitY(b.footprint[k]) } }
        return RingIndex(rings, 1.0 / (s.metresPerUnit * 0.04))
    }

    /** The outer rings of the tile's own building data (as the scenes' footprints are), indexed in unit Mercator. */
    private fun truth(id: TileId): RingIndex {
        val layer = TestKit.tile(id)["building"]!!
        val frame = TileFrame(id, id)
        val rings = ArrayList<DoubleArray>()
        for (f in layer.features) {
            if (f.type != GeomType.POLYGON || f.bool("hide_3d") == true) continue
            for (poly in frame.polygons(f.geometry, layer.extent)) {
                val r = poly[0]
                rings += DoubleArray(r.size) { k -> if (k % 2 == 0) id.unitX + r[k] / frame.metresPerUnit else id.unitY + r[k] / frame.metresPerUnit }
            }
        }
        return RingIndex(rings, 1.0 / (frame.metresPerUnit * 0.04))
    }

    @Test
    fun neighbouringTilesDrawEachBuildingOnce() {
        val a = TestKit.centre
        val sa = SceneBuilder.build(TestKit.tile(a), a, Look.DAY)
        val drawnA = drawn(sa)
        val truthA = truth(a)
        for (b in listOf(TileId(14, a.x + 1, a.y), TileId(14, a.x, a.y + 1))) {
            val sb = SceneBuilder.build(TestKit.tile(b), b, Look.DAY)
            val drawnB = drawn(sb)
            val truthB = truth(b)
            val east = b.x != a.x
            var both = 0; var missing = 0; var extra = 0; var built = 0
            // A strip 50 m either side of the shared edge, sampled every metre, clear of the corners (where buildings may
            // belong to tiles not built here).
            val step = 1.0 / sa.metresPerUnit
            val edge = if (east) b.unitX else b.unitY
            var along = (if (east) a.unitY else a.unitX) + 40 * step + step / 2
            val end = along + a.unitSize - 80 * step
            while (along < end) {
                // Half a metre off the grid, so no sample sits exactly on the seam.
                var across = edge - 50 * step + step / 2
                while (across < edge + 50 * step) {
                    val ux = if (east) across else along
                    val uy = if (east) along else across
                    val inA = drawnA.count(ux, uy) > 0
                    val inB = drawnB.count(ux, uy) > 0
                    val count = (if (across < edge) truthA else truthB).count(ux, uy)
                    val t = count > 0
                    // Where the map itself has two buildings overlapping, two scenes may each draw one.
                    if (inA && inB && count < 2) both++
                    if (t && !inA && !inB) missing++
                    if (!t && (inA || inB)) extra++
                    if (t) built++
                    across += step
                }
                along += step * 3
            }
            println("edge $a | $b: built $built m², drawn twice $both, missing $missing, extra $extra")
            assertEquals("building ground drawn by both $a and $b", 0, both)
            // Only footprints under 3 m² (sheds the map barely has) may go missing.
            assertTrue("missing $missing of $built", missing <= built / 1000)
            assertEquals("drawn where the map has no building", 0, extra)
        }
    }

    @Test
    fun osmIdsOnlyForBuildingsTheTileKeptApart() {
        val s = SceneBuilder.build(TestKit.tile(TestKit.centre), TestKit.centre, Look.DAY)
        val withId = s.buildings.filter { it.osmId != null }
        // The tiles merge most buildings into multipolygon features that carry one building's id: those get none.
        assertTrue("${withId.size} with ids", withId.isNotEmpty() && withId.size < s.buildings.size / 2)
        val byId = withId.groupBy { it.osmId }
        assertEquals("an OSM id names one building", withId.size, byId.size)
        // The tile feature id stays, for the record.
        assertTrue(s.buildings.all { it.featureId != 0L })
    }

    @Test
    fun quaysOnlyWhereTheyShow() {
        val z12 = TileId(12, 2103, 1346)
        val far = SceneBuilder.build(TestKit.tile(z12), z12, Look.DAY)
        assertEquals(0, (0 until far.ground.faceCount).count { far.ground.faceKind[it] == FaceMesh.TWO_SIDED })
        val z16 = TileId(16, TestKit.centre.x * 4 + 1, TestKit.centre.y * 4 + 1)
        val near = SceneBuilder.build(TestKit.tile(TestKit.centre), z16, Look.DAY, source = TestKit.centre)
        assertTrue((0 until near.ground.faceCount).count { near.ground.faceKind[it] == FaceMesh.TWO_SIDED } > 20)
    }

    @Test
    fun treesInARowLookTheSameAtEveryZoom() {
        val parent = TileId(16, TestKit.centre.x * 4 + 1, TestKit.centre.y * 4 + 1)
        val child = parent.children()[0]
        // A row of trees across the child (and so across the parent).
        val a = TileScene0.corner(child, 0.1, 0.5); val b = TileScene0.corner(child, 0.9, 0.5)
        val extras = Extras(treeRows = listOf(TreeRow(listOf(a, b))))
        val vt = TestKit.tile(TestKit.centre)
        val sp = SceneBuilder.build(vt, parent, Look.DAY, extras, TestKit.centre)
        val sc = SceneBuilder.build(vt, child, Look.DAY, extras, TestKit.centre)
        var compared = 0
        for (i in 0 until sc.trees.count) {
            if (!sc.trees.mapped[i]) continue
            val ux = sc.unitX(sc.trees.x[i]); val uy = sc.unitY(sc.trees.y[i])
            val j = (0 until sp.trees.count).firstOrNull { k ->
                sp.trees.mapped[k] && Math.abs(sp.unitX(sp.trees.x[k]) - ux) * sp.metresPerUnit < 0.01 && Math.abs(sp.unitY(sp.trees.y[k]) - uy) * sp.metresPerUnit < 0.01
            } ?: continue
            // Size and colour come from the tree alone (whether the sun reaches it depends on the scene's buildings).
            assertEquals(sp.trees.radius[j], sc.trees.radius[i], 1e-4f)
            assertEquals(sp.trees.height[j], sc.trees.height[i], 1e-4f)
            assertEquals(sp.trees.dark[j], sc.trees.dark[i])
            compared++
        }
        assertTrue("$compared trees compared", compared >= 5)
    }

    @Test
    fun litWindowsStayPutWhenTheZoomChanges() {
        val vt = TestKit.tile(TestKit.centre)
        val parent = TileId(16, TestKit.centre.x * 4 + 1, TestKit.centre.y * 4 + 1)
        val child = parent.children()[3]
        val sp = SceneBuilder.build(vt, parent, Look.NIGHT, source = TestKit.centre)
        val sc = SceneBuilder.build(vt, child, Look.NIGHT, source = TestKit.centre)
        val lit = Theme.NIGHT.windowLit.toSet()
        fun litOf(s: TileScene, o: Int): List<Int> {
            val m = s.objects.mesh
            val out = ArrayList<Int>()
            for (f in s.objects.faceStart[o] until s.objects.faceStart[o + 1]) {
                for (v in m.faceVertexStart[f] until m.faceVertexStart[f + 1]) if (m.colours[v] in lit) out += m.colours[v]
            }
            return out
        }
        var compared = 0; var same = 0
        for (o in 0 until sc.objects.count) {
            val ux = sc.unitX(sc.objects.centre[3 * o]); val uy = sc.unitY(sc.objects.centre[3 * o + 1])
            val p = (0 until sp.objects.count).firstOrNull { k ->
                Math.abs(sp.unitX(sp.objects.centre[3 * k]) - ux) * sp.metresPerUnit < 0.01 && Math.abs(sp.unitY(sp.objects.centre[3 * k + 1]) - uy) * sp.metresPerUnit < 0.01
            } ?: continue
            val a = litOf(sc, o)
            if (a.isEmpty()) continue
            compared++
            if (a == litOf(sp, p)) same++
        }
        assertTrue("$compared buildings compared", compared > 50)
        assertTrue("$same of $compared light the same windows", same >= compared * 0.97)
    }
}

/** Rings on a uniform grid of [cell] (in the rings' units), for "how many contain this point?" over many points. */
class RingIndex(private val rings: List<DoubleArray>, private val cell: Double) {
    private val cells = HashMap<Long, MutableList<Int>>()
    private val boxes = DoubleArray(rings.size * 4)

    init {
        for ((i, r) in rings.withIndex()) {
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (k in 0 until r.size / 2) {
                x0 = minOf(x0, r[2 * k]); x1 = maxOf(x1, r[2 * k]); y0 = minOf(y0, r[2 * k + 1]); y1 = maxOf(y1, r[2 * k + 1])
            }
            boxes[4 * i] = x0; boxes[4 * i + 1] = y0; boxes[4 * i + 2] = x1; boxes[4 * i + 3] = y1
            for (cy in cellOf(y0)..cellOf(y1)) for (cx in cellOf(x0)..cellOf(x1)) {
                cells.getOrPut(cx * 1_000_003L + cy) { ArrayList() } += i
            }
        }
    }

    private fun cellOf(v: Double): Long = kotlin.math.floor(v / cell).toLong()

    fun count(x: Double, y: Double): Int {
        val list = cells[cellOf(x) * 1_000_003L + cellOf(y)] ?: return 0
        var n = 0
        for (i in list) {
            if (x < boxes[4 * i] || x > boxes[4 * i + 2] || y < boxes[4 * i + 1] || y > boxes[4 * i + 3]) continue
            if (Rings.contains(rings[i], x, y)) n++
        }
        return n
    }
}

/** Points of a tile by fraction of its square. */
object TileScene0 {
    fun corner(t: TileId, fx: Double, fy: Double) = LatLon(Mercator.lat(t.unitY + fy * t.unitSize), Mercator.lon(t.unitX + fx * t.unitSize))
}
