package app.atlas.places

/** A numeric comparison in a [TagFilter.Compare]. */
enum class CompareOp(val symbol: String) {
    LT("<"), LE("<="), GT(">"), GE(">=");

    fun test(a: Double, b: Double): Boolean = when (this) {
        LT -> a < b
        LE -> a <= b
        GT -> a > b
        GE -> a >= b
    }
}

/**
 * A test on a feature's tags, shared by categories and search. Values are `;`-aware: `cuisine=pizza` matches
 * "italian;pizza". Regex tests (`~`) are case-insensitive and find a match anywhere, as Overpass `~` does with `,i`.
 * Parse the text form with [TagFilter.parse]:
 * `amenity=cafe and outdoor_seating=yes and (cuisine~coffee|cake) and not access=private`.
 */
sealed class TagFilter {
    abstract fun matches(tags: Map<String, String>): Boolean

    /** The text form, which [parse] reads back to an equal filter. */
    abstract fun toText(): String

    /** Every key the filter reads (key regexes are left out). */
    abstract fun keys(): Set<String>

    /** The Overpass QL tag selectors when the filter is a plain conjunction, e.g. `[amenity=cafe][!fee]`; else null. */
    open fun toOverpass(): String? = null

    override fun toString() = toText()

    /** The key is present (with any value). */
    data class Has(val key: String) : TagFilter() {
        override fun matches(tags: Map<String, String>) = key in tags
        override fun toText() = quoteKey(key)
        override fun keys() = setOf(key)
        override fun toOverpass() = "[${ql(key)}]"
    }

    /** The value, or one of its `;` parts, equals [value]. */
    data class Equals(val key: String, val value: String) : TagFilter() {
        override fun matches(tags: Map<String, String>) = tags[key]?.let { anyPart(it) { p -> p == value } } ?: false
        override fun toText() = "${quoteKey(key)}=${quoteValue(value)}"
        override fun keys() = setOf(key)
        override fun toOverpass() = "[${ql(key)}=${ql(value)}]"
    }

    /** Not [Equals]: true too when the key is missing, as Overpass `[k!=v]`. */
    data class NotEquals(val key: String, val value: String) : TagFilter() {
        override fun matches(tags: Map<String, String>) = tags[key]?.let { v -> !anyPart(v) { it == value } } ?: true
        override fun toText() = "${quoteKey(key)}!=${quoteValue(value)}"
        override fun keys() = setOf(key)
        override fun toOverpass() = "[${ql(key)}!=${ql(value)}]"
    }

    /**
     * The value (or a part of it) matches [pattern]; with [negate], no part does or the key is missing.
     * Anchored lists of plain words, `^(bar|pub|cafe)$`, are tested with a set lookup instead of the regex engine.
     */
    data class Matches(val key: String, val pattern: String, val negate: Boolean = false) : TagFilter() {
        private val regex by lazy { Regex(pattern, RegexOption.IGNORE_CASE) }
        private val words: Set<String>? by lazy { literalAlternatives(pattern) }
        override fun matches(tags: Map<String, String>): Boolean {
            val v = tags[key] ?: return negate
            val set = words
            val hit = if (set != null) {
                anyPart(v) { p -> p in set || p.lowercase() in set }
            } else {
                regex.containsMatchIn(v) || (';' in v && v.split(';').any { regex.containsMatchIn(it.trim()) })
            }
            return hit != negate
        }
        override fun toText() = "${quoteKey(key)}${if (negate) "!~" else "~"}${quoteValue(pattern)}"
        override fun keys() = setOf(key)
        override fun toOverpass() = "[${ql(key)}${if (negate) "!~" else "~"}${ql(pattern)},i]"
    }

    /** Some key matches [keyPattern] and, when given, its value matches [valuePattern]. */
    data class KeyMatches(val keyPattern: String, val valuePattern: String? = null) : TagFilter() {
        private val keyRegex by lazy { Regex(keyPattern, RegexOption.IGNORE_CASE) }
        private val valueRegex by lazy { valuePattern?.let { Regex(it, RegexOption.IGNORE_CASE) } }
        override fun matches(tags: Map<String, String>) =
            tags.any { (k, v) -> keyRegex.containsMatchIn(k) && (valueRegex?.containsMatchIn(v) ?: true) }
        override fun toText() = "~${quote(keyPattern)}" + (valuePattern?.let { "~${quote(it)}" } ?: "")
        override fun keys() = emptySet<String>()
        override fun toOverpass() = "[~${ql(keyPattern)}~${ql(valuePattern ?: ".")},i]"
    }

