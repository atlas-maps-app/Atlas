package app.atlas.mapview.svg

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The engine's real renders drawn by [SvgCanvas] at 2× and compared with WebKit's rasterisation of the same files
 * (made by `qlmanage`, in `cache/svg-ref`). Both live in the git-ignored cache, so this runs on the owner's Mac only;
 * it writes side-by-side pictures and a report to `cache/svg-android/compare`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgReferenceTest {
    private val refs = File(SvgCompare.cache, "svg-ref")

    private fun svgFor(name: String): File? =
        listOf(File(SvgCompare.cache, "render/$name.svg"), File(SvgCompare.cache, "blob/$name.svg")).firstOrNull { it.isFile }

    /** `-Datlas.svg.file=<path>` renders one SVG to `cache/svg-android/compare/one.png`, for looking at a case. */
    @Test
    fun rendersOneFile() {
        val path = System.getProperty("atlas.svg.file")
        assumeTrue(path != null)
        val mode = System.getProperty("atlas.svg.mode")?.let { SvgCanvas.FilterMode.valueOf(it) } ?: SvgCanvas.FilterMode.CPU
        val (bmp, canvas) = SvgCompare.render(File(path!!), mode = mode)
        SvgCompare.save(bmp, File(SvgCompare.out, "one.png"))
        println("drew $path: shapes ${canvas.stats.shapes} layers ${canvas.stats.layers} filtered ${canvas.stats.filtered}")
    }

    /**
     * Text against Safari twice: once as an image (no Geist on this Mac: Helvetica stands in), once inline with Geist
     * loaded as a web font. Georgia is drawn with Gelasio, its metric-compatible stand-in.
     */
    @Test
    fun textMatchesTheBrowser() {
        val dir = File(SvgCompare.cache, "svg-ref/text")
        assumeTrue(File(dir, "text-geist.png").isFile)
        for (name in listOf("text-img", "text-geist")) {
            val (bmp, _) = SvgCompare.render(File(dir, "$name.svg"))
            val ref = SvgCompare.load(File(dir, "$name.png"))
            val r = SvgCompare.compare(name, bmp, ref, tile = 32)
            SvgCompare.sideBySide(name, bmp, ref)
            println(r)
        }
    }

    /** Parts of renders cut out for looking at one divergence at a time (`cache/svg-ref/cut/<name>.svg` and `.png`). */
    @Test
    fun cutsMatchTheBrowser() {
        val dir = File(SvgCompare.cache, "svg-ref/cut")
        val svgs = dir.listFiles { f -> f.name.endsWith(".svg") && File(dir, f.name.removeSuffix(".svg") + ".png").isFile }.orEmpty()
        assumeTrue(svgs.isNotEmpty())
        for (svg in svgs.sortedBy { it.name }) {
            val name = svg.name.removeSuffix(".svg")
            val (bmp, _) = SvgCompare.render(svg)
            val ref = SvgCompare.load(File(dir, "$name.png"))
            val r = SvgCompare.compare("cut-$name", bmp, ref)
            SvgCompare.sideBySide(r.name, bmp, ref)
            println(r)
        }
    }

    @Test
    fun rendersMatchTheBrowser() {
        val only = System.getProperty("atlas.svg.only")
        val pngs = refs.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
            .filter { only == null || it.name.startsWith(only) }
        assumeTrue("no reference rasterisations in $refs", pngs.isNotEmpty())
        val lines = ArrayList<String>()
        for (png in pngs) {
            val name = png.name.removeSuffix(".png")
            val svg = svgFor(name) ?: continue
            val t0 = System.nanoTime()
            val doc = SvgParser.parse(svg)
            val t1 = System.nanoTime()
            val mode = System.getProperty("atlas.svg.mode")?.let { SvgCanvas.FilterMode.valueOf(it) } ?: SvgCanvas.FilterMode.CPU
            val (bmp, canvas) = SvgCompare.render(svg, mode = mode)
            val t2 = System.nanoTime()
            val result = SvgCompare.compare(name, bmp, SvgCompare.load(png))
            SvgCompare.sideBySide(name, bmp, SvgCompare.load(png))
            val s = canvas.stats
            val line = "%s  parse %4d ms  draw %5d ms  shapes %d texts %d layers %d filtered %d (%d px, %d ms, source %d ms) tiles %d  (%d KB)".format(
                result, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, s.shapes, s.texts, s.layers, s.filtered, s.filterPixels, s.filterNanos / 1_000_000, s.sourceNanos / 1_000_000,
                s.patternTiles, svg.length() / 1024,
            )
            println(line)
            lines.add(line)
            check(doc.drawables > 0)
        }
        File(SvgCompare.out, if (only == null) "report.txt" else "report-$only.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
