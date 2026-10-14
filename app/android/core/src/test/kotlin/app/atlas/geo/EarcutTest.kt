package app.atlas.geo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class EarcutTest {
    @Test fun indices2d() {
        assertArrayEquals(intArrayOf(1, 0, 3, 1, 3, 2), Earcut.triangulate(doubleArrayOf(10.0, 0.0, 0.0, 50.0, 60.0, 60.0, 70.0, 10.0)))
    }

    @Test fun indices3d() {
        val data = doubleArrayOf(10.0, 0.0, 0.0, 0.0, 50.0, 0.0, 60.0, 60.0, 0.0, 70.0, 10.0, 0.0)
        assertArrayEquals(intArrayOf(1, 0, 3, 1, 3, 2), Earcut.triangulate(data, null, 3))
    }

    @Test fun emptyAndDegenerateInput() {
        assertEquals(0, Earcut.triangulate(DoubleArray(0)).size)
        assertEquals(0, Earcut.triangulate(doubleArrayOf(1.0, 1.0, 2.0, 2.0)).size)
    }

    @Test fun flattenSkipsEmptyRings() {
        val outer = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
        val hole = doubleArrayOf(2.0, 2.0, 4.0, 2.0, 3.0, 4.0)
        val a = Earcut.flatten(listOf(outer, hole, DoubleArray(0)))
        val b = Earcut.flatten(listOf(outer, hole))
        assertArrayEquals(a.holes, b.holes)
        assertArrayEquals(intArrayOf(4), b.holes)
        val c = Earcut.flatten(listOf(outer, DoubleArray(0)))
        assertArrayEquals(Earcut.triangulate(outer), Earcut.triangulate(c.vertices, c.holes))
    }

    @Test fun infiniteLoopCaseTerminates() {
        Earcut.triangulate(doubleArrayOf(1.0, 2.0, 2.0, 2.0, 1.0, 2.0, 1.0, 1.0, 1.0, 2.0, 4.0, 1.0, 5.0, 1.0, 3.0, 2.0, 4.0, 2.0, 4.0, 1.0), intArrayOf(5))
    }

    @Test fun largeConvexPolygonIsFast() {
        val n = 100_000
        val v = DoubleArray(2 * n)
        for (i in 0 until n) { v[2 * i] = cos(2 * PI * i / n); v[2 * i + 1] = sin(2 * PI * i / n) }
        val started = System.nanoTime()
        val t = Earcut.triangulate(v)
        val ms = (System.nanoTime() - started) / 1e6
        assertEquals(n - 2, t.size / 3)
        assertTrue(Earcut.deviation(v, null, 2, t) < 1e-12)
        assertTrue("took $ms ms", ms < 3000)
    }

    @Test fun collinearPolygonHasZeroDeviation() {
        val v = doubleArrayOf(0.1, 0.2, 1.3, 2.6, 2.5, 5.0, 3.7, 7.4)
        val t = Earcut.triangulate(v)
        assertEquals(0, t.size)
        assertEquals(0.0, Earcut.deviation(v, null, 2, t), 0.0)
    }

    @Test fun refineImprovesABadQuadDiagonal() {
        val v = doubleArrayOf(0.0, 0.0, 3.0, 0.0, 10.0, 1.0, 0.0, 2.0)
        val t = intArrayOf(2, 3, 0, 2, 0, 1)
        val before = perimeter(t, v)
        Earcut.refine(t, v)
        assertArrayEquals(intArrayOf(2, 3, 1, 3, 0, 1), t)
        assertTrue(perimeter(t, v) < before * 0.7)
        assertEquals(0.0, Earcut.deviation(v, null, 2, t), 0.0)
    }

    @Test fun refineLeavesAGoodDiagonal() {
        val v = doubleArrayOf(0.0, 0.0, 5.0, 0.0, 4.0, 1.0, 0.0, 4.0)
        val t = intArrayOf(2, 3, 0, 2, 0, 1)
        Earcut.refine(t, v)
        assertArrayEquals(intArrayOf(2, 3, 0, 2, 0, 1), t)
    }

    @Test fun refineKeepsAConcavePolygonAndTerminatesOnCocircularPoints() {
        val v = doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 1.0, 1.0, 1.0, 1.0, 4.0, 0.0, 4.0)
        val t = Earcut.triangulate(v)
        val before = perimeter(t, v)
        Earcut.refine(t, v)
        assertTrue(perimeter(t, v) < before * 0.9)
        assertEquals(0.0, Earcut.deviation(v, null, 2, t), 0.0)

        val c = doubleArrayOf(
            127.65906365022843, 9.336137742499535, 124.21725103117963, 30.888097161477972,
            91.35514946628345, 89.65621376119454, 40.10446780041529, 121.5550560957686,
            -110.83205604043928, 64.03323632184248, -127.20394987965459, -14.253249980770189,
            61.074962259031416, -112.48932831632469, 127.37846573978545, -12.598669206638515,
            127.77010311801033, -7.668164657400608,
        )
        val tc = Earcut.triangulate(c)
        val n = tc.size
        Earcut.refine(tc, c)
        assertEquals(n, tc.size)
        assertTrue(Earcut.deviation(c, null, 2, tc) < 1e-15)
    }

    @Test fun blockIndexKeepsHolesOnACollinearGrid() {
        // Earcut issue 183: a collinear-rich outline with several holes must not drop a hole.
        val n = 30
        val outer = ArrayList<Double>()
        for (x in 0..n) { outer += x.toDouble(); outer += 0.0 }
        for (y in 1..n) { outer += n.toDouble(); outer += y.toDouble() }
        for (x in n - 1 downTo 0) { outer += x.toDouble(); outer += n.toDouble() }
        for (y in n - 1 downTo 1) { outer += 0.0; outer += y.toDouble() }
        fun rect(x0: Double, y0: Double, w: Double, h: Double) = doubleArrayOf(x0, y0, x0, y0 + h, x0 + w, y0 + h, x0 + w, y0)
        val flat = Earcut.flatten(listOf(outer.toDoubleArray(), rect(5.0, 5.0, 2.0, 4.0), rect(2.0, 23.0, 1.0, 1.0)))
        val t = Earcut.triangulate(flat.vertices, flat.holes)
        assertTrue(Earcut.deviation(flat.vertices, flat.holes, 2, t) < 1e-9)
    }

    @Test fun triangulatesEveryBuildingOfTheAmsterdamExtract() {
        // The real data the engine will see: each building outline in local metres.
        val frame = LocalFrame(Fixtures.westerkerk)
        var buildings = 0
        var worst = 0.0
        var triangles = 0
        for (e in Fixtures.ams) {
            if (e.type != "way" || e.tags["building"] == null || e.points.size < 4) continue
            val pts = if (e.points.first() == e.points.last()) e.points.dropLast(1) else e.points
            val v = DoubleArray(pts.size * 2)
            for (i in pts.indices) { v[2 * i] = frame.x(pts[i].lon); v[2 * i + 1] = frame.y(pts[i].lat) }
            val t = Earcut.triangulate(v)
            worst = maxOf(worst, Earcut.deviation(v, null, 2, t))
            triangles += t.size / 3
            buildings++
        }
        assertTrue("buildings $buildings", buildings > 2000)
        assertTrue("worst deviation $worst", worst < 1e-9)
        assertTrue(triangles > buildings * 2)
    }

    private fun perimeter(t: IntArray, v: DoubleArray): Double {
        var p = 0.0
        for (i in t.indices step 3) {
            val a = t[i]; val b = t[i + 1]; val c = t[i + 2]
            p += hypot(v[2 * a] - v[2 * b], v[2 * a + 1] - v[2 * b + 1]) +
                hypot(v[2 * b] - v[2 * c], v[2 * b + 1] - v[2 * c + 1]) +
                hypot(v[2 * c] - v[2 * a], v[2 * c + 1] - v[2 * a + 1])
        }
        return p
    }
}
