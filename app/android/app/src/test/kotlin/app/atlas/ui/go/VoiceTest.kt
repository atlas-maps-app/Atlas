package app.atlas.ui.go

import app.atlas.guide.Cue
import app.atlas.guide.EarconKind
import app.atlas.guide.Haptics
import app.atlas.guide.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The guide's voice over a fake speech engine, audio focus, earcon player and vibrator. */
class VoiceTest {
    /** A speech engine that records what it is asked and finishes utterances when told. */
    class FakeEngine(private val readyAtStart: Boolean = true) : SpeechEngine {
        data class Said(val text: String, val flush: Boolean, val pan: Float, val id: String)

        val said = ArrayList<Said>()
        var stops = 0
        var shutdown = false
        var locale: Locale? = null
        private var finished: (String) -> Unit = {}
        private var ready: (Boolean) -> Unit = {}

        override fun start(locale: Locale, ready: (Boolean) -> Unit) {
            this.locale = locale
            this.ready = ready
            if (readyAtStart) ready(true)
        }

        fun becomeReady() = ready(true)

        override fun speak(text: String, flush: Boolean, pan: Float, id: String) {
            said += Said(text, flush, pan, id)
        }

        override fun stop() {
            stops++
        }

        override fun onFinished(finished: (String) -> Unit) {
            this.finished = finished
        }

        fun finish(id: String) = finished(id)

        fun finishAll() = said.map { it.id }.forEach(::finish)

        override fun shutdown() {
            shutdown = true
        }
    }

