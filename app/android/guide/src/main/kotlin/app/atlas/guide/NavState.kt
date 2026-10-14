package app.atlas.guide

import app.atlas.geo.LatLon
import app.atlas.model.Lane
import app.atlas.model.Step
import java.time.Instant

/** Which side of the road something is on, seen in the direction of travel. */
enum class Side {
    LEFT, RIGHT, AHEAD, UNKNOWN;

    /** The pan of a sound about it: −0.7 for left, 0.7 for right (clearly to one side, still heard by both ears), 0 otherwise. */
    val pan: Float get() = when (this) { LEFT -> -0.7f; RIGHT -> 0.7f; else -> 0f }
}

/**
 * Where the traveller is on the route, after one fix.
 *
 * @property location the fix as reported; [snapped] the matched point on the route (the fix itself while off route).
 * @property along metres from the start of the route to [snapped]; [remaining] metres to the destination.
 * @property remainingTime seconds to the destination, smoothed and corrected by the pace so far.
 * @property stepIndex the step being driven: its maneuver is behind, [next] is the coming one.
 * @property distanceToManeuver metres to [next]'s maneuver.
 * @property lanes the lanes at the next maneuver.
 * @property facts the route's facts where you are: surface, maxspeed, highway, cycleway, waterway…
 * @property confidence 0..1, how sure the map match is.
 * @property progress 0..1 along the route.
 * @property speed smoothed speed, m/s.
 */
data class NavState(
    val time: Instant,
    val location: LatLon,
    val snapped: LatLon,
    val routeBearing: Double,
    val along: Double,
    val remaining: Double,
    val remainingTime: Double,
    val eta: Instant,
    val stepIndex: Int,
    val distanceToManeuver: Double,
    val next: Step?,
    val following: Step?,
    val lanes: List<Lane>,
    val facts: Map<String, String>,
    val offRoute: Boolean,
    val offset: Double,
    val confidence: Double,
    val progress: Double,
    val arrivalSide: Side,
    val speed: Double,
    val arrived: Boolean,
) {
    val surface: String? get() = facts["surface"]
    val maxspeed: String? get() = facts["maxspeed"]
}

/** The result of one fix: the [state] to show, and the [cues] to say, play or feel now (often none). */
data class Guidance(val state: NavState, val cues: List<Cue>) {
    /** The spoken sentences among [cues], for logs and tests. */
    val spoken: List<String> get() = cues.filterIsInstance<Cue.Speak>().map { it.text }
}
