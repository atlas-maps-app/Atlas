package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.IconText
import app.atlas.places.LineCap
import app.atlas.places.LineJoin
import app.atlas.places.PathSink
import app.atlas.places.Symbols
import app.atlas.places.TextAnchor
import java.util.concurrent.ConcurrentHashMap

/**
 * One path of an icon with its own look, as the boards draw their symbols: filled and/or stroked, in its own [colour]
 * (null: the caller's tint) at [opacity], stroked [strokeWidth] viewBox units wide with its caps, joins and [dash], or
 * filled with a [shader] (a gradient in viewBox units) when it has one.
 */
class IconPart(
    val path: Path,
    val fill: Boolean,
    val stroke: Boolean,
    val colour: Int?,
    val strokeWidth: Float,
    val opacity: Float,
    val cap: Paint.Cap,
    val join: Paint.Join,
    val shader: Shader?,
    val dash: PathEffect?,
)

/**
 * An icon as Android paths in its viewBox units, ready to draw at any size. [fills] and [strokes] hold its paths by
 * kind (for callers that draw them their own way); [parts] holds every path in order with its own look, and [texts]
 * the words a board symbol carries.
 */
class IconShape(
    val icon: Icon,
    val fills: Array<Path>,
    val strokes: Array<Path>,
    val parts: List<IconPart> = emptyList(),
    val texts: List<IconText> = emptyList(),
) {
    private val vb = icon.viewBox
    private val extent = maxOf(vb.width, vb.height)

    /**
     * Draws the icon [size] pixels across, centred on ([cx], [cy]): each path in its own colour, or in [paint]'s colour
     * (the tint) where it has none, at the paint's alpha. The paint is left as it was given, filling.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val k = size / extent
        canvas.save()
        canvas.translate(cx - vb.width * k / 2, cy - vb.height * k / 2)
        canvas.scale(k, k)
        canvas.translate(-vb.x, -vb.y)
        if (parts.isEmpty()) {
            if (fills.isNotEmpty()) {
                paint.style = Paint.Style.FILL
                for (p in fills) canvas.drawPath(p, paint)
            }
            if (strokes.isNotEmpty()) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = icon.strokeWidth
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                for (p in strokes) canvas.drawPath(p, paint)
                paint.style = Paint.Style.FILL
            }
        } else {
            val tint = paint.color
            val alpha = Color.alpha(tint) / 255f
            for (part in parts) {
                paint.color = part.colour ?: tint
                paint.alpha = (Color.alpha(paint.color) * part.opacity * (if (part.colour != null) alpha else 1f)).toInt().coerceIn(0, 255)
                if (part.fill) {
                    paint.style = Paint.Style.FILL
                    paint.shader = part.shader
                    canvas.drawPath(part.path, paint)
                    paint.shader = null
                }
                if (part.stroke) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = part.strokeWidth
                    paint.strokeCap = part.cap
                    paint.strokeJoin = part.join
                    paint.pathEffect = part.dash
                    canvas.drawPath(part.path, paint)
                    paint.pathEffect = null
                }
            }
            paint.color = tint
            paint.style = Paint.Style.FILL
        }
        if (texts.isNotEmpty()) drawTexts(canvas, paint)
        canvas.restore()
    }

    private fun drawTexts(canvas: Canvas, paint: Paint) {
        val tint = paint.color
        val face = paint.typeface
        val align = paint.textAlign
        val textSize = paint.textSize
        val spacing = paint.letterSpacing
        paint.style = Paint.Style.FILL
        for (t in texts) {
            paint.typeface = typeface(t.family, t.weight, t.italic)
            paint.textSize = t.size
            paint.letterSpacing = if (t.size > 0f) t.letterSpacing / t.size else 0f
            paint.textAlign = when (t.anchor) {
                TextAnchor.START -> Paint.Align.LEFT
                TextAnchor.MIDDLE -> Paint.Align.CENTER
                TextAnchor.END -> Paint.Align.RIGHT
            }
            paint.color = t.colour?.toInt() ?: tint
            paint.alpha = (Color.alpha(paint.color) * t.opacity).toInt().coerceIn(0, 255)
            if (t.rotation != 0f) {
                canvas.save()
                canvas.rotate(t.rotation, t.x, t.y)
            }
            canvas.drawText(t.text, t.x, t.y, paint)
            if (t.rotation != 0f) canvas.restore()
        }
        paint.color = tint
        paint.typeface = face
        paint.textAlign = align
        paint.textSize = textSize
        paint.letterSpacing = spacing
    }

    companion object {
        private val faces = ConcurrentHashMap<String, Typeface>()

        /** The typeface for a board symbol's text: "serif", "mono" or sans, at a CSS weight, upright or italic. */
        fun typeface(family: String, weight: Int, italic: Boolean): Typeface = faces.getOrPut("$family|$weight|$italic") {
            val base = when (family) {
                "serif" -> Typeface.SERIF
                "mono" -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            }
            Typeface.create(base, weight.coerceIn(1, 1000), italic)
        }
    }
}

/** A brand's mark for the map: its glyph (or null: then its initial) and its sign colours. */
class BrandMark(val name: String, val shape: IconShape?, val colour: Int?, val textColour: Int?)

/**
 * Symbols from `:places` as cached Android paths. Ids are full ("maki-cafe", "ui-walk", "logo-albertheijn") or short
 * ("cafe", "charging_station"), which try the map sets and then the UI set. Thread-safe: warm the caches off the main
 * thread with [warm].
 */
object Icons {
    private val shapes = ConcurrentHashMap<String, Any>()
    private val ids = ConcurrentHashMap<String, Any>()
    private val brands = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    private val PREFIXES = arrayOf("maki-", "temaki-", "pinhead-", "ui-")

