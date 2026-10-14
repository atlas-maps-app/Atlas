package app.atlas.render.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Reader

/** What [JsonPull.peek] sees next. */
enum class JsonToken { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

/**
 * A streaming JSON reader: the extracts are tens of megabytes, so elements are read one at a time and never as a
 * tree. Strict about structure, tolerant about white space; numbers keep their text until asked for.
 */
class JsonPull(private val reader: Reader) {
    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var lim = 0
    private var eof = false

    // one entry per open container: ARRAY or OBJECT in the low bit, then the state
    private var kinds = ByteArray(64)
    private var states = ByteArray(64)
    private var depth = 0

    private var peeked: JsonToken? = null
    private val text = StringBuilder()

    private fun fill(): Boolean {
        if (eof) return false
        val n = reader.read(buf, 0, buf.size)
        if (n <= 0) {
            eof = true
            return false
        }
        pos = 0
        lim = n
        return true
    }

    private fun nextChar(): Int {
        if (pos >= lim && !fill()) return -1
        return buf[pos++].code
    }

    private fun skipSpace(): Int {
        while (true) {
            if (pos >= lim && !fill()) return -1
            val c = buf[pos].code
            if (c == 32 || c == 10 || c == 13 || c == 9) pos++ else return c
        }
    }

    private fun fail(what: String): Nothing = throw PackFormatException("bad JSON: $what")

    private fun push(kind: Int) {
        if (depth == kinds.size) {
            kinds = kinds.copyOf(depth * 2)
            states = states.copyOf(depth * 2)
        }
        kinds[depth] = kind.toByte()
        states[depth] = 0
        depth++
    }

    /** The next token, without consuming it. */
    fun peek(): JsonToken {
        peeked?.let { return it }
        var c = skipSpace()
        if (depth > 0) {
            val top = depth - 1
            val isObj = kinds[top].toInt() == OBJECT
            var st = states[top].toInt()
            if (st == AFTER_VALUE) {
                if (c == ','.code) {
                    pos++
                    c = skipSpace()
                    st = NEED
                    states[top] = NEED.toByte()
                } else if (c == '}'.code && isObj) return JsonToken.END_OBJECT.also { peeked = it }
                else if (c == ']'.code && !isObj) return JsonToken.END_ARRAY.also { peeked = it }
                else fail("expected ',' or the end of the container")
            }
            if (isObj && st != EXPECT_VALUE) {
                if (c == '"'.code) return JsonToken.NAME.also { peeked = it }
                if (c == '}'.code && st == START) return JsonToken.END_OBJECT.also { peeked = it }
                fail("expected a name")
            }
            if (!isObj && c == ']'.code) {
                if (st == START) return JsonToken.END_ARRAY.also { peeked = it }
                fail("a value was expected before ']'")
            }
        }
        val t = when (c) {
            -1 -> JsonToken.END
            '{'.code -> JsonToken.BEGIN_OBJECT
            '['.code -> JsonToken.BEGIN_ARRAY
            '"'.code -> JsonToken.STRING
            't'.code, 'f'.code -> JsonToken.BOOLEAN
            'n'.code -> JsonToken.NULL
            '-'.code, in '0'.code..'9'.code -> JsonToken.NUMBER
            else -> fail("unexpected '${if (c < 0) "end of file" else c.toChar().toString()}'")
        }
        peeked = t
        return t
    }

    private fun consume(expected: JsonToken): Unit {
        val t = peek()
        if (t != expected) fail("expected $expected but found $t")
        peeked = null
    }

    private fun valueDone() {
        if (depth > 0) states[depth - 1] = AFTER_VALUE.toByte()
    }

    fun beginObject() {
        consume(JsonToken.BEGIN_OBJECT)
        pos++
        push(OBJECT)
    }

    fun beginArray() {
        consume(JsonToken.BEGIN_ARRAY)
        pos++
        push(ARRAY)
    }

    fun endObject() {
        consume(JsonToken.END_OBJECT)
        pos++
        depth--
        valueDone()
    }

    fun endArray() {
        consume(JsonToken.END_ARRAY)
        pos++
        depth--
        valueDone()
    }

    /** True while the current container has another entry. */
    fun hasNext(): Boolean {
        val t = peek()
        return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != JsonToken.END
    }

    fun nextName(): String {
        consume(JsonToken.NAME)
        val s = readString()
        if (skipSpace() != ':'.code) fail("expected ':' after a name")
        pos++
        states[depth - 1] = EXPECT_VALUE.toByte()
        return s
    }

