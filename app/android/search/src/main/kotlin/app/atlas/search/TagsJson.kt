package app.atlas.search

/** Flat JSON objects of strings, as the store keeps tags: written and read by hand, since it runs once per row. */
internal object TagsJson {
    fun write(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val sb = StringBuilder(map.size * 24)
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            string(sb, k)
            sb.append(':')
            string(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    /** Reads an object written by [write] (or any flat object of strings and scalars); scalars become their text. */
    fun read(s: String?): Map<String, String> {
        if (s == null || s.length < 2) return emptyMap()
        val out = HashMap<String, String>(16)
        var i = skip(s, 0)
        if (i >= s.length || s[i] != '{') return emptyMap()
        i = skip(s, i + 1)
        val sb = StringBuilder(32)
        while (i < s.length && s[i] != '}') {
            if (s[i] != '"') throw IllegalArgumentException("tags JSON: key expected at $i")
            i = quoted(s, i, sb)
            val key = sb.toString()
            i = skip(s, i)
            if (i >= s.length || s[i] != ':') throw IllegalArgumentException("tags JSON: ':' expected at $i")
            i = skip(s, i + 1)
            if (i < s.length && s[i] == '"') {
                i = quoted(s, i, sb)
                out[key] = sb.toString()
            } else {
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                val lit = s.substring(start, i).trim()
                if (lit != "null") out[key] = lit
            }
            i = skip(s, i)
            if (i < s.length && s[i] == ',') i = skip(s, i + 1)
        }
        return out
    }

    private fun skip(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** Reads the string starting at the quote at [start] into [sb]; returns the index after its closing quote. */
    private fun quoted(s: String, start: Int, sb: StringBuilder): Int {
        sb.setLength(0)
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return i + 1
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 5 >= s.length) throw IllegalArgumentException("tags JSON: bad escape at $i")
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        throw IllegalArgumentException("tags JSON: unclosed string")
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
