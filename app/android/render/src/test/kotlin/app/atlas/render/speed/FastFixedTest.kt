package app.atlas.render.speed

import app.atlas.render.Oracle
import app.atlas.render.py.PyFmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.SplittableRandom

/**
 * PyFmt's fast `'%.Nf'` (appendFixed and `fixed`, and `pf` through appendFixed) against the exact decimal value rounded half to
 * even (CPython's rule) and against the port's earlier `fixed`, on 10 M samples for `%.1f` with the ties and near-ties that decide
 * it, and on fewer for the other precisions; and against a fresh CPython on 400 000 of the same kinds of samples.
 */
class FastFixedTest {
    /** The exact rule: the binary value of [x] as a decimal, rounded half to even. */
    private fun exact(x: Double, prec: Int): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val body = BigDecimal(Math.abs(x)).setScale(prec, RoundingMode.HALF_EVEN).toPlainString()
        return if (neg) "-$body" else body
    }

    /** PyFmt.fixed before the fast path (commit 5b1b224): a guarded double decision, else BigDecimal. */
    private fun earlier(x: Double, prec: Int): String {
        if (!x.isFinite()) return exact(x, prec)
        val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
        val a = Math.abs(x)
        if (prec <= 15) {
            val s = a * Math.pow(10.0, prec.toDouble())
            if (s < 1e15) {
                val r = Math.floor(s); val f = s - r
                if (Math.abs(f - 0.5) > Math.ulp(s) * 2) {
                    val k = (if (f < 0.5) r else r + 1.0).toLong()
                    val ds = k.toString()
                    val sb = StringBuilder()
                    if (neg) sb.append('-')
                    if (prec == 0) return sb.append(ds).toString()
                    if (ds.length <= prec) {
                        sb.append("0."); repeat(prec - ds.length) { sb.append('0') }; sb.append(ds)
                    } else sb.append(ds, 0, ds.length - prec).append('.').append(ds, ds.length - prec, ds.length)
                    return sb.toString()
                }
            }
        }
        return exact(x, prec)
    }

    /** One sample of the kind [kind] (0–7) for precision [prec]: random magnitudes, exact ties and their neighbours, edges. */
    private fun sample(rnd: SplittableRandom, kind: Int, prec: Int): Double {
        val p = Math.pow(10.0, prec.toDouble())
        val sign = if (rnd.nextBoolean()) 1.0 else -1.0
        return sign * when (kind) {
            0 -> rnd.nextDouble() * 2000.0 // map coordinates
            1 -> Math.pow(10.0, rnd.nextDouble(-20.0, 16.0)) * rnd.nextDouble() // any magnitude, up to past 2^52 / 10^p
            2 -> (rnd.nextLong(0, 4_000_000_000L) + 0.5) / p // decimal ties, as near as doubles get to them
            3 -> { val t = (rnd.nextLong(0, 4_000_000_000L) + 0.5) / p; var v = t; repeat(rnd.nextInt(1, 4)) { v = if (rnd.nextBoolean()) Math.nextUp(v) else Math.nextDown(v) }; v }
            4 -> rnd.nextLong(0, 1L shl 40) * 0.25 / (1L shl rnd.nextInt(0, 12)) // exact binary ties: k/2^n
            5 -> java.lang.Double.longBitsToDouble(rnd.nextLong() and Long.MAX_VALUE) // any bits (NaN and the infinities too)
            6 -> { val e = 4503599627370496.0 / p; e * rnd.nextDouble(0.999999, 1.000001) } // around the fast path's bound
            else -> java.lang.Double.longBitsToDouble(rnd.nextLong(0, 1L shl 54)) // subnormals and tiny numbers
        }
    }

    @Test
    fun `fast percent one f equals the exact rule and the earlier fixed on 10 M samples`() {
        val rnd = SplittableRandom(20261016)
        val sb = StringBuilder()
        var ties = 0; var nearTies = 0
        val half = BigDecimal("0.5")
        for (i in 0 until 10_000_000) {
            val x = sample(rnd, i and 7, 1)
            sb.setLength(0)
            val fast = PyFmt.appendFixed(sb, x, 1).toString()
            val fixed = PyFmt.fixed(x, 1)
            val want = exact(x, 1)
            if (fast != want) assertEquals("appendFixed(${java.lang.Double.toHexString(x)}, 1)", want, fast)
            if (fixed != want) assertEquals("fixed(${java.lang.Double.toHexString(x)}, 1)", want, fixed)
            val before = earlier(x, 1)
            if (before != want) assertEquals("earlier fixed(${java.lang.Double.toHexString(x)}, 1)", before, want)
            if (x.isFinite() && Math.abs(x) * 10 < 4.5e15) {
                val s = BigDecimal(Math.abs(x)).multiply(BigDecimal.TEN)
                if (s.subtract(BigDecimal(s.toBigInteger())).compareTo(half) == 0) ties++
                // the cases a plain double decision cannot settle: the scaled value within two ulps of a half
                val d = Math.abs(x) * 10.0
                if (Math.abs(d - Math.floor(d) - 0.5) <= Math.ulp(d) * 2) nearTies++
            }
        }
        println("%.1f: 10 000 000 samples, appendFixed and fixed equal the exact rule and the earlier fixed ($ties exact ties, $nearTies within two ulps of one)")
        check(ties > 100_000) { "too few exact ties: $ties" }
        check(nearTies > 100_000) { "too few near ties: $nearTies" }
    }

    @Test
    fun `fast fixed equals the exact rule for every precision, and pf and fixed go through it`() {
        val rnd = SplittableRandom(7)
        val sb = StringBuilder()
        for (prec in 0..17) {
            for (i in 0 until 200_000) {
                val x = sample(rnd, i and 7, prec)
                sb.setLength(0)
                val want = exact(x, prec)
                assertEquals("appendFixed(${java.lang.Double.toHexString(x)}, $prec)", want, PyFmt.appendFixed(sb, x, prec).toString())
                assertEquals("fixed(${java.lang.Double.toHexString(x)}, $prec)", want, PyFmt.fixed(x, prec))
                if (i % 50 == 0) assertEquals("pf %.${prec}f", "<$want>", PyFmt.pf("<%.${prec}f>", x))
            }
        }
        // the flags keep their own path
        assertEquals("  2.5", PyFmt.pf("%5.1f", 2.45)); assertEquals("+2.4", PyFmt.pf("%+.1f", 2.4)); assertEquals("2.", PyFmt.pf("%#.0f", 2.4))
        assertEquals(" 2.0", PyFmt.pf("% .1f", 2.0)); assertEquals("-0.0", PyFmt.pf("%.1f", -0.04)); assertEquals("0.2 0.8", PyFmt.pf("%.1f %.1f", 0.25, 0.75))
        assertEquals("3.0", PyFmt.pf("%.1f", 3)); assertEquals("nan -inf", PyFmt.pf("%.1f %.2f", Double.NaN, Double.NEGATIVE_INFINITY))
        assertEquals("-9223372036854775808 7 -3", PyFmt.pf("%d %d %d", Long.MIN_VALUE, 7, -3L)); assertEquals("  7 007 1", PyFmt.pf("%3d %03d %d", 7, 7, true))
    }

    @Test
    fun `fast fixed equals a fresh CPython`() {
        val rnd = SplittableRandom(99)
        val dir = File(Oracle.out, "fixed").also { it.mkdirs() }
        val inp = File(dir, "in.txt"); val out = File(dir, "out.txt")
        val xs = ArrayList<Pair<Int, Double>>()
        for (i in 0 until 400_000) {
            val prec = if (i % 4 == 0) (i / 4) % 7 else 1
            xs.add(prec to sample(rnd, i and 7, prec))
        }
        inp.bufferedWriter().use { w -> for ((p, x) in xs) w.write("$p ${java.lang.Double.doubleToRawLongBits(x)}\n") }
        Oracle.run("fixed_oracle.py", inp.path, out.path)
        val py = out.readLines()
        assertEquals(xs.size, py.size)
        val sb = StringBuilder()
        for ((i, px) in xs.withIndex()) {
            sb.setLength(0)
            assertEquals("'%.${px.first}f' % ${java.lang.Double.toHexString(px.second)}", py[i], PyFmt.appendFixed(sb, px.second, px.first).toString())
        }
        inp.delete(); out.delete()
        println("%.Nf: ${xs.size} samples equal CPython")
    }
}
