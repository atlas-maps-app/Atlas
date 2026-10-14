package app.atlas.render.py

/**
 * Which CPython the port reproduces where CPython versions differ in the last bit.
 *
 * - String hashing: SipHash-2-4 up to 3.10, SipHash-1-3 from 3.11 (with PYTHONHASHSEED=0, the key is all zeros).
 * - `sum()` of floats: plain left-to-right addition up to 3.11, Neumaier compensated summation from 3.12.
 * - `math.hypot`: the 3.9 Neumaier sum of squared ratios, or the 3.12 lossless squaring with a differential correction.
 *
 * Only 3.9 and 3.12 are reproduced exactly; 3.10 and 3.11 get the nearer of the two.
 *
 * The default is CPython 3.9, the Python that made the owner's reference renders. Tests set it from the oracle.
 */
object PyCompat {
    /** CPython's minor version (3.[minor]). */
    @JvmStatic
    var minor: Int = 9
        set(value) {
            field = value
            sipRounds = if (value >= 11) 1 else 2
        }

    /** Compression rounds of SipHash for str and bytes: 2 (SipHash-2-4) or 1 (SipHash-1-3). */
    @JvmStatic
    var sipRounds: Int = 2

    /** True when `sum()` of floats is compensated (3.12+). */
    val compensatedSum: Boolean get() = minor >= 12

    /** True when `math.hypot` uses the 3.12 algorithm. */
    val modernHypot: Boolean get() = minor >= 12
}
