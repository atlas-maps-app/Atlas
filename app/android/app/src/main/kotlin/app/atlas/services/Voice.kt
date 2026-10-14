package app.atlas.services

import app.atlas.guide.Cue
import app.atlas.guide.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice of the guide: speech, earcons and vibrations for `:guide` [Cue]s, and plain sentences for "where am I" and
 * the voice sample in Settings. The go package implements it over TextToSpeech with navigation audio attributes and
 * audio focus; screens only talk to this interface (through `Services.voice`).
 */
interface Voice : AutoCloseable {
    /** True once an engine is ready to speak. */
    val ready: StateFlow<Boolean>

    /** The last sentence said, for the voice bubble on Sail and the "repeat" button. */
    val lastSaid: StateFlow<String?>

    /** Renders one cue: speaks a [Cue.Speak], plays an [Cue.Earcon], vibrates a [Cue.Haptic]; others are ignored. */
    fun play(cue: Cue)

    /** Says [text] now. */
    fun say(text: String, priority: Priority = Priority.NORMAL)

    /** Repeats the last sentence. */
    fun repeat()

    /** Silences anything playing or queued. */
    fun stop()

    override fun close() = stop()
}

/**
 * No voice yet: nothing is spoken and [ready] stays false, so screens show the voice as unavailable rather than
 * pretending. It remembers what it was asked to say, which tests read.
 */
class NoVoice : Voice {
    private val said = MutableStateFlow<String?>(null)
    override val ready: StateFlow<Boolean> = MutableStateFlow(false)
    override val lastSaid: StateFlow<String?> = said

    /** Everything asked for, in order. */
    val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    override fun play(cue: Cue) {
        if (cue is Cue.Speak) say(cue.text, cue.priority)
    }

    override fun say(text: String, priority: Priority) {
        said.value = text
        log += text
    }

    override fun repeat() {
        said.value?.let { log += it }
    }

    override fun stop() = Unit
}
