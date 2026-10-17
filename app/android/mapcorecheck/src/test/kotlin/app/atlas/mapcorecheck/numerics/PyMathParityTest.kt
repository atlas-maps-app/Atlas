package app.atlas.mapcorecheck.numerics

import app.atlas.render.py.PyCompat
import app.atlas.render.py.PyMath
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SplittableRandom

/**
 * L0 d (plan-vector D16, K3b): pymath, the core's C++ numerics, against the port's own (`StrictMath`, `PyMath`), bit
 * for bit over 10^7 arguments a function, drawn from the ranges the renders use, the wider ranges around them, the whole
 * double range and the edges of every branch. Python's errors must be the port's: where the port throws, pymath
 * reports an error, and the other way round. `-Datlas.numerics.n` lowers the count for a quick run.
 */
class PyMathParityTest {
    companion object {
        /** Arguments a function (the plan's 10^7). */
        val N: Int = System.getProperty("atlas.numerics.n")?.toInt() ?: 10_000_000

        private const val CHUNK = 1 shl 20
        private const val TWO_PI = 2 * Math.PI

        @AfterClass
        @JvmStatic
        fun restore() {
            PyCompat.minor = 9
        }

        /** Any finite double, its exponent uniform: every magnitude from the subnormals to the largest, either sign. */
        fun magnitude(r: SplittableRandom): Double {
            val e = r.nextInt(2047).toLong()
            val sign = if (r.nextBoolean()) Long.MIN_VALUE else 0L
            return Double.fromBits(sign or (e shl 52) or (r.nextLong() and 0xFFFFFFFFFFFFFL))
        }

        private fun twoTo(k: Int) = Math.scalb(1.0, k)

        /** The edges of fdlibm's branches and Python's checks, each with its neighbours a few ulps away. */
        val EDGES: DoubleArray = run {
            val base = doubleArrayOf(
                0.0, Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, 0.5, 1.0, 2.0, Math.PI / 4, Math.PI / 2, Math.PI,
                3 * Math.PI / 4, 524288 * Math.PI / 2, twoTo(-27), twoTo(-28), twoTo(-29), twoTo(-54), twoTo(-57),
                0.4375, 0.975, 0.3, 0.78125, 0.6744, 1.1875, 2.4375, twoTo(66), 709.782712893384, 745.1332191019411,
                0.5 * Math.log(2.0), 1.5 * Math.log(2.0), 56 * Math.log(2.0), 1e300, Double.MAX_VALUE, twoTo(31),
                twoTo(53), twoTo(64), 0.41422, 0.2929, 1.4142135623730951, 1.7320508075688772, 1024.0, 1075.0,
                twoTo(-1022), twoTo(-1074), 9.2e18, 9.223372036854775807e18, 1e15, 1e16, 2.5, 0.125, 2.675, 1e22, 1e23,
                // sinh's branch points: 22, log(maxdouble), the overflow threshold
                22.0, Double.fromBits(0x40862E4200000000L), Double.fromBits(0x408633CE8FB9F87DL),
                // Math.round's: just below a half, 2^52 - 1/2, 2^52 + 1, 2^63
                0.49999999999999994, 4503599627370495.5, 4503599627370497.0, 9223372036854775807.0,
            )
            val out = ArrayList<Double>()
            for (b in base) {
                var v = b
                repeat(3) { v = Math.nextDown(v) }
                repeat(7) {
                    out += v
                    out += -v
                    v = Math.nextUp(v)
                }
            }
            for (k in 1..2000) {
                val multiple = k * Math.PI / 2
                out += multiple
                out += Math.nextUp(multiple)
                out += Math.nextDown(multiple)
            }
            out += Double.POSITIVE_INFINITY
            out += Double.NEGATIVE_INFINITY
            out += Double.NaN
            out.toDoubleArray()
        }
    }

    /** A share of the arguments, drawn one way. */
    private class Range(val share: Double, val draw: (SplittableRandom) -> Double)

    private fun uniform(share: Double, lo: Double, hi: Double) = Range(share) { r -> lo + (hi - lo) * r.nextDouble() }
    private fun magnitudes(share: Double) = Range(share) { r -> magnitude(r) }
    private fun integers(share: Double, lo: Int, hi: Int) = Range(share) { r -> (lo + r.nextInt(hi - lo + 1)).toDouble() }

