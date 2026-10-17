package app.atlas.mapcorecheck.numerics

import app.atlas.render.py.PyCompat
import app.atlas.render.py.PyHash
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * pymath's check (`CP/check/NumericsCheck.cpp`, the `pymath <hash>` line the emulator and VK check print) computed
 * from the port's own functions on the JVM. The core's check must equal it part by part, and the total compiled into
 * the core (`numerics_expected`) must be this one, so every host that runs the core's tests, the emulator and each phone
 * hold pymath to the port without a JVM. This mirror follows the C++ line for line; the two change together.
 */
class NumericsCheckTest {
    companion object {
        @AfterClass
        @JvmStatic
        fun restore() {
            PyCompat.minor = 9
        }
    }

    @Test
    fun theCoresCheckEqualsThePorts() {
        val port = PortCheck.run()
        val names = NumericsHost.checkNames().trim().lines().map { it.substringBefore(' ') }
        val core = NumericsHost.checkParts()
        assertEquals("the parts", port.names, names)
        val differing = port.names.indices.filter { port.hashes[it] != core[it] }.map { port.names[it] }
        for (i in port.names.indices) {
            val hashes = "port 0x%016x, core 0x%016x".format(port.hashes[i], core[i])
            println("pymath check ${port.names[i]}: ${port.counts[i]} results, $hashes")
        }
        assertTrue("parts where the core differs from the port: $differing", differing.isEmpty())
        assertEquals(port.total, NumericsHost.checkTotal())
        println("pymath check total 0x%016x (core: ${NumericsHost.checkLine()})".format(port.total))
        assertEquals(
            "CP/check/NumericsCheck.cpp's numerics_expected must be the port's total, 0x%016XU".format(port.total),
            port.total, NumericsHost.checkExpected(),
        )
        assertTrue(NumericsHost.checkLine().endsWith(" ok"))
    }
}

/** The port's side of the check: CP/check/NumericsCheck.cpp, line for line. */
internal object PortCheck {
    class Result(val names: List<String>, val hashes: LongArray, val counts: IntArray, val total: Long)

    private val FNV_OFFSET = 0xcbf29ce484222325uL.toLong()
    private const val FNV_PRIME = 0x100000001b3L
    private const val NAN_BITS = 0x7ff8000000000000L

    /** FNV-1a over 64-bit words in little-endian order. */
    class Hasher {
        var hash = FNV_OFFSET
        var count = 0
        fun word(w: Long) {
            for (i in 0 until 8) {
                hash = hash xor ((w ushr (8 * i)) and 0xff)
                hash *= FNV_PRIME
            }
            count++
        }
        fun real(d: Double) = word(if (d.isNaN()) NAN_BITS else d.toRawBits())
        fun integer(v: Long) = word(v)
        fun error() = word(NumericsHost.ERROR_MARK)

        /** A double the port may throw for (Python raises). */
        inline fun result(f: () -> Double) {
            val v = try { f() } catch (_: ArithmeticException) { null } catch (_: IllegalArgumentException) { null }
            if (v == null) error() else real(v)
        }

        /** An int the port may throw for. */
        inline fun resultLong(f: () -> Long) {
            val v = try {
                f()
            } catch (_: ArithmeticException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IndexOutOfBoundsException) {
                null
            }
            if (v == null) error() else integer(v)
        }
    }

    /** SplitMix64, the inputs' generator. */
    class Inputs(private var state: Long) {
        fun next(): Long {
            state += 0x9E3779B97F4A7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return z xor (z ushr 31)
        }
        fun unit(): Double = (next() ushr 11).toDouble() * (1.0 / 9007199254740992.0)
        fun inRange(lo: Double, hi: Double): Double {
            val u = unit()
            return lo + (hi - lo) * u
        }
        fun magnitude(): Double {
            val r = next()
            val e = (r ushr 52) % 2047
            val m = next() and 0xFFFFFFFFFFFFFL
            return Double.fromBits((r and Long.MIN_VALUE) or (e shl 52) or m)
        }
        fun integer(lo: Long, hi: Long): Long = lo + java.lang.Long.remainderUnsigned(next(), hi - lo + 1)
    }

