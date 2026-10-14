package app.atlas.mapview.svg

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses the SVG the engine writes into an [SvgDocument] in one streaming pass: inherited presentation is resolved as
 * the tree is read, paths go straight from the text into packed arrays, and references (`url(#id)`, `href`) are
 * resolved at the end, so definitions may come before or after their use.
 *
 * It supports exactly what the engine's renders contain (see the survey in the test sources) and fails with
 * [SvgFormatException] on anything else.
 */
object SvgParser {
    fun parse(text: String): SvgDocument = parse(text.toCharArray())

    fun parse(file: File): SvgDocument = file.inputStream().use { parse(it) }

    fun parse(input: InputStream): SvgDocument {
        val reader = InputStreamReader(input, Charsets.UTF_8)
        var buf = CharArray(1 shl 16)
        var n = 0
        while (true) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            val r = reader.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return parse(buf, n)
    }

    fun parse(chars: CharArray, length: Int = chars.size): SvgDocument = Reader(chars, length).read()

    /** The properties that inherit down the tree. */
    private class Inherited(
        var fill: SvgPaint = SvgPaint.Color(0xFF000000.toInt()),
        var fillOpacity: Float = 1f,
        var evenOdd: Boolean = false,
        var stroke: SvgPaint = SvgPaint.None,
        var strokeOpacity: Float = 1f,
        var strokeWidth: Float = 1f,
        var cap: SvgStyle.Cap = SvgStyle.Cap.BUTT,
        var join: SvgStyle.Join = SvgStyle.Join.MITER,
        var miterLimit: Float = 4f,
        var dashes: List<Float>? = null,
        var dashOffset: Float = 0f,
        var fontFamily: String = "serif",
        var fontSize: Float = 16f,
        var fontWeight: Int = 400,
        var italic: Boolean = false,
        var letterSpacing: Float = 0f,
        var anchor: SvgFont.Anchor = SvgFont.Anchor.START,
        var linearRgb: Boolean = true,
        var visible: Boolean = true,
    ) {
        fun copy() = Inherited(
            fill, fillOpacity, evenOdd, stroke, strokeOpacity, strokeWidth, cap, join, miterLimit, dashes, dashOffset,
            fontFamily, fontSize, fontWeight, italic, letterSpacing, anchor, linearRgb, visible,
        )
    }

    /** The properties of one element that do not inherit. */
    private class Own {
        var id: String? = null
        var transform: FloatArray? = null
        var opacity = 1f
        var filter: String? = null
        var clip: String? = null
        var blend = SvgBlend.NORMAL
        var display = true

        fun reset() {
            id = null
            transform = null
            opacity = 1f
            filter = null
            clip = null
            blend = SvgBlend.NORMAL
            display = true
        }
    }

    private enum class Kind { ROOT, GROUP, DEFS, CLIP, PATTERN, GRADIENT, FILTER, MERGE, TEXT, SHAPE, SKIP }

    private class Frame(val kind: Kind, val inherited: Inherited) {
        val nodes = ArrayList<SvgNode>()
        var own: Own? = null
        var attrs: Map<String, String> = emptyMap()
        var offsets: ArrayList<Float>? = null
        var colors: ArrayList<Int>? = null
        var primitives: ArrayList<SvgFe>? = null
        var mergeInputs: ArrayList<String?>? = null
        var text: StringBuilder? = null
        var textX = 0f
        var textY = 0f
    }

    private class RawGradient(val radial: Boolean, val attrs: Map<String, String>, val offsets: FloatArray, val colors: IntArray)
    private class RawPattern(val attrs: Map<String, String>, val content: SvgGroup?)

    private class Reader(private val s: CharArray, n: Int) {
        private val xml = SvgXml(s, n)
        private val nums = SvgNumbers(s)
        private val path = SvgValues.PathBuilder()
        private val stack = ArrayList<Frame>()
        private val own = Own()
        private val styles = LinkedHashMap<SvgStyle, SvgStyle>()
        private val paints = HashMap<String, SvgPaint>()
        private val gradients = LinkedHashMap<String, RawGradient>()
        private val patterns = LinkedHashMap<String, RawPattern>()
        private val filters = LinkedHashMap<String, SvgFilter>()
        private val clips = LinkedHashMap<String, SvgClip>()
        private val clipOfClip = ArrayList<Pair<SvgClip, String>>()
        private val pendingRefs = ArrayList<Triple<SvgNode, String?, String?>>()
        private var width = Float.NaN
        private var height = Float.NaN
        private var viewBox: FloatArray? = null
        private var root: SvgGroup? = null

