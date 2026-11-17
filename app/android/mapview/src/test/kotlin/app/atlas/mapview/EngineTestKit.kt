package app.atlas.mapview

import app.atlas.mapview.svg.SvgFonts
import java.io.File

/** The repository and the bundled typefaces for the tests that draw the renders' SVG ([app.atlas.mapview.svg.SvgRaster]). */
object EngineTestKit {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile

    val fonts: SvgFonts by lazy { SvgFonts.fromDirectory(File(repo, "app/android/mapview/src/main/assets/svgfonts")) }
}
