package app.atlas.mapcorecheck.numerics

import java.io.File

/**
 * pymath's entry points in the core's host build (`CP/jni/TestNumerics.cpp`), loaded from the library that
 * `atlas.mapcore.host` names (built without sanitizers). Errors come back as a byte per result: 0 for a value, else the
 * index of pymath's `Error` plus one.
 */
internal object NumericsHost {
    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
    }

    /** The result the check folds for a Python error (a NaN pattern no result has). */
    const val ERROR_MARK = -0x1A1A1A1A1A1A1A1BL // 0xE5E5E5E5E5E5E5E5

    /** pymath's `Error` names, in order (an error byte is its index + 1). */
    val ERRORS = listOf(
        "float_modulo", "float_floor_division", "integer_division", "domain", "range", "not_finite", "too_large",
        "zero_to_negative_power", "negative_to_fractional_power", "power_out_of_range", "dimensions", "not_hashable",
        "empty_sequence", "empty_range", "invalid_text",
    )

    /**
     * StrictMath: [fn] 0..14 is sin, cos, tan, asin, acos, atan, atan2(x, y), exp, log, log10, pow(x, y), cbrt, expm1,
     * log1p and sinh.
     */
    @JvmStatic external fun strict(fn: Int, x: DoubleArray, y: DoubleArray?, out: DoubleArray)

    /** Python's math with its errors; [fn] as `TestNumerics.cpp` lists them; [minor] is CPython's 3.minor. */
    @JvmStatic external fun python(fn: Int, minor: Int, x: DoubleArray, y: DoubleArray?, out: DoubleArray, errors: ByteArray)

    /** round(x), int(x), floor, ceil and frexp's exponent as ints. */
    @JvmStatic external fun pythonLong(fn: Int, x: DoubleArray, out: LongArray, errors: ByteArray)

    /** Java's Math.round(double), the Kotlin writer's rounding. */
    @JvmStatic external fun javaRound(x: DoubleArray, out: LongArray)

    /** a % b, a // b and round(a, b) on ints. */
    @JvmStatic external fun intOps(fn: Int, a: LongArray, b: LongArray, out: LongArray, errors: ByteArray)

    /** hypot(*v), dist(p, q), sum(v) and sum by Summer over consecutive groups of [sizes] values. */
    @JvmStatic external fun vectors(fn: Int, minor: Int, values: DoubleArray, sizes: IntArray, out: DoubleArray)

    /** Random streams from int (0), unsigned (1) and float (2, its bits) seeds over an op script; returns the count. */
    @JvmStatic external fun randomStreams(kinds: IntArray, seeds: LongArray, ops: IntArray, args: LongArray, out: LongArray): Int

    /** random() results from big int seeds, each [counts] 32-bit words of [words], least significant first. */
    @JvmStatic external fun randomBig(words: IntArray, counts: IntArray, draws: Int, out: LongArray)

    @JvmStatic external fun hashLongs(values: LongArray, out: LongArray)

    @JvmStatic external fun hashDoubles(values: DoubleArray, out: LongArray, errors: ByteArray)

    /** hash() of each text as a str and of its UTF-8 as bytes. */
    @JvmStatic external fun hashTexts(utf8: Array<ByteArray>, minor: Int, strs: LongArray, bytes: LongArray, errors: ByteArray)

    /** hash() of tuples: [sizes] items each, every item of [kinds] (0 int, 1 float, 2 str, 3 bool) taken in order. */
    @JvmStatic external fun hashTuples(
        sizes: IntArray, kinds: IntArray, ints: LongArray, floats: DoubleArray, texts: Array<ByteArray>, minor: Int,
        out: LongArray, errors: ByteArray,
    )

    @JvmStatic external fun checkParts(): LongArray

    @JvmStatic external fun checkNames(): String

    @JvmStatic external fun checkTotal(): Long

    @JvmStatic external fun checkExpected(): Long

    @JvmStatic external fun checkLine(): String

    /** This host's own libm sin, the planted failure L0 d must catch. */
    @JvmStatic external fun platformSin(x: DoubleArray, out: DoubleArray)

    /** The library's build line: `atlasmap <version> (<debug|bench|release>); <compiler>; ...`. */
    @JvmStatic external fun buildInfo(): String

    /** Nanoseconds for [n] calls of pymath's sin on one thread. */
    @JvmStatic external fun timeSin(n: Int): Long
}
