package app.atlas.ui.go

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.atlas.guide.Cue

/**
 * A [Cue.Haptic] as `VibrationEffect.Composition` primitives: each pulse of the guide's rhythm becomes one primitive
 * after the pause before it. Short taps are clicks at the pulse's strength; long pulses are a slow swell that falls away,
 * so "one long" still feels long on a phone whose motor only does primitives well.
 */
data class HapticPlan(val steps: List<Step>) {
    /** One primitive: which, at what strength (0..1), after how many milliseconds of quiet. */
    data class Step(val primitive: Int, val scale: Float, val delayMs: Int)

    /** The distinct primitives the plan uses, to ask the vibrator about. */
    val primitives: IntArray get() = steps.map { it.primitive }.distinct().toIntArray()

    companion object {
        /** `VibrationEffect.Composition` primitive ids (API 30/31), by value so the plan is plain Kotlin. */
        const val CLICK = 1
        const val SLOW_RISE = 5
        const val QUICK_FALL = 6
        const val THUD = 2

        /** Pulses at or under this many milliseconds are taps. */
        const val TAP_MS = 150L

        fun of(cue: Cue.Haptic): HapticPlan {
            val out = ArrayList<Step>()
            var quiet = 0L
            for (i in cue.pattern.indices) {
                val ms = cue.pattern[i]
                val amp = cue.amplitudes[i]
                if (amp <= 0) {
                    quiet += ms
                    continue
                }
                val scale = (amp / 255f).coerceIn(0.1f, 1f)
                val delay = quiet.coerceAtMost(MAX_DELAY).toInt()
                if (ms <= TAP_MS) {
                    out += Step(CLICK, scale, delay)
                } else {
                    out += Step(SLOW_RISE, scale, delay)
                    out += Step(QUICK_FALL, scale, 0)
                }
                // A primitive takes about this long itself; the rest of the pulse is waited out before the next one.
                quiet = (ms - PRIMITIVE_MS).coerceAtLeast(0)
            }
            return HapticPlan(out)
        }

        private const val PRIMITIVE_MS = 40L
        private const val MAX_DELAY = 1_000L
    }
}

/** Feels [Cue.Haptic]s; the app's uses the vibrator, tests a list. */
fun interface HapticSink {
    fun vibrate(cue: Cue.Haptic)
}

/**
 * The phone's vibrator: compositions when it supports every primitive a plan needs (API 30+), else the guide's own
 * waveform with amplitudes, else the waveform's timings alone. Turn cues use the communication-request usage, which
 * Android lets through in battery saver and from a foreground service with the screen off.
 */
class AndroidHaptics(context: Context) : HapticSink {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun vibrate(cue: Cue.Haptic) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            val effect = effectFor(v, cue)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
            }
        }
    }

    private fun effectFor(v: Vibrator, cue: Cue.Haptic): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 30) {
            val plan = HapticPlan.of(cue)
            if (plan.steps.isNotEmpty() && v.areAllPrimitivesSupported(*plan.primitives)) {
                val c = VibrationEffect.startComposition()
                for (s in plan.steps) c.addPrimitive(s.primitive, s.scale, s.delayMs)
                return c.compose()
            }
        }
        return if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(cue.pattern, cue.amplitudes, -1)
        else VibrationEffect.createWaveform(cue.pattern, -1)
    }
}
