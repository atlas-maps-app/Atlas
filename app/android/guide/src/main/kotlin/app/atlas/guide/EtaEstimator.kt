package app.atlas.guide

import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/**
 * Remaining time from the route's planned time profile, corrected by the traveller's own pace so far.
 * The pace is planned seconds covered per real second, smoothed over minutes (longer for cars, whose roads ahead differ
 * more from the roads behind) and trusted more on foot, by bike and on the water. Planned charging stops are time, not
 * pace: standing at one teaches nothing, and the charging still ahead is added as planned.
 */
class EtaEstimator(private val profile: ModeProfile) {
    private var pace = 1.0
    private var lastAlong = Double.NaN
    private var lastTime: Instant? = null
    private var started = false
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

    /** Feeds one matched position; [onRoute] false (off the route) pauses learning. */
    fun update(index: RouteIndex, along: Double, time: Instant, speed: Double, onRoute: Boolean) {
        val prevTime = lastTime
        val atStop = index.atStop(along)
        if (!atStop) dwell = 0.0
        if (prevTime != null && onRoute && !lastAlong.isNaN()) {
            val dt = Duration.between(prevTime, time).toMillis() / 1000.0
            val charging = speed < profile.minMovingSpeed && atStop
            if (charging && dt > 0) dwell += dt.coerceAtMost(600.0)
            if (dt > 0 && dt < 60 && !charging) {
                if (!started && (along > 20 || speed >= profile.minMovingSpeed)) started = true
                if (started) {
                    val planned = index.drivingTimeAt(along) - index.drivingTimeAt(lastAlong)
                    val r = (planned / dt).coerceIn(0.0, 3.0)
                    val a = 1 - exp(-dt / tau)
                    pace += a * (r - pace)
                }
            }
        }
        if (onRoute) {
            lastAlong = along
            lastTime = time
        }
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
        dwell = 0.0
    }
}
