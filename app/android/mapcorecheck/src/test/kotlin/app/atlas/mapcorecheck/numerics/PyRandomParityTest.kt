package app.atlas.mapcorecheck.numerics

import app.atlas.render.py.PyCompat
import app.atlas.render.py.PyHash
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.util.SplittableRandom

/**
 * L0 d (plan-vector D16, K3b): pymath's random streams and hashes against the port's (`PyRandom`, `PyHash`). Over
 * 10^6 draws from 1,000 seeds (ints, unsigned and float seeds, and the renders' seeds from hashes of tuples of floats)
 * every draw is the port's to the bit, gauss's cached value included; big int seeds, and hash() of ints, floats, strs,
 * bytes and tuples, for CPython 3.9's SipHash-2-4 and 3.12's SipHash-1-3.
 */
class PyRandomParityTest {
    companion object {
        @AfterClass
        @JvmStatic
        fun restore() {
            PyCompat.minor = 9
        }
    }

    private val errorMark = NumericsHost.ERROR_MARK

    /** One draw of the script: its op code and three arguments (doubles as their bits). */
    private class Op(val code: Int, val a: Long = 0, val b: Long = 0, val c: Long = 0)

    private fun script(r: SplittableRandom, size: Int): List<Op> = List(size) {
        when (val p = r.nextInt(100)) {
            in 0..19 -> Op(0)
            in 20..32 -> Op(1, r.nextDouble(-10.0, 10.0).toRawBits(), r.nextDouble(-10.0, 10.0).toRawBits())
            in 33..42 -> Op(2, 0.0.toRawBits(), 1.0.toRawBits())
            in 43..47 -> Op(2, r.nextDouble(-5.0, 5.0).toRawBits(), r.nextDouble(0.0, 3.0).toRawBits())
            in 48..55 -> Op(3, if (p % 2 == 0) r.nextLong(0, 1000) else r.nextLong(0, Long.MAX_VALUE))
            in 56..63 -> Op(4, r.nextLong(-100, 100), r.nextLong(-100, 100), longArrayOf(1, 2, 3, -1, -2, 5, 0)[r.nextInt(7)])
            in 64..67 -> Op(5, r.nextLong(-50, 1), r.nextLong(0, 51))
            in 68..77 -> Op(6, r.nextLong(0, 65))
            in 78..83 -> Op(7, r.nextLong(0, 31))
            in 84..88 -> Op(8, r.nextLong(0, 13))
            in 89..94 -> Op(9)
            else -> Op(10, r.nextLong(-3, 1000))
        }
    }

    /** The port's results of [ops] from [rnd], as the core reports them. */
    private fun portDraws(rnd: PyRandom, ops: List<Op>, out: MutableList<Long>) {
        for (op in ops) {
            when (op.code) {
                0 -> out += rnd.random().toRawBits()
                1 -> out += rnd.uniform(Double.fromBits(op.a), Double.fromBits(op.b)).toRawBits()
                2 -> out += rnd.gauss(Double.fromBits(op.a), Double.fromBits(op.b)).toRawBits()
                3 -> out += rnd.randbelow(op.a)
                4 -> out += try { rnd.randrange(op.a, op.b, op.c) } catch (_: IllegalArgumentException) { errorMark }
                5 -> out += rnd.randint(op.a, op.b)
                6 -> out += if (op.a <= 63) rnd.getrandbits(op.a.toInt()) else rnd.getrandbitsBig(op.a.toInt()).toLong()
                7 -> out += try {
                    rnd.choice(IntArray(op.a.toInt()) { it }).toLong()
                } catch (_: IndexOutOfBoundsException) {
                    errorMark
                }
                8 -> { val deck = IntArray(op.a.toInt()) { it }; rnd.shuffle(deck); for (card in deck) out += card.toLong() }
                9 -> out += rnd.genrandUint32()
                else -> out += try { rnd.randrange(op.a) } catch (_: IllegalArgumentException) { errorMark }
            }
        }
    }

