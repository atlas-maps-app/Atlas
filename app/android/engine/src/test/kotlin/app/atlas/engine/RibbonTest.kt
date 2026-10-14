package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class RibbonTest {
    private fun area(m: FaceMesh): Double {
        var a = 0.0
        var k = 0
        while (k < m.indexCount) {
            val i = m.indices[k]; val j = m.indices[k + 1]; val l = m.indices[k + 2]
            a += abs((m.x(j) - m.x(i)) * (m.y(l) - m.y(i)) - (m.x(l) - m.x(i)) * (m.y(j) - m.y(i))) / 2.0
            k += 3
        }
        return a
    }

    /** Union area by rasterising the triangles on a fine grid (overlaps count once). */
    private fun coverage(m: FaceMesh, x0: Double, y0: Double, x1: Double, y1: Double, step: Double): Double {
        var hits = 0
        var y = y0 + step / 2
        while (y < y1) {
            var x = x0 + step / 2
            while (x < x1) {
                var k = 0
                while (k < m.indexCount) {
                    val i = m.indices[k]; val j = m.indices[k + 1]; val l = m.indices[k + 2]
                    if (inTri(x, y, m.x(i).toDouble(), m.y(i).toDouble(), m.x(j).toDouble(), m.y(j).toDouble(), m.x(l).toDouble(), m.y(l).toDouble())) { hits++; break }
                    k += 3
                }
                x += step
            }
            y += step
        }
        return hits * step * step
    }

    private fun inTri(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        return !((d1 < 0 || d2 < 0 || d3 < 0) && (d1 > 0 || d2 > 0 || d3 > 0))
    }

    @Test
    fun straightRibbonIsARectangleWithRoundEnds() {
        val mb = MeshBuilder()
        mb.begin()
        Ribbon.add(mb, floatArrayOf(0f, 0f, 100f, 0f), 2, 4f, Colours.WHITE)
        mb.end()
        val m = mb.build()
        // 100 × 8 body plus two half discs of radius 4 (a 6-step fan is a little under the circle).
        val expected = 800 + PI * 16
        assertEquals(expected, area(m), expected * 0.03)
        // Every vertex lies within the half-width of the centreline (segment distance).
        for (v in 0 until m.vertexCount) {
            val d = Rings.segmentDistance(m.x(v).toDouble(), m.y(v).toDouble(), 0.0, 0.0, 100.0, 0.0)
            assertTrue(d <= 4.0 + 1e-4)
        }
        val n = MeshBuilder().also { it.begin(); Ribbon.add(it, floatArrayOf(0f, 0f, 100f, 0f), 2, 4f, 1, capStart = false, capEnd = false); it.end() }.build()
        assertEquals(800.0, area(n), 1e-3)
    }

    @Test
    fun bendsAreFilledOnTheOutside() {
        // An L: east 50 m, then south 50 m; a round join fills the outer corner.
        val mb = MeshBuilder()
        mb.begin()
        Ribbon.add(mb, floatArrayOf(0f, 0f, 50f, 0f, 50f, 50f), 3, 5f, Colours.WHITE, capStart = false, capEnd = false)
        mb.end()
        val m = mb.build()
        // Union: two 50 × 10 arms overlapping in a 5 × 5 square, plus a quarter disc filling the outer corner.
        val union = coverage(m, -6.0, -6.0, 56.0, 56.0, 0.25)
        val expected = 50 * 10 + 50 * 10 - 25.0 + PI * 25 / 4
        assertEquals(expected, union, 12.0)
        // The outer corner point (55, -5) sits outside the round join, (53, -3) inside it.
        assertTrue(coverage(m, 54.9, -5.1, 55.1, -4.9, 0.1) < 0.01)
        assertTrue(coverage(m, 52.9, -3.1, 53.1, -2.9, 0.1) > 0.03)
    }

    @Test
    fun heightsFollowTheLine() {
        val mb = MeshBuilder()
        mb.begin()
        Ribbon.add(mb, floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f), 3, 2f, 1, z = floatArrayOf(0f, 5f, 0f), capStart = false, capEnd = false)
        mb.end()
        val m = mb.build()
        var maxZ = 0f
        for (v in 0 until m.vertexCount) {
            maxZ = maxOf(maxZ, m.z(v))
            if (abs(m.x(v) - 10f) < 1e-3) assertEquals(5f, m.z(v), 1e-4f)
        }
        assertEquals(5f, maxZ, 1e-5f)
    }

    @Test
    fun arrowsSitAlongTheLine() {
        val mb = MeshBuilder()
        mb.begin()
        Ribbon.arrows(mb, floatArrayOf(0f, 0f, 100f, 0f), 2, 25f, 3f, 1, 0f, reverse = false)
        mb.end()
        val m = mb.build()
        // 100 m at 25 m spacing from 12.5 m: four arrows, two triangles each.
        assertEquals(8, m.triangleCount)
        for (v in 0 until m.vertexCount) assertTrue(abs(m.y(v)) <= 1.0f)
        assertEquals(100.0, Ribbon.length(floatArrayOf(0f, 0f, 60f, 80f), 2).toDouble(), 1e-4)
    }
}