    /** Mismatches of one function, with the first few shown. */
    private class Tally(val name: String) {
        var results = 0L
        var mismatches = 0L
        val firsts = ArrayList<String>()
        fun miss(what: String) {
            mismatches++
            if (firsts.size < 8) firsts += what
        }
        fun assertClean() {
            println("pymath parity $name: $results results, $mismatches mismatches")
            assertEquals("$name differs from the port: ${firsts.joinToString("; ")}", 0L, mismatches)
        }
    }

    private fun hex(x: Double) = "0x%016X".format(x.toRawBits())

    private fun same(a: Double, b: Double) = (a.isNaN() && b.isNaN()) || a.toRawBits() == b.toRawBits()

    /** The port's value, or null where it throws (Python raises). */
    private inline fun guarded(f: () -> Double): Double? =
        try { f() } catch (_: ArithmeticException) { null } catch (_: IllegalArgumentException) { null }

    private fun errorName(code: Byte) = NumericsHost.ERRORS[code - 1]

    /**
     * Compares a function of one or two doubles: the port's [port] against the core's [core] (which fills results and
     * error bytes) over N arguments from [xs] (and [ys] for two arguments), the edges first: for two arguments, all
     * pairs of a sample of 64, or the sample against [yEdges] where the second argument is an int.
     */
    private fun compare(
        name: String, seed: Long, xs: List<Range>, ys: List<Range>?, port: (Double, Double) -> Double?,
        core: (DoubleArray, DoubleArray?, DoubleArray, ByteArray) -> Unit, yEdges: DoubleArray? = null,
    ) {
        val t = Tally(name)
        val r = SplittableRandom(seed)
        val ex = ArrayList<Double>()
        val ey = ArrayList<Double>()
        if (ys == null) {
            for (e in EDGES) ex += e
        } else {
            val sample = DoubleArray(64) { EDGES[(it * 97) % EDGES.size] }
            for (a in sample) for (b in yEdges ?: sample) {
                ex += a
                ey += b
            }
        }
        run(t, ex.toDoubleArray(), if (ys == null) null else ey.toDoubleArray(), port, core)
        var remaining = N
        for ((i, rx) in xs.withIndex()) {
            val ry = ys?.get(i % ys.size)
            var count = (N * rx.share).toInt().coerceAtMost(remaining)
            remaining -= count
            while (count > 0) {
                val n = minOf(count, CHUNK)
                val x = DoubleArray(n) { rx.draw(r) }
                val y = if (ry == null) null else DoubleArray(n) { ry.draw(r) }
                run(t, x, y, port, core)
                count -= n
            }
        }
        t.assertClean()
    }

    private fun run(
        t: Tally, x: DoubleArray, y: DoubleArray?, port: (Double, Double) -> Double?,
        core: (DoubleArray, DoubleArray?, DoubleArray, ByteArray) -> Unit,
    ) {
        val got = DoubleArray(x.size)
        val errors = ByteArray(x.size)
        core(x, y, got, errors)
        for (i in x.indices) {
            val b = y?.get(i) ?: 0.0
            val want = port(x[i], b)
            t.results++
            val gotError = errors[i].toInt() != 0
            val at = "(${hex(x[i])}, ${hex(b)})"
            when {
                want == null && !gotError -> t.miss("$at: the port raises, pymath gives ${hex(got[i])}")
                want != null && gotError -> t.miss("$at: the port gives ${hex(want)}, pymath raises ${errorName(errors[i])}")
                want != null && !same(want, got[i]) -> t.miss("$at: the port ${hex(want)}, pymath ${hex(got[i])}")
            }
        }
    }

    private fun strict(fn: Int): (DoubleArray, DoubleArray?, DoubleArray, ByteArray) -> Unit =
        { x, y, out, _ -> NumericsHost.strict(fn, x, y, out) }

    private fun python(fn: Int, minor: Int = 9): (DoubleArray, DoubleArray?, DoubleArray, ByteArray) -> Unit =
        { x, y, out, e -> NumericsHost.python(fn, minor, x, y, out, e) }

