package app.atlas.render.py

import java.math.BigInteger
import java.security.MessageDigest

/**
 * CPython's `random.Random`, bit for bit: the MT19937 generator with Python's seeding (int seeds through
 * `init_by_array`, str and bytes seeds through SHA-512, version 2), and the methods built on it.
 */
class PyRandom() {
    private val mt = IntArray(N)
    private var index = N + 1
    private var gaussNext: Double? = null

    /** `random.Random(seed)` for an int seed. */
    constructor(seed: Long) : this() { seed(seed) }

    /** `random.Random(seed)` for an int seed. */
    constructor(seed: Int) : this() { seed(seed.toLong()) }

    /** `random.Random(seed)` for a str seed. */
    constructor(seed: String) : this() { seed(seed) }

    /** `random.Random(seed)` for any seed Python accepts (int, BigInteger, float, str, bytes). */
    constructor(seed: Any?) : this() { seedAny(seed) }

    /** `seed(n)` for an int. */
    fun seed(n: Long) {
        seedBig(BigInteger.valueOf(n))
    }

    /** `seed(n)` for a big int. */
    fun seedBig(n: BigInteger) {
        val a = n.abs()
        val bits = a.bitLength()
        val keyUsed = if (bits == 0) 1 else (bits - 1) / 32 + 1
        val key = IntArray(keyUsed)
        for (i in 0 until keyUsed) key[i] = a.shiftRight(32 * i).toInt()
        initByArray(key)
        gaussNext = null
    }

    /** `seed(s)` for a str (version 2: the UTF-8 bytes followed by their SHA-512). */
    fun seed(s: String) {
        seedBytes(s.toByteArray(Charsets.UTF_8))
    }

    /** `seed(b)` for bytes. */
    fun seedBytes(b: ByteArray) {
        val digest = MessageDigest.getInstance("SHA-512").digest(b)
        seedBig(BigInteger(1, b + digest))
    }

    /** `seed(x)` for a float: the float's hash, as an unsigned 64-bit number. */
    fun seed(x: Double) {
        val h = PyHash.hashDouble(x)
        seedBig(BigInteger(java.lang.Long.toUnsignedString(h)))
    }

