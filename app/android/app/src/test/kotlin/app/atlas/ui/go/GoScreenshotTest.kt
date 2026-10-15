package app.atlas.ui.go

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.guide.Simulator
import app.atlas.model.Mode
import app.atlas.model.Turn
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import app.atlas.model.RouteRequest
import app.atlas.route.RouteResult
import app.atlas.route.WaterRouter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The go package's screens at the Westerkerk, on the fixture tiles and the Amsterdam place store, routed on the real
 * E0_N50 segment when it is on this machine (else the demo routes, which were computed from it): the Go tab, the
 * planner, Ride, Drive, Truck, Walk and Sail in demo mode partway along their trips, Ears only, Two wheels and the
 * vehicle forms.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class GoScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var services: Services? = null

    @After
    fun tearDown() {
        services?.go?.stopAll()
    }

    private fun capture(name: String): File {
        settleMap()
        val f = File(shotsDir, "$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun servicesFor(clock: Clock = app.atlas.testing.Fixtures.evening): Services =
        testServices(places = true, clock = clock).also {
            it.keepFixtureForecast()
            GoFixtures.linkSegment(it)
            services = it
        }

    private fun open(s: Services, vararg stack: Dest) {
        compose.setContent { AtlasRoot(s, BackStackNavigator(stack.toList()), dark = false) }
    }

    /**
     * Lets the map draw: Robolectric draws a frame only when the screen is captured, so capture until three frames in a
     * row come out the same (the tiles are in, the camera has stopped).
     */
    private fun settleMap(timeoutMillis: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        var last: android.graphics.Bitmap? = null
        var same = 0
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            if (last != null && now.sameAs(last)) {
                if (++same >= 3) return
            } else {
                same = 0
            }
            last = now
            Thread.sleep(120)
        }
    }

    @Test
    fun goTab() {
        val s = servicesFor()
        runBlocking {
            val store = s.go.store
            store.setWork(SavedPlace("Amsterdam Centraal", GoFixtures.CENTRAAL, arriveBy = LocalTime.of(20, 15)))
            store.setMode(Mode.BIKE)
            store.visited(SavedPlace("Vondelpark", app.atlas.geo.LatLon(52.35795, 4.86862)), s.clock.millis() - 26 * 3600_000L)
            store.visited(SavedPlace("Albert Cuypmarkt", app.atlas.geo.LatLon(52.35588, 4.89463)), s.clock.millis() - 3 * 24 * 3600_000L)
            store.visited(SavedPlace("Haarlem", GoFixtures.HAARLEM), s.clock.millis() - 2 * 3600_000L)
        }
        open(s, Dest.Go)
        // Both signs timed by the router: Amsterdam Centraal and Haarlem by bike (where the routing segment is on this
        // machine; on CI the gantry shows without times).
        if (GoFixtures.hasSegment) compose.waitUntil(60_000) { compose.onAllNodes(hasContentDescription("′ ·", substring = true)).fetchSemanticsNodes().size >= 2 }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        for (d in listOf("Where to?", "Search by voice", "Ears only", "Fuel", "Charge", "Park", "Bike fix", "Lockers")) {
            compose.onNodeWithContentDescription(d, substring = true, useUnmergedTree = true).assertExists()
        }
        capture("go")
    }

    @Test
    fun goTabFresh() {
        // A new phone: no home, work or places yet; the gantry says so.
        val s = servicesFor()
        open(s, Dest.Go)
        compose.settle()
        compose.onAllNodes(hasText("Not set yet")).fetchSemanticsNodes().let { assertEquals(2, it.size) }
        capture("go_fresh")
    }

    @Test
    fun routePlanner() {
        val s = servicesFor()
        open(s, Dest.Route(GoFixtures.CENTRAAL.lat, GoFixtures.CENTRAAL.lon, "Amsterdam Centraal", mode = "CAR"))
        // Car, bike and walk all found: four times in minutes (three rows and the Go button).
        compose.waitUntil(90_000) { compose.onAllNodes(hasText("′", substring = true)).fetchSemanticsNodes().size >= 4 }
        capture("route")
        // The preferences panel over the map.
        compose.onNodeWithContentDescription("Route preferences").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Motorways").assertExists()
        capture("route_prefs")
    }

    @Test
    fun routeNeedsData() {
        // No routing data on the phone and no demo route for this trip: the planner offers the segment, with its size.
        val s = testServices(places = true).also { services = it }
        open(s, Dest.Route(GoFixtures.HAARLEM.lat, GoFixtures.HAARLEM.lon, "Haarlem", mode = "CAR"))
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("E0_N50 · 79 MB", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Download").assertDoesNotExist()
        compose.onAllNodes(hasText("Connect to download it")).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        capture("route_nodata")
    }

    /** Opens guidance for [mode] to [to] in demo mode, then drives it by hand to where [stop] says, and takes the picture. */
    private fun guide(
        name: String, mode: Mode, to: app.atlas.geo.LatLon, toName: String, clock: Clock = app.atlas.testing.Fixtures.evening,
        given: Services? = null, stop: (NavView) -> Boolean,
    ) {
        val s = given ?: servicesFor(clock)
        open(s, Dest.Navigate(to.lat, to.lon, toName, mode.name, demo = true))
        compose.waitUntil(60_000) { s.go.nav.value != null }
        val session = s.go.nav.value!!
        // Take the wheel from the real-time demo feed and drive the simulator's fixes in one go.
        session.start(emptyFlow())
        val start = s.clock.instant()
        for (f in Simulator(session.view.value.route, start = start).fixes()) {
            session.onFix(f)
            if (stop(session.view.value)) break
        }
        compose.waitForIdle()
        session.view.value.let { v -> println("GUIDE $name at=${v.state?.location} bearing=${v.state?.routeBearing} next=${v.state?.next?.maneuver} street=${v.street} d=${v.state?.distanceToManeuver}") }
        capture(name)
    }

    /** True some 80–140 m before a turn. */
    private fun beforeATurn(v: NavView): Boolean {
        val st = v.state ?: return false
        val t = st.next?.maneuver?.turn ?: return false
        return v.fixesBeyond(20) && st.distanceToManeuver in 80.0..140.0 && (t.left || t.right)
    }

    private fun NavView.fixesBeyond(n: Int) = fixes > n

    @Test
    fun ride() = guide("ride", Mode.BIKE, GoFixtures.CENTRAAL, "Amsterdam Centraal") { v -> beforeATurn(v) }

    @Test
    fun drive() = guide("drive", Mode.CAR, GoFixtures.CENTRAAL, "Amsterdam Centraal") { v ->
        val st = v.state
        st != null && v.fixes > 8 && st.distanceToManeuver in 60.0..400.0
    }

    @Test
    fun truck() {
        guide("truck", Mode.TRUCK, GoFixtures.HAARLEM, "Haarlem") { v -> v.fixes > 40 && (v.state?.distanceToManeuver ?: 0.0) in 100.0..600.0 }
        val tacho = services!!.go.nav.value!!.view.value.tacho
        assertNotNull(tacho)
    }

    @Test
    fun walk() {
        // By day the walk is light.
        val noon = Clock.fixed(Instant.parse("2026-10-14T11:30:00Z"), ZoneId.of("Europe/Amsterdam"))
        guide("walk", Mode.WALK, GoFixtures.CENTRAAL, "Amsterdam Centraal", noon) { v -> beforeATurn(v) }
    }

    @Test
    fun sail() {
        val noon = Clock.fixed(Instant.parse("2026-10-14T11:30:00Z"), ZoneId.of("Europe/Amsterdam"))
        val router = GoFixtures.restrictedRouter
        assumeTrue("needs cache/brouter/segments4/E0_N50.rd5", router != null)
        // The boat's route as the app finds it online: with the bridges' clearances checked against its air draft.
        val s = servicesFor(noon)
        val req = RouteRequest(Mode.BOAT, listOf(Services.WESTERKERK, GoFixtures.IJ), vehicle = GoStore.defaultVehicle(Mode.BOAT))
        val route = (runBlocking { WaterRouter(router!!).route(req) } as RouteResult.Found).best
        val cleared = route.hazards.filter { it.kind == app.atlas.route.HazardKind.BRIDGE && it.value != null }
        assertTrue("bridges with a clearance: ${route.hazards.map { it.kind }}", cleared.isNotEmpty())
        val settings = runBlocking { s.go.guideSettings(Mode.BOAT, req.vehicle) }
        compose.runOnUiThread { s.go.startNav(androidx.test.core.app.ApplicationProvider.getApplicationContext(), req, route, "Het IJ", settings, demo = true) }
        val crossings = runBlocking { TileBridges.crossings(route, s.tiles.value!!) }
        val bridges = RouteBridges.find(route, s.placeStores, crossings)
        assertTrue("the tiles draw bridges over the canals: ${crossings.size}", crossings.size > 5)
        guide("sail", Mode.BOAT, GoFixtures.IJ, "Het IJ", noon, s) { v ->
            // Shortly before a bridge whose clearance is known, with no other bridge before it.
            val next = RouteBridges.next(bridges, v.travelled)
            next != null && next.first.clearance != null && next.second in 40.0..250.0
        }
        compose.onNodeWithContentDescription("clearance 2", substring = true).assertExists()
    }

    @Test
    fun ears() {
        val s = servicesFor()
        open(s, Dest.Ears)
        compose.waitUntil(30_000) { s.go.ears.value?.view?.value?.let { !it.loading && it.around.isNotEmpty() } == true }
        compose.onNodeWithText("Around me").performClick()
        compose.onNodeWithText("Beacon").performClick()
        compose.waitForIdle()
        assertTrue(s.go.ears.value!!.view.value.lines.isNotEmpty())
        compose.settle()
        capture("ears")
    }

    @Test
    fun twowheel() {
        val s = servicesFor()
        open(s, Dest.Twowheel())
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Stands")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        capture("twowheel")
    }

    @Test
    fun vehicleTruck() {
        val s = servicesFor()
        runBlocking { s.go.store.setTacho(TachoState(drivingSinceBreak = 4 * 3600L + 10 * 60, drivenToday = 6 * 3600L + 20 * 60, since = s.clock.instant())) }
        open(s, Dest.Vehicle("TRUCK"))
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("0:20 to the break", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("high").performClick()
        compose.settle()
        capture("vehicle")
    }

    @Test
    fun vehicleBoat() {
        val s = servicesFor()
        open(s, Dest.Vehicle("BOAT"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("air draft")).fetchSemanticsNodes().isNotEmpty() }
        compose.settle()
        capture("vehicle_boat")
    }

    @Test
    fun theDemoRouteIsRealAndTurnsAreLeftOrRight() {
        // The fixtures guidance falls back to: real BRouter output with named streets.
        for (m in listOf(Mode.WALK, Mode.BIKE, Mode.CAR, Mode.TRUCK, Mode.BOAT)) {
            val r = GoFixtures.fixture(m)!!
            assertTrue(Geo.distance(r.points.first(), Services.WESTERKERK) < DemoRoutes.MATCH_M)
            assertTrue("$m", r.steps.any { it.street != null })
            assertTrue("$m", r.steps.drop(1).dropLast(1).any { it.maneuver.turn != Turn.STRAIGHT } || m == Mode.CAR)
        }
    }
}
