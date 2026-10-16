package app.atlas.num

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.SplittableRandom

/** [ExactMath] against the JVM's `Math.fma`, bit for bit: random values of every scale, and the hard cases. */
class ExactMathTest {
    private fun same(a: Float, b: Float, c: Float) {
        val want = Math.fma(a, b, c)
        val got = ExactMath.fma(a, b, c)
        // NaN's payload bits mean nothing; any NaN is the answer.
        if (want.isNaN()) return assertEquals("fma($a, $b, $c)", true, got.isNaN())
        assertEquals("fma($a, $b, $c)", java.lang.Float.floatToRawIntBits(want), java.lang.Float.floatToRawIntBits(got))
    }

    @Test
    fun floatFmaIsMathFmaBitForBit() {
        val r = SplittableRandom(15)
        fun any(): Float = when (r.nextInt(4)) {
            0 -> Float.fromBits(r.nextInt())                          // any bit pattern: subnormals, huge, NaN, infinities
            1 -> (r.nextDouble() * 2 - 1).toFloat()                    // the filters' range
            2 -> (r.nextDouble() * 8192).toFloat()                     // turbulence coordinates
            else -> Math.scalb((r.nextDouble() + 1).toFloat(), r.nextInt(-140, 120))
        }
        repeat(3_000_000) { same(any(), any(), any()) }
        // Products that cancel the addend almost exactly, where double rounding would go wrong.
        repeat(1_000_000) {
            val a = any()
            val b = any()
            val p = a.toDouble() * b.toDouble()
            same(a, b, (-p).toFloat())
            same(a, b, Math.nextUp((-p).toFloat()))
            same(a, b, Math.nextDown((-p).toFloat()))
        }
        for (v in floatArrayOf(0f, -0f, 1f, -1f, Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NaN, 0.5f, 3f))
            for (w in floatArrayOf(0f, -0f, 1f, -1f, Float.MIN_VALUE, Float.MAX_VALUE, -2f, 0.1f))
                for (u in floatArrayOf(0f, -0f, 1f, Float.MIN_VALUE, -Float.MAX_VALUE, Float.NEGATIVE_INFINITY)) same(v, w, u)
    }

    @Test
    fun productErrorIsMathFma() {
        val r = SplittableRandom(16)
        repeat(3_000_000) {
            // The scaled values CPython's vector_norm squares: [0.5, 1) times a power of two near 1, either sign.
            val a = Math.scalb(r.nextDouble() * 0.5 + 0.5, r.nextInt(-60, 3)) * (if (r.nextBoolean()) 1 else -1)
            val b = if (r.nextBoolean()) a else Math.scalb(r.nextDouble() + 0.5, r.nextInt(-60, 3))
            val p = a * b
            assertEquals(java.lang.Double.doubleToRawLongBits(Math.fma(a, b, -p)), java.lang.Double.doubleToRawLongBits(ExactMath.productError(a, b, p)))
        }
    }
}