    private val trigRanges =
        listOf(uniform(0.4, -TWO_PI, TWO_PI), uniform(0.2, -1e3, 1e3), uniform(0.2, -824000.0, 824000.0), magnitudes(0.2))

    @Test
    fun sinCosTanEqualStrictMath() {
        compare("sin", 1, trigRanges, null, { x, _ -> StrictMath.sin(x) }, strict(0))
        compare("cos", 2, trigRanges, null, { x, _ -> StrictMath.cos(x) }, strict(1))
        compare("tan", 3, trigRanges, null, { x, _ -> StrictMath.tan(x) }, strict(2))
    }

    @Test
    fun inverseTrigEqualsStrictMath() {
        val unit = listOf(uniform(0.6, -1.0, 1.0), uniform(0.2, -1.1, 1.1), magnitudes(0.2))
        compare("asin", 4, unit, null, { x, _ -> StrictMath.asin(x) }, strict(3))
        compare("acos", 5, unit, null, { x, _ -> StrictMath.acos(x) }, strict(4))
        val wide = listOf(uniform(0.4, -10.0, 10.0), uniform(0.3, -1e6, 1e6), magnitudes(0.3))
        compare("atan", 6, wide, null, { x, _ -> StrictMath.atan(x) }, strict(5))
        val plane = listOf(uniform(0.5, -1e3, 1e3), uniform(0.2, -1.0, 1.0), magnitudes(0.3))
        compare("atan2", 7, plane, plane, { y, x -> StrictMath.atan2(y, x) }, strict(6))
    }

    @Test
    fun expAndLogsEqualStrictMath() {
        val exponents = listOf(uniform(0.5, -50.0, 50.0), uniform(0.3, -750.0, 712.0), magnitudes(0.2))
        compare("exp", 8, exponents, null, { x, _ -> StrictMath.exp(x) }, strict(7))
        val positive = listOf(uniform(0.4, 0.0, 1e4), uniform(0.2, 0.5, 2.0), Range(0.4) { r -> Math.abs(magnitude(r)) })
        compare("log", 9, positive, null, { x, _ -> StrictMath.log(x) }, strict(8))
        compare("log10", 10, positive, null, { x, _ -> StrictMath.log10(x) }, strict(9))
        val small = listOf(uniform(0.5, -1.0, 1.0), uniform(0.3, -50.0, 50.0), magnitudes(0.2))
        compare("expm1", 11, small, null, { x, _ -> StrictMath.expm1(x) }, strict(12))
        val aboveMinusOne = listOf(uniform(0.5, -0.5, 1.0), uniform(0.3, -1.0, 1e3), magnitudes(0.2))
        compare("log1p", 12, aboveMinusOne, null, { x, _ -> StrictMath.log1p(x) }, strict(13))
        compare("cbrt", 13, listOf(uniform(0.5, -1e3, 1e3), magnitudes(0.5)), null, { x, _ -> StrictMath.cbrt(x) }, strict(11))
    }

    @Test
    fun sinhEqualsStrictMath() {
        // K3's row edges take |x| <= pi; then the wider ranges, fdlibm's branch points (in the edges) and every magnitude.
        val ranges = listOf(uniform(0.7, -Math.PI, Math.PI), uniform(0.1, -30.0, 30.0), uniform(0.1, -712.0, 712.0), magnitudes(0.1))
        compare("sinh", 15, ranges, null, { x, _ -> StrictMath.sinh(x) }, strict(14))
        val wide = listOf(uniform(0.5, -800.0, 800.0), magnitudes(0.5))
        compare("math.sinh", 16, wide, null, { x, _ -> guarded { PyMath.sinh(x) } }, python(25))
    }

