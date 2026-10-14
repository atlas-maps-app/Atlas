package app.atlas.ui.kit

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Speech to text for a mic button, without Google Play services: the phone's own recogniser through
 * `RecognizerIntent` (offline preferred), else an in-app `SpeechRecognizer` (on-device when the phone has one), which
 * asks for the microphone only then. Returns the function the button calls; [onText] gets what was said,
 * [onUnavailable] runs when the phone cannot recognise speech at all.
 */
@Composable
fun rememberSpeechInput(onText: (String) -> Unit, onUnavailable: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val text by rememberUpdatedState(onText)
    val unavailable by rememberUpdatedState(onUnavailable)
    val listener = remember { InAppRecogniser(context.applicationContext) }
    DisposableEffect(listener) { onDispose { listener.destroy() } }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(text)
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listener.start(text, unavailable) else unavailable()
    }
    return remember {
        {
            try {
                system.launch(speechIntent())
            } catch (e: ActivityNotFoundException) {
                when {
                    !SpeechRecognizer.isRecognitionAvailable(context) -> unavailable()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                        listener.start(text, unavailable)
                    else -> mic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

/** The recogniser request: free speech in the phone's language, offline when it can. */
fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/** A one-shot in-app recogniser, on-device when the phone offers one. */
private class InAppRecogniser(private val context: Context) {
    private var recogniser: SpeechRecognizer? = null

    fun start(onText: (String) -> Unit, onUnavailable: () -> Unit) {
        destroy()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recogniser = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
                destroy()
            }

            override fun onError(error: Int) {
                // Nothing heard is no failure (the user said nothing); a recogniser that cannot work now (no language,
                // no network for an online one, no microphone) hands over to the keyboard.
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
