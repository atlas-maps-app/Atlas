package app.atlas.render.py

import app.atlas.render.Oracle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.Reader
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random

/**
 * PyJson's streaming reader makes exactly the values of the strict kotlinx.serialization tree it replaced ([PyJson.py] of
 * `Json.parseToJsonElement`): the same types (Int, Long, BigInteger, Double, Boolean, null, str, list, dict), the same order of
 * keys, the same text, on random documents with refills at every kind of boundary and on every extract and file the engine reads.
 */
class PyJsonTest {
    private val strict = Json { isLenient = false }

    private fun viaTree(text: String): Any? = PyJson.py(strict.parseToJsonElement(text))

    /** Every value's type, the order of every key and every number's exact bits, as one digest (both trees are never held at once). */
    private fun digest(v: Any?): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
        fun walk(x: Any?) {
            when (x) {
                null -> put("N;")
                is Boolean -> put("B$x;")
                is Int -> put("I$x;")
                is Long -> put("L$x;")
                is BigInteger -> put("G$x;")
                is Double -> put("D${java.lang.Double.doubleToRawLongBits(x)};")
                is String -> put("S${x.length}:$x")
                is List<*> -> { put("A${x.size}["); x.forEach { walk(it) }; put("]") }
                is Map<*, *> -> { put("O${x.size}{"); for ((k, y) in x) { walk(k); walk(y) }; put("}") }
                else -> fail("unexpected ${x.javaClass}")
            }
        }
        walk(v)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A reader that hands out its text a few characters at a time, so every value meets refills at every offset. */
    private class Trickle(private val text: String, seed: Long, private val most: Int) : Reader() {
        private val rnd = Random(seed)
        private var at = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (at >= text.length) return -1
            val n = minOf(len, text.length - at, 1 + rnd.nextInt(most))
            text.toCharArray(cbuf, off, at, at + n)
            at += n
            return n
        }