    /** Java's Math.round (the writer's grid and columns, K3's row edges), as Python's round is not. */
    @Test
    fun javaRoundEqualsMathRound() {
        val r = SplittableRandom(80)
        val draws = listOf<(SplittableRandom) -> Double>(
            { magnitude(it) },
            { it.nextDouble(-1e9, 1e9) },
            { it.nextLong(-1L shl 53, 1L shl 53) + 0.5 * it.nextInt(3) },
            { (it.nextInt(2_000_001) - 1_000_000) + 0.5 },
            { (it.nextDouble() * 2 - 1) * Math.scalb(1.0, it.nextInt(130) - 64) },
        )
        val t = Tally("Math.round")
        // the edges, each with a half and a one on both sides, then N drawn
        val edges = DoubleArray(EDGES.size * 5) {
            val e = EDGES[it / 5]
            when (it % 5) {
                0 -> e
                1 -> e + 0.5
                2 -> e - 0.5
                3 -> e + 1
                else -> e - 1
            }
        }
        var left = N
        var x = edges
        while (true) {
            val got = LongArray(x.size)
            NumericsHost.javaRound(x, got)
            for (i in x.indices) {
                t.results++
                val want = Math.round(x[i])
                if (want != got[i]) t.miss("${hex(x[i])}: Java $want, pymath ${got[i]}")
            }
            if (left == 0) break
            val n = minOf(left, CHUNK)
            x = DoubleArray(n) { draws[it % draws.size](r) }
            left -= n
        }
        t.assertClean()
    }

    @Test
    fun powEqualsStrictMath() {
        // The renders raise ratios to 1.3 (haze), halves to small powers, and the rest; around 1 with huge exponents is
        // fdlibm's |y| > 2^31 branch, and negative bases with integral exponents its sign.
        val xs = listOf(
            uniform(0.3, 0.0, 1.0), uniform(0.2, 0.0, 1e3), uniform(0.15, -10.0, 10.0), uniform(0.15, 0.99999, 1.00001),
            magnitudes(0.2),
        )
        val huge = Range(0.15) { r -> (if (r.nextBoolean()) 1 else -1) * Math.pow(10.0, 9 + 11 * r.nextDouble()) }
        val ys = listOf(uniform(0.3, 0.5, 3.0), uniform(0.2, -10.0, 10.0), integers(0.15, -50, 50), huge, magnitudes(0.2))
        compare("pow", 14, xs, ys, { x, y -> StrictMath.pow(x, y) }, strict(10))
    }

    @Test
    fun pythonsMathEqualsPyMath() {
        val wide = listOf(uniform(0.5, -10.0, 100.0), uniform(0.3, -1.5, 1.5), magnitudes(0.2))
        compare("math.sqrt", 21, wide, null, { x, _ -> guarded { PyMath.sqrt(x) } }, python(0))
        compare("math.asin", 22, wide, null, { x, _ -> guarded { PyMath.asin(x) } }, python(1))
        compare("math.acos", 23, wide, null, { x, _ -> guarded { PyMath.acos(x) } }, python(2))
        val exponents = listOf(uniform(0.5, -800.0, 800.0), magnitudes(0.5))
        compare("math.exp", 24, exponents, null, { x, _ -> guarded { PyMath.exp(x) } }, python(3))
        compare("math.log", 25, wide, null, { x, _ -> guarded { PyMath.log(x) } }, python(4))
        compare("math.log10", 26, wide, null, { x, _ -> guarded { PyMath.log10(x) } }, python(5))
        val powers = Range(0.2) { r -> Math.scalb(1.0, r.nextInt(2098) - 1074) }
        val withPowers = listOf(uniform(0.4, -1.0, 100.0), powers, magnitudes(0.4))
        compare("math.log2", 27, withPowers, null, { x, _ -> guarded { PyMath.log2(x) } }, python(6))
        val bases = listOf(uniform(1.0, -1.0, 10.0))
        compare("math.log(x, base)", 28, wide, bases, { x, b -> guarded { PyMath.log(x, b) } }, python(7))
        val xs = listOf(uniform(0.5, -10.0, 10.0), magnitudes(0.5))
        val ys = listOf(uniform(0.5, -5.0, 5.0), integers(0.5, -20, 20))
        compare("x ** y", 29, xs, ys, { x, y -> guarded { PyMath.pow(x, y) } }, python(8))
        compare("math.sin", 30, trigRanges, null, { x, _ -> PyMath.sin(x) }, python(24))
    }

