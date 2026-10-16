package app.atlas.render.py

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.math.BigInteger

/**
 * `json.load`: JSON as the values Python's json module makes (int or float, str, bool, None, list, ordered dict).
 *
 * The text is read in one streaming pass, straight into those values, with no text or tree of the whole document held: objects
 * are [CompactDict]s of their exact size, lists are exactly sized, and every key and string value goes through one [StringPool]
 * per document. The values are those [py] makes of kotlinx.serialization's strict tree: a number is a float when its text has '.',
 * 'e' or 'E', else an Int, a Long or a BigInteger; a repeated key keeps its first place and its last value.
 */
object PyJson {
    /** `json.load(open(file))`. */
    fun load(file: File): Any? = InputStreamReader(FileInputStream(file), Charsets.UTF_8).use { load(it) }

    /** `json.load(fp)`: the whole of [reader]'s text, which the caller closes. */
    fun load(reader: Reader): Any? = Parser(reader, CharArray(BUFFER), 0).document()

    /** `json.loads(text)`. */
    fun loads(text: String): Any? = Parser(null, text.toCharArray(), text.length).document()

    /** One JSON value as the Python one: ints stay Int (or Long, or BigInteger), numbers with '.', 'e' or 'E' are floats. */
    fun py(e: JsonElement): Any? = py(e, StringPool())

