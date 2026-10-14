package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCoverTest {
    private val ams = LatLon(52.3746, 4.8826)

    @Test
    fun straightDownCoversTheScreenAtOneZoom() {
        // 412 × 915 dp at zoom 15: tiles of 256 dp, so about 2 × 4 of them, give or take the edges.
        val c = Camera(ams, 15.0, 0.0, 0.0, 824, 1830, 2f)
        val tiles = TileCover.cover(c)
        assertTrue(tiles.all { it.z == 15 })
        assertTrue("${tiles.size} tiles", tiles.size in 6..15)
        // The tile under the target is first.
        assertEquals(Mercator.tileAt(ams, 15), tiles.first())
        // No tile twice, and they cover the four screen corners.
        assertEquals(tiles.size, tiles.toSet().size)
        for ((x, y) in listOf(0.0 to 0.0, 823.0 to 0.0, 0.0 to 1829.0, 823.0 to 1829.0)) {
            assertTrue(Mercator.tileAt(c.unproject(x, y)!!, 15) in tiles)
        }
    }

    @Test
    fun pitchedCoverIsFineNearAndCoarseFar() {
        val c = Camera(ams, 16.3, -20.0, 60.0, 824, 1830, 2f)
        val tiles = TileCover.cover(c)
        assertTrue(tiles.size <= TileCover.MAX_TILES)
        val near = tiles.first()
        val far = tiles.last()
        // The ground under the bottom of the screen is nearer than the target: a level finer.
        assertTrue(near.z in 16..17)
        assertEquals(near.z, tiles.maxOf { it.z })
        assertTrue("far tiles coarser: ${far.z}", far.z < near.z)
        // Tiles never overlap: no tile is an ancestor of another.
        for (a in tiles) for (b in tiles) if (a != b && b.z > a.z) assertFalse("$a holds $b", b.ancestor(a.z) == a)
        // Zoom falls with distance.
        val byZoom = tiles.groupBy { it.z }.mapValues { (_, l) -> l.minOf { TileCover.nearestDistance(c, it) } }
        val zs = byZoom.keys.sortedDescending()
        for (i in 1 until zs.size) assertTrue(byZoom[zs[i]]!! >= byZoom[zs[i - 1]]!! * 0.99)
    }

    @Test
    fun steepViewsStayWithinTheLimit() {
        for (pitch in listOf(65.0, 72.0)) {
            val c = Camera(ams, 18.0, 30.0, pitch, 1440, 3120, 3f)
            val tiles = TileCover.cover(c)
            assertTrue("${tiles.size} at pitch $pitch", tiles.size in 1..TileCover.MAX_TILES)
            assertTrue(tiles.first().z >= 18)
        }
        // A tighter budget coarsens the cover instead of dropping ground.
        val c = Camera(ams, 17.0, 0.0, 60.0, 824, 1830, 2f)
        val few = TileCover.cover(c, maxTiles = 12)
        assertTrue(few.size <= 12)
        assertTrue(few.minOf { it.z } < TileCover.cover(c).minOf { it.z } || few.size < TileCover.cover(c).size)
    }

    @Test
    fun intersectsUsesSeparatingAxes() {
        val t = TileId(10, 525, 336)
        val x0 = t.unitX; val y0 = t.unitY; val s = t.unitSize
        // A thin diagonal quad passing near the corner but outside it.
        val outside = doubleArrayOf(x0 + 1.2 * s, y0 - 0.5 * s, x0 + 1.4 * s, y0 - 0.5 * s, x0 + 0.1 * s, y0 - 0.05 * s, x0 - 0.1 * s, y0 - 0.1 * s)
        assertFalse(TileCover.intersects(outside, t))
        val inside = doubleArrayOf(x0 + 0.4 * s, y0 + 0.4 * s, x0 + 0.6 * s, y0 + 0.4 * s, x0 + 0.6 * s, y0 + 0.6 * s, x0 + 0.4 * s, y0 + 0.6 * s)
        assertTrue(TileCover.intersects(inside, t))
        val around = doubleArrayOf(x0 - s, y0 - s, x0 + 2 * s, y0 - s, x0 + 2 * s, y0 + 2 * s, x0 - s, y0 + 2 * s)
        assertTrue(TileCover.intersects(around, t))
    }

    @Test
    fun planFallsBackToParentsOnlyWhereChildrenAreMissing() {
        val parent = TileId(14, 8414, 5384)
        val kids = parent.children()
        val ready = HashMap<TileId, TileScene>()
        ready[parent] = Synthetic.scene(parent, emptyList())
        ready[kids[0]] = Synthetic.scene(kids[0], emptyList())
        val plan = TileCover.plan(kids) { ready[it] }
        // The parent first (coarse ground under), standing in for the three missing children only; then the ready child.
        assertEquals(2, plan.size)
        assertEquals(parent, plan[0].scene.id)
        assertEquals(kids.drop(1).toSet(), plan[0].only!!.toSet())
        assertEquals(kids[0], plan[1].scene.id)
        assertEquals(null, plan[1].only)
        // A point in the ready child is not the parent's to draw.
        val p = kids[0]
        assertFalse(plan[0].includes(p.unitX + p.unitSize / 2, p.unitY + p.unitSize / 2))
        assertTrue(plan[0].includes(kids[3].unitX + kids[3].unitSize / 2, kids[3].unitY + kids[3].unitSize / 2))
        // Zooming out: the finer scenes stand in for a missing coarse tile.
        val zoomOut = TileCover.plan(listOf(TileId(13, 4207, 2692))) { if (it == parent) ready[parent] else null }
        assertEquals(listOf(parent), zoomOut.map { it.scene.id })
        // Two levels out at once (a fast pinch): the grandchildren stand in.
        val fast = TileCover.plan(listOf(TileId(12, 2103, 1346))) { if (it == parent) ready[parent] else null }
        assertEquals(listOf(parent), fast.map { it.scene.id })
    }
}