    @Test
    fun pythonsOperatorsEqualPyMath() {
        val xs = listOf(uniform(0.5, -1e3, 1e3), uniform(0.3, -1e-3, 1e-3), magnitudes(0.2))
        val ys = listOf(uniform(0.5, -50.0, 50.0), integers(0.3, -3, 3), magnitudes(0.2))
        compare("x % y", 31, xs, ys, { x, y -> guarded { PyMath.mod(x, y) } }, python(9))
        compare("x // y", 32, xs, ys, { x, y -> guarded { PyMath.floorDiv(x, y) } }, python(10))
        compare("math.fmod", 33, xs, ys, { x, y -> guarded { PyMath.fmod(x, y) } }, python(11))
        compare("max", 34, xs, ys, { x, y -> PyMath.max(x, y) }, python(19))
        compare("min", 35, xs, ys, { x, y -> PyMath.min(x, y) }, python(20))
        compare("math.copysign", 36, xs, ys, { x, y -> PyMath.copysign(x, y) }, python(16))
        // Kotlin's maxOf and minOf: Java's Math.max and min, with the zeros of both signs and NaN on either side
        val zeros = doubleArrayOf(
            0.0, -0.0, Double.NaN, -Double.NaN, 1.0, -1.0, Double.MIN_VALUE, -Double.MIN_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )
        compare("Math.max", 38, xs, ys, { x, y -> Math.max(x, y) }, python(26), zeros)
        compare("Math.min", 39, xs, ys, { x, y -> Math.min(x, y) }, python(27), zeros)
        val ratios = listOf(uniform(1.0, -1e-8, 1e-8))
        compare(
            "math.isclose", 37, xs, ratios, { x, y -> if (PyMath.isclose(x, x * (1 + y))) 1.0 else 0.0 },
            { x, y, out, e -> NumericsHost.python(23, 9, x, DoubleArray(x.size) { x[it] * (1 + y!![it]) }, out, e) },
        )
    }

    @Test
    fun hypotAndConversionsEqualPyMath() {
        val plane = listOf(uniform(0.5, -1e3, 1e3), uniform(0.2, -1.0, 1.0), magnitudes(0.3))
        for (minor in listOf(9, 12)) {
            val port = { x: Double, y: Double ->
                PyCompat.minor = minor
                PyMath.hypot(x, y)
            }
            compare("math.hypot(x, y) 3.$minor", 40L + minor, plane, plane, port, python(12, minor))
        }
        PyCompat.minor = 9
        val ranges = listOf(uniform(0.4, -720.0, 720.0), uniform(0.3, -10.0, 10.0), magnitudes(0.3))
        compare("math.radians", 61, ranges, null, { x, _ -> PyMath.radians(x) }, python(13))
        compare("math.degrees", 62, ranges, null, { x, _ -> PyMath.degrees(x) }, python(14))
        compare("math.floor", 63, ranges, null, { x, _ -> PyMath.floor(x) }, python(17))
        compare("math.ceil", 64, ranges, null, { x, _ -> PyMath.ceil(x) }, python(18))
        compare("math.frexp", 65, ranges, null, { x, _ -> PyMath.frexp(x).first }, python(21))
        val exponents = doubleArrayOf(
            -2.0e9, -2200.0, -2099.0, -1075.0, -1074.0, -1023.0, -1022.0, -1.0, 0.0, 1.0, 1023.0, 1024.0, 1075.0, 2099.0,
            2200.0, 2.0e9,
        )
        val shifts = listOf(integers(1.0, -2200, 2200))
        compare("math.ldexp", 66, ranges, shifts, { x, i -> PyMath.ldexp(x, i.toInt()) }, python(15), exponents)
        // round(x, n): the fast path, its ties, and the exact decimal path at every scale a double has
        val ties = Range(0.2) { r -> ((r.nextInt(2_000_001) - 1_000_000) + 0.5) / Math.pow(10.0, r.nextInt(7).toDouble()) }
        val xs = listOf(uniform(0.4, -1e4, 1e4), ties, uniform(0.2, -1e18, 1e18), magnitudes(0.2))
        val ns = listOf(integers(0.4, -5, 17), integers(0.2, 0, 6), integers(0.2, -20, 20), integers(0.2, -330, 340))
        // digit counts the renders could write, and the extremes of a double's decimals (the port's BigDecimal cannot
        // take a scale near 2^31, where Python simply returns x; no rule asks for one)
        val digits = doubleArrayOf(
            -400.0, -330.0, -309.0, -308.0, -20.0, -2.0, -1.0, 0.0, 1.0, 2.0, 15.0, 16.0, 17.0, 22.0, 23.0, 330.0, 340.0,
            1074.0, 1075.0, 2000.0,
        )
        compare("round(x, n)", 67, xs, ns, { x, n -> PyMath.round(x, n.toInt()) }, python(22), digits)
    }

