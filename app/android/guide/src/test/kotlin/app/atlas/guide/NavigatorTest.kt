package app.atlas.guide

import app.atlas.model.Lane
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigator sessions on hand-built routes: what is said, when, in which language, once. */
class NavigatorTest {

    /** Westerkerk east along Raadhuisstraat for 1.2 km, left onto Singel for 800 m, the destination on the right. */
    private fun lRoute(mode: Mode, speed: Double): Route = Sketch(mode, speed = speed)
        .leg(90.0, 1200.0, "Raadhuisstraat")
        .leg(0.0, 800.0, "Singel")
        .route(arrive = Turn.RIGHT)

    private fun run(route: Route, settings: GuideSettings, speed: Double): Drive =
        drive(Navigator(route, settings), Simulator(route, speed = speed).fixes())

    /** Asserts the spoken texts exactly, and that each was said with the distance to its maneuver in the given range. */
    private fun assertSaid(d: Drive, vararg expected: Pair<String, ClosedFloatingPointRange<Double>>) {
        assertEquals(d.toString(), expected.map { it.first }, d.texts)
        for ((i, e) in expected.withIndex()) {
            val toGo = d.said[i].toGo
            assertTrue("«${e.first}» said ${"%.0f".format(toGo)} m before, expected ${e.second}\n$d", toGo in e.second)
        }
    }

    private fun assertNoDuplicates(d: Drive) {
        val ids = d.said.map { it.id }
        assertEquals("a stage was spoken twice:\n$d", ids.size, ids.toSet().size)
        for (i in 1 until d.texts.size) assertFalse("said twice in a row: ${d.texts[i]}", d.texts[i] == d.texts[i - 1])
    }

