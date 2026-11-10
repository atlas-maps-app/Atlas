package app.atlas.search

import app.atlas.providers.Forecast
import app.atlas.providers.Sky
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** One hour ahead as what is out of doors weighs it: the air, the rain and its chance, the sky, the gusts. */
data class HourAhead(
    val at: LocalDateTime,
    val tempC: Double,
    val rainMm: Double,
    /** The chance of rain, 0..100. */
    val chance: Double,
    val sky: Sky,
    val gustKmh: Double,
    val isDay: Boolean,
) {
    val storm: Boolean get() = sky == Sky.THUNDERSTORM || sky == Sky.THUNDERSTORM_HAIL || gustKmh >= Outlook.STORM_GUST_KMH
    val wet: Boolean get() = rainMm >= Outlook.WET_MM || (sky.wet && chance >= Outlook.LIKELY_PERCENT)
    val heavy: Boolean get() = rainMm >= Outlook.HEAVY_MM
}

/** What the outdoors should know is coming: a storm, heavy rain, rain, or, while it rains, when it stops. */
enum class WarningKind { STORM, HEAVY_RAIN, RAIN, DRY }

data class Warning(val kind: WarningKind, val at: LocalDateTime)

/**
 * The hours ahead out of doors (the user, 3 November: "If you're going to swim outdoors, you might want to know about
 * a storm or heavy rain coming up later today"): the hours of the forecast from now, when rain, heavy rain or a storm
 * comes, while it rains when it stops, and when the sun sets. Read from the forecast the Map home keeps.
 */
data class Outlook(
    val from: LocalDateTime,
    val hours: List<HourAhead>,
    val wetNow: Boolean,
    /** Dry now: when rain comes (to the quarter hour within the next four), null when the hours ahead stay dry. */
    val rainAt: LocalDateTime?,
    /** Raining now: when it stops, null when it does not within the hours ahead. */
    val dryAt: LocalDateTime?,
    val heavyAt: LocalDateTime?,
    val stormAt: LocalDateTime?,
    val sunset: LocalTime?,
) {
    /**
     * The one thing to know before going out until [until] (the end of the hours ahead when null): a storm first,
     * then heavy rain, then rain; while it rains, when it stops; null when nothing is coming.
     */
    fun warning(until: LocalDateTime? = null): Warning? {
        fun inTime(t: LocalDateTime?) = t != null && (until == null || !t.isAfter(until))
        return when {
            inTime(stormAt) -> Warning(WarningKind.STORM, stormAt!!)
            inTime(heavyAt) -> Warning(WarningKind.HEAVY_RAIN, heavyAt!!)
            wetNow -> dryAt?.let { Warning(WarningKind.DRY, it) }
            inTime(rainAt) -> Warning(WarningKind.RAIN, rainAt!!)
            else -> null
        }
    }

    /** The warmest of the hours ahead by day, for a lake on a cool morning that warms by the afternoon. */
    val warmest: HourAhead? get() = hours.filter { it.isDay }.maxByOrNull { it.tempC }

    companion object {
        /** How many hours ahead it reads. */
        const val SPAN = 12

        /** An hour with this much (mm) is wet; with [HEAVY_MM] heavy. */
        const val WET_MM = 0.3
        const val HEAVY_MM = 2.5

        /** A quarter hour with this much (mm) is wet, as the forecast's own rain times read it. */
        private const val QUARTER_MM = 0.1

        /** A wet sky counts as rain from this chance on. */
        const val LIKELY_PERCENT = 50.0

        /** Gusts from this speed on are a storm for what is out of doors. */
        const val STORM_GUST_KMH = 75.0

        /** The hours ahead of [now] in [f], or null when the forecast does not reach them. */
        fun of(f: Forecast, now: ZonedDateTime, span: Int = SPAN): Outlook? {
            val zone = now.zone
            val start = now.toInstant()
            val first = f.hourAt(start) ?: f.hours.firstOrNull { it.time.isAfter(start) } ?: return null
            val hours = f.hours.dropWhile { it.time.isBefore(first.time) }.take(span).map { h ->
                HourAhead(
                    LocalDateTime.ofInstant(h.time, zone), h.temperature, h.precipitation.nanToZero(), h.precipitationProbability.nanToZero(),
                    h.sky, h.windGusts.nanToZero(), h.isDay,
                )
            }
            if (hours.isEmpty()) return null
            fun local(i: java.time.Instant?) = i?.let { LocalDateTime.ofInstant(it, zone) }
            val nowLocal = now.toLocalDateTime()
            // the quarter hour of now says whether it rains now, as the Map home's chip reads it, else the hour
            val wetNow = f.quarters.firstOrNull { !it.time.isAfter(start) && it.time.plusSeconds(900).isAfter(start) }?.let { it.mm >= QUARTER_MM }
                ?: hours.first().wet
            val end = hours.last().at.plusHours(1)
            val rainAt = if (wetNow) null else local(f.nextRain(start, QUARTER_MM))?.takeIf { it.isBefore(end) } ?: hours.firstOrNull { it.wet }?.at
            val dryAt = if (!wetNow) null else (local(f.rainEnds(start, QUARTER_MM)) ?: hours.firstOrNull { !it.wet }?.at)?.takeIf { it.isBefore(end) }
            // what is already on counts from now
            fun from(t: LocalDateTime?) = t?.let { if (it.isBefore(nowLocal)) nowLocal else it }
            val heavyAt = from(hours.firstOrNull { it.heavy }?.at)
            val stormAt = from(hours.firstOrNull { it.storm }?.at)
            val sunset = f.days.firstOrNull { it.date == now.toLocalDate() }?.sunset?.let { LocalDateTime.ofInstant(it, zone).toLocalTime() }
            return Outlook(nowLocal, hours, wetNow, from(rainAt), dryAt, heavyAt, stormAt, sunset)
        }

        private fun Double.nanToZero() = if (isNaN()) 0.0 else this
    }
}