    private val EDGES = longArrayOf(
        0x0000000000000000L, 0x8000000000000000uL.toLong(), 0x0000000000000001L, 0x8000000000000001uL.toLong(),
        0x0010000000000000L, 0x8010000000000000uL.toLong(), 0x3FE0000000000000L, 0xBFE0000000000000uL.toLong(),
        0x3FF0000000000000L, 0xBFF0000000000000uL.toLong(), 0x4000000000000000L, 0xC000000000000000uL.toLong(),
        0x3FE921FB54442D18L, 0xBFE921FB54442D18uL.toLong(), 0x3FF921FB54442D18L, 0xBFF921FB54442D18uL.toLong(),
        0x400921FB54442D18L, 0xC00921FB54442D18uL.toLong(), 0x4002D97C7F3321D2L, 0xC002D97C7F3321D2uL.toLong(),
        0x413921FB54442D18L, 0xC13921FB54442D18uL.toLong(), 0x3E40000000000000L, 0xBE40000000000000uL.toLong(),
        0x3E30000000000000L, 0xBE30000000000000uL.toLong(), 0x3C90000000000000L, 0xBC90000000000000uL.toLong(),
        0x3FDC000000000000L, 0xBFDC000000000000uL.toLong(), 0x3FEF333333333333L, 0xBFEF333333333333uL.toLong(),
        0x40862E42FEFA39EFL, 0xC0862E42FEFA39EFuL.toLong(), 0x40874910D52D3051L, 0xC0874910D52D3051uL.toLong(),
        0x7E37E43C8800759CL, 0xFE37E43C8800759CuL.toLong(), 0x7FEFFFFFFFFFFFFFL, 0xFFEFFFFFFFFFFFFFuL.toLong(),
        0x7FF0000000000000L, 0xFFF0000000000000uL.toLong(), 0x7FF8000000000000L, 0x3FF0000000000001L,
        0x3FEFFFFFFFFFFFFFL, 0x4340000000000000L, 0x41E0000000000000L, 0x43F0000000000000L,
    )
    private val PAIR_EDGES = longArrayOf(
        0x0000000000000000L, 0x8000000000000000uL.toLong(), 0x0000000000000001L, 0x3FE0000000000000L,
        0x3FF0000000000000L, 0xBFF0000000000000uL.toLong(), 0x4000000000000000L, 0xC000000000000000uL.toLong(),
        0x3FF921FB54442D18L, 0x40862E42FEFA39EFL, 0x7FEFFFFFFFFFFFFFL, 0x7FF0000000000000L,
        0xFFF0000000000000uL.toLong(), 0x7FF8000000000000L, 0x4340000000000000L, 0x3FF0000000000001L,
    )

    private fun edge(i: Int) = Double.fromBits(EDGES[i])
    private fun pairEdge(i: Int) = Double.fromBits(PAIR_EDGES[i])

    private const val TWO_PI = 2 * Math.PI

    private fun unary(
        h: Hasher, inp: Inputs, f: (Double) -> Double, lo: Double, hi: Double, ranged: Int, lo2: Double, hi2: Double,
        ranged2: Int, magnitudes: Int,
    ) {
        repeat(ranged) { val x = inp.inRange(lo, hi); h.real(f(x)) }
        repeat(ranged2) { val x = inp.inRange(lo2, hi2); h.real(f(x)) }
        repeat(magnitudes) { val x = inp.magnitude(); h.real(f(x)) }
        for (i in EDGES.indices) h.real(f(edge(i)))
    }

    private inline fun edgePairs(f: (Double, Double) -> Unit) {
        for (i in PAIR_EDGES.indices) for (j in PAIR_EDGES.indices) f(pairEdge(i), pairEdge(j))
    }

