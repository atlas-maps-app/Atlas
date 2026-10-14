package app.atlas.packer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NodeLocationsTest {
    @Test
    fun sortedIdsAcrossManyPagesAndBigGaps() {
        val n = NodeLocations()
        val ids = LongArray(300_000)
        var id = 1L
        val rnd = Random(7)
        for (i in ids.indices) {
            // mostly small steps, sometimes a jump past what an Int offset can hold
            id += if (i % 50_000 == 49_999) 5_000_000_000L else 1L + rnd.nextInt(90)
            ids[i] = id
            n.put(id, i, -i)
        }
        assertTrue(n.isSorted)
        assertEquals(300_000L, n.size)
        for (i in ids.indices step 997) {
            val at = n.indexOf(ids[i])
            assertTrue("id ${ids[i]}", at >= 0)
            assertEquals(i, n.lat(at))
            assertEquals(-i, n.lon(at))
        }
        assertEquals(-1L, n.indexOf(0))
        val gap = (1 until 1000).first { ids[it + 1] - ids[it] > 1 }
        assertEquals(-1L, n.indexOf(ids[gap] + 1))
        assertEquals(-1L, n.indexOf(id + 1))
        assertEquals(-1L, n.indexOf(ids[49_999] - 1))
        // twelve bytes a node, in whole pages (a jump starts a new page)
        assertEquals(0L, n.bytes % (NodeLocations.PAGE * 12L))
    }

    @Test
    fun aRepeatedIdReplacesItsPosition() {
        val n = NodeLocations()
        n.put(5, 1, 1)
        n.put(5, 2, 3)
        assertEquals(1L, n.size)
        assertEquals(2, n.lat(n.indexOf(5)))
        assertEquals(3, n.lon(n.indexOf(5)))
        assertTrue(n.isSorted)
        n.clear()
        assertEquals(0L, n.size)
        assertEquals(-1L, n.indexOf(5))
        n.put(2, 7, 8)
        assertEquals(7, n.lat(n.indexOf(2)))
    }

    @Test
    fun theSmallestIdIsANodeLikeAnyOther() {
        // Long.MIN_VALUE was also the "nothing yet" mark: a first node with that id must not look like a repeat
        val n = NodeLocations()
        n.put(Long.MIN_VALUE, 1, 2)
        n.put(Long.MIN_VALUE, 3, 4)
        n.put(-5, 5, 6)
        assertTrue(n.isSorted)
        assertEquals(2L, n.size)
        assertEquals(3, n.lat(n.indexOf(Long.MIN_VALUE)))
        assertEquals(6, n.lon(n.indexOf(-5)))
    }

    @Test
    fun outOfOrderIdsStillAnswer() {
        val n = NodeLocations()
        val ids = (1L..100_000L).shuffled(Random(3))
        for (id in ids) n.put(id * 3 - 50, (id % 1000).toInt(), -(id % 777).toInt())
        assertFalse(n.isSorted)
        for (id in 1L..100_000L step 371) {
            val at = n.indexOf(id * 3 - 50)
            assertEquals((id % 1000).toInt(), n.lat(at))
            assertEquals(-(id % 777).toInt(), n.lon(at))
        }
        assertEquals(-1L, n.indexOf(2))
        n.put(-49, 9, 9)
        assertEquals(9, n.lat(n.indexOf(-49)))
    }

    @Test
    fun longLongMapGrowsAndFinds() {
        val m = LongLongMap(4)
        for (k in -5000L..5000L) m.put(k * 1_000_003, k)
        assertEquals(10_001, m.size)
        assertEquals(42L, m.get(42 * 1_000_003))
        assertEquals(-1L, m.get(7))
        assertTrue(0L in m)
        m.put(0, 99)
        assertEquals(99L, m.get(0))
    }
}

class ShapesTest {
    private fun e7(vararg latLon: Double) = IntArray(latLon.size) { Math.round(latLon[it] * 1e7).toInt() }

    @Test
    fun aSquareIsCentredAndBoxed() {
        val sq = e7(52.0, 4.0, 52.0, 4.01, 52.01, 4.01, 52.01, 4.0, 52.0, 4.0)
        val c = Shapes.wayCentre(sq, 5)
        assertEquals(52.005, c.lat, 1e-6)
        assertEquals(4.005, c.lon, 1e-6)
        val b = Shapes.box(sq, 5)
        assertEquals(4.0, b.west, 1e-9)
        assertEquals(52.01, b.north, 1e-9)
    }

