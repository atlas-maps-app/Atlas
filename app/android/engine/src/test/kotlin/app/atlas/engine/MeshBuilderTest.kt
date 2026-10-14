package app.atlas.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeshBuilderTest {
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

    @Test
    fun trianglesAreCutToTheTile() {
        val mb = MeshBuilder()
        mb.clip = floatArrayOf(0f, 0f, 100f, 100f)
        mb.begin()
        // A square from -50 to 150 in two triangles: only the 100 × 100 inside remains.
        val a = mb.v(-50f, -50f, 0f, Colours.WHITE); val b = mb.v(150f, -50f, 0f, Colours.WHITE)
        val c = mb.v(150f, 150f, 0f, Colours.WHITE); val d = mb.v(-50f, 150f, 0f, Colours.WHITE)
        mb.quad(a, b, c, d)
        mb.end()
        // A face wholly outside vanishes.
        mb.begin()
        mb.tri(mb.v(200f, 200f, 0f, 1), mb.v(300f, 200f, 0f, 1), mb.v(250f, 300f, 0f, 1))
        mb.end()
        val m = mb.build()
        assertEquals(10_000.0, area(m), 1e-2)
        for (v in 0 until m.vertexCount) assertTrue(m.x(v) in 0f..100f && m.y(v) in 0f..100f)
        assertEquals(1, m.faceCount)
    }

    @Test
    fun cutsInterpolateHeightAndColour() {
        val mb = MeshBuilder()
        mb.clip = floatArrayOf(0f, 0f, 10f, 10f)
        mb.begin()
        mb.tri(mb.v(0f, 0f, 0f, Colours.BLACK), mb.v(20f, 0f, 20f, Colours.WHITE), mb.v(0f, 5f, 0f, Colours.BLACK))
        mb.end()
        val m = mb.build()
        val cut = (0 until m.vertexCount).first { abs(m.x(it) - 10f) < 1e-4 && abs(m.y(it)) < 1e-4 }
        assertEquals(10f, m.z(cut), 1e-3f)
        assertEquals(128.0, Colours.red(m.colours[cut]).toDouble(), 1.0)
    }

    @Test
    fun smallDrawnFacesMergeButCulledOnesStayApart() {
        val mb = MeshBuilder()
        repeat(10) {
            mb.begin()
            mb.tri(mb.v(0f, 0f, 0f, 1), mb.v(1f, 0f, 0f, 1), mb.v(0f, 1f, 0f, 1))
            mb.end()
        }
        repeat(3) {
            mb.begin(FaceMesh.CULL, 1f, 0f, 0f)
            mb.tri(mb.v(0f, 0f, 0f, 1), mb.v(0f, 1f, 0f, 1), mb.v(0f, 0f, 1f, 1))
            mb.end()
        }
        val m = mb.build()
        assertEquals(4, m.faceCount)
        assertEquals(13, m.triangleCount)
        assertEquals(30, m.faceVertexStart[1])
        // Bounds per face.
        assertEquals(0f, m.faceBounds[0]); assertEquals(1f, m.faceBounds[3])
    }
}
