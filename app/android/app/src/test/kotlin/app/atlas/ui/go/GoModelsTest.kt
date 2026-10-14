package app.atlas.ui.go

import androidx.test.core.app.ApplicationProvider
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.guide.GuideSettings
import app.atlas.model.Fix
import app.atlas.model.Mode
import app.atlas.model.RoutePrefs
import app.atlas.services.NoVoice
import app.atlas.testing.testServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The go package's state holders on a test phone: what the Go tab keeps, the planner on the real segment, Ears only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoModelsTest {
    @Test
    fun theStoreKeepsHomeWorkPlacesAndVehicles() = runBlocking {
        val s = testServices()
        val store = s.go.store
        assertNull(store.home.first())
        assertEquals(Mode.WALK, store.mode.first())
        store.setHome(SavedPlace("Home", LatLon(52.36, 4.87)))
        store.setMode(Mode.TRUCK)
        assertEquals("Home", store.home.first()!!.name)
        assertEquals(Mode.TRUCK, store.mode.first())
        // Places you went to: the latest first, the same place once.
        val centraal = SavedPlace("Amsterdam Centraal", LatLon(52.37903, 4.90010))
        store.visited(centraal, 1)
        store.visited(SavedPlace("Vondelpark", LatLon(52.358, 4.8686)), 2)
        store.visited(centraal.copy(at = LatLon(52.37910, 4.90015)), 3)
        assertEquals(listOf("Amsterdam Centraal", "Vondelpark"), store.recent.first().map { it.name })
        assertEquals(3L, store.recent.first().first().usedAt)
        repeat(20) { store.visited(SavedPlace("P$it", LatLon(52.0 + it * 0.01, 4.0)), 10L + it) }
        assertEquals(GoStore.MAX_RECENT, store.recent.first().size)
        store.forget(store.recent.first().first())
        assertEquals(GoStore.MAX_RECENT - 1, store.recent.first().size)
        // A truck is checked from the start with a common lorry; what you enter replaces it.
        assertEquals(4.0, store.vehicleNow(Mode.TRUCK).heightM!!, 0.0)
        store.setVehicle(Mode.TRUCK, GoStore.defaultVehicle(Mode.TRUCK).copy(heightM = 3.8))
        assertEquals(3.8, store.vehicleNow(Mode.TRUCK).heightM!!, 0.0)
        assertNull(store.vehicleNow(Mode.CAR).heightM)
        store.setPrefs(RoutePrefs(avoidSteps = true))
        assertTrue(store.prefs.first().avoidSteps)
        store.setTacho(TachoState(drivingSinceBreak = 600, since = Instant.EPOCH))
        assertEquals(600L, store.tacho.first()!!.drivingSinceBreak)
        s.close()
    }

    @Test
    fun theGuideSpeaksTheAppsLanguageAndUnits() = runBlocking {
        val s = testServices()
        val g = s.go.guideSettings(Mode.BOAT, GoStore.defaultVehicle(Mode.BOAT))
        assertEquals(app.atlas.geo.UnitSystem.NAUTICAL, g.units)
        assertEquals(1.6, g.vehicle.airDraftM!!, 0.0)
        assertEquals(s.clock.zone, g.zone)
        s.close()
    }

    @Test
    fun theDemoFixturesAnswerOnlyTheirOwnTrips() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val bike = DemoRoutes.find(ctx, Mode.BIKE, Services.WESTERKERK, GoFixtures.CENTRAAL)
        assertNotNull(bike)
        assertEquals(Mode.BIKE, bike!!.mode)
        assertEquals(Mode.EBIKE, DemoRoutes.find(ctx, Mode.EBIKE, Services.WESTERKERK, GoFixtures.CENTRAAL)!!.mode)
        assertNull(DemoRoutes.find(ctx, Mode.BIKE, Services.WESTERKERK, LatLon(52.36, 4.87)))
        assertNotNull(DemoRoutes.find(ctx, Mode.SAIL, Services.WESTERKERK, GoFixtures.IJ))
        // The kept index of segment sizes knows Amsterdam's.
        assertTrue(SegmentOffer.keptSizes(ctx).getValue("E0_N50") > 70_000_000)
    }

    @Test
    fun withoutDataThePlannerOffersTheSegment() = runBlocking {
        val s = testServices()
        val vm = RouteViewModel(s, Endpoint(null, Services.WESTERKERK, here = true), Endpoint("Haarlem", GoFixtures.HAARLEM), Mode.CAR)
        // The test phone is not in demo-routing mode for Haarlem by car: nothing on the phone, nothing to fall back on.
        val plan = withTimeout(30_000) {
            var p = vm.ui.value.plan
            while (p is Plan.Loading || p is Plan.Waiting) {
                kotlinx.coroutines.delay(50)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                p = vm.ui.value.plan
            }
            p
        }
        val needs = (plan as Plan.NeedsData).needs
        assertEquals("E0_N50", needs.single().tile)
        assertEquals("E0_N50 · 79 MB", needs.single().label)
        s.close()
    }

    @Test
    fun thePlannerRoutesEveryModeOnTheRealSegment() = runBlocking {
        val s = testServices(places = true)
        assumeTrue("needs cache/brouter/segments4/E0_N50.rd5", GoFixtures.linkSegment(s))
        val vm = RouteViewModel(s, Endpoint(null, Services.WESTERKERK, here = true), Endpoint("Amsterdam Centraal", GoFixtures.CENTRAAL), Mode.BIKE)
        withTimeout(120_000) {
            while (vm.ui.value.rowModes.any { vm.ui.value.plans[it] !is Plan.Found } || vm.ui.value.ways.isEmpty()) {
                kotlinx.coroutines.delay(100)
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
            }
        }
        val ui = vm.ui.value
        assertEquals(listOf(Mode.BIKE, Mode.CAR, Mode.WALK), ui.rowModes)
        val bike = ui.plans.getValue(Mode.BIKE) as Plan.Found
        assertTrue(bike.best.distance in 1_200.0..2_500.0)
        assertTrue(bike.fact is KeyFact.Ride)
        val walk = ui.plans.getValue(Mode.WALK) as Plan.Found
        assertTrue("walking is slower", walk.best.duration > bike.best.duration)
        assertFalse(ui.demo)
        // The ways compared: fastest, quiet and flat.
        assertEquals(WayKind.FASTEST, ui.ways.first().kind)
        // Leave at 8:00 or arrive by 9:00: the times follow.
        val at = java.time.LocalDateTime.of(2026, 10, 15, 9, 0)
        vm.setTiming(Timing(Timing.Kind.ARRIVE, at))
        val (leave, arrive) = vm.times(bike.best)
        assertEquals(at.atZone(s.clock.zone).toInstant(), arrive)
        assertEquals(bike.best.duration, (arrive.toEpochMilli() - leave.toEpochMilli()) / 1000.0, 1.0)
        // A stop on the way, then swapped ends.
        vm.addStop(Endpoint("Westermarkt", LatLon(52.37502, 4.88392)))
        vm.swap()
        assertEquals("Amsterdam Centraal", vm.ui.value.from.name)
        assertEquals(1, vm.ui.value.stops.size)
        vm.removeStop(0)
        assertTrue(vm.ui.value.stops.isEmpty())
        s.close()
    }

    @Test
    fun earsOnlyCallsOutTheWesterkerksSurroundings() = runBlocking {
        val s = testServices(places = true)
        val voice = NoVoice()
        val ears = EarsSession(voice, GuideSettings(mode = Mode.WALK), CoroutineScope(Dispatchers.Unconfined), s.go::placesAround, s.clock.zone)
        val at = Fix(Services.WESTERKERK, s.clock.instant(), 5.0, 0.0, null)
        ears.onFix(at)
        val v = ears.view.value
        assertFalse(v.loading)
        assertTrue("places around: ${v.around.map { it.label }}", v.around.size >= 4)
        assertTrue(v.around.all { it.distance <= EarsSession.RADAR_M })
        ears.aroundMe()
        assertTrue(voice.log.isNotEmpty())
        assertEquals(EarsLine.Kind.AROUND, ears.view.value.lines.first().kind)
        ears.whereAmI()
        assertEquals(EarsLine.Kind.WHERE, ears.view.value.lines.first().kind)
        ears.toggleBeacon()
        val beacon = ears.view.value.beacon!!
        assertTrue(beacon.distance >= EarsSession.BEACON_MIN_M)
        assertTrue(ears.view.value.lines.any { it.kind == EarsLine.Kind.BEACON })
        // Walking towards the beacon: its distance shrinks.
        val closer = Fix(Geo.destination(Services.WESTERKERK, beacon.bearing, 8.0), s.clock.instant().plusSeconds(12), 5.0, 1.3, beacon.bearing)
        ears.onFix(closer)
        assertTrue(ears.view.value.beacon!!.distance < beacon.distance)
        // A second press on the same place clears it.
        ears.toggleBeacon(ears.view.value.around.first { Geo.distance(it.place.location, beacon.place.location) < 1.0 })
        assertNull(ears.view.value.beacon)
        ears.stop()
        s.close()
    }
}