    private fun readString(): String {
        pos++ // the opening quote
        text.setLength(0)
        while (true) {
            if (pos >= lim && !fill()) fail("a string runs to the end of the file")
            var i = pos
            val end = lim
            while (i < end) {
                val c = buf[i]
                if (c == '"' || c == '\\') break
                i++
            }
            text.append(buf, pos, i - pos)
            pos = i
            if (pos >= lim) continue
            val c = buf[pos++]
            if (c == '"') return text.toString()
            when (val e = nextChar()) {
                '"'.code, '\\'.code, '/'.code -> text.append(e.toChar())
                'b'.code -> text.append('\b')
                'f'.code -> text.append('\u000C')
                'n'.code -> text.append('\n')
                'r'.code -> text.append('\r')
                't'.code -> text.append('\t')
                'u'.code -> {
                    var v = 0
                    repeat(4) {
                        val h = nextChar()
                        val d = Character.digit(h, 16)
                        if (d < 0) fail("a bad \\u escape")
                        v = v * 16 + d
                    }
                    text.append(v.toChar())
                }
                else -> fail("a bad escape")
            }
        }
    }

    fun nextString(): String {
        val t = peek()
        if (t == JsonToken.NUMBER) return numberText()
        consume(JsonToken.STRING)
        val s = readString()
        valueDone()
        return s
    }

    private fun numberText(): String {
        consume(JsonToken.NUMBER)
        text.setLength(0)
        while (true) {
            if (pos >= lim && !fill()) break
            val c = buf[pos]
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                text.append(c)
                pos++
            } else break
        }
        valueDone()
        return text.toString()
    }

    /** The number's text exactly as written. */
    fun nextNumberText(): String = numberText()

    fun nextDouble(): Double {
        val t = peek()
        if (t == JsonToken.STRING) return nextString().toDouble()
        consume(JsonToken.NUMBER)
        // plain decimals of up to fifteen digits are exact as a long over a power of ten: the same double strtod gives
        var neg = false
        var mant = 0L
        var digits = 0
        var scale = 0
        var seenDot = false
        var simple = true
        text.setLength(0)
        while (true) {
            if (pos >= lim && !fill()) break
            val c = buf[pos]
            if (c in '0'..'9') {
                if (digits < 15) {
                    mant = mant * 10 + (c - '0')
                    if (mant != 0L) digits++
                    if (seenDot) scale++
                } else simple = false
            } else if (c == '.' && !seenDot) seenDot = true
            else if (c == '-' && text.isEmpty()) neg = true
            else if (c == 'e' || c == 'E' || c == '+' || c == '-' || c == '.') simple = false
            else break
            text.append(c)
            pos++
        }
        valueDone()
        if (simple && scale <= 22) {
            val v = mant.toDouble() / POW10[scale]
            return if (neg) -v else v
        }
        return text.toString().toDouble()
    }

    fun nextLong(): Long {
        val s = numberText()
        return s.toLongOrNull() ?: s.toDouble().also { if (it != Math.rint(it)) fail("expected an integer, found $s") }.toLong()
    }

    fun nextInt(): Int = nextLong().toInt()

    fun nextBoolean(): Boolean {
        consume(JsonToken.BOOLEAN)
        val c = nextChar()
        val word = if (c == 't'.code) "rue" else "alse"
        for (ch in word) if (nextChar() != ch.code) fail("a bad literal")
        valueDone()
        return c == 't'.code
    }

    fun nextNull() {
        consume(JsonToken.NULL)
        for (ch in "null") if (nextChar() != ch.code) fail("a bad literal")
        valueDone()
    }

    fun skipValue() {
        when (peek()) {
            JsonToken.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) {
                    nextName()
                    skipValue()
                }
                endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            JsonToken.STRING -> nextString()
            JsonToken.NUMBER -> numberText()
            JsonToken.BOOLEAN -> nextBoolean()
            JsonToken.NULL -> nextNull()
            else -> fail("a value was expected")
        }
    }

    /** The next value as a tree, numbers keeping their written text. Only for small values (headers, configs, grids' metadata). */
    fun nextJson(): JsonElement = when (peek()) {
        JsonToken.BEGIN_OBJECT -> {
            beginObject()
            val m = LinkedHashMap<String, JsonElement>()
            while (hasNext()) {
                val k = nextName()
                m[k] = nextJson()
            }
            endObject()
            JsonObject(m)
        }
        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            val l = ArrayList<JsonElement>()
            while (hasNext()) l += nextJson()
            endArray()
            JsonArray(l)
        }
        JsonToken.STRING -> JsonPrimitive(nextString())
        JsonToken.NUMBER -> JsonRaw.number(numberText())
        JsonToken.BOOLEAN -> JsonPrimitive(nextBoolean())
        JsonToken.NULL -> {
            nextNull()
            JsonNull
        }
        else -> fail("a value was expected")
    }

    fun close() = reader.close()

    private companion object {
        const val ARRAY = 0
        const val OBJECT = 1
        const val START = 0
        const val AFTER_VALUE = 1
        const val NEED = 2
        const val EXPECT_VALUE = 3
        val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
    }
}
