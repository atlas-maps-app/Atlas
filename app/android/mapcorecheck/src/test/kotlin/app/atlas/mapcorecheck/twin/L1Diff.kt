package app.atlas.mapcorecheck.twin

import app.atlas.anchor.AnchorCompiler
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgPaint
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgShape
import app.atlas.mapview.svg.SvgStyle
import app.atlas.mapview.svg.SvgText
import app.atlas.render.py.PyFmt

/**
 * L1, primitive parity (plan-vector D16): the port's SVG at a camera, as AnchorCompiler's parse reads it (SvgParser's
 * documents), against the twin's stream at the same camera (TwinCodec), element by element in painter's order.
 *
 * Both sides become [L1Element]s: the element's kind, its paints and style, its own node and the chain of groups
 * above it (opacity, blend, filter and clip by their SVG ids, transforms), and its vertices. Two elements match when
 * all but the vertices agree; a match is exact when every coordinate prints the same at `%.1f` (Python's, PyFmt), one
 * print step apart when none differs by more than one step. The sequences are aligned greedily in order (each twin
 * element takes the first port element of its key after the last match), so an element the twin lacks shows as missing
 * and one it adds as extra, by key.
 */
object L1Diff {
    /** One element of either side, in terms both share. */
    class L1Element(
        val key: String,
        /** The coordinates to compare: a path's points, a rect's x y w h rx ry, a circle's cx cy r, an ellipse's, a text's x y. */
        val vertices: FloatArray,
        /** A path's subpaths: each one's end in points and whether it closes, as "n:closed" (empty for other kinds). */
        val shape: String,
        val where: String,
    )

    /** What the diff found for one layer. */
    class Report(val layer: String, val port: Int, val twin: Int) {
        var matched = 0
        var exact = 0
        var oneStep = 0
        var differs = 0
        /** Port elements no twin element matched, and twin elements no port element matched, by key. */
        val missing = LinkedHashMap<String, Int>()
        val extra = LinkedHashMap<String, Int>()
        val firstDiffering = ArrayList<String>()

        fun summary(): String = "L1 $layer: port $port, twin $twin, matched $matched (exact $exact, one step $oneStep, differing $differs), " +
            "missing ${missing.values.sum()}, extra ${extra.values.sum()}"
    }

    /** Python's '%.1f' of a coordinate (the SVG's print). */
    private fun p1(v: Float): String = PyFmt.pf("%.1f", v.toDouble())

    private fun f3(v: Float): String = PyFmt.pf("%.3f", v.toDouble())

    private fun paintKey(p: SvgPaint): String = when (p) {
        SvgPaint.None -> "none"
        is SvgPaint.Color -> "#%08x".format(p.argb)
        is SvgPaint.Ref -> "url(#${p.id})"
    }

    private fun twinPaintKey(doc: TwinDocument, p: TwinPaint): String = when (p.kind) {
        TwinCodec.PAINT_NONE -> "none"
        TwinCodec.PAINT_COLOUR -> "#%08x".format(p.argb)
        TwinCodec.PAINT_GRADIENT -> "url(#${doc.gradients[p.ref].id})"
        else -> "url(#${doc.patterns[p.ref]})"
    }

    private fun styleKey(fill: String, fillOpacity: Float, evenOdd: Boolean, stroke: String, strokeOpacity: Float, width: Float, cap: Int,
                         join: Int, miter: Float, dashes: FloatArray, dashOffset: Float): String {
        val f = if (fill == "none") "fill none" else "fill $fill ${f3(fillOpacity)}${if (evenOdd) " evenodd" else ""}"
        val s = if (stroke == "none" || width <= 0f) "stroke none" else
            "stroke $stroke ${f3(strokeOpacity)} w ${f3(width)} cap $cap join $join miter ${f3(miter)}" +
                (if (dashes.isNotEmpty()) " dash ${dashes.joinToString(",") { f3(it) }}+${f3(dashOffset)}" else "")
        return "$f; $s"
    }