        override fun close() {}
    }

    private fun assertSameAsTree(text: String, ctx: String) {
        val want = digest(viaTree(text))
        assertEquals("$ctx: loads", want, digest(PyJson.loads(text)))
        assertEquals("$ctx: trickled", want, digest(PyJson.load(Trickle(text, text.length.toLong(), 9))))
        val f = File.createTempFile("pyjson", ".json")
        try {
            f.writeText(text, Charsets.UTF_8)
            assertEquals("$ctx: load", want, digest(PyJson.load(f)))
        } finally {
            f.delete()
        }
    }

    private val tricky = listOf(
        "0", "-0", "7", "-7", "2147483647", "2147483648", "-2147483648", "-2147483649", "999999999999999999", "1000000000000000000",
        "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809", "123456789012345678901234567890",
        "0.0", "-0.0", "1.5", "1e5", "1E5", "1e-7", "-2.5E+3", "4.35", "52.3731", "4.8838999999999997", "1.7976931348623157e308", "5e-324",
        "true", "false", "null", "00012", "+5",
    )

    private val strings = listOf(
        "", "yes", "building", "Westerkerk", "Prinsengracht 279", "a \"quoted\" word", "back\\slash", "tab\tand\nnew line", "slash/ \\/",
        "caf\u00e9", "Stra\u00dfe", "\u65e5\u672c", "emoji \ud83d\ude00", "lone \ud800 surrogate", "nul \u0000 char", "\u001f",
        "a name longer than the interned strings go, kept in the pool only",
    )

    private fun quote(s: String, rnd: Random): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '/' && rnd.nextBoolean() -> b.append("\\/")
                c == '\n' -> b.append(if (rnd.nextBoolean()) "\\n" else "\\u000a")
                c == '\t' -> b.append("\\t")
                c < ' ' || c.isSurrogate() || (c.code > 127 && rnd.nextInt(3) == 0) -> b.append("\\u%04X".format(c.code))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun space(rnd: Random): String = when (rnd.nextInt(6)) {
        0 -> " "
        1 -> "\n  "
        2 -> "\t"
        3 -> "\r\n"
        else -> ""
    }

    private fun doc(rnd: Random, depth: Int, b: StringBuilder) {
        val pick = if (depth > 4) 2 + rnd.nextInt(2) else rnd.nextInt(5)
        when (pick) {
            0 -> {
                b.append('{').append(space(rnd))
                val n = rnd.nextInt(if (rnd.nextInt(8) == 0) 40 else 9)
                for (i in 0 until n) {
                    if (i > 0) b.append(',').append(space(rnd))
                    // repeated keys now and then: the first place and the last value
                    b.append(quote(if (rnd.nextInt(6) == 0) "k${rnd.nextInt(3)}" else strings[rnd.nextInt(strings.size)] + i, rnd))
                    b.append(space(rnd)).append(':').append(space(rnd))
                    doc(rnd, depth + 1, b)
                }
                b.append(space(rnd)).append('}')
            }
            1 -> {
                b.append('[').append(space(rnd))
                val n = rnd.nextInt(if (rnd.nextInt(6) == 0) 200 else 7)
                for (i in 0 until n) {
                    if (i > 0) b.append(space(rnd)).append(',').append(space(rnd))
                    doc(rnd, depth + 1, b)
                }
                b.append(space(rnd)).append(']')
            }
            2 -> b.append(tricky[rnd.nextInt(tricky.size)])
            3 -> b.append(quote(strings[rnd.nextInt(strings.size)], rnd))
            else -> b.append(rnd.nextDouble() * 1000 - 500)
        }
    }

    @Test
    fun `random documents read as the strict tree did`() {
        val rnd = Random(20261016)
        for (round in 0 until 300) {
            val b = StringBuilder(space(rnd))
            doc(rnd, 0, b)
            b.append(space(rnd))
            assertSameAsTree(b.toString(), "round $round")
        }
    }

    @Test
    fun `values that straddle a refill`() {
        // a string, an escaped string, a number and a literal each cut by the refill of a 64 Ki character buffer, at every offset
        val values = listOf("\"Prinsengracht\"", "\"caf\\u00e9 \\\"Hoppe\\\"\"", "1234567890123", "-2.5E+3", "123456789012345678901234567890", "true", "null")
        for (v in values) for (cut in 0..v.length) {
            val head = "[\"" + "x".repeat(65536 - cut - 4) + "\"," // the value starts cut characters before the buffer ends
            val text = head + v + " , {\"k\" :" + v + "}]"
            check(head.length == 65536 - cut)
            assertEquals("$v cut at $cut", digest(viaTree(text)), digest(PyJson.load(text.reader())))
        }
        val big = StringBuilder("{\"elements\": [")
        val rnd = Random(7)
        for (i in 0 until 6000) {
            if (i > 0) big.append(',')
            doc(rnd, 3, big)
        }
        big.append("]}")
        assertTrue(big.length > 4 * 65536)
        assertSameAsTree(big.toString(), "a document of ${big.length} characters")
    }

    @Test
    fun `numbers, literals and strings keep their Python types`() {
        val o = PyJson.loads("""{"i": 7, "neg": -0, "big": 2147483648, "huge": 123456789012345678901234567890, "f": 1E5, "g": 4.35,
            "t": true, "n": null, "s": "caf\u00e9 \u2013 \/", "l": [], "d": {}}""") as Map<String, Any?>
        assertEquals(7, o["i"]); assertEquals(0, o["neg"]); assertEquals(2147483648L, o["big"])
        assertEquals(BigInteger("123456789012345678901234567890"), o["huge"]); assertEquals(1e5, o["f"]); assertEquals(4.35, o["g"])
        assertEquals(true, o["t"]); assertTrue("n" in o && o["n"] == null); assertEquals("caf\u00e9 \u2013 /", o["s"])
        assertEquals(emptyList<Any?>(), o["l"]); assertEquals(emptyMap<String, Any?>(), o["d"])
        assertTrue(o is CompactDict<*, *> && o["d"] is CompactDict<*, *> && o["l"] is ArrayList<*>)
        assertSame("building", (PyJson.loads("""[{"building": "yes"}]""") as List<Map<String, Any?>>)[0].keys.first())
        assertEquals(listOf("a", "b"), (PyJson.loads("""{"a": 1, "b": 2, "a": 3}""") as Map<String, Any?>).keys.toList())
        assertEquals(3, (PyJson.loads("""{"a": 1, "b": 2, "a": 3}""") as Map<String, Any?>)["a"])
        assertNull(PyJson.loads(" null "))
        assertEquals(-12, PyJson.loads("-12"))
    }

    @Test
    fun `malformed text is refused`() {
        for (bad in listOf("", "  ", "{", "[1,]", "[1 2]", "{\"a\" 1}", "{\"a\": 1,}", "{a: 1}", "\"open", "\"bad \\x escape\"", "\"\\u12\"",
            "[1] 2", "{\"a\": }", "-", "tru", "nul")) {
            try {
                PyJson.loads(bad)
                fail("accepted: '$bad'")
            } catch (_: IllegalArgumentException) {
                // NumberFormatException is one too
            }
        }
    }

    @Test
    fun `every extract and file the engine reads, value for value`() {
        val repo = Oracle.repo
        val files = ArrayList<File>()
        for (d in listOf("data/ams", "data/eat", "data/errands") + (File(repo, "data/regions").listFiles()?.filter { it.isDirectory }?.sorted()?.map { "data/regions/${it.name}" } ?: emptyList())) {
            File(repo, d).listFiles { f -> f.name.endsWith(".json") }?.sorted()?.let { files.addAll(it) }
        }
        File(repo, "app/android/render/src/main/resources").walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { files.add(it) }
        assertTrue("the Westerkerk extract is in git", files.any { it.path.endsWith("data/ams/raw.json") })
        var bytes = 0L
        for (f in files) {
            val streamed = digest(PyJson.load(f))
            val tree = digest(viaTree(f.readText(Charsets.UTF_8)))
            assertEquals(f.path, tree, streamed)
            bytes += f.length()
        }
        println("PyJson: ${files.size} files, ${bytes / 1048576} MB, every value as the strict tree reads it")
    }
}