    /** The full id of a symbol [name], or null when no set has it. */
    fun resolveId(name: String): String? {
        val r = ids.getOrPut(name) {
            when {
                name.isEmpty() -> NONE
                name.contains('-') && Symbols.has(name) -> name
                else -> PREFIXES.firstNotNullOfOrNull { p -> (p + name).takeIf { Symbols.has(it) } }
                    ?: PREFIXES.firstNotNullOfOrNull { p -> (p + name.replace('_', '-')).takeIf { Symbols.has(it) } }
                    ?: NONE
            }
        }
        return r as? String
    }

    /** The shape of symbol [name], or null. */
    fun shape(name: String): IconShape? {
        val id = resolveId(name) ?: return null
        val r = shapes.getOrPut(id) { Symbols.icon(id)?.let { build(it) } ?: NONE }
        return r as? IconShape
    }

    /** The mark of brand [id]: an NSI id ("albertheijn-1cf55d"), a glyph slug ("albertheijn") or a "logo-" id. */
    fun brand(id: String): BrandMark? {
        val r = brands.getOrPut(id) {
            val b: Brand? = Brands.byId(id)
            when {
                b != null -> BrandMark(b.brand, b.logo?.let { build(it) }, b.colour, b.textColour)
                id.startsWith("logo-") -> Symbols.icon(id)?.let { BrandMark(id.removePrefix("logo-"), build(it), null, null) } ?: NONE
                Symbols.has("logo-$id") -> Symbols.icon("logo-$id")?.let { BrandMark(id, build(it), null, null) } ?: NONE
                else -> {
                    // A brand name: the widest brand of that name.
                    val hit = Brands.search(id, limit = 1).firstOrNull()
                    hit?.let { BrandMark(it.brand, it.logo?.let { l -> build(l) }, it.colour, it.textColour) } ?: NONE
                }
            }
        }
        return r as? BrandMark
    }

    /** The shape of [name] if it was already loaded ([shape] or [warm]), without loading anything: for drawing. */
    fun loadedShape(name: String): IconShape? {
        val id = ids[name] as? String ?: return null
        return shapes[id] as? IconShape
    }

    /** The mark of brand [id] if it was already loaded, without loading anything: for drawing. */
    fun loadedBrand(id: String): BrandMark? = brands[id] as? BrandMark

    /** Whether [loadedShape] and [loadedBrand] have an answer (even "none") for every one of [names] and [logos]. */
    fun ready(names: Collection<String>, logos: Collection<String>): Boolean =
        names.all { n -> ids[n].let { it === NONE || (it is String && shapes.containsKey(it)) } } && logos.all { brands.containsKey(it) }

    /** Loads the symbols [names] and brands [logos] now (off the main thread), so drawing finds them ready. */
    fun warm(names: Collection<String>, logos: Collection<String> = emptyList()) {
        for (n in names) shape(n)
        for (l in logos) brand(l)
    }

    /** The Android shape of [icon]: its paths, each with its own look when the icon carries one. */
    fun build(icon: Icon): IconShape {
        val fills = ArrayList<Path>()
        val strokes = ArrayList<Path>()
        val parts = ArrayList<IconPart>(icon.paths.size)
        var styled = icon.texts.isNotEmpty()
        for (p in icon.paths) {
            val path = Path()
            p.commands.replay(PathBuilder(path))
            if (p.evenOdd) path.fillType = Path.FillType.EVEN_ODD
            if (p.stroke) strokes += path
            val fill = p.fill || !p.stroke
            if (fill) fills += path
            if (p.colour != null || p.strokeWidth != null || p.opacity != 1f || p.cap != LineCap.ROUND || p.join != LineJoin.ROUND ||
                p.gradient != null || p.dash != null
            ) styled = true
            parts += IconPart(
                path, fill, p.stroke, p.colour?.toInt(), p.strokeWidth ?: icon.strokeWidth, p.opacity,
                when (p.cap) { LineCap.BUTT -> Paint.Cap.BUTT; LineCap.ROUND -> Paint.Cap.ROUND; LineCap.SQUARE -> Paint.Cap.SQUARE },
                when (p.join) { LineJoin.MITER -> Paint.Join.MITER; LineJoin.ROUND -> Paint.Join.ROUND; LineJoin.BEVEL -> Paint.Join.BEVEL },
                shaderOf(p), p.dash?.takeIf { it.size >= 2 }?.let { d -> DashPathEffect(FloatArray(if (d.size % 2 == 0) d.size else 2 * d.size) { d[it % d.size] }, 0f) },
            )
        }
        return IconShape(icon, fills.toTypedArray(), strokes.toTypedArray(), if (styled) parts else emptyList(), icon.texts)
    }

    /** A path's gradient as a shader in viewBox units: linear between its two points, or radial about the first. */
    private fun shaderOf(p: IconPath): Shader? {
        val stops = p.gradient?.takeIf { it.size >= 2 } ?: return null
        val colours = IntArray(stops.size) { stops[it].second.toInt() }
        val positions = FloatArray(stops.size) { stops[it].first.coerceIn(0f, 1f) }
        return if (p.radial) {
            val r = kotlin.math.hypot(p.gx2 - p.gx1, p.gy2 - p.gy1)
            if (r <= 0f) null else RadialGradient(p.gx1, p.gy1, r, colours, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(p.gx1, p.gy1, p.gx2, p.gy2, colours, positions, Shader.TileMode.CLAMP)
        }
    }

    /** Replays SVG path commands into an Android [Path]. */
    private class PathBuilder(private val p: Path) : PathSink {
        override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
        override fun close() = p.close()
    }
}
