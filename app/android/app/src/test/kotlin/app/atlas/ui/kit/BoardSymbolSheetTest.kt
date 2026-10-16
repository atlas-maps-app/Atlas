package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.places.BoardSymbol
import app.atlas.places.BoardSymbols
import app.atlas.testing.shot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws the boards' symbols through the kit's [Symbol]: the whole set on one sheet, and three pages laid out like the
 * catalogue's contact sheets (cache/look-ref/symbols/page1–3.html.png: 20 × 19 cells of 51 × 53, numbered the same) put
 * side by side with them when the catalogue is on disk. Every symbol must put ink in its cell.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardSymbolSheetTest {
    // the catalogue only: a local board_art.json adds the boards' drawings (art.*), which are not icons
    private val all = BoardSymbols.all().filterNot { it.id.startsWith("art.") }
    private val byN = all.associateBy { it.n }

    /** The colour a symbol is drawn in on the sheets: its board hue, else the catalogue's ink for currentColor. */
    private fun tintOf(s: BoardSymbol) = s.hue?.let { Color(it.toInt()) } ?: INK

    /** True for a symbol whose largest shape and at least half its paths are light: the sheets put it on a dark cell. */
    private fun light(s: BoardSymbol): Boolean {
        val icon = s.icon
        fun lum(p: app.atlas.places.IconPath) =
            (p.gradient?.let { g -> Color(g[g.size / 2].second.toInt()) } ?: p.colour?.let { Color(it.toInt()) } ?: tintOf(s)).luminance()
        val biggest = icon.paths.maxByOrNull { p -> p.commands.bounds()?.let { (it[2] - it[0]) * (it[3] - it[1]) } ?: 0f } ?: return false
        return lum(biggest) > 0.8f && icon.paths.count { lum(it) > 0.8f } * 2 >= icon.paths.size
    }

    @Composable
    private fun Cell(s: BoardSymbol?, n: Int, width: Int, height: Int, side: Int) {
        Box(Modifier.size(width.dp, height.dp).border(0.5.dp, Color(0xFFEEEEEE))) {
            if (s != null) {
                if (light(s)) Box(Modifier.size(width.dp, (side + 4).dp).background(Color(0xFF4A4F5A)))
                Symbol(s.id, Modifier.offset(((width - side) / 2f).dp, 2.dp), tint = tintOf(s), size = side.dp)
            }
            BasicText(
                n.toString(),
                Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                style = TextStyle(fontSize = 9.sp, color = if (s == null) Color(0xFFD0D0D0) else Color(0xFF888888)),
            )
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h1260dp-xhdpi")
    fun theWholeSetOnOneSheet() {
        val cols = 32
        val f = shot("kit_board_symbols") {
            Box(Modifier.size(1280.dp, 1260.dp).background(Color.White)) {
                all.forEachIndexed { i, s ->
                    Box(Modifier.offset((i % cols * 40).dp, (i / cols * 46).dp)) { Cell(s, s.n, 40, 46, 28) }
                }
            }
        }
        assertTrue("$f was not written", f.isFile && f.length() > 100_000)
        val img = ImageIO.read(f)
        val k = img.width / 1280f
        val blank = all.withIndex().filter { (i, _) ->
            inked(img, ((i % cols * 40 + 6) * k).toInt(), ((i / cols * 46 + 2) * k).toInt(), (28 * k).toInt()) < 6
        }.map { it.value.id }
        assertTrue("symbols that drew nothing: $blank", blank.isEmpty())
        println("whole board set: $f (${all.size} symbols)")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1024dp-h1024dp-xhdpi")
    fun pagesLikeTheCatalogue() {
        val repo = System.getProperty("atlas.repo")?.let(::File)
        val pages = (all.maxOf { it.n } + PER_PAGE - 1) / PER_PAGE
        for (page in 0 until pages) {
            val f = shot("kit_board_symbols_page${page + 1}") {
                Box(Modifier.size(1024.dp).background(Color.White)) {
                    for (i in 0 until PER_PAGE) {
                        val n = page * PER_PAGE + i + 1
                        Box(Modifier.offset((i % COLS * 51).dp, (i / COLS * 53).dp)) { Cell(byN[n], n, 51, 53, 34) }
                    }
                }
            }
            assertTrue("$f was not written", f.isFile && f.length() > 50_000)
            val ours = ImageIO.read(f)
            val k = ours.width / 1024f
            val blank = (0 until PER_PAGE).mapNotNull { i -> byN[page * PER_PAGE + i + 1]?.let { i to it } }.filter { (i, _) ->
                inked(ours, ((i % COLS * 51 + 8.5f) * k).toInt(), ((i / COLS * 53 + 2) * k).toInt(), (34 * k).toInt()) < 6
            }.map { it.second.id }
            assertTrue("page ${page + 1}: symbols that drew nothing: $blank", blank.isEmpty())
            val ref = repo?.let { File(it, "cache/look-ref/symbols/page${page + 1}.html.png") }
            if (ref != null && ref.isFile) {
                val r = ImageIO.read(ref)
                val both = BufferedImage(r.width + ours.width + 16, maxOf(r.height, ours.height), BufferedImage.TYPE_INT_RGB)
                val g = both.createGraphics()
                g.color = java.awt.Color(0x2A2C32)
                g.fillRect(0, 0, both.width, both.height)
                g.drawImage(r, 0, 0, null)
                g.drawImage(ours, r.width + 16, 0, null)
                g.dispose()
                ImageIO.write(both, "png", File(f.parentFile, "kit_board_symbols_compare${page + 1}.png"))
            }
        }
    }

    /** Pixels in the [side] square at ([x], [y]) that differ clearly from the square's corner (its background). */
    private fun inked(img: BufferedImage, x: Int, y: Int, side: Int): Int {
        val bg = img.getRGB(x.coerceIn(0, img.width - 1), y.coerceIn(0, img.height - 1))
        var n = 0
        for (yy in y until minOf(y + side, img.height)) for (xx in x until minOf(x + side, img.width)) {
            val c = img.getRGB(xx, yy)
            val d = Math.abs((c shr 16 and 255) - (bg shr 16 and 255)) + Math.abs((c shr 8 and 255) - (bg shr 8 and 255)) + Math.abs((c and 255) - (bg and 255))
            if (d > 60) n++
        }
        return n
    }

    private companion object {
        /** The catalogue's sheets draw currentColor in this ink. */
        val INK = Color(0xFF14161B)
        const val COLS = 20
        const val PER_PAGE = 380
    }
}
