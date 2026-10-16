package app.atlas.ui.local

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/** Measures how loud it is here, in decibels: a reading of the phone's microphone, not a calibrated instrument. */
interface SoundMeter {
    /** Listens for about [millis] and returns the level in dB, or null when the microphone is not available. */
    suspend fun measure(millis: Long = 1_500): Double?
}

/**
 * The phone's microphone as a sound meter. The level is the root-mean-square of what it hears, taken as decibels relative to
 * full scale, plus 90: about what phones read as dB SPL. It is a guide to "quiet or loud", good to a few decibels at best.
 */
class MicSoundMeter : SoundMeter {
    @SuppressLint("MissingPermission")
    override suspend fun measure(millis: Long): Double? = withContext(Dispatchers.IO) {
        val rate = 44_100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext null
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        } catch (e: SecurityException) {
            return@withContext null
        } catch (e: IllegalArgumentException) {
            return@withContext null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return@withContext null }
        try {
            // The microphone may be taken by a call or another app: startRecording then throws or never gives samples.
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
            val buffer = ShortArray(min)
            var sum = 0.0
            var count = 0L
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) break
                for (i in 0 until n) { val v = buffer[i].toDouble(); sum += v * v }
                count += n
            }
            record.stop()
            level(sum, count)
        } catch (e: IllegalStateException) {
            null
        } finally {
            record.release()
        }
    }

    companion object {
        /** The level in dB from the sum of squared 16-bit samples: 0 dBFS reads as 90 dB. */
        fun level(sumSquares: Double, count: Long): Double? {
            if (count <= 0) return null
            val rms = sqrt(sumSquares / count)
            if (rms < 1.0) return 25.0
            return (20 * log10(rms / 32768.0) + 90.0).coerceIn(25.0, 110.0)
        }
    }
}
