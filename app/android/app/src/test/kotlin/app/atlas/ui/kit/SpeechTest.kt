package app.atlas.ui.kit

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import app.atlas.ui.go.AndroidSpeechEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.io.File
import java.util.Locale

/**
 * What the user says and what Atlas says stay on the phone (the user, 30 October: Atlas never contacts Google, and that
 * includes a voice handed to the phone's recogniser or a network voice): voice search only through the on-device
 * recogniser, the guide's voice only with a voice installed on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun reset() = ShadowSpeechRecognizer.reset()

    @Test
    fun voiceSearchTakesTheOnDeviceRecogniserOrNone() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        assertFalse(SpeechInput.onDevice(context))
        assertNull("no on-device recognition: no recogniser at all, never the default service", SpeechInput.recogniser(context))
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        assertTrue(SpeechInput.onDevice(context))
        assertNotNull(SpeechInput.recogniser(context)?.also { it.destroy() })
    }

    @Test
    fun theGuideSpeaksOnlyWithAVoiceOnThePhone() {
        val nl = Locale("nl", "NL")
        fun voice(name: String, locale: Locale, quality: Int, network: Boolean, features: Set<String> = emptySet()) =
            Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, features)
        val network = voice("nl-network", nl, Voice.QUALITY_VERY_HIGH, network = true)
        val notInstalled = voice("nl-later", nl, Voice.QUALITY_VERY_HIGH, network = false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val local = voice("nl-local", nl, Voice.QUALITY_NORMAL, network = false)
        val belgian = voice("nl-be", Locale("nl", "BE"), Voice.QUALITY_VERY_HIGH, network = false)
        val german = voice("de-local", Locale.GERMANY, Voice.QUALITY_VERY_HIGH, network = false)
        assertEquals("the country's own voice on the phone", local, AndroidSpeechEngine.offlineVoice(listOf(network, notInstalled, belgian, german, local), nl))
        assertEquals("else the language's", belgian, AndroidSpeechEngine.offlineVoice(listOf(network, german, belgian), nl))
        assertNull("only a network voice or one not installed: none", AndroidSpeechEngine.offlineVoice(listOf(network, notInstalled, german), nl))
    }

    @Test
    fun noShippedCodeHandsTheVoiceToARecogniserOrSynthesisThatMaySendItOn() {
        val android = File(System.getProperty("atlas.repo") ?: "../..", "app/android")
        val code = listOf("app", "mapview", "guide").flatMap { m ->
            File(android, "$m/src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        }
        assertTrue("the Android sources at $android", code.size > 100)
        // the default recognition service, the recogniser app started for a result, and synthesis on a server
        val sends = listOf("createSpeechRecognizer(", "launch(speechIntent", "startActivity(speechIntent", "startActivityForResult(speechIntent", "KEY_FEATURE_NETWORK_SYNTHESIS")
        for (f in code) {
            f.readLines().forEachIndexed { i, line ->
                val c = line.trim()
                if (c.startsWith("*") || c.startsWith("//") || c.startsWith("/*")) return@forEachIndexed
                for (s in sends) assertFalse("${f.relativeTo(android)}:${i + 1}: $c", s in c)
            }
        }
    }
}