    private fun nodeKey(opacity: Float, blend: Int, filter: String?, clip: String?, transform: FloatArray?): String =
        "o ${f3(opacity)} b $blend f ${filter ?: "-"} c ${clip ?: "-"} t ${transform?.joinToString(",") { f3(it) } ?: "-"}"

    private fun svgNodeKey(n: SvgNode): String = nodeKey(n.opacity, n.blend.ordinal, n.filter?.id, n.clip?.id, n.transform)

    /** The port's elements of [docs] (a layer's documents in order), generator-only filters pruned in structural mode. */
    fun portElements(docs: List<SvgDocument>, structural: Boolean): List<L1Element> {
        val out = ArrayList<L1Element>()
        for (doc in docs) {
            fun walk(n: SvgNode, chain: List<String>) {
                val f = n.filter
                if (structural && f != null && AnchorCompiler.generatorOnly(f)) return
                when (n) {
                    is SvgGroup -> {
                        val below = if (n === doc.root) chain else chain + svgNodeKey(n)
                        for (c in n.children) walk(c, below)
                    }
                    is SvgShape -> out += portShape(doc, n, chain)
                    is SvgText -> {
                        val s = n.style
                        val style = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity, s.strokeWidth,
                            s.cap.ordinal, s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
                        val font = n.font
                        val key = "text '${n.text}' ${font.family} ${f3(font.size)} ${font.weight} ${font.italic} ${f3(font.letterSpacing)} " +
                            "${font.anchor.ordinal}; $style; ${svgNodeKey(n)}; ${chain.joinToString(" > ")}"
                        out += L1Element(key, floatArrayOf(n.x, n.y), "", "text '${n.text}'")
                    }
                }
            }
            walk(doc.root, emptyList())
        }
        return out
    }

    private fun portShape(doc: SvgDocument, n: SvgShape, chain: List<String>): L1Element {
        val s = n.style
        val style = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity, s.strokeWidth, s.cap.ordinal,
            s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
        val tail = "$style; ${svgNodeKey(n)}; ${chain.joinToString(" > ")}"
        return when (n.kind) {
            SvgShape.Kind.PATH -> {
                val pts = ArrayList<Float>()
                val shape = StringBuilder()
                var c = n.coordStart
                var open = -1
                for (v in n.verbStart until n.verbEnd) {
                    when (doc.path.verbs[v]) {
                        SvgPathData.MOVE -> {
                            if (open >= 0) shape.append("${pts.size / 2}:o ")
                            open = pts.size / 2
                            pts += doc.path.coords[c]; pts += doc.path.coords[c + 1]; c += 2
                        }
                        SvgPathData.LINE -> { pts += doc.path.coords[c]; pts += doc.path.coords[c + 1]; c += 2 }
                        SvgPathData.CLOSE -> { shape.append("${pts.size / 2}:c "); open = -1 }
                    }
                }
                if (open >= 0) shape.append("${pts.size / 2}:o ")
                L1Element("path; $tail", pts.toFloatArray(), shape.toString().trim(), "path")
            }
            SvgShape.Kind.RECT -> L1Element("rect; $tail", n.geometry.copyOf(6), "", "rect")
            SvgShape.Kind.CIRCLE -> L1Element("circle; $tail", floatArrayOf(n.geometry[0], n.geometry[1], n.geometry[2], 0f), "", "circle")
            SvgShape.Kind.ELLIPSE -> L1Element("ellipse; $tail", n.geometry.copyOf(4), "", "ellipse")
        }
    }