        fun read(): SvgDocument {
            while (true) {
                when (xml.next()) {
                    SvgXml.EOF -> break
                    SvgXml.START -> start()
                    SvgXml.END -> end()
                    SvgXml.TEXT -> {
                        val f = stack.lastOrNull()
                        if (f != null && f.kind == Kind.TEXT) f.text!!.append(xml.text())
                    }
                }
            }
            val r = root ?: throw SvgFormatException("no <svg> element")
            val vb = viewBox ?: floatArrayOf(0f, 0f, if (width.isNaN()) 300f else width, if (height.isNaN()) 150f else height)
            val w = if (width.isNaN()) vb[2] else width
            val h = if (height.isNaN()) vb[3] else height
            val finalGradients = LinkedHashMap<String, SvgGradient>()
            for (id in gradients.keys) resolveGradient(id)?.let { finalGradients[id] = it }
            val finalPatterns = LinkedHashMap<String, SvgPattern>()
            for (id in patterns.keys) resolvePattern(id)?.let { finalPatterns[id] = it }
            for ((clip, ref) in clipOfClip) clip.clip = clips[ref]
            for ((node, f, c) in pendingRefs) {
                if (f != null) node.filter = filters[f]
                if (c != null) node.clip = clips[c]
            }
            return SvgDocument(w, h, vb, r, path.build(), finalGradients, finalPatterns, filters, clips, styles.keys.toList())
        }

        private fun top() = stack.last()

        private fun start() {
            val parent = stack.lastOrNull()
            if (parent != null && (parent.kind == Kind.SKIP || parent.kind == Kind.SHAPE)) {
                stack.add(Frame(Kind.SKIP, parent.inherited))
                return
            }
            if (parent != null && parent.kind == Kind.TEXT) throw SvgFormatException("<${xml.name()}> inside <text> is not written by the engine")
            val inh = (parent?.inherited ?: Inherited()).copy()
            own.reset()
            readProperties(inh)
            val name = xml.name()
            if (!own.display) {
                stack.add(Frame(Kind.SKIP, inh))
                return
            }
            when (name) {
                "svg" -> {
                    if (parent != null) throw SvgFormatException("nested <svg> is not written by the engine")
                    xml.find("width").takeIf { it >= 0 }?.let { width = SvgValues.parseLength(xml.value(it)) }
                    xml.find("height").takeIf { it >= 0 }?.let { height = SvgValues.parseLength(xml.value(it)) }
                    xml.find("viewBox").takeIf { it >= 0 }?.let {
                        val v = SvgValues.parseNumbers(xml.value(it))
                        if (v.size != 4) throw SvgFormatException("bad viewBox")
                        viewBox = v
                    }
                    stack.add(Frame(Kind.ROOT, inh).also { it.own = snapshot() })
                }
                "g" -> stack.add(Frame(if (parent?.kind == Kind.DEFS) Kind.DEFS else Kind.GROUP, inh).also { it.own = snapshot() })
                "defs" -> stack.add(Frame(Kind.DEFS, inh))
                "title", "desc", "metadata" -> stack.add(Frame(Kind.SKIP, inh))
                "path", "rect", "circle", "ellipse" -> {
                    shape(name, inh)?.let { add(it) }
                    stack.add(Frame(Kind.SHAPE, inh))
                }
                "text" -> stack.add(Frame(Kind.TEXT, inh).also {
                    it.own = snapshot()
                    it.text = StringBuilder()
                    it.textX = attrNumber("x", 0f)
                    it.textY = attrNumber("y", 0f)
                })
                "clipPath" -> stack.add(Frame(Kind.CLIP, inh).also { it.own = snapshot(); it.attrs = attributes() })
                "pattern" -> stack.add(Frame(Kind.PATTERN, inh).also { it.own = snapshot(); it.attrs = attributes() })
                "linearGradient", "radialGradient" -> stack.add(Frame(Kind.GRADIENT, inh).also {
                    it.attrs = attributes() + ("#radial" to (name == "radialGradient").toString())
                    it.offsets = ArrayList()
                    it.colors = ArrayList()
                })
                "stop" -> {
                    if (parent?.kind != Kind.GRADIENT) throw SvgFormatException("<stop> outside a gradient")
                    stop(parent)
                    stack.add(Frame(Kind.SKIP, inh))
                }
                "filter" -> stack.add(Frame(Kind.FILTER, inh).also { it.attrs = attributes(); it.primitives = ArrayList() })
                "feMergeNode" -> {
                    if (parent?.kind != Kind.MERGE) throw SvgFormatException("<feMergeNode> outside <feMerge>")
                    parent.mergeInputs!!.add(xml.find("in").takeIf { it >= 0 }?.let { xml.value(it) })
                    stack.add(Frame(Kind.SKIP, inh))
                }
                else -> if (name.startsWith("fe")) {
                    if (parent?.kind != Kind.FILTER) throw SvgFormatException("<$name> outside <filter>")
                    if (name == "feMerge") {
                        stack.add(Frame(Kind.MERGE, inh).also { it.attrs = attributes(); it.mergeInputs = ArrayList() })
                    } else {
                        parent.primitives!!.add(primitive(name, attributes(), inh))
                        stack.add(Frame(Kind.SKIP, inh))
                    }
                } else {
                    throw SvgFormatException("<$name> is not written by the engine")
                }
            }
        }