    @Test
    fun streamsFromAThousandSeedsEqualThePorts() {
        val r = SplittableRandom(90)
        val ops = script(r, 1000)
        val kinds = ArrayList<Int>()
        val seeds = ArrayList<Long>()
        for (s in 0L until 400L) { kinds += 0; seeds += s }
        repeat(300) { kinds += 0; seeds += r.nextLong() }
        // the renders' seeds: Random(hash((x0, z0)) & 0xffff), (("roof", x0, z0)) & 0xffff, (round(x, 1), round(z, 1)) & 0xffffff
        repeat(100) {
            val x = r.nextDouble(-500.0, 500.0)
            val z = r.nextDouble(-500.0, 500.0)
            kinds += 0
            seeds += when (it % 3) {
                0 -> PyHash.hashTuple(listOf(x, z)) and 0xffffL
                1 -> PyHash.hashTuple(listOf("roof", x, z)) and 0xffffL
                else -> PyHash.hashTuple(listOf(PyMath.round(x, 1), PyMath.round(z, 1))) and 0xffffffL
            }
        }
        repeat(100) {
            kinds += 2
            seeds += (if (it % 2 == 0) r.nextDouble(-1e6, 1e6) else PyMathParityTest.magnitude(r)).toRawBits()
        }
        repeat(99) { kinds += 1; seeds += r.nextLong() }
        kinds += 2; seeds += Double.NaN.toRawBits() // hash(nan) is not reproducible: no stream
        assertEquals(1000, seeds.size)

        val expected = ArrayList<Long>()
        for (i in seeds.indices) {
            val rnd = when (kinds[i]) {
                0 -> PyRandom(seeds[i])
                1 -> PyRandom().also { it.seedBig(BigInteger(java.lang.Long.toUnsignedString(seeds[i]))) }
                else -> {
                    val x = Double.fromBits(seeds[i])
                    if (x.isNaN()) { expected += errorMark; continue }
                    PyRandom().also { it.seed(x) }
                }
            }
            portDraws(rnd, ops, expected)
        }
        val out = LongArray(expected.size + 16)
        val count = NumericsHost.randomStreams(kinds.toIntArray(), seeds.toLongArray(), ops.map { it.code }.toIntArray(),
            ops.flatMap { listOf(it.a, it.b, it.c) }.toLongArray(), out)
        assertEquals("draws", expected.size, count)
        var mismatches = 0
        val firsts = ArrayList<String>()
        for (i in expected.indices) {
            if (expected[i] != out[i]) {
                mismatches++
                if (firsts.size < 8) firsts += "draw $i: port 0x%016x, pymath 0x%016x".format(expected[i], out[i])
            }
        }
        val gauss = ops.count { it.code == 2 } * 999
        println("pymath random: ${expected.size} draws from ${seeds.size} seeds ($gauss gauss), $mismatches mismatches")
        assertEquals("pymath's streams differ from the port's: $firsts", 0, mismatches)
    }

    @Test
    fun bigIntSeedsEqualThePorts() {
        val r = SplittableRandom(91)
        val words = ArrayList<Int>()
        val counts = IntArray(200) { 1 + r.nextInt(8) }
        val expected = ArrayList<Long>()
        for (n in counts) {
            var big = BigInteger.ZERO
            for (k in 0 until n) {
                val w = if (k == n - 1 && r.nextInt(4) == 0) 0 else r.nextInt() // some with leading zero words
                words += w
                big = big.or(BigInteger.valueOf(w.toLong() and 0xffffffffL).shiftLeft(32 * k))
            }
            val rnd = PyRandom().also { it.seedBig(big) }
            repeat(100) { expected += rnd.random().toRawBits() }
        }
        val out = LongArray(expected.size)
        NumericsHost.randomBig(words.toIntArray(), counts, 100, out)
        assertEquals(expected, out.toList())
    }

    @Test
    fun hashesOfIntsAndFloatsEqualThePorts() {
        val r = SplittableRandom(92)
        val ints = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -2, -1, 0, 1, (1L shl 61) - 1, 1L shl 61, Long.MAX_VALUE)
        val longs = LongArray(1_000_000) { if (it < ints.size) ints[it] else r.nextLong() }
        val gotLongs = LongArray(longs.size)
        NumericsHost.hashLongs(longs, gotLongs)
        for (i in longs.indices) assertEquals("hash(${longs[i]})", PyHash.hashInt(longs[i]), gotLongs[i])

