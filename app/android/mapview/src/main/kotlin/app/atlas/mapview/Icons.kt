package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Icon
import app.atlas.places.PathSink
import app.atlas.places.Symbols
import java.util.concurrent.ConcurrentHashMap

/** An icon as Android paths in its viewBox units, ready to draw at any size. */
class IconShape(val icon: Icon, val fills: Array<Path>, val strokes: Array<Path>) {
    private val vb = icon.viewBox
    private val extent = maxOf(vb.width, vb.height)

    /**
     * Draws the icon [size] pixels across, centred on ([cx], [cy]), in [paint]'s colour. The paint's style and stroke
     * width are set here and left as fill.
     */
    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val k = size / extent
        canvas.save()
        canvas.translate(cx - vb.width * k / 2, cy - vb.height * k / 2)
        canvas.scale(k, k)
        canvas.translate(-vb.x, -vb.y)
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
        canvas.restore()
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

    private fun build(icon: Icon): IconShape {
        val fills = ArrayList<Path>()
        val strokes = ArrayList<Path>()
        for (p in icon.paths) {
            val path = Path()
            p.commands.replay(PathBuilder(path))
            if (p.evenOdd) path.fillType = Path.FillType.EVEN_ODD
            if (p.stroke) strokes += path
            if (p.fill || !p.stroke) fills += path
        }
        return IconShape(icon, fills.toTypedArray(), strokes.toTypedArray())
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
