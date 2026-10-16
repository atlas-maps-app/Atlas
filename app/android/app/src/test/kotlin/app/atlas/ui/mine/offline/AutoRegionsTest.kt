package app.atlas.ui.mine.offline

import androidx.test.core.app.ApplicationProvider
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.services.RegionLayer
import app.atlas.testing.testServices
import app.atlas.ui.go.SavedPlace
import app.atlas.ui.mine.MineFixtures
import app.atlas.ui.mine.MineKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val HOME = LatLon(52.3733, 4.8840)
private val WORK = LatLon(52.0907, 5.1214)

class AutoRegionsPlanTest {
    private fun plan(home: LatLon? = HOME, work: LatLon? = WORK, trip: List<LatLon> = emptyList(), have: List<BBox> = emptyList(), done: Set<String> = emptySet()) =
        AutoRegions.plan(home, work, trip, have, done, "Around home", "Around work", "Route to Utrecht", "part")

    @Test
    fun homeAndWorkEachGetAnAreaOfTwelveKilometres() {
        val p = plan()
        assertEquals(listOf("Around home", "Around work"), p.map { it.name })
        assertEquals(listOf(AutoRegions.Kind.HOME, AutoRegions.Kind.WORK), p.map { it.kind })
        for (w in p) { val (a, b) = PickerModel.sides(w.bbox); assertEquals(24.0, a, 0.5); assertEquals(24.0, b, 0.5) }
    }

    @Test
    fun noHomeNoWorkNoTripAsksForNothing() {
        assertTrue(plan(home = null, work = null).isEmpty())
    }

    @Test
    fun aPlaceInsideARegionAlreadyOnThePhoneIsNotAskedFor() {
        val ams = BBox(4.70, 52.28, 5.05, 52.47)
        val p = plan(have = listOf(ams))
        assertEquals(listOf("Around work"), p.map { it.name })
    }

    @Test
    fun aRegionThatOnlyTouchesTheAreaDoesNotCoverIt() {
        val edge = BBox(4.88, 52.28, 5.30, 52.47) // the home is on its western edge: under 80 % of the wanted width inside
        assertEquals(listOf("Around home", "Around work"), plan(have = listOf(edge)).map { it.name })
    }

    @Test
    fun whatWasAskedForBeforeIsNotAskedAgain() {
        val first = plan()
        val again = plan(done = first.map { it.key }.toSet())
        assertTrue(again.isEmpty())
        // Home moved to another city: only that one is new.
        val moved = plan(home = LatLon(51.9225, 4.4792), done = first.map { it.key }.toSet())
        assertEquals(listOf("Around home"), moved.map { it.name })
    }

    @Test
    fun aTripGetsABoxPerStretchOfItsRoadNamedByPart() {
        val road = (0..600).map { LatLon(52.37 - it * 0.0010, 4.88 + it * 0.0016) } // about 95 km south-east
        val p = plan(home = null, work = null, trip = road)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.kind == AutoRegions.Kind.TRIP })
        assertEquals("Route to Utrecht part 1", p.first().name)
        for (pt in road) assertTrue(p.any { pt in it.bbox })
        val single = plan(home = null, work = null, trip = road.take(20))
        assertEquals(listOf("Route to Utrecht"), single.map { it.name })
    }

    @Test
    fun theKeyIsTheBoxToTwoDecimals() {
        val w = plan().first()
        assertEquals(4, w.key.split(',').size)
        assertEquals(w.key, AutoRegions.Wanted(w.kind, "other name", w.bbox).key)
    }
}

/** The switch in Offline, through the hub: it asks for home, work and the trip being guided, and only once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoRegionsHubTest {
    private class Queue : RegionScheduler {
        val ids = ArrayList<String>()
        override fun schedule(id: String, wifiOnly: Boolean, update: Boolean) { ids += id }
        override fun cancel(id: String) = Unit
    }

    private fun phone(queue: Queue): Services = testServices(places = true).apply {
        mine.useDeps(MineFixtures.packDeps(this, clock.instant()))
        mine.useScheduler(queue)
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(50) }
        throw AssertionError("never: $what")
    }

    @Test
    fun withTheSwitchOnHomeAndWorkAreTakenOnceAndRegionsAlreadyThereAreKept() {
        val q = Queue()
        val s = phone(q)
        runBlocking {
            s.go.store.setHome(SavedPlace("Home", HOME))
            s.go.store.setWork(SavedPlace("Work", WORK))
            s.settings.set(MineKeys.AUTO_REGIONS, true)
        }
        s.mine.offline // starts the hub
        waitFor("home and work") { s.mine.offline.details.value.size == 2 }
        assertEquals(setOf("around-home", "around-work"), s.mine.offline.details.value.map { it.id }.toSet())
        waitFor("both queued") { q.ids.size == 2 }
        val wanted = s.mine.files.get("around-home")!!.wanted
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.ROUTING, RegionLayer.VOICE), wanted)
        // A region you delete does not come back by itself.
        runBlocking { s.mine.offline.delete("around-work") }
        runBlocking { s.settings.set(MineKeys.AUTO_REGIONS, false); s.settings.set(MineKeys.AUTO_REGIONS, true) }
        Thread.sleep(800)
        assertEquals(listOf("around-home"), s.mine.offline.details.value.map { it.id })
        assertEquals(2, q.ids.size)
    }

    @Test
    fun aTripBeingGuidedGetsItsCorridorWhileTheSwitchIsOn() {
        val q = Queue()
        val s = phone(q)
        runBlocking { s.settings.set(MineKeys.AUTO_REGIONS, true) }
        s.mine.offline
        MineFixtures.startTrip(s, ApplicationProvider.getApplicationContext())
        val boxes = PickerModel.corridorBoxes(MineFixtures.trip().points, AutoRegions.CORRIDOR_M)
        waitFor("trip regions") { s.mine.offline.details.value.size == boxes.size }
        assertTrue(s.mine.offline.details.value.all { it.name.startsWith("Route to Rotterdam part ") })
    }

    @Test
    fun withTheSwitchOffNothingIsAskedFor() {
        val q = Queue()
        val s = phone(q)
        runBlocking { s.go.store.setHome(SavedPlace("Home", HOME)) }
        s.mine.offline
        Thread.sleep(800)
        assertTrue(q.ids.isEmpty())
        assertFalse(s.mine.offline.details.value.isNotEmpty())
    }
}
