package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.model.Mode
import app.atlas.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leaving the route, coming back to it, and never crying wolf on noisy GPS. */
class OffRouteTest {

    private fun straight(mode: Mode, speed: Double, length: Double = 5000.0) =
        Sketch(mode, speed = speed).leg(90.0, length, "Westerstraat").route()

    @Test fun aDetourIsCaughtAfterAFewFixesAndOnlyOnce() {
        val r = straight(Mode.CAR, 13.9, 3000.0)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        val d = drive(nav, Simulator(r, speed = 13.9, detours = listOf(Detour(1000.0, 90.0, 400.0))).fixes(), untilEnd = true)
        assertEquals(1, d.reroutes.size)
        val (t, cue) = d.reroutes.single()
        // left at 72 s; beyond 35 m from 75 s; three fixes out and held for the car's 3 s
        assertEquals(78.0, t, 1.0)
        val off = Geo.distance(cue.from.location, r.line.pointAt(1000.0))
        assertTrue("caught $off m out", off in 60.0..100.0)
        assertEquals(listOf("Head east on Westerstraat for 3 kilometres", "Off route. Finding a new way"), d.texts)
        assertEquals(Priority.HIGH, d.said.last().priority)
        assertTrue(d.cues.any { (it.second as? Cue.Earcon)?.kind == EarconKind.OFF_ROUTE })
        assertEquals(5, d.cues.map { it.second }.filterIsInstance<Cue.Haptic>().single { it.meaning == HapticMeaning.OFF_ROUTE }.pulses)
        // the state keeps its place on the route while off it
        val last = d.states.last()
        assertTrue(last.offRoute)
        assertEquals(1000.0, last.along, 20.0)
        assertTrue(last.offset > 300)
    }

