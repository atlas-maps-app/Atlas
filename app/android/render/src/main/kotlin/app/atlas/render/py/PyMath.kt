package app.atlas.render.py

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Python's arithmetic where it differs from Kotlin's: floor `%` and `//`, `round()`, `int()`, `max`/`min` order,
 * `sum()`, and the `math` functions the engine uses. Transcendentals go through [StrictMath] (fdlibm), so every
 * device computes the same bits.
 */
object PyMath {
    const val PI = Math.PI
    const val E = Math.E
    const val TAU = 2 * Math.PI
    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    // ---------------------------------------------------------------- int and float operators

    /** Python `a % b` on floats: the result takes the sign of [b]. */
    fun mod(a: Double, b: Double): Double {
        if (b == 0.0) throw ArithmeticException("float modulo")
        var m = a % b
        if (m != 0.0) {
            if ((b < 0) != (m < 0)) m += b
        } else {
            m = Math.copySign(0.0, b)
        }
        return m
    }

    /** Python `a % b` on ints. */
    fun mod(a: Int, b: Int): Int = Math.floorMod(a, b)

    /** Python `a % b` on ints. */
    fun mod(a: Long, b: Long): Long = Math.floorMod(a, b)

    /** Python `a // b` on floats. */
    fun floorDiv(a: Double, b: Double): Double {
        if (b == 0.0) throw ArithmeticException("float floor division by zero")
        var m = a % b
        var div = (a - m) / b
        if (m != 0.0) {
            if ((b < 0) != (m < 0)) {
                m += b
                div -= 1.0
            }
        }
        val floordiv: Double
        if (div != 0.0) {
            var f = Math.floor(div)
            if (div - f > 0.5) f += 1.0
            floordiv = f
        } else {
            floordiv = Math.copySign(0.0, a / b)
        }
        return floordiv
    }