    private fun strictTrig(h: Hasher, inp: Inputs, f: (Double) -> Double) =
        unary(h, inp, f, -TWO_PI, TWO_PI, 2048, -1e6, 1e6, 1024, 1024)

    private inline fun <T> compat(minor: Int, f: () -> T): T {
        PyCompat.minor = minor
        try { return f() } finally { PyCompat.minor = 9 }
    }

    private fun hypot2(h: Hasher, inp: Inputs, minor: Int) = compat(minor) {
        repeat(2048) { val x = inp.inRange(-1e3, 1e3); val y = inp.inRange(-1e3, 1e3); h.real(PyMath.hypot(x, y)) }
        repeat(512) { val x = inp.magnitude(); val y = inp.magnitude(); h.real(PyMath.hypot(x, y)) }
        edgePairs { x, y -> h.real(PyMath.hypot(x, y)) }
    }

    private fun hypotn(h: Hasher, inp: Inputs, minor: Int) = compat(minor) {
        val v = DoubleArray(4)
        repeat(512) {
            for (k in 0 until 3) v[k] = inp.inRange(-1e3, 1e3)
            h.real(PyMath.hypot(v[0], v[1], v[2]))
        }
        repeat(512) {
            for (k in 0 until 4) v[k] = inp.magnitude()
            h.real(PyMath.hypot(v[0], v[1], v[2], v[3]))
        }
        for (i in EDGES.indices) h.real(PyMath.hypot(edge(i), 3.0, edge((i + 7) % EDGES.size)))
    }

    private fun dist(h: Hasher, inp: Inputs, minor: Int) = compat(minor) {
        val p = DoubleArray(3)
        val q = DoubleArray(3)
        repeat(512) {
            for (k in 0 until 2) { p[k] = inp.inRange(-1e3, 1e3); q[k] = inp.inRange(-1e3, 1e3) }
            h.result { PyMath.dist(p.copyOf(2), q.copyOf(2)) }
        }
        repeat(512) {
            for (k in 0 until 3) { p[k] = inp.magnitude(); q[k] = inp.magnitude() }
            h.result { PyMath.dist(p.copyOf(), q.copyOf()) }
        }
        h.result { PyMath.dist(p.copyOf(2), q.copyOf()) } // Python's ValueError
    }

    private fun sums(h: Hasher, inp: Inputs, minor: Int) = compat(minor) {
        val v = DoubleArray(32)
        for (g in 0 until 256) {
            val n = inp.integer(0, 32).toInt()
            for (k in 0 until n) v[k] = if (g % 2 == 0) inp.inRange(-1e3, 1e3) else inp.magnitude()
            h.real(PyMath.sum(v, n))
            val s = PyMath.Summer()
            for (k in 0 until n) s.add(v[k])
            h.real(s.value())
        }
        for (i in EDGES.indices) h.real(PyMath.sum(doubleArrayOf(1.0, edge(i), -1.0)))
    }

    private fun binaryPy(h: Hasher, inp: Inputs, f: (Double, Double) -> Double) {
        repeat(2048) { val x = inp.inRange(-1e3, 1e3); val y = inp.inRange(-50.0, 50.0); h.result { f(x, y) } }
        repeat(512) { val x = inp.magnitude(); val y = inp.magnitude(); h.result { f(x, y) } }
        edgePairs { x, y -> h.result { f(x, y) } }
    }