    /** The twin's elements of [layer], in painter's order. */
    fun twinElements(doc: TwinDocument, layer: Int): List<L1Element> = doc.order(layer).map { e ->
        val chain = doc.chain(e.group).map { g -> twinNodeKey(doc, g.node) }
        val s = e.style
        val style = styleKey(twinPaintKey(doc, s.fill), s.fillOpacity, s.evenOdd, twinPaintKey(doc, s.stroke), s.strokeOpacity, s.strokeWidth,
            s.cap, s.join, s.miterLimit, s.dashes, s.dashOffset)
        val tail = "$style; ${twinNodeKey(doc, e.node)}; ${chain.joinToString(" > ")}"
        when (e.kind) {
            TwinCodec.PATH -> {
                val shape = e.ends.indices.joinToString(" ") { k -> "${e.ends[k]}:${if (e.closed[k]) "c" else "o"}" }
                L1Element("path; $tail", e.points, shape, "path")
            }
            TwinCodec.RECT -> L1Element("rect; $tail", e.points, "", "rect")
            TwinCodec.CIRCLE -> L1Element("circle; $tail", e.points, "", "circle")
            TwinCodec.ELLIPSE -> L1Element("ellipse; $tail", e.points, "", "ellipse")
            else -> {
                val font = e.font!!
                L1Element(
                    "text '${e.text}' ${font.family} ${f3(font.size)} ${font.weight} ${font.italic} ${f3(font.letterSpacing)} ${font.anchor}; $tail",
                    e.points, "", "text '${e.text}'",
                )
            }
        }
    }

    private fun twinNodeKey(doc: TwinDocument, n: TwinNode): String =
        nodeKey(n.opacity, n.blend, if (n.filter >= 0) doc.filters[n.filter] else null, if (n.clip >= 0) doc.clips[n.clip].id else null, n.transform)

    /** Aligns [twin] onto [port] in order and compares each match's vertices. */
    fun diff(layer: String, port: List<L1Element>, twin: List<L1Element>): Report {
        val r = Report(layer, port.size, twin.size)
        val byKey = HashMap<String, ArrayList<Int>>()
        for ((i, e) in port.withIndex()) byKey.getOrPut(e.key) { ArrayList() } += i
        val used = BooleanArray(port.size)
        var last = -1
        for (t in twin) {
            val list = byKey[t.key]
            val at = list?.let { firstAfter(it, last) }
            if (at == null) {
                r.extra.merge(shortKey(t.key), 1, Int::plus)
                continue
            }
            used[at] = true
            last = at
            r.matched++
            val p = port[at]
            when (compare(p, t)) {
                0 -> r.exact++
                1 -> r.oneStep++
                else -> {
                    r.differs++
                    if (r.firstDiffering.size < 5) r.firstDiffering += "${t.where}: port ${p.vertices.joinToString(" ") { p1(it) }} [${p.shape}], " +
                        "twin ${t.vertices.joinToString(" ") { p1(it) }} [${t.shape}]"
                }
            }
        }
        for ((i, e) in port.withIndex()) if (!used[i]) r.missing.merge(shortKey(e.key), 1, Int::plus)
        return r
    }

    /** 0: every coordinate prints the same; 1: none more than one print step apart; 2: otherwise (or the shapes differ). */
    private fun compare(p: L1Element, t: L1Element): Int {
        if (p.shape != t.shape || p.vertices.size != t.vertices.size) return 2
        var worst = 0
        for (i in p.vertices.indices) {
            val a = p1(p.vertices[i]); val b = p1(t.vertices[i])
            if (a == b) continue
            val steps = Math.abs(Math.round(a.toDouble() * 10) - Math.round(b.toDouble() * 10))
            worst = maxOf(worst, if (steps <= 1) 1 else 2)
        }
        return worst
    }

    private fun firstAfter(list: List<Int>, last: Int): Int? {
        var lo = 0; var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid] <= last) lo = mid + 1 else hi = mid
        }
        return if (lo < list.size) list[lo] else null
    }

    /** A key short enough to report: the kind and the paints. */
    private fun shortKey(key: String): String = key.substringBefore("; o ").take(160)

    /** The style key of an SvgStyle (for tests that build elements by hand). */
    fun styleKeyOf(s: SvgStyle): String = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity,
        s.strokeWidth, s.cap.ordinal, s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
}
