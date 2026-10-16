package app.atlas.ui.go

import android.content.Context
import app.atlas.guide.Cue
import app.atlas.guide.EarconKind
import app.atlas.guide.Priority
import app.atlas.services.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The guide's voice: speech through the phone's TextToSpeech (navigation-guidance audio, offline voices preferred),
 * earcons synthesised in code, and vibrations. It holds transient, ducking audio focus only while something plays and
 * gives it back after the last utterance or earcon. A more urgent line cuts in on interruptible ones; others queue.
 * Left and right pans are applied only on headphones ([headphones]), where they help.
 *
 * [muted] silences speech and earcons but keeps the vibrations, so a quiet ride still feels its turns; [hapticsOn]
 * turns those off.
 */
class TtsVoice(
    private val engine: SpeechEngine,
    private val focus: AudioFocus,
    private val earcons: EarconSink,
    private val haptics: HapticSink,
    private val headphones: () -> Boolean,
    locale: Locale = Locale.getDefault(),
) : Voice {
    private val readyState = MutableStateFlow(false)
    private val said = MutableStateFlow<String?>(null)
    private val mutedState = MutableStateFlow(false)
    private val hapticsState = MutableStateFlow(true)

    private class Pending(val priority: Priority, val interruptible: Boolean)

    /** Utterances handed to the engine and not finished, in order; guarded by itself. */
    private val pending = LinkedHashMap<String, Pending>()
    private var earconsPlaying = 0
    private var focusHeld = false
    private var counter = 0

    /** Lines asked for before the engine was ready, said when it is (recent ones only), guarded by [pending]. */
    private class Early(val text: String, val priority: Priority, val interruptible: Boolean, val pan: Float, val at: Long)
    private val early = ArrayList<Early>()

    override val ready: StateFlow<Boolean> = readyState.asStateFlow()
    override val lastSaid: StateFlow<String?> = said.asStateFlow()

    val muted: StateFlow<Boolean> = mutedState.asStateFlow()
    val hapticsOn: StateFlow<Boolean> = hapticsState.asStateFlow()

    init {
        engine.onFinished(::finished)
        engine.start(locale) { ok ->
            readyState.value = ok
            if (ok) sayEarly() else synchronized(pending) { early.clear() }
        }
    }

    /** Says what was asked while the engine started (the trip's first instruction), unless it has gone stale. */
    private fun sayEarly() {
        val lines = synchronized(pending) {
            val now = System.nanoTime()
            early.filter { now - it.at < EARLY_NS }.takeLast(EARLY_MAX).also { early.clear() }
        }
        for (l in lines) speak(l.text, l.priority, l.interruptible, l.pan)
    }

    fun setMuted(muted: Boolean) {
        mutedState.value = muted
        if (muted) stop()
    }

    fun setHaptics(on: Boolean) {
        hapticsState.value = on
    }

    override fun play(cue: Cue) {
        when (cue) {
            is Cue.Speak -> speak(cue.text, cue.priority, cue.interruptible, cue.pan)
            is Cue.Earcon -> earcon(cue.kind, cue.pan, cue.pitch)
            is Cue.Haptic -> if (hapticsState.value) haptics.vibrate(cue)
            else -> Unit
        }
    }

    override fun say(text: String, priority: Priority) = speak(text, priority, true, 0f)

    override fun repeat() {
        said.value?.let { speak(it, Priority.HIGH, false, 0f) }
    }

    override fun stop() {
        synchronized(pending) {
            early.clear()
            pending.clear()
            engine.stop()
            releaseIfIdle()
        }
    }

    override fun close() {
        stop()
        engine.shutdown()
        (earcons as? AutoCloseable)?.close()
    }

    private fun speak(text: String, priority: Priority, interruptible: Boolean, pan: Float) {
        if (text.isBlank()) return
        said.value = text
        if (mutedState.value) return
        if (!readyState.value) {
            synchronized(pending) {
                if (!readyState.value) {
                    early += Early(text, priority, interruptible, pan, System.nanoTime())
                    return
                }
            }
        }
        synchronized(pending) {
            if (!hold()) return
            // Cut in only on lines that may be cut, and only for something more urgent than all of them.
            val flush = pending.isNotEmpty() && pending.values.all { it.interruptible && it.priority < priority }
            if (flush) pending.clear()
            val id = "atlas-${++counter}"
            pending[id] = Pending(priority, interruptible)
            engine.speak(text, flush, if (headphones()) pan else 0f, id)
        }
    }

    private fun earcon(kind: EarconKind, pan: Float, pitch: Float) {
        if (mutedState.value) return
        synchronized(pending) {
            if (!hold()) return
            earconsPlaying++
        }
        earcons.play(kind, if (headphones()) pan else 0f, pitch) {
            synchronized(pending) {
                earconsPlaying = (earconsPlaying - 1).coerceAtLeast(0)
                releaseIfIdle()
            }
        }
    }

    private fun finished(id: String) {
        synchronized(pending) {
            pending.remove(id)
            releaseIfIdle()
        }
    }

    /** Takes focus if not held; false when the system refuses (a call, or no right to play now). */
    private fun hold(): Boolean {
        if (focusHeld) return true
        focusHeld = focus.request()
        return focusHeld
    }

    private fun releaseIfIdle() {
        if (focusHeld && pending.isEmpty() && earconsPlaying == 0) {
            focus.abandon()
            focusHeld = false
        }
    }

    /** True while the voice holds audio focus (for tests and the status icons). */
    val holdingFocus: Boolean get() = synchronized(pending) { focusHeld }

    companion object {
        private const val EARLY_NS = 10_000_000_000L
        private const val EARLY_MAX = 2

        /** The voice of the app: the phone's TextToSpeech, AudioTrack earcons and the vibrator. */
        fun forApp(context: Context): TtsVoice {
            val app = context.applicationContext ?: context
            return TtsVoice(AndroidSpeechEngine(app), AndroidAudioFocus(app), AudioTrackEarcons(), AndroidHaptics(app), { headphonesOn(app) })
        }
    }
}
