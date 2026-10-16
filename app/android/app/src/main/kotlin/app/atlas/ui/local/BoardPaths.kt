package app.atlas.ui.local

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/** The boards' own inline SVG path data (the hand-drawn ring, the way-ahead chevron), parsed for a Canvas. */
internal object BoardPaths {
    fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
}
