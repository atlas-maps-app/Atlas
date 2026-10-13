package app.atlas.guide

import app.atlas.geo.UnitSystem
import app.atlas.model.Landmark
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.Turn
import app.atlas.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The warnings ahead, mode by mode, from the route's hazards, spans and elevations. */
class CalloutsTest {

    private fun said(route: Route, settings: GuideSettings, speed: Double, start: Instant = Instant.parse("2026-06-21T10:00:00Z")): Drive =
        drive(Navigator(route, settings), Simulator(route, speed = speed, start = start).fixes())

    /** The warnings only: what is said apart from the departure and the arrival. */
    private fun Drive.warnings(): List<String> = texts.filterNot {
        it.startsWith("Head ") || it.startsWith("You have arrived") || it.startsWith("In ") && it.contains("arrive") || it.startsWith("Your destination")
    }

    private fun Drive.distanceBefore(fragment: String, at: Double) = at - first(fragment).along

    @Test fun bikeHearsClimbsSurfacesFerriesStepsAndTramTracks() {
        val sk = Sketch(Mode.BIKE, speed = 4.5).leg(90.0, 4000.0, "Westerstraat")
        sk.hazard(300.0, "climb", 6.2, mapOf("length" to "400"))
        sk.hazard(1000.0, "descent", 7.0, mapOf("length" to "300"))
        sk.hazard(1500.0, "tram_tracks")
        sk.hazard(1900.0, "unpaved", 820.0, mapOf("length" to "820", "surface" to "gravel"))
        sk.hazard(2900.0, "steps")
        sk.hazard(3500.0, "ferry")
        val d = said(sk.route(), GuideSettings(mode = Mode.BIKE), 4.5)
        assertEquals(
            listOf(
                "Climb ahead: 400 metres at 6 percent",
                "Descent ahead: 300 metres at 7 percent",
                "Tram tracks ahead, cross them square on",
                "Unpaved ahead, for 800 metres",
                "Steps ahead: carry your bike",
                "Ferry in 200 metres",
            ),
            d.warnings(),
        )
        assertTrue(d.distanceBefore("Climb", 300.0) in 100.0..160.0)
        assertTrue(d.distanceBefore("Tram", 1500.0) in 25.0..60.0)
        assertTrue(d.distanceBefore("Steps", 2900.0) in 40.0..100.0)
        assertEquals(Priority.HIGH, d.first("Steps").priority)
    }

    @Test fun bikeFindsClimbsInTheElevationsAndSurfacesInTheSpans() {
        val sk = Sketch(Mode.BIKE, speed = 4.5).leg(90.0, 3000.0, "Dijk")
        // flat, then 500 m at 5 %, then flat
        sk.elevations { x -> when { x < 1000 -> 0.0; x < 1500 -> (x - 1000) * 0.05; else -> 25.0 } }
        sk.span(0.0, 2000.0, "surface", "asphalt").span(2000.0, 2400.0, "surface", "sett").span(2400.0, 3000.0, "surface", "asphalt")
        sk.span(0.0, 2600.0, "highway", "cycleway").span(2600.0, 3000.0, "highway", "residential")
        val d = said(sk.route(), GuideSettings(mode = Mode.BIKE), 4.5)
        assertEquals(listOf("Climb ahead: 500 metres at 5 percent", "Cobbles ahead", "The cycle path ends, join the road"), d.warnings())
    }

    @Test fun carHearsLimitsTollsZonesTunnelsCrossingsAndReports() {
        val sk = Sketch(Mode.CAR, speed = 20.0).leg(Leg(90.0, 20_000.0, null, ref = "N201"))
        sk.hazard(1000.0, "speed_change", 80.0, mapOf("from" to "50", "to" to "80"))
        sk.hazard(3000.0, "speed_camera", null, mapOf("maxspeed" to "80"))
        sk.hazard(5000.0, "toll")
        sk.hazard(8000.0, "low_emission_zone")
        sk.hazard(11_000.0, "jam", 420.0)
        sk.hazard(14_000.0, "level_crossing")
        sk.span(0.0, 16_000.0, "tunnel", "no").span(16_000.0, 17_000.0, "tunnel", "yes").span(17_000.0, 20_000.0, "tunnel", "no")
        sk.hazard(18_500.0, "stopped_vehicle")
        val r = sk.route()
        val d = said(r, GuideSettings(mode = Mode.CAR), 20.0)
        assertEquals(
            listOf(
                "Speed limit 80",
                "Toll road ahead",
                "Clean-air zone ahead",
                "Traffic jam in 1 kilometre, about 7 minutes delay",
                "Level crossing ahead",
                "Tunnel ahead",
                "Stopped vehicle in 1 kilometre",
                "Stopped vehicle in 200 metres",
            ),
            d.warnings(),
        )
        // cameras only where the app says the law allows warnings
        val legal = said(r, GuideSettings(mode = Mode.CAR, speedCameras = true), 20.0)
        assertTrue(legal.warnings().contains("Speed camera ahead, limit 80"))
        assertTrue(d.distanceBefore("Toll", 5000.0) in 1000.0..1400.0)
    }

