package app.atlas.render.py

import app.atlas.render.Oracle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * PyRandom, PyFmt, PyMath and PyHash against CPython itself: the driver py_support.py computes thousands of
 * values with the oracle's python3, and every one must come out the same, bit for bit and character for character.
 */
class PySupportTest {
    @Before
    fun setUp() = Oracle.configure()

    /** Collects mismatches and fails with the first few. */
    private class Tally(val what: String) {
        var checked = 0
        val bad = ArrayList<String>()
        fun eq(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            if (expected != actual) bad.add("${ctx()}: python=$expected kotlin=$actual")
        }
        var offByOneUlp = 0

        /** Floats as IEEE bits, equal or (for the platform's libm) at most [ulps] units in the last place apart. */
        fun ulp1(expected: Long?, actual: Long?, ulps: Long = 1, ctx: () -> String) {
            checked++
            if (expected == actual) return
            if (expected != null && actual != null && (expected xor actual) >= 0 && Math.abs(expected - actual) <= ulps) {
                offByOneUlp++; return
            }
            bad.add("${ctx()}: python=$expected kotlin=$actual")
        }
        fun report(): String = "$what: $checked checked, ${bad.size} differ" + if (offByOneUlp > 0) ", $offByOneUlp one or two ulps apart" else ""
        fun done() {
            println(report())
            assertEquals("$what differs from CPython:\n" + bad.take(25).joinToString("\n"), 0, bad.size)
        }
    }

    private fun bits(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)
    private fun dbl(e: JsonElement): Double = java.lang.Double.longBitsToDouble(e.jsonPrimitive.long)
    private fun s(e: JsonElement): String? = if (e is JsonNull) null else e.jsonPrimitive.content
    private inline fun attempt(f: () -> Any?): Any? = try { f() } catch (e: RuntimeException) { null }

    // ---------------------------------------------------------------- random

    /** Replays rnd_ops in py_support.py. Each output becomes a list of strings. */
    private fun rndOps(r: PyRandom): List<List<String>> {
        val out = ArrayList<List<String>>()
        out.add(List(8) { bits(r.random()).toString() })
        out.add(intArrayOf(1, 2, 5, 8, 16, 31, 32).map { r.getrandbits(it).toString() })
        out.add(intArrayOf(33, 48, 63, 64, 65, 100, 200).map { r.getrandbitsBig(it).toString() })
        out.add(List(10) { r.randrange(1000).toString() })
        out.add(List(5) { r.randrange(5, 17).toString() } + List(5) { r.randrange(-10, 50, 7).toString() } +
            List(5) { r.randrange(50, -10, -3).toString() })
        out.add(List(10) { r.randint(1, 3).toString() } + List(5) { r.randint(-5, 5).toString() })
        out.add(List(3) { r.randrange(1L shl 40).toString() })
        val seq = listOf(14, 16, 18, 20, 22, 25, 28, 32)
        out.add(List(10) { r.choice(seq).toString() })
        out.add(List(5) { r.choiceRange(-1050, -70, 70).toString() })
        for (n in intArrayOf(20, 1, 2)) {
            val x = (0 until n).toMutableList(); r.shuffle(x); out.add(x.map { it.toString() })
        }
        out.add(r.sample((0 until 30).toList(), 10).map { it.toString() })
        out.add(r.sample((0 until 1000).toList(), 10).map { it.toString() })
        out.add(r.sample((0 until 10).toList(), 10).map { it.toString() })
        out.add(List(5) { bits(r.uniform(-4.0, 4.0)).toString() } + List(5) { bits(r.uniform(3.2, 5.2)).toString() })
        out.add(List(5) { bits(r.gauss(0.0, 1.0)).toString() } + List(4) { bits(r.gauss(10.0, 2.5)).toString() })
        out.add(List(5) { bits(r.normalvariate(0.0, 1.0)).toString() })
        out.add(List(3) { bits(r.triangular(0.0, 10.0)).toString() } + List(3) { bits(r.triangular(0.0, 10.0, 7.0)).toString() })
        out.add(List(3) { bits(r.expovariate(1.5)).toString() })
        out.add(r.choices(listOf("a", "b", "c", "d"), 6))
        out.add(r.choices(listOf("a", "b", "c", "d"), doubleArrayOf(1.0, 0.5, 3.0, 2.0), 6))
        r.seed(99)
        out.add(List(3) { bits(r.random()).toString() })
        r.seed("again")
        out.add(List(3) { bits(r.random()).toString() })
        val m = BigInteger.ONE.shiftLeft(61) - BigInteger.ONE
        var acc = BigInteger.ZERO
        val k31 = BigInteger.valueOf(31)
        repeat(5000) { acc = (acc * k31 + BigInteger.valueOf(r.getrandbits(32))).mod(m) }
        out.add(listOf(acc.toString()))
        return out
    }

