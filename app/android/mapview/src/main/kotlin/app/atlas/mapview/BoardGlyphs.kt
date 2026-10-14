package app.atlas.mapview

import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.ViewBox

/**
 * The glyphs of the Main board's own markers, taken as they are from its source (`canvas/project/Main.dc.html`, 24-unit
 * grids): the booked table's knife and fork with its check badge and route chip, the museum, the newest café's
 * sparkle, the coffee bean of a café chain's tile, the walk on a friend's chip, the tram stop and the charger's bolt.
 * Circles, ellipses and rectangles of the source are written as the same shapes in path data.
 */
object BoardGlyphs {
    const val KNIFE_FORK = "main-knife-fork"
    const val CHECK = "main-check"
    const val ROUTE = "main-route"
    const val MUSEUM = "main-museum"
    const val SPARKLE = "main-sparkle"
    const val COFFEE_BEAN = "main-coffee-bean"
    const val WALK = "main-walk"
    const val TRAM = "main-tram"
    const val BOLT = "main-bolt"

    private val grid = ViewBox(0f, 0f, 24f, 24f)

    private fun strokes(id: String, width: Float, vararg d: String) =
        Icon(id, grid, d.map { IconPath(it, stroke = true, fill = false, strokeWidth = width) })

    private fun fills(id: String, vararg d: String) = Icon(id, grid, d.map { IconPath(it, stroke = false, fill = true) })

    private val all: Map<String, Icon> = listOf(
        // The booked table's pin: stroke 2 in its 18 px glyph.
        strokes(KNIFE_FORK, 2f, "M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10", "M17 21V3c-2 1-3 3.5-3 7h3"),
        // Its badge: stroke 3.4, round caps and joins.
        strokes(CHECK, 3.4f, "M5 12.5l4.5 4.5L19 7.5"),
        // The callout's "go" chip: stroke 2.4.
        strokes(ROUTE, 2.4f, "M6 21v-8a4 4 0 0 1 4-4h9", "M15 5l4 4-4 4"),
        // The Anne Frank Huis pin: stroke 2.2.
        strokes(MUSEUM, 2.2f, "M3 9.5L12 4l9 5.5", "M5.5 10.5v7M10 10.5v7M14 10.5v7M18.5 10.5v7M3.5 20.5h17"),
        // The newest café's sparkle, filled.
        fills(SPARKLE, "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"),
        // The friend's walk: a head (circle 13, 4.5, r 1.7) and the walking figure, stroke 2.4.
        strokes(WALK, 2.4f, "M11.3 4.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "M10 21l2.2-6.5L10 12l1-4.5 3.2 2.5 3 .8M12.2 14.5L15 21"),
        // The tram stop: pantograph, body (rect 6, 6, 12 × 11, rx 2.5) and wheels, stroke 2.1.
        strokes(
            TRAM, 2.1f, "M8 3h8M12 3v3",
            "M8.5 6h7a2.5 2.5 0 0 1 2.5 2.5v6a2.5 2.5 0 0 1 -2.5 2.5h-7a2.5 2.5 0 0 1 -2.5 -2.5v-6a2.5 2.5 0 0 1 2.5 -2.5z",
            "M6 12h12M9 17l-2 4M15 17l2 4",
        ),
        // The charger chip's bolt, filled.
        fills(BOLT, "M13 3L5 13h6l-1 8 8-10h-6l1-8z"),
        // The café chain tile's bean: an ellipse (12, 12; 6 × 8, turned 30°) in crema and its seam in the tile's brown.
        Icon(
            COFFEE_BEAN, grid,
            listOf(
                IconPath("M17.196 15A6 8 30 1 0 6.804 9A6 8 30 1 0 17.196 15Z", stroke = false, fill = true, colour = 0xFFE9C9A3),
                IconPath("M9 6.5c3 3 3 8 6 11", stroke = true, fill = false, colour = 0xFF3B2A20, strokeWidth = 1.6f),
            ),
        ),
    ).associateBy { it.id }

    /** Whether [id] is one of these glyphs. */
    fun has(id: String): Boolean = id in all

    /** The glyph [id], or null. */
    fun icon(id: String): Icon? = all[id]

    /** Every glyph id. */
    val ids: Set<String> get() = all.keys
}
