package app.atlas.geo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpatialIndexTest {
    private val rnd = Random(42)

    private fun randomBoxes(n: Int): DoubleArray {
        val b = DoubleArray(n * 4)
        for (i in 0 until n) {
            val x = rnd.nextDouble(0.0, 100.0)
            val y = rnd.nextDouble(0.0, 100.0)
            b[4 * i] = x; b[4 * i + 1] = y
            b[4 * i + 2] = x + rnd.nextDouble(0.0, 5.0); b[4 * i + 3] = y + rnd.nextDouble(0.0, 5.0)
        }
        return b
    }

    private fun tree(b: DoubleArray, nodeSize: Int = 16): PackedRTree {
        val t = PackedRTree(b.size / 4, nodeSize)
        for (i in 0 until b.size / 4) assertEquals(i, t.add(b[4 * i], b[4 * i + 1], b[4 * i + 2], b[4 * i + 3]))
        return t.finish()
    }

    @Test fun rtreeSearchMatchesBruteForce() {
        val b = randomBoxes(10_000)
        for (nodeSize in listOf(4, 16, 64)) {
            val t = tree(b, nodeSize)
            repeat(200) {
                val x0 = rnd.nextDouble(-5.0, 100.0); val y0 = rnd.nextDouble(-5.0, 100.0)
                val x1 = x0 + rnd.nextDouble(0.0, 30.0); val y1 = y0 + rnd.nextDouble(0.0, 30.0)
                val expected = (0 until b.size / 4).filter { i ->
                    b[4 * i] <= x1 && b[4 * i + 1] <= y1 && b[4 * i + 2] >= x0 && b[4 * i + 3] >= y0
                }.toIntArray()
                val got = t.search(x0, y0, x1, y1).sortedArray()
                assertArrayEquals(expected, got)
            }
        }
    }

    @Test fun rtreeSearchOfEverythingUsesTheContainedShortcut() {
        val b = randomBoxes(5000)
        val t = tree(b)
        assertEquals(5000, t.search(-1.0, -1.0, 200.0, 200.0).distinct().size)
        assertEquals(0, t.search(300.0, 300.0, 400.0, 400.0).size)
    }

    @Test fun rtreeVisitorStopsEarly() {
        val t = tree(randomBoxes(1000))
        var seen = 0
        t.search(-1.0, -1.0, 200.0, 200.0) { seen++; seen < 7 }
        assertEquals(7, seen)
    }

    @Test fun rtreeNeighborsAreInDistanceOrder() {
        val b = randomBoxes(3000)
        val t = tree(b)
        repeat(50) {
            val x = rnd.nextDouble(0.0, 100.0); val y = rnd.nextDouble(0.0, 100.0)
            fun d(i: Int): Double {
                val dx = maxOf(b[4 * i] - x, x - b[4 * i + 2], 0.0)
                val dy = maxOf(b[4 * i + 1] - y, y - b[4 * i + 3], 0.0)
                return dx * dx + dy * dy
            }
            val got = t.neighbors(x, y, 10)
            assertEquals(10, got.size)
            val expected = (0 until 3000).sortedBy { d(it) }.take(10)
            assertEquals(expected.map { d(it) }, got.map { d(it) })
        }
        val near = t.neighbors(50.0, 50.0, maxDistance = 3.0)
        assertTrue(near.isNotEmpty())
        val one = t.neighbors(50.0, 50.0, 1)
        assertEquals(near[0], one[0])
        val odd = t.neighbors(50.0, 50.0, 5, filter = { it % 2 == 1 })
        assertTrue(odd.all { it % 2 == 1 } && odd.size == 5)
    }

    @Test fun rtreeHandlesTinyAndEmptyIndexes() {
        val empty = PackedRTree(0).finish()
        assertEquals(0, empty.search(0.0, 0.0, 1.0, 1.0).size)
        assertEquals(0, empty.neighbors(0.0, 0.0).size)
        val one = PackedRTree(1).also { it.add(1.0, 1.0) }.finish()
        assertArrayEquals(intArrayOf(0), one.search(0.0, 0.0, 2.0, 2.0))
        assertArrayEquals(intArrayOf(0), one.neighbors(5.0, 5.0))
    }

    @Test fun rtreeIndexesTheRealExtract() {
        // Every way of the Amsterdam extract by its bbox; the Westerkerk's own outline is among its neighbours.
        val ways = Fixtures.ams.filter { it.type == "way" && it.points.isNotEmpty() }
        val t = PackedRTree(ways.size)
        for (w in ways) {
            val bb = BBox.of(w.points)!!
            t.add(bb.west, bb.south, bb.east, bb.north)
        }
        t.finish()
        val p = Fixtures.westerkerk
        val hits = t.search(p.lon, p.lat, p.lon, p.lat).map { ways[it] }
        assertTrue(hits.any { it.tags["name"] == "Westerkerk" })
        val box = BBox.around(p, 150.0)
        val found = t.search(box.west, box.south, box.east, box.north).toSet()
        val brute = ways.indices.filter { BBox.of(ways[it].points)!!.intersects(box) }.toSet()
        assertEquals(brute, found)
    }

    @Test fun pointIndexRangeAndWithinMatchBruteForce() {
        val n = 20_000
        val xs = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        val ys = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        val idx = PointIndex(n, 16)
        for (i in 0 until n) assertEquals(i, idx.add(xs[i], ys[i]))
        idx.finish()
        repeat(100) {
            val x0 = rnd.nextDouble(0.0, 1000.0); val y0 = rnd.nextDouble(0.0, 1000.0)
            val x1 = x0 + rnd.nextDouble(0.0, 100.0); val y1 = y0 + rnd.nextDouble(0.0, 100.0)
            val expected = (0 until n).filter { xs[it] in x0..x1 && ys[it] in y0..y1 }.toIntArray()
            assertArrayEquals(expected, idx.range(x0, y0, x1, y1).sortedArray())
            val r = rnd.nextDouble(0.0, 80.0)
            val within = (0 until n).filter { (xs[it] - x0) * (xs[it] - x0) + (ys[it] - y0) * (ys[it] - y0) <= r * r }.toIntArray()
            assertArrayEquals(within, idx.within(x0, y0, r).sortedArray())
        }
    }

    @Test fun pointIndexNearestIsInDistanceOrder() {
        val n = 5000
        val xs = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        val ys = DoubleArray(n) { rnd.nextDouble(0.0, 1000.0) }
        val idx = PointIndex(n)
        for (i in 0 until n) idx.add(xs[i], ys[i])
        idx.finish()
        repeat(30) {
            val qx = rnd.nextDouble(0.0, 1000.0); val qy = rnd.nextDouble(0.0, 1000.0)
            fun d(i: Int) = (xs[i] - qx) * (xs[i] - qx) + (ys[i] - qy) * (ys[i] - qy)
            val expected = (0 until n).sortedBy { d(it) }.take(25).map { d(it) }
            assertEquals(expected, idx.nearest(qx, qy, 25).map { d(it) })
        }
        val capped = idx.nearest(500.0, 500.0, maxDistance = 20.0)
        assertEquals((0 until n).count { (xs[it] - 500) * (xs[it] - 500) + (ys[it] - 500) * (ys[it] - 500) <= 400 }, capped.size)
        assertTrue(idx.nearest(500.0, 500.0, 3, filter = { it < 100 }).all { it < 100 })
    }

    @Test fun pointIndexFindsRestaurantsAroundTheWesterkerk() {
        // In metres, on the real extract: the restaurants within 300 m, by radius and by nearness.
        val frame = LocalFrame(Fixtures.westerkerk)
        val places = Fixtures.ams.filter { it.type == "node" && it.tags["amenity"] == "restaurant" }
        val idx = PointIndex(places.size)
        for (p in places) idx.add(frame.x(p.points[0].lon), frame.y(p.points[0].lat))
        idx.finish()
        val within = idx.within(0.0, 0.0, 300.0).toSet()
        val brute = places.indices.filter { Geo.distance(Fixtures.westerkerk, places[it].points[0]) <= 300.0 }.toSet()
        // The flat frame and the sphere agree to well under a metre at this range.
        val edge = places.indices.filter { kotlin.math.abs(Geo.distance(Fixtures.westerkerk, places[it].points[0]) - 300) < 1 }
        assertEquals(brute - edge.toSet(), within - edge.toSet())
        assertTrue(within.isNotEmpty())
        val nearest = idx.nearest(0.0, 0.0, 1)[0]
        val bruteNearest = places.indices.minBy { Geo.distance(Fixtures.westerkerk, places[it].points[0]) }
        assertEquals(bruteNearest, nearest)
    }

    @Test fun pointIndexHandlesEmptyAndDuplicates() {
        assertEquals(0, PointIndex(0).finish().within(0.0, 0.0, 10.0).size)
        val idx = PointIndex(100)
        repeat(100) { idx.add(5.0, 5.0) }
        idx.finish()
        assertEquals(100, idx.range(5.0, 5.0, 5.0, 5.0).size)
        assertEquals(100, idx.within(5.0, 5.0, 0.0).size)
    }
}
