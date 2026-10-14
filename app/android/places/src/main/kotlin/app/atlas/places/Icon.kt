package app.atlas.places

/** An SVG viewBox: the icon's own coordinate space. */
data class ViewBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    companion object {
        fun parse(s: String): ViewBox {
            val p = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            require(p.size == 4) { "bad viewBox '$s'" }
            return ViewBox(p[0], p[1], p[2], p[3])
        }
    }
}

/**
 * One path of an icon. [stroke] paths are drawn as lines (Atlas's UI set: 2 units wide, round caps and joins);
 * [fill] paths are filled, with the even-odd rule when [evenOdd]. [commands] is parsed once, on first use.
 *
 * The boards' own symbols carry their own look per path: [colour] (ARGB; null means the caller's tint),
 * [strokeWidth] in viewBox units (null means the icon's default), [opacity], [cap] and [join], a [dash] pattern (on,
 * off, … in viewBox units), and a [gradient] (stops as offset 0..1 to ARGB) that wins over [colour] where the renderer
 * can draw it: linear from ([gx1], [gy1]) to ([gx2], [gy2]) in viewBox units, or, when [radial], centred on
 * ([gx1], [gy1]) with the distance to ([gx2], [gy2]) as its radius.
 */
data class IconPath(
    val d: String,
    val stroke: Boolean,
    val fill: Boolean,
    val evenOdd: Boolean = false,
    val colour: Long? = null,
    val strokeWidth: Float? = null,
    val opacity: Float = 1f,
    val cap: LineCap = LineCap.ROUND,
    val join: LineJoin = LineJoin.ROUND,
    val gradient: List<Pair<Float, Long>>? = null,
    val gx1: Float = 0f, val gy1: Float = 0f, val gx2: Float = 0f, val gy2: Float = 0f,
    val dash: List<Float>? = null,
    val radial: Boolean = false,
) {
    val commands: PathCommands by lazy { SvgPath.parse(d) }
}

enum class LineCap { BUTT, ROUND, SQUARE }
enum class LineJoin { MITER, ROUND, BEVEL }

/** Where a line of [IconText] sits on its x: its start, its middle or its end, as SVG's text-anchor. */
enum class TextAnchor { START, MIDDLE, END }

/**
 * A short text that is part of a board symbol (a price tag's "50", a sign's "OPEN"), drawn after the paths: its
 * baseline starts, centres or ends at ([x], [y]) by [anchor]; [size] and [letterSpacing] are in viewBox units,
 * [weight] is CSS's 100–900, [family] "sans", "serif" or "mono", [colour] ARGB or null for the tint, [rotation] in
 * degrees about ([x], [y]).
 */
data class IconText(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val weight: Int = 400,
    val family: String = "sans",
    val anchor: TextAnchor = TextAnchor.START,
    val colour: Long? = null,
    val opacity: Float = 1f,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val rotation: Float = 0f,
)

/**
 * A drop shadow under a whole symbol (a pin's shadow, a neon sign's glow): the symbol's shape moved by ([dx], [dy]),
 * blurred by [blur] (CSS's blur radius) and filled with [colour] (ARGB), all lengths in viewBox units.
 */
data class IconShadow(val dx: Float, val dy: Float, val blur: Float, val colour: Long)

/**
 * A vector symbol: a board symbol (Atlas's own, taken from the boards), a map symbol (Maki, Temaki, Pinhead), a UI
 * symbol (Lucide style) or a brand glyph. [coloured] icons are drawn in their own colours and never tinted (their
 * paths without a colour still take the tint). [texts] are drawn over the paths, [shadows] under them.
 */
data class Icon(
    val id: String,
    val viewBox: ViewBox,
    val paths: List<IconPath>,
    val coloured: Boolean = paths.any { it.colour != null || it.gradient != null },
    val texts: List<IconText> = emptyList(),
    val shadows: List<IconShadow> = emptyList(),
) {
    /** Stroke width in viewBox units for stroked paths: the UI set is drawn 2 wide on its 24 grid. */
    val strokeWidth: Float get() = viewBox.width / 12f

    /** Replays every path into [sink], scaled so the viewBox fills [size] × [size] with its origin at ([x], [y]). */
    fun replay(sink: PathSink, size: Float, x: Float = 0f, y: Float = 0f) {
        val scale = size / maxOf(viewBox.width, viewBox.height)
        for (p in paths) p.commands.replay(sink, scale, x - viewBox.x * scale, y - viewBox.y * scale)
    }
}
