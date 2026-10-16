package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgClip
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFe
import app.atlas.mapview.svg.SvgFilter
import app.atlas.mapview.svg.SvgFont
import app.atlas.mapview.svg.SvgGradient
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgPaint
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgPattern
import app.atlas.mapview.svg.SvgShape
import app.atlas.mapview.svg.SvgStyle
import app.atlas.mapview.svg.SvgText
import app.atlas.mapview.svg.SvgUnits
import java.util.Locale

/**
 * Builds the layer plan as the compiler walks the SVG in painter's order: layers (ground, raster, objects), groups that
 * need an effect (a sprite for opacity that cannot fold into one paint, a blend mode or a filter; a stencil clip), and
 * runs of consecutive primitives. A sprite is composited at its painter index, the index of its first primitive, which
 * puts it above everything drawn before it and below everything after. A group whose primitives are all fixed to the
 * screen is marked so (the sky, haze, fin and weather move with the horizon until G8).
 */
internal class PlanBuilder {
    private val nodes = ArrayList<PlanNode?>()
    private val open = ArrayList<Open>()
    private var run = -1
    private var runFirst = 0
    private var runLast = 0

    private class Open(
        val index: Int, val layer: LayerKind?, val opacity: Float, val blend: SvgBlend, val filter: Int, val filterBox: DoubleArray?,
        val clip: Int, val sprite: Boolean, val painter: Int,
    ) {
        var prims = 0
        var screen = 0
        var keep = false
    }

    val depth: Int get() = open.size

    fun openLayer(kind: LayerKind, primIndex: Int) {
        closeRun()
        open.add(Open(nodes.size, kind, 1f, SvgBlend.NORMAL, -1, null, -1, false, primIndex))
        nodes.add(null)
    }

    fun openGroup(opacity: Float, blend: SvgBlend, filter: Int, filterBox: DoubleArray?, clip: Int, sprite: Boolean, primIndex: Int) {
        closeRun()
        open.add(Open(nodes.size, null, opacity, blend, filter, filterBox, clip, sprite, primIndex))
        nodes.add(null)
    }

    /** Keeps the innermost node even when it holds no primitive (the raster layer). */
    fun keepEmpty() {
        open.last().keep = true
    }

    /** Closes the innermost layer or group; an empty one is dropped unless kept. */
    fun close() {
        closeRun()
        val o = open.removeAt(open.size - 1)
        if (o.prims == 0 && !o.keep && nodes.size == o.index + 1) {
            nodes.removeAt(o.index)
            return
        }
        val end = nodes.size
        nodes[o.index] = if (o.layer != null) PlanNode.Layer(o.layer, end) else PlanNode.Group(
            o.opacity, o.blend, o.filter, o.filterBox, o.clip, o.sprite, o.prims > 0 && o.screen == o.prims, o.painter, end,
        )
        if (open.isNotEmpty()) { open.last().prims += o.prims; open.last().screen += o.screen }
    }

    /** Primitive [i] (the next in painter's order) joins the innermost open node's run. */
    fun prim(i: Int, screenFixed: Boolean) {
        if (run >= 0 && runLast == i) {
            runLast = i + 1
        } else {
            closeRun()
            run = nodes.size; runFirst = i; runLast = i + 1
            nodes.add(null)
        }
        if (open.isNotEmpty()) { open.last().prims++; if (screenFixed) open.last().screen++ }
    }

    private fun closeRun() {
        if (run < 0) return
        nodes[run] = PlanNode.Run(runFirst, runLast, run + 1)
        run = -1
    }

    fun build(clips: List<AnchorClip>): AnchorPlan {
        closeRun()
        check(open.isEmpty()) { "${open.size} plan nodes left open" }
        return AnchorPlan(nodes.map { it!! }, clips)
    }
}

/**
 * Writes the SVG model back as SVG text: the anchor's screen-fixed elements (`SCRN`) and its pattern tiles (`PATT`),
 * with the definitions they use. Numbers are written in a fixed form, so the text is deterministic and parses back to
 * the same model.
 */
internal object SvgWriter {
    private fun n(v: Float): String {
        if (v == v.toLong().toFloat() && Math.abs(v) < 1e9) return v.toLong().toString()
        val s = String.format(Locale.ROOT, "%.4f", v).trimEnd('0')
        return if (s.endsWith('.')) s.dropLast(1) else s
    }

    private fun hex(c: Int) = String.format(Locale.ROOT, "#%06X", c and 0xFFFFFF)

    private fun matrix(t: FloatArray) = "matrix(${t.joinToString(" ") { n(it) }})"

    private fun paint(p: SvgPaint): String = when (p) {
        SvgPaint.None -> "none"
        is SvgPaint.Color -> hex(p.argb)
        is SvgPaint.Ref -> "url(#${p.id})"
    }

