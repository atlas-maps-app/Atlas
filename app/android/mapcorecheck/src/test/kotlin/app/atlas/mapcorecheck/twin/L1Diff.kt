package app.atlas.mapcorecheck.twin

import app.atlas.anchor.AnchorCompiler
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFe
import app.atlas.mapview.svg.SvgFilter
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
 * above it (opacity, blend, filter and clip by their SVG ids, transforms), and its vertices.
 *
 * Only what can show is compared. The port draws its whole scene (fills and shadows hundreds of pixels beyond the
 * frame, trams anywhere ahead), the frame only the cover's tiles; so both sides are clipped to the view box first: a
 * path keeps the subpaths whose extent, with its paint's reach (its stroke's half width, more for miters and square
 * caps, and its filters' spread), meets the view, and an element with nothing left, or a fill that covers nothing of
 * the view beyond its outline, is outside the view: the port's are listed as such, never as missing, and the twin's
 * are counted. The plan's L1 allows no margin beyond that.
 *
 * Then the sequences are aligned by key and by vertices together: two elements match when all but the vertices agree
 * and every coordinate prints the same at `%.1f` (Python's, PyFmt), and the longest common subsequence of such
 * matches anchors the rest, so an element one side lacks never shifts the others into differing. What is left is
 * matched in turn, each pair's place checked against the anchors:
 * - the same element elsewhere in the sequence;
 * - an element whose subpaths are the same set in another order (exact: a path's subpaths fill and stroke as one), or
 *   whose coordinates are at most one print step apart;
 * - a fill the twin draws in pieces (its rings clipped at tile edges): fills of the same key inside the port's element
 *   whose union covers what it covers of the view, point for point but within [EPS] of their outlines, count as that
 *   one element and are listed as in pieces (whether the pieces are an intended deviation, the pixels decide: L2);
 * - otherwise the nearest element of the same key overlapping it, whose coordinates differ.
 *
 * A pair out of its place is reordered when no element of another key it passes, among the anchors, overlaps it in the
 * view (painting it earlier or later changes no pixel; elements of one key are interchangeable), and out of order when
 * one does. What is left over is missing (the port's) or extra (the twin's), by key.
 */
object L1Diff {
    /** How far two outlines may lie apart and still be one, in CSS px: two print steps. */
    const val EPS = 0.2

    /** The rows' spacing of the coverage tests, in CSS px. */
    private const val ROW = 0.125

    /** One element of either side, in terms both share. */
    class L1Element(
        val key: String,
        /** The coordinates to compare: a path's points, a rect's x y w h rx ry, a circle's cx cy r, an ellipse's, a text's x y. */
        val vertices: FloatArray,
        /** A path's subpaths: each one's end in points and whether it closes, as "n:closed" (empty for other kinds). */
        val shape: String,
        val where: String,
        /** How far its paint reaches beyond its vertices, in its own units: its stroke's and its filters'. */
        val reach: Float = 0f,
        /** The transform from its coordinates to the view's (its own and its groups', `a b c d e f`), or null for none. */
        val matrix: FloatArray? = null,
        /** Whether it fills, by the even-odd rule, and strokes. */
        val fills: Boolean = false,
        val evenOdd: Boolean = false,
        val strokes: Boolean = false,
        /** A text run's characters and font size, which bound its extent. */
        val chars: Int = 0,
        val fontSize: Float = 0f,
    ) {
        /** path, rect, circle, ellipse or text. */
        val kind: String get() = key.substringBefore(';').substringBefore(' ')
    }

    /** What the diff found for one layer. */
    class Report(val layer: String) {
        /** The elements in the view on each side, and those outside it. */
        var port = 0
        var twin = 0
        var outside = 0
        var twinOutside = 0
        var matched = 0
        var exact = 0
        var oneStep = 0
        var differs = 0
        /** Port elements the twin draws in pieces, and the pieces. */
        var inPieces = 0
        var pieces = 0
        /** Matches out of their place: reordered (no pixel changes) and out of order (an overlapping element passed). */
        var reordered = 0
        var outOfOrder = 0
        /** Exact or one-step matches whose subpaths come in another order. */
        var subpathsReordered = 0
        /** Port elements outside the view, port elements no twin element matched, twin elements no port element matched, and fills in pieces, by key. */
        val outsideByKey = LinkedHashMap<String, Int>()
        val missing = LinkedHashMap<String, Int>()
        val extra = LinkedHashMap<String, Int>()
        val piecesByKey = LinkedHashMap<String, Int>()
        val firstDiffering = ArrayList<String>()
        val firstOutOfOrder = ArrayList<String>()

        fun summary(): String = "L1 $layer: port $port in the view ($outside outside), twin $twin ($twinOutside outside), matched $matched " +
            "(exact $exact, one step $oneStep, differing $differs; $inPieces in $pieces pieces), reordered $reordered, out of order $outOfOrder, " +
            "missing ${missing.values.sum()}, extra ${extra.values.sum()}"
    }

    // ---------------------------------------------------------------- the two sides' elements

    /** Python's '%.1f' of a coordinate (the SVG's print). */
    private fun p1(v: Float): String = PyFmt.fixed(v.toDouble(), 1)

    private fun f3(v: Float): String = PyFmt.pf("%.3f", v.toDouble())

    /** A coordinate as printed at '%.1f', in tenths (a printed zero's sign dropped; NaN and the infinities apart). */
    private fun tenths(v: Float): Long {
        val s = p1(v)
        val dot = s.indexOf('.')
        if (dot < 0) return if (v.isNaN()) Long.MIN_VALUE else if (v > 0) Long.MAX_VALUE else Long.MIN_VALUE + 1
        return (s.substring(0, dot) + s.substring(dot + 1)).toLongOrNull() ?: Long.MIN_VALUE
    }

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

    /** How far a stroke reaches beyond its line: half its width, times the miter limit for miter joins, more for square caps. */
    private fun strokeReach(width: Float, cap: Int, join: Int, miter: Float): Float {
        if (width <= 0f) return 0f
        val joins = if (join == SvgStyle.Join.MITER.ordinal) maxOf(1f, miter) else 1f
        val caps = if (cap == SvgStyle.Cap.SQUARE.ordinal) 1.415f else 1f
        return width / 2f * maxOf(joins, caps)
    }

    /** How far a filter's primitives move or spread what it draws: three deviations of a blur, half a displacement's scale, an offset. */
    fun spreadOf(f: SvgFilter): Float = f.primitives.sumOf { p ->
        when (p) {
            is SvgFe.GaussianBlur -> 3.0 * maxOf(p.deviationX, p.deviationY)
            is SvgFe.DisplacementMap -> Math.abs(p.scale) / 2.0
            is SvgFe.Offset -> maxOf(Math.abs(p.dx), Math.abs(p.dy)).toDouble()
            else -> 0.0
        }
    }.toFloat()

    /** Every filter's spread in [docs], by id: the twin names its filters by the port's ids (twinElements). */
    fun filterSpread(docs: List<SvgDocument>): Map<String, Float> {
        val out = HashMap<String, Float>()
        for (d in docs) for ((id, f) in d.filters) out[id] = spreadOf(f)
        return out
    }

    /** [outer] applied after [inner] (both `a b c d e f`, null for identity). */
    private fun compose(outer: FloatArray?, inner: FloatArray?): FloatArray? {
        if (outer == null) return inner
        if (inner == null) return outer
        return floatArrayOf(
            outer[0] * inner[0] + outer[2] * inner[1], outer[1] * inner[0] + outer[3] * inner[1],
            outer[0] * inner[2] + outer[2] * inner[3], outer[1] * inner[2] + outer[3] * inner[3],
            outer[0] * inner[4] + outer[2] * inner[5] + outer[4], outer[1] * inner[4] + outer[3] * inner[5] + outer[5],
        )
    }

    /** The port's elements of [docs] (a layer's documents in order), generator-only filters pruned in structural mode. */
    fun portElements(docs: List<SvgDocument>, structural: Boolean): List<L1Element> {
        val out = ArrayList<L1Element>()
        for (doc in docs) {
            fun walk(n: SvgNode, chain: List<String>, matrix: FloatArray?, spread: Float) {
                val f = n.filter
                if (structural && f != null && AnchorCompiler.generatorOnly(f)) return
                val m = compose(matrix, n.transform)
                val reach = spread + (f?.let(::spreadOf) ?: 0f)
                when (n) {
                    is SvgGroup -> {
                        val below = if (n === doc.root) chain else chain + svgNodeKey(n)
                        for (c in n.children) walk(c, below, m, reach)
                    }
                    is SvgShape -> out += portShape(doc, n, chain, m, reach)
                    is SvgText -> {
                        val s = n.style
                        val style = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity, s.strokeWidth,
                            s.cap.ordinal, s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
                        val font = n.font
                        val key = "text '${n.text}' ${font.family} ${f3(font.size)} ${font.weight} ${font.italic} ${f3(font.letterSpacing)} " +
                            "${font.anchor.ordinal}; $style; ${svgNodeKey(n)}; ${chain.joinToString(" > ")}"
                        out += L1Element(key, floatArrayOf(n.x, n.y), "", "text '${n.text}'",
                            reach = reach + strokeReach(if (s.hasStroke) s.strokeWidth else 0f, s.cap.ordinal, s.join.ordinal, s.miterLimit),
                            matrix = m, fills = s.hasFill, evenOdd = s.evenOdd, strokes = s.hasStroke, chars = n.text.length, fontSize = font.size)
                    }
                }
            }
            walk(doc.root, emptyList(), null, 0f)
        }
        return out
    }

    private fun portShape(doc: SvgDocument, n: SvgShape, chain: List<String>, matrix: FloatArray?, spread: Float): L1Element {
        val s = n.style
        val style = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity, s.strokeWidth, s.cap.ordinal,
            s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
        val tail = "$style; ${svgNodeKey(n)}; ${chain.joinToString(" > ")}"
        val reach = spread + strokeReach(if (s.hasStroke) s.strokeWidth else 0f, s.cap.ordinal, s.join.ordinal, s.miterLimit)
        fun element(key: String, vertices: FloatArray, shape: String, where: String) =
            L1Element(key, vertices, shape, where, reach, matrix, s.hasFill, s.evenOdd, s.hasStroke)
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
                element("path; $tail", pts.toFloatArray(), shape.toString().trim(), "path")
            }
            SvgShape.Kind.RECT -> element("rect; $tail", n.geometry.copyOf(6), "", "rect")
            SvgShape.Kind.CIRCLE -> element("circle; $tail", floatArrayOf(n.geometry[0], n.geometry[1], n.geometry[2], 0f), "", "circle")
            SvgShape.Kind.ELLIPSE -> element("ellipse; $tail", n.geometry.copyOf(4), "", "ellipse")
        }
    }

    /**
     * The twin's elements of [layer], in painter's order. [spread] gives its filters' spread by id (filterSpread of the
     * port's documents at the same camera), so that an element reaches as far on both sides.
     */
    fun twinElements(doc: TwinDocument, layer: Int, spread: Map<String, Float> = emptyMap()): List<L1Element> = doc.order(layer).map { e ->
        val groups = doc.chain(e.group)
        val chain = groups.map { g -> twinNodeKey(doc, g.node) }
        var matrix: FloatArray? = null
        var filters = 0f
        for (n in groups.map { it.node } + e.node) {
            matrix = compose(matrix, n.transform)
            if (n.filter >= 0) filters += spread[doc.filters[n.filter]] ?: 0f
        }
        val s = e.style
        val style = styleKey(twinPaintKey(doc, s.fill), s.fillOpacity, s.evenOdd, twinPaintKey(doc, s.stroke), s.strokeOpacity, s.strokeWidth,
            s.cap, s.join, s.miterLimit, s.dashes, s.dashOffset)
        val tail = "$style; ${twinNodeKey(doc, e.node)}; ${chain.joinToString(" > ")}"
        val fills = s.fill.kind != TwinCodec.PAINT_NONE
        val strokes = s.stroke.kind != TwinCodec.PAINT_NONE && s.strokeWidth > 0f
        val reach = filters + strokeReach(if (strokes) s.strokeWidth else 0f, s.cap, s.join, s.miterLimit)
        fun element(key: String, shape: String, where: String, chars: Int = 0, size: Float = 0f) =
            L1Element(key, e.points, shape, where, reach, matrix, fills, s.evenOdd, strokes, chars, size)
        when (e.kind) {
            TwinCodec.PATH -> element("path; $tail", e.ends.indices.joinToString(" ") { k -> "${e.ends[k]}:${if (e.closed[k]) "c" else "o"}" }, "path")
            TwinCodec.RECT -> element("rect; $tail", "", "rect")
            TwinCodec.CIRCLE -> element("circle; $tail", "", "circle")
            TwinCodec.ELLIPSE -> element("ellipse; $tail", "", "ellipse")
            else -> {
                val font = e.font!!
                element(
                    "text '${e.text}' ${font.family} ${f3(font.size)} ${font.weight} ${font.italic} ${f3(font.letterSpacing)} ${font.anchor}; $tail",
                    "", "text '${e.text}'", e.text!!.length, font.size,
                )
            }
        }
    }

    private fun twinNodeKey(doc: TwinDocument, n: TwinNode): String =
        nodeKey(n.opacity, n.blend, if (n.filter >= 0) doc.filters[n.filter] else null, if (n.clip >= 0) doc.clips[n.clip].id else null, n.transform)

    // ---------------------------------------------------------------- the view, and what of an element is in it

    /** The view box as a rectangle, and its rows for the coverage tests. */
    private class View(box: FloatArray) {
        val x0 = box[0].toDouble()
        val y0 = box[1].toDouble()
        val x1 = x0 + box[2]
        val y1 = y0 + box[3]
        val rows = Math.ceil((y1 - y0) / ROW).toInt()
        fun meets(b: DoubleArray): Boolean = b[2] >= x0 && b[0] <= x1 && b[3] >= y0 && b[1] <= y1
    }

    /** An element as compared: what of it is in the view, its place among its side's elements in the view. */
    private class Seen(val e: L1Element, val v: View, val shape: String, val vertices: FloatArray, val box: DoubleArray) {
        val ends: IntArray
        val closed: BooleanArray
        val tenths = LongArray(vertices.size) { tenths(vertices[it]) }
        val sig: String by lazy { e.key + '\n' + shape + '\n' + tenths.joinToString(" ") }
        /** The view-space rings it covers (each closed implicitly, as a fill closes them), its coverage and its outline. */
        val rings: List<DoubleArray> by lazy { ringsOf(this) }
        val coverage: Coverage by lazy { Coverage.of(rings, e.evenOdd, v) }
        val outline: Outline by lazy { Outline(v).also { it.add(rings) } }
        /** Whether the coverage tests see its painted region (a fill alone), or only its extent. */
        val region: Boolean get() = e.kind == "path" && e.fills && !e.strokes

        init {
            val toks = if (shape.isEmpty()) emptyList() else shape.split(' ')
            ends = IntArray(toks.size) { toks[it].substringBefore(':').toInt() }
            closed = BooleanArray(toks.size) { toks[it].endsWith(":c") }
        }

        /** Subpath [k]'s range of coordinates. */
        fun from(k: Int): Int = if (k == 0) 0 else 2 * ends[k - 1]
        fun to(k: Int): Int = 2 * ends[k]
    }

    /** [m] applied to (x, y), into [out] at [at]. */
    private fun put(m: FloatArray?, x: Float, y: Float, out: DoubleArray, at: Int) {
        if (m == null) {
            out[at] = x.toDouble(); out[at + 1] = y.toDouble()
        } else {
            out[at] = m[0].toDouble() * x + m[2].toDouble() * y + m[4]
            out[at + 1] = m[1].toDouble() * x + m[3].toDouble() * y + m[5]
        }
    }

    /** A bound of how much [m] enlarges a length. */
    private fun scale(m: FloatArray?): Double = if (m == null) 1.0 else maxOf(Math.abs(m[0]) + Math.abs(m[2]), Math.abs(m[1]) + Math.abs(m[3])).toDouble()

    private fun boxOf(pts: DoubleArray, from: Int, to: Int, grow: Double): DoubleArray {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        var i = from
        while (i < to) {
            b[0] = minOf(b[0], pts[i]); b[1] = minOf(b[1], pts[i + 1]); b[2] = maxOf(b[2], pts[i]); b[3] = maxOf(b[3], pts[i + 1]); i += 2
        }
        b[0] -= grow; b[1] -= grow; b[2] += grow; b[3] += grow
        return b
    }

    private fun union(a: DoubleArray?, b: DoubleArray): DoubleArray =
        if (a == null) b else doubleArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))

    /** What of [e] is in [v] (a path's subpaths whose extents meet it), or null when nothing is. */
    private fun seen(e: L1Element, v: View): Seen? {
        val grow = e.reach * scale(e.matrix)
        val m = e.matrix
        val g = e.vertices
        when (e.kind) {
            "path" -> {
                val toks = if (e.shape.isEmpty()) emptyList() else e.shape.split(' ')
                val pts = DoubleArray(g.size)
                for (i in 0 until g.size / 2) put(m, g[2 * i], g[2 * i + 1], pts, 2 * i)
                var start = 0
                var box: DoubleArray? = null
                val kept = ArrayList<String>()
                val keptPts = ArrayList<Float>()
                var all = true
                for (t in toks) {
                    val end = t.substringBefore(':').toInt()
                    val b = boxOf(pts, 2 * start, 2 * end, grow)
                    if (end > start && v.meets(b)) {
                        for (i in 2 * start until 2 * end) keptPts += g[i]
                        kept += "${keptPts.size / 2}:${t.substringAfter(':')}"
                        box = union(box, b)
                    } else {
                        all = false
                    }
                    start = end
                }
                if (box == null) return null
                return if (all) Seen(e, v, e.shape, g, box) else Seen(e, v, kept.joinToString(" "), keptPts.toFloatArray(), box)
            }
            "rect" -> {
                val c = DoubleArray(8)
                put(m, g[0], g[1], c, 0); put(m, g[0] + g[2], g[1], c, 2); put(m, g[0], g[1] + g[3], c, 4); put(m, g[0] + g[2], g[1] + g[3], c, 6)
                val b = boxOf(c, 0, 8, grow)
                return if (v.meets(b)) Seen(e, v, "", g, b) else null
            }
            "circle", "ellipse" -> {
                val rx = g[2].toDouble()
                val ry = if (e.kind == "circle") rx else g[3].toDouble()
                val c = DoubleArray(2)
                put(m, g[0], g[1], c, 0)
                val a = m ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
                val hx = Math.hypot(a[0] * rx, a[2] * ry) + grow
                val hy = Math.hypot(a[1] * rx, a[3] * ry) + grow
                val b = doubleArrayOf(c[0] - hx, c[1] - hy, c[0] + hx, c[1] + hy)
                return if (v.meets(b)) Seen(e, v, "", g, b) else null
            }
            else -> {
                // A text run lies within its characters' advance of its anchor, whatever its anchor and rotation.
                val c = DoubleArray(2)
                put(m, g[0], g[1], c, 0)
                val h = e.fontSize.toDouble() * (e.chars + 1) * scale(m) + grow
                val b = doubleArrayOf(c[0] - h, c[1] - h, c[0] + h, c[1] + h)
                return if (v.meets(b)) Seen(e, v, "", g, b) else null
            }
        }
    }

    /** The view-space rings of what [s] covers: a path's subpaths, a rect's corners, an ellipse's polygon, a text run's extent. */
    private fun ringsOf(s: Seen): List<DoubleArray> {
        val m = s.e.matrix
        val g = s.vertices
        return when (s.e.kind) {
            "path" -> s.ends.indices.map { k ->
                val from = s.from(k)
                val r = DoubleArray(s.to(k) - from)
                for (i in 0 until r.size / 2) put(m, g[from + 2 * i], g[from + 2 * i + 1], r, 2 * i)
                r
            }
            "rect" -> {
                val r = DoubleArray(8)
                put(m, g[0], g[1], r, 0); put(m, g[0] + g[2], g[1], r, 2); put(m, g[0] + g[2], g[1] + g[3], r, 4); put(m, g[0], g[1] + g[3], r, 6)
                listOf(r)
            }
            "circle", "ellipse" -> {
                val rx = g[2]
                val ry = if (s.e.kind == "circle") rx else g[3]
                val r = DoubleArray(2 * 48)
                for (k in 0 until 48) {
                    val t = 2 * Math.PI * k / 48
                    put(m, (g[0] + rx * Math.cos(t)).toFloat(), (g[1] + ry * Math.sin(t)).toFloat(), r, 2 * k)
                }
                listOf(r)
            }
            else -> listOf(doubleArrayOf(s.box[0], s.box[1], s.box[2], s.box[1], s.box[2], s.box[3], s.box[0], s.box[3]))
        }
    }

    // ---------------------------------------------------------------- coverage, row by row

    /** A growable list of doubles. */
    private class DoubleList {
        private var a = DoubleArray(8)
        var size = 0
            private set

        fun add(x: Double) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = x
        }

        operator fun get(i: Int): Double = a[i]
        fun last(): Double = a[size - 1]
        fun setLast(x: Double) { a[size - 1] = x }
        fun sorted(): DoubleArray = a.copyOf(size).also { it.sort() }
        fun toArray(): DoubleArray = a.copyOf(size)
    }

    /**
     * What a region covers of the view: per row (ROW apart, sampled at their centres), its inside intervals as x pairs,
     * clipped to the view. Rows are numbered from the view's top; [first] is the first row held.
     */
    private class Coverage(val first: Int, val rows: Array<DoubleArray>) {
        fun row(r: Int): DoubleArray = if (r < first || r >= first + rows.size) NONE else rows[r - first]
        val last: Int get() = first + rows.size

        companion object {
            val NONE = DoubleArray(0)
            val EMPTY = Coverage(0, emptyArray())

            /** The coverage of [rings] (each closed) by the even-odd or the nonzero rule. */
            fun of(rings: List<DoubleArray>, evenOdd: Boolean, v: View): Coverage {
                var lo = Double.MAX_VALUE
                var hi = -Double.MAX_VALUE
                for (r in rings) for (i in 1 until r.size step 2) { lo = minOf(lo, r[i]); hi = maxOf(hi, r[i]) }
                if (rings.isEmpty() || lo > hi) return EMPTY
                val r0 = maxOf(0, Math.ceil((lo - v.y0) / ROW - 0.5).toInt())
                val r1 = minOf(v.rows - 1, Math.ceil((hi - v.y0) / ROW - 0.5).toInt() - 1)
                if (r1 < r0) return EMPTY
                val n = r1 - r0 + 1
                // Each row's crossings: x where an edge goes down the view (winding +1) and where one goes up (-1).
                val down = arrayOfNulls<DoubleList>(n)
                val up = arrayOfNulls<DoubleList>(n)
                for (ring in rings) {
                    val k = ring.size / 2
                    for (i in 0 until k) {
                        val ax = ring[2 * i]; val ay = ring[2 * i + 1]
                        val j = (i + 1) % k
                        val bx = ring[2 * j]; val by = ring[2 * j + 1]
                        if (ay == by) continue
                        val ra = maxOf(r0, Math.ceil((minOf(ay, by) - v.y0) / ROW - 0.5).toInt())
                        val rb = minOf(r1, Math.ceil((maxOf(ay, by) - v.y0) / ROW - 0.5).toInt() - 1)
                        for (r in ra..rb) {
                            val y = v.y0 + (r + 0.5) * ROW
                            val x = ax + (y - ay) / (by - ay) * (bx - ax)
                            val lists = if (by > ay) down else up
                            (lists[r - r0] ?: DoubleList().also { lists[r - r0] = it }).add(x)
                        }
                    }
                }
                val rows = Array(n) { r ->
                    val a = down[r]?.sorted() ?: NONE
                    val b = up[r]?.sorted() ?: NONE
                    val out = DoubleList()
                    var w = 0
                    var i = 0
                    var j = 0
                    var start = 0.0
                    while (i < a.size || j < b.size) {
                        val fromA = j >= b.size || (i < a.size && a[i] <= b[j])
                        val x = if (fromA) a[i++] else b[j++]
                        val before = if (evenOdd) (w and 1) != 0 else w != 0
                        w += if (fromA) 1 else -1
                        val after = if (evenOdd) (w and 1) != 0 else w != 0
                        if (!before && after) {
                            start = x
                        } else if (before && !after) {
                            val x0 = maxOf(start, v.x0)
                            val x1 = minOf(x, v.x1)
                            if (x1 > x0) {
                                if (out.size > 0 && out.last() >= x0) out.setLast(maxOf(out.last(), x1)) else { out.add(x0); out.add(x1) }
                            }
                        }
                    }
                    out.toArray()
                }
                return Coverage(r0, rows)
            }

            /** The intervals where [keep] holds of being inside [a] and inside [b] (each sorted and disjoint). */
            fun combine(a: DoubleArray, b: DoubleArray, keep: (Boolean, Boolean) -> Boolean): DoubleArray {
                if (a.isEmpty() && b.isEmpty()) return NONE
                val xs = DoubleArray(a.size + b.size)
                System.arraycopy(a, 0, xs, 0, a.size)
                System.arraycopy(b, 0, xs, a.size, b.size)
                xs.sort()
                val out = DoubleList()
                var i = 0
                var j = 0
                for (k in 0 until xs.size - 1) {
                    val lo = xs[k]
                    val hi = xs[k + 1]
                    if (hi <= lo) continue
                    val mid = (lo + hi) / 2
                    while (i < a.size && a[i + 1] <= mid) i += 2
                    while (j < b.size && b[j + 1] <= mid) j += 2
                    val inA = i < a.size && a[i] <= mid
                    val inB = j < b.size && b[j] <= mid
                    if (keep(inA, inB)) {
                        if (out.size > 0 && out.last() == lo) out.setLast(hi) else { out.add(lo); out.add(hi) }
                    }
                }
                return out.toArray()
            }

            fun union(all: List<Coverage>): Coverage {
                val held = all.filter { it.rows.isNotEmpty() }
                if (held.isEmpty()) return EMPTY
                val first = held.minOf { it.first }
                val last = held.maxOf { it.last }
                return Coverage(first, Array(last - first) { k ->
                    var acc = NONE
                    for (c in held) acc = combine(acc, c.row(first + k)) { x, y -> x || y }
                    acc
                })
            }
        }
    }

    /** The edges of an element's outline in the view (and a pixel around it), bucketed: "is this point near the outline". */
    private class Outline(v: View) {
        private val x0 = v.x0 - 1
        private val y0 = v.y0 - 1
        private val x1 = v.x1 + 1
        private val y1 = v.y1 + 1
        private val cols = Math.ceil((x1 - x0) / CELL).toInt()
        private val rowsN = Math.ceil((y1 - y0) / CELL).toInt()
        private val cells = HashMap<Int, DoubleList>()

        fun add(rings: List<DoubleArray>) {
            for (r in rings) {
                val k = r.size / 2
                for (i in 0 until k) {
                    val j = (i + 1) % k
                    segment(r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1])
                }
            }
        }

        /** Adds what of a segment lies in the view and a pixel around it (Liang–Barsky). */
        private fun segment(ax: Double, ay: Double, bx: Double, by: Double) {
            var t0 = 0.0
            var t1 = 1.0
            val dx = bx - ax
            val dy = by - ay
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(ax - x0, x1 - ax, ay - y0, y1 - ay)
            for (k in 0 until 4) {
                if (p[k] == 0.0) {
                    if (q[k] < 0) return
                } else {
                    val t = q[k] / p[k]
                    if (p[k] < 0) t0 = maxOf(t0, t) else t1 = minOf(t1, t)
                }
            }
            if (t0 > t1) return
            val sx = ax + t0 * dx
            val sy = ay + t0 * dy
            val ex = ax + t1 * dx
            val ey = ay + t1 * dy
            val c0x = col(minOf(sx, ex) - EPS); val c1x = col(maxOf(sx, ex) + EPS)
            val c0y = row(minOf(sy, ey) - EPS); val c1y = row(maxOf(sy, ey) + EPS)
            for (cy in c0y..c1y) for (cx in c0x..c1x) {
                val l = cells.getOrPut(cy * cols + cx) { DoubleList() }
                l.add(sx); l.add(sy); l.add(ex); l.add(ey)
            }
        }

        private fun col(x: Double): Int = ((x - x0) / CELL).toInt().coerceIn(0, cols - 1)
        private fun row(y: Double): Int = ((y - y0) / CELL).toInt().coerceIn(0, rowsN - 1)

        /** Whether (x, y) lies within EPS of an edge. */
        fun near(x: Double, y: Double): Boolean {
            val l = cells[row(y) * cols + col(x)] ?: return false
            var i = 0
            while (i < l.size) {
                val ax = l[i]; val ay = l[i + 1]
                val dx = l[i + 2] - ax; val dy = l[i + 3] - ay
                val len = dx * dx + dy * dy
                val t = if (len == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0.0, 1.0)
                if (Math.hypot(x - (ax + t * dx), y - (ay + t * dy)) <= EPS) return true
                i += 4
            }
            return false
        }

        companion object {
            const val CELL = 2.0
        }
    }

    /**
     * The first point where [keep] holds of [a]'s and [b]'s coverage farther than EPS from every outline of [near], or
     * null: "they differ there beyond their outlines' rounding" for a symmetric difference, and so on.
     */
    private fun beyond(a: Coverage, b: Coverage, near: List<Outline>, v: View, keep: (Boolean, Boolean) -> Boolean): DoubleArray? {
        val first = minOf(if (a.rows.isEmpty()) Int.MAX_VALUE else a.first, if (b.rows.isEmpty()) Int.MAX_VALUE else b.first)
        val last = maxOf(a.last, b.last)
        for (r in first until last) {
            val iv = Coverage.combine(a.row(r), b.row(r), keep)
            val y = v.y0 + (r + 0.5) * ROW
            var k = 0
            while (k < iv.size) {
                val lo = iv[k]
                val hi = iv[k + 1]
                var x = if (lo + ROW / 2 < hi) lo + ROW / 2 else (lo + hi) / 2
                while (x < hi) {
                    if (near.none { it.near(x, y) }) return doubleArrayOf(x, y)
                    x += ROW
                }
                k += 2
            }
        }
        return null
    }

    private fun overlapBoxes(a: DoubleArray, b: DoubleArray): Boolean = a[2] >= b[0] && b[2] >= a[0] && a[3] >= b[1] && b[3] >= a[1]

    /** Whether [a] and [b] cover some of the view in common beyond their outlines (their extents meet, when either is not a fill alone). */
    private fun overlap(a: Seen, b: Seen): Boolean {
        if (!overlapBoxes(a.box, b.box)) return false
        if (!a.region || !b.region) return true
        return beyond(a.coverage, b.coverage, listOf(a.outline, b.outline), a.v) { x, y -> x && y } != null
    }

    /** Whether [s] shows anything: some of the view beyond its outline, for a fill alone (others: their extents meet the view). */
    private fun shows(s: Seen): Boolean = !s.region || beyond(s.coverage, Coverage.EMPTY, listOf(s.outline), s.v) { x, _ -> x } != null

    // ---------------------------------------------------------------- the alignment

    /** The longest common subsequence of [a] and [b] (Hunt–Szymanski: increasing chains of equal pairs), as index pairs. */
    private fun lcs(a: List<String>, b: List<String>): List<IntArray> {
        val at = HashMap<String, ArrayList<Int>>()
        for ((j, s) in b.withIndex()) at.getOrPut(s) { ArrayList() } += j
        val tails = IntArray(b.size + 1)
        val tailNode = IntArray(b.size + 1)
        var len = 0
        val nodeI = ArrayList<Int>()
        val nodeJ = ArrayList<Int>()
        val nodePrev = ArrayList<Int>()
        for (i in a.indices) {
            val js = at[a[i]] ?: continue
            for (q in js.indices.reversed()) {
                val j = js[q]
                var lo = 0
                var hi = len
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (tails[mid] < j) lo = mid + 1 else hi = mid
                }
                tails[lo] = j
                nodeI += i; nodeJ += j; nodePrev += if (lo > 0) tailNode[lo - 1] else -1
                tailNode[lo] = nodeI.size - 1
                if (lo == len) len++
            }
        }
        val out = ArrayList<IntArray>(len)
        var n = if (len > 0) tailNode[len - 1] else -1
        while (n >= 0) {
            out += intArrayOf(nodeI[n], nodeJ[n])
            n = nodePrev[n]
        }
        out.reverse()
        return out
    }

    /** The largest difference of [n] tenths from [a]'s [ai] and [b]'s [bi], capped at 2. */
    private fun worst(a: LongArray, ai: Int, b: LongArray, bi: Int, n: Int): Int {
        var w = 0
        for (k in 0 until n) {
            val d = a[ai + k] - b[bi + k]
            if (d == 0L) continue
            if (d > 1 || d < -1) return 2
            w = 1
        }
        return w
    }

    /** A subpath's closing and printed coordinates. */
    private fun subKey(s: Seen, k: Int): String = "${s.closed[k]} " + s.tenths.copyOfRange(s.from(k), s.to(k)).joinToString(" ")

    /**
     * 0: every coordinate prints the same; 1: none more than one print step apart; 2: otherwise. A path's subpaths are
     * compared as a set when their order differs; [reordered]`[0]` then holds.
     */
    private fun compare(p: Seen, t: Seen, reordered: BooleanArray): Int {
        if (p.shape == t.shape && p.tenths.size == t.tenths.size) {
            val w = worst(p.tenths, 0, t.tenths, 0, p.tenths.size)
            if (w <= 1 || p.ends.size <= 1) return w
        }
        val n = p.ends.size
        if (p.e.kind != "path" || n != t.ends.size || n <= 1 || p.tenths.size != t.tenths.size) return 2
        // Each of the port's subpaths takes the twin's that prints the same, then one at most a print step apart.
        val bySub = HashMap<String, ArrayDeque<Int>>()
        for (k in 0 until n) bySub.getOrPut(subKey(t, k)) { ArrayDeque() }.addLast(k)
        val used = BooleanArray(n)
        val open = ArrayList<Int>()
        for (k in 0 until n) {
            val m = bySub[subKey(p, k)]?.removeFirstOrNull()
            if (m != null) used[m] = true else open += k
        }
        var w = 0
        for (k in open) {
            val size = p.to(k) - p.from(k)
            val m = (0 until n).firstOrNull { c ->
                !used[c] && t.closed[c] == p.closed[k] && t.to(c) - t.from(c) == size && worst(p.tenths, p.from(k), t.tenths, t.from(c), size) <= 1
            } ?: return 2
            used[m] = true
            w = 1
        }
        reordered[0] = true
        return w
    }

    /** Aligns [twin] onto [port] in the view [viewBox] (min x, min y, width, height) and compares each match. */
    fun diff(layer: String, port: List<L1Element>, twin: List<L1Element>, viewBox: FloatArray): Report {
        val v = View(viewBox)
        val r = Report(layer)
        val ps = ArrayList<Seen>()
        val ts = ArrayList<Seen>()
        for (e in port) {
            val s = seen(e, v)
            if (s != null) ps += s else { r.outside++; r.outsideByKey.merge(shortKey(e.key), 1, Int::plus) }
        }
        for (e in twin) {
            val s = seen(e, v)
            if (s != null) ts += s else r.twinOutside++
        }

        // The anchors: the longest common subsequence of elements equal in key and vertices.
        val anchors = lcs(ps.map { it.sig }, ts.map { it.sig })
        val takenP = BooleanArray(ps.size)
        val takenT = BooleanArray(ts.size)
        val anchorP = IntArray(anchors.size) { anchors[it][0] }
        val anchorT = IntArray(anchors.size) { anchors[it][1] }
        for (a in anchors) { takenP[a[0]] = true; takenT[a[1]] = true }
        r.exact += anchors.size

        // Where a match (i, j) stands among the anchors: in its place, reordered, or out of order.
        fun place(i: Int, j: Int, p: Seen) {
            val x = lowerBound(anchorP, i)
            val y = lowerBound(anchorT, j)
            if (x == y) return
            for (k in minOf(x, y) until maxOf(x, y)) {
                val o = ps[anchorP[k]]
                if (o.e.key == p.e.key || !overlap(p, o)) continue
                r.outOfOrder++
                if (r.firstOutOfOrder.size < 5) r.firstOutOfOrder += "${p.e.where} ${shortKey(p.e.key)} passes ${o.e.where} ${shortKey(o.e.key)}"
                return
            }
            r.reordered++
        }

        // The same element elsewhere.
        val bySig = HashMap<String, ArrayDeque<Int>>()
        for (j in ts.indices) if (!takenT[j]) bySig.getOrPut(ts[j].sig) { ArrayDeque() }.addLast(j)
        for (i in ps.indices) {
            if (takenP[i]) continue
            val j = bySig[ps[i].sig]?.removeFirstOrNull() ?: continue
            takenP[i] = true; takenT[j] = true
            r.exact++
            place(i, j, ps[i])
        }

        // Subpaths in another order, or coordinates one print step apart: the same key, extents within a step.
        val byKey = HashMap<String, ArrayList<Int>>()
        for (j in ts.indices) if (!takenT[j]) byKey.getOrPut(ts[j].e.key) { ArrayList() } += j
        for (i in ps.indices) {
            if (takenP[i]) continue
            val p = ps[i]
            val list = byKey[p.e.key] ?: continue
            var best = -1
            var bestW = 2
            var bestReordered = false
            for (j in list) {
                if (takenT[j]) continue
                val t = ts[j]
                if ((0 until 4).any { Math.abs(t.box[it] - p.box[it]) > 0.2 }) continue
                val flag = BooleanArray(1)
                val w = compare(p, t, flag)
                if (w < bestW) { best = j; bestW = w; bestReordered = flag[0] }
                if (w == 0) break
            }
            if (best < 0) continue
            takenP[i] = true; takenT[best] = true
            if (bestW == 0) r.exact++ else r.oneStep++
            if (bestReordered) r.subpathsReordered++
            place(i, best, p)
        }

        // Fills the twin draws in pieces: fills of the same key inside the port's element, whose union covers what it does.
        for (i in ps.indices) {
            if (takenP[i]) continue
            val p = ps[i]
            if (!p.region) continue
            val list = byKey[p.e.key] ?: continue
            val inside = list.filter { j ->
                val t = ts[j]
                !takenT[j] && t.region && t.box[0] >= p.box[0] - 0.5 && t.box[1] >= p.box[1] - 0.5 && t.box[2] <= p.box[2] + 0.5 &&
                    t.box[3] <= p.box[3] + 0.5 && beyond(t.coverage, p.coverage, listOf(t.outline, p.outline), v) { a, b -> a && !b } == null
            }
            if (inside.isEmpty()) continue
            val pieces = inside.map { ts[it] }
            val union = Coverage.union(pieces.map { it.coverage })
            if (beyond(p.coverage, union, listOf(p.outline) + pieces.map { it.outline }, v) { a, b -> a != b } != null) continue
            takenP[i] = true
            for (j in inside) takenT[j] = true
            r.inPieces++
            r.pieces += inside.size
            r.piecesByKey.merge(shortKey(p.e.key), 1, Int::plus)
            for (j in inside) place(i, j, p)
        }

        // What differs: the nearest element of the same key whose extent meets its own.
        for (i in ps.indices) {
            if (takenP[i]) continue
            val p = ps[i]
            val list = byKey[p.e.key] ?: continue
            var best = -1
            var bestD = Double.MAX_VALUE
            for (j in list) {
                if (takenT[j]) continue
                val t = ts[j]
                if (!overlapBoxes(doubleArrayOf(p.box[0] - 1, p.box[1] - 1, p.box[2] + 1, p.box[3] + 1), t.box)) continue
                val d = Math.hypot((t.box[0] + t.box[2] - p.box[0] - p.box[2]) / 2, (t.box[1] + t.box[3] - p.box[1] - p.box[3]) / 2)
                if (d < bestD) { best = j; bestD = d }
            }
            if (best < 0) continue
            takenP[i] = true; takenT[best] = true
            r.differs++
            val t = ts[best]
            if (r.firstDiffering.size < 5) r.firstDiffering += "${t.e.where}: port ${p.vertices.joinToString(" ") { p1(it) }} [${p.shape}], " +
                "twin ${t.vertices.joinToString(" ") { p1(it) }} [${t.shape}]"
        }

        // What is left: missing and extra, but a fill that shows nothing of the view is outside it.
        var hiddenP = 0
        var hiddenT = 0
        for (i in ps.indices) {
            if (takenP[i]) continue
            if (!shows(ps[i])) { hiddenP++; r.outside++; r.outsideByKey.merge(shortKey(ps[i].e.key), 1, Int::plus); continue }
            r.missing.merge(shortKey(ps[i].e.key), 1, Int::plus)
        }
        for (j in ts.indices) {
            if (takenT[j]) continue
            if (!shows(ts[j])) { hiddenT++; r.twinOutside++; continue }
            r.extra.merge(shortKey(ts[j].e.key), 1, Int::plus)
        }
        r.port = ps.size - hiddenP
        r.twin = ts.size - hiddenT
        r.matched = r.exact + r.oneStep + r.differs + r.inPieces
        return r
    }

    /** How many of [a] (ascending) are below [x]. */
    private fun lowerBound(a: IntArray, x: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** A key short enough to report: the kind and the paints. */
    private fun shortKey(key: String): String = key.substringBefore("; o ").take(160)

    /** The style key of an SvgStyle (for tests that build elements by hand). */
    fun styleKeyOf(s: SvgStyle): String = styleKey(paintKey(s.fill), s.fillOpacity, s.evenOdd, paintKey(s.stroke), s.strokeOpacity,
        s.strokeWidth, s.cap.ordinal, s.join.ordinal, s.miterLimit, s.dashes?.toFloatArray() ?: FloatArray(0), s.dashOffset)
}
