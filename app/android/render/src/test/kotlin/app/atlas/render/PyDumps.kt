package app.atlas.render

import app.atlas.render.py.PyFmt
import java.math.BigInteger

/**
 * Python's `json.dumps(v, ensure_ascii=False)` for the values the port keeps in dicts: float repr, `", "` and `": "`
 * separators, tuples ([V2], [V3], [Pair]) written as lists. Tests compare a dict's dump with Python's, text for text.
 */
object PyDumps {
    fun dumps(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is String -> string(sb, v)
            is Double -> sb.append(float(v))
            is Int, is Long, is BigInteger -> sb.append(v.toString())
            is V2 -> seq(sb, listOf(v.x, v.y))
            is V3 -> seq(sb, listOf(v.x, v.y, v.z))
            is Pair<*, *> -> seq(sb, listOf(v.first, v.second))
            is DoubleArray -> seq(sb, v.asList())
            is Iterable<*> -> seq(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(", ")
                    first = false
                    string(sb, k as String)
                    sb.append(": ")
                    write(sb, x)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("Object of type ${v::class.simpleName} is not JSON serializable")
        }
    }

    private fun seq(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        var first = true
        for (x in items) {
            if (!first) sb.append(", ")
            first = false
            write(sb, x)
        }
        sb.append(']')
    }

    private fun float(x: Double): String = when {
        x.isNaN() -> "NaN"
        x == Double.POSITIVE_INFINITY -> "Infinity"
        x == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> PyFmt.repr(x)
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch.code < 0x20 -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
