package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Span
import app.atlas.model.Turn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Conversion of synthetic engine tracks into routes. */
class TrackConverterTest {

    /** Builds a track from flat (x, y) metres around Amsterdam, with tags per link and per node. */
    private class Builder {
        val frame = LocalFrame(LatLon(52.37, 4.88))
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val ele = ArrayList<Double>()
        val ways = HashMap<Int, String>()
        val nodes = HashMap<Int, String>()
        val hints = ArrayList<RawHint>()
        var vias = IntArray(0)

        fun go(dx: Double, dy: Double, steps: Int, rise: Double = 0.0, tags: String) {
            if (xs.isEmpty()) {
                xs += 0.0; ys += 0.0; ele += 0.0
            }
            for (k in 1..steps) {
                xs += xs.last() + dx; ys += ys.last() + dy; ele += ele.last() + rise
                ways[xs.size - 1] = tags
            }
        }

        fun build(speed: Double = 5.0, source: String = "test"): RawTrack {
            val n = xs.size
            val lat = DoubleArray(n) { frame.lat(ys[it]) }
            val lon = DoubleArray(n) { frame.lon(xs[it]) }
            val t = RawTrack(lat, lon, ele.toDoubleArray(), DoubleArray(n), DoubleArray(n), Array(n) { ways[it] }, Array(n) { nodes[it] }, hints, vias, Double.NaN, source)
            val c = t.cumulative()
            return RawTrack(lat, lon, t.ele, DoubleArray(n) { c[it] / speed }, DoubleArray(n), t.wayTags, t.nodeTags, hints, vias, Double.NaN, source)
        }
    }

    /**
     * East 200 m on a 30 street, right turn onto a cobbled 50 road (with give way), a roundabout taking the second exit,
     * a via point where a ferry leaves, an unpaved track, steps, then a 300 m climb at 8 %.
     */
    private fun cityTrack(): RawTrack = Builder().apply {
        go(20.0, 0.0, 10, tags = "highway=residential surface=asphalt maxspeed=30")
        hints += RawHint(10, HintCommand.RIGHT, angle = 90.0, giveWay = true)
        go(0.0, -20.0, 10, tags = "highway=secondary surface=sett maxspeed=50")
        hints += RawHint(20, HintCommand.ROUNDABOUT, exit = 2, angle = -10.0)
        go(10.0, -10.0, 2, tags = "highway=secondary junction=roundabout maxspeed=50")
        go(10.0, 10.0, 2, tags = "highway=secondary junction=roundabout maxspeed=50")
        go(0.0, -20.0, 6, tags = "highway=tertiary maxspeed=50")
        go(0.0, -100.0, 5, tags = "route=ferry")
        go(0.0, -20.0, 5, tags = "highway=track tracktype=grade3")
        go(0.0, -5.0, 2, tags = "highway=steps")
        go(0.0, -20.0, 15, rise = 1.6, tags = "highway=residential maxspeed=30")
        nodes[45] = "railway=level_crossing"
        vias = intArrayOf(30, 57)
    }.build()

    @Test
    fun stepsFromHintsTagsAndViaPoints() {
        val names = ArrayList<Double>()
        val route = TrackConverter { _, bearing -> names += bearing; "Street" }.convert(cityTrack(), Mode.BIKE)
        val types = route.steps.map { it.maneuver.type }
        assertEquals(
            listOf(
                ManeuverType.DEPART, ManeuverType.TURN, ManeuverType.ROUNDABOUT, ManeuverType.ROUNDABOUT_EXIT,
                ManeuverType.FERRY_BOARD, ManeuverType.FERRY_LEAVE, ManeuverType.ARRIVE,
            ),
            types,
        )
        val turn = route.steps[1]
        assertEquals(10, turn.pointIndex)
        assertEquals(Turn.RIGHT, turn.maneuver.turn)
        assertEquals(90.0, turn.maneuver.bearingBefore!!, 1.0)
        assertEquals(180.0, turn.maneuver.bearingAfter!!, 1.0)
        val roundabout = route.steps[2]
        assertEquals(20, roundabout.pointIndex)
        assertEquals(2, roundabout.maneuver.exit)
        assertEquals(Turn.STRAIGHT, roundabout.maneuver.turn)
        assertEquals(24, route.steps[3].pointIndex)
        assertEquals(30, route.steps[4].pointIndex)
        assertEquals(35, route.steps[5].pointIndex)
        // distances and durations add up, and follow the engine's clock
        assertEquals(route.distance, route.steps.sumOf { it.distance }, 1e-6)
        assertEquals(route.duration, route.steps.sumOf { it.duration }, 1e-6)
        assertEquals(200.0, route.steps[0].distance, 0.5)
        assertEquals(40.0, route.steps[0].duration, 0.1)
        assertEquals(0.0, route.steps.last().distance, 0.0)
        // the namer is asked once per step with the travel bearing
        assertEquals(route.steps.size, names.size)
        assertEquals(90.0, names[0], 1.0)
        assertEquals(180.0, names[1], 1.0)
        assertTrue(route.steps.all { it.street == "Street" })
    }

