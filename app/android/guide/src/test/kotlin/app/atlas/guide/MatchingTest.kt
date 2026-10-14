package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LocalFrame
import app.atlas.model.Fix
import app.atlas.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The map matcher, the route index and the arrival-time estimate. */
class MatchingTest {
    private val t0 = Instant.parse("2026-10-13T08:00:00Z")

    @Test fun toleranceGrowsWithPoorAccuracyAndIsWideOnWater() {
        val r = Sketch(Mode.CAR).leg(90.0, 1000.0, "A").route()
        val car = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.CAR))
        assertEquals(35.0, car.tolerance(5.0), 0.0)
        assertEquals(55.0, car.tolerance(30.0), 0.0)
        assertEquals(105.0, car.tolerance(500.0), 0.0)
        val boat = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.BOAT))
        assertTrue(boat.tolerance(10.0) > car.tolerance(10.0))
        assertTrue(ModeProfile.of(Mode.WALK).offRouteM in 25.0..32.0)
    }

    @Test fun oneWildFixDoesNotTakeYouOffTheRoute() {
        val r = Sketch(Mode.CAR).leg(90.0, 2000.0, "A").route()
        val m = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.CAR))
        val f = LocalFrame(WESTERKERK)
        for (i in 0..20) {
            val jump = i == 10 || i == 11
            val p = f.toLatLon(i * 14.0, if (jump) 120.0 else 0.0)
            val match = m.match(Fix(p, t0.plusSeconds(i.toLong()), 10.0, 14.0, if (jump) 0.0 else 90.0))
            assertFalse("fix $i", match.offRoute)
            if (!jump) assertEquals(i * 14.0, match.along, 1.0)
        }
    }

    @Test fun backOnTheRouteNeedsTwoGoodFixes() {
        val r = Sketch(Mode.CAR).leg(90.0, 2000.0, "A").route()
        val m = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.CAR))
        val f = LocalFrame(WESTERKERK)
        var t = 0L
        for (i in 0..10) m.match(Fix(f.toLatLon(i * 10.0, 0.0), t0.plusSeconds(t++), 5.0, 10.0, 90.0))
        // wander 100 m north and stay there
        var became = 0
        for (i in 1..10) if (m.match(Fix(f.toLatLon(100.0, i * 12.0), t0.plusSeconds(t++), 5.0, 12.0, 0.0)).becameOffRoute) became++
        assertEquals(1, became)
        assertTrue(m.offRoute)
        // come back to the road and drive on east
        val first = m.match(Fix(f.toLatLon(110.0, 1.0), t0.plusSeconds(t++), 5.0, 10.0, 90.0))
        assertTrue(first.offRoute)
        val second = m.match(Fix(f.toLatLon(120.0, 1.0), t0.plusSeconds(t++), 5.0, 10.0, 90.0))
        assertFalse(second.offRoute)
        assertTrue(second.rejoined)
        assertEquals(120.0, second.along, 1.5)
    }

    @Test fun headingPicksTheRightLegWhereTheRouteDoublesBack() {
        // east 300 m, then back west 300 m, 6 m further north
        val r = Sketch(Mode.WALK).leg(90.0, 300.0, "Out").leg(0.0, 6.0, null).leg(270.0, 300.0, "Back").route()
        val m = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.WALK))
        val f = LocalFrame(WESTERKERK)
        var t = 0L
        for (i in 0..200) m.match(Fix(f.toLatLon(i * 1.4, 0.5), t0.plusSeconds(t++), 5.0, 1.4, 90.0))
        // walking back west on the north side, though nearer the outbound line
        val back = m.match(Fix(f.toLatLon(150.0, 2.5), t0.plusSeconds(t++ + 150), 5.0, 1.4, 270.0))
        assertTrue("matched at ${back.along}", back.along > 300)
    }

    @Test fun nearACornerTheHeadingKeepsYouOnTheStreetYouAreIn() {
        // east 300 m, then north: a fix 8 m short of the corner and 12 m north of the street is nearer the next street
        val r = Sketch(Mode.WALK).leg(90.0, 300.0, "Rozengracht").leg(0.0, 300.0, "Prinsengracht").route()
        val m = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.WALK))
        val f = LocalFrame(WESTERKERK)
        var t = 0L
        for (i in 0..200) m.match(Fix(f.toLatLon(i * 1.4, 0.0), t0.plusSeconds(t++), 5.0, 1.4, 90.0))
        val east = m.match(Fix(f.toLatLon(292.0, 12.0), t0.plusSeconds(t++), 5.0, 1.4, 90.0))
        assertEquals(292.0, east.along, 1.5)
        // once you have turned, the same spot belongs to the next street
        val turned = MapMatcher(RouteIndex(r), ModeProfile.of(Mode.WALK))
        t = 0L
        for (i in 0..205) turned.match(Fix(f.toLatLon(minOf(i * 1.4, 300.0), (i * 1.4 - 300).coerceAtLeast(0.0)), t0.plusSeconds(t++), 5.0, 1.4, if (i * 1.4 < 300) 90.0 else 0.0))
        val north = turned.match(Fix(f.toLatLon(292.0, 12.0), t0.plusSeconds(t++), 5.0, 1.4, 0.0))
        assertEquals(312.0, north.along, 1.5)
    }

    @Test fun routeIndexFindsStepsTimesAndFacts() {
        val sk = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 1000.0, "A").leg(0.0, 500.0, "B")
        sk.span(0.0, 1000.0, "maxspeed", "50").span(1000.0, 1500.0, "maxspeed", "30")
        val idx = RouteIndex(sk.route())
        assertEquals(listOf(0.0, 1000.0, 1500.0), idx.stepAlong.map { Math.round(it).toDouble() })
        assertEquals(0, idx.stepAt(999.0))
        assertEquals(1, idx.stepAt(1000.5))
        assertEquals(2, idx.stepAt(1600.0))
        assertEquals(150.0, idx.plannedDuration, 0.5)
        assertEquals(50.0, idx.plannedTimeAt(500.0), 0.5)
        assertEquals(10.0, idx.plannedSpeedAt(1200.0)!!, 0.01)
        assertEquals("50", idx.fact("maxspeed", 5))
        assertEquals("30", idx.factsAt(120)["maxspeed"])
        assertEquals(null, idx.fact("surface", 5))
    }

    @Test fun theArrivalTimeLearnsYourPace() {
        val r = Sketch(Mode.BIKE, speed = 5.0).leg(90.0, 3000.0, "A").route()
        val idx = RouteIndex(r)
        val eta = EtaEstimator(ModeProfile.of(Mode.BIKE))
        assertEquals(600.0, eta.remaining(idx, 0.0), 0.5)
        // riding at 4 m/s where 5 was planned: after ten minutes the estimate has learned most of it
        for (s in 0..600) eta.update(idx, s * 4.0, t0.plusSeconds(s.toLong()), 4.0, onRoute = true)
        val left = eta.remaining(idx, 2400.0)
        assertTrue("estimate $left s for 600 m at 4 m/s", left in 140.0..152.0)
        assertTrue(eta.currentPace < 0.85)
        // off the route the pace is kept, not learned
        val pace = eta.currentPace
        eta.update(idx, 2400.0, t0.plusSeconds(700), 0.0, onRoute = false)
        assertEquals(pace, eta.currentPace, 0.0)
        assertTrue(Geo.distance(r.points.first(), r.points.last()) > 2990)
    }

    @Test fun theArrivalTimeIsNotFooledByGpsNoise() {
        // a walker on plan: a fix wanders further in a second than the walker goes, and must not make the pace look faster
        val r = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 3000.0, "Westerstraat").route()
        for (noise in listOf(5.0, 10.0)) {
            var sum = 0.0
            for (seed in 1L..5L) {
                val d = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), Simulator(r, speed = 1.4, noise = noise, seed = seed).fixes())
                val mid = d.states[d.states.size / 2]
                sum += mid.remainingTime / ((3000 - mid.along) / 1.4)
            }
            assertEquals("$noise m of noise", 1.0, sum / 5, 0.05)
        }
    }

    @Test fun theArrivalTimeCountsTheChargingStops() {
        // 100 km at 25 m/s with a 30-minute charge at 50 km: the router's duration has it, the steps do not
        val sk = Sketch(Mode.CAR, speed = 25.0, spacing = 500.0).leg(Leg(90.0, 100_000.0, null, ref = "A1"))
        val charger = app.atlas.model.Place(null, sk.beside(50_000.0, 40.0), mapOf("amenity" to "charging_station"), extra = mapOf("atlas:charge_minutes" to "30"))
        val plain = sk.route()
        val r = plain.copy(duration = plain.duration + 1800, stops = listOf(charger))
        val idx = RouteIndex(r)
        assertEquals(5800.0, idx.plannedDuration, 1.0)
        assertEquals(1800.0, idx.plannedCharging, 0.0)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        val sim = Simulator(r, speed = 25.0, interval = 10.0).fixes().toList()
        val toStop = sim.take(sim.indexOfFirst { Geo.distance(it.location, r.line.pointAt(50_000.0)) < 30 } + 1)
        for (f in toStop) nav.update(f)
        assertEquals(2000.0 + 1800.0, nav.state!!.remainingTime, 60.0)
        // twenty minutes at the charger: ten of the thirty still to come, and the pace is no slower for the wait
        val at = toStop.last()
        for (k in 1..120) nav.update(at.copy(time = at.time.plusSeconds(k * 10L), speed = 0.0, bearing = null))
        assertEquals(2000.0 + 600.0, nav.state!!.remainingTime, 60.0)
        // driving on
        val rest = sim.drop(toStop.size).map { it.copy(time = it.time.plusSeconds(1200)) }
        for (f in rest.take(40)) nav.update(f)
        assertEquals(2000.0 - 400.0, nav.state!!.remainingTime, 60.0)
    }

    @Test fun gradesFindSustainedClimbsAndIgnoreBumps() {
        val along = DoubleArray(301) { it * 10.0 }
        // 0–1000 flat with 2 m bumps, 1000–1500 up 30 m, 1500–2000 flat, 2000–2400 down 40 m, then flat
        val elev = DoubleArray(301) { i ->
            val x = i * 10.0
            val bump = if (i % 7 == 0) 2.0 else 0.0
            bump + when {
                x < 1000 -> 0.0
                x < 1500 -> (x - 1000) * 0.06
                x < 2000 -> 30.0
                x < 2400 -> 30 - (x - 2000) * 0.1
                else -> -10.0
            }
        }
        val g = Grades.find(along, elev, minPercent = 4.0, minLength = 200.0)
        assertEquals(2, g.size)
        assertEquals(1000.0, g[0].fromAlong, 30.0)
        assertEquals(1500.0, g[0].toAlong, 50.0)
        assertEquals(6.0, g[0].percent, 0.8)
        assertEquals(-10.0, g[1].percent, 1.0)
        assertTrue(Grades.find(along, DoubleArray(301), 4.0, 200.0).isEmpty())
    }

    @Test fun modeTimingFollowsTheBrief() {
        val car = ModeProfile.of(Mode.CAR)
        assertEquals(1500.0, car.prepare!!.at(13.9), 60.0)
        assertEquals(400.0, car.approach.at(13.9), 30.0)
        assertEquals(100.0, car.now.at(13.9), 0.0)
        // on a motorway the leads scale with speed
        assertEquals(180.0, car.now.at(30.0), 0.0)
        val bike = ModeProfile.of(Mode.BIKE)
        assertEquals(270.0, bike.prepare!!.at(4.5), 25.0)
        assertEquals(67.5, bike.approach.at(4.5), 10.0)
        val walk = ModeProfile.of(Mode.WALK)
        assertEquals(null, walk.prepare)
        assertEquals(100.0, walk.approach.at(1.4), 5.0)
        assertEquals(20.0, walk.now.at(1.4), 0.0)
        val boat = ModeProfile.of(Mode.SAIL)
        assertEquals(120.0 * 3.0, boat.approach.at(3.0), 0.0)
        assertEquals(30.0 * 3.0, boat.now.at(3.0), 0.0)
        // trucks are earlier than cars, longer ones earlier still
        val truck = ModeProfile.of(Mode.TRUCK, app.atlas.model.Vehicle(lengthM = 12.0, weightT = 18.0))
        val road = ModeProfile.of(Mode.TRUCK, app.atlas.model.Vehicle(lengthM = 25.25, weightT = 60.0))
        assertTrue(truck.approach.at(20.0) > car.approach.at(20.0))
        assertTrue(road.approach.at(20.0) > truck.approach.at(20.0))
        assertEquals(ModeGroup.WALK, Mode.WHEELCHAIR.group)
        assertEquals(ModeGroup.BIKE, Mode.EBIKE.group)
        assertEquals(ModeGroup.CAR, Mode.MOTORCYCLE.group)
        assertEquals(ModeGroup.BOAT, Mode.SAIL.group)
    }
}
