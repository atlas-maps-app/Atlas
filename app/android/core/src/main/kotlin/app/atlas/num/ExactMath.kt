package app.atlas.num

/**
 * `Math.fma` without `Math.fma`: the same correctly rounded results, fast on every runtime. ART computes `Math.fma`
 * through BigDecimal where it has no intrinsic (the x86 emulator, measured 15 October: a 320 px turbulence probe took
 * 23 s instead of milliseconds), and the engine and the SVG filters call it millions of times a frame.
 */
object ExactMath {
    /**
     * `a * b + c` rounded once, as `Math.fma(a, b, c)`. The product of two floats is exact in a double; the sum is
     * rounded to odd in double precision (53 bits ≥ 2 × 24 + 2), so the final rounding to float is the correct one
     * (Boldo and Melquiond, "When double rounding is odd", 2005).
     */
    fun fma(a: Float, b: Float, c: Float): Float {
        val p = a.toDouble() * b.toDouble()
        val cd = c.toDouble()
        val s = p + cd
        if (!s.isFinite()) return s.toFloat()
        // The rounding error of s, exactly (Knuth's TwoSum).
        val bv = s - p
        val e = (p - (s - bv)) + (cd - bv)
        if (e == 0.0 || (java.lang.Double.doubleToRawLongBits(s) and 1L) != 0L) return s.toFloat()
        // Rounded and even: the odd neighbour on the side of the exact sum.
        return (if (e > 0) Math.nextUp(s) else Math.nextDown(s)).toFloat()
    }

    /**
     * The exact rounding error of the product `p = a * b` (that is `Math.fma(a, b, -p)`), by Dekker's split. Exact
     * while |a|, |b| < 2^996 and the partial products do not underflow, which holds for the scaled values CPython's
     * `vector_norm` multiplies.
     */
    fun productError(a: Double, b: Double, p: Double): Double {
        var t = SPLIT * a
        val ah = t - (t - a)
        val al = a - ah
        t = SPLIT * b
        val bh = t - (t - b)
        val bl = b - bh
        return ((ah * bh - p) + ah * bl + al * bh) + al * bl
    }

    private const val SPLIT = 134217729.0 // 2^27 + 1
}
