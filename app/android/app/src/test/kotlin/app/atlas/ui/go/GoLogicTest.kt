package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.model.EvSpec
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.RoutePrefs
import app.atlas.model.Vehicle
import app.atlas.route.HazardKind
import app.atlas.services.Units
import app.atlas.testing.FixtureTiles
import app.atlas.tiles.TileStack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** The go package's plain logic: formats, JSON forms, the driver's hours, route facts, bridges, parking, vehicles. */
class GoLogicTest {
    @Test
    fun distancesAsAGlanceNeedsThem() {
        assertEquals("120 m", GoFormat.distance(123.0).toString())
        assertEquals("990 m", GoFormat.distance(994.0).toString())
        assertEquals("1.0 km", GoFormat.distance(996.0).toString())
        assertEquals("4.4 km", GoFormat.distance(4_420.0).toString())
        assertEquals("12 km", GoFormat.distance(12_400.0).toString())
        assertEquals("400 ft", GoFormat.distance(121.92, Units.IMPERIAL).toString())
        assertEquals("0.9 mi", GoFormat.distance(1_450.0, Units.IMPERIAL).toString())
        assertEquals("2.1 nm", GoFormat.distance(3_900.0, Units.NAUTICAL).toString())
        assertEquals("–", GoFormat.distance(Double.NaN).value)
    }

    @Test
    fun speedsTimesAndSizes() {
        assertEquals("22 km/h", GoFormat.speed(6.1).toString())
        assertEquals("6.2", GoFormat.knots(3.19))
        assertEquals("6′", GoFormat.minutes(340.0))
        assertEquals("1 h 05", GoFormat.minutes(3_900.0))
        assertEquals("4:10", GoFormat.hoursMinutes(4 * 3600L + 10 * 60))
        assertEquals("13′6″", GoFormat.size(4.115, Units.IMPERIAL))
        assertEquals("2.5 m", GoFormat.size(2.5))
        val t = Instant.parse("2026-10-14T17:22:00Z")
        assertEquals("19:22", GoFormat.clock(t, ZoneId.of("Europe/Amsterdam"), Locale.forLanguageTag("nl-NL")))
        assertEquals("7:22 PM", GoFormat.clock(t, ZoneId.of("Europe/Amsterdam"), Locale.US))
    }

    @Test
    fun savedPlacesVehiclesAndPrefsSurviveTheirJson() {
        val p = SavedPlace("Amsterdam Centraal", LatLon(52.37903, 4.90010), "n123", 42, LocalTime.of(8, 30))
        assertEquals(p, GoJson.place(GoJson.parse(GoJson.place(p).toString())))
        assertEquals(listOf(p, p.copy(name = "Haarlem")), GoJson.places(GoJson.places(listOf(p, p.copy(name = "Haarlem")))))
        val v = Vehicle(heightM = 4.0, widthM = 2.55, lengthM = 16.5, weightT = 40.0, axleLoadT = 11.5, trailer = true, hazmat = true,
            airDraftM = 1.6, draftM = 0.6, beamM = 2.3, maxSpeedKmh = 80.0, ev = EvSpec(75.0, 17.5, setOf("type2"), 150.0, 11.0, 0.6, 0.15))
        assertEquals(v, GoJson.vehicle(GoJson.parse(GoJson.vehicle(v).toString())))
        val prefs = RoutePrefs(avoidTolls = true, avoidSteps = true, quiet = 0.75, green = 0.5, flat = 1.0, lit = 0.25)
        assertEquals(prefs, GoJson.prefs(GoJson.parse(GoJson.prefs(prefs).toString())))
        // Broken input reads as nothing, not a crash; nonsense numbers are dropped.
        assertNull(GoJson.place(GoJson.parse("{\"name\":\"x\",\"lat\":95,\"lon\":4}")))
        assertTrue(GoJson.places("not json").isEmpty())
        assertEquals(Vehicle(), GoJson.vehicle(GoJson.parse("{\"height\":-3,\"weight\":\"heavy\"}")))
        assertEquals(1.0, GoJson.prefs(GoJson.parse("{\"quiet\":7}"))!!.quiet, 0.0)
    }