    @Test fun aSpeedLimitIsSaidInTheRoadsOwnUnit() {
        // the router gives the value in km/h and the sign as mapped: 30 mph is "30", not "48"
        val sk = Sketch(Mode.CAR, speed = 13.0).leg(90.0, 3000.0, "High Street")
        sk.hazard(1000.0, "speed_change", 30 * 1.609344, mapOf("from" to "40 mph", "to" to "30 mph"))
        sk.hazard(2000.0, "speed_change", 50.0, mapOf("from" to "30 mph", "to" to "urban"))
        val d = said(sk.route(), GuideSettings(mode = Mode.CAR, units = UnitSystem.IMPERIAL_UK), 13.0)
        assertEquals(listOf("Speed limit 30", "Speed limit 50"), d.warnings())
    }

    @Test fun anElectricCarHearsItsBatteryAtTheStart() {
        val ev = GuideSettings(mode = Mode.CAR, vehicle = Vehicle(ev = app.atlas.model.EvSpec(60.0, 18.0)))
        // the planner's two battery hazards: no charger in reach at 100 km, and the charge left on arrival
        val sk = Sketch(Mode.CAR, speed = 30.0, spacing = 200.0).leg(Leg(0.0, 150_000.0, null, ref = "E45"))
        sk.hazard(100_000.0, "battery", 10.0, mapOf("reason" to "no_chargers"), text = "no reachable charger")
        sk.hazard(150_000.0, "battery", 7.0, mapOf("arrive_soc" to "0.07"))
        val d = said(sk.route(), ev, 30.0)
        assertEquals(
            listOf(
                "No charger in reach: the battery runs low in 100 kilometres. Low battery on arrival: about 7 percent",
                "No charger in reach: the battery runs low in 2 kilometres",
            ),
            d.warnings(),
        )
        assertTrue(d.said[1].along < 100)
        assertEquals(Priority.HIGH, d.said[1].priority)
        val nl = said(sk.route(), ev.copy(language = "nl"), 30.0).texts
        assertTrue(nl.toString(), nl.contains("Geen laadpunt binnen bereik: de accu raakt leeg over 2 kilometer"))
    }

