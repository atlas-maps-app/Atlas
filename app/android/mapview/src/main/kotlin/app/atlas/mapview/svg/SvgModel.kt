package app.atlas.mapview.svg

/**
 * The display list of one SVG document as the engine writes it: a tree of groups, shapes and text, every shape's
 * presentation already resolved into a shared [SvgStyle], and every path's geometry packed into [path] arrays.
 * Plain Kotlin, so it parses and is measured on the JVM; [SvgCanvas] draws it on Android.
 */
class SvgDocument(
    /** The document's size in CSS pixels (the `width` and `height` attributes, or the view box's size). */
    val width: Float,
    val height: Float,
    /** The view box: min x, min y, width, height. */
    val viewBox: FloatArray,
    val root: SvgGroup,
    /** Every path's verbs and coordinates. */
    val path: SvgPathData,
    val gradients: Map<String, SvgGradient>,
    val patterns: Map<String, SvgPattern>,
    val filters: Map<String, SvgFilter>,
    val clips: Map<String, SvgClip>,
    /** The distinct shape styles, in order of first use. */
    val styles: List<SvgStyle>,
) {
    /** The number of shapes and text runs in the drawn tree. */
    val drawables: Int by lazy { count(root) }

    private fun count(g: SvgGroup): Int = g.children.sumOf { if (it is SvgGroup) count(it) else 1 }
}

/** Packed path geometry: [verbs] (see [SvgPathData.MOVE]) and [coords] (x, y pairs), shared by all paths of a document. */
class SvgPathData(val verbs: ByteArray, val coords: FloatArray) {
    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CLOSE: Byte = 2
    }
}

/** Something that blends with what is below it. The values mirror CSS `mix-blend-mode`. */
enum class SvgBlend { NORMAL, MULTIPLY, SCREEN, OVERLAY, SOFT_LIGHT, DARKEN, LIGHTEN }

/** A node of the drawn tree, with the properties every SVG element shares (none of them inherit). */
sealed class SvgNode {
    /** The affine transform `a b c d e f`, or null for identity. */
    var transform: FloatArray? = null
    var opacity: Float = 1f
    var filter: SvgFilter? = null
    var clip: SvgClip? = null
    var blend: SvgBlend = SvgBlend.NORMAL

    /** True when the node must be drawn into a layer of its own before it is composited. */
    val needsLayer: Boolean get() = filter != null || blend != SvgBlend.NORMAL || opacity < 1f
}

/** `<g>` and the document's root. */
class SvgGroup(val children: List<SvgNode>) : SvgNode()

/** `<path>`, `<rect>`, `<circle>` and `<ellipse>`. */
class SvgShape(
    val kind: Kind,
    /**
     * For [Kind.PATH], the range of verbs and the first coordinate in [SvgDocument.path]: `verbStart`, `verbEnd`,
     * `coordStart`. For [Kind.RECT], `x y w h rx ry`; for [Kind.CIRCLE], `cx cy r`; for [Kind.ELLIPSE], `cx cy rx ry`.
     */
    val geometry: FloatArray,
    val verbStart: Int,
    val verbEnd: Int,
    val coordStart: Int,
    val style: SvgStyle,
) : SvgNode() {
    enum class Kind { PATH, RECT, CIRCLE, ELLIPSE }
}

/** `<text>` with one run of characters (the engine never writes `tspan`). */
class SvgText(
    val text: String,
    val x: Float,
    val y: Float,
    val font: SvgFont,
    val style: SvgStyle,
) : SvgNode()

/** The font of a text run. [family] is the CSS list as written, for example `Georgia, serif`. */
data class SvgFont(
    val family: String,
    val size: Float,
    val weight: Int,
    val italic: Boolean,
    /** Letter spacing in user units. */
    val letterSpacing: Float,
    val anchor: Anchor,
) {
    enum class Anchor { START, MIDDLE, END }
}

/** A fill or stroke paint. */
sealed class SvgPaint {
    data object None : SvgPaint()

    /** A colour as ARGB, its alpha already the colour's own (the property's opacity is applied separately). */
    data class Color(val argb: Int) : SvgPaint()

