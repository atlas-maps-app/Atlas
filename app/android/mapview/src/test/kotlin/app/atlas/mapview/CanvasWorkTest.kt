package app.atlas.mapview

import app.atlas.geo.LatLon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canvas draws nothing over the core's picture only where everything over the map is the core's (ENGINE.md gain
 * 12): idle with the core's routes, areas, labels, markers and puck; drawing again with today's map over the core's
 * picture, with routes, areas, labels, markers or a puck the canvas still draws, with the tape, the debug panel or the
 * honest state, and where today's map is the path wanted.
 */
class CanvasWorkTest {
    private val at = LatLon(52.37, 4.88)
    private val full = MapOverlays(
        markers = listOf(Marker("m", at, "cafe")),
        labels = listOf(MapLabel("l", "Jordaan", LabelKind.AREA, listOf(at))),
        location = UserLocation(at, 10.0),
    )

    private fun idle(
        overlays: MapOverlays = full, debug: Boolean = false, tape: Boolean = false, honest: Boolean = false,
        core: Boolean = true, cover: Float = 0f, routes: Boolean = false, labels: Boolean = true, markers: Boolean = true,
        location: Boolean = true,
    ) = CanvasWork.idle(debug, tape, honest, core, cover, overlays, routes, labels, markers, location)

    @Test
    fun idleWhereEverythingOverTheMapIsTheCores() {
        assertTrue(idle())
        // nothing over the map at all; and what the core does not draw where there is none of it
        assertTrue(idle(MapOverlays(), labels = false, markers = false, location = false))
        assertTrue(idle(full.copy(labels = emptyList()), labels = false))
        // a route and an area the core draws
        assertTrue(idle(full.copy(routes = listOf(RouteLine("r", listOf(at, LatLon(52.38, 4.89)))), areas = listOf(AreaOverlay("a", at, 200.0))), routes = true))
    }

    @Test
    fun drawingWhereTheCanvasDrawsSomething() {
        assertFalse("today's map over the core's picture", idle(cover = 0.3f))
        assertFalse("today's map wanted", idle(core = false))
        assertFalse("the canvas's route", idle(full.copy(routes = listOf(RouteLine("r", listOf(at, LatLon(52.38, 4.89)))))))
        assertFalse("the canvas's area", idle(full.copy(areas = listOf(AreaOverlay("a", at, 200.0)))))
        assertFalse("the canvas's labels", idle(labels = false))
        assertFalse("the canvas's markers", idle(markers = false))
        assertFalse("the canvas's puck", idle(location = false))
        assertFalse("the tape", idle(tape = true))
        assertFalse("the debug panel", idle(debug = true))
        assertFalse("the honest state", idle(honest = true))
    }
}