        private fun end() {
            val f = stack.removeAt(stack.size - 1)
            when (f.kind) {
                Kind.ROOT -> root = SvgGroup(f.nodes).also { apply(it, f.own!!) }
                Kind.GROUP -> add(SvgGroup(f.nodes).also { apply(it, f.own!!) })
                Kind.TEXT -> {
                    val t = collapse(f.text.toString())
                    if (t.isNotEmpty()) {
                        val i = f.inherited
                        val font = SvgFont(i.fontFamily, i.fontSize, i.fontWeight, i.italic, i.letterSpacing, i.anchor)
                        add(SvgText(t, f.textX, f.textY, font, style(i)).also { apply(it, f.own!!) })
                    }
                }
                Kind.CLIP -> {
                    val o = f.own!!
                    val id = o.id ?: return
                    val units = if (f.attrs["clipPathUnits"] == "objectBoundingBox") SvgUnits.BOUNDING_BOX else SvgUnits.USER_SPACE
                    val clip = SvgClip(id, units, f.nodes)
                    clips[id] = clip
                    o.clip?.let { clipOfClip.add(clip to it) }
                }
                Kind.PATTERN -> {
                    val id = f.own!!.id ?: return
                    patterns[id] = RawPattern(f.attrs, if (f.nodes.isEmpty()) null else SvgGroup(f.nodes))
                }
                Kind.GRADIENT -> {
                    val id = f.attrs["id"] ?: return
                    gradients[id] = RawGradient(f.attrs["#radial"] == "true", f.attrs, f.offsets!!.toFloatArray(), f.colors!!.toIntArray())
                }
                Kind.FILTER -> {
                    val id = f.attrs["id"] ?: return
                    filters[id] = filter(id, f.attrs, f.primitives!!, f.inherited)
                }
                Kind.MERGE -> {
                    val parent = top()
                    parent.primitives!!.add(SvgFe.Merge(f.mergeInputs!!).also { fe ->
                        fe.result = f.attrs["result"]
                        fe.linearRgb = f.inherited.linearRgb
                    })
                }
                else -> Unit
            }
        }

        /** Adds a finished node to the nearest container that draws or collects. */
        private fun add(node: SvgNode) {
            val f = top()
            when (f.kind) {
                Kind.ROOT, Kind.GROUP, Kind.CLIP, Kind.PATTERN -> f.nodes.add(node)
                else -> Unit
            }
        }

        private fun snapshot() = Own().also {
            it.id = own.id
            it.transform = own.transform
            it.opacity = own.opacity
            it.filter = own.filter
            it.clip = own.clip
            it.blend = own.blend
        }

