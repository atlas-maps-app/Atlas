package app.atlas.ui.go

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * A driver's hours as the EU rules count them (Regulation (EC) No 561/2006): after 4 h 30 of driving a break of
 * 45 minutes, which may be split into 15 and then 30; at most 9 hours of driving a day, the day ending with a rest of
 * 9 hours or more. Seconds throughout; [since] is the last moment counted.
 */
data class TachoState(
    val drivingSinceBreak: Long = 0,
    val drivenToday: Long = 0,
    /** Seconds stood still so far, in the stop going on now. */
    val stoppedFor: Long = 0,
    /** A first part of a split break (15 minutes or more) has been taken since the last full break. */
    val splitTaken: Boolean = false,
    val since: Instant? = null,
) {
    /** Driving left before the break is due (never negative). */
    val toBreak: Long get() = (Tachograph.MAX_BLOCK - drivingSinceBreak).coerceAtLeast(0)

    /** Driving left today. */
    val leftToday: Long get() = (Tachograph.MAX_DAY - drivenToday).coerceAtLeast(0)

    /** True when the break is due now (or overdue). */
    val breakDue: Boolean get() = drivingSinceBreak >= Tachograph.MAX_BLOCK

    /** The share of the 4 h 30 block driven, 0..1, for the ring. */
    val blockShare: Float get() = (drivingSinceBreak.toFloat() / Tachograph.MAX_BLOCK).coerceIn(0f, 1f)

    /** The break the next stop must last to count, in seconds: 30 after a first 15-minute part, else 45. */
    val breakNeeded: Long get() = if (splitTaken) Tachograph.SPLIT_SECOND else Tachograph.FULL_BREAK

    fun toJson(): JsonObject = buildJsonObject {
        put("block", drivingSinceBreak); put("day", drivenToday); put("stopped", stoppedFor); put("split", splitTaken)
        since?.let { put("since", it.toEpochMilli()) }
    }

    companion object {
        fun fromJson(o: JsonObject?): TachoState? {
            o ?: return null
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            return TachoState(
                drivingSinceBreak = l("block")?.coerceAtLeast(0) ?: 0,
                drivenToday = l("day")?.coerceAtLeast(0) ?: 0,
                stoppedFor = l("stopped")?.coerceAtLeast(0) ?: 0,
                splitTaken = (o["split"] as? JsonPrimitive)?.booleanOrNull ?: false,
                since = l("since")?.let { Instant.ofEpochMilli(it) },
            )
        }
    }
}

/** Counts driving and rest from fixes, by the rules in [TachoState]. */
object Tachograph {
    const val MAX_BLOCK = 4 * 3600L + 30 * 60
    const val FULL_BREAK = 45 * 60L
    const val SPLIT_FIRST = 15 * 60L
    const val SPLIT_SECOND = 30 * 60L
    const val MAX_DAY = 9 * 3600L
    const val DAILY_REST = 9 * 3600L

    /** Moving faster than this (m/s, about 7 km/h) is driving; slower is standing (traffic lights count as driving). */
    const val MOVING = 2.0

    /** Stops shorter than this are part of driving, as at lights and in queues. */
    const val SHORT_STOP = 60L

    /** The state after time moves to [now] with the vehicle [moving] or standing since the last count. */
    fun advance(s: TachoState, now: Instant, moving: Boolean): TachoState {
        val last = s.since ?: return s.copy(since = now)
        val dt = Duration.between(last, now).seconds
        if (dt <= 0) return s
        // A gap with no fixes (the app closed, the phone off) is rest, not driving.
        if (dt > GAP) return standing(ended(s), dt).copy(stoppedFor = 0, since = now)
        if (!moving) return standing(s, dt).copy(since = now)
        // A stop too short to rest in counts as driving all along, as at lights and in queues.
        val held = if (s.stoppedFor in 1 until SHORT_STOP) s.stoppedFor else 0
        val r = ended(s)
        return r.copy(drivingSinceBreak = r.drivingSinceBreak + dt + held, drivenToday = r.drivenToday + dt + held, stoppedFor = 0, since = now)
    }

    /** Standing [dt] more seconds: a break resets the block as soon as it is long enough, a daily rest the day. */
    private fun standing(s: TachoState, dt: Long): TachoState {
        val after = s.stoppedFor + dt
        if (after >= DAILY_REST) return TachoState(stoppedFor = after)
        val enough = if (s.splitTaken) SPLIT_SECOND else FULL_BREAK
        return if (after >= enough) s.copy(drivingSinceBreak = 0, splitTaken = false, stoppedFor = after) else s.copy(stoppedFor = after)
    }

    /** A stop ending: one of 15 minutes or more that was not yet a full break is the first part of a split break. */
    private fun ended(s: TachoState): TachoState =
        if (!s.splitTaken && s.stoppedFor >= SPLIT_FIRST && s.drivingSinceBreak > 0) s.copy(splitTaken = true, stoppedFor = 0) else s.copy(stoppedFor = 0)

    private const val GAP = 10 * 60L
}