    /**
     * A numeric test with units: `maxheight>=4 m`, `"socket:type2_combo:output">=150kW`. A tag value without a
     * unit is read in [unit]'s base unit (metres, tonnes, kW…); with several `;` values, any may pass.
     */
    data class Compare(val key: String, val op: CompareOp, val value: Double, val unit: String? = null) : TagFilter() {
        private val target by lazy { Units.toBase(value, unit) }
        override fun matches(tags: Map<String, String>): Boolean {
            val v = tags[key] ?: return false
            val t = target
            for (q in Units.parseAll(v)) {
                val ok = when {
                    t.dimension == Dimension.NONE -> true
                    q.dimension == Dimension.NONE || q.dimension == t.dimension -> true
                    else -> false
                }
                if (ok && op.test(q.value, t.value)) return true
            }
            return false
        }
        override fun toText() = "${quoteKey(key)}${op.symbol}${formatNumber(value)}${unit?.let { " $it" } ?: ""}"
        override fun keys() = setOf(key)
    }

    /** Every part holds. */
    data class And(val parts: List<TagFilter>) : TagFilter() {
        override fun matches(tags: Map<String, String>) = parts.all { it.matches(tags) }
        override fun toText() = parts.joinToString(" and ") { if (it is Or) "(${it.toText()})" else it.toText() }
        override fun keys() = parts.flatMapTo(LinkedHashSet()) { it.keys() }
        override fun toOverpass(): String? {
            val sb = StringBuilder()
            for (p in parts) sb.append(p.toOverpass() ?: return null)
            return sb.toString()
        }
    }

    /** At least one part holds. */
    data class Or(val parts: List<TagFilter>) : TagFilter() {
        override fun matches(tags: Map<String, String>) = parts.any { it.matches(tags) }
        override fun toText() = parts.joinToString(" or ") { it.toText() }
        override fun keys() = parts.flatMapTo(LinkedHashSet()) { it.keys() }
    }

    /** The part does not hold. */
    data class Not(val part: TagFilter) : TagFilter() {
        override fun matches(tags: Map<String, String>) = !part.matches(tags)
        override fun toText() = when (part) {
            is And, is Or -> "not (${part.toText()})"
            else -> "not ${part.toText()}"
        }
        override fun keys() = part.keys()
        override fun toOverpass(): String? = when (part) {
            is Has -> "[!${ql(part.key)}]"
            is Equals -> "[${ql(part.key)}!=${ql(part.value)}]"
            is Matches -> "[${ql(part.key)}${if (part.negate) "~" else "!~"}${ql(part.pattern)},i]"
            else -> null
        }
    }

    /** Matches everything. */
    data object All : TagFilter() {
        override fun matches(tags: Map<String, String>) = true
        override fun toText() = "*"
        override fun keys() = emptySet<String>()
        override fun toOverpass() = ""
    }