    @Test
    fun anLShapeIsWeighedByArea() {
        // an L of three unit squares (in 0.001°): (0,0)-(2,0)-(2,1)-(1,1)-(1,2)-(0,2); centroid (5/6, 5/6) on the equator
        val d = 0.001
        val l = e7(0.0, 0.0, 0.0, 2 * d, d, 2 * d, d, d, 2 * d, d, 2 * d, 0.0, 0.0, 0.0)
        val c = Shapes.ringCentroid(l, 7)!!
        assertEquals(5.0 / 6 * d, c.lat, 1e-8)
        assertEquals(5.0 / 6 * d, c.lon, 1e-8)
        // clockwise gives the same
        val cw = IntArray(l.size).also { r -> for (i in 0 until 7) { r[2 * i] = l[2 * (6 - i)]; r[2 * i + 1] = l[2 * (6 - i) + 1] } }
        val c2 = Shapes.ringCentroid(cw, 7)!!
        assertEquals(c.lat, c2.lat, 1e-9)
        assertEquals(c.lon, c2.lon, 1e-9)
    }

    @Test
    fun anOpenLineIsPinnedHalfwayAlong() {
        // 1 km east then 3 km north: halfway is 1 km up the second leg
        val k = Math.cos(Math.toRadians(60.0))
        val line = e7(60.0, 10.0, 60.0, 10.0 + 0.01 / k, 60.03, 10.0 + 0.01 / k)
        val m = Shapes.wayCentre(line, 3)
        assertEquals(60.01, m.lat, 1e-6)
        assertEquals(10.0 + 0.01 / k, m.lon, 1e-6)
        val one = Shapes.midpoint(e7(1.0, 2.0), 1)
        assertEquals(1.0, one.lat, 1e-9)
    }

    @Test
    fun aRingWithoutAreaFallsBackToItsCorners() {
        val flat = e7(1.0, 1.0, 1.0, 2.0, 1.0, 3.0, 1.0, 1.0)
        assertNull(Shapes.ringCentroid(flat, 4))
        val c = Shapes.wayCentre(flat, 4)
        assertEquals(1.0, c.lat, 1e-9)
        assertEquals(2.0, c.lon, 1e-9)
    }

    @Test
    fun longitudesFarApartDoNotOverflow() {
        val wide = e7(0.0, -179.0, 0.0, 179.0)
        assertEquals(0.0, Shapes.midpoint(wide, 2).lon, 1e-6)
    }
}

class RingsTest {
    private fun e7(vararg latLon: Double) = IntArray(latLon.size) { Math.round(latLon[it] * 1e7).toInt() }

    @Test
    fun waysJoinIntoARingWhateverTheirDirection() {
        val r = Rings()
        r.add(e7(0.0, 0.0, 0.0, 0.002), isInner = false)
        r.add(e7(0.002, 0.002, 0.0, 0.002), isInner = false) // reversed
        r.add(e7(0.002, 0.002, 0.002, 0.0, 0.0, 0.0), isInner = false)
        val (outer, inner) = r.rings()
        assertEquals(1, outer.size)
        assertTrue(inner.isEmpty())
        val c = r.centroid()!!
        assertEquals(0.001, c.lat, 1e-9)
        assertEquals(0.001, c.lon, 1e-9)
    }

    @Test
    fun holesPullTheCentreAway() {
        val r = Rings()
        r.add(e7(0.0, 0.0, 0.0, 0.004, 0.004, 0.004, 0.004, 0.0, 0.0, 0.0), isInner = false)
        // a hole in the east half
        r.add(e7(0.001, 0.002, 0.001, 0.004 - 1e-4, 0.003, 0.004 - 1e-4, 0.003, 0.002, 0.001, 0.002), isInner = true)
        val c = r.centroid()!!
        assertEquals(0.002, c.lat, 1e-7)
        assertTrue("west of the middle: ${c.lon}", c.lon < 0.002)
    }

    @Test
    fun ringsAreWeighedByArea() {
        val r = Rings()
        r.add(e7(0.0, 0.0, 0.0, 0.003, 0.003, 0.003, 0.003, 0.0, 0.0, 0.0), isInner = false) // 9 units at 0.0015
        r.add(e7(0.0, 0.01, 0.0, 0.011, 0.001, 0.011, 0.001, 0.01, 0.0, 0.01), isInner = false) // 1 unit at 0.0105
        val c = r.centroid()!!
        assertEquals((9 * 0.0015 + 0.0105) / 10, c.lon, 1e-8)
    }

    @Test
    fun anUnclosedChainHasNoCentroid() {
        val r = Rings()
        r.add(e7(0.0, 0.0, 0.0, 0.002), isInner = false)
        r.add(e7(0.0, 0.002, 0.002, 0.002), isInner = false)
        assertNull(r.centroid())
    }
}