        private fun apply(node: SvgNode, o: Own) {
            node.transform = o.transform
            node.opacity = o.opacity
            node.blend = o.blend
            if (o.filter != null || o.clip != null) pendingRefs.add(Triple(node, o.filter, o.clip))
        }

        private fun attributes(): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            for (i in 0 until xml.attrCount) m[xml.attrName(i)] = xml.value(i)
            return m
        }

        /** Reads the element's presentation attributes, then its `style`, which wins. */
        private fun readProperties(inh: Inherited) {
            var style = -1
            for (i in 0 until xml.attrCount) {
                if (xml.attrNameIs(i, "style")) {
                    style = i
                    continue
                }
                if (xml.attrNameIs(i, "d")) continue
                property(xml.attrName(i), i, null, inh)
            }
            if (style >= 0) {
                for (decl in xml.value(style).split(';')) {
                    val colon = decl.indexOf(':')
                    if (colon < 0) continue
                    property(decl.substring(0, colon).trim(), -1, decl.substring(colon + 1).trim(), inh)
                }
            }
        }

        private fun property(name: String, attr: Int, given: String?, inh: Inherited) {
            fun v() = given ?: xml.value(attr)
            fun num(): Float = if (given != null) SvgValues.parseLength(given) else spanNumber(attr)
            when (name) {
                "id" -> own.id = v()
                "transform" -> own.transform = SvgValues.parseTransform(v())
                "opacity" -> own.opacity = num().coerceIn(0f, 1f)
                "filter" -> own.filter = urlId(v())
                "clip-path" -> own.clip = urlId(v())
                "mix-blend-mode" -> own.blend = blend(v())
                "display" -> own.display = v() != "none"
                "visibility" -> inh.visible = v() == "visible"
                "fill" -> inh.fill = paint(attr, given, inh.fill)
                "stroke" -> inh.stroke = paint(attr, given, inh.stroke)
                "fill-opacity" -> inh.fillOpacity = num().coerceIn(0f, 1f)
                "stroke-opacity" -> inh.strokeOpacity = num().coerceIn(0f, 1f)
                "stroke-width" -> inh.strokeWidth = num()
                "fill-rule" -> inh.evenOdd = v() == "evenodd"
                "stroke-linecap" -> inh.cap = when (v()) {
                    "round" -> SvgStyle.Cap.ROUND
                    "square" -> SvgStyle.Cap.SQUARE
                    else -> SvgStyle.Cap.BUTT
                }
                "stroke-linejoin" -> inh.join = when (v()) {
                    "round" -> SvgStyle.Join.ROUND
                    "bevel" -> SvgStyle.Join.BEVEL
                    else -> SvgStyle.Join.MITER
                }
                "stroke-miterlimit" -> inh.miterLimit = num()
                "stroke-dasharray" -> inh.dashes = dashes(v())
                "stroke-dashoffset" -> inh.dashOffset = num()
                "font-family" -> inh.fontFamily = v()
                "font-size" -> inh.fontSize = num()
                "font-weight" -> inh.fontWeight = when (val w = v()) {
                    "normal" -> 400
                    "bold" -> 700
                    "bolder" -> if (inh.fontWeight < 600) 700 else 900
                    "lighter" -> if (inh.fontWeight > 500) 400 else 100
                    else -> w.toIntOrNull() ?: throw SvgFormatException("bad font-weight '$w'")
                }
                "font-style" -> inh.italic = v() == "italic" || v() == "oblique"
                "letter-spacing" -> inh.letterSpacing = if (v() == "normal") 0f else num()
                "text-anchor" -> inh.anchor = when (v()) {
                    "middle" -> SvgFont.Anchor.MIDDLE
                    "end" -> SvgFont.Anchor.END
                    else -> SvgFont.Anchor.START
                }
                "color-interpolation-filters" -> inh.linearRgb = v() != "sRGB"
                else -> Unit
            }
        }

        private fun blend(v: String) = when (v) {
            "normal" -> SvgBlend.NORMAL
            "multiply" -> SvgBlend.MULTIPLY
            "screen" -> SvgBlend.SCREEN
            "overlay" -> SvgBlend.OVERLAY
            "soft-light" -> SvgBlend.SOFT_LIGHT
            "darken" -> SvgBlend.DARKEN
            "lighten" -> SvgBlend.LIGHTEN
            else -> throw SvgFormatException("mix-blend-mode '$v' is not written by the engine")
        }

