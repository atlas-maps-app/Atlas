package app.atlas.model

import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import java.time.Instant

/** How you travel. The router picks a profile per mode; the guide picks its voice timing per mode. */
enum class Mode(val moving: Boolean = true) {
    WALK, HIKE, WHEELCHAIR,
    BIKE, EBIKE, ROAD_BIKE, MTB, CARGO_BIKE,
    MOPED, MOTORCYCLE, CAR, TRUCK, CAMPER,
    BOAT, SAIL,
    TRANSIT;

    val onFoot get() = this == WALK || this == HIKE || this == WHEELCHAIR
    val cycling get() = this in setOf(BIKE, EBIKE, ROAD_BIKE, MTB, CARGO_BIKE)
    val motor get() = this in setOf(MOPED, MOTORCYCLE, CAR, TRUCK, CAMPER)
    val water get() = this == BOAT || this == SAIL
}

/** Battery and consumption of an electric vehicle, for charging stops. */
data class EvSpec(
    val batteryKwh: Double,
    /** kWh per 100 km at 100 km/h on the flat. */
    val consumption: Double,
    val connectors: Set<String> = setOf("type2_combo", "type2"),
    val maxDcKw: Double = 100.0,
    val maxAcKw: Double = 11.0,
    /** Charge level now, 0..1. */
    val socNow: Double = 0.8,
    /** Lowest acceptable charge on arrival anywhere, 0..1. */
    val socMin: Double = 0.1,
    /** Charging curve as (soc 0..1 → kW) points; empty means flat at maxDcKw up to 80 %. */
    val curve: List<Pair<Double, Double>> = emptyList(),
)

/** Dimensions that decide where a vehicle or a boat may go. Null means unknown or no limit. */
data class Vehicle(
    val heightM: Double? = null,
    val widthM: Double? = null,
    val lengthM: Double? = null,
    val weightT: Double? = null,
    val axleLoadT: Double? = null,
    val trailer: Boolean = false,
    val hazmat: Boolean = false,
    /** Boats: height above the waterline, depth below it, beam. */
    val airDraftM: Double? = null,
    val draftM: Double? = null,
    val beamM: Double? = null,
    val maxSpeedKmh: Double? = null,
    val ev: EvSpec? = null,
)

/** What the route should favour. Weights run 0 (do not care) to 1 (strongly). */
data class RoutePrefs(
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
    val avoidUnpaved: Boolean = false,
    val avoidSteps: Boolean = false,
    val quiet: Double = 0.0,
    val green: Double = 0.0,
    val flat: Double = 0.0,
    val shade: Double = 0.0,
    val lit: Double = 0.0,
    val fast: Double = 0.5,
)

data class RouteRequest(
    val mode: Mode,
    /** From, via…, to. At least two. */
    val waypoints: List<LatLon>,
    val prefs: RoutePrefs = RoutePrefs(),
    val vehicle: Vehicle = Vehicle(),
    val departAt: Instant? = null,
    /** Compass heading at the start, so the first instruction does not say "turn around" needlessly. */
    val startHeading: Double? = null,
    /** How many alternatives to try for, besides the best. */
    val alternatives: Int = 0,
)

enum class ManeuverType {
    DEPART, ARRIVE, WAYPOINT, CONTINUE, TURN, KEEP, MERGE, RAMP_ON, RAMP_OFF,
    ROUNDABOUT, ROUNDABOUT_EXIT, U_TURN, END_OF_ROAD, FERRY_BOARD, FERRY_LEAVE,
    /** Boats: a lock, a bridge to pass, a buoy to keep on one side. */
    LOCK, BRIDGE, PASS_MARK,
}

enum class Turn(val angle: Int) {
    STRAIGHT(0), SLIGHT_RIGHT(30), RIGHT(90), SHARP_RIGHT(140), SLIGHT_LEFT(-30), LEFT(-90), SHARP_LEFT(-140), U_TURN(180);

    val left get() = angle < 0
    val right get() = angle in 1..179

    companion object {
        /** Classifies a signed turn angle (negative left) into a turn. */
        fun of(angle: Double): Turn = when {
            angle <= -165 || angle >= 165 -> U_TURN
            angle < -120 -> SHARP_LEFT
            angle < -45 -> LEFT
            angle < -15 -> SLIGHT_LEFT
            angle <= 15 -> STRAIGHT
            angle <= 45 -> SLIGHT_RIGHT
            angle <= 120 -> RIGHT
            else -> SHARP_RIGHT
        }
    }
}

/** One lane at a junction: the directions it allows and whether it leads along the route. */
data class Lane(val turns: Set<Turn>, val valid: Boolean)

/** Something the guide can name to make an instruction easy: "after the bridge", "at the church". */
data class Landmark(val kind: String, val name: String?, val location: LatLon, val side: Int = 0, val tags: Map<String, String> = emptyMap())

data class Maneuver(
    val type: ManeuverType,
    val turn: Turn = Turn.STRAIGHT,
    /** Roundabout exit number, 1-based. */
    val exit: Int? = null,
    val bearingBefore: Double? = null,
    val bearingAfter: Double? = null,
)

/** One instruction: the maneuver at [pointIndex] of the route geometry, then the stretch until the next one. */
data class Step(
    val maneuver: Maneuver,
    val pointIndex: Int,
    val location: LatLon,
    /** Metres and seconds from this maneuver to the next. */
    val distance: Double,
    val duration: Double,
    val street: String? = null,
    val ref: String? = null,
    val destinations: String? = null,
    val lanes: List<Lane> = emptyList(),
    val landmarks: List<Landmark> = emptyList(),
)

/** A stretch of the route with uniform properties, by point index range [from, to). */
data class Span(val from: Int, val to: Int, val key: String, val value: String)

/** Something ahead the guide should warn about: a low bridge, a steep climb, cobbles, a speed camera, a lock. */
data class Hazard(
    val kind: String,
    val pointIndex: Int,
    val location: LatLon,
    val value: Double? = null,
    val text: String? = null,
    val tags: Map<String, String> = emptyMap(),
)

data class Route(
    val mode: Mode,
    val points: List<LatLon>,
    /** Metres above sea level per point, or empty. */
    val elevations: DoubleArray = DoubleArray(0),
    val distance: Double,
    val duration: Double,
    val steps: List<Step>,
    /** Per-stretch facts: surface, highway, maxspeed, cycleway, smoothness, incline, waterway… */
    val spans: List<Span> = emptyList(),
    val hazards: List<Hazard> = emptyList(),
    /** Charging stops planned on the way, as places with "atlas:charge_minutes" and "atlas:arrive_soc" in their extra. */
    val stops: List<Place> = emptyList(),
    val climb: Double = 0.0,
    val descent: Double = 0.0,
    /** Which engine and profile made it, e.g. "brouter:trekking". */
    val source: String = "",
) {
    val line: Polyline by lazy { Polyline(points) }

    override fun equals(other: Any?) = other is Route && other.points == points && other.steps == steps && other.source == source
    override fun hashCode() = points.hashCode() * 31 + steps.hashCode()
}

/** A location fix as the phone reports it. Speed in m/s, bearing in degrees, accuracy in metres. */
data class Fix(
    val location: LatLon,
    val time: Instant,
    val accuracy: Double = 10.0,
    val speed: Double? = null,
    val bearing: Double? = null,
    val altitude: Double? = null,
)
