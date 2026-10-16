package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgBounds
import app.atlas.mapview.svg.SvgClip
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFont
import app.atlas.mapview.svg.SvgGradient
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgPaint
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgPattern
import app.atlas.mapview.svg.SvgShape
import app.atlas.mapview.svg.SvgStyle
import app.atlas.mapview.svg.SvgText
import app.atlas.mapview.svg.SvgUnits
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.TextAttribute
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Java2D's raster of the engine's SVG with filters off (plan-gpu G1's RefRaster gate), the CPU raster's model: each
 * path's analytic coverage by Java2D (Marlin, pure strokes), composited one path at a time; a stroke no wider than a
 * device pixel as the CPU raster (Skia) draws it, a hairline whose alpha is its width. The compositing itself is
 * RefRaster's (paint evaluated at the pixel's centre, premultiplied in floating point, rounded once per blend), so the
 * two differ only where their coverage does. Generator-only elements are left out and other filters drawn as if
 * absent; group opacity, blend modes and clip paths go through layers (clips as anti-aliased masks); text is Java2D's,
 * the same as [Tiles] gives RefRaster.
 */
object Java2dSvg {
    /** The documents (ground first) drawn at [k] device px per CSS px into [w] × [h] opaque ARGB pixels on [background]. */
    fun render(docs: List<SvgDocument>, k: Double, w: Int, h: Int, background: Int = 0xFFFFFFFF.toInt()): IntArray {
        val px = IntArray(w * h) { background }
        val scratch = Scratch(w, h)
        for (d in docs) Drawer(d, w, h, scratch).node(px, d.root, AffineTransform.getScaleInstance(k, k))
        return px
    }

    /** Java2D's font for an SVG font: Geist and the like as SansSerif, serif faces as Serif; bold from 600. */
    fun font(family: String, size: Float, weight: Int, italic: Boolean, letterSpacing: Float): Font {
        val fam = family.lowercase()
        val name = when {
            fam.contains("mono") -> Font.MONOSPACED
            fam.contains("serif") && !fam.contains("sans") -> Font.SERIF
            else -> Font.SANS_SERIF
        }
        val style = (if (weight >= 600) Font.BOLD else Font.PLAIN) or (if (italic) Font.ITALIC else 0)
        var f = Font(name, style, 1).deriveFont(size)
        if (letterSpacing != 0f && size > 0f) f = f.deriveFont(mapOf(TextAttribute.TRACKING to letterSpacing / size))
        return f
    }

    private fun hints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    /** The text's left edge for its anchor, in text space. */
    private fun left(f: Font, g: Graphics2D, text: String, x: Float, anchor: Int): Float {
        if (anchor == 0) return x
        val adv = f.getStringBounds(text, g.fontRenderContext).width.toFloat()
        return if (anchor == 1) x - adv / 2 else x - adv
    }

    /** A frame-sized image whose alpha channel receives one path's coverage at a time. */
    private class Scratch(val w: Int, val h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
        val px: IntArray = (img.raster.dataBuffer as DataBufferInt).data
        val probe: Graphics2D = img.createGraphics().also { hints(it) }

        /** Covers [shape] (filled, or stroked with [stroke]) under [t]; returns its device box (clamped), or null. */
        fun cover(shape: Shape, t: AffineTransform, stroke: BasicStroke?): Rectangle? {
            val outline = if (stroke != null) t.createTransformedShape(stroke.createStrokedShape(shape)) else t.createTransformedShape(shape)
            return coverDevice(outline)
        }

        fun coverDevice(outline: Shape): Rectangle? {
            val b = outline.bounds2D
            val box = box(b.minX, b.minY, b.maxX, b.maxY, 1) ?: return null
            val g = img.createGraphics()
            hints(g)
            g.color = Color.WHITE
            g.fill(outline)
            g.dispose()
            return box
        }

        /** Covers [text] in [font] at ([x], [y]) under [t]. */
        fun coverText(text: String, font: Font, x: Float, y: Float, t: AffineTransform): Rectangle? {
            val outline = font.createGlyphVector(probe.fontRenderContext, text).getOutline(x, y)
            val b = t.createTransformedShape(outline).bounds2D
            val box = box(b.minX, b.minY, b.maxX, b.maxY, 2) ?: return null
            val g = img.createGraphics()
            hints(g)
            g.transform = t
            g.font = font
            g.color = Color.WHITE
            g.drawString(text, x, y)
            g.dispose()
            return box
        }

        /** The device box of [x0] … [y1] widened by [m] and clamped, cleared; null when empty. */
        private fun box(x0d: Double, y0d: Double, x1d: Double, y1d: Double, m: Int): Rectangle? {
            if (!(x1d >= x0d && y1d >= y0d)) return null
            val x0 = max(0, floor(x0d).toInt() - m); val y0 = max(0, floor(y0d).toInt() - m)
            val x1 = min(w - 1, ceil(x1d).toInt() + m); val y1 = min(h - 1, ceil(y1d).toInt() + m)
            if (x0 > x1 || y0 > y1) return null
            for (y in y0..y1) java.util.Arrays.fill(px, y * w + x0, y * w + x1 + 1, 0)
            return Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }

        fun coverage(i: Int): Int = px[i] ushr 24
    }