    @Test fun walkersAreToldOnlyOnce30MetresOff() {
        val r = straight(Mode.WALK, 1.4, 1000.0)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), Simulator(r, speed = 1.4, detours = listOf(Detour(300.0, -80.0, 100.0))).fixes(), untilEnd = true)
        assertEquals(1, d.texts.count { it.startsWith("Off route") })
        val off = Geo.distance(d.reroutes.first().second.from.location, r.line.pointAt(300.0))
        assertTrue("caught $off m out", off in 30.0..45.0)
    }

    @Test fun stillOffAfterHalfAMinuteAsksForARouteAgainQuietly() {
        val r = straight(Mode.CAR, 10.0, 3000.0)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), Simulator(r, speed = 10.0, detours = listOf(Detour(500.0, 90.0, 800.0))).fixes(), untilEnd = true)
        assertEquals(3, d.reroutes.size)
        assertEquals(30.0, d.reroutes[1].first - d.reroutes[0].first, 0.01)
        assertEquals(1, d.texts.count { it.startsWith("Off route") })
    }

    @Test fun comingBackBeforeTheNewRouteResumesTheOldOne() {
        val r = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 2000.0, "Rozengracht").leg(0.0, 600.0, "Singel").route(arrive = Turn.LEFT)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), Simulator(r, speed = 10.0, detours = listOf(Detour(400.0, 90.0, 120.0, rejoin = true))).fixes())
        assertEquals(1, d.reroutes.size)
        val rejoined = d.states.indexOfLast { it.offRoute }
        assertTrue(rejoined > 0 && rejoined < d.states.size - 10)
        assertFalse(d.states.last().offRoute)
        assertEquals(1, d.arrivals)
        assertTrue(d.texts.contains("Turn left onto Singel"))
    }

    @Test fun replaceRouteKeepsTheSessionAndSaysNewRoute() {
        val r = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 1200.0, "Rozengracht").leg(0.0, 600.0, "Singel").route(arrive = Turn.RIGHT)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        val first = drive(nav, Simulator(r, speed = 10.0, detours = listOf(Detour(600.0, 90.0, 300.0))).fixes(), untilEnd = true)
        val from = first.reroutes.first().second.from
        val paceBefore = nav.state!!
        // the new route: back north to Rozengracht, on east to Singel, left, as before
        val back = Sketch(Mode.CAR, origin = from.location, speed = 10.0)
        back.leg(0.0, Geo.distance(from.location, r.line.pointAt(600.0)) + 0.5, "Lijnbaansgracht")
        val corner = r.line.pointAt(1200.0)
        back.leg(90.0, Geo.distance(back.at(1e9), corner), "Rozengracht")
        back.leg(0.0, 600.0, "Singel")
        val newRoute = back.route(arrive = Turn.RIGHT)
        nav.replaceRoute(newRoute)
        assertEquals(newRoute, nav.route)
        val second = drive(nav, Simulator(newRoute, speed = 10.0, start = from.time.plusSeconds(1)).fixes())
        assertEquals(listOf("New route. Turn right onto Rozengracht", "In 300 metres, turn left onto Singel"), second.texts.take(2))
        assertTrue(second.cues.first().second == Cue.Earcon(EarconKind.REROUTED))
        // the left onto Singel was announced as before; the arrival comes once
        assertTrue(second.texts.contains("Turn left onto Singel"))
        assertEquals(1, second.arrivals)
        assertFalse(paceBefore.arrived)
    }

    @Test fun fifteenMetresOfNoiseNeverRaisesAFalseAlarm() {
        for ((mode, speed) in listOf(Mode.CAR to 13.9, Mode.CAR to 30.0, Mode.BIKE to 4.5, Mode.WALK to 1.4, Mode.TRUCK to 22.0, Mode.BOAT to 3.0)) {
            val r = straight(mode, speed, if (speed > 10) 10_000.0 else 3000.0)
            for (seed in 1L..5L) {
                val d = drive(Navigator(r, GuideSettings(mode = mode)), Simulator(r, speed = speed, noise = 15.0, seed = seed).fixes())
                assertEquals("$mode at $speed m/s, seed $seed: ${d.reroutes.map { it.first }}", 0, d.reroutes.size)
                assertFalse(d.texts.any { it.startsWith("Wrong way") })
                assertEquals("$mode seed $seed", 1, d.arrivals)
            }
        }
    }

    @Test fun noiseAtTheCornersOfACityRouteIsNoAlarmEither() {
        for ((mode, speed) in listOf(Mode.CAR to 13.9, Mode.BIKE to 4.5, Mode.WALK to 1.4)) {
            val r = Sketch(mode, speed = speed).leg(90.0, 600.0, "Rozengracht").leg(0.0, 300.0, "Prinsengracht")
                .leg(90.0, 200.0, "Leliegracht").leg(180.0, 400.0, "Keizersgracht").route(arrive = Turn.LEFT)
            for (seed in 1L..5L) {
                val d = drive(Navigator(r, GuideSettings(mode = mode)), Simulator(r, speed = speed, noise = 15.0, seed = seed).fixes())
                assertEquals("$mode seed $seed", 0, d.reroutes.size)
                assertEquals(1, d.arrivals)
                // every turn is told, none twice at the same stage
                for (street in listOf("Prinsengracht", "Leliegracht", "Keizersgracht")) assertTrue("$mode $seed\n$d", d.texts.any { it.contains("onto $street") })
                assertEquals(d.said.size, d.said.map { it.id }.toSet().size)
            }
        }
    }

    @Test fun goingStraightOnPastATurnIsCaughtSoon() {
        // the heading still matches the old street, but the way back to the route is behind you
        for ((mode, speed, within) in listOf(Triple(Mode.CAR, 13.9, 100.0), Triple(Mode.BIKE, 4.5, 60.0), Triple(Mode.WALK, 1.4, 50.0))) {
            val r = Sketch(mode, speed = speed).leg(90.0, 1200.0, "Raadhuisstraat").leg(0.0, 800.0, "Singel").route(arrive = Turn.RIGHT)
            val d = drive(Navigator(r, GuideSettings(mode = mode)), Simulator(r, speed = speed, detours = listOf(Detour(1199.0, 0.0, 400.0))).fixes(), untilEnd = true)
            val past = Geo.distance(d.reroutes.first().second.from.location, r.line.pointAt(1200.0))
            assertTrue("$mode caught $past m past the turn", past <= within)
            assertEquals(1, d.texts.count { it.startsWith("Off route") })
        }
    }

    @Test fun aLongTunnelWithoutGpsIsNoDetour() {
        // two minutes without a fix at 108 km/h: the traveller comes out 3.6 km further on
        val r = Sketch(Mode.CAR, speed = 30.0).leg(90.0, 12_000.0, "A10").route()
        val fixes = Simulator(r, speed = 30.0).fixes().toList().filterIndexed { i, _ -> i < 100 || i > 220 }
        val d = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), fixes.asSequence())
        assertEquals(d.toString(), 0, d.reroutes.size)
        assertFalse(d.texts.any { it.startsWith("Off route") })
        assertEquals(221 * 30.0, d.states[100].along, 1.0)
        assertEquals(1, d.arrivals)
    }

    @Test fun cellTowerFixesNeitherSkipTurnsNorRaiseAnAlarm() {
        val r = Sketch(Mode.CAR, speed = 13.9).leg(90.0, 1200.0, "Raadhuisstraat").leg(0.0, 400.0, "Singel").leg(90.0, 1000.0, "Herengracht").route(arrive = Turn.RIGHT)
        val fixes = Simulator(r, speed = 13.9).fixes().toList().mapIndexed { i, f ->
            // for five seconds the phone falls back to cell towers: 800 m accurate, a guess 250 m off the route, no course
            if (i in 60..64) f.copy(location = Geo.destination(r.line.pointAt(1450.0), 45.0, 250.0), accuracy = 800.0, bearing = null, speed = null) else f
        }
        val d = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), fixes.asSequence())
        assertEquals(d.toString(), 0, d.reroutes.size)
        // the match holds where it was, and no turn is skipped
        for (i in 60..64) assertEquals(d.states[59].along, d.states[i].along, 0.0)
        assertTrue(d.states[62].remainingTime > 0)
        for (turn in listOf("Turn left onto Singel", "Turn right onto Herengracht")) assertTrue("$turn\n$d", turn in d.texts)
        assertEquals(1, d.arrivals)
    }

    @Test fun aWayOfYourOwnToTheDestinationStillArrives() {
        // east 300 m, north 300 m; the walker leaves at 100 m, goes round the block and comes to the door from the east
        val r = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 300.0, "Westerstraat").leg(0.0, 300.0, "Prinsengracht").route()
        val f = app.atlas.geo.LocalFrame(WESTERKERK)
        val walk = Simulator(r, speed = 1.4).fixes().take(72).toList()
        val t = walk.last().time
        val path = ArrayList<app.atlas.model.Fix>(walk)
        var p = walk.last().location
        fun go(to: app.atlas.geo.LatLon) {
            val b = Geo.bearing(p, to)
            while (Geo.distance(p, to) > 0.5) {
                p = Geo.destination(p, b, minOf(1.4, Geo.distance(p, to)))
                path += app.atlas.model.Fix(p, t.plusSeconds(path.size - 71L), 5.0, 1.4, b)
            }
        }
        go(f.toLatLon(360.0, -40.0))
        go(f.toLatLon(360.0, 300.0))
        go(f.toLatLon(299.5, 300.0))
        val d = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), path.asSequence())
        assertEquals(1, d.arrivals)
        assertEquals("You have arrived at your destination", d.texts.last())
        assertTrue("arrived off the route", d.states[d.states.size - 2].offRoute)
        assertTrue(d.states.last().arrived)
    }

    @Test fun noiseNearACornerDoesNotSwallowTheMomentToTurn() {
        // 15 m of noise is as much as a walker's "now" lead: the fix must not jump onto the next street before the turn
        val r = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 600.0, "Rozengracht").leg(0.0, 300.0, "Prinsengracht")
            .leg(90.0, 200.0, "Leliegracht").leg(180.0, 400.0, "Keizersgracht").route(arrive = Turn.LEFT)
        var missing = 0
        for (seed in 1L..20L) {
            val ids = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), Simulator(r, speed = 1.4, noise = 15.0, seed = seed).fixes()).said.map { it.id }.toSet()
            missing += (1..3).count { "step:$it:now" !in ids }
        }
        assertTrue("$missing of 60 turns had no moment-to-turn cue", missing <= 2)
    }

    @Test fun aRouteThatDoublesBackIsFollowedLegByLeg() {
        // out along a street and back on the other side of it, 12 m apart
        val sk = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 400.0, "Westerstraat").leg(180.0, 12.0, null).leg(270.0, 400.0, "Westerstraat")
        val r = sk.route()
        val d = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), Simulator(r, speed = 1.4, noise = 4.0, seed = 7).fixes())
        assertEquals(0, d.reroutes.size)
        assertEquals(1, d.arrivals)
        val alongs = d.states.map { it.along }
        for (i in 1 until alongs.size) assertTrue("went back at $i: ${alongs[i - 1]} → ${alongs[i]}", alongs[i] >= alongs[i - 1] - 15)
        assertTrue(d.states[d.states.size / 2 + 60].along > 412)
    }
}