    @Test fun carInTheCitySpeaksPrepareApproachAndNowOnTime() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR), 13.9)
        assertSaid(
            d,
            "Head east on Raadhuisstraat. In 1 kilometre, turn left onto Singel" to 1199.0..1201.0,
            "In 400 metres, turn left onto Singel" to 400.0..500.0,
            "Turn left onto Singel" to 90.0..130.0,
            "In 400 metres, you will arrive at your destination, on the right" to 400.0..500.0,
            "Your destination is on the right" to 90.0..150.0,
            "You have arrived" to 0.0..40.0,
        )
        assertNoDuplicates(d)
        assertEquals(1, d.arrivals)
    }

    @Test fun bikeHearsTheTurnAt250And60MetresAndNow() {
        val d = run(lRoute(Mode.BIKE, 4.5), GuideSettings(mode = Mode.BIKE), 4.5)
        assertSaid(
            d,
            "Head east on Raadhuisstraat for 1 kilometre" to 1199.0..1201.0,
            "In 300 metres, turn left onto Singel" to 250.0..330.0,
            "In 70 metres, turn left onto Singel" to 60.0..90.0,
            "Now turn left onto Singel" to 10.0..30.0,
            "In 80 metres, you will arrive at your destination, on the right" to 60.0..90.0,
            "Your destination is on the right" to 10.0..30.0,
            "You have arrived" to 0.0..20.0,
        )
        assertNoDuplicates(d)
    }

    @Test fun walkingHearsTheTurnAt100And20MetresAndTheStretchAfter() {
        val d = run(lRoute(Mode.WALK, 1.4), GuideSettings(mode = Mode.WALK), 1.4)
        assertSaid(
            d,
            "Head east on Raadhuisstraat for 1 kilometre" to 1199.0..1201.0,
            "In 100 metres, turn left onto Singel" to 95.0..110.0,
            "Turn left onto Singel" to 15.0..30.0,
            "Continue on Singel for 800 metres" to 790.0..802.0,
            "In 100 metres, you will arrive at your destination, on the right" to 95.0..110.0,
            "Your destination is on the right" to 15.0..30.0,
            "You have arrived" to 0.0..12.0,
        )
        assertNoDuplicates(d)
    }

    @Test fun speaksDutch() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR, language = "nl"), 13.9)
        assertEquals(
            listOf(
                "Neem Raadhuisstraat in oostelijke richting. Over 1 kilometer, ga linksaf naar Singel",
                "Over 400 meter, ga linksaf naar Singel",
                "Ga linksaf naar Singel",
                "Over 400 meter, u zult arriveren bij uw bestemming, deze bevindt zich aan de rechterkant",
                "Uw bestemming is aan de rechterkant",
                "U bent gearriveerd",
            ),
            d.texts,
        )
    }

    @Test fun speaksGerman() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR, language = "de-DE"), 13.9)
        assertEquals(
            listOf(
                "Fahren Sie Richtung Osten auf Raadhuisstraat. In einem Kilometer, links abbiegen auf Singel",
                "In 400 Metern, links abbiegen auf Singel",
                "Links abbiegen auf Singel",
                "In 400 Metern, erreichen Sie Ihr Ziel, es befindet sich rechts",
                "Ihr Ziel befindet sich rechts",
                "Sie haben Ihr Ziel erreicht",
            ),
            d.texts,
        )
    }

    @Test fun speaksFrench() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR, language = "fr"), 13.9)
        assertEquals(
            listOf(
                "Se diriger vers l’est sur Raadhuisstraat. Dans 1 kilomètre, tourner à gauche sur Singel",
                "Dans 400 mètres, tourner à gauche sur Singel",
                "Tourner à gauche sur Singel",
                "Dans 400 mètres, vous arriverez à votre destination, sur la droite",
                "Votre destination est sur la droite",
                "Vous êtes arrivé",
            ),
            d.texts,
        )
    }

    @Test fun unknownLanguageFallsBackToEnglish() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR, language = "cs"), 13.9)
        assertEquals("Head east on Raadhuisstraat. In 1 kilometre, turn left onto Singel", d.texts.first())
    }

    @Test fun roundaboutSaysWhichExit() {
        val r = Sketch(Mode.CAR, speed = 13.9).leg(90.0, 1500.0, "Haarlemmerweg")
            .leg(Leg(45.0, 900.0, "Westelijk Halfrond", type = ManeuverType.ROUNDABOUT, exit = 2)).route()
        val d = run(r, GuideSettings(mode = Mode.CAR), 13.9)
        assertSaid(
            d,
            "Head east on Haarlemmerweg. In 1.5 kilometres, enter the roundabout and take the 2nd exit onto Westelijk Halfrond" to 1499.0..1501.0,
            "In 500 metres, enter the roundabout and take the 2nd exit onto Westelijk Halfrond" to 400.0..500.0,
            "Enter the roundabout and take the 2nd exit onto Westelijk Halfrond" to 150.0..180.0,
            "In 500 metres, you will arrive at your destination" to 400.0..500.0,
            "You have arrived" to 0.0..40.0,
        )
    }

    @Test fun motorwayExitIsAnnouncedAt3And1KilometresWithLanes() {
        val lanes = listOf(Lane(setOf(Turn.STRAIGHT), false), Lane(setOf(Turn.STRAIGHT), false), Lane(setOf(Turn.SLIGHT_RIGHT), true))
        val r = Sketch(Mode.CAR, speed = 30.0).leg(Leg(0.0, 6000.0, null, ref = "A10"))
            .leg(Leg(20.0, 600.0, null, type = ManeuverType.RAMP_OFF, turn = Turn.SLIGHT_RIGHT, destinations = "Haarlem", lanes = lanes))
            .leg(Leg(90.0, 2000.0, "Haarlemmerweg")).route()
        val d = run(r, GuideSettings(mode = Mode.CAR), 30.0)
        assertSaid(
            d,
            "Head north on A10 for 6 kilometres" to 5999.0..6001.0,
            "In 3 kilometres, take the ramp towards Haarlem. Keep right" to 2900.0..3300.0,
            "In 1 kilometre, take the ramp towards Haarlem. Keep right" to 900.0..1100.0,
            "Take the ramp towards Haarlem" to 180.0..300.0,
            // told once on the ramp: the fix exactly on the fork is not yet past it
            "In 500 metres, turn right onto Haarlemmerweg" to 500.0..601.0,
            "Turn right onto Haarlemmerweg" to 180.0..300.0,
            "In 1 kilometre, you will arrive at your destination" to 900.0..1100.0,
            "You have arrived" to 0.0..40.0,
        )
        val state = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), Simulator(r, speed = 30.0).fixes().take(10)).states.last()
        assertEquals(3, state.lanes.size)
        assertEquals(ManeuverType.RAMP_OFF, state.next?.maneuver?.type)
    }

    @Test fun closeTurnsAreChained() {
        val r = Sketch(Mode.CAR, speed = 8.0).leg(90.0, 900.0, "Rozengracht").leg(180.0, 30.0, "Marnixstraat")
            .leg(90.0, 120.0, "Elandsgracht").route(arrive = Turn.LEFT)
        val car = run(r, GuideSettings(mode = Mode.CAR), 8.0)
        assertEquals(
            listOf(
                "Head east on Rozengracht. In 900 metres, turn right onto Marnixstraat",
                "In 300 metres, turn right onto Marnixstraat, then immediately turn left onto Elandsgracht",
                "Turn right onto Marnixstraat, then immediately turn left onto Elandsgracht",
                "Your destination is on the left",
                "You have arrived",
            ),
            car.texts,
        )
        val bike = run(r, GuideSettings(mode = Mode.BIKE), 4.5)
        assertEquals(
            listOf(
                "Head east on Rozengracht",
                "In 300 metres, turn right onto Marnixstraat",
                "In 80 metres, turn right onto Marnixstraat, then turn left onto Elandsgracht",
                "Now turn right onto Marnixstraat, then turn left onto Elandsgracht",
                "Now turn left onto Elandsgracht",
                "In 80 metres, you will arrive at your destination, on the left",
                "Your destination is on the left",
                "You have arrived",
            ),
            bike.texts,
        )
        assertNoDuplicates(car)
        assertNoDuplicates(bike)
        // the left said "immediately" with the right still gets its own sound and quick taps at the moment to turn
        val haptics = car.cues.filter { it.second is Cue.Haptic }.map { it.first to (it.second as Cue.Haptic) }
        assertEquals(listOf(HapticMeaning.RIGHT, HapticMeaning.RIGHT, HapticMeaning.LEFT, HapticMeaning.ARRIVE), haptics.map { it.second.meaning })
        val leftAt = haptics[2].first
        val stateThen = car.states[leftAt.toInt()]
        assertTrue("left buzzed ${930 - stateThen.along} m before the turn", 930 - stateThen.along in 0.0..30.0)
        assertTrue(car.cues.any { it.first == leftAt && it.second == Cue.Earcon(EarconKind.TURN_LEFT, -0.7f) })
    }

    @Test fun aTurnJustBeforeTheDestinationTellsTheSideAtOnce() {
        val r = Sketch(Mode.CAR, speed = 10.0).leg(90.0, 1000.0, "Rozengracht").leg(0.0, 90.0, "North 12th").route(arrive = Turn.LEFT)
        val d = run(r, GuideSettings(mode = Mode.CAR), 10.0)
        assertEquals(
            listOf(
                "Head east on Rozengracht. In 1 kilometre, turn left onto North 12th",
                "In 300 metres, turn left onto North 12th. Your destination is on the left",
                "Turn left onto North 12th. Your destination is on the left",
                "You have arrived",
            ),
            d.texts,
        )
    }

    @Test fun arrivesOnceAndThenFallsSilent() {
        val r = lRoute(Mode.CAR, 13.9)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        val d = drive(nav, Simulator(r, speed = 13.9, dwell = 5).fixes(), untilEnd = true)
        assertEquals(1, d.arrivals)
        val arrivedAt = d.cues.first { it.second == Cue.Arrived }.first
        assertTrue(d.cues.none { it.first > arrivedAt })
        val last = d.states.last()
        assertTrue(last.arrived)
        assertEquals(0.0, last.remaining, 0.0)
        assertEquals(1.0, last.progress, 0.02)
        assertEquals(Side.RIGHT, last.arrivalSide)
        assertTrue(d.cues.any { it.second is Cue.Haptic && (it.second as Cue.Haptic).meaning == HapticMeaning.ARRIVE })
    }

    @Test fun stateTracksProgressTimeAndFacts() {
        val sk = Sketch(Mode.CAR, speed = 13.9).leg(90.0, 1200.0, "Raadhuisstraat").leg(0.0, 800.0, "Singel")
        sk.span(0.0, 1200.0, "maxspeed", "50").span(1200.0, 2000.0, "maxspeed", "30").span(0.0, 2000.0, "surface", "asphalt")
        val r = sk.route(arrive = Turn.RIGHT)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.CAR)), Simulator(r, speed = 13.9).fixes())
        val s = d.states
        for (i in 1 until s.size) {
            assertTrue(s[i].along >= s[i - 1].along - 0.5)
            assertTrue(s[i].progress >= s[i - 1].progress - 1e-3)
            assertTrue(s[i].remainingTime <= s[i - 1].remainingTime + 1.0)
        }
        assertEquals("50", s[10].maxspeed)
        assertEquals("asphalt", s[10].surface)
        assertEquals("30", s[100].maxspeed)
        assertEquals(0, s[10].stepIndex)
        assertEquals(ManeuverType.TURN, s[10].next?.maneuver?.type)
        assertEquals(ManeuverType.ARRIVE, s[10].following?.maneuver?.type)
        assertEquals(1200.0 - s[10].along, s[10].distanceToManeuver, 1.0)
        // planned 50 km/h and driven at 50 km/h: the arrival time holds
        assertEquals(2000 / 13.9 - 10, s[10].remainingTime, 3.0)
        assertEquals(s[10].time.plusMillis((s[10].remainingTime * 1000).toLong()), s[10].eta)
        assertTrue(s[10].confidence > 0.8)
    }

    @Test fun theDoorDecidesTheArrivalSide() {
        val sk = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 300.0, "Westerstraat")
        val door = sk.beside(295.0, -12.0)
        val r = sk.route()
        val nav = Navigator(r, GuideSettings(mode = Mode.WALK), destination = door)
        val d = drive(nav, Simulator(r, speed = 1.4).fixes())
        assertEquals(Side.LEFT, d.states.last().arrivalSide)
        assertEquals("Your destination is on the left", d.texts[d.texts.size - 2])
    }

    @Test fun degenerateRoutesDoNotBreakTheSession() {
        val point = Route(Mode.CAR, listOf(WESTERKERK), distance = 0.0, duration = 0.0, steps = emptyList())
        val nav = Navigator(point, GuideSettings(mode = Mode.CAR))
        val g = nav.update(app.atlas.model.Fix(WESTERKERK, java.time.Instant.parse("2026-10-13T08:00:00Z")))
        assertTrue(g.state.arrived)
        assertEquals(listOf<Cue>(Cue.Earcon(EarconKind.ARRIVE), Haptics.arrive(), Cue.Arrived), g.cues)
        assertTrue(nav.repeat().isEmpty())
        val bare = Sketch(Mode.CAR).leg(90.0, 500.0, "A").route().copy(steps = emptyList())
        val d = drive(Navigator(bare, GuideSettings(mode = Mode.CAR)), Simulator(bare, speed = 10.0).fixes())
        assertEquals(1, d.arrivals)
        assertTrue(d.said.isEmpty())
    }

    @Test fun minimalVerbositySaysOnlyTheBareTurns() {
        val d = run(lRoute(Mode.CAR, 13.9), GuideSettings(mode = Mode.CAR, verbosity = Verbosity.MINIMAL), 13.9)
        assertEquals(listOf("In 400 metres, left", "Left", "In 400 metres, you will arrive", "Your destination is on the right", "You have arrived"), d.texts)
    }

    @Test fun withoutVoiceItStillPlaysAndVibrates() {
        val r = lRoute(Mode.BIKE, 4.5)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.BIKE, voice = false)), Simulator(r, speed = 4.5).fixes())
        assertTrue(d.said.isEmpty())
        val earcons = d.cues.map { it.second }.filterIsInstance<Cue.Earcon>().map { it.kind }
        assertEquals(listOf(EarconKind.TURN_LEFT, EarconKind.TURN_LEFT, EarconKind.ARRIVE, EarconKind.ARRIVE, EarconKind.ARRIVE), earcons)
        val haptics = d.cues.map { it.second }.filterIsInstance<Cue.Haptic>().map { it.meaning }
        assertEquals(listOf(HapticMeaning.LEFT, HapticMeaning.LEFT, HapticMeaning.ARRIVE), haptics)
        // the second left is the urgent one: faster taps
        val lefts = d.cues.map { it.second }.filterIsInstance<Cue.Haptic>().filter { it.meaning == HapticMeaning.LEFT }
        assertTrue(lefts[1].totalMillis < lefts[0].totalMillis)
        assertEquals(1, d.arrivals)
    }

    @Test fun repeatGivesTheNextInstructionWithAFreshDistance() {
        val r = lRoute(Mode.CAR, 13.9)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        assertTrue(nav.repeat().isEmpty())
        val fixes = Simulator(r, speed = 13.9).fixes().toList()
        for (f in fixes.take(40)) nav.update(f)
        val text = (nav.repeat().single() as Cue.Speak).text
        assertEquals("In 600 metres, turn left onto Singel", text)
        for (f in fixes.drop(40).take(41)) nav.update(f)
        assertEquals("Turn left onto Singel", (nav.repeat().single() as Cue.Speak).text)
        val summary = (nav.summary().single() as Cue.Speak).text
        assertTrue(summary, summary.matches(Regex("""\d+ metres to go, 1 minute, arriving at \d+:\d\d""")))
    }

    @Test fun aViaPointIsNotGivenTheDestinationsSide() {
        // the destination is on the left; the via point on the way has no side of its own
        val r = Sketch(Mode.CAR, speed = 13.9).leg(90.0, 1500.0, "Main Street").leg(Leg(90.0, 1500.0, "Main Street", type = ManeuverType.WAYPOINT))
            .leg(0.0, 1000.0, "Oak Avenue").route(arrive = Turn.LEFT)
        val d = run(r, GuideSettings(mode = Mode.CAR), 13.9)
        val via = d.texts.filter { "1st destination" in it }
        assertEquals(d.toString(), listOf(
            "Head east on Main Street. In 1.5 kilometres, you will arrive at your 1st destination",
            "In 400 metres, you will arrive at your 1st destination",
            "You will arrive at your 1st destination",
            "You have arrived at your 1st destination",
        ), via)
        assertEquals("Your destination is on the left", d.texts[d.texts.size - 2])
        assertNoDuplicates(d)
    }

    @Test fun joiningOnTheWaySaysNothingOfWhatIsBehind() {
        // the app restarted during the trip: the turn onto Singel and the via point are behind
        val r = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 1200.0, "Raadhuisstraat").leg(0.0, 300.0, "Singel")
            .leg(Leg(0.0, 1500.0, null, type = ManeuverType.WAYPOINT)).leg(90.0, 900.0, "Herengracht").route(arrive = Turn.RIGHT)
        val d = drive(Navigator(r, GuideSettings(mode = Mode.WALK)), Simulator(r, speed = 1.4).fixes().drop(1214))
        assertEquals("Continue straight for 1.5 kilometres", d.texts.first())
        assertFalse(d.toString(), d.texts.any { "Raadhuisstraat" in it || "Singel" in it || "1st destination" in it })
        assertTrue(d.texts.contains("Turn right onto Herengracht"))
        assertEquals(1, d.arrivals)
    }

    @Test fun aNewRouteForgetsTheOldOnesDistances() {
        val r = lRoute(Mode.CAR, 13.9)
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        for (f in Simulator(r, speed = 13.9).fixes().take(40)) nav.update(f)
        assertEquals("In 600 metres, turn left onto Singel", (nav.repeat().single() as Cue.Speak).text)
        val other = Sketch(Mode.CAR, origin = nav.state!!.snapped, speed = 13.9).leg(90.0, 300.0, "Raadhuisstraat").leg(180.0, 500.0, "Herengracht").route()
        nav.replaceRoute(other)
        // nothing to repeat until a fix on the new route says where on it you are
        assertTrue(nav.repeat().isEmpty())
        assertEquals(0.0, nav.upcoming().sumOf { it.second }, 0.0)
        nav.update(Simulator(other, speed = 13.9, start = nav.state!!.time.plusSeconds(1)).fixes().first())
        assertEquals("In 300 metres, turn right onto Herengracht", (nav.repeat().single() as Cue.Speak).text)
    }

    @Test fun drivingTheWrongWayAsksToTurnRound() {
        val r = Sketch(Mode.CAR, speed = 13.9).leg(90.0, 3000.0, "Westerstraat").route()
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR))
        val fixes = Simulator(r, speed = 13.9).fixes().toList()
        // drive 1 km east, then back west along the route
        val out = fixes.take(72)
        val back = fixes.take(72).reversed().mapIndexed { i, f ->
            f.copy(time = out.last().time.plusSeconds(i + 1L), bearing = 270.0)
        }
        val d = drive(nav, (out + back).asSequence(), untilEnd = true)
        assertEquals(listOf("Head east on Westerstraat for 3 kilometres", "Wrong way. Turn around when possible"), d.texts)
        assertEquals(1, d.reroutes.size)
    }

    @Test fun truckHearsTheLowBridgeAgainstItsHeight() {
        val sk = Sketch(Mode.TRUCK, speed = 22.0).leg(Leg(90.0, 9000.0, "N200"))
        sk.hazard(3000.0, "maxheight", 3.8, mapOf("avoided" to "yes"))
        sk.hazard(6000.0, "maxheight", 3.9)
        sk.hazard(7000.0, "descent", 8.0, mapOf("length" to "2000"))
        val r = sk.route()
        val settings = GuideSettings(mode = Mode.TRUCK, vehicle = app.atlas.model.Vehicle(heightM = 4.0, lengthM = 16.5, weightT = 40.0))
        val d = run(r, settings, 22.0)
        assertEquals(
            listOf(
                "Head east on N200 for 9 kilometres",
                "Height limit 3.8 metres ahead, the route avoids it",
                "Warning: height limit 3.9 metres, your truck is 4 metres",
                "Warning: height limit 3.9 metres, your truck is 4 metres",
                "Use engine brake: 2 kilometres at 8 percent",
                "In 1 kilometre, you will arrive at your destination",
                "You have arrived",
            ),
            d.texts,
        )
        val warnings = d.said.filter { it.text.startsWith("Warning") }
        assertEquals(Priority.CRITICAL, warnings[0].priority)
        // first far ahead, then once more close by
        assertTrue(6000 - warnings[0].along in 1800.0..2600.0)
        assertTrue(6000 - warnings[1].along in 300.0..800.0)
        assertTrue(d.cues.any { (it.second as? Cue.Haptic)?.meaning == HapticMeaning.HAZARD })
    }

    @Test fun boatOnACanalHearsTheBridgeTheMarkAndTheLock() {
        val sk = Sketch(Mode.BOAT, speed = 3.0).leg(10.0, 3000.0, "Amstel")
        sk.hazard(1500.0, "bridge", 41.0)
        sk.hazard(1700.0, "buoy", null, mapOf("seamark:buoy_lateral:colour" to "red", "seamark:name" to "6"), location = sk.beside(1700.0, 30.0))
        sk.hazard(2500.0, "lock", null, mapOf("vhf_channel" to "20"))
        val r = sk.route()
        val settings = GuideSettings(
            mode = Mode.BOAT, units = app.atlas.geo.UnitSystem.NAUTICAL, heightsInFeet = true,
            vehicle = app.atlas.model.Vehicle(airDraftM = 12.8),
        )
        val d = run(r, settings, 3.0)
        assertSaid(
            d,
            "Head north on Amstel" to 2999.0..3001.0,
            "Bridge in half a mile, clearance 135 feet, you're clear" to 0.0..3000.0,
            "Keep red 6 to starboard" to 0.0..3000.0,
            "Lock in half a mile, VHF channel 20" to 0.0..3000.0,
            "In 2 cables, you will arrive at your destination" to 330.0..400.0,
            "You have arrived" to 0.0..30.0,
        )
        val mark = d.first("Keep red 6")
        assertTrue(mark.pan > 0)
        assertTrue(1700 - mark.along in 150.0..250.0)
        assertTrue(1500 - d.first("Bridge in").along in 800.0..1000.0)
    }
}