    /** A paint at a device pixel: sets the premultiplied colour into a, r, g, b (0 … 255). */
    private fun interface PixelPaint {
        fun at(x: Double, y: Double, out: DoubleArray)
    }

    private fun straight(argb: Int, op: Float, out: DoubleArray) {
        val a = ((argb ushr 24) / 255.0) * op
        out[0] = a * 255; out[1] = ((argb ushr 16) and 0xFF) * a; out[2] = ((argb ushr 8) and 0xFF) * a; out[3] = (argb and 0xFF) * a
    }

    /** Text and pattern tiles drawn by Java2D, for RefRaster on the JVM. */
    class Tiles : TileSource {
        override fun text(anchor: Anchor, i: Int, density: Double): Tile? {
            val t = anchor.texts[i]
            val m = t.transform
            val at = AffineTransform(density, 0.0, 0.0, density, 0.0, 0.0)
            at.concatenate(AffineTransform(m[0].toDouble(), m[1].toDouble(), m[2].toDouble(), m[3].toDouble(), m[4].toDouble(), m[5].toDouble()))
            val f = font(t.family, t.size, t.weight, t.italic, t.letterSpacing)
            val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE).createGraphics().also { hints(it) }
            val x = left(f, probe, t.text, t.x, t.anchor)
            val outline = f.createGlyphVector(probe.fontRenderContext, t.text).getOutline(x, t.y)
            probe.dispose()
            val b = at.createTransformedShape(outline).bounds2D
            val x0 = floor(b.minX).toInt() - 2; val y0 = floor(b.minY).toInt() - 2
            val w = ceil(b.maxX).toInt() + 2 - x0; val h = ceil(b.maxY).toInt() + 2 - y0
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE)
            val g = img.createGraphics()
            hints(g)
            g.transform = AffineTransform.getTranslateInstance(-x0.toDouble(), -y0.toDouble()).also { it.concatenate(at) }
            g.font = f
            g.color = Color.WHITE
            g.drawString(t.text, x, t.y)
            g.dispose()
            // the paint over the coverage, rounded once: an 8-bit tile, as the device's is
            val cov = (img.raster.dataBuffer as DataBufferInt).data
            val c = DoubleArray(4)
            val p = anchor.paints
            straight(p.argb[t.paint], p.opacity[t.paint], c)
            val out = IntArray(w * h) { k ->
                val a = (cov[k] ushr 24) / 255.0
                if (a == 0.0) 0 else ((c[0] * a).roundToInt() shl 24) or ((c[1] * a).roundToInt() shl 16) or ((c[2] * a).roundToInt() shl 8) or (c[3] * a).roundToInt()
            }
            return Tile(x0, y0, w, h, out)
        }

        override fun pattern(anchor: Anchor, i: Int, pxPerUnit: Double): Tile? {
            val doc = SvgParser.parse(anchor.patterns[i].svg)
            val tw = max(1, ceil(doc.width * pxPerUnit - 1e-3).toInt()); val th = max(1, ceil(doc.height * pxPerUnit - 1e-3).toInt())
            val px = IntArray(tw * th)
            Drawer(doc, tw, th, Scratch(tw, th)).node(px, doc.root, AffineTransform.getScaleInstance(tw / doc.width.toDouble(), th / doc.height.toDouble()))
            return Tile(0, 0, tw, th, px)
        }
    }

    /** Draws one document into a frame of premultiplied ARGB pixels. */
    private class Drawer(val doc: SvgDocument, val w: Int, val h: Int, val scratch: Scratch) {
        val bounds = SvgBounds(doc)
        val src = DoubleArray(4)

        fun node(dst: IntArray, n: SvgNode, parent: AffineTransform) {
            val t = AffineTransform(parent)
            n.transform?.let { t.concatenate(aff(it)) }
            val f = n.filter
            if (f != null && AnchorCompiler.generatorOnly(f)) return
            if (n.opacity <= 0f) return
            val st = when (n) { is SvgShape -> n.style; is SvgText -> n.style; else -> null }
            val direct = n.clip == null && n.blend == SvgBlend.NORMAL && (n.opacity >= 1f || (st != null && !(st.hasFill && st.hasStroke)))
            if (direct) {
                content(dst, n, t, n.opacity)
                return
            }
            // a layer: the content, clipped by an anti-aliased mask, composited with the opacity and blend
            val layer = IntArray(w * h)
            content(layer, n, t, 1f)
            n.clip?.let { c -> applyClip(layer, c, n, t) }
            for (i in layer.indices) {
                val s = layer[i]
                if (s == 0) continue
                dst[i] = RefRaster.composite(s, n.opacity, dst[i], n.blend)
            }
        }

        fun content(dst: IntArray, n: SvgNode, t: AffineTransform, opacity: Float) {
            when (n) {
                is SvgGroup -> for (c in n.children) node(dst, c, t)
                is SvgShape -> shape(dst, n, t, opacity)
                is SvgText -> text(dst, n, t, opacity)
            }
        }

        private fun aff(m: FloatArray) = AffineTransform(m[0].toDouble(), m[1].toDouble(), m[2].toDouble(), m[3].toDouble(), m[4].toDouble(), m[5].toDouble())

        /** Composites [paint] over [dst] where the scratch holds coverage, in [box]. */
        private fun composite(dst: IntArray, box: Rectangle, paint: PixelPaint) {
            for (y in box.y until box.y + box.height) for (x in box.x until box.x + box.width) {
                val i = y * w + x
                val a = scratch.coverage(i)
                if (a == 0) continue
                paint.at(x + 0.5, y + 0.5, src)
                dst[i] = RefRaster.over(src, a / 255.0, dst[i])
            }
        }

        private fun applyClip(layer: IntArray, c: SvgClip, owner: SvgNode, t: AffineTransform) {
            val ct = AffineTransform(t)
            if (c.units == SvgUnits.BOUNDING_BOX) {
                val b = FloatArray(4)
                if (!bounds.of(owner, b)) { layer.fill(0); return }
                ct.concatenate(AffineTransform((b[2] - b[0]).toDouble(), 0.0, 0.0, (b[3] - b[1]).toDouble(), b[0].toDouble(), b[1].toDouble()))
            }
            val union = Area()
            for (child in c.children) {
                if (child !is SvgShape) continue
                val s = shapeOf(child) ?: continue
                val m = AffineTransform(ct)
                child.transform?.let { m.concatenate(aff(it)) }
                union.add(Area(m.createTransformedShape(s)))
            }
            val box = scratch.coverDevice(union)
            for (i in layer.indices) {
                if (layer[i] == 0) continue
                val x = i % w; val y = i / w
                val inside = box != null && x >= box.x && y >= box.y && x < box.x + box.width && y < box.y + box.height
                val a = if (inside) scratch.coverage(i) else 0
                if (a == 255) continue
                layer[i] = if (a == 0) 0 else RefRaster.scalePremul(layer[i], a / 255f)
            }
            c.clip?.let { applyClip(layer, it, owner, t) }
        }

        private fun shapeOf(s: SvgShape): Shape? {
            val g = s.geometry
            return when (s.kind) {
                SvgShape.Kind.PATH -> {
                    val p = Path2D.Double(if (s.style.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
                    val verbs = doc.path.verbs; val xy = doc.path.coords
                    var k = s.coordStart
                    var open = false
                    for (v in s.verbStart until s.verbEnd) {
                        when (verbs[v]) {
                            SvgPathData.MOVE -> { p.moveTo(xy[k].toDouble(), xy[k + 1].toDouble()); k += 2; open = true }
                            SvgPathData.LINE -> {
                                if (!open) { val c = p.currentPoint; p.moveTo(c.x, c.y); open = true }
                                p.lineTo(xy[k].toDouble(), xy[k + 1].toDouble()); k += 2
                            }
                            else -> { p.closePath(); open = false }
                        }
                    }
                    p
                }
                SvgShape.Kind.RECT -> if (g[4] > 0f && g[5] > 0f) RoundRectangle2D.Double(g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble(), 2.0 * g[4], 2.0 * g[5])
                else Rectangle2D.Double(g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble())
                SvgShape.Kind.CIRCLE -> Ellipse2D.Double((g[0] - g[2]).toDouble(), (g[1] - g[2]).toDouble(), 2.0 * g[2], 2.0 * g[2])
                SvgShape.Kind.ELLIPSE -> Ellipse2D.Double((g[0] - g[2]).toDouble(), (g[1] - g[3]).toDouble(), 2.0 * g[2], 2.0 * g[3])
            }
        }

        private fun shape(dst: IntArray, s: SvgShape, t: AffineTransform, opacity: Float) {
            val st = s.style
            val shape = shapeOf(s) ?: return
            if (st.hasFill) paint(st.fill, st.fillOpacity * opacity, s, t)?.let { p -> scratch.cover(shape, t, null)?.let { composite(dst, it, p) } }
            if (st.hasStroke) paint(st.stroke, st.strokeOpacity * opacity, s, t)?.let { p ->
                // a stroke no wider than a device pixel is drawn as the CPU raster draws it: Skia's hairline, alpha by width
                val device = st.strokeWidth * Math.sqrt(Math.abs(t.determinant))
                if (device <= Strokes.HAIRLINE && st.dashes == null && s.kind == SvgShape.Kind.PATH) { hairlines(dst, s, t, st.cap != SvgStyle.Cap.BUTT, device, p); return@let }
                val cap = when (st.cap) { SvgStyle.Cap.BUTT -> BasicStroke.CAP_BUTT; SvgStyle.Cap.ROUND -> BasicStroke.CAP_ROUND; SvgStyle.Cap.SQUARE -> BasicStroke.CAP_SQUARE }
                val join = when (st.join) { SvgStyle.Join.MITER -> BasicStroke.JOIN_MITER; SvgStyle.Join.ROUND -> BasicStroke.JOIN_ROUND; SvgStyle.Join.BEVEL -> BasicStroke.JOIN_BEVEL }
                val dash = st.dashes?.toFloatArray()
                val stroke = if (dash != null) BasicStroke(st.strokeWidth, cap, join, max(1f, st.miterLimit), dash, st.dashOffset) else BasicStroke(st.strokeWidth, cap, join, max(1f, st.miterLimit))
                scratch.cover(shape, t, stroke)?.let { composite(dst, it, p) }
            }
        }

        /** Each segment of [s]'s subpaths under [t] as a hairline [width] device px wide; free ends extended when [capped]. */
        private fun hairlines(dst: IntArray, s: SvgShape, t: AffineTransform, capped: Boolean, width: Double, paint: PixelPaint) {
            val verbs = doc.path.verbs; val xy = doc.path.coords
            val q = DoubleArray(2)
            val pts = ArrayList<DoubleArray>()
            fun flush(closed: Boolean) {
                // repeats dropped, as the stroker does
                val v = ArrayList<DoubleArray>()
                for (p in pts) if (v.isEmpty() || v.last()[0] != p[0] || v.last()[1] != p[1]) v.add(p)
                if (closed && v.size > 1 && v.last()[0] == v[0][0] && v.last()[1] == v[0][1]) v.removeAt(v.size - 1)
                val n = if (closed) v.size else v.size - 1
                for (k in 0 until n) {
                    val a = v[k]; val b = v[(k + 1) % v.size]
                    Strokes.hairline(a[0], a[1], b[0], b[1], capped && !closed && k == 0, capped && !closed && k == v.size - 2, width) { x, y, cov ->
                        if (x in 0 until w && y in 0 until h) { paint.at(x + 0.5, y + 0.5, src); dst[y * w + x] = RefRaster.over(src, cov, dst[y * w + x]) }
                    }
                }
                pts.clear()
            }
            var k = s.coordStart
            var start: DoubleArray? = null
            for (vi in s.verbStart until s.verbEnd) {
                when (verbs[vi]) {
                    SvgPathData.MOVE -> {
                        flush(false)
                        q[0] = xy[k].toDouble(); q[1] = xy[k + 1].toDouble(); k += 2
                        t.transform(q, 0, q, 0, 1); start = q.copyOf(); pts.add(q.copyOf())
                    }
                    SvgPathData.LINE -> {
                        if (pts.isEmpty() && start != null) pts.add(start.copyOf())
                        q[0] = xy[k].toDouble(); q[1] = xy[k + 1].toDouble(); k += 2
                        t.transform(q, 0, q, 0, 1); pts.add(q.copyOf())
                    }
                    else -> flush(true)
                }
            }
            flush(false)
        }

        private fun text(dst: IntArray, t: SvgText, at: AffineTransform, opacity: Float) {
            val st = t.style
            if (!st.hasFill) return
            val p = paint(st.fill, st.fillOpacity * opacity, t, at) ?: return
            val f = t.font
            val font = font(f.family, f.size, f.weight, f.italic, f.letterSpacing)
            val anchor = when (f.anchor) { SvgFont.Anchor.START -> 0; SvgFont.Anchor.MIDDLE -> 1; SvgFont.Anchor.END -> 2 }
            val x = left(font, scratch.probe, t.text, t.x, anchor)
            scratch.coverText(t.text, font, x, t.y, at)?.let { composite(dst, it, p) }
        }

        /** The paint of [p] at [op]acity on [owner] drawn under [t], or null when nothing is drawn. */
        private fun paint(p: SvgPaint, op: Float, owner: SvgNode, t: AffineTransform): PixelPaint? {
            if (op <= 0f) return null
            return when (p) {
                SvgPaint.None -> null
                is SvgPaint.Color -> PixelPaint { _, _, out -> straight(p.argb, op, out) }
                is SvgPaint.Ref -> {
                    val g = doc.gradients[p.id]
                    if (g != null) return gradient(g, op, owner, t)
                    val pt = doc.patterns[p.id]
                    if (pt != null) return pattern(pt, op, owner, t)
                    paint(p.fallback, op, owner, t)
                }
            }
        }

        private fun gradient(g: SvgGradient, op: Float, owner: SvgNode, t: AffineTransform): PixelPaint? {
            if (g.offsets.isEmpty()) return null
            val k = g.coords
            val degenerate = if (g.radial) k[2] <= 0f else k[0] == k[2] && k[1] == k[3]
            if (g.offsets.size == 1 || degenerate) { val c = g.colors[g.colors.size - 1]; return PixelPaint { _, _, out -> straight(c, op, out) } }
            val m = AffineTransform(t)
            if (g.units == SvgUnits.BOUNDING_BOX) {
                val b = FloatArray(4)
                if (!bounds.of(owner, b)) return null
                val bw = b[2] - b[0]; val bh = b[3] - b[1]
                if (bw <= 0f || bh <= 0f) return null
                m.concatenate(AffineTransform(bw.toDouble(), 0.0, 0.0, bh.toDouble(), b[0].toDouble(), b[1].toDouble()))
            }
            g.transform?.let { m.concatenate(aff(it)) }
            val inv = try { m.createInverse() } catch (e: java.awt.geom.NoninvertibleTransformException) { return null }
            val pt = DoubleArray(2)
            return PixelPaint { x, y, out ->
                pt[0] = x; pt[1] = y
                inv.transform(pt, 0, pt, 0, 1)
                RefRaster.gradientAt(g, pt[0], pt[1], out)
                RefRaster.premultiplyInPlace(out, op)
            }
        }

        private fun pattern(pt: SvgPattern, op: Float, owner: SvgNode, t: AffineTransform): PixelPaint? {
            var x = pt.x; var y = pt.y; var pw = pt.width; var ph = pt.height
            if (pt.units == SvgUnits.BOUNDING_BOX) {
                val b = FloatArray(4)
                if (!bounds.of(owner, b)) return null
                val bw = b[2] - b[0]; val bh = b[3] - b[1]
                x = b[0] + x * bw; y = b[1] + y * bh; pw *= bw; ph *= bh
            }
            if (pw <= 0f || ph <= 0f) return null
            val m = AffineTransform(t)
            pt.transform?.let { m.concatenate(aff(it)) }
            m.concatenate(AffineTransform.getTranslateInstance(x.toDouble(), y.toDouble()))
            val res = Math.sqrt(Math.abs(m.determinant))
            val tw = max(1, ceil(pw * res - 1e-3).toInt()); val th = max(1, ceil(ph * res - 1e-3).toInt())
            val tile = IntArray(tw * th)
            val tp = Drawer(doc, tw, th, Scratch(tw, th))
            for (c in pt.content.children) tp.node(tile, c, AffineTransform.getScaleInstance(tw / pw.toDouble(), th / ph.toDouble()))
            val inv = try { m.createInverse() } catch (e: java.awt.geom.NoninvertibleTransformException) { return null }
            val q = DoubleArray(2)
            val ppu = tw / pw.toDouble()
            return PixelPaint { px, py, out ->
                q[0] = px; q[1] = py
                inv.transform(q, 0, q, 0, 1)
                val tx = Math.floorMod(floor(q[0] * ppu).toInt(), tw); val ty = Math.floorMod(floor(q[1] * ppu).toInt(), th)
                val c = tile[ty * tw + tx]
                out[0] = (c ushr 24) * op.toDouble(); out[1] = ((c ushr 16) and 0xFF) * op.toDouble(); out[2] = ((c ushr 8) and 0xFF) * op.toDouble(); out[3] = (c and 0xFF) * op.toDouble()
            }
        }
    }
}