    private fun intOps(h: Hasher, inp: Inputs) {
        repeat(1024) {
            val a = inp.next()
            val b = inp.integer(-1000, 1000)
            h.resultLong { PyMath.mod(a, b) }
            h.resultLong { PyMath.floorDiv(a, b) }
        }
        val as_ = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE)
        val bs = longArrayOf(Long.MIN_VALUE, -7, -1, 1, 7, Long.MAX_VALUE)
        for (a in as_) for (b in bs) {
            h.resultLong { PyMath.mod(a, b) }
            h.resultLong { PyMath.floorDiv(a, b) }
        }
    }

    private fun rounds(h: Hasher, inp: Inputs) {
        repeat(2048) { val x = inp.inRange(-1e6, 1e6); h.resultLong { PyMath.round(x) } }
        repeat(512) { val k = inp.integer(-1000000, 1000000).toDouble(); h.resultLong { PyMath.round(k + 0.5) } }
        repeat(512) { val x = inp.magnitude(); h.resultLong { PyMath.round(x) } }
        for (i in EDGES.indices) h.resultLong { PyMath.round(edge(i)) }
    }

    private fun roundDigits(h: Hasher, inp: Inputs) {
        repeat(2048) {
            val x = inp.inRange(-1e4, 1e4)
            val n = inp.integer(-5, 17).toInt()
            h.real(PyMath.round(x, n))
        }
        val scale = doubleArrayOf(1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0)
        repeat(512) {
            val k = inp.integer(-1000000, 1000000).toDouble()
            val d = inp.integer(0, 6).toInt()
            val x = (k + 0.5) / scale[d]
            h.real(PyMath.round(x, d))
        }
        repeat(512) {
            val x = inp.magnitude()
            val n = inp.integer(-330, 340).toInt()
            h.real(PyMath.round(x, n))
        }
        for (i in EDGES.indices) for (n in intArrayOf(0, 1, 2, -1)) h.real(PyMath.round(edge(i), n))
    }

    private fun roundInts(h: Hasher, inp: Inputs) {
        repeat(512) {
            val n = inp.next()
            val k = inp.integer(-20, 2).toInt()
            h.integer(PyMath.round(n, k))
        }
        val ns = longArrayOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1, -5000000000000000000L, -15, 15, 25, 5000000000000000001L, Long.MAX_VALUE,
        )
        for (n in ns) for (k in intArrayOf(-1, -18, -19, -20)) h.integer(PyMath.round(n, k))
    }

    private fun javaRound(h: Hasher, inp: Inputs) {
        repeat(2048) { val x = inp.magnitude(); h.integer(Math.round(x)) }
        repeat(2048) { val x = inp.inRange(-1e9, 1e9); h.integer(Math.round(x)) }
        repeat(1024) { val k = inp.integer(-1000000, 1000000).toDouble(); h.integer(Math.round(k + 0.5)) }
        val centres = longArrayOf(
            0x3FDFFFFFFFFFFFFFL, 0x3FE0000000000000L, 0x4330000000000000L, 0x4340000000000000L, 0x43E0000000000000L,
            0x0000000000000001L,
        )
        for (c in centres) for (d in 0L until 7L) {
            val x = Double.fromBits(c + d - 3)
            for (y in doubleArrayOf(x, x + 0.5, x + 1.0, x - 0.5, x - 1.0)) {
                h.integer(Math.round(y))
                h.integer(Math.round(-y))
            }
        }
        for (i in EDGES.indices) h.integer(Math.round(edge(i)))
    }

    private fun javaMinMax(h: Hasher, inp: Inputs) {
        for (i in 0 until 1024) {
            val a = inp.magnitude()
            val b = if (i % 2 == 0) inp.magnitude() else -a
            h.real(Math.max(a, b))
            h.real(Math.min(a, b))
            h.real(Math.max(a, 0.0))
            h.real(Math.min(-0.0, a))
            h.real(PyMath.max(a, -0.0))
            h.real(PyMath.min(0.0, a))
        }
        edgePairs { x, y ->
            h.real(Math.max(x, y))
            h.real(Math.min(x, y))
            h.real(PyMath.max(x, y))
            h.real(PyMath.min(x, y))
        }
    }

    private fun toInts(h: Hasher, inp: Inputs) {
        val all = { x: Double ->
            h.resultLong { PyMath.int(x) }
            h.resultLong { PyMath.floorInt(x) }
            h.resultLong { PyMath.ceilInt(x) }
        }
        repeat(1024) { val x = inp.inRange(-1e19, 1e19); all(x) }
        repeat(512) { val x = inp.magnitude(); all(x) }
        for (i in EDGES.indices) all(edge(i))
    }

    private fun checkedMath(h: Hasher, inp: Inputs) {
        repeat(512) {
            val a = inp.inRange(-1.0, 100.0)
            val b = inp.inRange(-1.5, 1.5)
            val c = inp.inRange(-800.0, 800.0)
            val d = inp.magnitude()
            val base = inp.inRange(-1.0, 10.0)
            val px = inp.inRange(-10.0, 10.0)
            val py = inp.inRange(-5.0, 5.0)
            h.result { PyMath.sqrt(a) }
            h.result { PyMath.asin(b) }
            h.result { PyMath.acos(b) }
            h.result { PyMath.exp(c) }
            h.result { PyMath.log(a) }
            h.result { PyMath.log10(a) }
            h.result { PyMath.log2(d) }
            val k = inp.integer(-1074, 1023).toInt()
            h.result { PyMath.log2(Math.scalb(1.0, k)) }
            h.result { PyMath.log(a, base) }
            h.result { PyMath.pow(px, py) }
            h.result { PyMath.pow(px, Math.floor(py)) }
        }
        for (i in EDGES.indices) {
            val x = edge(i)
            h.result { PyMath.sqrt(x) }
            h.result { PyMath.asin(x) }
            h.result { PyMath.acos(x) }
            h.result { PyMath.exp(x) }
            h.result { PyMath.log(x) }
            h.result { PyMath.log10(x) }
            h.result { PyMath.log2(x) }
        }
        edgePairs { x, y ->
            h.result { PyMath.pow(x, y) }
            h.result { PyMath.log(x, y) }
        }
    }

    private fun misc(h: Hasher, inp: Inputs) {
        repeat(512) {
            val a = inp.inRange(-720.0, 720.0)
            val b = inp.inRange(-10.0, 10.0)
            val m = inp.magnitude()
            val m2 = inp.magnitude()
            val e = inp.integer(-1100, 1100).toInt()
            val r = inp.inRange(-1e-8, 1e-8)
            h.real(PyMath.radians(a))
            h.real(PyMath.degrees(b))
            val f = PyMath.frexp(m)
            h.real(f.first)
            h.integer(f.second.toLong())
            h.real(PyMath.ldexp(m, e))
            h.integer(if (PyMath.isclose(a, a * (1 + r))) 1 else 0)
            h.real(PyMath.copysign(m, m2))
            h.real(PyMath.floor(m))
            h.real(PyMath.ceil(m))
            h.real(PyMath.fabs(m2))
            h.real(PyMath.max(m, m2))
            h.real(PyMath.min(m, m2))
        }
        for (i in EDGES.indices) {
            val x = edge(i)
            val f = PyMath.frexp(x)
            h.real(f.first)
            h.integer(f.second.toLong())
            h.real(PyMath.ldexp(x, -1075))
            h.real(PyMath.ldexp(x, 1075))
            h.integer(if (PyMath.isclose(x, 1.0)) 1 else 0)
            h.real(PyMath.max(x, 0.0))
            h.real(PyMath.min(0.0, x))
        }
    }

    private fun draws(h: Hasher, inp: Inputs, r: PyRandom) {
        val steps = longArrayOf(1, 2, 3, -1, -2, 5)
        for (i in 0 until 48) {
            when (i % 12) {
                0, 11 -> h.real(r.random())
                1 -> { val a = inp.inRange(-10.0, 10.0); val b = inp.inRange(-10.0, 10.0); h.real(r.uniform(a, b)) }
                2 -> h.real(r.gauss())
                3 -> { val mu = inp.inRange(-5.0, 5.0); val sigma = inp.inRange(0.0, 3.0); h.real(r.gauss(mu, sigma)) }
                4 -> { val n = inp.integer(0, 1000); h.integer(r.randbelow(n)) }
                5 -> { val n = inp.next() ushr 2; h.integer(r.randbelow(n)) }
                6 -> {
                    val start = inp.integer(-100, 100)
                    val stop = inp.integer(-100, 100)
                    val step = steps[inp.integer(0, 5).toInt()]
                    h.resultLong { r.randrange(start, stop, step) }
                }
                7 -> { val a = inp.integer(-50, 0); val b = inp.integer(0, 50); h.integer(r.randint(a, b)) }
                8 -> { val k = inp.integer(0, 63).toInt(); h.word(r.getrandbits(k)) }
                9 -> { val size = inp.integer(0, 20).toInt(); h.resultLong { r.choice(IntArray(size) { it }).toLong() } }
                else -> { val deck = IntArray(8) { it }; r.shuffle(deck); for (card in deck) h.integer(card.toLong()) }
            }
        }
    }

    private fun randomIntSeeds(h: Hasher, inp: Inputs) {
        for (s in 0L until 64L) draws(h, inp, PyRandom(s))
        repeat(64) { val s = inp.next(); draws(h, inp, PyRandom(s)) }
        for (s in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, -1, -12345)) draws(h, inp, PyRandom(s))
    }

    private fun randomHashedSeeds(h: Hasher, inp: Inputs) {
        for (i in 0 until 64) {
            val x = inp.inRange(-500.0, 500.0)
            val z = inp.inRange(-500.0, 500.0)
            val seed = when (i % 4) {
                0 -> PyHash.hashTuple(listOf(x, z)) and 0xffffL
                1 -> PyHash.hashTuple(listOf("roof", x, z)) and 0xffffL
                2 -> PyHash.hashTuple(listOf(PyMath.round(x, 1), PyMath.round(z, 1))) and 0xffffffL
                else -> PyHash.hashTuple(listOf(x, z, 7L)) and 0xffffffffL
            }
            h.integer(seed)
            draws(h, inp, PyRandom(seed))
        }
        for (i in 0 until 16) {
            val x = if (i % 2 == 0) inp.inRange(-1e6, 1e6) else inp.magnitude()
            val r = PyRandom()
            r.seed(x)
            draws(h, inp, r)
        }
        repeat(8) {
            val used = inp.integer(1, 5).toInt()
            var magnitude = BigInteger.ZERO
            for (k in 0 until used) magnitude = magnitude.or(BigInteger.valueOf(inp.next() and 0xffffffffL).shiftLeft(32 * k))
            val r = PyRandom()
            r.seedBig(magnitude)
            draws(h, inp, r)
        }
    }

    private fun hashNumbers(h: Hasher, inp: Inputs) {
        repeat(512) { h.integer(PyHash.hashInt(inp.next())) }
        val ints = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1, 0, 1, (1L shl 61) - 1, 1L shl 61, Long.MAX_VALUE)
        for (n in ints) h.integer(PyHash.hashInt(n))
        repeat(512) { val x = inp.magnitude(); h.resultLong { PyHash.hashDouble(x) } }
        repeat(256) { val x = inp.integer(-1000000000000L, 1000000000000L).toDouble(); h.resultLong { PyHash.hashDouble(x) } }
        repeat(256) { val x = inp.inRange(-500.0, 500.0); h.resultLong { PyHash.hashDouble(PyMath.round(x, 1)) } }
        for (i in EDGES.indices) h.resultLong { PyHash.hashDouble(edge(i)) }
    }

    private val TEXTS = listOf(
        "", "a", "roof", "bk", "tree_pick", "café", "Ⅻ", "名字", "😀", "a😀b",
        "Jordaan", "IJ", "Westerkerk", "ÿĀ", "12345678", "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghij",
    )

    private fun hashTexts(h: Hasher, inp: Inputs) {
        for (minor in intArrayOf(9, 12)) {
            compat(minor) {
                for (t in TEXTS) {
                    h.resultLong { PyHash.hashStr(t) }
                    val bytes = t.toByteArray(Charsets.UTF_8)
                    h.integer(PyHash.hashBytes(bytes, bytes.size))
                }
            }
            h.error() // "\xff": not UTF-8, so never a Python str (the port's strings are always text)
        }
        repeat(256) {
            val x = PyMath.round(inp.inRange(-500.0, 500.0), 1)
            val z = PyMath.round(inp.inRange(-500.0, 500.0), 1)
            h.resultLong { PyHash.hashTuple(listOf(x, z)) }
        }
        repeat(128) {
            val x = inp.inRange(-500.0, 500.0)
            val z = inp.inRange(-500.0, 500.0)
            h.resultLong { PyHash.hashTuple(listOf("roof", x, z)) }
            compat(12) { h.resultLong { PyHash.hashTuple(listOf("bk", x, z)) } }
        }
        repeat(128) {
            val n = inp.next() ushr 20
            val m = inp.magnitude()
            h.resultLong { PyHash.hashTuple(listOf(n, m, "bk", true)) }
        }
        h.resultLong { PyHash.hashTuple(emptyList<Any>()) }
        h.resultLong { PyHash.hashTuple(listOf(1.0, edge(42))) } // a NaN: not hashable
    }

    fun run(): Result {
        val names = ArrayList<String>()
        val hashes = ArrayList<Long>()
        val counts = ArrayList<Int>()
        fun part(name: String, body: (Hasher, Inputs) -> Unit) {
            val h = Hasher()
            body(h, Inputs(1000003L * (names.size + 1)))
            names += name; hashes += h.hash; counts += h.count
        }
        fun sweep(
            f: (Double) -> Double, lo: Double, hi: Double, ranged: Int, lo2: Double, hi2: Double, ranged2: Int,
            magnitudes: Int,
        ): (Hasher, Inputs) -> Unit =
            { h, inp -> unary(h, inp, f, lo, hi, ranged, lo2, hi2, ranged2, magnitudes) }
        PyCompat.minor = 9
        part("strict.sin") { h, inp -> strictTrig(h, inp) { StrictMath.sin(it) } }
        part("strict.cos") { h, inp -> strictTrig(h, inp) { StrictMath.cos(it) } }
        part("strict.tan") { h, inp -> strictTrig(h, inp) { StrictMath.tan(it) } }
        part("strict.asin", sweep({ StrictMath.asin(it) }, -1.0, 1.0, 2048, -1.25, 1.25, 512, 512))
        part("strict.acos", sweep({ StrictMath.acos(it) }, -1.0, 1.0, 2048, -1.25, 1.25, 512, 512))
        part("strict.atan", sweep({ StrictMath.atan(it) }, -10.0, 10.0, 2048, -1e6, 1e6, 512, 1024))
        part("strict.atan2") { h, inp ->
            repeat(2048) {
                val y = inp.inRange(-100.0, 100.0)
                val x = inp.inRange(-100.0, 100.0)
                h.real(StrictMath.atan2(y, x))
            }
            repeat(1024) { val y = inp.magnitude(); val x = inp.magnitude(); h.real(StrictMath.atan2(y, x)) }
            edgePairs { y, x -> h.real(StrictMath.atan2(y, x)) }
        }
        part("strict.exp", sweep({ StrictMath.exp(it) }, -750.0, 712.0, 2048, -1.0, 1.0, 512, 1024))
        part("strict.log", sweep({ StrictMath.log(it) }, 0.0, 2.0, 1024, 0.0, 1e6, 1024, 2048))
        part("strict.log10", sweep({ StrictMath.log10(it) }, 0.0, 2.0, 1024, 0.0, 1e6, 1024, 2048))
        part("strict.pow") { h, inp ->
            repeat(2048) { val x = inp.inRange(0.0, 100.0); val y = inp.inRange(-20.0, 20.0); h.real(StrictMath.pow(x, y)) }
            repeat(1024) {
                val x = inp.inRange(-10.0, 10.0)
                val y = inp.integer(-30, 30).toDouble()
                h.real(StrictMath.pow(x, y))
            }
            repeat(1024) { val x = inp.magnitude(); val y = inp.magnitude(); h.real(StrictMath.pow(x, y)) }
            repeat(512) {
                val x = inp.inRange(0.9999995, 1.0000005)
                val y = inp.inRange(-1e19, 1e19)
                h.real(StrictMath.pow(x, y))
                h.real(StrictMath.pow(-x, Math.floor(y)))
            }
            edgePairs { x, y -> h.real(StrictMath.pow(x, y)) }
        }
        part("strict.cbrt", sweep({ StrictMath.cbrt(it) }, -1e3, 1e3, 1024, -1.0, 1.0, 512, 2048))
        part("strict.expm1", sweep({ StrictMath.expm1(it) }, -40.0, 40.0, 1024, -1.0, 1.0, 512, 1024))
        part("strict.log1p", sweep({ StrictMath.log1p(it) }, -1.0, 10.0, 1024, -1e-5, 1e-5, 512, 1024))
        part("strict.sinh") { h, inp ->
            unary(h, inp, { StrictMath.sinh(it) }, -Math.PI, Math.PI, 2048, -30.0, 30.0, 1024, 512)
            repeat(512) { val x = inp.inRange(-712.0, 712.0); h.real(StrictMath.sinh(x)) }
            val branches = longArrayOf(
                0x3E30000000000000L, 0x3FF0000000000000L, 0x4036000000000000L, 0x40862E4200000000L, 0x40862E42FEFA39EFL,
                0x408633CE8FB9F87DL,
            )
            for (b in branches) {
                for (d in 0L until 7L) {
                    val x = Double.fromBits(b + d - 3)
                    h.real(StrictMath.sinh(x))
                    h.real(StrictMath.sinh(-x))
                }
            }
        }
        part("py.hypot.39") { h, inp -> hypot2(h, inp, 9) }
        part("py.hypot.312") { h, inp -> hypot2(h, inp, 12) }
        part("py.hypotn.39") { h, inp -> hypotn(h, inp, 9) }
        part("py.hypotn.312") { h, inp -> hypotn(h, inp, 12) }
        part("py.dist.39") { h, inp -> dist(h, inp, 9) }
        part("py.dist.312") { h, inp -> dist(h, inp, 12) }
        part("py.sum.39") { h, inp -> sums(h, inp, 9) }
        part("py.sum.312") { h, inp -> sums(h, inp, 12) }
        part("py.mod") { h, inp -> binaryPy(h, inp) { x, y -> PyMath.mod(x, y) } }
        part("py.floor_div") { h, inp -> binaryPy(h, inp) { x, y -> PyMath.floorDiv(x, y) } }
        part("py.fmod") { h, inp -> binaryPy(h, inp) { x, y -> PyMath.fmod(x, y) } }
        part("py.int_ops", ::intOps)
        part("py.round", ::rounds)
        part("py.round_digits", ::roundDigits)
        part("py.round_int", ::roundInts)
        part("java.round", ::javaRound)
        part("java.min_max", ::javaMinMax)
        part("py.to_int", ::toInts)
        part("py.checked", ::checkedMath)
        part("py.misc", ::misc)
        part("random.int_seeds", ::randomIntSeeds)
        part("random.hashed_seeds", ::randomHashedSeeds)
        part("hash.numbers", ::hashNumbers)
        part("hash.texts", ::hashTexts)
        val total = Hasher()
        for (x in hashes) total.word(x)
        return Result(names, hashes.toLongArray(), counts.toIntArray(), total.hash)
    }
}
