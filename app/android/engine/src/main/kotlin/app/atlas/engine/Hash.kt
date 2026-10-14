package app.atlas.engine

/**
 * Deterministic randomness per map feature (SplitMix64): the same building gets the same colour and the same
 * chimney on every device and every run, whatever order features arrive in.
 */
class Hash(seed: Long) {
    private var state = mix(seed)

    fun nextLong(): Long {
        state += GOLDEN
        return mix(state)
    }

    /** Uniform in 0 until 1. */
    fun next(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun nextFloat(): Float = (nextLong() ushr 40) * (1f / (1 shl 24))

    /** Uniform in [lo] until [hi]. */
    fun range(lo: Double, hi: Double): Double = lo + (hi - lo) * next()

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    /** Uniform in 0 until [n]. */
    fun index(n: Int): Int = ((nextLong() ushr 33) % n).toInt()

    fun <T> choice(list: List<T>): T = list[index(list.size)]

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL

        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** A seed from a feature id and a salt, so different uses of one id draw independent numbers. */
        fun seed(id: Long, salt: Int): Long = mix(id * 31 + salt)

        /** A stable 0..1 value for a position (millimetre grid) and a salt: for features without ids. */
        fun ofPoint(x: Double, y: Double, salt: Int): Long =
            mix((Math.round(x * 1000.0) * 73856093L) xor (Math.round(y * 1000.0) * 19349663L) xor salt.toLong())
    }
}
