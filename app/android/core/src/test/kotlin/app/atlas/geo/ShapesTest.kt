package app.atlas.geo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class ShapesTest {
    private val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

    @Test fun signedAreaFollowsWinding() {
        assertEquals(100.0, Shapes.signedArea(square), 1e-12)
        assertEquals(-100.0, Shapes.signedArea(doubleArrayOf(0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0)), 1e-12)
        // A closed ring (first point repeated) gives the same area.
        assertEquals(100.0, Shapes.signedArea(square + doubleArrayOf(0.0, 0.0)), 1e-12)
        val ring = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0), LatLon(1.0, 0.0))
        assertEquals(1.0, Shapes.signedArea(ring), 1e-12)
    }

    @Test fun geodesicAreaOfADegreeBoxMatchesTheSphere() {
        val r = Geo.EARTH_RADIUS
        val box = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0), LatLon(1.0, 0.0))
        val exact = r * r * (PI / 180) * (sin(PI / 180) - 0.0)
        assertEquals(exact, Shapes.geodesicArea(box), exact * 1e-9)
        val north = listOf(LatLon(52.0, 4.0), LatLon(52.0, 5.0), LatLon(53.0, 5.0), LatLon(53.0, 4.0), LatLon(52.0, 4.0))
        val exactNorth = r * r * (PI / 180) * (sin(53 * PI / 180) - sin(52 * PI / 180))
        assertEquals(exactNorth, Shapes.geodesicArea(north), exactNorth * 1e-9)
        // With a hole of a quarter of the box.
        val hole = listOf(LatLon(52.0, 4.0), LatLon(52.0, 4.5), LatLon(52.5, 4.5), LatLon(52.5, 4.0))
        assertTrue(Shapes.geodesicArea(listOf(north, hole)) < exactNorth * 0.76)
    }

    @Test fun geodesicAreaOfTheWesterkerkAgreesWithLocalMetres() {
        val church = Fixtures.ams.first { it.type == "way" && it.tags["name"] == "Westerkerk" }.points
        val frame = LocalFrame(church[0])
        val xy = DoubleArray(church.size * 2)
        church.forEachIndexed { i, p -> xy[2 * i] = frame.x(p.lon); xy[2 * i + 1] = frame.y(p.lat) }
        val planar = abs(Shapes.signedArea(xy))
        val geodesic = Shapes.geodesicArea(church)
        assertEquals(planar, geodesic, planar * 1e-3)
        // The church fills much of its 60 m × 46 m box.
        assertTrue("area $geodesic", geodesic in 800.0..2760.0)
    }

    @Test fun centroidOfShapesWithAndWithoutHoles() {
        assertArrayEquals(doubleArrayOf(5.0, 5.0), Shapes.centroid(square)!!, 1e-12)
        // An L made of a 2×1 and a 1×1 block.
        val l = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0)
        assertArrayEquals(doubleArrayOf(5.0 / 6, 5.0 / 6), Shapes.centroid(l)!!, 1e-12)
        // A hole in the right half pulls the centroid left, whatever its winding.
        val withHole = square + doubleArrayOf(6.0, 4.0, 8.0, 4.0, 8.0, 6.0, 6.0, 6.0)
        val c = Shapes.centroid(withHole, intArrayOf(4))!!
        assertEquals((5.0 * 100 - 7.0 * 4) / 96, c[0], 1e-12)
        assertEquals(5.0, c[1], 1e-12)
        // No area: the middle of the line; a point: itself.
        assertArrayEquals(doubleArrayOf(1.0, 0.0), Shapes.centroid(doubleArrayOf(0.0, 0.0, 2.0, 0.0))!!, 1e-12)
        assertArrayEquals(doubleArrayOf(3.0, 4.0), Shapes.centroid(doubleArrayOf(3.0, 4.0))!!, 1e-12)
        val church = Fixtures.ams.first { it.type == "way" && it.tags["name"] == "Westerkerk" }.points
        val cc = Shapes.centroid(church)!!
        assertTrue(Shapes.contains(church, cc))
        assertTrue(Geo.distance(cc, Fixtures.westerkerk) < 40)
    }

    @Test fun containsWithHolesAndEdges() {
        val withHole = square + doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
        val holes = intArrayOf(4)
        assertTrue(Shapes.contains(withHole, holes, 1.0, 1.0))
        assertFalse(Shapes.contains(withHole, holes, 5.0, 5.0))
        assertFalse(Shapes.contains(withHole, holes, 11.0, 5.0))
        assertTrue(Shapes.contains(withHole, holes, 0.0, 5.0)) // on the outline
        assertTrue(Shapes.contains(withHole, holes, 10.0, 10.0)) // a corner
        val polygon = listOf(
            listOf(LatLon(52.0, 4.0), LatLon(52.0, 5.0), LatLon(53.0, 5.0), LatLon(53.0, 4.0)),
            listOf(LatLon(52.4, 4.4), LatLon(52.4, 4.6), LatLon(52.6, 4.6), LatLon(52.6, 4.4)),
        )
        assertTrue(Shapes.contains(polygon, LatLon(52.2, 4.2)))
        assertFalse(Shapes.contains(polygon, LatLon(52.5, 4.5)))
    }

    @Test fun douglasPeuckerKeepsTheShape() {
        val line = doubleArrayOf(0.0, 0.0, 1.0, 0.1, 2.0, 0.0, 3.0, 5.0, 4.0, 0.0)
        assertArrayEquals(intArrayOf(0, 2, 3, 4), Shapes.simplifyIndices(line, 0.5))
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 2.0, 0.0, 3.0, 5.0, 4.0, 0.0), Shapes.simplify(line, 0.5), 0.0)
        assertArrayEquals(IntArray(5) { it }, Shapes.simplifyIndices(line, 0.0))
        assertArrayEquals(intArrayOf(0, 9), Shapes.simplifyIndices(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0, 5.0, 6.0, 6.0, 7.0, 7.0, 8.0, 8.0, 9.0, 9.0), 0.01))
        // Every dropped vertex lies within the tolerance of the simplified line.
        val rnd = Random(7)
        val walk = DoubleArray(2000)
        for (i in 1 until 1000) { walk[2 * i] = walk[2 * i - 2] + rnd.nextDouble(); walk[2 * i + 1] = walk[2 * i - 1] + rnd.nextDouble(-1.0, 1.0) }
        val keep = Shapes.simplifyIndices(walk, 2.0)
        assertTrue(keep.size in 10..400)
        for (k in 0 until keep.size - 1) {
            for (i in keep[k] + 1 until keep[k + 1]) {
                val d2 = Shapes.sqSegmentDistance(walk[2 * i], walk[2 * i + 1], walk[2 * keep[k]], walk[2 * keep[k] + 1], walk[2 * keep[k + 1]], walk[2 * keep[k + 1] + 1])
                assertTrue(d2 <= 4.0 + 1e-9)
            }
        }
    }

    @Test fun simplifyInMetresOnARealStreet() {
        val street = Fixtures.ams.filter { it.type == "way" && it.tags["highway"] != null }.maxBy { it.points.size }.points
        val simple = Shapes.simplify(street, 2.0)
        assertTrue(simple.size < street.size)
        assertEquals(street.first(), simple.first())
        assertEquals(street.last(), simple.last())
        val line = Polyline(simple)
        for (p in street) assertTrue(line.snap(p)!!.offset <= 2.05)
    }

    @Test fun sutherlandHodgmanClipsRings() {
        // A square half over the right edge.
        val clipped = Shapes.clipRing(doubleArrayOf(5.0, 2.0, 15.0, 2.0, 15.0, 8.0, 5.0, 8.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(30.0, abs(Shapes.signedArea(clipped)), 1e-12)
        for (i in clipped.indices step 2) assertTrue(clipped[i] in 0.0..10.0 && clipped[i + 1] in 0.0..10.0)
        // Inside: unchanged; outside: empty; a ring covering the box: the box.
        assertEquals(100.0, abs(Shapes.signedArea(Shapes.clipRing(square, -1.0, -1.0, 11.0, 11.0))), 1e-12)
        assertEquals(0, Shapes.clipRing(square, 20.0, 20.0, 30.0, 30.0).size)
        val big = doubleArrayOf(-100.0, -100.0, 100.0, -100.0, 0.0, 100.0)
        assertEquals(100.0, abs(Shapes.signedArea(Shapes.clipRing(big, 0.0, 0.0, 10.0, 10.0))), 1e-9)
        // A triangle whose hypotenuse cuts the box's corner off: 25 less a 3 × 3 half square.
        val tri = Shapes.clipRing(doubleArrayOf(5.0, 5.0, 12.0, 5.0, 5.0, 12.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(20.5, abs(Shapes.signedArea(tri)), 1e-9)
    }

    @Test fun liangBarskyClipsLines() {
        val out = DoubleArray(4)
        assertTrue(Shapes.clipSegment(-5.0, 5.0, 15.0, 5.0, 0.0, 0.0, 10.0, 10.0, out))
        assertArrayEquals(doubleArrayOf(0.0, 5.0, 10.0, 5.0), out, 1e-12)
        assertFalse(Shapes.clipSegment(-5.0, 15.0, 15.0, 15.0, 0.0, 0.0, 10.0, 10.0, out))
        assertTrue(Shapes.clipSegment(2.0, 2.0, 3.0, 3.0, 0.0, 0.0, 10.0, 10.0, out))
        assertArrayEquals(doubleArrayOf(2.0, 2.0, 3.0, 3.0), out, 0.0)
        // A zigzag leaving and re-entering the box gives two parts.
        val parts = Shapes.clipLine(doubleArrayOf(-5.0, 5.0, 5.0, 5.0, 5.0, 15.0, 8.0, 15.0, 8.0, 5.0, 9.0, 5.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(2, parts.size)
        assertArrayEquals(doubleArrayOf(0.0, 5.0, 5.0, 5.0, 5.0, 10.0), parts[0], 1e-12)
        assertArrayEquals(doubleArrayOf(8.0, 10.0, 8.0, 5.0, 9.0, 5.0), parts[1], 1e-12)
        assertEquals(0, Shapes.clipLine(doubleArrayOf(20.0, 20.0, 30.0, 30.0), 0.0, 0.0, 10.0, 10.0).size)
    }

    @Test fun convexHullContainsEverything() {
        val rnd = Random(3)
        val pts = DoubleArray(2000) { rnd.nextDouble(-50.0, 50.0) }
        val hull = Shapes.convexHull(pts)
        assertTrue(Shapes.signedArea(hull) > 0)
        val n = hull.size / 2
        for (i in 0 until n) {
            val ax = hull[2 * i]; val ay = hull[2 * i + 1]
            val bx = hull[2 * ((i + 1) % n)]; val by = hull[2 * ((i + 1) % n) + 1]
            for (k in pts.indices step 2) assertTrue((bx - ax) * (pts[k + 1] - ay) - (by - ay) * (pts[k] - ax) >= -1e-9)
        }
        // Collinear and duplicate points drop out.
        val sq = Shapes.convexHull(doubleArrayOf(0.0, 0.0, 5.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 5.0, 5.0, 0.0, 0.0))
        assertEquals(4, sq.size / 2)
        val geo = Shapes.convexHull(listOf(LatLon(52.0, 4.0), LatLon(52.0, 5.0), LatLon(53.0, 4.5), LatLon(52.3, 4.5)))
        assertEquals(3, geo.size)
    }

    @Test fun corridorAroundAStraightLineIsAStadium() {
        val a = LatLon(52.0, 4.0)
        val b = Geo.destination(a, 90.0, 2000.0)
        val r = 100.0
        val ring = Shapes.corridor(Polyline(listOf(a, b)), r)
        assertTrue(ring.size >= 8)
        assertTrue(Shapes.signedArea(ring) > 0)
        val expected = 2 * r * 2000 + PI * r * r
        assertEquals(expected, Shapes.geodesicArea(ring), expected * 0.02)
        // Points just inside and just outside the width.
        val mid = Geo.midpoint(a, b)
        assertTrue(Shapes.contains(ring, Geo.destination(mid, 0.0, r * 0.9)))
        assertFalse(Shapes.contains(ring, Geo.destination(mid, 0.0, r * 1.1)))
        assertTrue(Shapes.contains(ring, Geo.destination(b, 90.0, r * 0.9)))
        assertFalse(Shapes.contains(ring, Geo.destination(b, 90.0, r * 1.1)))
        for (p in ring) {
            val s = Polyline(listOf(a, b)).snap(p)!!
            val d = if (s.t in 1e-9..(1 - 1e-9)) s.offset else minOf(Geo.distance(p, a), Geo.distance(p, b))
            assertEquals(r, d, r * 0.05)
        }
    }

    @Test fun corridorOfALoopHasAHole() {
        // A square loop 2 km a side, 100 m either side: the middle stays out.
        val o = LatLon(52.0, 4.0)
        val ne = Geo.destination(o, 90.0, 2000.0)
        val loop = listOf(o, ne, Geo.destination(ne, 0.0, 2000.0), Geo.destination(o, 0.0, 2000.0), o)
        val rings = Shapes.corridorRings(Polyline(loop), 100.0)
        assertEquals(2, rings.size)
        assertTrue(Shapes.signedArea(rings[0]) > 0)
        assertTrue(Shapes.signedArea(rings[1]) < 0)
        val centre = Geo.destination(Geo.destination(o, 90.0, 1000.0), 0.0, 1000.0)
        assertFalse(Shapes.contains(rings, centre))
        assertTrue(Shapes.contains(rings, Geo.destination(o, 90.0, 1000.0)))
        // A single point gives a disc.
        val disc = Shapes.corridor(Polyline(listOf(o)), 50.0)
        assertEquals(PI * 50 * 50, Shapes.geodesicArea(disc), PI * 50 * 50 * 0.03)
    }

    @Test fun corridorAlongARealStreetHoldsItsPlaces() {
        val street = Fixtures.ams.filter { it.type == "way" && it.tags["highway"] in setOf("primary", "secondary", "tertiary") }
            .maxBy { Polyline(it.points).length }.points
        val line = Polyline(street)
        val ring = Shapes.corridor(line, 60.0)
        assertTrue(ring.isNotEmpty())
        val pois = Fixtures.ams.filter { it.type == "node" && it.tags.isNotEmpty() }.map { it.points[0] }
        var checked = 0
        for (p in pois) {
            val d = line.snap(p)!!.offset
            if (d < 50) { assertTrue(Shapes.contains(ring, p)); checked++ }
            if (d > 70) assertFalse(Shapes.contains(ring, p))
        }
        assertTrue(checked > 10)
    }

    @Test fun nearestApproachOfTwoLines() {
        val a = Polyline(listOf(LatLon(52.0, 4.0), LatLon(52.0, 4.01)))
        val crossing = Polyline(listOf(LatLon(51.99, 4.005), LatLon(52.01, 4.005)))
        val gap = Shapes.nearest(a, crossing)!!
        assertEquals(0.0, gap.distance, 0.0)
        assertEquals(4.005, gap.onA.lon, 1e-9)
        val parallel = Polyline(listOf(LatLon(52.001, 3.99), LatLon(52.001, 4.02)))
        assertEquals(111.2, Shapes.distance(a, parallel), 0.5)
        // An L-shaped line whose corner comes nearest.
        val corner = Polyline(listOf(LatLon(52.01, 4.02), LatLon(52.002, 4.02), LatLon(52.002, 4.03)))
        val g = Shapes.nearest(a, corner)!!
        assertTrue(g.segmentB in 0..1)
        assertEquals(0.0, Geo.distance(g.onB, LatLon(52.002, 4.02)), 0.01)
        assertEquals(Geo.distance(LatLon(52.0, 4.01), LatLon(52.002, 4.02)), g.distance, 0.5)
        assertEquals(null, Shapes.nearest(Polyline(emptyList()), a))
    }

    @Test fun nearestBetweenRealStreetsMatchesBruteForce() {
        val streets = Fixtures.ams.filter { it.type == "way" && it.tags["highway"] != null && it.points.size > 3 }
        val a = Polyline(streets[0].points)
        for (s in streets.drop(1).take(40)) {
            val b = Polyline(s.points)
            val brute = b.points.minOf { a.snap(it)!!.offset }.coerceAtMost(a.points.minOf { b.snap(it)!!.offset })
            val got = Shapes.distance(a, b)
            // Lines that do not cross come nearest at a vertex of one of them, which is what the brute force measures.
            if (got > 0) assertEquals(brute, got, maxOf(0.05, brute * 1e-3))
        }
    }

    @Test fun labelPointSitsDeepInside() {
        val p = Shapes.labelPoint(square)
        assertEquals(5.0, p[0], 0.5)
        assertEquals(5.0, p[1], 0.5)
        assertEquals(5.0, p[2], 0.5)
        // A U shape: the label avoids the notch, where the centroid falls.
        val u = doubleArrayOf(0.0, 0.0, 30.0, 0.0, 30.0, 30.0, 20.0, 30.0, 20.0, 10.0, 10.0, 10.0, 10.0, 30.0, 0.0, 30.0)
        val c = Shapes.centroid(u)!!
        assertFalse(Shapes.contains(u, null, c[0], c[1]))
        val lp = Shapes.labelPoint(u, precision = 0.1)
        assertTrue(Shapes.contains(u, null, lp[0], lp[1]))
        assertTrue(lp[2] >= 4.9)
        val church = Fixtures.ams.first { it.type == "way" && it.tags["name"] == "Westerkerk" }.points
        val frame = LocalFrame(church[0])
        val xy = DoubleArray(church.size * 2)
        church.forEachIndexed { i, q -> xy[2 * i] = frame.x(q.lon); xy[2 * i + 1] = frame.y(q.lat) }
        val label = Shapes.labelPoint(xy, precision = 0.5)
        assertNotNull(label)
        assertTrue(label[2] > 5.0)
    }
}

class PreparedPolygonTest {
    @Test fun agreesWithTheSimpleTestOnACorridor() {
        val street = Fixtures.ams.filter { it.type == "way" && it.tags["highway"] != null }.maxBy { Polyline(it.points).length }.points
        val rings = Shapes.corridorRings(Polyline(street), 40.0)
        val prepared = PreparedPolygon(rings)
        val rnd = Random(9)
        val box = prepared.bbox.buffer(50.0)
        var inside = 0
        repeat(20_000) {
            val p = LatLon(rnd.nextDouble(box.south, box.north), rnd.nextDouble(box.west, box.east))
            val expected = Shapes.contains(rings, p)
            assertEquals(expected, prepared.contains(p))
            if (expected) inside++
        }
        assertTrue(inside > 100)
        for (p in Fixtures.ams.filter { it.type == "node" }.map { it.points[0] }) assertEquals(Shapes.contains(rings, p), prepared.contains(p))
    }

    @Test fun holesEdgesAndEmpty() {
        val outer = listOf(LatLon(0.0, 0.0), LatLon(0.0, 10.0), LatLon(10.0, 10.0), LatLon(10.0, 0.0))
        val hole = listOf(LatLon(4.0, 4.0), LatLon(4.0, 6.0), LatLon(6.0, 6.0), LatLon(6.0, 4.0))
        val p = PreparedPolygon(listOf(outer, hole))
        assertTrue(p.contains(1.0, 1.0))
        assertFalse(p.contains(5.0, 5.0))
        assertTrue(p.contains(0.0, 5.0))
        assertFalse(p.contains(11.0, 5.0))
        assertFalse(PreparedPolygon(emptyList()).contains(0.0, 0.0))
    }
}
