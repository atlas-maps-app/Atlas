package app.atlas.mapcorecheck.likeness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * D16's continuity metrics, where there is no oracle between views: along a scripted path, the pops (pixels changing by
 * more than [POP] levels between consecutive frames) may not exceed the port's own; the hand-over between two levels of
 * tiles (the block hand-over at 4.5 km) compared after an 8 × 8 box filter (MAD and worst block); band F's colour blend
 * (no step over 2 levels between frames 1 % of zoom apart), also after the box filter.
 */
object Continuity {
    const val POP = 32
    const val BOX = 8

    /** Pixels whose largest channel changes by more than [POP] levels from [a] to [b]. */
    fun pops(a: IntArray, b: IntArray): Int {
        require(a.size == b.size)
        var n = 0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x == y) continue
            if (abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)) > POP || abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)) > POP || abs((x and 0xFF) - (y and 0xFF)) > POP) n++
        }
        return n
    }

    /**
     * The candidate's pops above the port's along one path, the worst step, as a share of the frame: for each pair of
     * consecutive frames, (the candidate's pops − the port's pops) / pixels. At most 0 when the candidate pops no more.
     */
    fun popsOverPort(cand: List<IntArray>, port: List<IntArray>): Double {
        require(cand.size == port.size && cand.size >= 2) { "a path of ${cand.size} candidate and ${port.size} port frames" }
        val pixels = cand[0].size.toDouble()
        var worst = Double.NEGATIVE_INFINITY
        for (k in 1 until cand.size) worst = max(worst, (pops(cand[k - 1], cand[k]) - pops(port[k - 1], port[k])) / pixels)
        return worst
    }

    /**
     * The pops along one path, fed a step at a time in order (only the previous frames are kept): the candidate's and
     * the port's totals and the worst step's excess as a share of the frame ([popsOverPort]'s value).
     */
    class PathPops {
        private var prevCand: IntArray? = null
        private var prevPort: IntArray? = null
        var steps = 0
            private set
        var candPops = 0L
            private set
        var portPops = 0L
            private set
        var worst = Double.NEGATIVE_INFINITY
            private set

        fun add(cand: IntArray, port: IntArray) {
            val pc = prevCand; val pp = prevPort
            if (pc != null && pp != null) {
                val c = pops(pc, cand); val p = pops(pp, port)
                candPops += c; portPops += p
                worst = max(worst, (c - p).toDouble() / cand.size)
            }
            prevCand = cand; prevPort = port; steps++
        }
    }

    /** [px] ([w] × [h]) averaged over [BOX] × [BOX] blocks: three channels per block, row-major; partial blocks left out. */
    fun box(px: IntArray, w: Int, h: Int): DoubleArray {
        val bw = w / BOX; val bh = h / BOX
        val out = DoubleArray(bw * bh * 3)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var r = 0; var g = 0; var b = 0
            for (y in by * BOX until by * BOX + BOX) for (x in bx * BOX until bx * BOX + BOX) {
                val p = px[y * w + x]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val o = (by * bw + bx) * 3
            val n = (BOX * BOX).toDouble()
            out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n
        }
        return out
    }

    /** The hand-over between [a] and [b] after the box filter: the mean absolute channel difference, and the worst block's. */
    fun handOver(a: IntArray, b: IntArray, w: Int, h: Int): Pair<Double, Double> {
        val x = box(a, w, h); val y = box(b, w, h)
        var sum = 0.0; var worst = 0.0
        for (i in x.indices step 3) {
            val d = (abs(x[i] - y[i]) + abs(x[i + 1] - y[i + 1]) + abs(x[i + 2] - y[i + 2])) / 3
            sum += d; worst = max(worst, d)
        }
        return sum / (x.size / 3) to worst
    }

    /** Band F's largest colour step between consecutive [frames] after the box filter: the largest block channel change. */
    fun bandStep(frames: List<IntArray>, w: Int, h: Int): Double {
        var worst = 0.0
        var prev = box(frames[0], w, h)
        for (k in 1 until frames.size) {
            val cur = box(frames[k], w, h)
            for (i in cur.indices) worst = max(worst, abs(cur[i] - prev[i]))
            prev = cur
        }
        return worst
    }
}

class ContinuityTest {
    private val w = 64; private val h = 32

    private fun flat(c: Int) = IntArray(w * h) { c or (0xFF shl 24) }

    @Test
    fun `pops count changes over 32 levels, and the candidate is judged against the port's own`() {
        val a = flat(0x101010); val b = flat(0x101010).also { for (i in 0 until 100) it[i] = 0xFF505050.toInt() }
        assertEquals(100, Continuity.pops(a, b))
        assertEquals(0, Continuity.pops(a, flat(0x303030)))
        // the port pops 100 pixels at the step; a candidate that pops 164 is 64 pixels over
        val c = flat(0x101010).also { for (i in 0 until 164) it[i] = 0xFF505050.toInt() }
        assertEquals(64.0 / (w * h), Continuity.popsOverPort(listOf(a, c), listOf(a, b)), 1e-12)
        assertTrue(Continuity.popsOverPort(listOf(a, b), listOf(a, c)) < 0)
        // fed a step at a time, the same
        val acc = Continuity.PathPops()
        acc.add(a, a); acc.add(c, b)
        assertEquals(64.0 / (w * h), acc.worst, 1e-12); assertEquals(164L, acc.candPops); assertEquals(100L, acc.portPops); assertEquals(2, acc.steps)
    }

    @Test
    fun `the hand-over and the band's steps are seen through an 8 by 8 box`() {
        // a one-pixel checker against its flat mean: invisible through the box
        val checker = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) 0xFF000000.toInt() else 0xFF202020.toInt() }
        val mean = flat(0x101010)
        val (mad, worst) = Continuity.handOver(checker, mean, w, h)
        assertEquals(0.0, mad, 1e-9); assertEquals(0.0, worst, 1e-9)
        // a block 24 levels off in one channel: worst block 8 (a third of 24), the mean over 32 blocks
        val off = flat(0x101010).also { for (y in 0 until 8) for (x in 0 until 8) it[y * w + x] = 0xFF281010.toInt() }
        val (m2, w2) = Continuity.handOver(off, mean, w, h)
        assertEquals(8.0, w2, 1e-9); assertEquals(8.0 / 32, m2, 1e-9)
        assertEquals(24.0, Continuity.bandStep(listOf(mean, off, off), w, h), 1e-9)
    }
}
