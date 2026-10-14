package app.atlas.ui.kit

import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.LineCap
import app.atlas.places.LineJoin
import app.atlas.places.ViewBox
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Main board's own symbols (`canvas/project/Main.dc.html`), path for path: the weather pill, the moments and the
 * needs, the control rail, the category row, the search bar and the tab bar. Ids are `main.<what>`; draw them with
 * [Symbol] and the board's stroke width as `weight = width / 2` (the board strokes them 1.8 to 2.4 units on their 24
 * grid). Circles and rects become path data here, and the A-near-B ring's dashes become arcs.
 */
object MainSymbols {
    private val icons: Map<String, Icon> = listOf(
        // The weather pill: the sun (#B8461A) and "works offline here" (#178A4E).
        icon("main.sun", cap = LineCap.ROUND, join = LineJoin.MITER, svgCircle(12f, 12f, 4f), "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"),
        icon("main.cloud-check", cap = LineCap.ROUND, join = LineJoin.ROUND, "M7 18a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 9.5a4 4 0 0 1-.5 8.5", "M9 14.5l2.2 2.2 4-4.2"),
        // The moments pill's sparkles and the needs trio's drop and bread, filled.
        filled("main.sparkles", "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"),
        filled("main.drop", "M12 2.5s7 7.5 7 12.5a7 7 0 0 1-14 0c0-5 7-12.5 7-12.5z"),
        filled("main.bread", "M3 15c0-5 5-8 10-8s10 3 10 8c0 2-2 3-10 3S3 17 3 15z", box = ViewBox(0f, 0f, 26f, 24f)),
        // The control rail: 3D, layers, my location, the globe.
        icon("main.cube", cap = LineCap.BUTT, join = LineJoin.ROUND, "M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z", "M12 12l8-4.5M12 12v9M12 12L4 7.5"),
        icon("main.layers", cap = LineCap.BUTT, join = LineJoin.ROUND, "M12 3l9 5-9 5-9-5 9-5z", "M3 13l9 5 9-5"),
        icon("main.locate", cap = LineCap.BUTT, join = LineJoin.ROUND, "M4 11l16-7-7 16-2-7-7-2z"),
        Icon("main.locate.on", ViewBox(0f, 0f, 24f, 24f), listOf(IconPath("M4 11l16-7-7 16-2-7-7-2z", stroke = true, fill = true, cap = LineCap.BUTT, join = LineJoin.ROUND))),
        icon("main.globe", cap = LineCap.ROUND, join = LineJoin.MITER, svgCircle(12f, 12f, 9f), "M3.5 9h17M3.5 15h17M12 3c-3 3.5-3 14.5 0 18M12 3c3 3.5 3 14.5 0 18"),
        // The category row, in the board's order.
        icon("main.eat", cap = LineCap.ROUND, join = LineJoin.MITER, "M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10", "M17 21V3c-2 1-3 3.5-3 7h3"),
        icon("main.groceries", cap = LineCap.ROUND, join = LineJoin.ROUND, "M5 9h14l-1.5 11h-11z", "M9 9l3-5 3 5M9 13v4M12 13v4M15 13v4"),
        icon("main.shops", cap = LineCap.ROUND, join = LineJoin.ROUND, "M5 8h14l-1 12H6L5 8z", "M9 8V6.5a3 3 0 0 1 6 0V8"),
        icon("main.stay", cap = LineCap.ROUND, join = LineJoin.ROUND, "M3 19V6M3 15h18v4M21 15v-2.5A3.5 3.5 0 0 0 17.5 9H11v6", svgCircle(7f, 11.5f, 1.8f)),
        icon("main.charge", cap = LineCap.BUTT, join = LineJoin.ROUND, "M13 3L5 13h6l-1 8 8-10h-6l1-8z"),
        icon("main.outdoors", cap = LineCap.BUTT, join = LineJoin.ROUND, "M3 19l6.5-11 4 6.5 2.5-3.5 5 8H3z"),
        icon("main.events", cap = LineCap.ROUND, join = LineJoin.MITER, "M9 18V5l11-2v13", svgCircle(6.5f, 18f, 2.5f), svgCircle(17.5f, 16f, 2.5f)),
        icon("main.water", cap = LineCap.BUTT, join = LineJoin.ROUND, "M3 16.5l1.8 3.5h14.4l1.8-3.5H3z", "M12 3v13.5M12 4.5l6 10H12M12 7l-4.5 7.5H12"),
        // The search bar: the lens, A near B (a dashed ring, a ring, the dot between) and the mic.
        icon("main.search", cap = LineCap.ROUND, join = LineJoin.MITER, svgCircle(11f, 11f, 7f), "M20 20l-3.6-3.6"),
        Icon(
            "main.near",
            ViewBox(0f, 0f, 24f, 24f),
            listOf(
                IconPath(svgDashedCircle(8f, 8f, 4.5f, 2.2f, 2f), stroke = true, fill = false, cap = LineCap.ROUND, join = LineJoin.MITER),
                IconPath(svgCircle(16f, 16f, 4.5f), stroke = true, fill = false, cap = LineCap.ROUND, join = LineJoin.MITER),
                IconPath(svgCircle(12.2f, 12.2f, 1.3f), stroke = false, fill = true),
            ),
        ),
        icon("main.mic", cap = LineCap.ROUND, join = LineJoin.MITER, svgRect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0 0 14 0M12 18v3"),
        // The tab bar: Map, Go, Soon (with its filled dot), Local, Mine.
        icon("main.tab-map", cap = LineCap.ROUND, join = LineJoin.ROUND, "M3 6.5l6-2.5 6 2.5 6-2.5v13.5l-6 2.5-6-2.5-6 2.5z", "M9 4v13.5M15 6.5V20"),
        icon("main.tab-go", cap = LineCap.ROUND, join = LineJoin.ROUND, "M6 21v-8a4 4 0 0 1 4-4h9", "M15 5l4 4-4 4"),
        Icon(
            "main.tab-soon",
            ViewBox(0f, 0f, 24f, 24f),
            listOf(
                IconPath(svgRect(3.5f, 5f, 17f, 15.5f, 2.5f), stroke = true, fill = false),
                IconPath("M3.5 10h17M8 3v4M16 3v4", stroke = true, fill = false),
                IconPath(svgCircle(12f, 15f, 1.4f), stroke = false, fill = true),
            ),
        ),
        icon("main.tab-local", cap = LineCap.ROUND, join = LineJoin.ROUND, "M12 6.5C10 5 7 4.5 3.5 5v13c3.5-.5 6.5 0 8.5 1.5 2-1.5 5-2 8.5-1.5V5C17 4.5 14 5 12 6.5z", "M12 6.5v13"),
        icon("main.tab-mine", cap = LineCap.ROUND, join = LineJoin.ROUND, svgCircle(12f, 8.5f, 3.8f), "M4.5 20.5a7.5 7.5 0 0 1 15 0"),
    ).associateBy { it.id }

    fun icon(id: String): Icon? = icons[id]

    fun has(id: String): Boolean = id in icons

    val ids: Set<String> get() = icons.keys

    /** The board's stroke widths, in its 24 units, for [Symbol]'s `weight` (which counts 2 units as 1). */
    object Width {
        const val PILL = 1.9f / 2f
        const val RAIL = 1.8f / 2f
        const val TAB = 1.8f / 2f
        const val TAB_ON = 2.1f / 2f
    }

    private fun icon(id: String, cap: LineCap, join: LineJoin, vararg d: String) =
        Icon(id, ViewBox(0f, 0f, 24f, 24f), d.map { IconPath(it, stroke = true, fill = false, cap = cap, join = join) })

    private fun filled(id: String, vararg d: String, box: ViewBox = ViewBox(0f, 0f, 24f, 24f)) =
        Icon(id, box, d.map { IconPath(it, stroke = false, fill = true) })
}

private fun f(v: Float): String = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')

/** SVG's `<circle>` as path data, starting where SVG starts it (3 o'clock) and running clockwise. */
internal fun svgCircle(cx: Float, cy: Float, r: Float): String =
    "M${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx + r)} ${f(cy)}z"

/** SVG's `<rect>` with round corners [rx] as path data. */
internal fun svgRect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    val r = minOf(rx, w / 2f, h / 2f)
    return "M${f(x + r)} ${f(y)}H${f(x + w - r)}A${f(r)} ${f(r)} 0 0 1 ${f(x + w)} ${f(y + r)}V${f(y + h - r)}" +
        "A${f(r)} ${f(r)} 0 0 1 ${f(x + w - r)} ${f(y + h)}H${f(x + r)}A${f(r)} ${f(r)} 0 0 1 ${f(x)} ${f(y + h - r)}" +
        "V${f(y + r)}A${f(r)} ${f(r)} 0 0 1 ${f(x + r)} ${f(y)}z"
}

/**
 * A `<circle>` stroked with `stroke-dasharray="[on] [off]"` as the arcs a browser draws: from 3 o'clock, clockwise,
 * the pattern restarting at the start and the last dash cut where the circle closes.
 */
internal fun svgDashedCircle(cx: Float, cy: Float, r: Float, on: Float, off: Float): String {
    val length = (2.0 * Math.PI * r).toFloat()
    val sb = StringBuilder()
    var s = 0f
    while (s < length) {
        val e = minOf(s + on, length)
        val a0 = s / r
        val a1 = e / r
        sb.append("M").append(f(cx + r * cos(a0))).append(' ').append(f(cy + r * sin(a0)))
        sb.append("A").append(f(r)).append(' ').append(f(r)).append(" 0 0 1 ").append(f(cx + r * cos(a1))).append(' ').append(f(cy + r * sin(a1)))
        s += on + off
    }
    return sb.toString()
}