    /** `url(#id)`: a gradient or a pattern of the document, with a fallback when the id is missing. */
    data class Ref(val id: String, val fallback: SvgPaint) : SvgPaint()
}

/** The resolved, inherited presentation of a shape or text run. Shared between all nodes that look alike. */
data class SvgStyle(
    val fill: SvgPaint,
    val fillOpacity: Float,
    val evenOdd: Boolean,
    val stroke: SvgPaint,
    val strokeOpacity: Float,
    val strokeWidth: Float,
    val cap: Cap,
    val join: Join,
    val miterLimit: Float,
    /** The dash lengths, already doubled when the list was odd, or null for a solid line. */
    val dashes: List<Float>?,
    val dashOffset: Float,
) {
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    val hasFill: Boolean get() = fill != SvgPaint.None
    val hasStroke: Boolean get() = stroke != SvgPaint.None && strokeWidth > 0f
}

/** Whether a gradient's, pattern's, filter's or clip's coordinates are user units or fractions of the bounding box. */
enum class SvgUnits { USER_SPACE, BOUNDING_BOX }

/** `<linearGradient>` or `<radialGradient>`, with its `href` chain already followed. */
class SvgGradient(
    val id: String,
    val radial: Boolean,
    /** Linear: x1 y1 x2 y2. Radial: cx cy r fx fy. */
    val coords: FloatArray,
    val units: SvgUnits,
    val transform: FloatArray?,
    val spread: Spread,
    val offsets: FloatArray,
    /** Each stop's colour with its `stop-opacity` folded into the alpha. */
    val colors: IntArray,
) {
    enum class Spread { PAD, REFLECT, REPEAT }
}

/** `<pattern>`, with its `href` chain already followed. */
class SvgPattern(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val units: SvgUnits,
    val contentUnits: SvgUnits,
    val transform: FloatArray?,
    val content: SvgGroup,
)

/** `<clipPath>`: the union of its children's geometry, itself possibly clipped by another clip. */
class SvgClip(
    val id: String,
    val units: SvgUnits,
    val children: List<SvgNode>,
) {
    /** The clip path's own `clip-path`, intersected with it. */
    var clip: SvgClip? = null
}

/** `<filter>`: its region and its chain of primitives. */
class SvgFilter(
    val id: String,
    val units: SvgUnits,
    /** The region: x y width height, in user units or (for [SvgUnits.BOUNDING_BOX]) fractions of the bounding box. */
    val region: FloatArray,
    val primitives: List<SvgFe>,
    /** True when the primitives use linear RGB (`color-interpolation-filters`, linearRGB by default). */
    val linearRgb: Boolean,
)

/** One filter primitive. [input] and [input2] name a `result` or a standard input; null means the previous result. */
sealed class SvgFe {
    var input: String? = null
    var input2: String? = null
    var result: String? = null
    var linearRgb: Boolean = true

    class Turbulence(
        val baseX: Float,
        val baseY: Float,
        val octaves: Int,
        val seed: Float,
        val fractal: Boolean,
        val stitch: Boolean,
    ) : SvgFe()

    class DisplacementMap(val scale: Float, val xChannel: Int, val yChannel: Int) : SvgFe()

    /** Any `feColorMatrix`, as the 4 × 5 matrix (saturate and hueRotate already expanded). */
    class ColorMatrix(val matrix: FloatArray) : SvgFe()

    class Composite(val operator: Operator, val k1: Float, val k2: Float, val k3: Float, val k4: Float) : SvgFe() {
        enum class Operator { OVER, IN, OUT, ATOP, XOR, ARITHMETIC }
    }

    class GaussianBlur(val deviationX: Float, val deviationY: Float) : SvgFe()

    class Offset(val dx: Float, val dy: Float) : SvgFe()

    class Flood(val argb: Int) : SvgFe()

    class Merge(val inputs: List<String?>) : SvgFe()

    companion object {
        const val SOURCE_GRAPHIC = "SourceGraphic"
        const val SOURCE_ALPHA = "SourceAlpha"
    }
}

/** Thrown for SVG the engine never writes, so a new feature fails loudly instead of drawing wrong. */
class SvgFormatException(message: String) : IllegalArgumentException(message)