    companion object {
        /** Parses the text form; throws [TagFilterException] with the position of the first problem. */
        fun parse(text: String): TagFilter = TagFilterParser(text).parse()

        fun parseOrNull(text: String): TagFilter? = try { parse(text) } catch (e: TagFilterException) { null }

        fun and(vararg parts: TagFilter): TagFilter = if (parts.size == 1) parts[0] else And(parts.toList())
        fun or(vararg parts: TagFilter): TagFilter = if (parts.size == 1) parts[0] else Or(parts.toList())

        internal inline fun anyPart(v: String, test: (String) -> Boolean): Boolean {
            if (test(v)) return true
            if (';' !in v) return false
            var start = 0
            while (start <= v.length) {
                val end = v.indexOf(';', start).let { if (it < 0) v.length else it }
                if (test(v.substring(start, end).trim())) return true
                start = end + 1
            }
            return false
        }

        /** The words of a pattern that only lists literal alternatives, `^(a|b|c)$` or `^a$`, lower-cased; else null. */
        internal fun literalAlternatives(p: String): Set<String>? {
            if (p.length < 3 || p[0] != '^' || p[p.length - 1] != '$') return null
            var body = p.substring(1, p.length - 1)
            val grouped = body.length >= 2 && body[0] == '(' && body[body.length - 1] == ')'
            if (grouped) body = body.substring(1, body.length - 1)
            if (body.isEmpty() || ('|' in body && !grouped)) return null
            for (c in body) if (!(c.isLetterOrDigit() || c == '_' || c == ':' || c == '|' || c == '-' || c == ' ')) return null
            return body.split('|').mapTo(HashSet()) { it.lowercase() }
        }

        private val bare = Regex("[A-Za-z0-9_:.\\-@/]+")

        internal fun quoteKey(k: String) = if (bare.matches(k) && k.lowercase() !in TagFilterParser.keywords) k else quote(k)
        internal fun quoteValue(v: String) =
            if (v.isNotEmpty() && v != "*" && v.none { it.isWhitespace() || it == '"' || it == '\'' } && balanced(v) &&
                !v.startsWith("(") && "||" !in v && "&&" !in v
            ) v else quote(v)

        private fun balanced(v: String): Boolean {
            var d = 0
            for (c in v) { if (c == '(') d++ else if (c == ')') { d--; if (d < 0) return false } }
            return d == 0
        }

        internal fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun ql(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** A number as the parser reads it back: no exponent, no trailing zeros ("4.2", "150", "0.00001"). */
        internal fun formatNumber(d: Double): String =
            if (d.isNaN() || d.isInfinite()) d.toString() else java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    }
}

/** A parse error in a tag filter's text; [index] is where it was found. */
class TagFilterException(message: String, val index: Int) : IllegalArgumentException(message)

/** Recursive descent over the text form: or < and (also implied by a space) < not < parentheses and terms. */
internal class TagFilterParser(private val s: String) {
    private var i = 0

    fun parse(): TagFilter {
        skip()
        if (i >= s.length) throw TagFilterException("empty filter", 0)
        val f = or()
        skip()
        if (i < s.length) throw TagFilterException("unexpected '${s[i]}'", i)
        return f
    }

    private fun or(): TagFilter {
        val parts = mutableListOf(and())
        while (true) {
            skip()
            if (word("or") || symbol("||") || (peek() == '|' && symbol("|"))) parts += and() else break
        }
        return if (parts.size == 1) parts[0] else TagFilter.Or(parts.flatMap { if (it is TagFilter.Or) it.parts else listOf(it) })
    }

    private fun and(): TagFilter {
        val parts = mutableListOf(unary())
        while (true) {
            skip()
            if (i >= s.length || peek() == ')' || atWord("or") || s.startsWith("||", i) || peek() == '|') break
            if (!word("and") && !symbol("&&")) symbol("&")
            parts += unary()
        }
        return if (parts.size == 1) parts[0] else TagFilter.And(parts.flatMap { if (it is TagFilter.And) it.parts else listOf(it) })
    }

    private fun unary(): TagFilter {
        skip()
        if (word("not")) return TagFilter.Not(unary())
        if (peek() == '!' && !s.startsWith("!=", i) && !s.startsWith("!~", i)) { i++; return TagFilter.Not(unary()) }
        if (peek() == '(') {
            i++
            val f = or()
            skip()
            if (peek() != ')') throw TagFilterException("')' expected", i)
            i++
            return f
        }
        return term()
    }

    private fun term(): TagFilter {
        skip()
        if (i >= s.length) throw TagFilterException("tag expected", i)
        if (s[i] == '*') { i++; return TagFilter.All }
        if (s[i] == '~') {
            i++
            val kp = if (peek() == '"' || peek() == '\'') quoted() else until('~')
            skip()
            if (peek() != '~') return TagFilter.KeyMatches(checkedRegex(kp))
            i++
            skip()
            return TagFilter.KeyMatches(checkedRegex(kp), checkedRegex(value()))
        }
        val key = key()
        skip()
        val op = when {
            s.startsWith("!=", i) -> "!="
            s.startsWith("!~", i) -> "!~"
            s.startsWith(">=", i) -> ">="
            s.startsWith("<=", i) -> "<="
            s.startsWith("==", i) -> "=="
            peek() == '=' -> "="
            peek() == '~' -> "~"
            peek() == '>' -> ">"
            peek() == '<' -> "<"
            else -> return TagFilter.Has(key)
        }
        i += op.length
        skip()
        return when (op) {
            // a bare * is "any value"; a quoted "*" is the value itself
            "=", "==" -> value().let { if (it == "*" && !quotedValue) TagFilter.Has(key) else TagFilter.Equals(key, it) }
            "!=" -> value().let { if (it == "*" && !quotedValue) TagFilter.Not(TagFilter.Has(key)) else TagFilter.NotEquals(key, it) }
            "~" -> TagFilter.Matches(key, checkedRegex(value()))
            "!~" -> TagFilter.Matches(key, checkedRegex(value()), negate = true)
            else -> compare(key, CompareOp.entries.first { it.symbol == op })
        }
    }