    /** [py] with the strings drawn from [pool]. */
    fun py(e: JsonElement, pool: StringPool): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> if (e.isString) pool.of(e.content) else literal(e.content)
        is JsonArray -> e.mapTo(ArrayList(e.size)) { py(it, pool) }
        is JsonObject -> CompactDict<String, Any?>(e.size).also { m -> for ((k, v) in e) m[pool.of(k)] = py(v, pool) }
    }

    /** An unquoted value's text as the Python value: true, false, a float, an Int, a Long or a BigInteger. */
    private fun literal(s: String): Any = when {
        s == "true" -> true
        s == "false" -> false
        s.any { it == '.' || it == 'e' || it == 'E' } -> s.toDouble()
        else -> s.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: BigInteger(s)
    }

    private const val BUFFER = 1 shl 16

    /** One document: from [reader] in chunks of [buf], or from [buf]'s first [lim] characters when [reader] is null. */
    private class Parser(private val reader: Reader?, private var buf: CharArray, private var lim: Int) {
        private var pos = 0
        private val pool = StringPool(4096)
        private val text = StringBuilder()

        /** Keys and values of the objects and lists still open, in order; each container takes its own off the top when it ends. */
        private var stack = arrayOfNulls<Any?>(256)
        private var top = 0

        fun document(): Any? {
            val v = value(next())
            if (skipSpace() >= 0) fail("text after the document")
            return v
        }

        private fun fail(what: String): Nothing = throw IllegalArgumentException("bad JSON: $what")

        private fun fill(): Boolean {
            val r = reader ?: return false
            while (true) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) return false
                if (n > 0) {
                    pos = 0
                    lim = n
                    return true
                }
            }
        }

        /** The next character that is not white space, without taking it; -1 at the end. */
        private fun skipSpace(): Int {
            while (true) {
                if (pos >= lim && !fill()) return -1
                val c = buf[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return c.code
            }
        }

        /** Takes the next character that is not white space. */
        private fun next(): Int {
            val c = skipSpace()
            if (c < 0) fail("unexpected end")
            pos++
            return c
        }

        private fun push(v: Any?) {
            if (top == stack.size) stack = stack.copyOf(top * 2)
            stack[top++] = v
        }

        private fun value(c: Int): Any? = when (c) {
            '{'.code -> obj()
            '['.code -> list()
            '"'.code -> string()
            else -> {
                pos--
                bare()
            }
        }

        private fun obj(): Dict {
            val mark = top
            var c = next()
            if (c != '}'.code) {
                while (true) {
                    if (c != '"'.code) fail("expected a key")
                    push(string())
                    if (next() != ':'.code) fail("expected ':'")
                    push(value(next()))
                    c = next()
                    if (c == '}'.code) break
                    if (c != ','.code) fail("expected ',' or '}'")
                    c = next()
                }
            }
            val d = CompactDict<String, Any?>((top - mark) / 2)
            var i = mark
            while (i < top) {
                @Suppress("UNCHECKED_CAST")
                d[stack[i] as String] = stack[i + 1]
                i += 2
            }
            java.util.Arrays.fill(stack, mark, top, null)
            top = mark
            return d
        }

        private fun list(): ArrayList<Any?> {
            val mark = top
            if (skipSpace() == ']'.code) {
                pos++
            } else {
                while (true) {
                    push(value(next()))
                    val c = next()
                    if (c == ']'.code) break
                    if (c != ','.code) fail("expected ',' or ']'")
                }
            }
            val l = ArrayList<Any?>(top - mark)
            for (i in mark until top) l.add(stack[i])
            java.util.Arrays.fill(stack, mark, top, null)
            top = mark
            return l
        }

        /** A string after its opening quote: pooled straight from the buffer when it has no escapes and does not cross a refill. */
        private fun string(): String {
            val start = pos
            var i = pos
            val b = buf
            val n = lim
            while (i < n) {
                val c = b[i]
                if (c == '"') {
                    pos = i + 1
                    return pool.of(b, start, i)
                }
                if (c == '\\') break
                i++
            }
            text.setLength(0)
            text.appendRange(b, start, i)
            pos = i
            return pool.of(slowString())
        }

        /** The rest of a string into [text], escapes decoded, across refills. */
        private fun slowString(): String {
            while (true) {
                if (pos >= lim && !fill()) fail("unterminated string")
                val c = buf[pos++]
                when (c) {
                    '"' -> return text.toString()
                    '\\' -> {
                        if (pos >= lim && !fill()) fail("unterminated escape")
                        when (val e = buf[pos++]) {
                            '"', '\\', '/' -> text.append(e)
                            'b' -> text.append('\b')
                            'f' -> text.append('\u000C')
                            'n' -> text.append('\n')
                            'r' -> text.append('\r')
                            't' -> text.append('\t')
                            'u' -> {
                                var u = 0
                                repeat(4) {
                                    if (pos >= lim && !fill()) fail("unterminated escape")
                                    val d = Character.digit(buf[pos++], 16)
                                    if (d < 0) fail("bad \\u escape")
                                    u = u * 16 + d
                                }
                                text.append(u.toChar())
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> text.append(c)
                }
            }
        }

        /** An unquoted value: null, true, false or a number, up to the next structural character or white space. */
        private fun bare(): Any? {
            val start = pos
            var i = pos
            val b = buf
            val n = lim
            while (i < n && !ends(b[i])) i++
            if (i < n || reader == null) {
                pos = i
                return bareOf(b, start, i)
            }
            text.setLength(0)
            text.appendRange(b, start, i)
            pos = i
            while (true) {
                if (pos >= lim && !fill()) break
                val c = buf[pos]
                if (ends(c)) break
                text.append(c)
                pos++
            }
            val cs = text.toString().toCharArray()
            return bareOf(cs, 0, cs.size)
        }

        private fun ends(c: Char): Boolean =
            c == ',' || c == '}' || c == ']' || c == ':' || c == '{' || c == '[' || c == '"' || c == ' ' || c == '\n' || c == '\r' || c == '\t'

        private fun bareOf(b: CharArray, start: Int, end: Int): Any? {
            if (start == end) fail("expected a value")
            // the common case without a String: an optional minus and up to 18 digits, exactly what toLongOrNull makes of it
            var i = start
            val neg = b[i] == '-'
            if (neg) i++
            if (end - i in 1..18) {
                var v = 0L
                while (i < end) {
                    val d = b[i] - '0'
                    if (d !in 0..9) break
                    v = v * 10 + d
                    i++
                }
                if (i == end) {
                    if (neg) v = -v
                    return if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
                }
            }
            val s = String(b, start, end - start)
            return if (s == "null") null else literal(s)
        }
    }
}
