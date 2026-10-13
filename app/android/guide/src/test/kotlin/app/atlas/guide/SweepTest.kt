package app.atlas.guide

import app.atlas.geo.UnitSystem
import app.atlas.model.Landmark
import app.atlas.model.Lane
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.Turn
import app.atlas.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.SplittableRandom

/**
 * Every language, every mode and every hazard kind the router reports, on routes drawn at random: nothing throws, no
 * template token is left unfilled, no stage is said twice, and the drive ends with exactly one arrival.
 */
class SweepTest {

    private val kinds = listOf(
        "climb" to 7.0, "descent" to 9.0, "unpaved" to 400.0, "cobbles" to 200.0, "steps" to 20.0, "ferry" to null,
        "toll" to null, "tunnel" to 600.0, "tram_tracks" to null, "unlit" to 300.0, "ford" to null, "height_restrictor" to null,
        "speed_change" to 50.0, "speed_camera" to null, "speed_bump" to null, "level_crossing" to null, "crossing" to null,
        "give_way" to null, "lock" to 120.0, "weir" to null, "bridge" to 3.2, "open_water" to 900.0, "maxheight" to 3.6,
        "maxweight" to 7.5, "maxwidth" to 2.2, "maxlength" to 10.0, "maxaxleload" to 8.0, "hazmat" to null, "hgv" to null,
        "maxdraught" to 1.1, "battery" to 6.0, "restrictions_unchecked" to null, "off_network" to 120.0, "jam" to 300.0,
        "road_works" to null, "accident" to null, "buoy" to null, "waterway_speed" to 9.0, "shallow" to 1.0,
    )

    private fun randomRoute(rnd: SplittableRandom, mode: Mode): Route {
        val speed = when (mode.group) { ModeGroup.WALK -> 1.4; ModeGroup.BIKE -> 5.0; ModeGroup.BOAT -> 3.0; else -> 14.0 }
        val sk = Sketch(mode, speed = speed, spacing = 20.0)
        var bearing = rnd.nextDouble(360.0)
        val n = 2 + rnd.nextInt(5)
        val types = listOf(null, ManeuverType.KEEP, ManeuverType.ROUNDABOUT, ManeuverType.RAMP_OFF, ManeuverType.END_OF_ROAD, ManeuverType.MERGE)
        var total = 0.0
        for (i in 0 until n) {
            val length = if (mode.group == ModeGroup.WALK) 60.0 + rnd.nextDouble(400.0) else 100.0 + rnd.nextDouble(2500.0)
            val type = if (i == 0) null else types[rnd.nextInt(types.size)]
            val lanes = if (rnd.nextBoolean()) listOf(Lane(setOf(Turn.STRAIGHT), false), Lane(setOf(Turn.RIGHT), true)) else emptyList()
            val lm = if (rnd.nextInt(3) == 0) listOf(Landmark("traffic_signals", null, sk.at(total))) else emptyList()
            sk.leg(Leg(bearing, length, if (rnd.nextInt(4) == 0) null else "Street $i", type = type, exit = if (type == ManeuverType.ROUNDABOUT) 2 else null,
                ref = if (rnd.nextInt(3) == 0) "N${i}0" else null, destinations = if (rnd.nextInt(3) == 0) "Haarlem" else null, lanes = lanes, landmarks = lm))
            total += length
            bearing = (bearing + listOf(-90.0, -45.0, 0.0, 20.0, 90.0, 135.0)[rnd.nextInt(6)] + 360) % 360
        }
        for (k in 0 until 6) {
            val (kind, value) = kinds[rnd.nextInt(kinds.size)]
            val tags = mapOf("length" to "300", "vhf_channel" to "18", "avoided" to if (rnd.nextBoolean()) "yes" else "no", "to" to "30 mph", "keep" to "port")
            sk.hazard(rnd.nextDouble(total), kind, value, tags, if (kind == "battery") "no reachable charger" else null)
        }
        sk.span(0.0, total / 2, "maxspeed", "50").span(total / 2, total, "maxspeed", "80")
        sk.span(0.0, total / 3, "surface", "sett").span(0.0, total / 2, "lit", "no")
        return sk.route(arrive = listOf(Turn.LEFT, Turn.RIGHT, Turn.STRAIGHT)[rnd.nextInt(3)])
    }

    @Test fun everyLanguageModeAndHazardSpeaksCleanly() {
        val rnd = SplittableRandom(42)
        val languages = OsrmInstructions.CODES + listOf("cs", "en-US")
        val modes = listOf(Mode.WALK, Mode.WHEELCHAIR, Mode.BIKE, Mode.CAR, Mode.TRUCK, Mode.CAMPER, Mode.BOAT, Mode.MOTORCYCLE)
        var drives = 0
        var spoken = 0
        for (lang in languages) {
            for (mode in modes) {
                val route = randomRoute(rnd, mode)
                val units = UnitSystem.entries[rnd.nextInt(UnitSystem.entries.size)]
                val verbosity = Verbosity.entries[rnd.nextInt(Verbosity.entries.size)]
                val settings = GuideSettings(
                    mode = mode, units = units, language = lang, verbosity = verbosity, speedCameras = true,
                    vehicle = Vehicle(heightM = 4.0, weightT = 30.0, widthM = 2.5, lengthM = 12.0, axleLoadT = 10.0, hazmat = true, airDraftM = 3.5, draftM = 1.5,
                        ev = if (rnd.nextBoolean()) app.atlas.model.EvSpec(60.0, 18.0) else null),
                )
                val speed = when (mode.group) { ModeGroup.WALK -> 1.4; ModeGroup.BIKE -> 5.0; ModeGroup.BOAT -> 3.0; else -> 14.0 }
                val start = Instant.parse("2026-12-21T20:00:00Z")
                val d = drive(Navigator(route, settings), Simulator(route, speed = speed, noise = 6.0, seed = rnd.nextLong(), start = start).fixes())
                val where = "$lang $mode $units $verbosity\n$d"
                assertEquals(where, 1, d.arrivals)
                // the chatty reassurance is periodic by design and keeps one id, so a newer one replaces a stale one
                val staged = d.said.filter { it.id != "reassure" }
                assertEquals(where, staged.size, staged.map { it.id }.toSet().size)
                for (s in d.said) {
                    assertFalse("blank: $where", s.text.isBlank())
                    assertFalse("unfilled token in «${s.text}»: $where", '{' in s.text || '}' in s.text)
                    assertFalse("double space in «${s.text}»", "  " in s.text)
                }
                drives++
                spoken += d.said.size
            }
        }
        assertTrue(drives == (OsrmInstructions.CODES.size + 2) * 8)
        assertTrue("spoken $spoken", spoken > drives * 3)
    }
}
