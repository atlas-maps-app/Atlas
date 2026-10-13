package app.atlas.places

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws symbols through [Icon.replay] into Java2D, the way the app draws them into an Android Path: every icon must
 * put ink on the page. Writes a contact sheet (icon-sheet.png in the test task's temporary folder) to look at.
 */
class IconSheetTest {
    private class Java2DSink(val path: Path2D.Float) : PathSink {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.curveTo(x1, y1, x2, y2, x, y)
        override fun close() = path.closePath()
    }

    private fun draw(g: java.awt.Graphics2D, icon: Icon, x: Float, y: Float, size: Float, colour: Color) {
        val scale = size / maxOf(icon.viewBox.width, icon.viewBox.height)
        for (p in icon.paths) {
            val path = Path2D.Float(if (p.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
            p.commands.replay(Java2DSink(path), scale, x - icon.viewBox.x * scale, y - icon.viewBox.y * scale)
            g.color = colour
            if (p.fill) g.fill(path)
            if (p.stroke) {
                g.stroke = BasicStroke(icon.strokeWidth * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(path)
            }
        }
    }

    private fun inked(icon: Icon): Boolean {
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        draw(g, icon, 4f, 4f, 40f, Color.BLACK)
        g.dispose()
        var n = 0
        for (y in 0 until 48) for (x in 0 until 48) if (img.getRGB(x, y) ushr 24 > 64) n++
        return n >= 6
    }

    @Test
    fun everyIconPutsInkOnThePage() {
        val blank = (Symbols.ids("symbols") + Symbols.ids("ui") + Symbols.ids("logos")).filter { !inked(Symbols.icon(it)!!) }
        assertTrue("icons that draw nothing: $blank", blank.isEmpty())
    }

    @Test
    fun contactSheet() {
        val ui = Symbols.ids("ui").sorted()
        val map = listOf(
            "maki-restaurant", "maki-cafe", "maki-bar", "maki-fast-food", "maki-grocery", "maki-bakery", "maki-pharmacy", "maki-lodging",
            "maki-museum", "maki-park", "maki-toilet", "maki-fuel", "pinhead-charging_station", "maki-bicycle-share", "maki-bus",
            "temaki-briefcase", "pinhead-pizza_slice", "pinhead-sushi", "pinhead-taco", "temaki-vending_machine", "maki-shop",
            "maki-defibrillator", "maki-swimming", "maki-theatre",
        )
        val logos = Symbols.ids("logos").sorted().take(48)
        val cell = 40
        val cols = 24
        val rows = (ui.size + cols - 1) / cols + (map.size + cols - 1) / cols + (logos.size + cols - 1) / cols + 1
        val img = BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0xF7F5F0)
        g.fillRect(0, 0, img.width, img.height)
        var i = 0
        fun place(ids: List<String>, colour: (String) -> Color) {
            for (id in ids) {
                draw(g, Symbols.icon(id)!!, (i % cols) * cell + 6f, (i / cols) * cell + 6f, cell - 12f, colour(id))
                i++
            }
            i = (i + cols - 1) / cols * cols
        }
        place(ui) { Color(0x1E1E1E) }
        place(map) { Color(Symbols.colourFor(mapOf("amenity" to "restaurant"))) }
        place(logos) { id -> Brands.all().firstOrNull { "logo-${it.logoSlug}" == id }?.colour?.let { Color(it) } ?: Color.DARK_GRAY }
        g.dispose()
        val out = File(System.getProperty("atlas.out") ?: System.getProperty("java.io.tmpdir"), "icon-sheet.png")
        ImageIO.write(img, "png", out)
        println("contact sheet: $out (${ui.size} UI symbols, ${map.size} map symbols, ${logos.size} logos)")
        assertTrue(out.length() > 10_000)
    }
}
