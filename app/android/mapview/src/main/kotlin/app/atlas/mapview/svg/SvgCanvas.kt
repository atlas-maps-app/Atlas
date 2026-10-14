package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws an [SvgDocument] on an Android [Canvas] the way a browser draws the same SVG: paths with their fill rules,
 * caps, joins and dashes; linear and radial gradients in both unit systems; patterns as a [BitmapShader] of a recorded
 * tile; clip paths; group opacity and `mix-blend-mode` through layers; text in the bundled typefaces; and filters.
 *
 * Filters run on the CPU by default ([SvgFilterRaster], exact on every Android version). On API 33 and later a
 * hardware-accelerated canvas runs them as AGSL shaders ([SvgShaderFilters]) when [filters] allows it.
 *
 * One instance keeps caches (paths of clips, pattern tiles, filter chains) and is not thread-safe.
 */
class SvgCanvas(
    val doc: SvgDocument,
    private val fonts: SvgFonts,
    var filters: FilterMode = FilterMode.AUTO,
) {
    /** How filters are drawn. */
    enum class FilterMode {
        /** AGSL on a hardware canvas on API 33+, the CPU otherwise. */
        AUTO,

        /** Always on the CPU: exact, slower. */
        CPU,

        /** AGSL shaders (API 33+; falls back to the CPU below). */
        SHADER,

        /** Filters are ignored: the element is drawn as if it had none. */
        NONE,
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val layerPaint = Paint()
    private val bitmapPaint = Paint()
    private val path = Path()
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val box = FloatArray(4)
    private val rectF = RectF()
    private val clipRect = Rect()
    private val bounds = SvgBounds(doc) { measure(it) }
    private val clipPaths = IdentityHashMap<SvgClip, Path>()
    private val gradientShaders = HashMap<String, Shader?>()
    private val patternTiles = HashMap<String, Bitmap>()
    private val rasters = IdentityHashMap<SvgFilter, SvgFilterRaster>()
    private val strokeReach = IdentityHashMap<SvgNode, Float>()
    private val dashEffects = HashMap<Pair<List<Float>, Float>, DashPathEffect>()
    private var shaderFilters: SvgShaderFilters? = null

    /** Counts of what the last [draw] did, for tests and timing. */
    val stats = Stats()

    class Stats {
        var shapes = 0
        var texts = 0
        var layers = 0
        var filtered = 0
        var filterPixels = 0L
        var patternTiles = 0

        /** Time spent running filters on the CPU, and drawing their sources, in nanoseconds. */
        var filterNanos = 0L
        var sourceNanos = 0L

        fun reset() {
            filterNanos = 0
            sourceNanos = 0
            shapes = 0
            texts = 0
            layers = 0
            filtered = 0
            filterPixels = 0
            patternTiles = 0
        }
    }

    /** Draws the document into the rectangle 0, 0, [width], [height] of [canvas], fitting its view box (xMidYMid meet). */
    fun draw(canvas: Canvas, width: Float = doc.width, height: Float = doc.height) {
        stats.reset()
        val vb = doc.viewBox
        val s = min(width / vb[2], height / vb[3])
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.translate((width - vb[2] * s) / 2, (height - vb[3] * s) / 2)
        canvas.scale(s, s)
        canvas.translate(-vb[0], -vb[1])
        node(canvas, doc.root)
        canvas.restore()
    }

    /** Draws [n] with its transform, clip, filter, opacity and blending. */
    private fun node(c: Canvas, n: SvgNode) {
        val t = n.transform
        if (n.filter == null && n.clip == null && (n is SvgShape || n is SvgText) && foldable(n)) {
            if (t != null) {
                c.save()
                concat(c, t)
            }
            if (n is SvgShape) shape(c, n, n.opacity, n.blend) else text(c, n as SvgText, n.opacity, n.blend)
            if (t != null) c.restore()
            return
        }
        val count = c.save()
        if (t != null) concat(c, t)
        val f = n.filter
        val cpuFilter = f != null && filters != FilterMode.NONE && !useShader(c)
        // A CPU-filtered element's opacity goes into its result's pixels, as Core Graphics applies it, not a layer.
        val fold = cpuFilter && n.blend == SvgBlend.NORMAL
        if (n.opacity <= 0f) {
            c.restoreToCount(count)
            return
        }
        if ((n.opacity < 1f && !fold) || n.blend != SvgBlend.NORMAL) {
            layerPaint.reset()
            // A float alpha: the layer is composited at the exact opacity rather than its nearest byte.
            layerPaint.setColor(Color.pack(0f, 0f, 0f, n.opacity))
            if (n.blend != SvgBlend.NORMAL) layerPaint.blendMode = blendMode(n.blend)
            stats.layers++
            if (layerBounds(n, rectF)) c.saveLayer(rectF, layerPaint) else c.saveLayer(null, layerPaint)
        }
        n.clip?.let { clip(c, it, n) }
        if (f != null && filters != FilterMode.NONE) filtered(c, n, f, if (fold) n.opacity else 1f) else content(c, n)
        c.restoreToCount(count)
    }

    /** True when [n]'s opacity and blending can go on its single paint instead of a layer. */
    private fun foldable(n: SvgNode): Boolean {
        if (n.opacity >= 1f && n.blend == SvgBlend.NORMAL) return true
        val st = if (n is SvgShape) n.style else (n as SvgText).style
        return !(st.hasFill && st.hasStroke)
    }

    /** Draws what [n] is made of, without its own transform, clip, filter or opacity. */
    private fun content(c: Canvas, n: SvgNode) {
        when (n) {
            is SvgGroup -> for (child in n.children) node(c, child)
            is SvgShape -> shape(c, n, 1f, SvgBlend.NORMAL)
            is SvgText -> text(c, n, 1f, SvgBlend.NORMAL)
        }
    }

    private fun shape(c: Canvas, s: SvgShape, opacity: Float, blend: SvgBlend) {
        val st = s.style
        stats.shapes++
        if (st.hasFill && paint(c, fillPaint, st.fill, st.fillOpacity * opacity, s)) {
            fillPaint.blendMode = if (blend == SvgBlend.NORMAL) null else blendMode(blend)
            geometry(c, s, fillPaint, st.evenOdd)
        }
        if (st.hasStroke && paint(c, strokePaint, st.stroke, st.strokeOpacity * opacity, s)) {
            strokePaint.strokeWidth = st.strokeWidth
            strokePaint.strokeCap = when (st.cap) {
                SvgStyle.Cap.BUTT -> Paint.Cap.BUTT
                SvgStyle.Cap.ROUND -> Paint.Cap.ROUND
                SvgStyle.Cap.SQUARE -> Paint.Cap.SQUARE
            }
            strokePaint.strokeJoin = when (st.join) {
                SvgStyle.Join.MITER -> Paint.Join.MITER
                SvgStyle.Join.ROUND -> Paint.Join.ROUND
                SvgStyle.Join.BEVEL -> Paint.Join.BEVEL
            }
            strokePaint.strokeMiter = st.miterLimit
            strokePaint.pathEffect = st.dashes?.let { d ->
                dashEffects.getOrPut(d to st.dashOffset) { DashPathEffect(d.toFloatArray(), st.dashOffset) }
            }
            strokePaint.blendMode = if (blend == SvgBlend.NORMAL) null else blendMode(blend)
            geometry(c, s, strokePaint, false)
        }
    }

    private fun geometry(c: Canvas, s: SvgShape, p: Paint, evenOdd: Boolean) {
        val g = s.geometry
        when (s.kind) {
            SvgShape.Kind.PATH -> {
                buildPath(s, path)
                path.fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                c.drawPath(path, p)
            }
            SvgShape.Kind.RECT -> if (g[4] > 0f && g[5] > 0f) {
                c.drawRoundRect(g[0], g[1], g[0] + g[2], g[1] + g[3], g[4], g[5], p)
            } else {
                c.drawRect(g[0], g[1], g[0] + g[2], g[1] + g[3], p)
            }
            SvgShape.Kind.CIRCLE -> c.drawCircle(g[0], g[1], g[2], p)
            SvgShape.Kind.ELLIPSE -> c.drawOval(g[0] - g[2], g[1] - g[3], g[0] + g[2], g[1] + g[3], p)
        }
    }

    /** Fills [out] with [s]'s path geometry (any kind). */
    private fun buildPath(s: SvgShape, out: Path) {
        out.rewind()
        val g = s.geometry
        when (s.kind) {
            SvgShape.Kind.PATH -> {
                val verbs = doc.path.verbs
                val xy = doc.path.coords
                var k = s.coordStart
                for (v in s.verbStart until s.verbEnd) {
                    when (verbs[v]) {
                        SvgPathData.MOVE -> {
                            out.moveTo(xy[k], xy[k + 1])
                            k += 2
                        }
                        SvgPathData.LINE -> {
                            out.lineTo(xy[k], xy[k + 1])
                            k += 2
                        }
                        else -> out.close()
                    }
                }
            }
            SvgShape.Kind.RECT -> if (g[4] > 0f && g[5] > 0f) {
                out.addRoundRect(g[0], g[1], g[0] + g[2], g[1] + g[3], g[4], g[5], Path.Direction.CW)
            } else {
                out.addRect(g[0], g[1], g[0] + g[2], g[1] + g[3], Path.Direction.CW)
            }
            SvgShape.Kind.CIRCLE -> out.addCircle(g[0], g[1], g[2], Path.Direction.CW)
            SvgShape.Kind.ELLIPSE -> out.addOval(g[0] - g[2], g[1] - g[3], g[0] + g[2], g[1] + g[3], Path.Direction.CW)
        }
    }

    private fun text(c: Canvas, t: SvgText, opacity: Float, blend: SvgBlend) {
        val st = t.style
        stats.texts++
        if (!st.hasFill || !paint(c, textPaint, st.fill, st.fillOpacity * opacity, t)) return
        font(textPaint, t.font)
        textPaint.blendMode = if (blend == SvgBlend.NORMAL) null else blendMode(blend)
        c.drawText(t.text, t.x, t.y, textPaint)
    }

    private fun font(p: Paint, f: SvgFont) {
        p.typeface = fonts.typeface(f.family, f.weight, f.italic)
        p.fontVariationSettings = fonts.variation(f.family, f.weight)
        p.textSize = f.size
        p.letterSpacing = if (f.size > 0f) f.letterSpacing / f.size else 0f
        p.textAlign = when (f.anchor) {
            SvgFont.Anchor.START -> Paint.Align.LEFT
            SvgFont.Anchor.MIDDLE -> Paint.Align.CENTER
            SvgFont.Anchor.END -> Paint.Align.RIGHT
        }
    }

    private fun measure(t: SvgText): Float {
        font(textPaint, t.font)
        return textPaint.measureText(t.text)
    }

    /**
     * Sets [p] up for [paint] at [opacity]: a colour, a gradient or a pattern. Returns false when nothing is to be drawn
     * (`none`, a missing reference without fallback, or a box-relative paint on a shape without area).
     */
    private fun paint(c: Canvas, p: Paint, paint: SvgPaint, opacity: Float, owner: SvgNode): Boolean {
        when (paint) {
            SvgPaint.None -> return false
            is SvgPaint.Color -> {
                p.shader = null
                color(p, paint.argb, opacity)
                return opacity > 0f
            }
            is SvgPaint.Ref -> {
                val g = doc.gradients[paint.id]
                if (g != null) return gradient(p, g, opacity, owner)
                val pt = doc.patterns[paint.id]
                if (pt != null) return pattern(c, p, pt, opacity, owner)
                return paint(c, p, paint.fallback, opacity, owner)
            }
        }
    }

    private fun color(p: Paint, argb: Int, opacity: Float) {
        val a = ((argb ushr 24) / 255f) * opacity
        p.setColor(Color.pack(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, a))
    }

    private fun gradient(p: Paint, g: SvgGradient, opacity: Float, owner: SvgNode): Boolean {
        if (g.offsets.isEmpty()) return false
        if (g.offsets.size == 1 || degenerate(g)) {
            p.shader = null
            color(p, g.colors[g.colors.size - 1], opacity)
            return true
        }
        val shader = gradientShaders.getOrPut(g.id) { gradientShader(g) } ?: return false
        matrix.reset()
        if (g.units == SvgUnits.BOUNDING_BOX) {
            if (!bounds.of(owner, box)) return false
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            if (w <= 0f || h <= 0f) return false
            matrix.setValues(floatArrayOf(w, 0f, box[0], 0f, h, box[1], 0f, 0f, 1f))
        }
        g.transform?.let { matrix.preConcat(affine(it)) }
        shader.setLocalMatrix(matrix)
        p.shader = shader
        p.setColor(Color.pack(0f, 0f, 0f, opacity))
        return opacity > 0f
    }

    private fun degenerate(g: SvgGradient): Boolean {
        val k = g.coords
        return if (g.radial) k[2] <= 0f else k[0] == k[2] && k[1] == k[3]
    }

    private fun gradientShader(g: SvgGradient): Shader? {
        val tile = when (g.spread) {
            SvgGradient.Spread.PAD -> Shader.TileMode.CLAMP
            SvgGradient.Spread.REFLECT -> Shader.TileMode.MIRROR
            SvgGradient.Spread.REPEAT -> Shader.TileMode.REPEAT
        }
        val k = g.coords
        val (colors, offsets) = unpremultipliedStops(g.colors, g.offsets)
        return if (g.radial) {
            if (k[3] != k[0] || k[4] != k[1]) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val packed = LongArray(colors.size) { Color.pack(colors[it]) }
                    return RadialGradient(k[3], k[4], 0f, k[0], k[1], k[2], packed, offsets, tile)
                }
            }
            RadialGradient(k[0], k[1], k[2], colors, offsets, tile)
        } else {
            LinearGradient(k[0], k[1], k[2], k[3], colors, offsets, tile)
        }
    }

    private fun pattern(c: Canvas, p: Paint, pt: SvgPattern, opacity: Float, owner: SvgNode): Boolean {
        var x = pt.x
        var y = pt.y
        var w = pt.width
        var h = pt.height
        if (pt.units == SvgUnits.BOUNDING_BOX || pt.contentUnits == SvgUnits.BOUNDING_BOX) {
            if (!bounds.of(owner, box)) return false
            val bw = box[2] - box[0]
            val bh = box[3] - box[1]
            if (pt.contentUnits == SvgUnits.BOUNDING_BOX) throw SvgFormatException("patternContentUnits=objectBoundingBox is not written by the engine")
            x = box[0] + x * bw
            y = box[1] + y * bh
            w *= bw
            h *= bh
        }
        if (w <= 0f || h <= 0f) return false
        val t = pt.transform
        // The tile's resolution: device pixels per pattern unit, from the canvas and the pattern's own transform.
        currentMatrix(c)
        var res = sqrt(abs(values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y] - values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y]))
        if (t != null) res *= sqrt(abs(t[0] * t[3] - t[1] * t[2]))
        res = res.coerceAtLeast(0.01f)
        val bw = ceil(w * res - 1e-3f).toInt().coerceIn(1, 2048)
        val bh = ceil(h * res - 1e-3f).toInt().coerceIn(1, 2048)
        val key = "${System.identityHashCode(pt.content)}:$w:$h:$bw:$bh"
        val tile = patternTiles.getOrPut(key) {
            stats.patternTiles++
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val tc = Canvas(bmp)
            tc.scale(bw / w, bh / h)
            val saved = SavedPaints(this)
            for (child in pt.content.children) node(tc, child)
            saved.restore(this)
            bmp
        }
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        matrix.reset()
        if (t != null) matrix.set(affine(t))
        matrix.preTranslate(x, y)
        matrix.preScale(w / bw, h / bh)
        shader.setLocalMatrix(matrix)
        p.shader = shader
        p.setColor(Color.pack(0f, 0f, 0f, opacity))
        return opacity > 0f
    }

    /** The paints are shared; drawing a pattern tile in the middle of setting one up must not disturb it. */
    private class SavedPaints(c: SvgCanvas) {
        private val fill = Paint(c.fillPaint)
        private val stroke = Paint(c.strokePaint)
        private val text = Paint(c.textPaint)

        fun restore(c: SvgCanvas) {
            c.fillPaint.set(fill)
            c.strokePaint.set(stroke)
            c.textPaint.set(text)
        }
    }

    private fun clip(c: Canvas, clip: SvgClip, owner: SvgNode) {
        val p = clipPaths.getOrPut(clip) {
            val out = Path()
            val one = Path()
            for (child in clip.children) {
                if (child !is SvgShape) continue
                buildPath(child, one)
                one.fillType = if (child.style.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                child.transform?.let { one.transform(affine(it)) }
                if (out.isEmpty) out.set(one) else out.op(one, Path.Op.UNION)
            }
            out
        }
        if (clip.units == SvgUnits.BOUNDING_BOX) {
            if (!bounds.of(owner, box)) {
                c.clipRect(0f, 0f, 0f, 0f)
                return
            }
            val m = Matrix()
            m.setValues(floatArrayOf(box[2] - box[0], 0f, box[0], 0f, box[3] - box[1], box[1], 0f, 0f, 1f))
            val q = Path(p)
            q.transform(m)
            c.clipPath(q)
        } else {
            c.clipPath(p)
        }
        clip.clip?.let { clip(c, it, owner) }
    }

    /** Draws [n] through [f]. */
    private fun filtered(c: Canvas, n: SvgNode, f: SvgFilter, opacity: Float) {
        val hasBox = bounds.of(n, box)
        val region = DoubleArray(4)
        if (f.units == SvgUnits.BOUNDING_BOX) {
            if (!hasBox) return
            val bw = (box[2] - box[0]).toDouble()
            val bh = (box[3] - box[1]).toDouble()
            if (bw <= 0.0 || bh <= 0.0) return
            region[0] = box[0] + f.region[0] * bw
            region[1] = box[1] + f.region[1] * bh
            region[2] = f.region[2] * bw
            region[3] = f.region[3] * bh
        } else {
            for (i in 0 until 4) region[i] = f.region[i].toDouble()
        }
        if (region[2] <= 0.0 || region[3] <= 0.0) return
        currentMatrix(c)
        var sx = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]).toDouble()
        var sy = hypot(values[Matrix.MSKEW_X], values[Matrix.MSCALE_Y]).toDouble()
        if (sx <= 0.0 || sy <= 0.0) return
        // WebKit lowers the filter's resolution when the source (its stroke box within the region) or the region
        // would need more than 4096² pixels.
        if (hasBox) {
            val pad = reach(n).coerceAtMost(1e4f).toDouble()
            val w = min(box[2] + pad, region[0] + region[2]) - max(box[0] - pad, region[0])
            val h = min(box[3] + pad, region[1] + region[3]) - max(box[1] - pad, region[1])
            val k = clampScale(w * sx, h * sy)
            sx *= k
            sy *= k
        }
        val k = clampScale(region[2] * sx, region[3] * sy)
        sx *= k
        sy *= k
        if (!c.getClipBounds(clipRect)) return
        val visible = SvgFilterRaster.Rect(
            floor(clipRect.left * sx).toInt() - 1, floor(clipRect.top * sy).toInt() - 1,
            ceil(clipRect.right * sx).toInt() + 1, ceil(clipRect.bottom * sy).toInt() + 1,
        )
        stats.filtered++
        if (useShader(c)) {
            val sf = shaderFilters ?: SvgShaderFilters().also { shaderFilters = it }
            if (sf.draw(c, f, region, sx, sy, visible) { canvas -> content(canvas, n) }) return
        }
        val raster = rasters.getOrPut(f) { SvgFilterRaster(f) }
        if (!raster.prepare(region, sx, sy, visible)) return
        val t0 = System.nanoTime()
        val sr = raster.sourceRect
        val pixels: IntArray
        if (sr.isEmpty) {
            pixels = IntArray(0)
        } else {
            val src = Bitmap.createBitmap(sr.width, sr.height, Bitmap.Config.ARGB_8888)
            val sc = Canvas(src)
            sc.translate(-sr.left.toFloat(), -sr.top.toFloat())
            sc.scale(sx.toFloat(), sy.toFloat())
            val saved = SavedPaints(this)
            content(sc, n)
            saved.restore(this)
            pixels = premultipliedPixels(src)
            src.recycle()
        }
        val t1 = System.nanoTime()
        val o = raster.outputRect
        val out = IntArray(o.width * o.height)
        raster.run(pixels, out)
        if (opacity < 1f) for (i in out.indices) out[i] = fade(out[i], opacity)
        stats.sourceNanos += t1 - t0
        stats.filterNanos += System.nanoTime() - t1
        stats.filterPixels += out.size
        val result = bitmapOfPremultiplied(out, o.width, o.height)
        c.save()
        c.scale((1 / sx).toFloat(), (1 / sy).toFloat())
        bitmapPaint.isFilterBitmap = k < 1.0
        c.drawBitmap(result, o.left.toFloat(), o.top.toFloat(), bitmapPaint)
        c.restore()
        result.recycle()
    }

    /** The local bounds of a layer for [n]: its box grown by the widest stroke inside it, or false when unknown. */
    private fun layerBounds(n: SvgNode, out: RectF): Boolean {
        if (!bounds.of(n, box)) return false
        val pad = reach(n) + 1f
        out.set(box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad)
        return true
    }

    /** How far [n] draws beyond its bounding box: half its widest stroke (miters and caps included), or a filter. */
    private fun reach(n: SvgNode): Float = strokeReach.getOrPut(n) {
        if (n.filter != null) return@getOrPut 1e5f
        when (n) {
            is SvgShape -> if (n.style.hasStroke) n.style.strokeWidth * max(n.style.miterLimit, 1.5f) / 2 else 0f
            is SvgText -> n.font.size
            is SvgGroup -> {
                var r = 0f
                for (c in n.children) {
                    val k = reach(c)
                    val t = c.transform
                    r = max(r, if (t == null) k else k * sqrt(abs(t[0] * t[3] - t[1] * t[2])).coerceAtLeast(1f))
                }
                r
            }
        }
    }

    /** Reads [c]'s current matrix into [values]. */
    @Suppress("DEPRECATION")
    private fun currentMatrix(c: Canvas) {
        c.getMatrix(matrix)
        matrix.getValues(values)
    }

    private fun concat(c: Canvas, t: FloatArray) {
        c.concat(affine(t))
    }

    private fun affine(t: FloatArray): Matrix {
        val m = Matrix()
        m.setValues(floatArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0f, 0f, 1f))
        return m
    }

    private fun useShader(c: Canvas) = Build.VERSION.SDK_INT >= 33 && when (filters) {
        FilterMode.SHADER -> true
        FilterMode.AUTO -> c.isHardwareAccelerated
        else -> false
    }

    /** Premultiplied [p] at [opacity], rounded. */
    private fun fade(p: Int, opacity: Float): Int {
        if (p == 0) return 0
        fun s(v: Int) = (v * opacity + 0.5f).toInt().coerceIn(0, 255)
        return (s(p ushr 24) shl 24) or (s((p shr 16) and 0xFF) shl 16) or (s((p shr 8) and 0xFF) shl 8) or s(p and 0xFF)
    }

    /** WebKit's `ImageBuffer::sizeNeedsClamping`: the factor that brings a w × h pixel area within 4096². */
    private fun clampScale(w: Double, h: Double): Double {
        if (w <= 0 || h <= 0) return 1.0
        return if (floor(w) * floor(h) > MAX_FILTER_AREA) sqrt(MAX_FILTER_AREA / (w * h)) else 1.0
    }

    companion object {
        private const val MAX_FILTER_AREA = 4096.0 * 4096.0

        /** Sub-stops per pair of stops whose opacities differ. */
        private const val SUBSTOPS = 32

        /**
         * Stops that make Android's gradients, which interpolate premultiplied colour, look like the browser's, which
         * interpolate unpremultiplied colour: between two stops of different opacity, sub-stops sample the browser's
         * curve closely enough that the premultiplied interpolation between them stays within a quarter of a level.
         */
        fun unpremultipliedStops(colors: IntArray, offsets: FloatArray): Pair<IntArray, FloatArray> {
            var extra = 0
            for (i in 0 until colors.size - 1) if (needsSubstops(colors[i], colors[i + 1], offsets[i], offsets[i + 1])) extra += SUBSTOPS - 1
            if (extra == 0) return colors to offsets
            val c = IntArray(colors.size + extra)
            val o = FloatArray(colors.size + extra)
            var n = 0
            for (i in colors.indices) {
                c[n] = colors[i]
                o[n++] = offsets[i]
                if (i == colors.size - 1 || !needsSubstops(colors[i], colors[i + 1], offsets[i], offsets[i + 1])) continue
                for (k in 1 until SUBSTOPS) {
                    val t = k.toFloat() / SUBSTOPS
                    c[n] = lerpArgb(colors[i], colors[i + 1], t)
                    o[n++] = offsets[i] + (offsets[i + 1] - offsets[i]) * t
                }
            }
            return c to o
        }

        private fun needsSubstops(a: Int, b: Int, oa: Float, ob: Float) = (a ushr 24) != (b ushr 24) && (a and 0xFFFFFF) != (b and 0xFFFFFF) && ob > oa

        private fun lerpArgb(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int): Int {
                val x = (a ushr sh) and 0xFF
                val y = (b ushr sh) and 0xFF
                return Math.round(x + (y - x) * t).coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** [b]'s pixels as premultiplied `a r g b` ints (Android keeps them as R, G, B, A bytes). */
        fun premultipliedPixels(b: Bitmap): IntArray {
            val bytes = ByteBuffer.allocate(b.byteCount).order(ByteOrder.nativeOrder())
            b.copyPixelsToBuffer(bytes)
            bytes.rewind()
            val ints = IntArray(b.width * b.height)
            bytes.asIntBuffer().get(ints)
            for (i in ints.indices) ints[i] = swapRedBlue(ints[i])
            return ints
        }

        /** A bitmap holding premultiplied `a r g b` [px] as they are, without a round trip through unpremultiplied colour. */
        fun bitmapOfPremultiplied(px: IntArray, w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bytes = ByteBuffer.allocate(w * h * 4).order(ByteOrder.nativeOrder())
            val ints = bytes.asIntBuffer()
            for (p in px) ints.put(swapRedBlue(p))
            bytes.rewind()
            b.copyPixelsFromBuffer(bytes)
            return b
        }

        /** `a r g b` ↔ the int a little-endian R, G, B, A byte quadruple reads as. */
        private fun swapRedBlue(p: Int): Int {
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                // Big-endian: the bytes R, G, B, A read as r g b a.
                return (p ushr 8) or (p shl 24)
            }
            return (p and 0xFF00FF00.toInt()) or ((p shr 16) and 0xFF) or ((p and 0xFF) shl 16)
        }

        /** The Android blend mode of a CSS `mix-blend-mode`. */
        fun blendMode(b: SvgBlend): BlendMode = when (b) {
            SvgBlend.NORMAL -> BlendMode.SRC_OVER
            SvgBlend.MULTIPLY -> BlendMode.MULTIPLY
            SvgBlend.SCREEN -> BlendMode.SCREEN
            SvgBlend.OVERLAY -> BlendMode.OVERLAY
            SvgBlend.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
            SvgBlend.DARKEN -> BlendMode.DARKEN
            SvgBlend.LIGHTEN -> BlendMode.LIGHTEN
        }
    }
}
