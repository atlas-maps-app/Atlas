package app.atlas.places

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.MultipleGradientPaint
import java.awt.Paint
import java.awt.RadialGradientPaint
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import kotlin.math.hypot
import kotlin.math.max

/** Draws an [Icon] into Java2D with everything a board symbol carries: colours, opacity, widths, caps, dashes, gradients, texts. */
object Java2DIcons {
    private class Sink(val path: Path2D.Float) : PathSink {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.curveTo(x1, y1, x2, y2, x, y)
        override fun close() = path.closePath()
    }

    /** Draws [icon] fitted and centred in the [side] square at ([x], [y]); paths without a colour take [tint]. */
    fun draw(g0: Graphics2D, icon: Icon, x: Float, y: Float, side: Float, tint: Color) {
        for (s in icon.shadows) shadow(g0, icon, x, y, side, s)
        shape(g0, icon, x, y, side, tint)
    }

    /** A drop shadow: the symbol's silhouette in the shadow's colour, moved and blurred (a box blur run three times). */
    private fun shadow(g0: Graphics2D, icon: Icon, x: Float, y: Float, side: Float, s: IconShadow) {
        val scale = side / max(icon.viewBox.width, icon.viewBox.height)
        val blur = (s.blur * scale / 2f).toInt().coerceAtLeast(0)
        val pad = blur * 3 + 2
        val size = side.toInt() + 2 * pad
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        val c = Color(s.colour.toInt(), true)
        shape(g, icon.copy(texts = emptyList(), paths = icon.paths.map { it.copy(colour = s.colour or 0xFF000000L, gradient = null) }), pad.toFloat(), pad.toFloat(), side, c)
        g.dispose()
        val alpha = IntArray(size * size) { (img.getRGB(it % size, it / size) ushr 24) }
        if (blur > 0) repeat(3) { boxBlur(alpha, size, blur) }
        val a0 = c.alpha
        for (i in alpha.indices) img.setRGB(i % size, i / size, ((alpha[i] * a0 / 255) shl 24) or (c.rgb and 0xFFFFFF))
        g0.drawImage(img, (x - pad + s.dx * scale).toInt(), (y - pad + s.dy * scale).toInt(), null)
    }

    private fun boxBlur(a: IntArray, n: Int, r: Int) {
        val tmp = IntArray(a.size)
        for (y in 0 until n) for (x in 0 until n) {
            var sum = 0
            for (k in -r..r) sum += a[y * n + (x + k).coerceIn(0, n - 1)]
            tmp[y * n + x] = sum / (2 * r + 1)
        }
        for (y in 0 until n) for (x in 0 until n) {
            var sum = 0
            for (k in -r..r) sum += tmp[(y + k).coerceIn(0, n - 1) * n + x]
            a[y * n + x] = sum / (2 * r + 1)
        }
    }

    private fun shape(g0: Graphics2D, icon: Icon, x: Float, y: Float, side: Float, tint: Color) {
        val vb = icon.viewBox
        val scale = side / max(vb.width, vb.height)
        val g = g0.create() as Graphics2D
        g.translate((x + (side - vb.width * scale) / 2f - vb.x * scale).toDouble(), (y + (side - vb.height * scale) / 2f - vb.y * scale).toDouble())
        g.scale(scale.toDouble(), scale.toDouble())
        for (p in icon.paths) {
            val path = Path2D.Float(if (p.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
            p.commands.replay(Sink(path))
            g.paint = paintOf(p, tint)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, p.opacity.coerceIn(0f, 1f))
            // a path marked neither stroke nor fill follows SVG's default: filled
            if (p.fill || !p.stroke) g.fill(path)
            if (p.stroke) {
                val w = p.strokeWidth ?: icon.strokeWidth
                val dash = p.dash?.toFloatArray()
                g.stroke = BasicStroke(
                    w,
                    when (p.cap) { LineCap.BUTT -> BasicStroke.CAP_BUTT; LineCap.ROUND -> BasicStroke.CAP_ROUND; LineCap.SQUARE -> BasicStroke.CAP_SQUARE },
                    when (p.join) { LineJoin.MITER -> BasicStroke.JOIN_MITER; LineJoin.ROUND -> BasicStroke.JOIN_ROUND; LineJoin.BEVEL -> BasicStroke.JOIN_BEVEL },
                    4f, dash, 0f,
                )
                g.draw(path)
            }
        }
        for (t in icon.texts) {
            val style = (if (t.weight >= 600) Font.BOLD else Font.PLAIN) or (if (t.italic) Font.ITALIC else 0)
            val family = when (t.family) { "serif" -> Font.SERIF; "mono" -> Font.MONOSPACED; else -> Font.SANS_SERIF }
            val font = Font(family, style, 1).deriveFont(t.size)
            g.font = font
            g.color = colour(t.colour, tint)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, t.opacity.coerceIn(0f, 1f))
            val width = g.getFontMetrics(font).stringWidth(t.text).toFloat() + t.letterSpacing * (t.text.length - 1)
            val dx = when (t.anchor) { TextAnchor.START -> 0f; TextAnchor.MIDDLE -> -width / 2f; TextAnchor.END -> -width }
            val saved = g.transform
            if (t.rotation != 0f) g.rotate(Math.toRadians(t.rotation.toDouble()), t.x.toDouble(), t.y.toDouble())
            g.drawString(t.text, t.x + dx, t.y)
            g.transform = saved
        }
        g.dispose()
    }

    private fun colour(argb: Long?, tint: Color): Color = argb?.let { Color(it.toInt(), true) } ?: tint

    private fun paintOf(p: IconPath, tint: Color): Paint {
        val stops = p.gradient ?: return colour(p.colour, tint)
        val fractions = FloatArray(stops.size)
        val colours = Array(stops.size) { i -> Color(stops[i].second.toInt(), true) }
        for (i in stops.indices) {
            // Java2D wants strictly increasing fractions; nudge equal stops apart.
            fractions[i] = if (i > 0 && stops[i].first <= fractions[i - 1]) minOf(1f, fractions[i - 1] + 1e-4f) else stops[i].first.coerceIn(0f, 1f)
        }
        if ((1 until fractions.size).any { fractions[it] <= fractions[it - 1] }) return colours[colours.size / 2]
        return if (p.radial) {
            val r = hypot(p.gx2 - p.gx1, p.gy2 - p.gy1).coerceAtLeast(1e-3f)
            RadialGradientPaint(Point2D.Float(p.gx1, p.gy1), r, fractions, colours)
        } else {
            if (p.gx1 == p.gx2 && p.gy1 == p.gy2) return colours.last()
            LinearGradientPaint(
                Point2D.Float(p.gx1, p.gy1), Point2D.Float(p.gx2, p.gy2), fractions, colours,
                MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, AffineTransform(),
            )
        }
    }
}
