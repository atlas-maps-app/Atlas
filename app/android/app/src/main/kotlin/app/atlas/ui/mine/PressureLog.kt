package app.atlas.ui.mine

import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * The phone's barometer over the last 48 hours, for the forecast and the brass dial of the Weather screen: one reading
 * (`seconds,hPa`) per line, older ones dropped on every write. It stays on this phone, and is off unless the setting is on.
 */
class PressureLog(private val file: File, private val keep: Duration = Duration.ofHours(48)) {
    private val lock = Any()

    /** Adds a reading of [hPa] at [time] and forgets what is older than 48 hours. */
    fun add(hPa: Double, time: Instant) = synchronized(lock) {
        if (!hPa.isFinite() || hPa < 300 || hPa > 1_100) return@synchronized
        val cutoff = time.minus(keep).epochSecond
        val kept = readLines().filter { it.first >= cutoff }.toMutableList()
        kept += time.epochSecond to hPa
        try {
            file.parentFile?.mkdirs()
            file.writeText(kept.joinToString("") { "${it.first},${"%.2f".format(java.util.Locale.ROOT, it.second)}\n" })
        } catch (_: IOException) {
        }
    }

    /**
     * Adds a reading unless one is already kept from less than [gap] before [time]. Android gives no barometer readings to
     * an app in the background, so the log also grows from the screens that read it while they are open.
     */
    fun addIfDue(hPa: Double, time: Instant, gap: Duration = Duration.ofMinutes(10)): Boolean = synchronized(lock) {
        val last = readLines().lastOrNull()
        if (last != null && Duration.between(Instant.ofEpochSecond(last.first), time) < gap && !time.isBefore(Instant.ofEpochSecond(last.first))) return false
        add(hPa, time)
        true
    }

    /** The readings kept, oldest first. */
    fun readings(): List<PressureReading> = synchronized(lock) { readLines().map { PressureReading(Instant.ofEpochSecond(it.first), it.second) } }

    /** Forgets every reading. */
    fun clear() = synchronized(lock) { file.delete(); Unit }

    private fun readLines(): List<Pair<Long, Double>> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { l ->
                val f = l.split(',')
                if (f.size < 2) null else (f[0].toLongOrNull() ?: return@mapNotNull null) to (f[1].toDoubleOrNull() ?: return@mapNotNull null)
            }.toList()
        }
    }
}

/** One barometer reading. */
data class PressureReading(val time: Instant, val hPa: Double)

/** Which way the pressure is going. */
enum class PressureTrend(val symbol: String) { RISING("↑"), STEADY("→"), FALLING("↓") }

/** What the dial shows: the needle's angle, the weather word it points at, and the trend. */
data class Barometer(val hPa: Double, val trend: PressureTrend?, val needleDeg: Double, val setHandDeg: Double?, val word: Word) {
    /** The four words on the dial, from the stormy end. */
    enum class Word { STORMY, RAIN, CHANGE, FAIR }

    companion object {
        /** The dial turns this many degrees to a hectopascal, with 1013.25 hPa straight up (the board's 1004 hPa points 34° left). */
        const val DEGREES_PER_HPA = 3.68

        /** A change of this many hPa in three hours is "rising" or "falling"; less is steady. */
        const val TREND_HPA = 1.0

        fun angle(hPa: Double): Double = ((hPa - 1013.25) * DEGREES_PER_HPA).coerceIn(-130.0, 130.0)

        /** The word the needle is nearest: STORMY, RAIN, CHANGE or FAIR sit at −105°, −60°, 0° and 60° on the dial. */
        fun wordAt(deg: Double): Word = when {
            deg < -82.5 -> Word.STORMY
            deg < -30 -> Word.RAIN
            deg < 30 -> Word.CHANGE
            else -> Word.FAIR
        }

        /**
         * The dial for the latest of [readings] at [now]. The trend compares it with the reading nearest three hours
         * earlier (at least [min] hours back); with fewer readings than that it is unknown, and [fallback] (the forecast's
         * sea-level pressure) can stand in for the needle alone.
         */
        fun of(readings: List<PressureReading>, now: Instant, fallback: Double? = null, min: Duration = Duration.ofHours(1)): Barometer? {
            val latest = readings.lastOrNull { !it.time.isAfter(now) }
            val hPa = latest?.hPa ?: fallback ?: return null
            val before = latest?.let { l ->
                readings.filter { !it.time.isAfter(l.time.minus(min)) }.minByOrNull { abs(Duration.between(it.time, l.time.minusSeconds(3 * 3600)).seconds) }
            }
            val trend = before?.let { b ->
                val d = hPa - b.hPa
                when { d <= -TREND_HPA -> PressureTrend.FALLING; d >= TREND_HPA -> PressureTrend.RISING; else -> PressureTrend.STEADY }
            }
            val needle = angle(hPa)
            return Barometer(hPa, trend, needle, before?.let { angle(it.hPa) }, wordAt(needle))
        }
    }
}