        val doubles = DoubleArray(1_000_000) {
            when (it % 4) {
                0 -> PyMathParityTest.magnitude(r)
                1 -> r.nextLong(-1L shl 53, 1L shl 53).toDouble()
                2 -> PyMath.round(r.nextDouble(-500.0, 500.0), 1)
                else -> PyMathParityTest.EDGES[r.nextInt(PyMathParityTest.EDGES.size)]
            }
        }
        val gotDoubles = LongArray(doubles.size)
        val errors = ByteArray(doubles.size)
        NumericsHost.hashDoubles(doubles, gotDoubles, errors)
        var errorsSeen = 0
        for (i in doubles.indices) {
            val want = try { PyHash.hashDouble(doubles[i]) } catch (_: IllegalArgumentException) { null }
            if (want == null) {
                errorsSeen++
                assertEquals("hash(${doubles[i]}) raises", NumericsHost.ERRORS.indexOf("not_hashable") + 1, errors[i].toInt())
            } else {
                assertEquals("hash(${doubles[i]})", want, gotDoubles[i])
            }
        }
        println("pymath hash: ${longs.size} ints and ${doubles.size} floats equal the port's ($errorsSeen NaN refused by both)")
    }

    /** A random str: ASCII, Latin-1, the rest of the BMP (no surrogates) and the astral planes: CPython's three widths. */
    private fun text(r: SplittableRandom): String {
        val sb = StringBuilder()
        val kind = r.nextInt(4)
        repeat(r.nextInt(41)) {
            val cp = when (if (kind == 3) r.nextInt(4) else minOf(kind, r.nextInt(4))) {
                0 -> 0x20 + r.nextInt(0x5f)
                1 -> 0xa0 + r.nextInt(0x60)
                2 -> r.nextInt(0x100, 0xd800)
                else -> r.nextInt(0x10000, 0x110000)
            }
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    @Test
    fun hashesOfStrsBytesAndTuplesEqualThePorts() {
        val r = SplittableRandom(93)
        val texts = List(20_000) { if (it == 0) "" else text(r) } + listOf("roof", "bk", "tree_pick", "Jordaan", "IJ")
        val utf8 = texts.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        for (minor in listOf(9, 12)) {
            val strs = LongArray(texts.size)
            val bytes = LongArray(texts.size)
            val errors = ByteArray(texts.size)
            NumericsHost.hashTexts(utf8, minor, strs, bytes, errors)
            PyCompat.minor = minor
            for (i in texts.indices) {
                assertEquals("hash of str ${texts[i]} (3.$minor)", PyHash.hashStr(texts[i]), strs[i])
                assertEquals("hash of bytes ${texts[i]} (3.$minor)", PyHash.hashBytes(utf8[i], utf8[i].size), bytes[i])
                assertEquals(0, errors[i].toInt())
            }
            PyCompat.minor = 9
        }

        // Tuples of ints, floats, strs and bools, nested in no tuple: the renders' seeds are of this kind.
        for (minor in listOf(9, 12)) {
            val sizes = IntArray(100_000) { r.nextInt(7) }
            val kinds = ArrayList<Int>()
            val ints = ArrayList<Long>()
            val floats = ArrayList<Double>()
            val strs = ArrayList<ByteArray>()
            val tuples = ArrayList<List<Any>>()
            for (n in sizes) {
                val items = ArrayList<Any>()
                repeat(n) {
                    when (val k = r.nextInt(4)) {
                        0 -> { val v = r.nextLong(); kinds += k; ints += v; items += v }
                        1 -> {
                            val v = if (r.nextBoolean()) PyMath.round(r.nextDouble(-500.0, 500.0), 1) else PyMathParityTest.magnitude(r)
                            kinds += k; floats += v; items += v
                        }
                        2 -> {
                            val v = texts[r.nextInt(texts.size)]
                            kinds += k; strs += v.toByteArray(Charsets.UTF_8); items += v
                        }
                        else -> { val v = r.nextBoolean(); kinds += k; ints += if (v) 1L else 0L; items += v }
                    }
                }
                tuples += items
            }
            val out = LongArray(sizes.size)
            val errors = ByteArray(sizes.size)
            NumericsHost.hashTuples(
                sizes, kinds.toIntArray(), ints.toLongArray(), floats.toDoubleArray(), strs.toTypedArray(), minor, out, errors,
            )
            PyCompat.minor = minor
            for (i in tuples.indices) {
                assertEquals("hash of ${tuples[i]} (3.$minor)", PyHash.hashTuple(tuples[i]), out[i])
                assertEquals(0, errors[i].toInt())
            }
            PyCompat.minor = 9
        }
        println("pymath hash: ${texts.size} strs and bytes and 200,000 tuples equal the port's, for 3.9 and 3.12")
    }
}
