package app.atlas.render.py

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Correctly rounded `cos` and `sin`, for the few one-off values that every coordinate depends on (a region's metres per degree of
 * longitude, `KX`). CPython's `math.cos` is the platform's libm: glibc rounds correctly; macOS's libm is faithful but not
 * correctly rounded, and differs from both this and StrictMath in a few per cent of arguments. For every place origin in
 * data/regions this equals macOS's libm, where StrictMath does not (San Gimignano, 43.4677°). Slow (BigDecimal): not for loops.
 */
object CrMath {
    private val MC = MathContext(70)
    private val PI = BigDecimal("3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706798")
    private val TWO_PI = PI.multiply(BigDecimal(2))

    /** cos(x), rounded once from a 70-digit evaluation. */
    fun cos(x: Double): Double = eval(x, true)

    /** sin(x), rounded once from a 70-digit evaluation. */
    fun sin(x: Double): Double = eval(x, false)

    private fun eval(x: Double, cos: Boolean): Double {
        if (!x.isFinite()) return Double.NaN
        val b = BigDecimal(x)
        val k = b.divide(TWO_PI, MC).setScale(0, RoundingMode.HALF_EVEN)
        val r = b.subtract(k.multiply(TWO_PI, MC), MC)
        val r2 = r.multiply(r, MC)
        var term = if (cos) BigDecimal.ONE else r
        var sum = term
        var n = 1
        while (n < 120) {
            val div = if (cos) (2L * n - 1) * (2L * n) else (2L * n) * (2L * n + 1)
            term = term.multiply(r2, MC).negate().divide(BigDecimal.valueOf(div), MC)
            sum = sum.add(term, MC)
            if (term.signum() == 0 || term.abs().compareTo(BigDecimal.ONE.movePointLeft(75)) < 0) break
            n++
        }
        return sum.toDouble()
    }
}
