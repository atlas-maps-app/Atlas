package app.atlas.engine

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a [Frame] with Java2D, the way the Android view draws it with Canvas: triangles with per-vertex colour
 * (flat where a triangle is one colour, Gouraud by subdivision where it is not), translucent layers composited once,
 * then labels in Serif Italic and SansSerif with halos, and simple discs for the symbols. Geometry is supersampled
 * [supersample] times and averaged down, for clean edges.
 */
class Java2dSink(private val supersample: Int = 2) {
    fun render(frame: Frame, background: Int = Colours.WHITE): BufferedImage {
        val w = frame.width
        val h = frame.height
        val ss = supersample
        val big = BufferedImage(w * ss, h * ss, BufferedImage.TYPE_INT_RGB)
        val g = big.createGraphics()
        g.color = Color(background, false)
        g.fillRect(0, 0, w * ss, h * ss)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        var layerImage: BufferedImage? = null
        var layerGraphics: Graphics2D? = null
        var layerId = 0
        var layerAlpha = 1f
        fun flushLayer() {
            val li = layerImage ?: return
            layerGraphics!!.dispose()
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layerAlpha)
            g.drawImage(li, 0, 0, null)
            g.composite = AlphaComposite.SrcOver
            layerImage = null
            layerGraphics = null
            layerId = 0
        }
        for (b in frame.batches) {
            val t = b as Batch.Triangles
            if (t.layer != layerId) {
                flushLayer()
                if (t.layer != 0) {
                    layerImage = BufferedImage(w * ss, h * ss, BufferedImage.TYPE_INT_ARGB)
                    layerGraphics = layerImage!!.createGraphics().also { it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF) }
                    layerId = t.layer
                    layerAlpha = t.layerAlpha
                }
            }
            triangles(layerGraphics ?: g, t, ss.toFloat())
        }
        flushLayer()
        g.dispose()
        val out = downsample(big, ss)
        val g2 = out.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        for (p in frame.pois) poi(g2, p)
        for (l in frame.labels) label(g2, l)
        g2.dispose()
        return out
    }

    fun write(frame: Frame, file: File, background: Int = Colours.WHITE): BufferedImage {
        val img = render(frame, background)
        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
        return img
    }

    private val xs = IntArray(3)
    private val ys = IntArray(3)

    private fun triangles(g: Graphics2D, t: Batch.Triangles, ss: Float) {
        val p = t.positions
        val c = t.colours
        val idx = t.indices
        var k = 0
        while (k + 2 < t.indexCount) {
            val a = idx[k].toInt() and 0xFFFF
            val b = idx[k + 1].toInt() and 0xFFFF
            val d = idx[k + 2].toInt() and 0xFFFF
            k += 3
            tri(g, p[2 * a] * ss, p[2 * a + 1] * ss, c[a], p[2 * b] * ss, p[2 * b + 1] * ss, c[b], p[2 * d] * ss, p[2 * d + 1] * ss, c[d], 0)
        }
    }

    private fun tri(g: Graphics2D, ax: Float, ay: Float, ca: Int, bx: Float, by: Float, cb: Int, cx: Float, cy: Float, cc: Int, depth: Int) {
        val spread = spread(ca, cb) + spread(cb, cc) + spread(ca, cc)
        val area = abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2
        if (spread > 9 && area > 60f && depth < 5) {
            // Gouraud by halving: four triangles through the edge midpoints.
            val abx = (ax + bx) / 2; val aby = (ay + by) / 2; val cab = Colours.mix(ca, cb, 0.5f)
            val bcx = (bx + cx) / 2; val bcy = (by + cy) / 2; val cbc = Colours.mix(cb, cc, 0.5f)
            val cax = (cx + ax) / 2; val cay = (cy + ay) / 2; val cca = Colours.mix(cc, ca, 0.5f)
            tri(g, ax, ay, ca, abx, aby, cab, cax, cay, cca, depth + 1)
            tri(g, abx, aby, cab, bx, by, cb, bcx, bcy, cbc, depth + 1)
            tri(g, cax, cay, cca, bcx, bcy, cbc, cx, cy, cc, depth + 1)
            tri(g, abx, aby, cab, bcx, bcy, cbc, cax, cay, cca, depth + 1)
            return
        }
        val r = (Colours.red(ca) + Colours.red(cb) + Colours.red(cc)) / 3
        val gg = (Colours.green(ca) + Colours.green(cb) + Colours.green(cc)) / 3
        val b = (Colours.blue(ca) + Colours.blue(cb) + Colours.blue(cc)) / 3
        g.color = Color(r, gg, b)
        xs[0] = ax.roundToInt(); ys[0] = ay.roundToInt()
        xs[1] = bx.roundToInt(); ys[1] = by.roundToInt()
        xs[2] = cx.roundToInt(); ys[2] = cy.roundToInt()
        g.fillPolygon(xs, ys, 3)
    }

    private fun spread(a: Int, b: Int): Int =
        abs(Colours.red(a) - Colours.red(b)) + abs(Colours.green(a) - Colours.green(b)) + abs(Colours.blue(a) - Colours.blue(b))

    private fun downsample(src: BufferedImage, ss: Int): BufferedImage {
        if (ss == 1) return src
        val w = src.width / ss
        val h = src.height / ss
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val row = IntArray(src.width * ss)
        val line = IntArray(w)
        val n = ss * ss
        for (y in 0 until h) {
            src.getRGB(0, y * ss, src.width, ss, row, 0, src.width)
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until ss) for (dx in 0 until ss) {
                    val c = row[dy * src.width + x * ss + dx]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
                }
                line[x] = ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            out.setRGB(0, y, w, 1, line, 0, w)
        }
        return out
    }

    private fun label(g: Graphics2D, l: PlacedLabel) {
        val font = Java2dMeasurer.font(l.style, l.size)
        val frc = g.fontRenderContext
        val alpha = l.alpha.coerceIn(0f, 1f)
        val fill = Color(Colours.red(l.colour), Colours.green(l.colour), Colours.blue(l.colour), (alpha * 255).roundToInt())
        val halo = Color(Colours.red(l.halo), Colours.green(l.halo), Colours.blue(l.halo), (alpha * 0.85f * 255).roundToInt())
        val stroke = BasicStroke(l.haloWidth * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val glyphs = l.glyphs
        if (glyphs == null) {
            val text = spaced(l.text, l.letterSpacing)
            val layout = TextLayout(text, font, frc)
            val w = layout.advance
            val shape = layout.getOutline(AffineTransform.getTranslateInstance((l.x - w / 2).toDouble(), (l.y + layout.ascent / 2 - layout.descent / 3).toDouble()))
            g.color = halo; g.stroke = stroke; g.draw(shape)
            g.color = fill; g.fill(shape)
            return
        }
        for (pass in 0..1) {
            for (k in l.text.indices) {
                val ch = l.text[k].toString()
                if (ch == " ") continue
                val layout = TextLayout(ch, font, frc)
                val at = AffineTransform()
                at.translate(glyphs[3 * k].toDouble(), glyphs[3 * k + 1].toDouble())
                at.rotate(Math.toRadians(glyphs[3 * k + 2].toDouble()))
                at.translate((-layout.advance / 2).toDouble(), (layout.ascent / 2 - layout.descent / 3).toDouble())
                val shape = layout.getOutline(at)
                if (pass == 0) { g.color = halo; g.stroke = stroke; g.draw(shape) } else { g.color = fill; g.fill(shape) }
            }
        }
    }

    private fun spaced(text: String, spacing: Float): String = if (spacing >= 0.05f) text.toCharArray().joinToString(" ") else text

    /** A stand-in for the view's symbol: a disc in the place's category colour with a white ring. */
    private fun poi(g: Graphics2D, p: PlacedPoi) {
        val r = 7.5f * p.scale * 2
        val c = categoryColour(p.poi.cls)
        g.color = Color(255, 255, 255, (p.alpha * 235).roundToInt())
        g.fill(Ellipse2D.Float(p.x - r - 2.5f, p.y - r - 2.5f, 2 * r + 5f, 2 * r + 5f))
        g.color = Color(Colours.red(c), Colours.green(c), Colours.blue(c), (p.alpha * 255).roundToInt())
        g.fill(Ellipse2D.Float(p.x - r, p.y - r, 2 * r, 2 * r))
    }

    private fun categoryColour(cls: String): Int = Colours.parse(
        when (cls) {
            "restaurant", "cafe", "fast_food", "bar", "beer", "ice_cream" -> "#E07A1F"
            "grocery", "bakery", "alcohol_shop" -> "#2E9E5B"
            "shop", "clothing_store", "jewelry", "shoes", "gift" -> "#8E5BD0"
            "hairdresser", "beauty" -> "#D2508F"
            "museum", "art_gallery", "theatre", "attraction", "monument", "castle", "place_of_worship" -> "#B8326A"
            "lodging" -> "#2E6FD8"
            "hospital", "pharmacy", "doctors", "dentist" -> "#D23C3C"
            "fuel" -> "#1E9E5A"
            else -> "#5A6478"
        },
    )
}

