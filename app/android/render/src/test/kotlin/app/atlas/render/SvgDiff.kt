package app.atlas.render

/**
 * Compares two SVG documents element by element: tags and attributes exactly, numbers inside attribute values
 * and text within a tolerance. It reports the first divergences with their element path and nearest id or
 * `data-*`, counts by element kind, and a similarity score. The end-to-end tests of the port all use it.
 */
object SvgDiff {
    /** One parsed element, or a text run (tag "#text", the text in [text]). */
    class Node(val tag: String, val parent: Node?) {
        val attrs = LinkedHashMap<String, String>(4)
        val children = ArrayList<Node>(0)
        var text: String = ""

        /** The element's index among its parent's children of the same tag. */
        var nth = 0
        private var tagCounts: HashMap<String, Int>? = null

        /** Adds [child] as the last child, numbering it among its same-tag siblings. */
        fun add(child: Node) {
            val m = tagCounts ?: HashMap<String, Int>().also { tagCounts = it }
            val k = m[child.tag] ?: 0
            child.nth = k
            m[child.tag] = k + 1
            children.add(child)
        }

        /** A hash of the tag, attributes and text, for re-aligning after an insertion or deletion. */
        val signature: Int by lazy { (tag.hashCode() * 31 + attrs.hashCode()) * 31 + text.hashCode() }

        /** "svg/g[2]/path[17]": tags with their index among same-tag siblings. */
        fun path(): String {
            val parts = ArrayList<String>()
            var n: Node? = this
            while (n != null && n.tag != "#document") {
                parts.add(if (n.parent == null || n.parent.tag == "#document") n.tag else "${n.tag}[${n.nth}]")
                n = n.parent
            }
            return parts.asReversed().joinToString("/")
        }

        /** The nearest `id` or `data-*` attribute on this element or an ancestor, as `name="value"`. */
        fun context(): String? {
            var n: Node? = this
            while (n != null) {
                n.attrs["id"]?.let { return "id=\"$it\" on ${n!!.tag}" }
                for ((k, v) in n.attrs) if (k.startsWith("data-")) return "$k=\"$v\" on ${n.tag}"
                n = n.parent
            }
            return null
        }

        /** The number of elements (not text runs) in this subtree, this one included. */
        fun count(): Int {
            var c = if (tag == "#text") 0 else 1
            for (ch in children) c += ch.count()
            return c
        }
    }

    // ---------------------------------------------------------------- the tolerant reader