        private fun urlId(v: String): String? {
            val t = v.trim()
            if (t == "none") return null
            if (!t.startsWith("url(")) throw SvgFormatException("bad reference '$v'")
            val inner = t.substring(4, t.indexOf(')')).trim().trim('"', '\'')
            return if (inner.startsWith("#")) inner.substring(1) else inner
        }

        /** A paint, read straight from the attribute span for the common `#rrggbb`. */
        private fun paint(attr: Int, given: String?, current: SvgPaint): SvgPaint {
            if (given == null) {
                val a = xml.valueStart(attr)
                val b = xml.valueEnd(attr)
                if (b - a == 7 && s[a] == '#') {
                    var c = 0
                    for (k in a + 1 until b) {
                        val d = Character.digit(s[k], 16)
                        if (d < 0) throw SvgFormatException("bad colour '${String(s, a, b - a)}'")
                        c = c * 16 + d
                    }
                    return SvgPaint.Color(c or (0xFF shl 24))
                }
            }
            val v = (given ?: xml.value(attr)).trim()
            if (v == "inherit") return current
            paints[v]?.let { return it }
            val p = if (v.startsWith("url(")) {
                val close = v.indexOf(')')
                val id = urlId(v.substring(0, close + 1))!!
                val rest = v.substring(close + 1).trim()
                SvgPaint.Ref(id, if (rest.isEmpty()) SvgPaint.None else SvgValues.parseColor(rest)?.let { SvgPaint.Color(it) } ?: SvgPaint.None)
            } else {
                SvgValues.parseColor(v)?.let { SvgPaint.Color(it) } ?: SvgPaint.None
            }
            paints[v] = p
            return p
        }

        private fun dashes(v: String): List<Float>? {
            if (v.trim() == "none") return null
            val d = SvgValues.parseNumbers(v)
            if (d.isEmpty() || d.any { it < 0 } || d.all { it == 0f }) return null
            val list = d.toList()
            return if (list.size % 2 == 1) list + list else list
        }

        private fun spanNumber(attr: Int): Float {
            val a = xml.valueStart(attr)
            val b = xml.valueEnd(attr)
            nums.reset(a, b)
            val v = nums.next()
            nums.skipSeparators()
            if (nums.pos < b) {
                if (s[nums.pos] == '%') return v / 100f
                if (b - nums.pos == 2 && s[nums.pos] == 'p' && s[nums.pos + 1] == 'x') return v
                throw SvgFormatException("bad number '${String(s, a, b - a)}'")
            }
            return v
        }

        private fun attrNumber(name: String, default: Float): Float {
            val i = xml.find(name)
            return if (i < 0) default else spanNumber(i)
        }

        private fun style(i: Inherited): SvgStyle {
            val st = SvgStyle(
                if (i.visible) i.fill else SvgPaint.None, i.fillOpacity, i.evenOdd,
                if (i.visible) i.stroke else SvgPaint.None, i.strokeOpacity, i.strokeWidth,
                i.cap, i.join, i.miterLimit, i.dashes, i.dashOffset,
            )
            return styles.getOrPut(st) { st }
        }

