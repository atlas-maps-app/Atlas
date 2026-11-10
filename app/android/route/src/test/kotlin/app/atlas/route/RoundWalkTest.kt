package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a round walk is picked, with a router that walks straight between the stops. */
class RoundWalkTest {
    private val from = LatLon(52.37, 4.88)
    private val calls = ArrayList<RouteRequest>()

    /** Walks straight between the stops at a walking pace, [detour] times as far as the line. */
    private fun straight(detour: Double = 1.0) = Router { req ->
        calls += req
        val d = req.waypoints.zipWithNext { a, b -> Geo.distance(a, b) }.sum() * detour
        RouteResult.Found(listOf(Route(req.mode, req.waypoints, distance = d, duration = d / 1.3, steps = emptyList())))
    }

    private fun found(r: RoundWalks.Result) = (r as RoundWalks.Found).walk

    @Test
    fun aWalkEndsWhereItBeganAndTakesAboutTheTimeAsked() = runBlocking {
        val w = found(RoundWalks.find(straight(), from, 40, emptyList()))
        assertEquals(from, w.request.waypoints.first())
        assertEquals(from, w.request.waypoints.last())
        assertTrue(w.request.waypoints.size >= 4)
        assertEquals(40.0, w.route.duration / 60, 5.0)
    }

    @Test
    fun theStreetsDetourIsLearntFromTheFirstTry() = runBlocking {
        val w = found(RoundWalks.find(straight(detour = 1.8), from, 40, emptyList()))
        assertEquals(40.0, w.route.duration / 60, 5.0)
        assertTrue("it tried again at another size", calls.size >= 2)
    }

    @Test
    fun aGreenInReachIsGoneThroughAndNamed() = runBlocking {
        val park = BBox.around(Geo.destination(from, 0.0, 500.0), 250.0)
        val w = found(RoundWalks.find(straight(), from, 40, listOf(Green(park, "Het Park"))))
        assertTrue(w.request.waypoints.any { it in park })
        assertEquals("Het Park", w.through)
    }

    @Test
    fun aGreenOutOfReachIsNotClaimed() = runBlocking {
        val far = BBox.around(Geo.destination(from, 0.0, 10_000.0), 250.0)
        val w = found(RoundWalks.find(straight(), from, 20, listOf(Green(far, "Ver Weg"))))
        assertTrue(w.request.waypoints.none { it in far })
        assertNull(w.through)
    }

    @Test
    fun aGreenNoOneNamedLeadsNoName() = runBlocking {
        val park = BBox.around(Geo.destination(from, 90.0, 400.0), 250.0)
        assertNull(found(RoundWalks.find(straight(), from, 40, listOf(Green(park)))).through)
    }

    @Test
    fun routingDataNotOnThePhoneIsSaidAtOnce() = runBlocking {
        val r = RoundWalks.find({ calls += it; RouteResult.NoData(listOf("E0_N50.rd5")) }, from, 20, emptyList())
        assertEquals(listOf("E0_N50.rd5"), ((r as RoundWalks.Failed).why as RouteResult.NoData).missingTiles)
        assertEquals(1, calls.size)
    }

    @Test
    fun dataMissingFartherOutLeavesTheOtherShapes() = runBlocking {
        var n = 0
        val inner = straight()
        val w = found(RoundWalks.find({ if (n++ == 0) RouteResult.NoData(listOf("E5_N50.rd5")) else inner.route(it) }, from, 20, emptyList()))
        assertEquals(20.0, w.route.duration / 60, 4.0)
    }

    @Test
    fun dataMissingFartherOutIsOfferedWhereNoWayRoundWasFound() = runBlocking {
        val r = RoundWalks.find({ calls += it; RouteResult.NoData(listOf("E5_N50.rd5")) }, from, 20, emptyList())
        assertEquals(listOf("E5_N50.rd5"), ((r as RoundWalks.Failed).why as RouteResult.NoData).missingTiles)
        assertTrue("every side", calls.size >= 4)
    }

    @Test
    fun noWayRoundIsSaidAfterTryingEachSide() = runBlocking {
        val r = RoundWalks.find({ calls += it; RouteResult.NoRoute("an island") }, from, 20, emptyList())
        assertTrue((r as RoundWalks.Failed).why is RouteResult.NoRoute)
        assertTrue("every side", calls.size >= 4)
    }

    @Test
    fun aFailedSideLeavesTheOthers() = runBlocking {
        var n = 0
        val inner = straight()
        val w = found(RoundWalks.find({ if (n++ == 0) RouteResult.NoRoute("water") else inner.route(it) }, from, 20, emptyList()))
        assertEquals(20.0, w.route.duration / 60, 4.0)
    }

    @Test
    fun outAndBackIsWalkedTwiceAndARoundIsNot() {
        val out = (0..40).map { Geo.destination(from, 0.0, it * 15.0) }
        assertTrue(RoundWalks.twice(out + out.reversed()) > 0.4)
        val square = (0..39).map { Geo.destination(from, 0.0, it * 15.0) } + (0..39).map { Geo.destination(Geo.destination(from, 0.0, 600.0), 90.0, it * 15.0) } +
            (0..39).map { Geo.destination(Geo.destination(Geo.destination(from, 0.0, 600.0), 90.0, 600.0), 180.0, it * 15.0) } +
            (0..39).map { Geo.destination(Geo.destination(from, 90.0, 600.0), 270.0, it * 15.0) }
        assertEquals(0.0, RoundWalks.twice(square), 0.02)
    }

    @Test
    fun aStopAtTheTipOfASpurMovesToItsFoot() {
        val along = (0..20).map { Geo.destination(from, 90.0, it * 10.0) }
        val foot = along.last()
        val up = (1..8).map { Geo.destination(foot, 0.0, it * 10.0) }
        val way = along + up + up.reversed().drop(1) + foot + (1..20).map { Geo.destination(foot, 90.0, it * 10.0) }
        val moved = RoundWalks.moveOffSpikes(way, listOf(up.last()))
        assertTrue("moved to ${moved.single()}", Geo.distance(moved.single(), foot) < 12)
    }

    @Test
    fun aStopOnTheWayStaysWhereItIs() {
        val along = (0..40).map { Geo.destination(from, 90.0, it * 10.0) }
        assertEquals(listOf(along[20]), RoundWalks.moveOffSpikes(along, listOf(along[20])))
    }
}
