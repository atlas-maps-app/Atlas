package app.atlas.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.io.Reader

/**
 * A small pull parser for JSON too big to hold as a tree: city-sized Overpass answers, the national charger files.
 * It reads tokens straight from a [Reader] through one buffer, so memory stays flat however long the input is.
 */
class JsonStream(private val reader: Reader) : Closeable {
    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var limit = 0
    private val sb = StringBuilder(64)

    // The nesting stack: what we are inside and whether a member has been read there yet.
    private var stack = IntArray(32).also { it[0] = EMPTY_DOCUMENT }
    private var depth = 1
    private var peeked: Token? = null

    /** The next token without consuming it. */
    fun peek(): Token = peeked ?: doPeek().also { peeked = it }

    fun beginObject() {
        expect(Token.BEGIN_OBJECT)
        push(EMPTY_OBJECT)
    }

    fun endObject() {
        expect(Token.END_OBJECT)
        depth--
    }

    fun beginArray() {
        expect(Token.BEGIN_ARRAY)
        push(EMPTY_ARRAY)
    }

    fun endArray() {
        expect(Token.END_ARRAY)
        depth--
    }

    /** True while the current object or array has more members. */
    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_OBJECT && t != Token.END_ARRAY && t != Token.END
    }

    fun nextName(): String {
        expect(Token.NAME)
        return readQuoted()
    }

    /** A string, or the literal text of a number or boolean. */
    fun nextString(): String = when (peek()) {
        Token.STRING -> { peeked = null; readQuoted() }
        Token.NUMBER, Token.BOOLEAN -> { peeked = null; readLiteral() }
        else -> throw syntax("Expected a string but was ${peek()}")
    }

    fun nextDouble(): Double = when (peek()) {
        Token.NUMBER -> { peeked = null; number(readLiteral()) }
        Token.STRING -> { peeked = null; readQuoted().trim().toDoubleOrNull() ?: throw syntax("Not a number") }
        else -> throw syntax("Expected a number but was ${peek()}")
    }

    fun nextLong(): Long = when (peek()) {
        Token.NUMBER -> { peeked = null; readLiteral().let { it.toLongOrNull() ?: number(it).toLong() } }
        Token.STRING -> { peeked = null; readQuoted().trim().let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: throw syntax("Not a number") }
        else -> throw syntax("Expected a number but was ${peek()}")
    }

    fun nextBoolean(): Boolean {
        expect(Token.BOOLEAN)
        return when (val lit = readLiteral()) {
            "true" -> true
            "false" -> false
            else -> throw syntax("Expected true or false but was $lit")
        }
    }

    fun nextNull() {
        expect(Token.NULL)
        val lit = readLiteral()
        if (lit != "null") throw syntax("Expected null but was $lit")
    }

    /** A number, or NaN for null. */
    fun nextDoubleOrNaN(): Double = if (peek() == Token.NULL) { nextNull(); Double.NaN } else nextDouble()

    /** A string, number or boolean as text; null for JSON null. */
    fun nextScalarOrNull(): String? = if (peek() == Token.NULL) { nextNull(); null } else nextString()

    /** Skips the next value, however deeply nested. */
    fun skipValue() {
        var level = 0
        do {
            when (peek()) {
                Token.BEGIN_OBJECT -> { beginObject(); level++ }
                Token.BEGIN_ARRAY -> { beginArray(); level++ }
                Token.END_OBJECT -> { endObject(); level-- }
                Token.END_ARRAY -> { endArray(); level-- }
                Token.NAME -> nextName()
                Token.STRING -> { peeked = null; skipQuoted() }
                Token.NUMBER, Token.BOOLEAN, Token.NULL -> { peeked = null; readLiteral() }
                Token.END -> throw syntax("Unexpected end of input")
            }
        } while (level > 0)
    }

    /** Reads the next value as a kotlinx.serialization tree: handy for small sub-objects inside a stream. */
    fun readTree(): JsonElement = when (peek()) {
        Token.BEGIN_OBJECT -> {
            beginObject()
            val m = LinkedHashMap<String, JsonElement>()
            while (hasNext()) m[nextName()] = readTree()
            endObject()
            JsonObject(m)
        }
        Token.BEGIN_ARRAY -> {
            beginArray()
            val l = ArrayList<JsonElement>()
            while (hasNext()) l.add(readTree())
            endArray()
            JsonArray(l)
        }
        Token.STRING -> JsonPrimitive(nextString())
        Token.NUMBER -> { peeked = null; readLiteral().let { n -> n.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(number(n)) } }
        Token.BOOLEAN -> JsonPrimitive(nextBoolean())
        Token.NULL -> { nextNull(); JsonNull }
        else -> throw syntax("Expected a value but was ${peek()}")
    }

    /** Reads an object of scalar values (OSM tags) as text, dropping nulls. */
    fun readStringMap(): Map<String, String> {
        beginObject()
        if (!hasNext()) {
            endObject()
            return emptyMap()
        }
        val m = HashMap<String, String>(16)
        while (hasNext()) {
            val k = nextName()
            if (peek() == Token.BEGIN_OBJECT || peek() == Token.BEGIN_ARRAY) skipValue() else nextScalarOrNull()?.let { m[k] = it }
        }
        endObject()
        return m
    }

    override fun close() = reader.close()

    // ---- tokenising, after the state machine of Gson's JsonReader

    private fun expect(t: Token) {
        val p = peek()
        if (p != t) throw syntax("Expected $t but was $p")
        peeked = null
    }

    private fun push(state: Int) {
        if (depth == stack.size) stack = stack.copyOf(depth * 2)
        stack[depth++] = state
    }

    private fun doPeek(): Token {
        val top = stack[depth - 1]
        when (top) {
            EMPTY_ARRAY -> stack[depth - 1] = NONEMPTY_ARRAY
            NONEMPTY_ARRAY -> when (nextNonSpace()) {
                ']' -> return Token.END_ARRAY
                ',' -> {}
                else -> throw syntax("Unterminated array")
            }
            EMPTY_OBJECT, NONEMPTY_OBJECT -> {
                stack[depth - 1] = DANGLING_NAME
                if (top == NONEMPTY_OBJECT) when (nextNonSpace()) {
                    '}' -> return Token.END_OBJECT
                    ',' -> {}
                    else -> throw syntax("Unterminated object")
                }
                return when (nextNonSpace()) {
                    '"' -> { pos--; Token.NAME }
                    '}' -> if (top == EMPTY_OBJECT) Token.END_OBJECT else throw syntax("Trailing comma")
                    else -> throw syntax("Expected a name")
                }
            }
            DANGLING_NAME -> {
                stack[depth - 1] = NONEMPTY_OBJECT
                if (nextNonSpace() != ':') throw syntax("Expected ':'")
            }
            EMPTY_DOCUMENT -> stack[depth - 1] = NONEMPTY_DOCUMENT
            NONEMPTY_DOCUMENT -> return if (!skipSpaceOrEnd()) Token.END else throw syntax("More than one top-level value")
        }
        return when (val c = nextNonSpace()) {
            '{' -> Token.BEGIN_OBJECT
            '[' -> Token.BEGIN_ARRAY
            ']' -> if (top == EMPTY_ARRAY) Token.END_ARRAY else throw syntax("Unexpected ']'")
            '"' -> { pos--; Token.STRING }
            't', 'f' -> { pos--; Token.BOOLEAN }
            'n' -> { pos--; Token.NULL }
            else -> if (c == '-' || c in '0'..'9') { pos--; Token.NUMBER } else throw syntax("Unexpected character '$c'")
        }
    }

    private fun fill(): Boolean {
        if (pos < limit) return true
        val n = reader.read(buf, 0, buf.size)
        if (n <= 0) return false
        pos = 0
        limit = n
        return true
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿'

    private fun nextNonSpace(): Char {
        while (true) {
            if (pos >= limit && !fill()) throw syntax("Unexpected end of input")
            val c = buf[pos++]
            if (!isSpace(c)) return c
        }
    }

    /** Skips whitespace; false at the end of input. */
    private fun skipSpaceOrEnd(): Boolean {
        while (true) {
            if (pos >= limit && !fill()) return false
            if (!isSpace(buf[pos])) return true
            pos++
        }
    }

    private fun readQuoted(): String {
        if (nextNonSpace() != '"') throw syntax("Expected '\"'")
        sb.setLength(0)
        while (true) {
            if (pos >= limit && !fill()) throw syntax("Unterminated string")
            val start = pos
            while (pos < limit) {
                val c = buf[pos]
                if (c == '"' || c == '\\') break
                pos++
            }
            sb.appendRange(buf, start, pos)
            if (pos < limit) {
                if (buf[pos++] == '"') return sb.toString()
                sb.append(readEscape())
            }
        }
    }

    private fun skipQuoted() {
        if (nextNonSpace() != '"') throw syntax("Expected '\"'")
        while (true) {
            if (pos >= limit && !fill()) throw syntax("Unterminated string")
            val c = buf[pos++]
            if (c == '"') return
            if (c == '\\') readEscape()
        }
    }

    private fun readEscape(): Char {
        if (pos >= limit && !fill()) throw syntax("Unterminated escape")
        return when (val e = buf[pos++]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                var v = 0
                repeat(4) {
                    if (pos >= limit && !fill()) throw syntax("Unterminated escape")
                    val h = Character.digit(buf[pos++], 16)
                    if (h < 0) throw syntax("Bad \\u escape")
                    v = v * 16 + h
                }
                v.toChar()
            }
            else -> e
        }
    }

    private fun readLiteral(): String {
        nextNonSpace()
        pos--
        sb.setLength(0)
        while (true) {
            if (pos >= limit && !fill()) break
            val c = buf[pos]
            if (c == ',' || c == '}' || c == ']' || c == ':' || isSpace(c)) break
            sb.append(c)
            pos++
        }
        if (sb.isEmpty()) throw syntax("Expected a value")
        return sb.toString()
    }

    /** A number literal; a malformed one ("1.2.3", "-", "12abc") is a syntax error like any other, not a crash. */
    private fun number(literal: String): Double =
        try {
            literal.toDouble()
        } catch (_: NumberFormatException) {
            throw syntax("Not a number: ${literal.take(40)}")
        }

    private fun syntax(message: String) = IOException("JSON: $message")

    private companion object {
        const val EMPTY_ARRAY = 1
        const val NONEMPTY_ARRAY = 2
        const val EMPTY_OBJECT = 3
        const val DANGLING_NAME = 4
        const val NONEMPTY_OBJECT = 5
        const val EMPTY_DOCUMENT = 6
        const val NONEMPTY_DOCUMENT = 7
    }
}