    /** round(x), int(x), floor, ceil and frexp's exponent, as Python's ints, with their errors. */
    @Test
    fun intConversionsEqualPyMath() {
        val r = SplittableRandom(70)
        val draws = listOf<(SplittableRandom) -> Double>(
            { it.nextDouble(-1e6, 1e6) },
            { (it.nextInt(2_000_001) - 1_000_000) + 0.5 },
            { it.nextDouble(-1e19, 1e19) },
            { magnitude(it) },
        )
        val names = listOf("round(x)", "int(x)", "math.floor(x) as int", "math.ceil(x) as int", "math.frexp(x)[1]")
        val ports = listOf<(Double) -> Long>(
            { PyMath.round(it) }, { PyMath.int(it) }, { PyMath.floorInt(it) }, { PyMath.ceilInt(it) },
            { PyMath.frexpExp(it).toLong() },
        )
        for (fn in ports.indices) {
            val t = Tally(names[fn])
            var left = N
            var x = EDGES.copyOf()
            while (true) {
                val got = LongArray(x.size)
                val errors = ByteArray(x.size)
                NumericsHost.pythonLong(fn, x, got, errors)
                for (i in x.indices) {
                    t.results++
                    val want = try { ports[fn](x[i]) } catch (_: ArithmeticException) { null }
                    val gotError = errors[i].toInt() != 0
                    if ((want == null) != gotError || (want != null && want != got[i])) {
                        val core = if (gotError) errorName(errors[i]) else got[i].toString()
                        t.miss("${hex(x[i])}: the port ${want ?: "raises"}, pymath $core")
                    }
                }
                if (left == 0) break
                val n = minOf(left, CHUNK)
                x = DoubleArray(n) { draws[it % draws.size](r) }
                left -= n
            }
            t.assertClean()
        }
    }

