package app.atlas.mapview

import app.atlas.mapview.svg.SvgFonts
import java.io.File

/** The repository and the bundled typefaces, for the tests that shape and draw the map's text. */
object EngineTestKit {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile

    val fonts: SvgFonts by lazy { SvgFonts.fromDirectory(File(repo, "app/android/mapview/src/main/assets/svgfonts")) }
}