    @Test
    fun spansFollowTheWayTags() {
        val route = TrackConverter().convert(cityTrack(), Mode.BIKE)
        val highway = route.spans.filter { it.key == "highway" }
        assertEquals(Span(0, 10, "highway", "residential"), highway[0])
        assertEquals(Span(10, 24, "highway", "secondary"), highway[1])
        assertEquals("tertiary", highway[2].value)
        assertTrue(route.spans.contains(Span(30, 35, "route", "ferry")))
        assertTrue(route.spans.contains(Span(10, 20, "surface", "sett")))
        assertTrue(route.spans.contains(Span(0, 10, "maxspeed", "30")))
        assertTrue(route.spans.contains(Span(35, 40, "tracktype", "grade3")))
        // spans of one key never overlap and stay inside the line
        for ((_, list) in route.spans.groupBy { it.key }) {
            for (i in 1 until list.size) assertTrue(list[i].from >= list[i - 1].to)
            assertTrue(list.all { it.from < it.to && it.to <= route.points.size - 1 })
        }
    }

    @Test
    fun hazardsForACyclist() {
        val route = TrackConverter().convert(cityTrack(), Mode.BIKE)
        val kinds = route.hazards.groupBy { it.kind }
        assertEquals(10, kinds.getValue(HazardKind.COBBLES).single().pointIndex)
        assertEquals(200.0, kinds.getValue(HazardKind.COBBLES).single().value!!, 0.5)
        assertEquals(35, kinds.getValue(HazardKind.UNPAVED).single().pointIndex)
        assertEquals(40, kinds.getValue(HazardKind.STEPS).single().pointIndex)
        assertEquals(500.0, kinds.getValue(HazardKind.FERRY).single().value!!, 1.0)
        assertEquals(45, kinds.getValue(HazardKind.LEVEL_CROSSING).single().pointIndex)
        assertEquals(10, kinds.getValue(HazardKind.GIVE_WAY).single().pointIndex)
        val climb = kinds.getValue(HazardKind.CLIMB).single()
        assertEquals(8.0, climb.value!!, 1.0)
        assertTrue(climb.tags.getValue("length").toDouble() in 200.0..320.0)
        assertTrue("$climb", climb.pointIndex in 39..46)
        assertNull(kinds[HazardKind.SPEED_CHANGE])
        assertNull(kinds[HazardKind.DESCENT])
        assertEquals(24.0, route.climb, 0.5)
        assertEquals(0.0, route.descent, 0.5)
        assertEquals(route.points.size, route.elevations.size)
    }

    @Test
    fun hazardsForADriver() {
        val route = TrackConverter().convert(cityTrack(), Mode.CAR)
        val kinds = route.hazards.groupBy { it.kind }
        val changes = kinds.getValue(HazardKind.SPEED_CHANGE)
        assertEquals(listOf(10 to 50.0, 42 to 30.0), changes.map { it.pointIndex to it.value })
        assertEquals("30", changes[0].tags["from"])
        assertNull(kinds[HazardKind.COBBLES])
        assertNull(kinds[HazardKind.CLIMB]) // 8 % over 300 m is no matter for a car
        assertNotNull(kinds[HazardKind.UNPAVED])
    }

