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
 */
data class IconPath(val d: String, val stroke: Boolean, val fill: Boolean, val evenOdd: Boolean = false) {
    val commands: PathCommands by lazy { SvgPath.parse(d) }
}

/** A vector symbol: a map symbol (Maki, Temaki, Pinhead), a UI symbol (Lucide style) or a brand glyph. */
data class Icon(val id: String, val viewBox: ViewBox, val paths: List<IconPath>) {
    /** Stroke width in viewBox units for stroked paths: the UI set is drawn 2 wide on its 24 grid. */
    val strokeWidth: Float get() = viewBox.width / 12f

    /** Replays every path into [sink], scaled so the viewBox fills [size] × [size] with its origin at ([x], [y]). */
    fun replay(sink: PathSink, size: Float, x: Float = 0f, y: Float = 0f) {
        val scale = size / maxOf(viewBox.width, viewBox.height)
        for (p in paths) p.commands.replay(sink, scale, x - viewBox.x * scale, y - viewBox.y * scale)
    }
}