    private val LIBM_OPS = setOf(16, 17, 19)

    @Test
    fun randomMatchesCPython() {
        val t = Tally("random")
        for (case in Oracle.json("py_support.py", "random").jsonArray) {
            val c = case.jsonObject
            val kind = c["kind"]!!.jsonPrimitive.content
            val seed = c["seed"]!!
            val r = when (kind) {
                "int" -> PyRandom().also { it.seedBig(BigInteger(seed.jsonPrimitive.content)) }
                "str" -> PyRandom(seed.jsonPrimitive.content)
                "bytes" -> PyRandom().also { it.seedBytes(ByteArray(seed.jsonArray.size) { i -> seed.jsonArray[i].jsonPrimitive.long.toByte() }) }
                "float" -> PyRandom(dbl(seed))
                else -> error(kind)
            }
            val got = rndOps(r)
            val want = c["out"]!!.jsonArray
            assertEquals(want.size, got.size)
            for (i in want.indices) {
                val w = want[i].let { if (it is JsonArray) it.map { e -> e.jsonPrimitive.content } else listOf(it.jsonPrimitive.content) }
                if (i in LIBM_OPS) {
                    // gauss, normalvariate and expovariate go through log, sin and cos
                    for (k in w.indices) t.ulp1(w[k].toLong(), got[i][k].toLong(), 1) { "seed $kind $seed op $i [$k]" }
                } else {
                    t.eq(w, got[i]) { "seed $kind $seed op $i" }
                }
            }
        }
        t.done()
    }

    // ---------------------------------------------------------------- formatting

    private fun pyInt(s: String): Any {
        val b = BigInteger(s)
        return if (b.bitLength() < 63) b.toLong() else b
    }