    @Test
    fun aWholeRouteSurvivesItsJson() {
        val r = GoFixtures.fixture(Mode.BIKE)!!
        val back = GoJson.route(GoJson.parse(GoJson.route(r).toString()))!!
        assertEquals(r.points.size, back.points.size)
        assertEquals(r.steps.map { it.maneuver to it.street }, back.steps.map { it.maneuver to it.street })
        assertEquals(r.hazards.map { it.kind }, back.hazards.map { it.kind })
        assertEquals(r.distance, back.distance, 1e-6)
    }

    @Test
    fun theDriversHoursFollowTheEuRules() {
        val t0 = Instant.parse("2026-10-14T06:00:00Z")
        var s = Tachograph.advance(TachoState(), t0, moving = true)
        // Four hours of driving in one-minute steps, with a 40 s red light in the middle that counts as driving.
        var t = t0
        repeat(240) {
            t = t.plusSeconds(60)
            s = Tachograph.advance(s, t, moving = true)
        }
        s = Tachograph.advance(s, t.plusSeconds(40), moving = false)
        t = t.plusSeconds(40)
        s = Tachograph.advance(s, t.plusSeconds(60), moving = true)
        t = t.plusSeconds(60)
        assertEquals(4 * 3600L + 100, s.drivingSinceBreak)
        assertEquals(30 * 60L - 100, s.toBreak)
        assertFalse(s.breakDue)
        // Thirty minutes more: the break is due.
        repeat(30) {
            t = t.plusSeconds(60)
            s = Tachograph.advance(s, t, moving = true)
        }
        assertTrue(s.breakDue)
        assertEquals(1f, s.blockShare)
        // A 15-minute stop is the first part of a split break: the next stop then needs only 30.
        repeat(16) {
            t = t.plusSeconds(60)
            s = Tachograph.advance(s, t, moving = false)
        }
        t = t.plusSeconds(60)
        s = Tachograph.advance(s, t, moving = true)
        assertTrue(s.splitTaken)
        assertEquals(Tachograph.SPLIT_SECOND, s.breakNeeded)
        repeat(31) {
            t = t.plusSeconds(60)
            s = Tachograph.advance(s, t, moving = false)
        }
        assertEquals(0L, s.drivingSinceBreak)
        assertFalse(s.splitTaken)
        // The day's driving stays; a gap with the phone off is rest; nine hours of rest start a new day.
        assertTrue(s.drivenToday > 4 * 3600)
        val next = Tachograph.advance(s, t.plusSeconds(10L * 3600), moving = true)
        assertEquals(0L, next.drivenToday)
        assertEquals(s, TachoState.fromJson(s.toJson()))
    }

    @Test
    fun aBikeRowsFactIsItsClimbAndItsCarFreeShare() {
        val r = GoFixtures.fixture(Mode.BIKE)!!
        val f = RouteFacts.of(Mode.BIKE, r, r.points.last(), null) as KeyFact.Ride
        assertEquals(r.climb, f.climbM, 0.0)
        assertTrue(f.carFreeShare in 0.0..1.0)
        assertTrue("some cycleway on the way to Centraal: ${f.carFreeShare}", f.carFreeShare > 0.05)
        assertEquals(KeyFact.Walk(GoFixtures.fixture(Mode.WALK)!!.distance), RouteFacts.of(Mode.WALK, GoFixtures.fixture(Mode.WALK)!!, r.points.last(), null))
        val boat = GoFixtures.fixture(Mode.BOAT)!!
        assertEquals(boat.hazards.count { it.kind == HazardKind.LOCK }, (RouteFacts.of(Mode.BOAT, boat, boat.points.last(), null) as KeyFact.Water).locks)
        // A car without a place store has no parking fact rather than a made-up one.
        assertNull(RouteFacts.of(Mode.CAR, GoFixtures.fixture(Mode.CAR)!!, r.points.last(), null))
    }