    /** The colour's own alpha as an opacity factor, folded into fill- or stroke-opacity. */
    private fun alpha(p: SvgPaint): Float = if (p is SvgPaint.Color) (p.argb ushr 24) / 255f else 1f

    private fun style(sb: StringBuilder, st: SvgStyle) {
        sb.append(" fill=\"").append(paint(st.fill)).append('"')
        val fo = st.fillOpacity * alpha(st.fill)
        if (fo < 1f) sb.append(" fill-opacity=\"").append(n(fo)).append('"')
        if (st.evenOdd) sb.append(" fill-rule=\"evenodd\"")
        if (st.stroke != SvgPaint.None) {
            sb.append(" stroke=\"").append(paint(st.stroke)).append('"')
            val so = st.strokeOpacity * alpha(st.stroke)
            if (so < 1f) sb.append(" stroke-opacity=\"").append(n(so)).append('"')
            sb.append(" stroke-width=\"").append(n(st.strokeWidth)).append('"')
            if (st.cap != SvgStyle.Cap.BUTT) sb.append(" stroke-linecap=\"").append(st.cap.name.lowercase(Locale.ROOT)).append('"')
            if (st.join != SvgStyle.Join.MITER) sb.append(" stroke-linejoin=\"").append(st.join.name.lowercase(Locale.ROOT)).append('"')
            if (st.miterLimit != 4f) sb.append(" stroke-miterlimit=\"").append(n(st.miterLimit)).append('"')
            st.dashes?.let { d -> sb.append(" stroke-dasharray=\"").append(d.joinToString(" ") { n(it) }).append('"') }
            if (st.dashOffset != 0f) sb.append(" stroke-dashoffset=\"").append(n(st.dashOffset)).append('"')
        }
    }

    private fun common(sb: StringBuilder, node: SvgNode) {
        node.transform?.let { sb.append(" transform=\"").append(matrix(it)).append('"') }
        if (node.opacity < 1f) sb.append(" opacity=\"").append(n(node.opacity)).append('"')
        node.filter?.let { sb.append(" filter=\"url(#").append(it.id).append(")\"") }
        node.clip?.let { sb.append(" clip-path=\"url(#").append(it.id).append(")\"") }
        if (node.blend != SvgBlend.NORMAL) sb.append(" style=\"mix-blend-mode:").append(node.blend.name.lowercase(Locale.ROOT).replace('_', '-')).append('"')
    }

