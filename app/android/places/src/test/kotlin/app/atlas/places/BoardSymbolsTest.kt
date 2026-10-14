package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The boards' own symbols: the bundled set loads, its ids and aliases resolve, every symbol puts ink on the page, and
 * the whole set draws to contact sheets laid out like the catalogue's (cache/look-ref/symbols/page1–3), side by side
 * with them when the catalogue is on disk.
 */
class BoardSymbolsTest {
    private val all = BoardSymbols.all()

    @Test
    fun theSetLoadsWithUniqueIds() {
        assertTrue("board symbols: ${all.size}", all.size >= 700)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(all.size, all.map { it.hash }.toSet().size)
        assertEquals(all.map { it.n }, all.map { it.n }.sorted())
        for (s in all) assertTrue(s.id, s.icon.paths.isNotEmpty() || s.icon.texts.isNotEmpty())
        for (s in all) assertTrue(s.id, Regex("[a-z0-9]+(\\.[a-z0-9-]+)+").matches(s.id))
    }

    @Test
    fun everyPathParses() {
        for (s in all) for (p in s.icon.paths) {
            assertTrue("${s.id}: ${p.d.take(40)}", p.commands.size > 0)
            if (p.stroke) assertNotNull(s.id, p.strokeWidth)
            p.dash?.let { d -> assertTrue(s.id, d.size % 2 == 0 && d.all { it >= 0f }) }
            p.gradient?.let { g -> assertTrue(s.id, g.size >= 2 && g.zipWithNext().all { (a, b) -> b.first >= a.first }) }
        }
    }

    @Test
    fun aliasesResolveToBoardSymbols() {
        val aliases = BoardSymbols.aliases()
        assertTrue(aliases.isNotEmpty())
        for ((old, id) in aliases) {
            assertSame(old, BoardSymbols.resolve(id), BoardSymbols.resolve(old))
            assertTrue(old, BoardSymbols.resolve(id)!!.aliases.contains(old))
            assertTrue(old, Symbols.isBoard(old))
            assertSame(old, Symbols.icon(old), BoardSymbols.icon(id))
            assertEquals(id, Symbols.boardId(old))
        }
        assertNull(BoardSymbols.resolve("ui.no-such-symbol"))
        assertFalse(Symbols.isBoard("ui-no-such-symbol"))
    }

    @Test
    fun boardSymbolsWinOverTheOpenSets() {
        val search = Symbols.icon("ui-search")!!
        assertEquals("ui.search", search.id)
        assertNotNull(Symbols.original("ui-search"))
        assertTrue(Symbols.original("ui-search") !== search)
        // an id the boards do not draw still comes from the open sets
        val sandwich = Symbols.icon("pinhead-sub_sandwich")!!
        assertEquals("pinhead-sub_sandwich", sandwich.id)
        assertFalse(Symbols.isBoard("pinhead-sub_sandwich"))
    }

    @Test
    fun colouredSymbolsKeepTheirColours() {
        val coloured = all.filter { it.icon.coloured }
        assertTrue(coloured.size > 100)
        for (s in coloured) assertTrue(s.id, s.icon.paths.any { it.colour != null || it.gradient != null } || s.icon.texts.any { it.colour != null })
        assertTrue(all.any { s -> s.icon.paths.any { it.gradient != null && !it.radial } })
        assertTrue(all.any { s -> s.icon.paths.any { it.gradient != null && it.radial } })
        assertTrue(all.any { s -> s.icon.paths.any { it.dash != null } })
        assertTrue(all.any { s -> s.icon.texts.isNotEmpty() })
    }

    @Test
    fun everySymbolPutsInkOnThePage() {
        val blank = all.filter { s -> ink(s.icon).first < 4 }.map { "${it.n} ${it.id}" }
        assertTrue("board symbols that draw nothing: $blank", blank.isEmpty())
    }

    /** (inked pixels, mean lightness of the ink 0..1) of [icon] drawn at 48 px on transparent. */
    private fun ink(icon: Icon, tint: Color = INK): Pair<Int, Double> {
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        Java2DIcons.draw(g, icon, 0f, 0f, 48f, tint)
        g.dispose()
        var n = 0
        var light = 0.0
        for (y in 0 until 48) for (x in 0 until 48) {
            val c = img.getRGB(x, y)
            if (c ushr 24 > 64) {
                n++
                light += ((c shr 16 and 255) * 0.299 + (c shr 8 and 255) * 0.587 + (c and 255) * 0.114) / 255.0
            }
        }
        return n to if (n == 0) 0.0 else light / n
    }

    @Test
    fun contactSheets() {
        val out = File(System.getProperty("atlas.out") ?: System.getProperty("java.io.tmpdir"))
        val byN = all.associateBy { it.n }
        val last = maxOf(all.maxOf { it.n }, 1)
        val pages = (last + PER_PAGE - 1) / PER_PAGE
        val repo = System.getProperty("atlas.repo")?.let(::File)
        for (page in 0 until pages) {
            val img = sheet(page, byN)
            val f = File(out, "board-sheet-${page + 1}.png")
            ImageIO.write(img, "png", f)
            assertTrue(f.length() > 10_000)
            val ref = repo?.let { File(it, "cache/look-ref/symbols/page${page + 1}.html.png") }
            if (ref != null && ref.isFile) {
                val r = ImageIO.read(ref)
                val both = BufferedImage(r.width + img.width + 16, maxOf(r.height, img.height), BufferedImage.TYPE_INT_RGB)
                val g = both.createGraphics()
                g.color = Color(0x2A2C32)
                g.fillRect(0, 0, both.width, both.height)
                g.drawImage(r, 0, 0, null)
                g.drawImage(img, r.width + 16, 0, null)
                g.dispose()
                ImageIO.write(both, "png", File(out, "board-compare-${page + 1}.png"))
            }
        }
        println("board sheets: $out (${all.size} symbols on $pages pages)")
    }

    /** One page laid out like the catalogue's (20 × 19 cells of 51 × 53 at twice the size), numbered the same. */
    private fun sheet(page: Int, byN: Map<Int, BoardSymbol>): BufferedImage {
        val k = 2
        val img = BufferedImage(1024 * k, 1024 * k, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 9 * k)
        for (i in 0 until PER_PAGE) {
            val n = page * PER_PAGE + i + 1
            val cx = (i % COLS) * 51 * k
            val cy = (i / COLS) * 53 * k
            val s = byN[n]
            g.color = Color(0xEEEEEE)
            g.drawRect(cx, cy, 51 * k - 1, 53 * k - 1)
            if (s != null && ink(s.icon, s.hue?.let { Color(it.toInt(), true) } ?: INK).second > 0.86) {
                // a white symbol: on a dark cell, so it shows
                g.color = Color(0x4A4F5A)
                g.fillRect(cx + 1, cy + 1, 51 * k - 2, 38 * k)
            }
            if (s != null) Java2DIcons.draw(g, s.icon, cx + (51 - 34) / 2f * k, cy + 2f * k, 34f * k, s.hue?.let { Color(it.toInt(), true) } ?: INK)
            g.color = if (s == null) Color(0xD0D0D0) else Color(0x888888)
            val label = n.toString()
            g.drawString(label, cx + (51 * k - g.fontMetrics.stringWidth(label)) / 2, cy + 49 * k)
        }
        g.dispose()
        return img
    }

    private companion object {
        /** The catalogue's sheets draw currentColor in this ink. */
        val INK = Color(0x14161B)
        const val COLS = 20
        const val PER_PAGE = 380
    }
}