    @Test
    fun theNextHazardAndTheClimbAhead() {
        val r = GoFixtures.fixture(Mode.BIKE)!!
        val (h, d) = RouteFacts.nextHazard(r, 0.0, setOf(HazardKind.COBBLES))!!
        assertEquals(HazardKind.COBBLES, h.kind)
        assertTrue(d > 0)
        assertNull(RouteFacts.nextHazard(r, r.distance + 100, setOf(HazardKind.COBBLES)))
        // Amsterdam's bridges are small hills: a grade of a few percent at most.
        RouteFacts.climbAhead(r, 0.0, aheadM = r.distance)?.let { assertTrue(it in 0.5..12.0) }
    }

    @Test
    fun bridgesFromTheTilesCrossTheCanals() {
        val boat = GoFixtures.fixture(Mode.BOAT)!!
        val crossings = runBlocking { TileBridges.crossings(boat, TileStack(listOf(FixtureTiles()))) }
        assertTrue("a canal trip through the Grachtengordel passes many bridges: ${crossings.size}", crossings.size >= 10)
        assertTrue(crossings.toList().zipWithNext().all { (a, b) -> b - a >= RouteBridges.SAME_M })
        val bridges = RouteBridges.find(boat, null, crossings)
        assertEquals(crossings.size, bridges.size)
        assertTrue(bridges.all { it.clearance == null })
        val (next, d) = RouteBridges.next(bridges, 0.0)!!
        assertEquals(crossings[0], next.along, 1e-9)
        assertEquals(crossings[0], d, 1e-9)
        // A road route is not "under" a bridge it drives over: its own bridges run along it, not across.
        assertEquals(-1.0, TileBridges.intersect(0.0, 0.0, 10.0, 0.0, 0.0, 1.0, 10.0, 1.0), 0.0)
        assertEquals(0.5, TileBridges.intersect(0.0, 0.0, 10.0, 0.0, 5.0, -1.0, 5.0, 1.0), 1e-9)
        assertEquals(2.5, RouteBridges.clearanceOf(mapOf("seamark:bridge:clearance_height" to "2.5"))!!, 0.0)
        assertEquals(4.1148, RouteBridges.clearanceOf(mapOf("maxheight:physical" to "13'6\""))!!, 1e-4)
    }

    @Test
    fun twoWheelParkingIsReadFromItsTags() {
        fun p(vararg kv: Pair<String, String>) = Place(null, LatLon(52.3745, 4.8838), mapOf(*kv))
        assertEquals(setOf(ParkKind.STANDS), Twowheel.kinds(mapOf("amenity" to "bicycle_parking", "bicycle_parking" to "stands")))
        assertEquals(setOf(ParkKind.COVERED), Twowheel.kinds(mapOf("amenity" to "bicycle_parking", "bicycle_parking" to "shed")))
        assertEquals(setOf(ParkKind.GARAGE, ParkKind.COVERED), Twowheel.kinds(mapOf("amenity" to "bicycle_parking", "bicycle_parking" to "building", "covered" to "yes")))
        assertEquals(setOf(ParkKind.LOCKERS), Twowheel.kinds(mapOf("amenity" to "bicycle_parking", "bicycle_parking" to "lockers")))
        assertTrue(ParkKind.CARGO in Twowheel.kinds(mapOf("amenity" to "bicycle_parking", "cargo_bike" to "yes")))
        assertEquals(setOf(ParkKind.MOTORBIKE), Twowheel.kinds(mapOf("amenity" to "motorcycle_parking")))
        assertEquals(setOf(ParkKind.CHARGE), Twowheel.kinds(mapOf("amenity" to "charging_station", "bicycle" to "yes")))
        assertTrue(Twowheel.kinds(mapOf("amenity" to "charging_station", "motorcar" to "yes")).isEmpty())
        assertEquals(setOf(ParkKind.REPAIR), Twowheel.kinds(mapOf("amenity" to "bicycle_repair_station")))
        assertTrue(Twowheel.kinds(mapOf("amenity" to "cafe")).isEmpty())

        val places = listOf(
            p("amenity" to "bicycle_parking", "bicycle_parking" to "stands", "capacity" to "24", "covered" to "yes"),
            p("amenity" to "motorcycle_parking", "capacity" to "8"),
            p("amenity" to "bicycle_repair_station"),
        )
        val bike = Twowheel.around(places, LatLon(52.3746, 4.8838), Mode.BIKE)
        assertEquals(1, bike.counts[ParkKind.MOTORBIKE])
        assertEquals(1, bike.spots.size)
        assertEquals(24, bike.spots[0].capacity)
        assertTrue(bike.spots[0].covered)
        val moto = Twowheel.around(places, LatLon(52.3746, 4.8838), Mode.MOTORCYCLE)
        assertEquals(8, moto.spots.single().capacity)
    }