    @Test
    fun formattingMatchesCPython() {
        val doc = Oracle.json("py_support.py", "fmt").jsonObject
        val pct = doc["pct"]!!.jsonArray.map { it.jsonPrimitive.content }
        val fmt = doc["fmt"]!!.jsonArray.map { it.jsonPrimitive.content }
        val t = Tally("formatting")
        val nd = intArrayOf(0, 1, 2, 3, 4, 5, 8, -1, -2)
        for (row0 in doc["floats"]!!.jsonArray) {
            val row = row0.jsonObject
            val x = dbl(row["x"]!!)
            val label = { PyFmt.repr(x) }
            t.eq(s(row["str"]!!), attempt { PyFmt.str(x) }) { "str(${label()})" }
            t.eq(s(row["repr"]!!), attempt { PyFmt.repr(x as Any?) }) { "repr(${label()})" }
            row["pct"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.pf(pct[i], x) }) { "'${pct[i]}' % ${label()}" } }
            row["fmt"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.format(x, fmt[i]) }) { "format(${label()}, '${fmt[i]}')" } }
            row["round"]!!.jsonArray.forEachIndexed { i, e ->
                t.eq(s(e)?.toLong(), attempt { bits(PyMath.round(x, nd[i])) }) { "round(${label()}, ${nd[i]})" }
            }
            t.eq(s(row["round0"]!!), attempt { PyMath.roundBig(x).toString() }) { "round(${label()})" }
            t.eq(s(row["int"]!!), attempt { PyMath.intBig(x).toString() }) { "int(${label()})" }
            if (Math.abs(x) < 9e18) {
                t.eq(s(row["round0"]!!), attempt { PyMath.round(x).toString() }) { "round(${label()}) as Long" }
                t.eq(s(row["int"]!!), attempt { PyMath.int(x).toString() }) { "int(${label()}) as Long" }
            }
        }
        val pctInt = doc["pct_int"]!!.jsonArray.map { it.jsonPrimitive.content }
        val fmtInt = doc["fmt_int"]!!.jsonArray.map { it.jsonPrimitive.content }
        for (row0 in doc["ints"]!!.jsonArray) {
            val row = row0.jsonObject
            val n = pyInt(row["n"]!!.jsonPrimitive.content)
            row["pct"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.pf(pctInt[i], n) }) { "'${pctInt[i]}' % $n" } }
            row["fmt"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.format(n, fmtInt[i]) }) { "format($n, '${fmtInt[i]}')" } }
            if (n is Long && n in Int.MIN_VALUE..Int.MAX_VALUE) {
                row["pct"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.pf(pctInt[i], n.toInt()) }) { "'${pctInt[i]}' % Int $n" } }
            }
        }
        val fmtStr = doc["fmt_str"]!!.jsonArray.map { it.jsonPrimitive.content }
        val pctStr = listOf("%s", "%r", "%10s", "%-10s|", "%.3s")
        for (row0 in doc["strs"]!!.jsonArray) {
            val row = row0.jsonObject
            val v: Any? = row["s"]!!.let { e -> if (e is JsonNull) null else (e as JsonPrimitive).let { if (it.isString) it.content else it.content.toBooleanStrict() } }
            row["pct"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.pf(pctStr[i], v) }) { "'${pctStr[i]}' % ${PyFmt.repr(v)}" } }
            row["fmt"]!!.jsonArray.forEachIndexed { i, e -> t.eq(s(e), attempt { PyFmt.format(v, fmtStr[i]) }) { "format(${PyFmt.repr(v)}, '${fmtStr[i]}')" } }
        }
        for (m0 in doc["multi"]!!.jsonArray) {
            val m = m0.jsonArray
            val f = m[0].jsonPrimitive.content
            val args = m[1].jsonArray.map { e -> (e as JsonPrimitive).let { p -> if (p.isString) p.content else p.long } }
            val argv: Array<Any?> = if (f.startsWith("%.1f")) args.map { java.lang.Double.longBitsToDouble(it as Long) }.toTypedArray() else args.toTypedArray()
            t.eq(m[2].jsonPrimitive.content, attempt { PyFmt.pf(f, *argv) }) { "'$f' % $args" }
        }
        t.done()
    }

    // ---------------------------------------------------------------- math

    /**
     * The functions CPython takes from the platform's C library. Apple's libm is not correctly rounded (sin, cos and
     * tan miss in a few percent of cases, tan in a third), so no portable code can match it bit for bit: the port
     * uses fdlibm (StrictMath), the same on every device, and these must be within one unit in the last place.
     */
    private val libm = setOf("sin", "cos", "tan", "atan", "exp", "log", "**2", "**0.8", "**0.9", "log4", "asin", "acos", "log10", "log2", "atan2", "**")

    @Test
    fun mathMatchesCPython() {
        val doc = Oracle.json("py_support.py", "math").jsonObject
        val t = Tally("math")
        val lm = Tally("math (libm)")
        fun cmp(name: String, want: JsonElement, got: Any?, ctx: () -> String) {
            // Apple's tan is up to two ulps from fdlibm's
            if (name in libm) lm.ulp1(s(want)?.toLong(), got as Long?, if (name == "tan") 2 else 1, ctx) else t.eq(s(want)?.toLong(), got, ctx)
        }
        val pairNames = listOf("%", "//", "fmod", "hypot", "atan2", "copysign", "isclose", "isclose2", "max", "min", "**")
        for (row0 in doc["pairs"]!!.jsonArray) {
            val row = row0.jsonArray
            val a = dbl(row[0]); val b = dbl(row[1])
            val fs: List<() -> Double> = listOf(
                { PyMath.mod(a, b) }, { PyMath.floorDiv(a, b) }, { PyMath.fmod(a, b) }, { PyMath.hypot(a, b) },
                { PyMath.atan2(a, b) }, { PyMath.copysign(a, b) }, { if (PyMath.isclose(a, b)) 1.0 else 0.0 },
                { if (PyMath.isclose(a, b, 1e-3, 0.5)) 1.0 else 0.0 }, { PyMath.max(a, b) }, { PyMath.min(a, b) },
                { if (Math.abs(b) < 50) PyMath.pow(Math.abs(a), b) else 0.0 },
            )
            for (i in fs.indices) cmp(pairNames[i], row[2 + i], attempt { bits(fs[i]()) }) { "${pairNames[i]}(${PyFmt.repr(a)}, ${PyFmt.repr(b)})" }
        }
        val unNames = listOf("sin", "cos", "tan", "atan", "exp", "log", "sqrt", "radians", "degrees", "floor", "ceil",
            "**2", "**0.8", "**0.9", "log4", "asin", "acos", "log10", "log2")
        for (row0 in doc["unary"]!!.jsonArray) {
            val row = row0.jsonArray
            val a = dbl(row[0])
            val fs: List<() -> Double> = listOf(
                { PyMath.sin(a) }, { PyMath.cos(a) }, { PyMath.tan(a) }, { PyMath.atan(a) }, { PyMath.exp(a) }, { PyMath.log(a) },
                { PyMath.sqrt(a) }, { PyMath.radians(a) }, { PyMath.degrees(a) }, { PyMath.floorInt(a).toDouble() },
                { PyMath.ceilInt(a).toDouble() }, { PyMath.pow(a, 2) }, { PyMath.pow(a, 0.8) }, { PyMath.pow(a, 0.9) },
                { PyMath.log(a, 4.0) }, { PyMath.asin(a) }, { PyMath.acos(a) }, { PyMath.log10(a) }, { PyMath.log2(a) },
            )
            for (i in fs.indices) {
                // math.floor/ceil return Python ints: beyond 2**63 they stay exact, and float(int) is the float itself
                val f = if (unNames[i] == "floor") ({ Math.floor(a) + 0.0 }) else if (unNames[i] == "ceil") ({ Math.ceil(a) + 0.0 }) else fs[i]
                cmp(unNames[i], row[1 + i], attempt { bits(f()) }) { "${unNames[i]}(${PyFmt.repr(a)})" }
            }
        }
        for (row0 in doc["sums"]!!.jsonArray) {
            val row = row0.jsonArray
            val xs = row[0].jsonArray.map { dbl(it) }.toDoubleArray()
            t.eq(s(row[1])?.toLong(), bits(PyMath.sum(xs))) { "sum(${xs.joinToString { PyFmt.repr(it) }})" }
            t.eq(s(row[1])?.toLong(), bits(PyMath.sumOf(xs.asList()) { it })) { "sumOf(${xs.size} items)" }
        }
        for (row0 in doc["hyp3"]!!.jsonArray) {
            val row = row0.jsonArray
            val p = row[0].jsonArray.map { dbl(it) }.toDoubleArray()
            t.eq(s(row[1])?.toLong(), bits(PyMath.hypot(*p))) { "hypot(${p.joinToString { PyFmt.repr(it) }})" }
        }
        for (row0 in doc["divmod_int"]!!.jsonArray) {
            val r = row0.jsonArray.map { it.jsonPrimitive.long }
            t.eq(r[2], PyMath.mod(r[0], r[1])) { "${r[0]} % ${r[1]}" }
            t.eq(r[3], PyMath.floorDiv(r[0], r[1])) { "${r[0]} // ${r[1]}" }
            t.eq(r[2].toInt(), PyMath.mod(r[0].toInt(), r[1].toInt())) { "${r[0]} % ${r[1]} (Int)" }
        }
        println(lm.report())
        t.done()
        lm.done()
    }

    // ---------------------------------------------------------------- hash

    private fun build(spec: JsonArray): Any {
        val tag = spec[0].jsonPrimitive.content
        val v = spec[1]
        return when (tag) {
            "s" -> v.jsonPrimitive.content
            "i" -> v.jsonPrimitive.long
            "f" -> dbl(v)
            "t" -> v.jsonArray.map { build(it.jsonArray) }
            else -> error(tag)
        }
    }

    @Test
    fun hashMatchesCPython() {
        val doc = Oracle.json("py_support.py", "hash").jsonObject
        val t = Tally("hash")
        for (row0 in doc["values"]!!.jsonArray) {
            val row = row0.jsonObject
            val v: Any = when (row["t"]!!.jsonPrimitive.content) {
                "s" -> row["v"]!!.jsonPrimitive.content
                "i" -> pyInt(row["v"]!!.jsonPrimitive.content)
                else -> dbl(row["v"]!!)
            }
            t.eq(row["h"]!!.jsonPrimitive.long, PyHash.hash(v)) { "hash(${PyFmt.repr(v)})" }
        }
        for (row0 in doc["tuples"]!!.jsonArray) {
            val row = row0.jsonObject
            val items = row["items"]!!.jsonArray.map { build(it.jsonArray) }
            t.eq(row["h"]!!.jsonPrimitive.long, PyHash.hashTuple(items)) { "hash($items)" }
            t.eq(row["seed32"]!!.jsonPrimitive.long, PyHash.hashTuple(items) and 0xffffffffL) { "seed32($items)" }
        }
        t.done()
    }

    @Test
    fun bothHashAlgorithmsAreKnown() {
        // SipHash-1-3 (3.11+) and SipHash-2-4 (up to 3.10) of b"abc" with the all-zero key, from CPython runs
        val bytes = "abc".toByteArray()
        val r24 = PyHash.sipHash(0, 0, bytes, 3, 2)
        val r13 = PyHash.sipHash(0, 0, bytes, 3, 1)
        if (Oracle.info["hash"]!!.jsonPrimitive.content == "siphash24") assertEquals(PyHash.hashStr("abc"), r24)
        else assertEquals(PyHash.hashStr("abc"), r13)
        assertEquals(false, r24 == r13)
    }
}