    @Test
    fun boatsWaitAtLocksAndCrossOpenWater() {
        val raw = Builder().apply {
            go(0.0, 50.0, 4, tags = "waterway=canal")
            nodes[4] = "waterway=lock_gate"
            go(0.0, 25.0, 4, tags = "waterway=lock")
            nodes[8] = "waterway=lock_gate"
            go(0.0, 50.0, 4, tags = "waterway=canal")
            go(400.0, 0.0, 3, tags = "direct_segment=2")
        }.build(speed = 2.0)
        val route = TrackConverter().convert(raw, Mode.BOAT)
        val lockSteps = route.steps.filter { it.maneuver.type == ManeuverType.LOCK }
        assertEquals(1, lockSteps.size)
        assertEquals(4, lockSteps[0].pointIndex)
        // one lock, one wait, and it is spent on the lock step
        assertEquals(raw.duration + TrackConverter.LOCK_SECONDS, route.duration, 1e-6)
        assertTrue(lockSteps[0].duration >= TrackConverter.LOCK_SECONDS)
        assertEquals(1, route.hazards.count { it.kind == HazardKind.LOCK })
        val open = route.hazards.single { it.kind == HazardKind.OPEN_WATER }
        assertEquals(12, open.pointIndex)
        assertEquals(1200.0, open.value!!, 1.0)
    }

    @Test
    fun rampsMergesAndUTurnsFromRoadClasses() {
        val raw = Builder().apply {
            go(50.0, 0.0, 4, tags = "highway=primary")
            hints += RawHint(4, HintCommand.SLIGHT_RIGHT, priority = 29, previousPriority = 26, link = true)
            go(40.0, -10.0, 3, tags = "highway=motorway_link")
            hints += RawHint(7, HintCommand.CONTINUE, priority = 30, previousPriority = 29, previousLink = true)
            go(50.0, 0.0, 6, tags = "highway=motorway")
            hints += RawHint(13, HintCommand.EXIT_RIGHT, priority = 29, previousPriority = 30, link = true)
            go(40.0, -10.0, 3, tags = "highway=motorway_link")
            hints += RawHint(16, HintCommand.U_TURN_LEFT, angle = -175.0)
            go(-40.0, 0.0, 3, tags = "highway=primary")
            hints += RawHint(19, HintCommand.END)
        }.build(speed = 25.0)
        val route = TrackConverter().convert(raw, Mode.CAR)
        assertEquals(
            listOf(ManeuverType.DEPART, ManeuverType.RAMP_ON, ManeuverType.MERGE, ManeuverType.RAMP_OFF, ManeuverType.U_TURN, ManeuverType.ARRIVE),
            route.steps.map { it.maneuver.type },
        )
        assertEquals(Turn.SLIGHT_RIGHT, route.steps[3].maneuver.turn)
        assertEquals(Turn.U_TURN, route.steps[4].maneuver.turn)
    }

    @Test
    fun roundaboutAnglesFoldBackToADirection() {
        val raw = Builder().apply {
            go(20.0, 0.0, 3, tags = "highway=primary")
            hints += RawHint(3, HintCommand.ROUNDABOUT, exit = 3, angle = -270.0)
            go(0.0, -20.0, 3, tags = "highway=primary")
        }.build()
        val step = TrackConverter().convert(raw, Mode.CAR).steps[1]
        assertEquals(ManeuverType.ROUNDABOUT, step.maneuver.type)
        assertEquals(3, step.maneuver.exit)
        assertEquals(Turn.RIGHT, step.maneuver.turn)
    }

    @Test
    fun appendJoinsLegsAndStraightLinesSpanLongDistances() {
        val a = LatLon(52.0, 4.0)
        val b = LatLon(52.0, 4.1)
        val c = LatLon(52.1, 4.1)
        val one = RawTrack.straight(a, b, 10.0, "atlas=open_water", "x", stepMetres = 2000.0)
        assertEquals(5, one.size) // 6.8 km in 2 km pieces
        assertEquals(one.length / (10 / 3.6), one.duration, 0.01)
        val two = RawTrack.straight(b, c, 10.0, "atlas=open_water", "x")
        val joined = one.append(two)
        assertEquals(one.size + two.size - 1, joined.size)
        assertEquals(one.duration + two.duration, joined.duration, 1e-6)
        assertArrayEquals(intArrayOf(4, joined.size - 1), joined.waypointIndices)
        assertEquals(2, joined.hints.count { it.command == HintCommand.BEELINE })
        assertEquals(4, joined.hints[1].index)
        val route = TrackConverter().convert(joined, Mode.SAIL)
        assertTrue(route.steps.any { it.maneuver.type == ManeuverType.WAYPOINT && it.pointIndex == 4 })
        assertEquals(1, route.hazards.count { it.kind == HazardKind.OPEN_WATER })
        assertTrue(route.elevations.isEmpty())
    }

