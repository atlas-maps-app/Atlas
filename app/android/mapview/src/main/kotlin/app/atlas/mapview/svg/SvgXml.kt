package app.atlas.mapview.svg

/**
 * A small, tolerant pull reader for the XML the engine writes. It reads a char array in place: names and attribute
 * values are spans of it, so a path's `d` is parsed without becoming a string. Comments, processing instructions,
 * doctypes and CDATA are skipped or read as text; entities are decoded only when a value becomes a string.
 */
internal class SvgXml(private val s: CharArray, private val n: Int = s.size) {
    private var pos = 0

    /** The current tag's name span. */
    var nameStart = 0
        private set
    var nameEnd = 0
        private set

    /** True for `<x/>`: the reader then reports [END] for it next. */
    var selfClosing = false
        private set

    var attrCount = 0
        private set
    private var attrs = IntArray(4 * 16)

    /** The text span of a [TEXT] event. */
    var textStart = 0
        private set
    var textEnd = 0
        private set

    private var pendingEnd = false

    /** Moves to the next start tag, end tag or text run and returns its kind. */
    fun next(): Int {
        if (pendingEnd) {
            pendingEnd = false
            return END
        }
        while (pos < n) {
            val c = s[pos]
            if (c != '<') {
                val start = pos
                while (pos < n && s[pos] != '<') pos++
                textStart = start
                textEnd = pos
                return TEXT
            }
            if (pos + 1 >= n) throw SvgFormatException("unexpected end of the document at $pos")
            when (s[pos + 1]) {
                '/' -> {
                    pos += 2
                    nameStart = pos
                    while (pos < n && !isSpace(s[pos]) && s[pos] != '>') pos++
                    nameEnd = pos
                    while (pos < n && s[pos] != '>') pos++
                    pos++
                    return END
                }
                '!' -> {
                    if (startsWith(pos, "<!--")) {
                        val end = indexOf("-->", pos + 4)
                        pos = if (end < 0) n else end + 3
                    } else if (startsWith(pos, "<![CDATA[")) {
                        val end = indexOf("]]>", pos + 9)
                        textStart = pos + 9
                        textEnd = if (end < 0) n else end
                        pos = if (end < 0) n else end + 3
                        return TEXT
                    } else {
                        while (pos < n && s[pos] != '>') pos++
                        pos++
                    }
                }
                '?' -> {
                    val end = indexOf("?>", pos + 2)
                    pos = if (end < 0) n else end + 2
                }
                else -> {
                    readStart()
                    return START
                }
            }
        }
        return EOF
    }

    private fun readStart() {
        pos++
        nameStart = pos
        while (pos < n && !isSpace(s[pos]) && s[pos] != '>' && s[pos] != '/') pos++
        nameEnd = pos
        attrCount = 0
        selfClosing = false
        while (true) {
            while (pos < n && isSpace(s[pos])) pos++
            if (pos >= n) throw SvgFormatException("unterminated tag at $nameStart")
            val c = s[pos]
            if (c == '>') {
                pos++
                break
            }
            if (c == '/') {
                selfClosing = true
                while (pos < n && s[pos] != '>') pos++
                pos++
                break
            }
            val ns = pos
            while (pos < n && s[pos] != '=' && !isSpace(s[pos]) && s[pos] != '>' && s[pos] != '/') pos++
            val ne = pos
            while (pos < n && isSpace(s[pos])) pos++
            var vs = pos
            var ve = pos
            if (pos < n && s[pos] == '=') {
                pos++
                while (pos < n && isSpace(s[pos])) pos++
                val q = s[pos]
                if (q == '"' || q == '\'') {
                    vs = pos + 1
                    pos = vs
                    while (pos < n && s[pos] != q) pos++
                    ve = pos
                    pos++
                } else {
                    vs = pos
                    while (pos < n && !isSpace(s[pos]) && s[pos] != '>') pos++
                    ve = pos
                }
            }
            if (attrCount * 4 + 4 > attrs.size) attrs = attrs.copyOf(attrs.size * 2)
            val k = attrCount * 4
            attrs[k] = ns
            attrs[k + 1] = ne
            attrs[k + 2] = vs
            attrs[k + 3] = ve
            attrCount++
        }
        if (selfClosing) pendingEnd = true
    }

    fun name(): String = String(s, nameStart, nameEnd - nameStart)

    fun nameIs(t: String): Boolean = spanIs(nameStart, nameEnd, t)

    fun attrNameStart(i: Int) = attrs[4 * i]
    fun attrNameEnd(i: Int) = attrs[4 * i + 1]
    fun valueStart(i: Int) = attrs[4 * i + 2]
    fun valueEnd(i: Int) = attrs[4 * i + 3]

    /** The attribute's local name: `xlink:href` reads as `href`. */
    fun attrName(i: Int): String {
        var a = attrNameStart(i)
        val b = attrNameEnd(i)
        for (k in a until b) if (s[k] == ':') a = k + 1
        return String(s, a, b - a)
    }

    fun attrNameIs(i: Int, t: String) = spanIs(attrNameStart(i), attrNameEnd(i), t)

    /** The index of the attribute named [t], or -1. */
    fun find(t: String): Int {
        for (i in 0 until attrCount) if (attrNameIs(i, t)) return i
        return -1
    }

    fun value(i: Int): String = decode(valueStart(i), valueEnd(i))

    fun text(): String = decode(textStart, textEnd)

    val chars: CharArray get() = s

    private fun spanIs(a: Int, b: Int, t: String): Boolean {
        if (b - a != t.length) return false
        for (k in t.indices) if (s[a + k] != t[k]) return false
        return true
    }

    private fun startsWith(at: Int, t: String): Boolean {
        if (at + t.length > n) return false
        for (k in t.indices) if (s[at + k] != t[k]) return false
        return true
    }

    private fun indexOf(t: String, from: Int): Int {
        var i = from
        while (i + t.length <= n) {
            if (startsWith(i, t)) return i
            i++
        }
        return -1
    }

    private fun decode(a: Int, b: Int): String {
        var amp = -1
        for (k in a until b) if (s[k] == '&') {
            amp = k
            break
        }
        if (amp < 0) return String(s, a, b - a)
        val out = StringBuilder(b - a)
        out.appendRange(s, a, amp)
        var k = amp
        while (k < b) {
            val c = s[k]
            if (c != '&') {
                out.append(c)
                k++
                continue
            }
            var e = k + 1
            while (e < b && s[e] != ';' && e - k < 12) e++
            if (e >= b || s[e] != ';') {
                out.append(c)
                k++
                continue
            }
            val name = String(s, k + 1, e - k - 1)
            val code = when {
                name == "amp" -> '&'.code
                name == "lt" -> '<'.code
                name == "gt" -> '>'.code
                name == "quot" -> '"'.code
                name == "apos" -> '\''.code
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16) ?: -1
                name.startsWith("#") -> name.substring(1).toIntOrNull() ?: -1
                else -> -1
            }
            if (code < 0) {
                out.append(c)
                k++
            } else {
                out.appendCodePoint(code)
                k = e + 1
            }
        }
        return out.toString()
    }

    companion object {
        const val START = 1
        const val END = 2
        const val TEXT = 3
        const val EOF = 0

        fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r'
    }
}
