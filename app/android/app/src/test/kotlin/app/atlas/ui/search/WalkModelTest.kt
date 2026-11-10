package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.route.RouteResult
import app.atlas.route.Router
import app.atlas.search.Intents
import app.atlas.search.MemoryPlaces
import app.atlas.search.TellKey
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** A walk in the search's model: its chip's figure, its results, a round walk asked for, found, let go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkModelTest {
    private val w = Services.WESTERKERK
    private val walk = Intents.all.first { it.id == Intents.WALK }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

    private fun until(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    private fun green(id: Long, north: Double, across: Double, vararg tags: Pair<String, String>): Place {
        val at = Geo.destination(w, 0.0, north)
        return Place(OsmId(OsmType.WAY, id), at, tags.toMap(), BBox.around(at, across / 2))
    }

    private val places = listOf(
        green(1, 500.0, 600.0, "leisure" to "park", "name" to "Het Grote Park"),
        green(2, 60.0, 40.0, "leisure" to "park", "name" to "Postzegelpark"),
        green(3, 120.0, 300.0, "leisure" to "garden", "access" to "private", "name" to "Besloten Tuin"),
        // a monument's own small outline, and a view: places to walk to, neither a green to go round
        green(4, 90.0, 30.0, "historic" to "memorial", "memorial" to "sculpture", "name" to "Homomonument"),
        Place(OsmId(OsmType.NODE, 5), Geo.destination(w, 90.0, 150.0), mapOf("tourism" to "viewpoint", "name" to "Uitkijk")),
        // farther than a walk's first circle reaches, nearer than it reads
        Place(OsmId(OsmType.NODE, 6), Geo.destination(w, 120.0, 2_600.0), mapOf("man_made" to "windmill", "name" to "De Gooyer")),
    )

    private val straight = Router { req ->
        val d = req.waypoints.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
        RouteResult.Found(listOf(Route(req.mode, req.waypoints, distance = d, duration = d / 1.3, steps = emptyList())))
    }

    private fun model(router: Router = straight, places: List<Place> = this.places): SearchSheetModel {
        val m = SearchSheetModel(testServices(stores = listOf(MemoryPlaces(places)), router = router), w, "en")
        m.moveTo(w)
        until { m.state.value.served.isNotEmpty() }
        return m
    }

    private fun entered(m: SearchSheetModel): SearchSheetState {
        m.enter(walk)
        until { m.state.value.results != null && !m.state.value.loading }
        return m.state.value
    }

    @Test
    fun theChipNamesTheNearestGreenAndCountsEveryPlaceToWalkTo() {
        val p = model().state.value.served.getValue(Intents.WALK)
        // the monument and the view are nearer; a walk's chip names where to go round
        assertEquals("Het Grote Park", p.nearest?.name())
        assertEquals("the park, the monument, the view, within the grid's circle", 3, p.count)
        assertEquals(0, p.open)
        assertNotNull(p.minutes)
    }

    @Test
    fun itsViewListsEveryPlaceToWalkToAndAGreenSaysALap() {
        val s = entered(model())
        val hits = s.results!!.hits
        assertEquals(setOf("Het Grote Park", "Homomonument", "Uitkijk", "De Gooyer"), hits.map { it.record.name() }.toSet())
        assertEquals("≈ 25 min lap", hits.first { it.record.name() == "Het Grote Park" }.tells.first { it.key == TellKey.LAP }.text)
        assertTrue(hits.first { it.record.name() == "Homomonument" }.tells.none { it.key == TellKey.LAP })
        // its kinds are those among its places, the park's, the view's and the monument's
        assertEquals(hits.size, s.results!!.subcategories.sumOf { it.second })
        assertTrue(s.results!!.subcategories.size >= 3)
    }

    @Test
    fun aWalkReadsAsFarAsOneWalksThoughEnoughLieNearer() {
        // eight statues round the corner fill a kind's first circle; a walk reads on to the mill
        val statues = (1..8).map { i ->
            Place(OsmId(OsmType.NODE, 100L + i), Geo.destination(w, i * 40.0, 200.0), mapOf("tourism" to "artwork", "artwork_type" to "statue", "name" to "Beeld $i"))
        }
        val s = entered(model(places = statues + places.last()))
        assertTrue("De Gooyer" in s.results!!.hits.map { it.record.name() })
        assertTrue("read within ${s.radius}", s.radius >= Intents.WALK_REACH_M)
    }

    @Test
    fun aRoundWalkIsFoundThroughTheGreenAndLetGoAtASecondPress() {
        val m = model()
        entered(m)
        m.roundWalk(40)
        assertEquals(40, m.state.value.walking?.minutes)
        assertNull("looking", m.state.value.walking?.walk)
        until { m.state.value.walking?.walk != null }
        val found = m.state.value.walking!!.walk!!
        assertEquals("Het Grote Park", found.through)
        assertEquals(w, found.request.waypoints.first())
        assertEquals(w, found.request.waypoints.last())
        assertEquals(1, walkLines(m.state.value).size)
        m.roundWalk(40)
        assertNull(m.state.value.walking)
        assertTrue(walkLines(m.state.value).isEmpty())
    }

    @Test
    fun anotherLengthAsksAgain() {
        val m = model()
        entered(m)
        m.roundWalk(20)
        until { m.state.value.walking?.walk != null }
        val short = m.state.value.walking!!.walk!!.route.distance
        m.roundWalk(60)
        assertEquals(60, m.state.value.walking?.minutes)
        assertNull("the old one is gone", m.state.value.walking?.walk)
        until { m.state.value.walking?.walk != null }
        assertTrue(m.state.value.walking!!.walk!!.route.distance > short * 2)
    }

    @Test
    fun routingDataNotOnThePhoneIsSaidWithWhatItTakes() {
        val m = model(Router { RouteResult.NoData(listOf("E0_N50.rd5")) })
        entered(m)
        m.roundWalk(40)
        until { m.state.value.walking?.failed != null }
        val a = m.state.value.walking!!
        assertEquals("E0_N50", a.needs.single().tile)
        assertNull(a.walk)
    }

    @Test
    fun noWayRoundIsSaid() {
        val m = model(Router { RouteResult.NoRoute("an island") })
        entered(m)
        m.roundWalk(20)
        until { m.state.value.walking?.failed != null }
        assertTrue(m.state.value.walking!!.needs.isEmpty())
    }

    @Test
    fun aRouterThatThrowsIsNoWayRound() {
        val m = model(Router { throw IllegalStateException("broken") })
        entered(m)
        m.roundWalk(20)
        until { m.state.value.walking?.failed != null }
        assertEquals("broken", m.state.value.walking!!.failed!!.message)
    }

    @Test
    fun leavingTheWalkLetsItsRoundGo() {
        val m = model()
        entered(m)
        m.roundWalk(40)
        until { m.state.value.walking?.walk != null }
        m.up()
        assertFalse(m.state.value.inside)
        assertNull(m.state.value.roundWalk)
        entered(m)
        assertNull("a new visit starts afresh", m.state.value.walking)
    }

    @Test
    fun aRoundWalkIsOnlyAWalksToAskFor() {
        val m = model()
        m.enter("parks")
        until { m.state.value.results != null && !m.state.value.loading }
        m.roundWalk(40)
        repeat(10) { idle() }
        assertNull(m.state.value.walking)
        assertTrue(walkLines(m.state.value).isEmpty())
    }
}
