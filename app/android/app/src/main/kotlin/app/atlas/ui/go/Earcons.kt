package app.atlas.ui.go

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.atlas.guide.EarconKind
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The guide's earcons, synthesised in code: no sound files. Each is a few soft sine notes with a bell-like envelope,
 * placed left or right by constant-power pan. Turns are heard before any word: right rises, left falls, a keep glides
 * gently, a U-turn falls and rises, arrival is a rising major arpeggio, off-route two low knocks, the beacon one ping
 * whose pitch rises as you face the target.
 */
object EarconSynth {
    const val SAMPLE_RATE = 22_050

    /** One note: [freq] Hz from [startMs] for [lengthMs], gliding to [glideTo] Hz when given, at [gain] 0..1. */
    data class Note(val freq: Double, val startMs: Int, val lengthMs: Int, val gain: Double = 0.8, val glideTo: Double? = null)

    /** The notes of [kind] at [pitch] (1 = as designed). */
    fun notes(kind: EarconKind, pitch: Float = 1f): List<Note> {
        val p = pitch.toDouble().coerceIn(0.25, 4.0)
        fun n(f: Double, at: Int, len: Int, g: Double = 0.8, to: Double? = null) = Note(f * p, at, len, g, to?.times(p))
        return when (kind) {
            // A fourth up for right, a fourth down for left: direction by contour.
            EarconKind.TURN_RIGHT -> listOf(n(E5, 0, 120), n(A5, 110, 170))
            EarconKind.TURN_LEFT -> listOf(n(A5, 0, 120), n(E5, 110, 170))
            EarconKind.KEEP_RIGHT -> listOf(n(E5, 0, 220, 0.55, to = G5))
            EarconKind.KEEP_LEFT -> listOf(n(G5, 0, 220, 0.55, to = E5))
            EarconKind.STRAIGHT -> listOf(n(A5, 0, 160, 0.6))
            EarconKind.U_TURN -> listOf(n(A5, 0, 110), n(E5, 100, 110), n(A5, 200, 160))
            EarconKind.ARRIVE -> listOf(n(C5, 0, 160), n(E5, 140, 160), n(G5, 280, 160), n(C6, 420, 320))
            EarconKind.OFF_ROUTE -> listOf(n(A3, 0, 90, 0.9), n(A3, 170, 90, 0.9))
            EarconKind.REROUTED -> listOf(n(G5, 0, 100, 0.6), n(C6, 90, 180, 0.6))
            EarconKind.HAZARD -> listOf(n(C6, 0, 80, 0.7), n(C6, 130, 80, 0.7))
            EarconKind.BEACON -> listOf(n(E5, 0, 140, 0.65))
        }
    }

    /** How long [kind] lasts, in milliseconds. */
    fun lengthMs(kind: EarconKind): Int = notes(kind).maxOf { it.startMs + it.lengthMs }

    /**
     * Interleaved stereo 16-bit PCM of [kind], panned to [pan] (−1 left … 1 right) with constant power, at [pitch].
     * Every note starts and ends at zero, so nothing clicks.
     */
    fun pcm(kind: EarconKind, pan: Float = 0f, pitch: Float = 1f, rate: Int = SAMPLE_RATE): ShortArray {
        val notes = notes(kind, pitch)
        val total = (notes.maxOf { it.startMs + it.lengthMs } * rate / 1000) + 1
        val mix = DoubleArray(total)
        for (note in notes) {
            val start = note.startMs * rate / 1000
            val len = note.lengthMs * rate / 1000
            val attack = min(len / 8, (0.008 * rate).toInt()).coerceAtLeast(1)
            var phase = 0.0
            for (i in 0 until len) {
                val t = i.toDouble() / len
                val f = if (note.glideTo != null) note.freq + (note.glideTo - note.freq) * t else note.freq
                phase += 2 * PI * f / rate
                // A quick rise, then a bell's exponential decay that reaches zero at the end.
                val env = if (i < attack) i.toDouble() / attack else exp(-3.0 * t) * (1 - t)
                // A touch of the octave makes it round rather than a pure test tone.
                val v = sin(phase) + 0.18 * sin(2 * phase)
                val k = start + i
                if (k < total) mix[k] += v * env * note.gain
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val norm = 0.7 / peak
        val a = (pan.coerceIn(-1f, 1f) + 1.0) * PI / 4
        val left = cos(a)
        val right = sin(a)
        val out = ShortArray(total * 2)
        for (i in 0 until total) {
            val v = mix[i] * norm * Short.MAX_VALUE
            out[2 * i] = (v * left).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (v * right).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private const val A3 = 220.0
    private const val C5 = 523.25
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.0
    private const val C6 = 1046.5
}

/** Plays earcons; the app's uses an AudioTrack, tests a list. */
fun interface EarconSink {
    /** Plays [kind] panned to [pan] at [pitch]; [done] runs when it has finished. */
    fun play(kind: EarconKind, pan: Float, pitch: Float, done: () -> Unit)
}

/**
 * Earcons through a static AudioTrack each, with navigation-guidance audio attributes, on one background thread so the
 * caller never waits. A track is released once it has played.
 */
class AudioTrackEarcons : EarconSink, AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "atlas-earcons").apply { isDaemon = true } }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun play(kind: EarconKind, pan: Float, pitch: Float, done: () -> Unit) {
        worker.execute {
            val pcm = EarconSynth.pcm(kind, pan, pitch)
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(EarconSynth.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
            }.getOrNull()
            if (track == null || track.state == AudioTrack.STATE_UNINITIALIZED) {
                track?.release()
                done()
                return@execute
            }
            try {
                track.write(pcm, 0, pcm.size)
                track.play()
                // The frames played are known exactly: wait for them, plus a little for the device's latency.
                Thread.sleep(pcm.size / 2 * 1000L / EarconSynth.SAMPLE_RATE + 60)
            } catch (_: Exception) {
            } finally {
                runCatching { track.stop() }
                track.release()
                done()
            }
        }
    }

    override fun close() {
        worker.shutdownNow()
    }
}