    /** Python `a // b` on ints. */
    fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)

    /** Python `a // b` on ints. */
    fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)

    /** Python `divmod(a, b)` on floats. */
    fun divmod(a: Double, b: Double): Pair<Double, Double> = floorDiv(a, b) to mod(a, b)

    /** Python `divmod(a, b)` on ints. */
    fun divmod(a: Long, b: Long): Pair<Long, Long> = Math.floorDiv(a, b) to Math.floorMod(a, b)

    /** `math.fmod`: the C remainder, with the sign of [a]. */
    fun fmod(a: Double, b: Double): Double {
        if (b.isInfinite() && a.isFinite()) return a
        if (b == 0.0 || a.isInfinite()) throw ArithmeticException("math domain error")
        return a % b
    }

    /** Python `round(x)`: the nearest int, ties to even. */
    fun round(x: Double): Long {
        if (!x.isFinite()) throw ArithmeticException("cannot convert float to integer")
        val r = Math.rint(x)
        if (Math.abs(r) < 9.2e18) return r.toLong()
        return BigDecimal(r).toBigInteger().longValueExact()
    }

    /** Python `round(x)` for any float: an int of any size. */
    fun roundBig(x: Double): java.math.BigInteger {
        if (!x.isFinite()) throw ArithmeticException("cannot convert float to integer")
        return BigDecimal(Math.rint(x)).toBigInteger()
    }

    /** Python `round(x, n)` for a float: the double nearest to [x] correctly rounded to [n] decimals, ties to even. */
    fun round(x: Double, n: Int): Double {
        if (!x.isFinite() || x == 0.0) return x
        if (n in 0..15) {
            val p = POW10[n]
            val s = x * p
            if (Math.abs(s) < 1e15) {
                val r = Math.floor(s)
                val f = s - r
                val tol = Math.ulp(s) * 2
                if (Math.abs(f - 0.5) > tol) {
                    val k = if (f < 0.5) r else r + 1.0
                    val out = k / p
                    return if (out == 0.0) Math.copySign(0.0, x) else out
                }
            }
        }
        val bd = BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN)
        val out = java.lang.Double.parseDouble(bd.toString())
        return if (out == 0.0) Math.copySign(0.0, x) else out
    }

    /** Python `round(n, k)` for an int with k < 0 (k >= 0 returns n). */
    fun round(n: Long, k: Int): Long {
        if (k >= 0) return n
        return BigDecimal.valueOf(n).setScale(k, RoundingMode.HALF_EVEN).toLong()
    }

    /** Python `int(x)` for a float: truncation toward zero. */
    fun int(x: Double): Long {
        if (!x.isFinite()) throw ArithmeticException("cannot convert float to integer")
        if (Math.abs(x) >= 9.2e18) return BigDecimal(x).toBigInteger().longValueExact()
        return x.toLong()
    }

    /** Python `int(x)` for any float: an int of any size. */
    fun intBig(x: Double): java.math.BigInteger {
        if (!x.isFinite()) throw ArithmeticException("cannot convert float to integer")
        return BigDecimal(x).toBigInteger()
    }

    /** Python `max(a, b)`: the first of equal values, and `b` only when `b > a`. */
    fun max(a: Double, b: Double): Double = if (b > a) b else a

    /** Python `min(a, b)`: the first of equal values, and `b` only when `b < a`. */
    fun min(a: Double, b: Double): Double = if (b < a) b else a

    /** Python `max(a, b, c, …)`. */
    fun max(a: Double, vararg rest: Double): Double {
        var m = a
        for (v in rest) if (v > m) m = v
        return m
    }

    /** Python `min(a, b, c, …)`. */
    fun min(a: Double, vararg rest: Double): Double {
        var m = a
        for (v in rest) if (v < m) m = v
        return m
    }

    // ---------------------------------------------------------------- sum

    /** Python `sum()` of floats (or of ints that are exact in a double), as the configured CPython adds them. */
    fun sum(xs: DoubleArray, n: Int = xs.size): Double {
        if (n == 0) return 0.0
        var f = 0.0 + xs[0]
        if (!PyCompat.compensatedSum) {
            for (i in 1 until n) f += xs[i]
            return f
        }
        var c = 0.0
        for (i in 1 until n) {
            val x = xs[i]
            val t = f + x
            c += if (Math.abs(f) >= Math.abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        if (c != 0.0 && c.isFinite()) f += c
        return f
    }

    /** Python `sum(f(x) for x in items)` of floats. */
    inline fun <T> sumOf(items: Iterable<T>, f: (T) -> Double): Double {
        val acc = Summer()
        for (it in items) acc.add(f(it))
        return acc.value()
    }

    /** An incremental Python `sum()` of floats, for loops that cannot build an array. */
    class Summer {
        private var f = 0.0
        private var c = 0.0
        private var first = true

        /** Adds one float item. */
        fun add(x: Double) {
            if (first) {
                f = 0.0 + x; first = false; return
            }
            if (!PyCompat.compensatedSum) {
                f += x; return
            }
            val t = f + x
            c += if (Math.abs(f) >= Math.abs(x)) (f - t) + x else (x - t) + f
            f = t
        }

        /** The sum so far. */
        fun value(): Double = if (PyCompat.compensatedSum && c != 0.0 && c.isFinite()) f + c else f
    }

    // ---------------------------------------------------------------- math module

    fun sqrt(x: Double): Double {
        if (x < 0) throw ArithmeticException("math domain error")
        return Math.sqrt(x)
    }

    fun sin(x: Double): Double = StrictMath.sin(x)
    fun cos(x: Double): Double = StrictMath.cos(x)
    fun tan(x: Double): Double = StrictMath.tan(x)
    fun asin(x: Double): Double = domain(StrictMath.asin(x), x)
    fun acos(x: Double): Double = domain(StrictMath.acos(x), x)
    fun atan(x: Double): Double = StrictMath.atan(x)
    fun atan2(y: Double, x: Double): Double = StrictMath.atan2(y, x)
    fun exp(x: Double): Double = range(StrictMath.exp(x), x)
    fun sinh(x: Double): Double = range(StrictMath.sinh(x), x)
    fun cosh(x: Double): Double = range(StrictMath.cosh(x), x)

    // math_1's checks: a NaN from a non-NaN is a domain error, an infinity from a finite value a range error.
    private fun domain(r: Double, x: Double): Double {
        if (r.isNaN() && !x.isNaN()) throw ArithmeticException("math domain error")
        return r
    }

    private fun range(r: Double, x: Double): Double {
        if (r.isInfinite() && x.isFinite()) throw ArithmeticException("math range error")
        return r
    }
    fun tanh(x: Double): Double = StrictMath.tanh(x)

    /** `math.log(x)`. */
    fun log(x: Double): Double {
        if (x <= 0) throw ArithmeticException("math domain error")
        return StrictMath.log(x)
    }

    /** `math.log(x, base)`: CPython divides the two natural logs. */
    fun log(x: Double, base: Double): Double = log(x) / log(base)

    /** `math.log10(x)`. */
    fun log10(x: Double): Double {
        if (x <= 0) throw ArithmeticException("math domain error")
        return StrictMath.log10(x)
    }

    /** `math.log2(x)`. */
    fun log2(x: Double): Double {
        if (x <= 0) throw ArithmeticException("math domain error")
        val m = Math.getExponent(x)
        // CPython uses the C library's log2; for exact powers of two it is exact.
        if (x == Math.scalb(1.0, m)) return m.toDouble()
        return StrictMath.log(x) / StrictMath.log(2.0)
    }

    /** `x ** y` on floats (and `math.pow`). */
    fun pow(x: Double, y: Double): Double {
        if (x == 0.0 && y < 0) throw ArithmeticException("0.0 cannot be raised to a negative power")
        if (x < 0 && y != Math.floor(y) && y.isFinite()) throw ArithmeticException("negative number cannot be raised to a fractional power")
        val r = StrictMath.pow(x, y)
        if (r.isInfinite() && x.isFinite() && y.isFinite()) throw ArithmeticException("Numerical result out of range")
        return r
    }

    /** `x ** n` for a float and a small int exponent, as CPython computes it (through the C pow). */
    fun pow(x: Double, n: Int): Double = pow(x, n.toDouble())

    fun floor(x: Double): Double = Math.floor(x)
    fun ceil(x: Double): Double = Math.ceil(x)

    /** `math.floor(x)` as an int. */
    fun floorInt(x: Double): Long = int(Math.floor(x))

    /** `math.ceil(x)` as an int. */
    fun ceilInt(x: Double): Long = int(Math.ceil(x))

    fun radians(x: Double): Double = x * DEG_TO_RAD
    fun degrees(x: Double): Double = x * RAD_TO_DEG
    fun copysign(x: Double, y: Double): Double = Math.copySign(x, y)
    fun fabs(x: Double): Double = Math.abs(x)

    /** `math.isclose(a, b, rel_tol, abs_tol)`. */
    fun isclose(a: Double, b: Double, relTol: Double = 1e-9, absTol: Double = 0.0): Boolean {
        if (a == b) return true
        if (a.isInfinite() || b.isInfinite()) return false
        val diff = Math.abs(b - a)
        return (diff <= Math.abs(relTol * b)) || (diff <= Math.abs(relTol * a)) || (diff <= absTol)
    }

    /** The exponent `e` of `math.frexp(x)` (x = m * 2**e with 0.5 <= |m| < 1); 0 for zero. */
    fun frexpExp(x: Double): Int {
        if (x == 0.0 || !x.isFinite()) return 0
        val g = Math.getExponent(x)
        if (g == java.lang.Double.MIN_EXPONENT - 1) return Math.getExponent(x * TWO_54) + 1 - 54
        return g + 1
    }

    /** `math.frexp(x)`. */
    fun frexp(x: Double): Pair<Double, Int> {
        if (x == 0.0 || !x.isFinite()) return x to 0
        val e = frexpExp(x)
        return Math.scalb(x, -e) to e
    }

    /** `math.ldexp(x, i)`. */
    fun ldexp(x: Double, i: Int): Double = Math.scalb(x, i)

    /** `math.hypot(x, y)`. */
    fun hypot(x: Double, y: Double): Double {
        val a = Math.abs(x)
        val b = Math.abs(y)
        val max = if (b > a) b else a
        val nan = a.isNaN() || b.isNaN()
        return vectorNorm2(a, b, max, nan)
    }

    /** `math.hypot(*coordinates)`. */
    fun hypot(vararg xs: Double): Double {
        val v = DoubleArray(xs.size)
        var max = 0.0
        var nan = false
        for (i in xs.indices) {
            val x = Math.abs(xs[i])
            v[i] = x
            nan = nan || x.isNaN()
            if (x > max) max = x
        }
        return vectorNorm(v, max, nan)
    }

    /** `math.dist(p, q)`. */
    fun dist(p: DoubleArray, q: DoubleArray): Double {
        require(p.size == q.size) { "both points must have the same number of dimensions" }
        val v = DoubleArray(p.size)
        var max = 0.0
        var nan = false
        for (i in p.indices) {
            val x = Math.abs(p[i] - q[i])
            v[i] = x
            nan = nan || x.isNaN()
            if (x > max) max = x
        }
        return vectorNorm(v, max, nan)
    }

    private fun vectorNorm2(a: Double, b: Double, max: Double, nan: Boolean): Double {
        if (max.isInfinite()) return max
        if (nan) return Double.NaN
        if (max == 0.0) return max
        if (!PyCompat.modernHypot) {
            var csum = 1.0
            var frac = 0.0
            var x = a / max
            x *= x
            var old = csum
            csum += x
            frac += (old - csum) + x
            x = b / max
            x *= x
            old = csum
            csum += x
            frac += (old - csum) + x
            return max * Math.sqrt(csum - 1.0 + frac)
        }
        return vectorNorm(doubleArrayOf(a, b), max, false)
    }

    private fun vectorNorm(vec: DoubleArray, max: Double, nan: Boolean): Double {
        val n = vec.size
        if (max.isInfinite()) return max
        if (nan) return Double.NaN
        if (max == 0.0 || n <= 1) return max
        if (!PyCompat.modernHypot) {
            var csum = 1.0
            var frac = 0.0
            for (i in 0 until n) {
                var x = vec[i]
                x /= max
                x *= x
                val old = csum
                csum += x
                frac += (old - csum) + x
            }
            return max * Math.sqrt(csum - 1.0 + frac)
        }
        val maxE = frexpExp(max)
        if (maxE < -1023) {
            for (i in 0 until n) vec[i] /= java.lang.Double.MIN_NORMAL
            return java.lang.Double.MIN_NORMAL * vectorNorm(vec, max / java.lang.Double.MIN_NORMAL, nan)
        }
        val scale = Math.scalb(1.0, -maxE)
        var csum = 1.0
        var frac1 = 0.0
        var frac2 = 0.0
        for (i in 0 until n) {
            var x = vec[i]
            x *= scale
            val prHi = x * x
            val prLo = Math.fma(x, x, -prHi)
            val smHi = csum + prHi
            val smLo = (csum - smHi) + prHi
            csum = smHi
            frac1 += prLo
            frac2 += smLo
        }
        var h = Math.sqrt(csum - 1.0 + (frac1 + frac2))
        val prHi = -h * h
        val prLo = Math.fma(-h, h, -prHi)
        val smHi = csum + prHi
        val smLo = (csum - smHi) + prHi
        csum = smHi
        frac1 += prLo
        frac2 += smLo
        val x = csum - 1.0 + (frac1 + frac2)
        h += x / (2.0 * h)
        return h / scale
    }

    private const val TWO_54 = 18014398509481984.0
    private val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
}
