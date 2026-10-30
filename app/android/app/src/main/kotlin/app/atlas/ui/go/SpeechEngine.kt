package app.atlas.ui.go

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** What [TtsVoice] needs of a speech engine: the phone's TextToSpeech in the app, a fake in tests. */
interface SpeechEngine {
    /** Starts the engine; [ready] gets true once it can speak in [locale] with a voice on the phone, false if never. */
    fun start(locale: Locale, ready: (Boolean) -> Unit)

    /** Speaks [text]; [flush] drops what is queued first. [pan] −1 … 1 is applied as given (0 for none). */
    fun speak(text: String, flush: Boolean, pan: Float, id: String)

    fun stop()

    /** [finished] gets an utterance id when it has been spoken, stopped or failed. */
    fun onFinished(finished: (String) -> Unit)

    fun shutdown()
}

/** The audio focus the voice holds while it talks: other audio ducks, then gets it back. */
interface AudioFocus {
    /** True when the voice may speak now. */
    fun request(): Boolean
    fun abandon()
}

/**
 * The phone's TextToSpeech with navigation-guidance audio attributes, speaking only with a voice installed on the phone:
 * what Atlas says is never synthesised on a server (the user, 30 October: Atlas never contacts Google, and that includes
 * a network voice, which the phone's engine is Google's on most phones).
 */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var tts: TextToSpeech? = null
    private var finished: (String) -> Unit = {}

    override fun start(locale: Locale, ready: (Boolean) -> Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val t = engine
            if (status != TextToSpeech.SUCCESS || t == null) {
                ready(false)
                return@TextToSpeech
            }
            t.setAudioAttributes(NAVIGATION_SPEECH)
            ready(chooseVoice(t, locale))
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let(finished) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let(finished) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let(finished) }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let(finished) }
        })
        tts = engine
    }

    /**
     * Sets the best installed voice for [locale] that needs no network ([offlineVoice]); false where the language has
     * none, and the voice stays off. Until 30 October a language without one fell back to the engine's default voice for
     * it, which may synthesise on a server.
     */
    private fun chooseVoice(t: TextToSpeech, locale: Locale): Boolean {
        val best = offlineVoice(runCatching { t.voices }.getOrNull().orEmpty(), locale) ?: return false
        return t.setVoice(best) == TextToSpeech.SUCCESS
    }

    override fun speak(text: String, flush: Boolean, pan: Float, id: String) {
        val t = tts ?: return finished(id)
        // the voice chosen speaks on the phone; should the engine have moved to one that needs the network (its settings
        // changed meanwhile), the line is dropped rather than sent to a server
        if (runCatching { t.voice }.getOrNull()?.isNetworkConnectionRequired == true) return finished(id)
        val params = Bundle()
        if (pan != 0f) params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.coerceIn(-1f, 1f))
        val r = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        if (r != TextToSpeech.SUCCESS) finished(id)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun onFinished(finished: (String) -> Unit) {
        this.finished = finished
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        /**
         * The best of [voices] for [locale]: one that synthesises on the phone and is installed there, of the locale's
         * country where there is one, then of the highest quality; null where none speaks the language on the phone.
         */
        fun offlineVoice(voices: Collection<Voice>, locale: Locale): Voice? = voices
            .filter { v ->
                v.locale.language == locale.language && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            }
            .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull()

        val NAVIGATION_SPEECH: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** Transient focus that lets music duck under the voice (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`). */
class AndroidAudioFocus(context: Context) : AudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AndroidSpeechEngine.NAVIGATION_SPEECH)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()

    override fun request(): Boolean {
        val a = audio ?: return true
        return a.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    override fun abandon() {
        audio?.abandonAudioFocusRequest(request)
    }
}

/**
 * True when sound reaches two ears separately (wired or Bluetooth headphones, a headset, hearing aids): only then does a
 * left or right pan help. Through the phone's speaker or a car it would only make one side quieter.
 */
fun headphonesOn(context: Context): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }
}

private val HEADPHONES = setOf(
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
)
