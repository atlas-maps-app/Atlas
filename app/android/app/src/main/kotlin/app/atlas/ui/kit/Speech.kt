package app.atlas.ui.kit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.atlas.R
import java.util.Locale

/**
 * Speech to text for a mic button, on the phone only: the phone's on-device recogniser
 * (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12 and later), which asks for the microphone only then.
 * What the user says never goes to a server: Atlas never contacts Google, and that includes a voice handed to a
 * recogniser that may send it on (the user, 30 October). Until then the mic started the phone's recogniser app (Google's
 * on most phones) and, failing that, its default recognition service. Where the phone has no on-device recognition, the
 * mic says that voice search needs it ([R.string.kit_mic_unavailable]) and [onUnavailable] runs; [onText] gets what
 * was said.
 */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit, onUnavailable: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val text by rememberUpdatedState(onText)
    val fallBack by rememberUpdatedState(onUnavailable)
    val unavailable = remember(context) {
        {
            Toast.makeText(context, R.string.kit_mic_unavailable, Toast.LENGTH_LONG).show()
            fallBack()
        }
    }
    val listener = remember { InAppRecogniser(context.applicationContext) }
    DisposableEffect(listener) { onDispose { listener.destroy() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listener.start(text, unavailable) else fallBack()
    }
    return remember {
        {
            when {
                !SpeechInput.onDevice(context) -> unavailable()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                    listener.start(text, unavailable)
                else -> mic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/** Which recogniser voice search may use: the phone's on-device one, or none. */
object SpeechInput {
    /** True where the phone recognises speech on the device itself (Android 12 and later, with its recogniser installed). */
    fun onDevice(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** The on-device recogniser, or null where the phone has none: never the default service, which may send the voice on. */
    fun recogniser(context: Context): SpeechRecognizer? =
        if (Build.VERSION.SDK_INT >= 31 && onDevice(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
}

/** The recogniser request: free speech in the phone's language, on the device. */
fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/** A one-shot in-app recogniser, on the device only. */
private class InAppRecogniser(private val context: Context) {
    private var recogniser: SpeechRecognizer? = null

    fun start(onText: (String) -> Unit, onUnavailable: () -> Unit) {
        destroy()
        val r = SpeechInput.recogniser(context) ?: return onUnavailable()
        recogniser = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
                destroy()
            }

            override fun onError(error: Int) {
                // Nothing heard is no failure (the user said nothing); a recogniser that cannot work now (the language
                // not on the phone, no microphone) says so, and the caller hands over to the keyboard.
                if (error in CANNOT_RECOGNISE) onUnavailable()
                destroy()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(speechIntent())
    }

    fun destroy() {
        recogniser?.destroy()
        recogniser = null
    }

    private companion object {
        val CANNOT_RECOGNISE = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        )
    }
}
