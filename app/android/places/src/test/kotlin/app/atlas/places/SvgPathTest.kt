package app.atlas.places

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SvgPathTest {
    /** Writes what it receives as text, rounded to 3 decimals: "M0,0 L10,0 C… Z". */
    private class Recorder : PathSink {
        val out = ArrayList<String>()
        private fun f(v: Float) = "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
        override fun moveTo(x: Float, y: Float) { out += "M${f(x)},${f(y)}" }
        override fun lineTo(x: Float, y: Float) { out += "L${f(x)},${f(y)}" }
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) { out += "Q${f(x1)},${f(y1)} ${f(x)},${f(y)}" }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            out += "C${f(x1)},${f(y1)} ${f(x2)},${f(y2)} ${f(x)},${f(y)}"
        }
        override fun close() { out += "Z" }
    }

    private fun replay(d: String): List<String> = Recorder().also { SvgPath.parse(d).replay(it) }.out

    @Test
    fun linesAbsoluteAndRelative() {
        assertEquals(listOf("M1,2", "L3,4", "L5,4", "L5,9", "Z"), replay("M1 2 L3 4 H5 V9 Z"))
        assertEquals(listOf("M1,2", "L4,6", "L6,6", "L6,3", "Z"), replay("m1 2 l3 4 h2 v-3 z"))
        // implicit repeats: pairs after a moveto are linetos, relative after a relative moveto
        assertEquals(listOf("M0,0", "L10,0", "L10,10"), replay("M0 0 10 0 10 10"))
        assertEquals(listOf("M1,1", "L2,2", "L3,3"), replay("m1 1 1 1 1 1"))
        assertEquals(listOf("M0,0", "L1,0", "L2,0", "L3,0"), replay("M0 0h1 1 1"))
    }

    @Test
    fun compactNumbers() {
        assertEquals(listOf("M0,-1.5", "L0.5,0.5"), replay("M0-1.5L.5.5"))
        assertEquals(listOf("M100,0.01", "L-200,3"), replay("M1e2,1E-2L-2e+2,3"))
        assertEquals(listOf("M1,2", "L3,4"), replay("  M 1 , 2 \n L\t3,4  "))
    }

    @Test
    fun closeThenContinue() {
        // after Z the pen returns to the subpath start, and drawing on starts a new subpath there
        assertEquals(listOf("M5,5", "L10,5", "Z", "M5,5", "L5,10"), replay("M5 5 L10 5 Z L5 10"))
        assertEquals(listOf("M5,5", "L10,5", "Z", "M6,6", "L7,6"), replay("M5 5 L10 5 z m1 1 h1"))
    }

    @Test
    fun cubicsAndSmoothCubics() {
        assertEquals(listOf("M0,0", "C1,2 3,4 5,6"), replay("M0 0 C1 2 3 4 5 6"))
        assertEquals(listOf("M1,1", "C2,3 4,5 6,7"), replay("M1 1 c1 2 3 4 5 6"))
        // S reflects the previous second control point about the current point
        assertEquals(listOf("M0,0", "C0,10 10,10 10,0", "C10,-10 20,-10 20,0"), replay("M0 0 C0 10 10 10 10 0 S20 -10 20 0"))
        // without a cubic before, S's first control point is the current point
        assertEquals(listOf("M0,0", "C0,0 5,5 10,0"), replay("M0 0 S5 5 10 0"))
        assertEquals(listOf("M0,0", "C0,10 10,10 10,0", "C10,-10 20,-10 20,0"), replay("M0 0 c0 10 10 10 10 0 s10 -10 10 0"))
    }

    @Test
    fun quadraticsAndSmoothQuadratics() {
        assertEquals(listOf("M0,0", "Q5,10 10,0", "Q15,-10 20,0", "Q25,10 30,0"), replay("M0 0 Q5 10 10 0 T20 0 T30 0"))
        assertEquals(listOf("M0,0", "Q5,10 10,0", "Q15,-10 20,0"), replay("M0 0 q5 10 10 0 t10 0"))
        assertEquals(listOf("M0,0", "Q0,0 10,0"), replay("M0 0 T10 0"))
    }

    @Test
    fun arcsBecomeCubicsOnTheCircle() {
        // a half circle of radius 5 from (0,0) to (10,0): two quarter cubics, ending exactly on the endpoint
        val c = SvgPath.parse("M0 0 A5 5 0 0 1 10 0")
        assertArrayEquals(byteArrayOf(PathCommands.MOVE, PathCommands.CUBIC, PathCommands.CUBIC), c.verbs)
        assertEquals(10f, c.coords[c.coords.size - 2], 1e-5f)
        assertEquals(0f, c.coords[c.coords.size - 1], 1e-5f)
        // the curve's midpoint at t = 0.5 lies on the circle around (5,0)
        for (seg in 0 until 2) {
            val o = 2 + seg * 6
            val x0 = if (seg == 0) 0f else c.coords[o - 2]
            val y0 = if (seg == 0) 0f else c.coords[o - 1]
            val mx = (x0 + 3 * c.coords[o] + 3 * c.coords[o + 2] + c.coords[o + 4]) / 8
            val my = (y0 + 3 * c.coords[o + 1] + 3 * c.coords[o + 3] + c.coords[o + 5]) / 8
            assertEquals(5.0, hypot(mx - 5.0, my.toDouble()), 0.01)
        }
        // sweep 1 goes through negative y (clockwise on screen), sweep 0 through positive y
        assertTrue(SvgPath.parse("M0 0 A5 5 0 0 1 10 0").bounds()!![1] < -4f)
        assertTrue(SvgPath.parse("M0 0 A5 5 0 0 0 10 0").bounds()!![3] > 4f)
    }

    @Test
    fun arcFlagsRadiiAndEdgeCases() {
        // the large-arc flag picks the long way round: three quarters of a circle needs three cubics
        val large = SvgPath.parse("M0 5 A5 5 0 1 1 5 0")
        assertEquals(4, large.size)
        val small = SvgPath.parse("M0 5 A5 5 0 0 1 5 0")
        assertEquals(2, small.size)
        // flags written without separators, relative endpoint
        assertEquals(SvgPath.parse("M0 0 a5 5 0 0 1 10 0").coords.toList(), SvgPath.parse("M0 0a5,5,0,0,1,10,0").coords.toList())
        assertEquals(SvgPath.parse("M0 0 a5 5 0 0 1 10 0").coords.toList(), SvgPath.parse("M0 0a5 5 0 0110 0").coords.toList())
        // radii too small are scaled up until the arc fits: still a half circle
        val scaled = SvgPath.parse("M0 0 A1 1 0 0 1 10 0")
        assertEquals(10f, scaled.coords[scaled.coords.size - 2], 1e-4f)
        assertTrue(scaled.bounds()!![1] < -4.9f)
        // zero radius is a straight line; a zero-length arc draws nothing
        assertEquals(listOf("M0,0", "L10,0"), replay("M0 0 A0 5 0 0 1 10 0"))
        assertEquals(listOf("M0,0"), replay("M0 0 A5 5 0 0 1 0 0"))
        // a rotated ellipse still ends on its endpoint
        val rotated = SvgPath.parse("M0 0 A10 5 30 0 1 12 7")
        assertEquals(12f, rotated.coords[rotated.coords.size - 2], 1e-4f)
        assertEquals(7f, rotated.coords[rotated.coords.size - 1], 1e-4f)
        // a full circle drawn as two arcs, the way the build writes circles
        val circle = SvgPath.parse("M2 7a5 5 0 1 0 10 0a5 5 0 1 0-10 0z")
        val b = circle.bounds()!!
        assertEquals(2f, b[0], 0.01f)
        assertEquals(12f, b[2], 0.01f)
        assertTrue(abs(b[1] - 2f) < 0.6f && abs(b[3] - 12f) < 0.6f)
    }

    @Test
    fun replayScalesAndMoves() {
        val r = Recorder()
        SvgPath.parse("M1 1 L2 3").replay(r, scale = 2f, dx = 10f, dy = 20f)
        assertEquals(listOf("M12,22", "L14,26"), r.out)
    }

    @Test
    fun badDataFailsWithPosition() {
        for (bad in listOf("L1 2", "M1", "M1 2 L3", "M0 0 A5 5 0 2 1 10 0", "M0 0 X1 2", "1 2", "M0 0 Z 3 4")) {
            assertNull(bad, SvgPath.parseOrNull(bad))
        }
        try {
            SvgPath.parse("M0 0 L1 2 L3 x")
            fail()
        } catch (e: SvgPathException) {
            assertEquals(13, e.index)
        }
        assertEquals(0, SvgPath.parse("").size)
    }

    @Test
    fun everyBundledIconParses() {
        var paths = 0
        for (set in listOf("symbols", "ui", "logos")) {
            for (id in Symbols.ids(set)) {
                val icon = Symbols.icon(id) ?: error("no icon $id")
                for (p in icon.paths) {
                    val c = p.commands
                    assertTrue(id, c.size > 0)
                    paths++
                }
            }
        }
        assertTrue(paths > 1000)
    }
}
