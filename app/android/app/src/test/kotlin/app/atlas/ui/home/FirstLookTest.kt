package app.atlas.ui.home

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraOps
import app.atlas.mapview.HomeView
import app.atlas.mapview.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The home's first look: the Main board's view where it shows you, the same view around you anywhere else. */
class FirstLookTest {
    private val board = Viewport(412, 915, 1f, offsetY = -17.5)

    @Test
    fun theBoardsViewWhenItShowsYouOrNothingIsKnown() {
        assertEquals(HomeView.camera, FirstLook.around(null))
        assertEquals(HomeView.camera, FirstLook.around(Services.WESTERKERK))
        // The Westerkerk sits where the board draws you, near the dot at 230 × 628 dp.
        val p = CameraOps.camera(HomeView.camera, board).project(Services.WESTERKERK)!!
        assertEquals(FirstLook.YOU_X, p.x, 40f)
        assertEquals(FirstLook.YOU_Y, p.y, 40f)
    }

    @Test
    fun elsewhereYouStandWhereTheBoardStandsYou() {
        val utrecht = LatLon(52.0907, 5.1214)
        val c = FirstLook.around(utrecht)
        assertEquals(HomeView.ZOOM, c.zoom, 1e-9)
        assertEquals(HomeView.BEARING, c.bearing, 1e-9)
        assertEquals(HomeView.PITCH, c.pitch, 1e-9)
        val p = CameraOps.camera(c, board).project(utrecht)!!
        assertEquals(FirstLook.YOU_X, p.x, 0.5f)
        assertEquals(FirstLook.YOU_Y, p.y, 0.5f)
        // A place in the board's view but behind the pills (Centraal, at the top) moves too.
        val centraal = LatLon(52.37903, 4.90010)
        val q = CameraOps.camera(FirstLook.around(centraal), board).project(centraal)!!
        assertEquals(FirstLook.YOU_Y, q.y, 0.5f)
    }

    @Test
    fun withNoDataWhereYouAreTheBoardsViewOfTheCanalRing() {
        // Q5 (plan-vector §8): where the map has nothing to draw, the Main board's view the app carries.
        val utrecht = LatLon(52.0907, 5.1214)
        assertEquals(HomeView.camera, FirstLook.around(utrecht) { false })
        // Covered, as before; in the board's view, the board's view whatever is covered.
        assertEquals(FirstLook.around(utrecht), FirstLook.around(utrecht) { true })
        assertEquals(HomeView.camera, FirstLook.around(Services.WESTERKERK) { false })
    }
}
