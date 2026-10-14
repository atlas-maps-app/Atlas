package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.search.EvalContext
import app.atlas.search.Hit
import app.atlas.search.OfflineEvaluator
import app.atlas.search.PlaceRecord
import app.atlas.search.Query
import app.atlas.search.QueryParser
import app.atlas.search.QueryResult
import app.atlas.search.SortBy
import app.atlas.testing.Fixtures
import app.atlas.testing.testServices
import app.atlas.ui.kit.WalkTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Ask's planner: each step near the one before, chosen open when you get there, timed with walks and usual stays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskPlannerTest {
    private val evening = LocalDateTime.of(2026, 10, 13, 19, 30)
    private val ctx = OpeningHoursContext.Default

    private fun hit(id: Long, at: LatLon, vararg tags: Pair<String, String>): Hit {
        val p = Place(OsmId.parse("n$id"), at, mapOf(*tags), source = "osm")
        return Hit(PlaceRecord.of(p), 1.0, 1.0, null, null)
    }

    private fun result(vararg hits: Hit) = QueryResult(hits.toList(), hits.size, hits.size, 0)

    @Test
    fun aPlaceClosedOnArrivalIsPassedOver() {
        val near = LatLon(52.3740, 4.8830)
        val closed = hit(1, near, "amenity" to "cafe", "name" to "Shut", "opening_hours" to "Mo-Su 08:00-18:00")
        val unknown = hit(2, near, "amenity" to "cafe", "name" to "Maybe")
        val open = hit(3, near, "amenity" to "cafe", "name" to "Open", "opening_hours" to "Mo-Su 09:00-24:00")
        val chosen = AskPlanner.choose(listOf(closed, unknown, open), Services.WESTERKERK, evening, ctx, strict = false)
        assertEquals("Open", chosen?.first?.record?.name())
        assertEquals(true, chosen?.second)
        // Hours not mapped beat closed when nothing is surely open; "surely open" refuses them.
        assertEquals("Maybe", AskPlanner.choose(listOf(closed, unknown), Services.WESTERKERK, evening, ctx, strict = false)?.first?.record?.name())
        assertNull(AskPlanner.choose(listOf(closed, unknown), Services.WESTERKERK, evening, ctx, strict = true))
    }

    @Test
    fun closingSoonAfterArrivalDoesNotCount() {
        val tags = mapOf("amenity" to "restaurant", "opening_hours" to "Mo-Su 12:00-20:00")
        // Arriving at 19:30 for a 90 minute dinner, it closes at 20:00: before half the stay is over.
        assertEquals(false, AskPlanner.openFor(tags, evening, Duration.ofMinutes(90), ctx))
        assertEquals(true, AskPlanner.openFor(tags, evening.minusHours(3), Duration.ofMinutes(90), ctx))
        assertNull(AskPlanner.openFor(mapOf("amenity" to "restaurant"), evening, Duration.ofMinutes(90), ctx))
        assertEquals(true, AskPlanner.openFor(mapOf("opening_hours" to "24/7"), evening, Duration.ofHours(10), ctx))
    }

    @Test
    fun stopsAreTimedWithWalksAndStays() {
        val p = QueryParser.parse("a pub, then dinner")
        val pubAt = LatLon(52.3760, 4.8838)
        val dinnerAt = LatLon(52.3780, 4.8838)
        val pub = hit(1, pubAt, "amenity" to "pub", "name" to "Pub", "opening_hours" to "Mo-Su 12:00-01:00")
        val dinner = hit(2, dinnerAt, "amenity" to "restaurant", "name" to "Diner", "opening_hours" to "Mo-Su 12:00-23:00")
        val searched = ArrayList<LatLon>()
        val plan = AskPlanner.plan(p, Services.WESTERKERK, evening, ctx) { _, at ->
            searched += at
            if (searched.size == 1) result(pub) else result(dinner)
        }
        assertEquals(2, plan.stops.size)
        // The second step is searched around the first stop.
        assertEquals(listOf(Services.WESTERKERK, pubAt), searched)
        val first = plan.stops[0]
        val walk1 = Geo.distance(Services.WESTERKERK, pubAt)
        assertEquals(evening.plusSeconds(WalkTime.seconds(walk1).toLong()), first.arrive)
        assertEquals(Duration.ofMinutes(75), first.stay)
        val second = plan.stops[1]
        assertEquals(first.leave.plusSeconds(WalkTime.seconds(Geo.distance(pubAt, dinnerAt)).toLong()), second.arrive)
        assertEquals(2, plan.found)
        assertEquals(walk1 + Geo.distance(pubAt, dinnerAt), plan.walkM, 1e-6)
        assertEquals(second.leave, plan.end)
    }

    @Test
    fun aStepWithNothingStaysInThePlanEmpty() {
        val p = QueryParser.parse("a pub, then dinner")
        val plan = AskPlanner.plan(p, Services.WESTERKERK, evening, ctx) { _, _ -> result() }
        assertEquals(2, plan.stops.size)
        assertTrue(plan.stops.all { it.hit == null })
        assertEquals(0, plan.found)
        assertNull(plan.end)
        assertEquals("pub", plan.stops[0].label)
    }

    @Test
    fun refinementsChangeTheSteps() {
        val q = Query.Category("drinks")
        val r = AskPlanner.refined(listOf(q), setOf(Refine.CLOSER, Refine.STEP_FREE)).single()
        assertTrue(r is Query.Sort && r.by == SortBy.DISTANCE)
        assertEquals(listOf(q), AskPlanner.refined(listOf(q), emptySet()))
    }

    @Test
    fun usualStays() {
        assertEquals(45L, AskPlanner.stayAt(mapOf("amenity" to "cafe")).toMinutes())
        assertEquals(90L, AskPlanner.stayAt(mapOf("amenity" to "restaurant")).toMinutes())
        assertEquals(20L, AskPlanner.stayAt(mapOf("shop" to "bakery")).toMinutes())
        assertEquals(90L, AskPlanner.stayAt(mapOf("tourism" to "museum")).toMinutes())
    }

    @Test
    fun anEveningAtTheWesterkerkFromTheRealExtract() {
        val s = testServices(places = true)
        val now = ZonedDateTime.now(Fixtures.evening)
        val hours = HereContext.hours(s, Services.WESTERKERK, now.zone)
        val ev = OfflineEvaluator(s.placeStores)
        val p = QueryParser.parse("a pub with a terrace, then dinner", "en", now)
        val plan = AskPlanner.plan(p, Services.WESTERKERK, now.toLocalDateTime(), hours) { q, at ->
            ev.run(q, EvalContext(location = at, now = now, lang = "en", limit = 20, hours = hours))
        }
        assertEquals(2, plan.found)
        for (stop in plan.stops) {
            assertEquals("${stop.hit?.record?.name()} open on arrival", true, stop.openThen)
            assertTrue(stop.walkM < 1_500)
        }
        assertEquals("pub", plan.stops[0].hit!!.tags["amenity"])
        assertTrue(plan.stops[0].hit!!.tags["outdoor_seating"] == "yes")
        assertTrue(plan.stops[1].arrive.isAfter(plan.stops[0].leave.minusSeconds(1)))
    }
}
