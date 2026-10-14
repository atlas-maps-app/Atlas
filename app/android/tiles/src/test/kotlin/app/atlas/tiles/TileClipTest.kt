package app.atlas.tiles

import app.atlas.geo.TileId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileClipTest {
    private fun geometry(type: Int, commands: IntArray): TileGeometry =
        Mvt.decode(MvtBuilder().layer("l") { feature(type, commands) }.build())["l"]!![0].geometry

    private fun totalArea(g: TileGeometry): Double {
        var a = 0.0
        g.forEachPolygon { j, _, _ -> a += g.polygonArea(j) }
        return a
    }

    @Test
    fun polygonWithHoleCutInHalf() {
        val g = geometry(3, Geom.rings(Geom.square(0, 0, 100), Geom.square(40, 40, 20, reverse = true)))
        val left = g.clip(0, 0, 50, 100)
        assertEquals(1, left.polygonCount)
        assertEquals(2, left.partCount)
        assertEquals(50.0 * 100 - 10.0 * 20, totalArea(left), 0.0)
        assertTrue(left.ringArea2(0) > 0)
        assertTrue(left.ringArea2(1) < 0)
        left.forEachPart { _, s, e -> assertEquals(left.x(s), left.x(e - 1)); assertEquals(left.y(s), left.y(e - 1)) }
        // Cutting beside the hole drops it.
        val strip = g.clip(0, 0, 30, 100)
        assertEquals(1, strip.partCount)
        assertEquals(3000.0, totalArea(strip), 0.0)
    }

    @Test
    fun insideIsKeptAndOutsideIsDropped() {
        val g = geometry(3, Geom.rings(Geom.square(10, 10, 10), Geom.square(500, 500, 10)))
        assertSame(g, g.clip(0, 0, 1000, 1000))
        assertTrue(g.clip(2000, 2000, 3000, 3000).isEmpty)
        val one = g.clip(0, 0, 100, 100)
        assertEquals(1, one.polygonCount)
        assertArrayEquals(intArrayOf(0, 1), one.polygons)
    }

    @Test
    fun linesBreakWhereTheyLeave() {
        // A U that leaves the box through the top and comes back.
        val g = geometry(2, Geom.lines(intArrayOf(10, 50, 10, -50, 90, -50, 90, 50)))
        val c = g.clip(0, 0, 100, 100)
        assertEquals(2, c.partCount)
        assertArrayEquals(intArrayOf(10, 50, 10, 0, 90, 0, 90, 50), c.coords)
        // Crossing straight through.
        val through = geometry(2, Geom.lines(intArrayOf(-100, 50, 200, 50))).clip(0, 0, 100, 100)
        assertArrayEquals(intArrayOf(0, 50, 100, 50), through.coords)
    }

    @Test
    fun pointsOutsideGo() {
        val g = geometry(1, Geom.points(5, 5, 150, 5, 50, 50))
        assertArrayEquals(intArrayOf(5, 5, 50, 50), g.clip(0, 0, 100, 100).coords)
    }

    @Test
    fun quadrantsOfARealTileAddUpToTheWhole() {
        val tile = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))
        for (name in listOf("building", "water", "landuse")) {
            var whole = 0.0
            var parts = 0.0
            var inputPoints = 0
            var quadrantPoints = 0
            for (f in tile[name]!!.features) {
                val g = f.geometry.clip(0, 0, 4096, 4096)
                whole += totalArea(g)
                inputPoints += g.pointCount
                for (child in Fixtures.CENTRE.children()) {
                    val r = TileTransform(Fixtures.CENTRE, child).clipRect(4096)
                    val q = g.clip(r[0], r[1], r[2], r[3])
                    q.forEachPoint { x, y -> assertTrue(x in r[0]..r[2] && y in r[1]..r[3]) }
                    parts += totalArea(q)
                    quadrantPoints += q.pointCount
                }
            }
            assertTrue("$name area $parts vs $whole", kotlin.math.abs(parts - whole) <= whole * 0.002)
            assertTrue(quadrantPoints < inputPoints * 2)
        }
    }

    @Test
    fun overzoomClipKeepsAFractionOfTheTile() {
        val tile = Mvt.decode(Fixtures.bytes(Fixtures.CENTRE))
        val t = TileTransform(Fixtures.CENTRE, TileId(18, 8414 * 16 + 5, 5384 * 16 + 9))
        val r = t.clipRect(4096, buffer = 64)
        assertArrayEquals(intArrayOf(1276, 2300, 1536 + 4, 2560 + 4), r)
        var before = 0
        var after = 0
        for (layer in tile.layers.values) for (f in layer.features) {
            before += f.geometry.pointCount
            after += f.geometry.clip(r[0], r[1], r[2], r[3]).pointCount
        }
        assertTrue("$after of $before points", after < before / 50)
    }
}