    @Test
    fun vehicleFieldsStepAndStayInRange() {
        val v = Vehicle(heightM = 3.95)
        val up = VehicleField.HEIGHT.stepped(v.heightM, up = true, start = 4.0)
        assertEquals(4.0, up, 1e-9)
        assertEquals(5.0, VehicleField.HEIGHT.stepped(5.0, up = true, start = 4.0), 0.0)
        assertEquals(4.0, VehicleField.HEIGHT.stepped(null, up = true, start = 4.0), 0.0)
        assertEquals(40.0, VehicleField.WEIGHT.set(Vehicle(), 40.0).weightT!!, 0.0)
        val ev = VehicleField.CHARGE_NOW.set(Vehicle(), 55.0)
        assertEquals(0.55, ev.ev!!.socNow, 1e-9)
        assertEquals(55.0, VehicleField.CHARGE_NOW.get(ev)!!, 1e-9)
        assertEquals("16.5 m", VehicleField.LENGTH.format(16.5))
        assertEquals("40 t", VehicleField.WEIGHT.format(40.0))
        assertEquals("13′1″", VehicleField.HEIGHT.format(4.0, Units.IMPERIAL))
        assertEquals(listOf(VehicleField.LENGTH, VehicleField.AIR_DRAFT, VehicleField.DRAFT, VehicleField.BEAM), VehicleField.forMode(Mode.BOAT))
        assertEquals("16.5 m · 4.0 m · 40 t", VehicleFacts.short(Mode.TRUCK, GoStore.defaultVehicle(Mode.TRUCK), Units.METRIC))
        assertTrue(VehicleField.forMode(Mode.WALK).isEmpty())
    }

    @Test
    fun theGantryShowsHomeAndWorkThenWhereYouWent() {
        val here = LatLon(52.37452, 4.88377)
        val home = SavedPlace("Home", LatLon(52.36, 4.87))
        val work = SavedPlace("Centraal", LatLon(52.37903, 4.90010))
        val recent = listOf(work.copy(usedAt = 5), SavedPlace("Haarlem", LatLon(52.38785, 4.63830)))
        val signs = GoTabViewModel.signsFor(here, home, work, recent)
        assertEquals(listOf(GuideSign.Kind.HOME, GuideSign.Kind.WORK), signs.map { it.kind })
        assertEquals(listOf("Centraal", "Haarlem"), GoTabViewModel.signsFor(here, null, null, recent).map { it.place.name })
        // The Haarlem sign points west.
        val haarlem = GoTabViewModel.signsFor(here, null, null, recent.drop(1)).single()
        assertTrue(haarlem.bearing in 260.0..290.0)
        assertTrue(GoTabViewModel.signsFor(here, null, null, emptyList()).isEmpty())
    }