    /** Parses [xml] into a document node. It forgives unknown entities, unclosed and stray closing tags. */
    fun parse(xml: String): Node {
        val doc = Node("#document", null)
        var cur = doc
        var i = 0
        val n = xml.length
        val text = StringBuilder()
        fun flushText() {
            if (text.isEmpty()) return
            val t = text.toString()
            text.setLength(0)
            if (t.isBlank()) return
            val tn = Node("#text", cur)
            tn.text = decode(t)
            cur.add(tn)
        }
        while (i < n) {
            val c = xml[i]
            if (c != '<') {
                text.append(c); i++; continue
            }
            when {
                xml.startsWith("<!--", i) -> {
                    val e = xml.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                }
                xml.startsWith("<![CDATA[", i) -> {
                    val e = xml.indexOf("]]>", i + 9)
                    val end = if (e < 0) n else e
                    // CDATA is literal: escape it so decoding gives it back unchanged
                    text.append(xml, i + 9, end)
                    i = if (e < 0) n else e + 3
                }
                xml.startsWith("<?", i) -> {
                    val e = xml.indexOf("?>", i + 2)
                    i = if (e < 0) n else e + 2
                }
                xml.startsWith("<!", i) -> {
                    val e = xml.indexOf('>', i + 2)
                    i = if (e < 0) n else e + 1
                }
                i + 1 < n && xml[i + 1] == '/' -> {
                    flushText()
                    val e = xml.indexOf('>', i + 2)
                    val name = xml.substring(i + 2, if (e < 0) n else e).trim()
                    i = if (e < 0) n else e + 1
                    // close up to the matching open element; a stray closing tag is ignored
                    var m: Node? = cur
                    while (m != null && m.tag != name) m = m.parent
                    if (m != null && m.parent != null) cur = m.parent
                }
                else -> {
                    flushText()
                    var j = i + 1
                    while (j < n && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
                    val node = Node(xml.substring(i + 1, j), cur)
                    var selfClose = false
                    while (j < n) {
                        while (j < n && xml[j].isWhitespace()) j++
                        if (j >= n) break
                        if (xml[j] == '>') {
                            j++; break
                        }
                        if (xml[j] == '/') {
                            selfClose = true; j++; continue
                        }
                        val ks = j
                        while (j < n && xml[j] != '=' && !xml[j].isWhitespace() && xml[j] != '>' && xml[j] != '/') j++
                        val key = xml.substring(ks, j)
                        while (j < n && xml[j].isWhitespace()) j++
                        if (j < n && xml[j] == '=') {
                            j++
                            while (j < n && xml[j].isWhitespace()) j++
                            if (j < n && (xml[j] == '"' || xml[j] == '\'')) {
                                val q = xml[j]
                                val e = xml.indexOf(q, j + 1).let { if (it < 0) n else it }
                                node.attrs[key] = decode(xml.substring(j + 1, e))
                                j = e + 1
                            } else {
                                val vs = j
                                while (j < n && !xml[j].isWhitespace() && xml[j] != '>') j++
                                node.attrs[key] = decode(xml.substring(vs, j))
                            }
                        } else if (key.isNotEmpty()) {
                            node.attrs[key] = ""
                        }
                    }
                    i = j
                    cur.add(node)
                    if (!selfClose) cur = node
                }
            }
        }
        flushText()
        return doc
    }

    private fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val e = s.indexOf(';', i)
                if (e > i && e - i <= 10) {
                    val ent = s.substring(i + 1, e)
                    val r = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (r != null) {
                        sb.append(r); i = e + 1; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- comparing

    /** One difference: where, what, and both values (null where one side has nothing). */
    class Divergence(val path: String, val what: String, val expected: String?, val actual: String?, val context: String?) {
        override fun toString(): String =
            "$path: $what\n    expected: ${clip(expected)}\n    actual:   ${clip(actual)}" + (context?.let { "\n    near $it" } ?: "")
    }

    /** Per element kind: how many each side has, and how many of the aligned pairs differ. */
    class KindCount(var expected: Int = 0, var actual: Int = 0, var differing: Int = 0, var withinTolerance: Int = 0)

    /** The outcome of [compare]. */
    class Result(
        val identicalText: Boolean,
        val expectedElements: Int,
        val actualElements: Int,
        /** Element pairs aligned with each other. */
        val aligned: Int,
        /** Aligned pairs equal exactly or within the tolerance. */
        val equal: Int,
        /** Pairs equal only within the tolerance (some number differs by at most the tolerance). */
        val withinTolerance: Int,
        /** Every divergence found (attributes, text, missing and extra elements). */
        val divergenceCount: Int,
        val missing: Int,
        val extra: Int,
        val first: List<Divergence>,
        val byKind: Map<String, KindCount>,
    ) {
        /** Equal pairs over the larger document's element count: 1.0 is the same document. */
        val similarity: Double get() = if (Math.max(expectedElements, actualElements) == 0) 1.0 else equal.toDouble() / Math.max(expectedElements, actualElements)

        /** True when nothing diverges (numbers may differ within the tolerance). */
        val same: Boolean get() = divergenceCount == 0

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append(if (identicalText) "byte-identical" else "not byte-identical")
            sb.append("; elements expected $expectedElements, actual $actualElements; aligned $aligned, equal $equal")
            if (withinTolerance > 0) sb.append(" ($withinTolerance within tolerance)")
            sb.append("; divergences $divergenceCount (missing $missing, extra $extra); similarity ")
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", similarity))
            val bad = byKind.filter { it.value.differing > 0 || it.value.expected != it.value.actual }
            if (bad.isNotEmpty()) {
                sb.append("\n  by kind (expected/actual/differing):")
                for ((k, v) in bad) sb.append(" $k ${v.expected}/${v.actual}/${v.differing}")
            }
            for (d in first) sb.append("\n  ").append(d)
            return sb.toString()
        }
    }

    private fun clip(s: String?): String {
        if (s == null) return "(none)"
        return if (s.length <= 300) s else s.substring(0, 300) + "… (${s.length} chars)"
    }

    /** Compares [expected] with [actual]; numbers may differ by [tol]. Keeps the first [keep] divergences. */
    fun compare(expected: String, actual: String, tol: Double = 0.01, keep: Int = 20): Result {
        val c = Comparer(tol, keep)
        val a = parse(expected)
        val b = parse(actual)
        c.countKinds(a, true)
        c.countKinds(b, false)
        c.children(a, b)
        return Result(
            expected == actual, a.count() - 1, b.count() - 1, c.aligned, c.equal, c.within, c.divergences, c.missing, c.extra,
            c.first, c.kinds,
        )
    }

    private class Comparer(val tol: Double, val keep: Int) {
        var aligned = 0
        var equal = 0
        var within = 0
        var divergences = 0
        var missing = 0
        var extra = 0
        val first = ArrayList<Divergence>()
        val kinds = LinkedHashMap<String, KindCount>()

        fun countKinds(n: Node, expected: Boolean) {
            if (n.tag != "#document" && n.tag != "#text") {
                val k = kinds.getOrPut(n.tag) { KindCount() }
                if (expected) k.expected++ else k.actual++
            }
            for (ch in n.children) countKinds(ch, expected)
        }

        fun add(d: Divergence) {
            divergences++
            if (first.size < keep) first.add(d)
        }

        /** Compares two child lists, re-aligning after an inserted or deleted element. */
        fun children(a: Node, b: Node) {
            val xs = a.children
            val ys = b.children
            var i = 0
            var j = 0
            while (i < xs.size && j < ys.size) {
                val x = xs[i]
                val y = ys[j]
                if (x.tag == y.tag && (x.signature == y.signature || score(x, y) >= 0.5)) {
                    pair(x, y); i++; j++; continue
                }
                // look ahead for the nearest re-alignment: skip some of xs (missing) or of ys (extra)
                val (di, dj) = resync(xs, ys, i, j)
                if (di == dj) {
                    // nothing re-aligns, or as many on each side: elements were changed, not inserted or deleted
                    pair(x, y); i++; j++; continue
                }
                repeat(di) { gone(xs[i + it]) }
                repeat(dj) { added(ys[j + it]) }
                i += di; j += dj
            }
            while (i < xs.size) gone(xs[i++])
            while (j < ys.size) added(ys[j++])
        }

        /** The smallest skip (di, dj), with di + dj up to a window, after which the next pair is identical; (0, 0) if none. */
        private fun resync(xs: List<Node>, ys: List<Node>, i: Int, j: Int): Pair<Int, Int> {
            for (k in 1..WINDOW) {
                for (di in 0..k) {
                    val dj = k - di
                    if (i + di >= xs.size || j + dj >= ys.size) continue
                    if (xs[i + di].signature == ys[j + dj].signature && xs[i + di].tag == ys[j + dj].tag) return di to dj
                }
            }
            return 0 to 0
        }

        /** How alike two elements are, 0..1, from their attributes (tags already equal). */
        private fun score(x: Node, y: Node): Double {
            if (x.tag == "#text") return if (sameValue(x.text, y.text) != null) 1.0 else 0.0
            val keys = LinkedHashSet<String>(x.attrs.keys).apply { addAll(y.attrs.keys) }
            if (keys.isEmpty()) return if (x.children.size == y.children.size) 1.0 else 0.9
            var ok = 0
            for (k in keys) {
                val a = x.attrs[k]
                val b = y.attrs[k]
                if (a != null && b != null && sameValue(a, b) != null) ok++
            }
            return ok.toDouble() / keys.size
        }

        private fun gone(x: Node) {
            missing++
            add(Divergence(x.path(), "missing ${describe(x)}", describe(x), null, x.context()))
        }

        private fun added(y: Node) {
            extra++
            add(Divergence(y.path(), "extra ${describe(y)}", null, describe(y), y.context()))
        }

        private fun describe(n: Node): String =
            if (n.tag == "#text") "text \"${clip(n.text)}\"" else "<${n.tag}" + n.attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" } + ">"

        private fun pair(x: Node, y: Node) {
            if (x.tag != "#text") aligned++
            var differs = false
            var tolerant = false
            if (x.tag == "#text") {
                val s = sameValue(x.text, y.text)
                if (s == null) add(Divergence(x.parent!!.path(), "text", x.text, y.text, x.context()))
                return
            }
            if (x.tag != y.tag) {
                add(Divergence(x.path(), "tag <${x.tag}> became <${y.tag}>", x.tag, y.tag, x.context()))
                differs = true
            } else {
                for ((k, a) in x.attrs) {
                    val b = y.attrs[k]
                    if (b == null) {
                        add(Divergence(x.path(), "attribute $k missing", a, null, x.context())); differs = true; continue
                    }
                    when (sameValue(a, b)) {
                        null -> {
                            add(Divergence(x.path(), "attribute $k", a, b, x.context())); differs = true
                        }
                        false -> tolerant = true
                        true -> {}
                    }
                }
                for ((k, b) in y.attrs) if (k !in x.attrs) {
                    add(Divergence(y.path(), "attribute $k extra", null, b, y.context())); differs = true
                }
            }
            val kc = kinds.getOrPut(x.tag) { KindCount() }
            if (differs) kc.differing++ else {
                equal++
                if (tolerant) {
                    within++; kc.withinTolerance++
                }
            }
            if (x.tag == y.tag) children(x, y) else {
                // different elements: their subtrees count as missing and extra
                for (ch in x.children) if (ch.tag != "#text") gone(ch)
                for (ch in y.children) if (ch.tag != "#text") added(ch)
            }
        }

        /** True when equal, false when equal only within the tolerance, null when different. */
        fun sameValue(a: String, b: String): Boolean? {
            if (a == b) return true
            // colours, references and names compare exactly
            if (a.indexOf('#') >= 0 || b.indexOf('#') >= 0) return null
            val ta = Numbers(a)
            val tb = Numbers(b)
            if (ta.skeleton != tb.skeleton || ta.values.size != tb.values.size || ta.values.isEmpty()) return null
            for (k in ta.values.indices) if (Math.abs(ta.values[k] - tb.values[k]) > tol + 1e-9) return null
            return false
        }
    }

    /** A value split into its numbers and the text around them (the skeleton, with each number as `#`). */
    private class Numbers(s: String) {
        val values = ArrayList<Double>()
        val skeleton: String

        init {
            val sk = StringBuilder(s.length)
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                val startsNumber = c.isDigit() || ((c == '-' || c == '+' || c == '.') && i + 1 < n && (s[i + 1].isDigit() || (s[i + 1] == '.' && c != '.' && i + 2 < n && s[i + 2].isDigit())))
                if (!startsNumber) {
                    sk.append(c); i++; continue
                }
                val st = i
                if (c == '-' || c == '+') i++
                var dot = false
                while (i < n && (s[i].isDigit() || (s[i] == '.' && !dot))) {
                    if (s[i] == '.') dot = true
                    i++
                }
                if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                    var k = i + 1
                    if (k < n && (s[k] == '-' || s[k] == '+')) k++
                    if (k < n && s[k].isDigit()) {
                        i = k
                        while (i < n && s[i].isDigit()) i++
                    }
                }
                val v = s.substring(st, i).toDoubleOrNull()
                if (v == null) {
                    sk.append(s, st, i)
                } else {
                    values.add(v); sk.append('#')
                }
            }
            skeleton = sk.toString()
        }
    }

    private const val WINDOW = 64
}
