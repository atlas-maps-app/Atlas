package app.atlas.guide

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Remaining time from the route's planned time profile, corrected by the traveller's own pace so far.
 * The pace is planned seconds covered per real second, smoothed over minutes (longer for cars, whose roads ahead differ
 * more from the roads behind) and trusted more on foot, by bike and on the water. It is measured over stretches long
 * enough to rise above GPS noise (a walker covers less in a second than a fix wanders), never fix by fix. Planned
 * charging stops are time, not pace: standing at one teaches nothing, and the charging still ahead is added as planned.
 */
class EtaEstimator(private val profile: ModeProfile) {
    private var pace = 1.0
    private var lastAlong = Double.NaN
    private var lastTime: Instant? = null
    private var started = false
    /** Where and when the stretch being measured began; null when none is under way. */
    private var anchorAlong = 0.0
    private var anchorTime: Instant? = null
    /** Seconds spent standing at the charging stop you are at; it comes off that stop's planned charge. */
    private var dwell = 0.0

    private val tau: Double = when (profile.group) {
        ModeGroup.WALK -> 60.0
        ModeGroup.BIKE -> 90.0
        ModeGroup.CAR, ModeGroup.TRUCK -> 180.0
        ModeGroup.BOAT -> 300.0
    }
    private val trust: Double = when (profile.group) {
        ModeGroup.WALK, ModeGroup.BOAT -> 0.9
        ModeGroup.BIKE -> 0.8
        ModeGroup.CAR, ModeGroup.TRUCK -> 0.6
    }

    /** Planned seconds per real second so far, smoothed; 1 is exactly on plan. */
    val currentPace: Double get() = pace

    /**
     * Feeds one matched position; [onRoute] false (off the route) pauses learning. [accuracy] (metres) sets how long a
     * stretch must be before its pace is trusted.
     */
    fun update(index: RouteIndex, along: Double, time: Instant, speed: Double, onRoute: Boolean, accuracy: Double = 10.0) {
        val atStop = index.atStop(along)
        if (!atStop) dwell = 0.0
        if (!onRoute) {
            // A detour is no measure of the pace along the route: the next stretch starts when you are back on it.
            anchorTime = null
            return
        }
        val prev = lastTime
        val dt = if (prev == null || lastAlong.isNaN()) Double.NaN else Duration.between(prev, time).toMillis() / 1000.0
        if (dt == 0.0) return
        val charging = speed < profile.minMovingSpeed && atStop
        if (charging && dt > 0) dwell += dt.coerceAtMost(600.0)
        if (!started && !charging && (along > 20 || speed >= profile.minMovingSpeed)) started = true
        val anchor = anchorTime
        if (!started || charging || anchor == null || dt.isNaN() || dt < 0 || dt >= GAP_S) {
            // Standing at the start or at a charger, a gap in the fixes or a clock that went back: measure afresh from here.
            anchorAlong = along
            anchorTime = time
        } else {
            val span = Duration.between(anchor, time).toMillis() / 1000.0
            if (abs(along - anchorAlong) >= max(STRETCH_M, 2 * accuracy) || span >= STRETCH_S) {
                val planned = index.drivingTimeAt(along) - index.drivingTimeAt(anchorAlong)
                val r = (planned / span).coerceIn(0.0, 3.0)
                pace += (1 - exp(-span / tau)) * (r - pace)
                anchorAlong = along
                anchorTime = time
            }
        }
        lastAlong = along
        lastTime = time
    }

    /** Seconds still to go from [along]: the driving at the learned pace, the charging as planned. */
    fun remaining(index: RouteIndex, along: Double): Double {
        val driving = (index.plannedDriving - index.drivingTimeAt(along)).coerceAtLeast(0.0)
        val charging = (index.plannedCharging - index.chargingBefore(along) - dwell).coerceAtLeast(0.0)
        val f = (trust * pace + (1 - trust)).coerceIn(0.5, 2.0)
        return driving / f + charging
    }

    /** Keeps the learned pace for a new route. */
    fun restart() {
        lastAlong = Double.NaN
        lastTime = null
        anchorTime = null
        dwell = 0.0
    }

    private companion object {
        /** A stretch is measured once it is this long (or twice the fix's accuracy), or has lasted [STRETCH_S]. */
        const val STRETCH_M = 25.0
        const val STRETCH_S = 20.0
        /** Fixes further apart than this (a tunnel, the app asleep) start a new stretch. */
        const val GAP_S = 60.0
    }
}