        private fun shape(name: String, inh: Inherited): SvgShape? {
            val st = style(inh)
            val shape = when (name) {
                "path" -> {
                    val d = xml.find("d")
                    if (d < 0) return null
                    val v0 = path.verbCount
                    val c0 = path.coordCount
                    SvgValues.parsePath(s, xml.valueStart(d), xml.valueEnd(d), path, nums)
                    if (path.verbCount == v0) return null
                    SvgShape(SvgShape.Kind.PATH, EMPTY, v0, path.verbCount, c0, st)
                }
                "rect" -> {
                    val w = attrNumber("width", 0f)
                    val h = attrNumber("height", 0f)
                    if (w <= 0f || h <= 0f) return null
                    val rxi = xml.find("rx")
                    val ryi = xml.find("ry")
                    var rx = if (rxi >= 0) spanNumber(rxi) else Float.NaN
                    var ry = if (ryi >= 0) spanNumber(ryi) else Float.NaN
                    if (rx.isNaN()) rx = if (ry.isNaN()) 0f else ry
                    if (ry.isNaN()) ry = rx
                    rx = rx.coerceIn(0f, w / 2)
                    ry = ry.coerceIn(0f, h / 2)
                    SvgShape(SvgShape.Kind.RECT, floatArrayOf(attrNumber("x", 0f), attrNumber("y", 0f), w, h, rx, ry), 0, 0, 0, st)
                }
                "circle" -> {
                    val r = attrNumber("r", 0f)
                    if (r <= 0f) return null
                    SvgShape(SvgShape.Kind.CIRCLE, floatArrayOf(attrNumber("cx", 0f), attrNumber("cy", 0f), r), 0, 0, 0, st)
                }
                else -> {
                    val rx = attrNumber("rx", 0f)
                    val ry = attrNumber("ry", 0f)
                    if (rx <= 0f || ry <= 0f) return null
                    SvgShape(SvgShape.Kind.ELLIPSE, floatArrayOf(attrNumber("cx", 0f), attrNumber("cy", 0f), rx, ry), 0, 0, 0, st)
                }
            }
            apply(shape, own)
            return shape
        }

        private fun stop(g: Frame) {
            var offset = 0f
            var color = 0xFF000000.toInt()
            var opacity = 1f
            fun take(name: String, v: String) {
                when (name) {
                    "offset" -> offset = SvgValues.parseLength(v).coerceIn(0f, 1f)
                    "stop-color" -> color = SvgValues.parseColor(v) ?: 0
                    "stop-opacity" -> opacity = SvgValues.parseLength(v).coerceIn(0f, 1f)
                }
            }
            for (i in 0 until xml.attrCount) {
                val n = xml.attrName(i)
                if (n == "style") {
                    for (decl in xml.value(i).split(';')) {
                        val c = decl.indexOf(':')
                        if (c > 0) take(decl.substring(0, c).trim(), decl.substring(c + 1).trim())
                    }
                } else {
                    take(n, xml.value(i))
                }
            }
            val last = g.offsets!!.lastOrNull() ?: 0f
            g.offsets!!.add(maxOf(offset, last))
            val a = Math.round(((color ushr 24) and 0xFF) * opacity)
            g.colors!!.add((a shl 24) or (color and 0xFFFFFF))
        }

        private fun filter(id: String, a: Map<String, String>, prims: List<SvgFe>, inh: Inherited): SvgFilter {
            val units = if (a["filterUnits"] == "userSpaceOnUse") SvgUnits.USER_SPACE else SvgUnits.BOUNDING_BOX
            if (a["primitiveUnits"] == "objectBoundingBox") throw SvgFormatException("primitiveUnits=objectBoundingBox is not written by the engine")
            val pct = BooleanArray(1)
            fun len(key: String, default: Float): Float {
                val v = a[key] ?: return default
                val x = SvgValues.parseLength(v, pct)
                if (pct[0] && units == SvgUnits.USER_SPACE) throw SvgFormatException("percentages in a user-space filter region")
                return x
            }
            val region = if (units == SvgUnits.BOUNDING_BOX) {
                floatArrayOf(len("x", -0.1f), len("y", -0.1f), len("width", 1.2f), len("height", 1.2f))
            } else {
                floatArrayOf(len("x", 0f), len("y", 0f), len("width", Float.NaN), len("height", Float.NaN))
            }
            if (region[2].isNaN() || region[3].isNaN()) throw SvgFormatException("a user-space filter needs its size")
            return SvgFilter(id, units, region, prims, inh.linearRgb)
        }

