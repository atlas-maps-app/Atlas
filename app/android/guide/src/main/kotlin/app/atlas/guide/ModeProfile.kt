package app.atlas.guide

import app.atlas.model.Mode
import app.atlas.model.Vehicle

/** The five ways the guide times its voice: on foot, by bike, by car, by truck and on the water. */
enum class ModeGroup { WALK, BIKE, CAR, TRUCK, BOAT }

val Mode.group: ModeGroup
    get() = when {
        onFoot || this == Mode.TRANSIT -> ModeGroup.WALK
        cycling -> ModeGroup.BIKE
        this == Mode.TRUCK -> ModeGroup.TRUCK
        water -> ModeGroup.BOAT
        else -> ModeGroup.CAR
    }

/** A lead distance that is time-based at the current speed, clamped to a floor and a ceiling in metres. */
data class Lead(val seconds: Double, val minM: Double, val maxM: Double) {
    fun at(speed: Double): Double = (seconds * speed).coerceIn(minM, maxM)
}

/**
 * When the guide speaks for one mode: the three stages before a maneuver (prepare far ahead, approach, now),
 * how close maneuvers are chained, how far off the route counts as off, and how sparse the rest is.
 * The numbers follow OsmAnd, Organic Maps and Valhalla's timing (see the voice research), tuned per mode.
 */
data class ModeProfile(
    val group: ModeGroup,
    /** The slowest speed the timing assumes, m/s: below it leads stop shrinking. */
    val speedFloor: Double,
    /** The speed assumed before the first moving fix, m/s. */
    val defaultSpeed: Double,
    val prepare: Lead?,
    val approach: Lead,
    val now: Lead,
    /** Chain the next maneuver ("then …") when it follows within this many seconds or [chainMinM] metres. */
    val chainSeconds: Double,
    val chainMinM: Double,
    /** Below this gap the chain says "then immediately" and the second maneuver gets no separate warning. */
    val immediateM: Double,
    /** A stretch this long after a maneuver earns "continue for …". */
    val postMinM: Double,
    val arriveRadiusM: Double,
    /** Metres off the route before a fix counts as off it (accuracy widens it). */
    val offRouteM: Double,
    /** Seconds continuously off before the guide declares it. */
    val offRouteSeconds: Double,
    /** Below this speed, m/s, heading is not trusted. */
    val minMovingSpeed: Double,
    /** Seconds between chatty reassurances. */
    val reassureSeconds: Double,
    /** At least this many seconds between an approach and a now cue, else they merge into one. */
    val speechGapS: Double,
    /** The now stage says "Now, …" (bikes: the cue comes late and short). */
    val nowWord: Boolean,
    /** Lead distances for callouts (warnings) scale with this. */
    val calloutScale: Double = 1.0,
) {
    companion object {
        /** The timing for [mode]; trucks and long vehicles get earlier cues, scaled by [vehicle]'s length and weight. */
        fun of(mode: Mode, vehicle: Vehicle = Vehicle()): ModeProfile = when (mode.group) {
            ModeGroup.WALK -> ModeProfile(
                ModeGroup.WALK, speedFloor = 1.2, defaultSpeed = 1.4,
                prepare = null, approach = Lead(70.0, 60.0, 150.0), now = Lead(12.0, 20.0, 35.0),
                chainSeconds = 20.0, chainMinM = 30.0, immediateM = 15.0, postMinM = 300.0, arriveRadiusM = 12.0,
                offRouteM = 30.0, offRouteSeconds = 6.0, minMovingSpeed = 0.5, reassureSeconds = 120.0, speechGapS = 6.0, nowWord = false,
            )
            ModeGroup.BIKE -> ModeProfile(
                ModeGroup.BIKE, speedFloor = 4.0, defaultSpeed = 4.5,
                prepare = Lead(60.0, 250.0, 500.0), approach = Lead(15.0, 60.0, 150.0), now = Lead(4.0, 12.0, 25.0),
                chainSeconds = 15.0, chainMinM = 80.0, immediateM = 25.0, postMinM = 1000.0, arriveRadiusM = 20.0,
                offRouteM = 30.0, offRouteSeconds = 4.0, minMovingSpeed = 1.5, reassureSeconds = 180.0, speechGapS = 4.0, nowWord = true,
            )
            ModeGroup.CAR -> ModeProfile(
                ModeGroup.CAR, speedFloor = 8.0, defaultSpeed = 13.9,
                prepare = Lead(110.0, 1000.0, 3000.0), approach = Lead(30.0, 300.0, 1000.0), now = Lead(6.0, 100.0, 250.0),
                chainSeconds = 13.0, chainMinM = 60.0, immediateM = 50.0, postMinM = 3000.0, arriveRadiusM = 40.0,
                offRouteM = 35.0, offRouteSeconds = 3.0, minMovingSpeed = 3.0, reassureSeconds = 300.0, speechGapS = 5.0, nowWord = false,
            )
            ModeGroup.TRUCK -> {
                val length = vehicle.lengthM ?: 16.5
                val weight = vehicle.weightT ?: 40.0
                // Longer and heavier vehicles need more time to change lanes and brake.
                val k = (1.0 + (length - 12.0).coerceAtLeast(0.0) / 30.0 + (weight - 20.0).coerceAtLeast(0.0) / 200.0).coerceAtMost(1.5)
                ModeProfile(
                    ModeGroup.TRUCK, speedFloor = 8.0, defaultSpeed = 12.5,
                    prepare = Lead(150.0 * k, 1500.0 * k, 4000.0 * k), approach = Lead(35.0 * k, 400.0 * k, 1200.0 * k),
                    now = Lead(9.0 * k, 130.0 * k, 300.0 * k),
                    chainSeconds = 15.0, chainMinM = 80.0, immediateM = 60.0, postMinM = 3000.0, arriveRadiusM = 60.0,
                    offRouteM = 40.0, offRouteSeconds = 4.0, minMovingSpeed = 3.0, reassureSeconds = 300.0, speechGapS = 6.0, nowWord = false,
                    calloutScale = k,
                )
            }
            ModeGroup.BOAT -> ModeProfile(
                ModeGroup.BOAT, speedFloor = 1.5, defaultSpeed = 3.0,
                prepare = null, approach = Lead(120.0, 150.0, 3704.0), now = Lead(30.0, 30.0, 300.0),
                chainSeconds = 30.0, chainMinM = 60.0, immediateM = 40.0, postMinM = 3704.0, arriveRadiusM = 30.0,
                offRouteM = 60.0, offRouteSeconds = 10.0, minMovingSpeed = 0.8, reassureSeconds = 600.0, speechGapS = 10.0, nowWord = false,
            )
        }
    }
}