    @Test
    fun longStraightLegsFollowTheGreatCircle() {
        // 685 km due "east" along 52° N: the shortest way bulges north, and its length is the great-circle distance
        val a = LatLon(52.0, 4.0)
        val b = LatLon(52.0, 14.0)
        val leg = RawTrack.straight(a, b, 12.0, "atlas=open_water", "x", stepMetres = 20_000.0)
        assertEquals(a, leg.point(0))
        assertEquals(b, leg.point(leg.size - 1))
        // atan(tan 52° / cos 5°) = 52.10°, where a straight line in degrees would stay on 52°
        assertEquals(52.10, leg.lat[leg.size / 2], 0.01)
        assertEquals(app.atlas.geo.Geo.distance(a, b), leg.length, 1.0)
        assertEquals(leg.length / (12 / 3.6), leg.duration, 1.0)
    }

    @Test
    fun brouterHintsAndViaNames() {
        assertEquals(HintCommand.ROUNDABOUT_LEFT, BRouterEngine.command(btools.router.AtlasTrackAccess.RNLB))
        assertEquals(HintCommand.EXIT_LEFT, BRouterEngine.command(btools.router.AtlasTrackAccess.EL))
        assertNull(BRouterEngine.command(999))
        // BRouter names the real point "_add" when it joined it to the network with a straight line
        val idx = BRouterEngine.viaIndices(
            intArrayOf(0, 3, 12, 15, 20, 30, 40),
            arrayOf("from", "from_add", "via1", "via1_add", "via1_2", "to", "to_add"),
            41,
        )
        assertArrayEquals(intArrayOf(15, 40), idx)
    }

    @Test
    fun helpers() {
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 1.0, 3.0), TrackConverter.monotonic(doubleArrayOf(Double.NaN, 1.0, 0.5, 3.0)), 0.0)
        // a missing first time must not spread: every time is a number
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 2.0), TrackConverter.monotonic(doubleArrayOf(Double.NaN, Double.NaN, 2.0)), 0.0)
        val filled = TrackConverter.filledElevations(doubleArrayOf(Double.NaN, 10.0, Double.NaN, 30.0, Double.NaN), doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0))!!
        assertArrayEquals(doubleArrayOf(10.0, 10.0, 20.0, 30.0, 30.0), filled, 1e-9)
        assertNull(TrackConverter.filledElevations(doubleArrayOf(Double.NaN, Double.NaN), doubleArrayOf(0.0, 1.0)))
        // noise below 5 m does not add up
        assertEquals(0.0, TrackConverter.filteredAscent(doubleArrayOf(0.0, 3.0, 0.0, 3.0, 0.0, 3.0)), 0.0)
        assertEquals(20.0, TrackConverter.filteredAscent(doubleArrayOf(0.0, 10.0, 20.0, 15.0)), 0.0)
        assertEquals(mapOf("highway" to "residential", "maxspeed" to "30"), TagReader.parse("highway=residential  maxspeed=30 junk"))
        assertEquals(50.0, OsmValues.speedKmh("urban")!!, 0.0)
        assertEquals(48.28, OsmValues.speedKmh("30mph")!!, 0.01)
        assertEquals(-8.0, OsmValues.inclinePercent("8%", reversed = true)!!, 0.0)
        assertTrue(abs(OsmValues.inclinePercent("10°", false)!! - 17.63) < 0.01)
        assertFalse(OsmValues.isUnpaved(mapOf("highway" to "track", "tracktype" to "grade1")))
        assertTrue(OsmValues.isUnpaved(mapOf("highway" to "track")))
    }
}