/** Text widths from Java2D fonts: Serif Italic for place and water names, SansSerif for the rest. */
object Java2dMeasurer : TextMeasurer {
    private val frc = FontRenderContext(null, true, true)
    private val fonts = HashMap<Pair<LabelStyle, Int>, Font>()

    fun font(style: LabelStyle, size: Float): Font {
        val key = style to (size * 4).roundToInt()
        return synchronized(fonts) {
            fonts.getOrPut(key) {
                when (style) {
                    LabelStyle.SERIF_ITALIC -> Font(Font.SERIF, Font.ITALIC, 12).deriveFont(size)
                    LabelStyle.SANS -> Font(Font.SANS_SERIF, Font.PLAIN, 12).deriveFont(size)
                    LabelStyle.SANS_MEDIUM -> Font(Font.SANS_SERIF, Font.BOLD, 12).deriveFont(size)
                }
            }
        }
    }

    override fun width(text: String, style: LabelStyle, size: Float): Float =
        if (text.isEmpty()) 0f else max(0f, font(style, size).getStringBounds(text, frc).width.toFloat())

    override fun advances(text: String, style: LabelStyle, size: Float, out: FloatArray) {
        val f = font(style, size)
        for (i in text.indices) out[i] = f.getStringBounds(text, i, i + 1, frc).width.toFloat()
    }
}
