package app.atlas.mapview

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.ViewLadder
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The map as the app shows it: [AtlasMap] drawing the 1:1 engine's frames from Atlas's own region packs, captured to PNG
 * with Roborazzi at the boards' size (412 × 915 dp at density 2). The Westerkerk (the app's bundled pack) and the Main
 * board (the canal ring's pack, on the owner's machine) are compared pixel for pixel with the engine's own SVG drawn by
 * `SvgCanvas`; then the warp, night, markers, a route with the location puck, the tape, the tilt dial by day and by
 * night, the debug overlay and no data.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapScreenshotTest {
    val compose = createComposeRule()
    private val threads = MapFrameThreads()

    /** The compose rule's teardown closes the maps; their frame threads are waited for after it. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(threads).around(compose)

    @After
    fun unwire() = EngineTestKit.unwire()

    private fun capture(name: String): File {
        val f = File(Fixtures.shots, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 10_000)
        return f
    }

    private fun pixels(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun save(b: Bitmap, name: String) = FileOutputStream(File(Fixtures.shots, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun show(
        state: MapState,
        style: MapStyle = MapStyle(theme = Theme.DAY),
        overlays: MapOverlays = MapOverlays(),
        padding: PaddingValues = HomeView.padding(),
        debug: Boolean = false,
        controls: Boolean = false,
    ) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AtlasMap(state, Modifier.fillMaxSize(), style, overlays, padding, debug = debug, units = UnitSystem.METRIC)
                if (controls) {
                    MapCompass(state, Modifier.align(Alignment.TopEnd).padding(16.dp))
                    ScaleBar(state, Modifier.align(Alignment.BottomStart).padding(16.dp), units = UnitSystem.METRIC)
                }
            }
        }
        compose.waitForIdle()
        threads.track(state)
        compose.settleMap(state, 240_000)
    }

    private fun westerkerk() {
        assumeTrue("the app's bundled pack", EngineTestKit.bundled.isFile)
        EngineTestKit.wire("amsterdam" to EngineTestKit.bundled)
    }

    private fun stateAt(camera: CameraPosition) = MapState(camera).apply { time = Fixtures.afternoon }

    private fun frame(state: MapState): EngineFrame = state.controller!!.frames.latest!!

    @Test
    fun theWesterkerkIsTheEnginesOwnRender() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, controls = false)
        val f = frame(state)
        assertEquals("amsterdam", f.region)
        assertEquals("amsterdam", f.view)
        assertEquals("sunny", f.moment)
        assertEquals(MapStatus.Ready, state.status)
        val shot = pixels()
        capture("map_westerkerk")
        // amsterdam.py's main camera, rendered by the engine from data/ams/raw.json, drawn by SvgCanvas
        Regions.resetPlace()
        val S = Amsterdam.load(File(EngineTestKit.repo, "data/ams/raw.json"))
        val r = Amsterdam.render(S, "sunny", LinkedHashMap(ViewLadder.livingView()), null, layer = "split")
        val ref = EngineTestKit.raster(listOf(r.svg) + r.objs!!)
        save(ref, "map_westerkerk_reference")
        val (mad, near) = EngineTestKit.difference(shot, ref)
        println("MAP westerkerk: MAD %.3f, %.2f%% within 2 levels; frame %s".format(mad, 100 * near, f.timings))
        assertTrue("MAD $mad", mad < 0.5)
        assertTrue("within 2 levels: $near", near > 0.99)
        assertTrue(Fixtures.westerkerk in state.visibleBounds!!)
    }

    @Test
    fun theMainBoardIsPythonsRender() {
        assumeTrue("the grachten pack is local only", EngineTestKit.grachten.isFile)
        val oracle = listOf("g", "o", "o2").map { File(EngineTestKit.repo, "cache/oracle/v2_grachten_home_sunny_$it.svg") }
        assumeTrue("today's Python render of the board", oracle.all { it.isFile })
        EngineTestKit.wire("amsterdam" to EngineTestKit.bundled, "grachten" to EngineTestKit.grachten)
        val state = stateAt(HomeView.camera)
        show(state)
        val f = frame(state)
        assertEquals("grachten", f.region)
        assertEquals("grachten_home", f.view)
        assertEquals(0.7, f.tolerance, 0.0)
        val shot = pixels()
        capture("map_board_main")
        val ref = EngineTestKit.raster(oracle.map { it.readText() })
        save(ref, "map_board_main_python")
        val (mad, near) = EngineTestKit.difference(shot, ref)
        println("MAP board Main: MAD %.3f, %.2f%% within 2 levels of Python's SVG drawn by SvgCanvas; frame %s".format(mad, 100 * near, f.timings))
        assertTrue("MAD $mad", mad < 0.5)
        assertTrue("within 2 levels: $near", near > 0.99)
    }

    @Test
    fun aMovedCameraWarpsTheLastFrameAtOnce() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state)
        val before = pixels()
        val first = frame(state).number
        // hold the engine: the next exact frame waits while the warped one is on screen
        LiveEngine.lock.lock()
        val warped: Bitmap
        try {
            val c = state.camera
            state.camera = c.copy(target = Geo.destination(c.target, c.bearing + 90.0, 40.0), bearing = c.bearing + 4)
            compose.waitForIdle()
            warped = pixels()
            assertEquals("no new exact frame yet", first, frame(state).number)
        } finally {
            LiveEngine.lock.unlock()
        }
        compose.settleMap(state, 240_000)
        assertTrue(frame(state).number > first)
        val exact = pixels()
        save(warped, "map_warp_warped")
        save(exact, "map_warp_exact")
        // where the old frame reaches (the warp leaves the rest bare), the ground of the warp (the lower half) is close to
        // the new exact frame, much closer than the old frame itself
        val h = exact.height / 2
        val bare = MapController.DAY_EARTH
        val (warpMad, _) = EngineTestKit.difference(warped, exact, top = h, skip = warped, skipColour = bare)
        val (staleMad, _) = EngineTestKit.difference(before, exact, top = h, skip = warped, skipColour = bare)
        println("MAP warp: ground MAD warped %.2f, unwarped %.2f".format(warpMad, staleMad))
        assertTrue("warped $warpMad against unwarped $staleMad", warpMad < staleMad * 0.6)
    }

    @Test
    fun night() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, style = MapStyle(theme = Theme.NIGHT))
        assertEquals("night", frame(state).moment)
        capture("map_night")
    }

    @Test
    fun topDown() {
        westerkerk()
        val state = stateAt(CameraPosition(Fixtures.westerkerk, 17.0))
        show(state, padding = PaddingValues(), controls = true)
        assertNotNull(state.controller!!.frames.latest)
        capture("map_top_down")
    }

    @Test
    fun markers() {
        westerkerk()
        val camera = HomeView.westerkerk
        val state = stateAt(camera)
        val v = Viewport(824, 1830, 2f, offsetY = -(915.0 - 880.0), frame = app.atlas.render.live.RegionFrame.living())
        fun at(x: Float, y: Float) = CameraOps.groundAt(camera, v, x, y)!!
        val markers = listOf(
            Marker("belhamel", at(330f, 820f), "restaurant", 0xFF14161B, MarkerStyle.PIN, label = "De Belhamel", badge = "ui-check", ring = 0.7f, selected = true, priority = 10),
            Marker("museum", at(560f, 980f), "museum", 0xFF7B4FD6, MarkerStyle.PIN, value = "4′", priority = 5),
            Marker("late", at(290f, 1640f), "bar", 0xFFE07A1F, MarkerStyle.PIN, ring = 0.12f, badge = "ui-moon-star", priority = 5),
            Marker("ah", at(180f, 1130f), "grocery", 0xFF2E9E5B, MarkerStyle.BRAND, logo = "albertheijn", priority = 4),
            Marker("coffee", at(660f, 1060f), "cafe", 0xFF5B3A29, MarkerStyle.BRAND, logo = "Starbucks", priority = 4),
            Marker("charge", at(420f, 1260f), "charging_station", 0xFF14161B, MarkerStyle.CHIP, value = "150", ring = 0.62f, selected = true, priority = 6),
            Marker("cafe", at(610f, 1430f), "cafe", 0xFF2E5BFF, MarkerStyle.CHIP, value = "4.8", selected = true, priority = 3),
            Marker("cluster", at(160f, 940f), "restaurant", 0xFFE07A1F, MarkerStyle.CLUSTER, count = 12, priority = 2),
            Marker("dot1", at(520f, 1600f), "bus", 0xFF2E5BFF, MarkerStyle.DOT, priority = 1),
            Marker("dot2", at(530f, 1606f), "bus", 0xFF2E5BFF, MarkerStyle.DOT),
            Marker("dot3", at(512f, 1594f), "bus", 0xFF2E5BFF, MarkerStyle.DOT),
        )
        show(state, overlays = MapOverlays(markers = markers))
        capture("map_markers")
        val c = state.controller!!
        assertNotNull(c.frames.latest)
        // Everything shows but two of the three bus stops, which fold into the first as a cluster of three.
        val layout = c.painter!!.layout
        assertEquals(markers.size - 2, layout.placedCount)
        val shown = (0 until layout.placedCount).associate { markers[layout.placed[it]].id to layout.clusterCount[it] }
        assertEquals(3, shown["dot1"])
        assertEquals(12, shown["cluster"])
        // A marker sits on its ground point as the engine's camera projects it.
        val p = state.latLonToScreen(markers[0].at)!!
        assertEquals(330f, p.first, 0.5f)
        assertEquals(820f, p.second, 0.5f)
    }

    @Test
    fun routeAndPuck() {
        westerkerk()
        // along the Prinsengracht's west bank from the Westerkerk, over the Westermarkt, to the Keizersgracht
        val main = listOf(LatLon(52.37452, 4.88377), LatLon(52.37500, 4.88330), LatLon(52.37560, 4.88290), LatLon(52.37600, 4.88420), LatLon(52.37560, 4.88600))
        val alternative = listOf(LatLon(52.37452, 4.88377), LatLon(52.37390, 4.88470), LatLon(52.37450, 4.88640), LatLon(52.37560, 4.88600))
        val walk = listOf(main.last(), LatLon(52.37520, 4.88660))
        val length = main.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
        val state = stateAt(HomeView.westerkerk)
        val overlays = MapOverlays(
            routes = listOf(
                RouteLine("alt", alternative, alternative = true, label = "8′"),
                RouteLine("main", main, travelled = length / 3, label = "6′"),
                RouteLine("walk", walk, widthDp = 5f, dashed = true),
            ),
            location = UserLocation(main[1], 18.0, heading = Geo.bearing(main[1], main[2])),
            areas = listOf(AreaOverlay("near", walk.last(), 40.0)),
            markers = listOf(Marker("dest", walk.last(), "restaurant", 0xFFE07A1F, MarkerStyle.PIN, selected = true, label = "Osteria")),
        )
        show(state, overlays = overlays)
        capture("map_route_puck")
        assertNotNull(state.controller!!.frames.latest)
        assertTrue(state.controller!!.painter!!.routeVertices > 0)
    }

    @Test
    fun measuringTape() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, overlays = MapOverlays(measure = true))
        compose.onRoot().performTouchInput {
            down(0, Offset(230f, 900f))
            down(1, Offset(620f, 1450f))
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
    fun tiltDial() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, controls = true)
        tiltDownWithTwoFingers()
        val dial = state.controller!!.dial
        assertNotNull("the dial shows while the fingers tilt", dial)
        assertTrue("away from the fingers, on the left: ${dial!!.left}", dial.left < 824f / 2)
        capture("map_tilt_dial")
        compose.onRoot().performTouchInput {
            up(0)
            up(1)
        }
    }

    @Test
    fun tiltDialAtNight() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, style = MapStyle(theme = Theme.NIGHT), controls = true)
        tiltDownWithTwoFingers()
        assertNotNull(state.controller!!.dial)
        capture("map_tilt_dial_night")
        compose.onRoot().performTouchInput {
            up(0)
            up(1)
        }
    }

    /** Two fingers right of the middle dragged down 80 px at 125 dp a second, held there: the dial shows on the left. */
    private fun tiltDownWithTwoFingers() {
        compose.onRoot().performTouchInput {
            down(0, Offset(470f, 1100f))
            down(1, Offset(690f, 1080f))
            for (i in 1..20) {
                updatePointerTo(0, Offset(470f, 1100f + 4f * i))
                updatePointerTo(1, Offset(690f, 1080f + 4f * i))
                move()
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    @Test
    fun debugOverlay() {
        westerkerk()
        val state = stateAt(HomeView.westerkerk)
        show(state, debug = true)
        capture("map_debug")
    }

    @Test
    fun noDataHere() {
        westerkerk()
        // Paris, offline, with only the Westerkerk on the phone
        val state = stateAt(CameraPosition(LatLon(48.8584, 2.2945), 16.0, 0.0, 40.0))
        show(state)
        assertTrue(state.status is MapStatus.NoData)
        capture("map_no_data")
    }
}