    /** Python's % and // on ints, and round(n, k) with k < 0. */
    @Test
    fun intArithmeticEqualsPyMath() {
        val r = SplittableRandom(71)
        val edges = longArrayOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE, 5_000_000_000_000_000_000L,
            -5_000_000_000_000_000_001L, 15, -25, 150, 250,
        )
        val names = listOf("int %", "int //", "round(n, k)")
        for (fn in 0..2) {
            val t = Tally(names[fn])
            var left = N
            var a = LongArray(edges.size * edges.size) { edges[it / edges.size] }
            var b = LongArray(edges.size * edges.size) { if (fn == 2) (it % edges.size) - 21L else edges[it % edges.size] }
            while (true) {
                val got = LongArray(a.size)
                val errors = ByteArray(a.size)
                NumericsHost.intOps(fn, a, b, got, errors)
                for (i in a.indices) {
                    t.results++
                    val want = try {
                        when (fn) {
                            0 -> PyMath.mod(a[i], b[i])
                            1 -> PyMath.floorDiv(a[i], b[i])
                            else -> PyMath.round(a[i], b[i].toInt())
                        }
                    } catch (_: ArithmeticException) { null }
                    val gotError = errors[i].toInt() != 0
                    if ((want == null) != gotError || (want != null && want != got[i])) {
                        t.miss("(${a[i]}, ${b[i]}): the port ${want ?: "raises"}, pymath ${got[i]}")
                    }
                }
                if (left == 0) break
                val n = minOf(left, CHUNK)
                a = LongArray(n) { if (it % 2 == 0) r.nextLong() else r.nextLong(-100_000, 100_000) }
                b = LongArray(n) {
                    when {
                        fn == 2 -> r.nextLong(-21, 3)
                        it % 3 == 0 -> r.nextLong()
                        else -> r.nextLong(-1000, 1000)
                    }
                }
                left -= n
            }
            t.assertClean()
        }
    }

    /** hypot(*v), dist(p, q), sum(v) and Summer over groups, for CPython 3.9 and 3.12. */
    @Test
    fun vectorsEqualPyMath() {
        val r = SplittableRandom(72)
        val names = listOf("math.hypot(*v)", "math.dist(p, q)", "sum(v)", "sum by Summer")
        for (minor in listOf(9, 12)) {
            for (fn in 0..3) {
                val t = Tally("${names[fn]} 3.$minor")
                var left = N
                while (left > 0) {
                    val groups = minOf(left / 4 + 1, CHUNK / 8)
                    val sizes = IntArray(groups) { if (fn == 2 || fn == 3) r.nextInt(0, 33) else r.nextInt(1, 6) }
                    val total = sizes.sum() * (if (fn == 1) 2 else 1)
                    val values = DoubleArray(total) {
                        when (r.nextInt(4)) {
                            0 -> r.nextDouble(-1e3, 1e3)
                            1 -> r.nextDouble(-1.0, 1.0)
                            2 -> magnitude(r)
                            else -> EDGES[r.nextInt(EDGES.size)]
                        }
                    }
                    val got = DoubleArray(groups)
                    NumericsHost.vectors(fn, minor, values, sizes, got)
                    PyCompat.minor = minor
                    var at = 0
                    for (g in 0 until groups) {
                        val size = sizes[g]
                        val group = values.copyOfRange(at, at + size)
                        val want = when (fn) {
                            0 -> PyMath.hypot(*group)
                            1 -> PyMath.dist(group, values.copyOfRange(at + size, at + 2 * size))
                            2 -> PyMath.sum(group)
                            else -> PyMath.Summer().also { s -> group.forEach { s.add(it) } }.value()
                        }
                        at += if (fn == 1) 2 * size else size
                        t.results++
                        if (!same(want, got[g])) t.miss("group $g of $size: the port ${hex(want)}, pymath ${hex(got[g])}")
                    }
                    PyCompat.minor = 9
                    left -= groups * 4
                }
                t.assertClean()
            }
        }
    }

    /**
     * The planted failure of D16: the platform's own sin in pymath's place must fail L0 d on at least one host. Over the
     * same arguments as the parity test, this host's libm differs from StrictMath (fdlibm) somewhere; CI's planted job
     * builds the core with `ATLAS_PLANTED_PLATFORM_SIN` and requires the whole L0 d to fail.
     */
    @Test
    fun thePlatformsSinFailsTheParityHere() {
        val r = SplittableRandom(1)
        var differ = 0L
        var count = 0L
        var left = N
        while (left > 0) {
            val n = minOf(left, CHUNK)
            val x = DoubleArray(n) { trigRanges[it % trigRanges.size].draw(r) }
            val got = DoubleArray(n)
            NumericsHost.platformSin(x, got)
            for (i in 0 until n) if (!same(StrictMath.sin(x[i]), got[i])) differ++
            count += n
            left -= n
        }
        println("pymath planted: this host's libm sin differs from StrictMath on $differ of $count arguments")
        assertTrue("this host's libm sin equals fdlibm on every argument: the plant would not fail here", differ > 0)
    }

    /**
     * 10^7 sin calls take at most 0.3 s on one core of this Mac (plan-vector K3b) in an optimised build (bench or
     * release, the shipped flags); a debug library and other hosts report the time. `CP/bench/pymath_bench.cpp`
     * measures it with the release flags too.
     */
    @Test
    fun sinIsFastEnough() {
        NumericsHost.timeSin(1_000_000) // warm
        val ns = (0 until 5).minOf { NumericsHost.timeSin(10_000_000) }
        val seconds = ns / 1e9
        val build = NumericsHost.buildInfo()
        val host = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        println("pymath timing: 10^7 sin calls in %.3f s on one core ($host; $build)".format(seconds))
        val optimised = "(release)" in build || "(bench)" in build
        if (optimised && host.startsWith("Mac")) assertTrue("10^7 sin calls took $seconds s", seconds <= 0.3)
    }
}
