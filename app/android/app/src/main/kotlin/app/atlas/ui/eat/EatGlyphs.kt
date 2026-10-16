package app.atlas.ui.eat

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/**
 * The eat boards' drawings that are not in the board symbol set, their SVG path data as the boards draw them, with
 * each board's stroke width. Every icon of the boards is a board symbol, drawn by its id.
 */
object EatGlyphs {
    /** The engraved flourish over "La Carte" (the Carte board's 120 × 16 drawing). */
    val flourish = BoardGlyph(
        listOf("M0 8h44M76 8h44", "M60 2c-6 0-10 3-10 6s4 6 10 6 10-3 10-6-4-6-10-6z", "M44 8c4-4 6-4 10 0M66 8c4 4 6 4 10 0"),
        1f, viewBox = 120f, cap = StrokeCap.Butt, join = StrokeJoin.Miter,
    )

    /** The tick of a checked box, as the Market board's browser draws its checkboxes. */
    val tickBox = BoardGlyph(listOf("M5 12.5l4.5 4.5L19 7.5"), 2.6f)
}