        private fun primitive(name: String, a: Map<String, String>, inh: Inherited): SvgFe {
            for (k in listOf("x", "y", "width", "height")) if (a.containsKey(k)) throw SvgFormatException("primitive subregions are not written by the engine")
            fun f(k: String, d: Float) = a[k]?.let { SvgValues.parseLength(it) } ?: d
            fun channel(k: String) = when (a[k] ?: "A") {
                "R" -> 0
                "G" -> 1
                "B" -> 2
                else -> 3
            }
            val fe: SvgFe = when (name) {
                "feTurbulence" -> {
                    val bf = SvgValues.parseNumbers(a["baseFrequency"] ?: "0")
                    val fx = if (bf.isEmpty()) 0f else bf[0]
                    val fy = if (bf.size > 1) bf[1] else fx
                    SvgFe.Turbulence(
                        fx, fy, (a["numOctaves"] ?: "1").toFloat().toInt(), f("seed", 0f),
                        a["type"] == "fractalNoise", a["stitchTiles"] == "stitch",
                    )
                }
                "feDisplacementMap" -> SvgFe.DisplacementMap(f("scale", 0f), channel("xChannelSelector"), channel("yChannelSelector"))
                "feColorMatrix" -> SvgFe.ColorMatrix(colorMatrix(a["type"] ?: "matrix", a["values"]))
                "feComposite" -> SvgFe.Composite(
                    when (a["operator"] ?: "over") {
                        "over" -> SvgFe.Composite.Operator.OVER
                        "in" -> SvgFe.Composite.Operator.IN
                        "out" -> SvgFe.Composite.Operator.OUT
                        "atop" -> SvgFe.Composite.Operator.ATOP
                        "xor" -> SvgFe.Composite.Operator.XOR
                        "arithmetic" -> SvgFe.Composite.Operator.ARITHMETIC
                        else -> throw SvgFormatException("feComposite operator '${a["operator"]}'")
                    },
                    f("k1", 0f), f("k2", 0f), f("k3", 0f), f("k4", 0f),
                )
                "feGaussianBlur" -> {
                    val d = SvgValues.parseNumbers(a["stdDeviation"] ?: "0")
                    val dx = if (d.isEmpty()) 0f else d[0]
                    SvgFe.GaussianBlur(dx, if (d.size > 1) d[1] else dx)
                }
                "feOffset" -> SvgFe.Offset(f("dx", 0f), f("dy", 0f))
                "feFlood" -> {
                    val c = SvgValues.parseColor(a["flood-color"] ?: "black") ?: 0
                    val o = f("flood-opacity", 1f).coerceIn(0f, 1f)
                    SvgFe.Flood((Math.round(((c ushr 24) and 0xFF) * o) shl 24) or (c and 0xFFFFFF))
                }
                else -> throw SvgFormatException("<$name> is not written by the engine")
            }
            fe.input = a["in"]
            fe.input2 = a["in2"]
            fe.result = a["result"]
            fe.linearRgb = a["color-interpolation-filters"]?.let { it != "sRGB" } ?: inh.linearRgb
            return fe
        }

