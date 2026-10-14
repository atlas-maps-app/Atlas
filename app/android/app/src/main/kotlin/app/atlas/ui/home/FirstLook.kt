package app.atlas.ui.home

import app.atlas.geo.LatLon
import app.atlas.mapview.CameraOps
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.HomeView
import app.atlas.mapview.Viewport

/**
 * The home's first look when nothing says where to look: the Main board's view ([HomeView]: 3D, facing 52°, the
 * canal ring ahead). When you stand inside that view, low enough to show under the top pills, it is the board's view
 * exactly; anywhere else the same view moves so that you stand where the board stands you, at the Westermarkt's dot
 * 230 × 628 dp on its 412 × 915 dp screen.
 */
object FirstLook {
    /** The board's screen in dp, its principal point 440 dp down (the home's content padding). */
    private val BOARD = Viewport(412, 915, 1f, offsetY = -(915.0 - 2 * 440.0) / 2)

    /** Where the board draws you. */
    const val YOU_X = 230f
    const val YOU_Y = 628f

    /** The part of the board's screen where a location counts as in its view: clear of the pills and the bottom row. */
    private const val LEFT = 24f
    private const val RIGHT = 388f
    private const val TOP = 300f
    private const val BOTTOM = 700f

    fun around(you: LatLon?): CameraPosition {
        val board = HomeView.camera
        if (you == null) return board
        val p = CameraOps.camera(board, BOARD).project(you)
        if (p != null && p.x in LEFT..RIGHT && p.y in TOP..BOTTOM) return board
        return CameraOps.place(board, BOARD, you, YOU_X, YOU_Y)
    }
}