    /** `seed(v)` for any Python seed. */
    fun seedAny(v: Any?) {
        when (v) {
            is Int -> seed(v.toLong())
            is Long -> seed(v)
            is BigInteger -> seedBig(v)
            is Boolean -> seed(if (v) 1L else 0L)
            is Double -> seed(v)
            is String -> seed(v)
            is ByteArray -> seedBytes(v)
            else -> throw IllegalArgumentException("unsupported seed type ${v?.let { it::class }}")
        }
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        index = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = if (N > key.size) N else key.size
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++; j++
            if (i >= N) {
                mt[0] = mt[N - 1]; i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]; i = 1
            }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    /** One 32-bit output of the twister, as an unsigned value in a Long. */
    fun genrandUint32(): Long {
        if (index >= N) {
            var kk = 0
            while (kk < N - M) {
                val y = (mt[kk] and UPPER) or (mt[kk + 1] and LOWER)
                mt[kk] = mt[kk + M] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            while (kk < N - 1) {
                val y = (mt[kk] and UPPER) or (mt[kk + 1] and LOWER)
                mt[kk] = mt[kk + (M - N)] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            val y = (mt[N - 1] and UPPER) or (mt[0] and LOWER)
            mt[N - 1] = mt[M - 1] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
            index = 0
        }
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y.toLong() and 0xffffffffL
    }

    /** `random()`: a float in [0, 1) with 53 random bits. */
    fun random(): Double {
        val a = genrandUint32() ushr 5
        val b = genrandUint32() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    /** `getrandbits(k)` for k <= 63. */
    fun getrandbits(k: Int): Long {
        require(k >= 0) { "number of bits must be non-negative" }
        if (k == 0) return 0L
        if (k <= 32) return genrandUint32() ushr (32 - k)
        require(k <= 63) { "use getrandbitsBig for more than 63 bits" }
        val lo = genrandUint32()
        val hi = genrandUint32() ushr (64 - k)
        return lo or (hi shl 32)
    }

    /** `getrandbits(k)` for any k. */
    fun getrandbitsBig(k: Int): BigInteger {
        require(k >= 0) { "number of bits must be non-negative" }
        if (k == 0) return BigInteger.ZERO
        if (k <= 32) return BigInteger.valueOf(genrandUint32() ushr (32 - k))
        val words = (k - 1) / 32 + 1
        var r = BigInteger.ZERO
        var left = k
        for (i in 0 until words) {
            var w = genrandUint32()
            if (left < 32) w = w ushr (32 - left)
            r = r.or(BigInteger.valueOf(w).shiftLeft(32 * i))
            left -= 32
        }
        return r
    }

    /** `_randbelow(n)`: an int in [0, n); 0 when n is 0. */
    fun randbelow(n: Long): Long {
        if (n == 0L) return 0L
        val k = 64 - java.lang.Long.numberOfLeadingZeros(n)
        var r = getrandbits(k)
        while (r >= n) r = getrandbits(k)
        return r
    }

    /** `randrange(stop)`. */
    fun randrange(stop: Long): Long {
        require(stop > 0) { "empty range for randrange()" }
        return randbelow(stop)
    }

    /** `randrange(start, stop, step)`. */
    fun randrange(start: Long, stop: Long, step: Long = 1): Long {
        val width = stop - start
        if (step == 1L) {
            require(width > 0) { "empty range for randrange($start, $stop)" }
            return start + randbelow(width)
        }
        val n = when {
            step > 0 -> Math.floorDiv(width + step - 1, step)
            step < 0 -> Math.floorDiv(width + step + 1, step)
            else -> throw IllegalArgumentException("zero step for randrange()")
        }
        require(n > 0) { "empty range for randrange($start, $stop, $step)" }
        return start + step * randbelow(n)
    }

    /** `randint(a, b)`: an int in [a, b]. */
    fun randint(a: Long, b: Long): Long = randrange(a, b + 1)

    /** `randint(a, b)` for Int bounds. */
    fun randint(a: Int, b: Int): Int = randrange(a.toLong(), b.toLong() + 1).toInt()

    /** `choice(seq)`. */
    fun <T> choice(seq: List<T>): T {
        if (seq.isEmpty()) throw IndexOutOfBoundsException("Cannot choose from an empty sequence")
        return seq[randbelow(seq.size.toLong()).toInt()]
    }

    /** `choice(seq)` for an IntArray. */
    fun choice(seq: IntArray): Int = seq[randbelow(seq.size.toLong()).toInt()]

    /** `choice(seq)` for a DoubleArray. */
    fun choice(seq: DoubleArray): Double = seq[randbelow(seq.size.toLong()).toInt()]

    /** `choice(range(start, stop, step))`. */
    fun choiceRange(start: Long, stop: Long, step: Long = 1): Long {
        val n = if (step > 0) Math.max(0L, Math.floorDiv(stop - start + step - 1, step))
        else Math.max(0L, Math.floorDiv(start - stop - step - 1, -step))
        if (n == 0L) throw IndexOutOfBoundsException("Cannot choose from an empty sequence")
        return start + step * randbelow(n)
    }

    /** `shuffle(x)` in place. */
    fun <T> shuffle(x: MutableList<T>) {
        for (i in x.size - 1 downTo 1) {
            val j = randbelow((i + 1).toLong()).toInt()
            val t = x[i]; x[i] = x[j]; x[j] = t
        }
    }

    /** `shuffle(x)` in place, for an IntArray. */
    fun shuffle(x: IntArray) {
        for (i in x.size - 1 downTo 1) {
            val j = randbelow((i + 1).toLong()).toInt()
            val t = x[i]; x[i] = x[j]; x[j] = t
        }
    }

    /** `sample(population, k)`. */
    fun <T> sample(population: List<T>, k: Int): List<T> {
        val n = population.size
        require(k in 0..n) { "Sample larger than population or is negative" }
        val result = ArrayList<T>(k)
        var setsize = 21
        if (k > 5) setsize += pow4ceil(k * 3)
        if (n <= setsize) {
            val pool = ArrayList(population)
            for (i in 0 until k) {
                val j = randbelow((n - i).toLong()).toInt()
                result.add(pool[j])
                pool[j] = pool[n - i - 1]
            }
        } else {
            val selected = HashSet<Int>()
            for (i in 0 until k) {
                var j = randbelow(n.toLong()).toInt()
                while (j in selected) j = randbelow(n.toLong()).toInt()
                selected.add(j)
                result.add(population[j])
            }
        }
        return result
    }

    // 4 ** ceil(log(x, 4)) with Python's float log, as random.sample computes it
    private fun pow4ceil(x: Int): Int {
        val e = Math.ceil(StrictMath.log(x.toDouble()) / StrictMath.log(4.0)).toInt()
        var r = 1
        repeat(e) { r *= 4 }
        return r
    }

    /** `uniform(a, b)`. */
    fun uniform(a: Double, b: Double): Double = a + (b - a) * random()

    /** `triangular(low, high, mode)`; a null mode is the midpoint. */
    fun triangular(low: Double = 0.0, high: Double = 1.0, mode: Double? = null): Double {
        var u = random()
        if (high == low) return low
        var c = if (mode == null) 0.5 else (mode - low) / (high - low)
        var lo = low
        var hi = high
        if (u > c) {
            u = 1.0 - u; c = 1.0 - c
            lo = high; hi = low
        }
        return lo + (hi - lo) * Math.sqrt(u * c)
    }

    /** `normalvariate(mu, sigma)` (Kinderman and Monahan). */
    fun normalvariate(mu: Double, sigma: Double): Double {
        var z: Double
        while (true) {
            val u1 = random()
            val u2 = 1.0 - random()
            z = NV_MAGICCONST * (u1 - 0.5) / u2
            val zz = z * z / 4.0
            if (zz <= -PyMath.log(u2)) break
        }
        return mu + z * sigma
    }

    /** `gauss(mu, sigma)`, with its cached second value. */
    fun gauss(mu: Double = 0.0, sigma: Double = 1.0): Double {
        var z = gaussNext
        gaussNext = null
        if (z == null) {
            val x2pi = random() * PyMath.TAU
            val g2rad = Math.sqrt(-2.0 * PyMath.log(1.0 - random()))
            z = PyMath.cos(x2pi) * g2rad
            gaussNext = PyMath.sin(x2pi) * g2rad
        }
        return mu + z * sigma
    }

    /** `expovariate(lambd)`. */
    fun expovariate(lambd: Double): Double = -PyMath.log(1.0 - random()) / lambd

    /** `choices(population, k=k)` without weights. */
    fun <T> choices(population: List<T>, k: Int): List<T> {
        val n = population.size.toDouble()
        return List(k) { population[Math.floor(random() * n).toInt()] }
    }

    /** `choices(population, weights, k=k)`. */
    fun <T> choices(population: List<T>, weights: DoubleArray, k: Int): List<T> {
        require(weights.size == population.size) { "The number of weights does not match the population" }
        val cum = DoubleArray(weights.size)
        var acc = 0.0
        for (i in weights.indices) {
            acc = if (i == 0) weights[0] else acc + weights[i]
            cum[i] = acc
        }
        val total = cum[cum.size - 1] + 0.0
        require(total > 0.0) { "Total of weights must be greater than zero" }
        val hi = population.size - 1
        return List(k) {
            val x = random() * total
            // bisect_right(cum, x, 0, hi)
            var lo = 0
            var h = hi
            while (lo < h) {
                val mid = (lo + h) / 2
                if (x < cum[mid]) h = mid else lo = mid + 1
            }
            population[lo]
        }
    }

    /** `getstate()`'s mutable part, for tests: the index into the state. */
    internal val stateIndex: Int get() = index

    companion object {
        private const val N = 624
        private const val M = 397
        private const val MATRIX_A = 0x9908b0df.toInt()
        private const val UPPER = 0x80000000.toInt()
        private const val LOWER = 0x7fffffff
        private const val NV_MAGICCONST = 1.7155277699214135 // 4 * exp(-0.5) / sqrt(2.0)
    }
}