        private fun colorMatrix(type: String, values: String?): FloatArray = when (type) {
            "matrix" -> {
                val v = SvgValues.parseNumbers(values ?: "")
                if (values == null) identity() else if (v.size != 20) throw SvgFormatException("feColorMatrix needs 20 values") else v
            }
            "saturate" -> {
                val x = values?.let { SvgValues.parseNumbers(it).firstOrNull() } ?: 1f
                floatArrayOf(
                    0.213f + 0.787f * x, 0.715f - 0.715f * x, 0.072f - 0.072f * x, 0f, 0f,
                    0.213f - 0.213f * x, 0.715f + 0.285f * x, 0.072f - 0.072f * x, 0f, 0f,
                    0.213f - 0.213f * x, 0.715f - 0.715f * x, 0.072f + 0.928f * x, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
            "hueRotate" -> {
                val r = Math.toRadians((values?.let { SvgValues.parseNumbers(it).firstOrNull() } ?: 0f).toDouble())
                val c = Math.cos(r).toFloat()
                val sn = Math.sin(r).toFloat()
                floatArrayOf(
                    0.213f + c * 0.787f - sn * 0.213f, 0.715f - c * 0.715f - sn * 0.715f, 0.072f - c * 0.072f + sn * 0.928f, 0f, 0f,
                    0.213f - c * 0.213f + sn * 0.143f, 0.715f + c * 0.285f + sn * 0.140f, 0.072f - c * 0.072f - sn * 0.283f, 0f, 0f,
                    0.213f - c * 0.213f - sn * 0.787f, 0.715f - c * 0.715f + sn * 0.715f, 0.072f + c * 0.928f + sn * 0.072f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            }
            "luminanceToAlpha" -> floatArrayOf(
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.2125f, 0.7154f, 0.0721f, 0f, 0f,
            )
            else -> throw SvgFormatException("feColorMatrix type '$type'")
        }

        private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

        /** The attributes of gradient [id] after following its `href` chain, nearest first. */
        private fun chain(id: String, attrsOf: (String) -> Map<String, String>?): List<Map<String, String>> {
            val out = ArrayList<Map<String, String>>()
            var cur: String? = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val a = attrsOf(cur) ?: break
                out.add(a)
                cur = a["href"]?.trim()?.removePrefix("#")
            }
            return out
        }

        private fun resolveGradient(id: String): SvgGradient? {
            val raw = gradients[id] ?: return null
            val chain = chain(id) { gradients[it]?.attrs }
            fun attr(k: String) = chain.firstNotNullOfOrNull { it[k] }
            var stops: RawGradient? = null
            var cur: String? = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val g = gradients[cur] ?: break
                if (g.offsets.isNotEmpty()) {
                    stops = g
                    break
                }
                cur = g.attrs["href"]?.trim()?.removePrefix("#")
            }
            val units = if (attr("gradientUnits") == "userSpaceOnUse") SvgUnits.USER_SPACE else SvgUnits.BOUNDING_BOX
            fun c(k: String, d: Float) = attr(k)?.let { SvgValues.parseLength(it) } ?: d
            val coords = if (raw.radial) {
                val cx = c("cx", 0.5f)
                val cy = c("cy", 0.5f)
                floatArrayOf(cx, cy, c("r", 0.5f), c("fx", cx), c("fy", cy))
            } else {
                floatArrayOf(c("x1", 0f), c("y1", 0f), c("x2", 1f), c("y2", 0f))
            }
            val spread = when (attr("spreadMethod")) {
                "reflect" -> SvgGradient.Spread.REFLECT
                "repeat" -> SvgGradient.Spread.REPEAT
                else -> SvgGradient.Spread.PAD
            }
            return SvgGradient(
                id, raw.radial, coords, units, attr("gradientTransform")?.let { SvgValues.parseTransform(it) }, spread,
                stops?.offsets ?: FloatArray(0), stops?.colors ?: IntArray(0),
            )
        }

        private fun resolvePattern(id: String): SvgPattern? {
            val chain = chain(id) { patterns[it]?.attrs }
            if (chain.isEmpty()) return null
            fun attr(k: String) = chain.firstNotNullOfOrNull { it[k] }
            var content: SvgGroup? = null
            var cur: String? = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val p = patterns[cur] ?: break
                if (p.content != null) {
                    content = p.content
                    break
                }
                cur = p.attrs["href"]?.trim()?.removePrefix("#")
            }
            if (attr("viewBox") != null) throw SvgFormatException("pattern viewBox is not written by the engine")
            val units = if (attr("patternUnits") == "userSpaceOnUse") SvgUnits.USER_SPACE else SvgUnits.BOUNDING_BOX
            val contentUnits = if (attr("patternContentUnits") == "objectBoundingBox") SvgUnits.BOUNDING_BOX else SvgUnits.USER_SPACE
            fun c(k: String) = attr(k)?.let { SvgValues.parseLength(it) } ?: 0f
            return SvgPattern(
                id, c("x"), c("y"), c("width"), c("height"), units, contentUnits,
                attr("patternTransform")?.let { SvgValues.parseTransform(it) }, content ?: SvgGroup(emptyList()),
            )
        }

        /** XML whitespace handling of SVG text without `xml:space`: drop newlines, tabs to spaces, trim and collapse. */
        private fun collapse(t: String): String {
            val sb = StringBuilder(t.length)
            var space = false
            for (ch in t) {
                if (ch == '\n' || ch == '\r') continue
                val c = if (ch == '\t') ' ' else ch
                if (c == ' ') {
                    if (!space && sb.isNotEmpty()) sb.append(' ')
                    space = true
                } else {
                    sb.append(c)
                    space = false
                }
            }
            return sb.toString().trimEnd()
        }
    }

    private val EMPTY = FloatArray(0)
}