    /** Writes [node] (and its subtree) of [doc]. */
    fun node(sb: StringBuilder, doc: SvgDocument, node: SvgNode) {
        when (node) {
            is SvgGroup -> {
                sb.append("<g"); common(sb, node); sb.append('>')
                for (c in node.children) node(sb, doc, c)
                sb.append("</g>")
            }
            is SvgShape -> {
                val g = node.geometry
                when (node.kind) {
                    SvgShape.Kind.PATH -> {
                        sb.append("<path d=\"")
                        val verbs = doc.path.verbs; val xy = doc.path.coords
                        var k = node.coordStart
                        for (v in node.verbStart until node.verbEnd) {
                            when (verbs[v]) {
                                SvgPathData.MOVE -> { sb.append('M').append(n(xy[k])).append(' ').append(n(xy[k + 1])); k += 2 }
                                SvgPathData.LINE -> { sb.append('L').append(n(xy[k])).append(' ').append(n(xy[k + 1])); k += 2 }
                                else -> sb.append('Z')
                            }
                        }
                        sb.append('"')
                    }
                    SvgShape.Kind.RECT -> {
                        sb.append("<rect x=\"").append(n(g[0])).append("\" y=\"").append(n(g[1])).append("\" width=\"").append(n(g[2]))
                            .append("\" height=\"").append(n(g[3])).append('"')
                        if (g[4] > 0f) sb.append(" rx=\"").append(n(g[4])).append('"')
                        if (g[5] > 0f) sb.append(" ry=\"").append(n(g[5])).append('"')
                    }
                    SvgShape.Kind.CIRCLE -> sb.append("<circle cx=\"").append(n(g[0])).append("\" cy=\"").append(n(g[1])).append("\" r=\"").append(n(g[2])).append('"')
                    SvgShape.Kind.ELLIPSE -> sb.append("<ellipse cx=\"").append(n(g[0])).append("\" cy=\"").append(n(g[1])).append("\" rx=\"").append(n(g[2]))
                        .append("\" ry=\"").append(n(g[3])).append('"')
                }
                style(sb, node.style); common(sb, node); sb.append("/>")
            }
            is SvgText -> {
                val f = node.font
                sb.append("<text x=\"").append(n(node.x)).append("\" y=\"").append(n(node.y)).append("\" font-family=\"").append(esc(f.family))
                    .append("\" font-size=\"").append(n(f.size)).append("\" font-weight=\"").append(f.weight).append('"')
                if (f.italic) sb.append(" font-style=\"italic\"")
                if (f.letterSpacing != 0f) sb.append(" letter-spacing=\"").append(n(f.letterSpacing)).append('"')
                if (f.anchor != SvgFont.Anchor.START) sb.append(" text-anchor=\"").append(f.anchor.name.lowercase(Locale.ROOT)).append('"')
                style(sb, node.style); common(sb, node); sb.append('>').append(esc(node.text)).append("</text>")
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The definitions [nodes] use (gradients, patterns, filters, clips), written once each. */
    fun defs(sb: StringBuilder, doc: SvgDocument, nodes: List<SvgNode>) {
        val grads = LinkedHashMap<String, SvgGradient>()
        val pats = LinkedHashMap<String, SvgPattern>()
        val filters = LinkedHashMap<String, SvgFilter>()
        val clips = LinkedHashMap<String, SvgClip>()
        fun ref(p: SvgPaint) {
            if (p !is SvgPaint.Ref) return
            doc.gradients[p.id]?.let { grads[p.id] = it }
            doc.patterns[p.id]?.let { pats[p.id] = it }
        }
        fun visit(node: SvgNode) {
            node.filter?.let { filters[it.id] = it }
            var c = node.clip
            while (c != null && c.id !in clips) { clips[c.id] = c; c.children.forEach { visit(it) }; c = c.clip }
            when (node) {
                is SvgGroup -> node.children.forEach { visit(it) }
                is SvgShape -> { ref(node.style.fill); ref(node.style.stroke) }
                is SvgText -> ref(node.style.fill)
            }
        }
        nodes.forEach { visit(it) }
        pats.values.forEach { p -> p.content.children.forEach { visit(it) } }
        if (grads.isEmpty() && pats.isEmpty() && filters.isEmpty() && clips.isEmpty()) return
        sb.append("<defs>")
        for (g in grads.values) gradient(sb, g)
        for (p in pats.values) {
            sb.append("<pattern id=\"").append(p.id).append("\" x=\"").append(n(p.x)).append("\" y=\"").append(n(p.y)).append("\" width=\"").append(n(p.width))
                .append("\" height=\"").append(n(p.height)).append('"')
            if (p.units == SvgUnits.USER_SPACE) sb.append(" patternUnits=\"userSpaceOnUse\"")
            p.transform?.let { sb.append(" patternTransform=\"").append(matrix(it)).append('"') }
            sb.append('>')
            for (c in p.content.children) node(sb, doc, c)
            sb.append("</pattern>")
        }
        for (f in filters.values) filter(sb, f)
        for (c in clips.values) {
            sb.append("<clipPath id=\"").append(c.id).append('"')
            if (c.units == SvgUnits.BOUNDING_BOX) sb.append(" clipPathUnits=\"objectBoundingBox\"")
            c.clip?.let { sb.append(" clip-path=\"url(#").append(it.id).append(")\"") }
            sb.append('>')
            for (ch in c.children) node(sb, doc, ch)
            sb.append("</clipPath>")
        }
        sb.append("</defs>")
    }

    private fun gradient(sb: StringBuilder, g: SvgGradient) {
        val k = g.coords
        if (g.radial) {
            sb.append("<radialGradient id=\"").append(g.id).append("\" cx=\"").append(n(k[0])).append("\" cy=\"").append(n(k[1])).append("\" r=\"").append(n(k[2]))
                .append("\" fx=\"").append(n(k[3])).append("\" fy=\"").append(n(k[4])).append('"')
        } else {
            sb.append("<linearGradient id=\"").append(g.id).append("\" x1=\"").append(n(k[0])).append("\" y1=\"").append(n(k[1])).append("\" x2=\"").append(n(k[2]))
                .append("\" y2=\"").append(n(k[3])).append('"')
        }
        if (g.units == SvgUnits.USER_SPACE) sb.append(" gradientUnits=\"userSpaceOnUse\"")
        g.transform?.let { sb.append(" gradientTransform=\"").append(matrix(it)).append('"') }
        if (g.spread != SvgGradient.Spread.PAD) sb.append(" spreadMethod=\"").append(g.spread.name.lowercase(Locale.ROOT)).append('"')
        sb.append('>')
        for (i in g.offsets.indices) {
            val c = g.colors[i]
            sb.append("<stop offset=\"").append(n(g.offsets[i])).append("\" stop-color=\"").append(hex(c)).append('"')
            if ((c ushr 24) != 0xFF) sb.append(" stop-opacity=\"").append(n((c ushr 24) / 255f)).append('"')
            sb.append("/>")
        }
        sb.append(if (g.radial) "</radialGradient>" else "</linearGradient>")
    }

    private fun filter(sb: StringBuilder, f: SvgFilter) {
        val r = f.region
        sb.append("<filter id=\"").append(f.id).append('"')
        if (f.units == SvgUnits.USER_SPACE) sb.append(" filterUnits=\"userSpaceOnUse\"")
        sb.append(" x=\"").append(n(r[0])).append("\" y=\"").append(n(r[1])).append("\" width=\"").append(n(r[2])).append("\" height=\"").append(n(r[3])).append('"')
        if (!f.linearRgb) sb.append(" color-interpolation-filters=\"sRGB\"")
        sb.append('>')
        for (p in f.primitives) primitive(sb, p)
        sb.append("</filter>")
    }

    private fun primitive(sb: StringBuilder, p: SvgFe) {
        fun io() {
            p.input?.let { sb.append(" in=\"").append(it).append('"') }
            p.input2?.let { sb.append(" in2=\"").append(it).append('"') }
            p.result?.let { sb.append(" result=\"").append(it).append('"') }
            sb.append(" color-interpolation-filters=\"").append(if (p.linearRgb) "linearRGB" else "sRGB").append('"')
        }
        when (p) {
            is SvgFe.Turbulence -> {
                sb.append("<feTurbulence type=\"").append(if (p.fractal) "fractalNoise" else "turbulence").append("\" baseFrequency=\"").append(n(p.baseX)).append(' ')
                    .append(n(p.baseY)).append("\" numOctaves=\"").append(p.octaves).append("\" seed=\"").append(n(p.seed)).append('"')
                if (p.stitch) sb.append(" stitchTiles=\"stitch\"")
            }
            is SvgFe.DisplacementMap -> sb.append("<feDisplacementMap scale=\"").append(n(p.scale)).append("\" xChannelSelector=\"").append("RGBA"[p.xChannel])
                .append("\" yChannelSelector=\"").append("RGBA"[p.yChannel]).append('"')
            is SvgFe.ColorMatrix -> sb.append("<feColorMatrix type=\"matrix\" values=\"").append(p.matrix.joinToString(" ") { n(it) }).append('"')
            is SvgFe.Composite -> {
                sb.append("<feComposite operator=\"").append(p.operator.name.lowercase(Locale.ROOT)).append('"')
                if (p.operator == SvgFe.Composite.Operator.ARITHMETIC) sb.append(" k1=\"").append(n(p.k1)).append("\" k2=\"").append(n(p.k2))
                    .append("\" k3=\"").append(n(p.k3)).append("\" k4=\"").append(n(p.k4)).append('"')
            }
            is SvgFe.GaussianBlur -> sb.append("<feGaussianBlur stdDeviation=\"").append(n(p.deviationX)).append(' ').append(n(p.deviationY)).append('"')
            is SvgFe.Offset -> sb.append("<feOffset dx=\"").append(n(p.dx)).append("\" dy=\"").append(n(p.dy)).append('"')
            is SvgFe.Flood -> sb.append("<feFlood flood-color=\"").append(hex(p.argb)).append("\" flood-opacity=\"").append(n((p.argb ushr 24) / 255f)).append('"')
            is SvgFe.Merge -> {
                sb.append("<feMerge"); io(); sb.append('>')
                for (i in p.inputs) { sb.append("<feMergeNode"); i?.let { sb.append(" in=\"").append(it).append('"') }; sb.append("/>") }
                sb.append("</feMerge>")
                return
            }
        }
        io(); sb.append("/>")
    }

    /** A pattern's tile as a standalone SVG document [w] × [h] (pattern units): its content (of [doc]) in tile space. */
    fun patternTile(p: SvgPattern, w: Float, h: Float, doc: SvgDocument): String {
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n(w)).append("\" height=\"").append(n(h)).append("\" viewBox=\"0 0 ")
            .append(n(w)).append(' ').append(n(h)).append("\">")
        val body = StringBuilder()
        defs(body, doc, p.content.children)
        for (c in p.content.children) node(body, doc, c)
        sb.append(body).append("</svg>")
        return sb.toString()
    }

    /** A document of [nodes] of [doc], [w] × [h], with the definitions they use. */
    fun document(doc: SvgDocument, nodes: List<SvgNode>, w: Double, h: Double): String {
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n(w.toFloat())).append(' ').append(n(h.toFloat()))
            .append("\" width=\"").append(n(w.toFloat())).append("\" height=\"").append(n(h.toFloat())).append("\">")
        defs(sb, doc, nodes)
        for (node in nodes) node(sb, doc, node)
        sb.append("</svg>")
        return sb.toString()
    }
}