    private fun compare(key: String, op: CompareOp): TagFilter {
        val start = i
        val text = value()
        if ('\'' in text) {
            // feet and inches, 12'6": kept in metres
            val m = Units.parse(text)?.takeIf { it.dimension == Dimension.LENGTH }
                ?: throw TagFilterException("length expected after ${op.symbol}", start)
            return TagFilter.Compare(key, op, Math.round(m.value * 1000) / 1000.0, "m")
        }
        val m = Regex("""^([-+]?(?:\d+(?:[.,]\d+)?|[.,]\d+))\s*([A-Za-z/'"]*)$""").matchEntire(text)
            ?: throw TagFilterException("number expected after ${op.symbol}", start)
        val n = m.groupValues[1].replace(',', '.').toDouble()
        var unit = m.groupValues[2].ifEmpty { null }
        if (unit == null) {
            // "4.2 m": a unit written after a space
            val save = i
            skip()
            val w = Regex("[A-Za-z/]+").find(s, i)?.takeIf { it.range.first == i }?.value
            // a word that is followed by an operator is the next term's key ("capacity>=10 a=b"), not a unit
            if (w != null && Units.isUnit(w) && w.lowercase() !in keywords &&
                (i + w.length >= s.length || !isKeyChar(s[i + w.length])) && !operatorAt(i + w.length)
            ) {
                unit = w; i += w.length
            } else {
                i = save
            }
        }
        if (unit != null && !Units.isUnit(unit)) throw TagFilterException("unknown unit '$unit'", start)
        return TagFilter.Compare(key, op, n, unit)
    }

    /** True when, after spaces, an operator starts at [from]. */
    private fun operatorAt(from: Int): Boolean {
        var j = from
        while (j < s.length && s[j].isWhitespace()) j++
        return j < s.length && s[j] in "=!~<>"
    }

    private fun checkedRegex(p: String): String {
        try { Regex(p) } catch (e: Exception) { throw TagFilterException("bad regular expression '$p'", i) }
        return p
    }

    private fun key(): String {
        if (peek() == '"' || peek() == '\'') return quoted()
        val start = i
        while (i < s.length && isKeyChar(s[i])) i++
        if (i == start) throw TagFilterException("tag key expected", i)
        return s.substring(start, i)
    }

    private fun isKeyChar(c: Char) = !c.isWhitespace() && c !in "=!~<>()&|\"'"

    /** Whether the last [value] was written in quotes. */
    private var quotedValue = false

    private fun value(): String {
        quotedValue = peek() == '"' || peek() == '\''
        if (quotedValue) return quoted()
        val start = i
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace() && depth == 0) break
            // "a=b||c=d", "a=b&&c": the operators end an unquoted value, as they do with spaces around them
            if (depth == 0 && (s.startsWith("||", i) || s.startsWith("&&", i))) break
            if (c == '(') depth++
            if (c == ')') { if (depth == 0) break; depth-- }
            i++
        }
        if (i == start) throw TagFilterException("value expected", i)
        return s.substring(start, i)
    }

    private fun until(stop: Char): String {
        val start = i
        while (i < s.length && s[i] != stop && !s[i].isWhitespace()) i++
        if (i == start) throw TagFilterException("pattern expected", i)
        return s.substring(start, i)
    }

    private fun quoted(): String {
        val q = s[i]
        val sb = StringBuilder()
        i++
        while (i < s.length && s[i] != q) {
            if (s[i] == '\\' && i + 1 < s.length) i++
            sb.append(s[i]); i++
        }
        if (i >= s.length) throw TagFilterException("unclosed quote", i)
        i++
        return sb.toString()
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun atWord(w: String): Boolean =
        s.regionMatches(i, w, 0, w.length, ignoreCase = true) && (i + w.length >= s.length || s[i + w.length].isWhitespace() || s[i + w.length] == '(')

    private fun word(w: String): Boolean {
        if (!atWord(w)) return false
        i += w.length
        return true
    }

    private fun symbol(sym: String): Boolean {
        if (!s.startsWith(sym, i)) return false
        i += sym.length
        return true
    }

    companion object {
        val keywords = setOf("and", "or", "not")
    }
}