    @Test
    fun leaveOnTime() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val now = Instant.parse("2026-10-14T05:30:00Z") // 7:30 in Amsterdam
        val leave = GoTabViewModel.leaveFor(LocalTime.of(8, 30), 25 * 60.0, now, zone)!!
        assertEquals(LocalTime.of(8, 5), leave.atZone(zone).toLocalTime())
        // Arriving by 7:00 is past: tomorrow's is too far off to say.
        assertNull(GoTabViewModel.leaveFor(LocalTime.of(6, 0), 600.0, now, zone))
    }

    @Test
    fun servicesAroundAreCounted() {
        val at = LatLon(52.37452, 4.88377)
        val places = listOf(
            Place(null, LatLon(52.3750, 4.8838), mapOf("amenity" to "charging_station")),
            Place(null, LatLon(52.3760, 4.8838), mapOf("amenity" to "charging_station")),
            Place(null, LatLon(52.3746, 4.8840), mapOf("amenity" to "parking", "access" to "private")),
            Place(null, LatLon(52.3747, 4.8840), mapOf("shop" to "bicycle")),
        )
        val c = GoTabViewModel.count(places, at).associateBy { it.kind }
        assertEquals(2, c.getValue(Moving.CHARGE).count)
        assertEquals(53.0, c.getValue(Moving.CHARGE).nearestM!!, 5.0)
        assertEquals(0, c.getValue(Moving.PARK).count)
        assertEquals(1, c.getValue(Moving.BIKE_FIX).count)
        assertNull(c.getValue(Moving.FUEL).nearestM)
    }

    @Test
    fun modesAndTheirScreens() {
        assertEquals(Family.RIDE, familyOf(Mode.EBIKE))
        assertEquals(Family.DRIVE, familyOf(Mode.MOTORCYCLE))
        assertEquals(Family.TRUCK, familyOf(Mode.TRUCK))
        assertEquals(Family.SAIL, familyOf(Mode.SAIL))
        assertEquals(Family.WALK, familyOf(Mode.WHEELCHAIR))
        assertEquals(GoMode.BIKE, GoMode.of(Mode.ROAD_BIKE))
        assertEquals(GoMode.MOTO, GoMode.of(Mode.MOPED))
        assertEquals(GoMode.TRUCK, GoMode.named("TRUCK"))
        assertNull(GoMode.named("SUBMARINE"))
    }

    @Test
    fun compassSmoothingTakesTheShortWay() {
        assertEquals(5.0, CompassHeadings.smoothAngle(350.0, 20.0, 0.5), 1e-9)
        assertEquals(355.0, CompassHeadings.smoothAngle(10.0, 340.0, 0.5), 1e-9)
    }

    @Test
    fun segmentSizesReadAsTheBoardSaysThem() {
        assertEquals("E0_N50 · 79 MB", SegmentNeed("E0_N50", 79_383_775).label)
        assertEquals("E5_N50 · 4.2 MB", SegmentNeed("E5_N50", 4_200_000).label)
        assertEquals("W5_N50", SegmentNeed("W5_N50", null).label)
    }

    @Test
    fun distinctWaysDropTheFastestAgain() {
        val r = GoFixtures.fixture(Mode.CAR)!!
        val walk = GoFixtures.fixture(Mode.WALK)!!
        val ways = listOf(WayOption(WayKind.FASTEST, r), WayOption(WayKind.NO_MOTORWAYS, r), WayOption(WayKind.NO_TOLLS, walk))
        assertEquals(listOf(WayKind.FASTEST, WayKind.NO_TOLLS), RouteViewModel.distinctWays(ways).map { it.kind })
        assertNotNull(RouteViewModel.prefsFor(WayKind.QUIET, RoutePrefs()).quiet)
        assertEquals(1.0, RouteViewModel.prefsFor(WayKind.FLAT, RoutePrefs(quiet = 1.0)).flat, 0.0)
        assertTrue(RouteViewModel.prefsFor(WayKind.NO_TOLLS, RoutePrefs()).avoidTolls)
        assertEquals(listOf(WayKind.FASTEST, WayKind.QUIET, WayKind.FLAT), RouteViewModel.waysFor(Mode.BIKE))
        assertTrue(RouteViewModel.waysFor(Mode.BOAT).isEmpty())
    }
}
