package app.atlas.guide

import app.atlas.model.Fix
import app.atlas.model.ManeuverType
import app.atlas.model.Turn

/** How urgent a cue is. The app's audio queue lets a higher priority cut in on an interruptible lower one. */
enum class Priority { LOW, NORMAL, HIGH, CRITICAL }

/** The short sounds of the guide. Turns rise for right and fall for left, so the direction is heard before any word. */
enum class EarconKind { TURN_LEFT, TURN_RIGHT, KEEP_LEFT, KEEP_RIGHT, STRAIGHT, U_TURN, ARRIVE, OFF_ROUTE, REROUTED, HAZARD, BEACON }

/** What a vibration means; the pattern for each is in [Haptics]. */
enum class HapticMeaning { LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, STRAIGHT, U_TURN, ARRIVE, OFF_ROUTE, HAZARD }

/** What the guide wants said, played or felt now. The app renders each with TTS, its earcon player and the vibrator. */
sealed interface Cue {

    /**
     * A sentence to speak. [pan] places it from −1 (left ear) to 1 (right ear) for the side it is about; the app applies
     * it on headphones only. [id] names what it is about (e.g. "step:3:approach") so the app can drop stale speech.
     */
    data class Speak(
        val text: String,
        val ssml: String? = null,
        val priority: Priority = Priority.NORMAL,
        val interruptible: Boolean = true,
        val pan: Float = 0f,
        val id: String = "",
    ) : Cue

    /** A short sound; [pitch] 1 is as recorded (the beacon raises it as you face the target). */
    data class Earcon(val kind: EarconKind, val pan: Float = 0f, val pitch: Float = 1f) : Cue

    /**
     * A vibration in Android's `VibrationEffect.createWaveform(timings, amplitudes, -1)` form: [pattern] alternates
     * off and on durations in milliseconds, starting with an off; [amplitudes] gives 0 for off and 1..255 for on.
     */
    class Haptic(val pattern: LongArray, val amplitudes: IntArray, val meaning: HapticMeaning) : Cue {
        init {
            require(pattern.size == amplitudes.size) { "pattern and amplitudes differ in length" }
        }

        /** How many on pulses the pattern has. */
        val pulses: Int get() = amplitudes.count { it > 0 }
        val totalMillis: Long get() = pattern.sum()

        override fun equals(other: Any?) = other is Haptic && other.meaning == meaning &&
            other.pattern.contentEquals(pattern) && other.amplitudes.contentEquals(amplitudes)
        override fun hashCode() = (meaning.hashCode() * 31 + pattern.contentHashCode()) * 31 + amplitudes.contentHashCode()
        override fun toString() = "Haptic($meaning, ${pattern.joinToString("/")})"
    }

    /** The traveller left the route at [from]: the app should find a new route and call `Navigator.replaceRoute`. */
    data class Reroute(val from: Fix) : Cue

    /** The destination is reached. */
    data object Arrived : Cue
}

/**
 * The haptic vocabulary: rhythms you can learn in a minute and feel through a pocket or on a wrist.
 *
 * | Meaning     | Feel             | Pattern (ms on / off)                    |
 * |-------------|------------------|------------------------------------------|
 * | left        | ● ●              | 2 short: 70 on, 130 off                  |
 * | right       | ● ● ●            | 3 short: 70 on, 130 off                  |
 * | keep left   | ● ● (soft)       | as left, at 40 % strength                |
 * | keep right  | ● ● ● (soft)     | as right, at 40 % strength               |
 * | straight    | ━                | 1 long: 450 on                           |
 * | U-turn      | ● ━ ●            | short, long, short: 90 / 400 / 90        |
 * | arrive      | ━ ● ━            | long, short, long: 400 / 90 / 400        |
 * | off route   | ●●●●●            | rapid 5: 40 on, 50 off                   |
 * | hazard      | ━ ━              | 2 long: 300 on, 200 off                  |
 *
 * Counting is the code: two is left, three is right, one long is straight. Close to the turn ([urgent]) the taps come
 * faster and at full strength, so the rhythm itself says "now". On a vibrator without amplitude control the soft "keep"
 * patterns feel like the turns they lean towards, which is still the right side.
 */
object Haptics {
    private const val FULL = 255
    private const val SOFT = 100

    fun left(urgent: Boolean = false) = pulses(2, urgent, FULL, HapticMeaning.LEFT)
    fun right(urgent: Boolean = false) = pulses(3, urgent, FULL, HapticMeaning.RIGHT)
    fun keepLeft(urgent: Boolean = false) = pulses(2, urgent, SOFT, HapticMeaning.KEEP_LEFT)
    fun keepRight(urgent: Boolean = false) = pulses(3, urgent, SOFT, HapticMeaning.KEEP_RIGHT)
    fun straight() = wave(HapticMeaning.STRAIGHT, 450L to FULL)
    fun uTurn() = wave(HapticMeaning.U_TURN, 90L to FULL, 150L to 0, 400L to FULL, 150L to 0, 90L to FULL)
    fun arrive() = wave(HapticMeaning.ARRIVE, 400L to FULL, 150L to 0, 90L to FULL, 150L to 0, 400L to FULL)
    fun offRoute() = pulses(5, onMs = 40, offMs = 50, amplitude = FULL, meaning = HapticMeaning.OFF_ROUTE)
    fun hazard() = wave(HapticMeaning.HAZARD, 300L to FULL, 200L to 0, 300L to FULL)

    /** The pattern for a maneuver: keep/fork/ramp maneuvers feel soft, U-turns have their own rhythm. */
    fun forManeuver(type: ManeuverType, turn: Turn, urgent: Boolean): Cue.Haptic {
        if (type == ManeuverType.U_TURN || turn == Turn.U_TURN) return uTurn()
        if (type == ManeuverType.ARRIVE) return arrive()
        val soft = type == ManeuverType.KEEP || type == ManeuverType.RAMP_OFF || type == ManeuverType.RAMP_ON ||
            type == ManeuverType.MERGE || turn == Turn.SLIGHT_LEFT || turn == Turn.SLIGHT_RIGHT
        return when {
            turn.left -> if (soft) keepLeft(urgent) else left(urgent)
            turn.right -> if (soft) keepRight(urgent) else right(urgent)
            else -> straight()
        }
    }

    private fun pulses(n: Int, urgent: Boolean, amplitude: Int, meaning: HapticMeaning): Cue.Haptic =
        if (urgent) pulses(n, 60, 80, FULL.coerceAtMost(amplitude + 80), meaning) else pulses(n, 70, 130, amplitude, meaning)

    private fun pulses(n: Int, onMs: Long, offMs: Long, amplitude: Int, meaning: HapticMeaning): Cue.Haptic {
        val parts = ArrayList<Pair<Long, Int>>()
        repeat(n) { i ->
            if (i > 0) parts += offMs to 0
            parts += onMs to amplitude
        }
        return wave(meaning, *parts.toTypedArray())
    }

    private fun wave(meaning: HapticMeaning, vararg parts: Pair<Long, Int>): Cue.Haptic {
        // Android's waveform starts with an off segment; a zero-length one keeps the first pulse immediate.
        val timings = LongArray(parts.size + 1)
        val amps = IntArray(parts.size + 1)
        parts.forEachIndexed { i, (ms, a) -> timings[i + 1] = ms; amps[i + 1] = a }
        return Cue.Haptic(timings, amps, meaning)
    }
}
