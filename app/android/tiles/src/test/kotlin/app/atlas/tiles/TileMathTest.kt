package app.atlas.tiles

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TileMathTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    private fun square(x0: Double, y0: Double, x1: Double, y1: Double) = doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    @Test
    fun aTileCoversItself() {
        val t = Fixtures.CENTRE
        val poly = square(t.unitX, t.unitY, t.unitX + t.unitSize, t.unitY + t.unitSize)
        assertEquals(listOf(t), TileMath.cover(poly, 14, t.unitX + t.unitSize / 2, t.unitY + t.unitSize / 2))
        // At z15 the same square is its four children; at z13 its parent.
        assertEquals(t.children().toSet(), TileMath.cover(poly, 15, t.unitX, t.unitY).toSet())
        assertEquals(listOf(t.parent()!!), TileMath.cover(poly, 13, t.unitX, t.unitY))
    }

    @Test
    fun nearestTheCentreFirst() {
        val c = Fixtures.CENTRE
        val s = c.unitSize
        val poly = square(c.unitX - s, c.unitY - s, c.unitX + 2 * s, c.unitY + 2 * s)
        val tiles = TileMath.cover(poly, 14, c.unitX + s / 2, c.unitY + s / 2)
        assertEquals(9, tiles.size)
        assertEquals(c, tiles[0])
        assertEquals(Fixtures.z14.toSet(), tiles.toSet())
        // Edge neighbours before corners.
        assertTrue(tiles.subList(1, 5).all { abs(it.x - c.x) + abs(it.y - c.y) == 1 })
        val parents = TileMath.parents(tiles)
        assertEquals(tiles.map { it.parent()!! }.distinct(), parents)
        assertEquals(listOf(TileId(12, 2103, 1346), TileId(12, 2103, 1345)), TileMath.parents(tiles, 2))
    }

    @Test
    fun aDiamondSkipsItsCorners() {
        val c = Fixtures.CENTRE
        val s = c.unitSize
        val cx = c.unitX + s / 2
        val cy = c.unitY + s / 2
        // A diamond reaching 1.4 tiles out: the corner tiles of the 3×3 block are only touched near their inner corner.
        val r = 1.4 * s
        val diamond = doubleArrayOf(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy)
        val tiles = TileMath.cover(diamond, 14, cx, cy)
        assertEquals(9, tiles.size)
        val thin = doubleArrayOf(cx, cy - 0.9 * s, cx + 0.9 * s, cy, cx, cy + 0.9 * s, cx - 0.9 * s, cy)
        assertEquals(5, TileMath.cover(thin, 14, cx, cy).size)
    }

    @Test
    fun nothingBeyondTheWorldsEdges() {
        // Wholly east or west of the world (a low-zoom view past the antimeridian): no tiles, not the edge column.
        assertEquals(emptyList<TileId>(), TileMath.cover(square(1.1, 0.4, 1.3, 0.6), 3, 1.2, 0.5))
        assertEquals(emptyList<TileId>(), TileMath.cover(square(-0.3, 0.4, -0.1, 0.6), 3, -0.2, 0.5))
        // Straddling the east edge: only the tiles inside the world.
        assertEquals(setOf(TileId(3, 7, 3), TileId(3, 7, 4)), TileMath.cover(square(0.9, 0.4, 1.3, 0.6), 3, 1.0, 0.5).toSet())
        // A rotated quadrilateral whose top rows lie east of the world while its bottom rows are inside.
        val slanted = doubleArrayOf(0.8, 0.9, 0.95, 0.9, 1.4, 0.1, 1.25, 0.1)
        val tiles = TileMath.cover(slanted, 3, 1.0, 0.5)
        assertTrue(tiles.isNotEmpty())
        assertTrue("$tiles", tiles.all { it.y >= 4 })
    }

    @Test
    fun coversAPolygonOfPlaces() {
        val ring = listOf(LatLon(52.37, 4.87), LatLon(52.37, 4.90), LatLon(52.38, 4.90), LatLon(52.38, 4.87))
        val tiles = TileMath.cover(ring, 14, westerkerk)
        assertEquals(Mercator.tileAt(westerkerk, 14), tiles[0])
        assertEquals(Mercator.tilesIn(app.atlas.geo.BBox(4.87, 52.37, 4.90, 52.38), 14).toSet(), tiles.toSet())
    }

    @Test
    fun aFlatCameraSeesItsViewport() {
        val cam = CameraView.at(westerkerk, zoom = 16.0, width = 1080.0, height = 2340.0)
        val f = TileMath.footprint(cam)
        val world = 256.0 * (1 shl 16)
        // Width and height of the footprint in pixels at that zoom.
        assertEquals(1080.0, (f[2] - f[0]) * world, 1e-6)
        assertEquals(2340.0, (f[1] - f[5]) * world, 1e-6)
        assertEquals(cam.centerX, (f[0] + f[2]) / 2, 1e-12)
        assertEquals(14, TileMath.tileZoom(cam.zoom))
        val cover = TileMath.cover(cam, TileMath.tileZoom(cam.zoom, maxZoom = 22))
        assertEquals(16, cover.zoom)
        assertEquals(Mercator.tileAt(westerkerk, 16), cover.tiles[0])
        // 1080 × 2340 px is 4.2 × 9.1 tiles of 256 px: at most 6 × 11 tiles.
        assertTrue(cover.tiles.size in 20..66)
        assertTrue(cover.parents.size < cover.tiles.size)
    }

    @Test
    fun bearingTurnsTheFootprint() {
        val cam = CameraView.at(westerkerk, zoom = 15.0, width = 400.0, height = 800.0, bearing = 90.0)
        val f = TileMath.footprint(cam)
        val world = 256.0 * (1 shl 15)
        // Facing east, the screen's top edge lies east of the centre and the long side runs east–west.
        val topMidX = (f[4] + f[6]) / 2
        assertEquals(400.0, (topMidX - cam.centerX) * world, 1e-6)
        assertEquals(400.0, abs(f[5] - f[7]) * world, 1e-6)
    }

    @Test
    fun aPitchedCameraSeesFartherAndWider() {
        val flat = CameraView.at(westerkerk, zoom = 17.0, width = 1000.0, height = 2000.0)
        val tilted = flat.copy(pitch = 60.0)
        val f = TileMath.footprint(tilted)
        val world = 256.0 * (1 shl 17)
        val topWidth = hypot(f[4] - f[6], f[5] - f[7]) * world
        val bottomWidth = hypot(f[2] - f[0], f[3] - f[1]) * world
        assertTrue("top $topWidth bottom $bottomWidth", topWidth > bottomWidth * 1.5)
        val ahead = (flat.centerY - (f[5] + f[7]) / 2) * world
        assertTrue("sees $ahead px ahead", ahead > 1000.0 && ahead <= tilted.farFactor * tilted.height + 1e-6)
        assertTrue(TileMath.cover(tilted, 17).tiles.size > TileMath.cover(flat, 17).tiles.size)
        // Looking at the horizon is cut off at the far limit, not infinity.
        val horizon = flat.copy(pitch = 85.0)
        val h = TileMath.footprint(horizon)
        val far = (flat.centerY - (h[5] + h[7]) / 2) * world
        assertEquals(horizon.farFactor * horizon.height, far, 1e-3)
    }

    @Test
    fun tileZoomOverzoomsPastTheLast() {
        assertEquals(14, TileMath.tileZoom(17.3))
        assertEquals(12, TileMath.tileZoom(12.99))
        assertEquals(13, TileMath.tileZoom(12.9999999))
        assertEquals(0, TileMath.tileZoom(-1.0))
    }
}
