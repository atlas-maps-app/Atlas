package app.atlas.mapview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.geo.BBox
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The map as the app shows it, through [AtlasMap] on the real fixture tiles, captured to PNG with Roborazzi: the Main
 * board's camera, top-down, night, markers, a route with the location puck, and the measuring tape.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val tiles = FixtureTiles()

    private fun capture(name: String): File {
        val f = File(Fixtures.shots, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 10_000)
        return f
    }

    private fun show(
        state: MapState,
        style: MapStyle = MapStyle(theme = Theme.DAY),
        overlays: MapOverlays = MapOverlays(),
        padding: PaddingValues = PaddingValues(),
        debug: Boolean = false,
        controls: Boolean = false,
        trees: Boolean = true,
    ) {
        val extras = if (trees && AmsterdamData.available) AmsterdamData.trees else null
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AtlasMap(
                    state, tiles, Modifier.fillMaxSize(), style, overlays, padding, debug = debug, units = UnitSystem.METRIC,
                    extras = extras?.let { e -> { _ -> e } },
                )
                if (controls) {
                    MapCompass(state, Modifier.align(Alignment.TopEnd).padding(16.dp))
                    ScaleBar(state, Modifier.align(Alignment.BottomStart).padding(16.dp), units = app.atlas.geo.UnitSystem.METRIC)
                }
            }
        }
        compose.settleMap(state)
    }

    private fun stateAt(camera: CameraPosition) = MapState(camera).apply { time = Fixtures.afternoon }

    @Test
    fun main() {
        val state = stateAt(Fixtures.main)
        // The board's framing: the Westerkerk low on the screen (the engine preview's principal point, 320 px down).
        show(state, padding = PaddingValues(top = 320.dp), controls = true)
        capture("map_main")
        val f = state.controller!!.loop.latest!!
        assertTrue(f.frame.stats.objectsDrawn > 500)
        assertNotNull(state.visibleBounds)
        assertTrue(Fixtures.westerkerk in state.visibleBounds!!)
    }

    @Test
    fun topDown() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 15.0))
        show(state, controls = true)
        capture("map_top_down")
    }

    @Test
    fun night() {
        val state = MapState(Fixtures.main).apply { time = Fixtures.night }
        show(state, style = MapStyle(theme = Theme.AUTO), padding = PaddingValues(top = 320.dp))
        capture("map_night")
        assertTrue(state.controller!!.loop.latest!!.input.look.night)
    }

    @Test
    fun markers() {
        val camera = CameraPosition(LatLon(52.3747, 4.8838), 17.0, 22.0, 50.0)
        val state = stateAt(camera)
        val v = Viewport(824, 1830, 2f)
        fun at(x: Float, y: Float) = CameraOps.groundAt(camera, v, x, y)!!
        val markers = listOf(
            Marker("belhamel", at(330f, 520f), "restaurant", 0xFF14161B, MarkerStyle.PIN, label = "De Belhamel", badge = "ui-check", ring = 0.7f, selected = true, priority = 10),
            Marker("museum", at(560f, 780f), "museum", 0xFF7B4FD6, MarkerStyle.PIN, value = "4′", priority = 5),
            Marker("late", at(290f, 1640f), "bar", 0xFFE07A1F, MarkerStyle.PIN, ring = 0.12f, badge = "ui-moon-star", priority = 5),
            Marker("ah", at(180f, 930f), "grocery", 0xFF2E9E5B, MarkerStyle.BRAND, logo = "albertheijn", priority = 4),
            Marker("coffee", at(660f, 1060f), "cafe", 0xFF5B3A29, MarkerStyle.BRAND, logo = "Starbucks", priority = 4),
            Marker("plain", at(700f, 1700f), "shop", 0xFF8E5BD0, MarkerStyle.BRAND, logo = "no-such-brand-x", label = "Mono", priority = 4),
            Marker("charge", at(420f, 1160f), "charging_station", 0xFF14161B, MarkerStyle.CHIP, value = "150", ring = 0.62f, selected = true, priority = 6),
            Marker("charge2", at(210f, 1310f), "charging_station", 0xFFF2A516, MarkerStyle.CHIP, value = "350 ~8′", ring = 0.3f, priority = 3),
            Marker("cafe", at(610f, 1430f), "cafe", 0xFF2E5BFF, MarkerStyle.CHIP, value = "4.8", selected = true, priority = 3),
            Marker("cluster", at(160f, 640f), "restaurant", 0xFFE07A1F, MarkerStyle.CLUSTER, count = 12, priority = 2),
            Marker("dot1", at(520f, 1600f), "bus", 0xFF2E5BFF, MarkerStyle.DOT, priority = 1),
            Marker("dot2", at(530f, 1606f), "bus", 0xFF2E5BFF, MarkerStyle.DOT),
            Marker("dot3", at(512f, 1594f), "bus", 0xFF2E5BFF, MarkerStyle.DOT),
            Marker("dot4", at(700f, 300f), "tram", 0xFF2E5BFF, MarkerStyle.DOT),
        )
        show(state, overlays = MapOverlays(markers = markers))
        capture("map_markers")
        val c = state.controller!!
        assertTrue(c.loop.latest != null)
        // Everything shows but two of the three bus stops, which fold into the first as a cluster of three.
        val layout = c.painter!!.layout
        assertEquals(markers.size - 2, layout.placedCount)
        val shown = (0 until layout.placedCount).associate { markers[layout.placed[it]].id to layout.clusterCount[it] }
        assertEquals(3, shown["dot1"])
        assertEquals(12, shown["cluster"])
        assertEquals(0, shown["belhamel"])
        // The selected pin is drawn last, on top of everything.
        assertTrue(markers[layout.placed[layout.placedCount - 1]].selected)
    }

    @Test
    fun routeAndPuck() {
        org.junit.Assume.assumeTrue(AmsterdamData.available)
        val from = LatLon(52.37445, 4.88330)
        val to = LatLon(52.37310, 4.89180)
        val main = AmsterdamData.route(from, to)
        val alternative = AmsterdamData.route(from, to, avoid = AmsterdamData.edges(main), penalty = 4.0)
        val walk = listOf(main.last()) + AmsterdamData.route(main.last(), LatLon(52.37300, 4.89340), walk = true).drop(1)
        assertTrue(main.size >= 5 && alternative.size >= 5 && walk.size >= 2)
        val length = main.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
        // The puck a third of the way along, facing along the route.
        var walked = 0.0
        var puck = main[0]
        var heading = 0.0
        for ((a, b) in main.zipWithNext()) {
            val d = Geo.distance(a, b)
            if (walked + d >= length / 3) {
                puck = Geo.interpolate(a, b, (length / 3 - walked) / d)
                heading = Geo.bearing(a, b)
                break
            }
            walked += d
        }
        val box = BBox.of(main + alternative + walk)!!
        val camera = CameraOps.fit(CameraPosition(box.center, 16.0, 75.0, 45.0), Viewport(824, 1830, 2f), box, floatArrayOf(60f, 240f, 60f, 360f))
        val state = stateAt(camera)
        val overlays = MapOverlays(
            routes = listOf(
                RouteLine("alt", alternative, alternative = true, label = "8′"),
                RouteLine("main", main, travelled = length / 3, label = "6′"),
                RouteLine("walk", walk, widthDp = 5f, dashed = true),
            ),
            location = UserLocation(puck, 18.0, heading = heading),
            areas = listOf(AreaOverlay("near", walk.last(), 70.0)),
            markers = listOf(Marker("dest", walk.last(), "restaurant", 0xFFE07A1F, MarkerStyle.PIN, selected = true, label = "Osteria")),
        )
        show(state, overlays = overlays)
        capture("map_route_puck")
        assertTrue(state.controller!!.loop.latest != null)
    }

    @Test
    fun measuringTape() {
        val state = stateAt(CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 35.0))
        show(state, overlays = MapOverlays(measure = true))
        // Two fingers come to rest on the map; after 400 ms the tape runs between them.
        compose.onRoot().performTouchInput {
            down(0, Offset(230f, 700f))
            down(1, Offset(620f, 1250f))
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertNotNull("the tape shows while the fingers rest", state.controller!!.tape)
        capture("map_measure")
        compose.onRoot().performTouchInput {
            up(0)
            up(1)
        }
    }

    @Test
    fun debugOverlay() {
        val state = stateAt(Fixtures.main)
        show(state, padding = PaddingValues(top = 320.dp), debug = true)
        capture("map_debug")
    }
}