    @Test fun barriersBumpsAndTheRoutersOwnVehicleSize() {
        val sk = Sketch(Mode.CAMPER, speed = 14.0).leg(90.0, 6000.0, "Kade")
        sk.hazard(1500.0, "height_restrictor")
        sk.hazard(3000.0, "speed_bump", null, mapOf("traffic_calming" to "bump"))
        // the guide was not told the camper's height; the router compared it with 3.4 m
        sk.hazard(4500.0, "maxheight", 3.2, mapOf("violated" to "yes", "vehicle" to "3.4"))
        val r = sk.route()
        val normal = said(r, GuideSettings(mode = Mode.CAMPER), 14.0)
        assertEquals(
            listOf("Height barrier ahead", "Warning: height limit 3.2 metres, your camper is 3.4 metres", "Warning: height limit 3.2 metres, your camper is 3.4 metres"),
            normal.warnings(),
        )
        // the close-by repeat is its own utterance, so an app dropping stale speech by id keeps it
        val ids = normal.said.filter { it.text.startsWith("Warning") }.map { it.id }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids[1].endsWith("/2"))
        assertTrue(said(r, GuideSettings(mode = Mode.CAMPER, verbosity = Verbosity.CHATTY), 14.0).texts.contains("Speed bump ahead"))
        assertEquals("Verkeersdrempel in aantocht", Phrasebook.of("nl").phrase("speed_bump"))
    }

    @Test fun theRoadAheadIsListedForTheScreen() {
        val sk = Sketch(Mode.TRUCK, speed = 20.0).leg(Leg(90.0, 12_000.0, "B9"))
        sk.hazard(2500.0, "maxweight", 7.5)
        sk.hazard(5000.0, "toll")
        sk.hazard(11_000.0, "maxheight", 3.9)
        val r = sk.route()
        val nav = Navigator(r, GuideSettings(mode = Mode.TRUCK, vehicle = Vehicle(heightM = 4.0, weightT = 40.0)))
        val fixes = Simulator(r, speed = 20.0).fixes().toList()
        for (f in fixes.take(101)) nav.update(f)
        val ahead = nav.upcoming()
        assertEquals(listOf("limit_weight", "toll", "limit_height"), ahead.map { it.first.kind })
        assertEquals(listOf(500.0, 3000.0, 9000.0), ahead.map { Math.round(it.second / 10.0) * 10.0 })
        assertEquals(Priority.CRITICAL, ahead[2].first.priority)
        assertEquals(listOf("limit_weight", "toll"), nav.upcoming(5000.0).map { it.first.kind })
        assertEquals(3, nav.plannedCallouts.size)
    }

    @Test fun carIsToldTheLastFuelBeforeALongStretch() {
        val sk = Sketch(Mode.CAR, speed = 30.0, spacing = 200.0).leg(Leg(0.0, 150_000.0, null, ref = "E45"))
        sk.hazard(20_000.0, "fuel", null, mapOf("name" to "Shell Vika"))
        sk.hazard(25_000.0, "fuel", null, mapOf("brand" to "Circle K"))
        val d = said(sk.route(), GuideSettings(mode = Mode.CAR), 30.0)
        assertEquals(listOf("Last fuel for 125 kilometres: Circle K, in 2 kilometres"), d.warnings())
        val ev = said(
            Sketch(Mode.CAR, speed = 30.0, spacing = 200.0).leg(Leg(0.0, 150_000.0, null, ref = "E45"))
                .hazard(25_000.0, "charging", null, mapOf("name" to "Ionity")).route(),
            GuideSettings(mode = Mode.CAR, vehicle = Vehicle(ev = app.atlas.model.EvSpec(77.0, 18.0))),
            30.0,
        )
        assertEquals(listOf("Last charger for 125 kilometres: Ionity, in 2 kilometres"), ev.warnings())
    }

    @Test fun truckHearsWeightWidthHazmatAndBans() {
        val sk = Sketch(Mode.TRUCK, speed = 20.0).leg(Leg(90.0, 12_000.0, "B9"))
        sk.hazard(2500.0, "maxweight", 7.5)
        sk.hazard(5000.0, "maxwidth", 2.6)
        sk.hazard(8000.0, "tunnel", null, mapOf("hazmat" to "no"))
        sk.hazard(11_000.0, "hgv", null, mapOf("hgv" to "no"))
        val d = said(sk.route(), GuideSettings(mode = Mode.TRUCK, vehicle = Vehicle(heightM = 4.0, widthM = 2.55, weightT = 40.0, lengthM = 16.5, hazmat = true)), 20.0)
        val w = d.warnings()
        assertEquals("Warning: weight limit 7.5 tonnes, your truck weighs 40 tonnes", w[0])
        assertTrue(w.contains("Width limit 2.6 metres ahead, you are clear"))
        assertTrue(w.contains("Warning: hazardous goods are not allowed in the tunnel ahead"))
        assertTrue(w.contains("Warning: trucks are not allowed ahead"))
        assertEquals(2, w.count { it.startsWith("Warning: weight") })
    }

    @Test fun boatHearsShallowsSpeedLimitsAndOpeningBridges() {
        val sk = Sketch(Mode.BOAT, speed = 2.5).leg(0.0, 6000.0, "Vecht")
        sk.hazard(1000.0, "waterway_speed", 6.0)
        sk.hazard(2500.0, "bridge", null, mapOf("bridge:movable" to "bascule", "opening_hours" to "on the hour"))
        sk.hazard(3500.0, "shallow", 1.4)
        sk.hazard(4600.0, "bridge", 3.1)
        sk.hazard(5600.0, "weir")
        val settings = GuideSettings(mode = Mode.BOAT, vehicle = Vehicle(airDraftM = 2.8, draftM = 1.2))
        val d = said(sk.route(), settings, 2.5)
        assertEquals(
            listOf(
                "Speed limit 6 kilometres per hour",
                "Opening bridge in 700 metres, opens on the hour",
                "Warning: shallow water ahead, 1.4 metres, your draft is 1.2 metres",
                "Warning: shallow water ahead, 1.4 metres, your draft is 1.2 metres",
                "Bridge in 700 metres, clearance 3.1 metres, only 0.3 metres to spare",
                "Weir in 300 metres, keep clear",
            ),
            d.warnings(),
        )
        val tooLow = said(Sketch(Mode.BOAT, speed = 2.5).leg(0.0, 3000.0, "Vecht").hazard(1500.0, "bridge", 2.5).route(), settings, 2.5)
        assertEquals(listOf("Warning: bridge in 700 metres, clearance 2.5 metres, your air draft is 2.8 metres", "Warning: bridge in 200 metres, clearance 2.5 metres, your air draft is 2.8 metres"), tooLow.warnings())
        val nautical = said(
            Sketch(Mode.SAIL, speed = 3.0).leg(0.0, 3000.0, "IJ").hazard(2000.0, "bridge", 9.0).route(),
            settings.copy(mode = Mode.SAIL, units = UnitSystem.NAUTICAL), 3.0,
        )
        assertEquals(listOf("Bridge in half a mile, clearance 9 metres, you're clear"), nautical.warnings())
    }

    @Test fun walkersHearCrossingsStepsAndTheEntranceSide() {
        val sk = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 900.0, "Westermarkt")
        sk.hazard(200.0, "crossing", null, mapOf("crossing" to "traffic_signals", "signals" to "yes"))
        sk.hazard(400.0, "crossing", null, mapOf("crossing" to "uncontrolled", "signals" to "no"))
        sk.hazard(500.0, "crossing", null, mapOf("crossing" to "zebra"))
        sk.hazard(700.0, "steps", null, mapOf("step_count" to "12", "incline" to "up"))
        val entrance = Landmark("entrance", null, sk.beside(900.0, 8.0), side = 1)
        val d = said(sk.route(arrive = Turn.RIGHT, arriveLandmarks = listOf(entrance)), GuideSettings(mode = Mode.WALK), 1.4)
        assertEquals(
            listOf("Crossing ahead, with signal", "Crossing ahead, no signal", "Zebra crossing ahead", "Steps ahead: 12 steps up"),
            d.warnings(),
        )
        assertEquals("You have arrived. The entrance is on your right", d.texts.last())
        assertTrue(d.distanceBefore("with signal", 200.0) in 15.0..25.0)
    }

    @Test fun anUnlitPathIsMentionedOnlyAfterDark() {
        val sk = Sketch(Mode.WALK, speed = 1.4).leg(90.0, 800.0, "Vondelpark")
        sk.span(0.0, 300.0, "lit", "yes").span(300.0, 700.0, "lit", "no").span(700.0, 800.0, "lit", "yes")
        val r = sk.route()
        val day = said(r, GuideSettings(mode = Mode.WALK), 1.4, Instant.parse("2026-06-21T10:00:00Z"))
        assertFalse(day.texts.any { "Unlit" in it })
        val night = said(r, GuideSettings(mode = Mode.WALK), 1.4, Instant.parse("2026-12-21T21:00:00Z"))
        assertEquals(listOf("Unlit stretch ahead, for 400 metres"), night.warnings())
    }

    @Test fun minimalVerbosityKeepsOnlyCriticalWarnings() {
        val sk = Sketch(Mode.TRUCK, speed = 20.0).leg(Leg(90.0, 8000.0, "B9"))
        sk.hazard(2000.0, "toll")
        sk.hazard(5000.0, "maxheight", 3.9)
        val d = said(sk.route(), GuideSettings(mode = Mode.TRUCK, verbosity = Verbosity.MINIMAL, vehicle = Vehicle(heightM = 4.0)), 20.0)
        assertEquals(listOf("Warning: height limit 3.9 metres, your truck is 4 metres", "Warning: height limit 3.9 metres, your truck is 4 metres"), d.warnings())
    }

    @Test fun warningsSpeakDutchGermanAndFrench() {
        val sk = Sketch(Mode.BIKE, speed = 4.5).leg(90.0, 2000.0, "Dijk")
        sk.hazard(500.0, "climb", 6.0, mapOf("length" to "400"))
        sk.hazard(1500.0, "steps")
        val r = sk.route()
        assertEquals(listOf("Klim in aantocht: 400 meter van 6 procent", "Trap in aantocht: draag uw fiets"), said(r, GuideSettings(mode = Mode.BIKE, language = "nl"), 4.5).warnings().filterNot { it.startsWith("Ga ") || it.startsWith("Over ") || it.startsWith("U ") || it.startsWith("Uw ") })
        val de = said(r, GuideSettings(mode = Mode.BIKE, language = "de"), 4.5).texts
        assertTrue(de.toString(), de.contains("Steigung voraus: 400 Meter mit 6 Prozent"))
        val fr = said(r, GuideSettings(mode = Mode.BIKE, language = "fr"), 4.5).texts
        assertTrue(fr.toString(), fr.contains("Montée à venir : 400 mètres à 6 pour cent"))
    }
}
