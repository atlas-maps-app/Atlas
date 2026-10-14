package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/** Replaying routes as fixes: pace, noise, detours, determinism. */
class SimulatorTest {
    private val route = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 1000.0, "Rozengracht").leg(0.0, 500.0, "Singel").route()

    @Test fun cleanFixesRunAlongTheRouteAtTheSpeed() {
        val start = Instant.parse("2026-10-13T08:00:00Z")
        val fixes = Simulator(route, speed = 10.0, start = start, dwell = 3).fixes().toList()
        assertEquals(151 + 3, fixes.size)
        assertEquals(start, fixes[0].time)
        for (i in 1 until fixes.size) assertEquals(Duration.ofSeconds(1), Duration.between(fixes[i - 1].time, fixes[i].time))
        for (f in fixes.take(151)) assertTrue(route.line.snap(f.location)!!.offset < 0.01)
        assertEquals(10.0, Geo.distance(fixes[10].location, fixes[11].location), 0.01)
        assertEquals(90.0, fixes[5].bearing!!, 0.01)
        assertEquals(0.0, fixes[120].bearing!!, 0.01)
        assertEquals(10.0, fixes[5].speed!!, 0.0)
        // standing still at the end: no bearing
        assertEquals(0.0, fixes.last().speed!!, 0.0)
        assertEquals(null, fixes.last().bearing)
        assertEquals(0.0, Geo.distance(fixes.last().location, route.points.last()), 0.01)
    }

    @Test fun aRouteWithoutPointsIsRefusedClearly() {
        val empty = app.atlas.model.Route(Mode.CAR, emptyList(), distance = 0.0, duration = 0.0, steps = emptyList())
        val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { Simulator(empty, speed = 10.0) }
        assertEquals("the route has no points", e.message)
        // a single point is a route you are already at the end of
        val point = app.atlas.model.Route(Mode.CAR, listOf(WESTERKERK), distance = 0.0, duration = 0.0, steps = emptyList())
        assertEquals(1 + 2, Simulator(point, speed = 10.0).fixes().count())
    }

    @Test fun withoutASpeedItKeepsThePlannedPace() {
        val sk = Sketch(Mode.CAR, speed = 20.0).leg(90.0, 2000.0, "A")
        val r = sk.route()
        val fixes = Simulator(r, interval = 2.0).fixes().toList()
        assertEquals(40.0, Geo.distance(fixes[3].location, fixes[4].location), 0.01)
    }

    @Test fun noiseIsGaussianDriftingAndRepeatable() {
        val long = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 20_000.0, "N").route()
        val a = Simulator(long, speed = 10.0, noise = 15.0, seed = 42).fixes().toList()
        val b = Simulator(long, speed = 10.0, noise = 15.0, seed = 42).fixes().toList()
        val c = Simulator(long, speed = 10.0, noise = 15.0, seed = 43).fixes().toList()
        assertEquals(a, b)
        assertNotEquals(a, c)
        // cross-track error: about 15 m standard deviation, drifting (neighbouring errors correlate)
        val off = a.dropLast(2).map { f -> long.line.snap(f.location)!!.let { it.offset * it.side } }
        val mean = off.average()
        val sd = sqrt(off.sumOf { (it - mean) * (it - mean) } / off.size)
        assertEquals(15.0, sd, 3.0)
        var cov = 0.0
        for (i in 1 until off.size) cov += (off[i] - mean) * (off[i - 1] - mean)
        val r1 = cov / off.size / (sd * sd)
        assertTrue("lag-1 correlation $r1", r1 in 0.7..0.95)
        assertEquals(22.5, a[10].accuracy, 0.0)
        // the Doppler bearing at 10 m/s is good to a few degrees
        val bearingErr = a.dropLast(2).map { Geo.bearingDelta(it.bearing!!, 90.0) }
        assertTrue(bearingErr.average() < 4)
    }

    @Test fun aDetourLeavesTheRouteAndMayComeBack() {
        val out = Simulator(route, speed = 10.0, detours = listOf(Detour(400.0, 90.0, 200.0))).fixes().toList()
        val far = out.last()
        assertEquals(200.0, Geo.distance(far.location, route.line.pointAt(400.0)), 1.0)
        assertEquals(180.0, far.bearing!!, 0.01)
        assertEquals(40 + 20, out.size)

        val back = Simulator(route, speed = 10.0, detours = listOf(Detour(400.0, -90.0, 100.0, rejoin = true)), dwell = 0).fixes().toList()
        val farthest = back.maxOf { route.line.snap(it.location)!!.offset }
        assertEquals(100.0, farthest, 1.0)
        assertEquals(0.0, Geo.distance(back.last().location, route.points.last()), 0.01)
        assertEquals(151 + 20, back.size)
    }
}