    class FakeFocus(var grant: Boolean = true) : AudioFocus {
        var requests = 0
        var abandons = 0
        override fun request(): Boolean {
            requests++
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    class FakeEarcons(private val instant: Boolean = true) : EarconSink {
        val played = ArrayList<Triple<EarconKind, Float, Float>>()
        val pending = ArrayList<() -> Unit>()
        override fun play(kind: EarconKind, pan: Float, pitch: Float, done: () -> Unit) {
            played += Triple(kind, pan, pitch)
            if (instant) done() else pending += done
        }
    }

    class FakeHaptics : HapticSink {
        val felt = ArrayList<Cue.Haptic>()
        override fun vibrate(cue: Cue.Haptic) {
            felt += cue
        }
    }

    private val engine = FakeEngine()
    private val focus = FakeFocus()
    private val earcons = FakeEarcons()
    private val haptics = FakeHaptics()
    private var headphones = false
    private val voice = TtsVoice(engine, focus, earcons, haptics, { headphones }, Locale.forLanguageTag("nl-NL"))

    @Test
    fun speaksInTheTravellersLanguageAndHoldsFocusOnlyWhileTalking() {
        assertEquals("nl", engine.locale?.language)
        assertTrue(voice.ready.value)
        voice.say("Over 200 meter rechtsaf")
        voice.say("Daarna linksaf")
        assertEquals(listOf("Over 200 meter rechtsaf", "Daarna linksaf"), engine.said.map { it.text })
        assertEquals("Daarna linksaf", voice.lastSaid.value)
        // One request for both: ducking music once, not twice.
        assertEquals(1, focus.requests)
        assertTrue(voice.holdingFocus)
        engine.finish(engine.said[0].id)
        assertTrue("still talking", voice.holdingFocus)
        engine.finish(engine.said[1].id)
        assertFalse(voice.holdingFocus)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun aMoreUrgentLineCutsInOnlyOnInterruptibleOnes() {
        voice.play(Cue.Speak("Continue for 2 kilometres", priority = Priority.LOW))
        voice.play(Cue.Speak("Turn right now", priority = Priority.HIGH))
        assertFalse(engine.said[0].flush)
        assertTrue("the turn flushes the chatter", engine.said[1].flush)
        engine.finishAll()

        voice.play(Cue.Speak("Low bridge, 3.2 metres", priority = Priority.CRITICAL, interruptible = false))
        voice.play(Cue.Speak("Turn left", priority = Priority.HIGH))
        assertFalse("a critical warning is never cut", engine.said.last().flush)
    }

    @Test
    fun panIsUsedOnHeadphonesOnly() {
        voice.play(Cue.Speak("Café on your right", pan = 0.7f))
        voice.play(Cue.Earcon(EarconKind.TURN_RIGHT, pan = 0.7f))
        assertEquals(0f, engine.said.last().pan)
        assertEquals(0f, earcons.played.last().second)
        headphones = true
        voice.play(Cue.Speak("Café on your right", pan = 0.7f))
        voice.play(Cue.Earcon(EarconKind.TURN_RIGHT, pan = 0.7f, pitch = 1.2f))
        assertEquals(0.7f, engine.said.last().pan)
        assertEquals(Triple(EarconKind.TURN_RIGHT, 0.7f, 1.2f), earcons.played.last())
    }

    @Test
    fun cuesGoToTheirOwnChannels() {
        voice.play(Cue.Speak("Turn right"))
        voice.play(Cue.Earcon(EarconKind.TURN_RIGHT))
        voice.play(Haptics.right())
        voice.play(Cue.Arrived)
        assertEquals(1, engine.said.size)
        assertEquals(listOf(EarconKind.TURN_RIGHT), earcons.played.map { it.first })
        assertEquals(3, haptics.felt.single().pulses)
    }

    @Test
    fun mutedKeepsTheVibrationsAndTheLastLine() {
        voice.setMuted(true)
        voice.play(Cue.Speak("Turn left"))
        voice.play(Cue.Earcon(EarconKind.TURN_LEFT))
        voice.play(Haptics.left())
        assertTrue(engine.said.isEmpty())
        assertTrue(earcons.played.isEmpty())
        assertEquals(1, haptics.felt.size)
        // The bubble on the screen still shows what would have been said.
        assertEquals("Turn left", voice.lastSaid.value)
        voice.setMuted(false)
        voice.setHaptics(false)
        voice.play(Haptics.left())
        assertEquals(1, haptics.felt.size)
    }

    @Test
    fun repeatSaysTheLastLineAgainUrgently() {
        assertNull(voice.lastSaid.value)
        voice.repeat()
        assertTrue(engine.said.isEmpty())
        voice.say("Turn right onto Raadhuisstraat")
        engine.finishAll()
        voice.repeat()
        assertEquals("Turn right onto Raadhuisstraat", engine.said.last().text)
    }

    @Test
    fun withoutFocusNothingIsSpoken() {
        focus.grant = false
        voice.say("Turn right")
        voice.play(Cue.Earcon(EarconKind.TURN_RIGHT))
        assertTrue(engine.said.isEmpty())
        assertTrue(earcons.played.isEmpty())
        // A call ended: the next line gets through.
        focus.grant = true
        voice.say("Turn right")
        assertEquals(1, engine.said.size)
    }

    @Test
    fun anEarconHoldsFocusUntilItHasPlayed() {
        val slow = FakeEarcons(instant = false)
        val f = FakeFocus()
        val v = TtsVoice(FakeEngine(), f, slow, FakeHaptics(), { false })
        v.play(Cue.Earcon(EarconKind.ARRIVE))
        assertTrue(v.holdingFocus)
        slow.pending.single()()
        assertFalse(v.holdingFocus)
        assertEquals(1, f.abandons)
    }

    @Test
    fun aVoiceNotReadyYetStaysQuietThenSpeaks() {
        val e = FakeEngine(readyAtStart = false)
        val v = TtsVoice(e, FakeFocus(), FakeEarcons(), FakeHaptics(), { false })
        assertFalse(v.ready.value)
        v.say("Turn right")
        assertTrue(e.said.isEmpty())
        e.becomeReady()
        v.say("Turn right")
        assertEquals(1, e.said.size)
    }

    @Test
    fun stopAndCloseLetGo() {
        voice.say("Turn right")
        voice.stop()
        assertEquals(1, engine.stops)
        assertFalse(voice.holdingFocus)
        voice.close()
        assertTrue(engine.shutdown)
    }
}
