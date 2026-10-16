package app.atlas.ui.mine

import app.atlas.providers.Forecast
import app.atlas.providers.Sky
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** What the weather means for you: one piece of advice, with the time and the number it is about. */
data class Advice(val kind: Kind, val at: Instant? = null, val until: Instant? = null, val value: Double? = null) {
    enum class Kind { RAIN_NOW, RAIN_SOON, DRY_DAY, WINDY, FROST, HOT, UV, FOG, SNOW }
}

/**
 * The rule engine of the Weather board: turns a forecast into the few things worth knowing, each from the forecast's own
 * numbers (nothing is guessed): rain coming or falling now and when it stops, wind and gusts, frost, heat, strong sun,
 * fog and snow. Most pressing first.
 */
object WeatherAdvice {
    /** Gusts at or above this, in km/h, are worth a line. */
    const val GUST_KMH = 50.0

    /** At or below this, in °C, the ground may freeze. */
    const val FROST_C = 1.0

    /** A day this warm, in °C, is worth a line. */
    const val HOT_C = 27.0

    /** The UV index at which cream and shade are advised. */
    const val UV_INDEX = 6.0

    fun of(f: Forecast, now: Instant, limit: Int = 4): List<Advice> {
        val out = ArrayList<Advice>()
        val horizon12 = now.plus(Duration.ofHours(12))
        val raining = f.rainEnds(now)
        val rainSoon = if (raining == null) f.nextRain(now)?.takeIf { it < horizon12 } else null
        when {
            raining != null -> out += Advice(Advice.Kind.RAIN_NOW, now, raining)
            rainSoon != null -> out += Advice(Advice.Kind.RAIN_SOON, rainSoon, f.rainEnds(rainSoon.plusSeconds(60)))
            f.nextRain(now) == null -> out += Advice(Advice.Kind.DRY_DAY)
        }
        val next24 = f.hours.filter { it.time >= now.minus(Duration.ofHours(1)) && it.time < now.plus(Duration.ofHours(24)) }
        next24.filter { it.windGusts.isFinite() }.maxByOrNull { it.windGusts }?.takeIf { it.windGusts >= GUST_KMH }
            ?.let { out += Advice(Advice.Kind.WINDY, it.time, null, it.windGusts) }
        val next36 = f.hours.filter { it.time >= now && it.time < now.plus(Duration.ofHours(36)) }
        next36.filter { it.temperature.isFinite() }.minByOrNull { it.temperature }?.takeIf { it.temperature <= FROST_C }
            ?.let { out += Advice(Advice.Kind.FROST, it.time, null, it.temperature) }
        next24.filter { it.sky == Sky.FOG || (it.visibility.isFinite() && it.visibility < 1_000.0) }.firstOrNull()
            ?.let { out += Advice(Advice.Kind.FOG, it.time) }
        next24.filter { it.sky in setOf(Sky.SNOW, Sky.SNOW_SHOWERS, Sky.SNOW_GRAINS) }.firstOrNull()
            ?.let { out += Advice(Advice.Kind.SNOW, it.time) }
        next24.filter { it.isDay && it.uvIndex.isFinite() }.maxByOrNull { it.uvIndex }?.takeIf { it.uvIndex >= UV_INDEX }
            ?.let { out += Advice(Advice.Kind.UV, it.time, null, it.uvIndex) }
        f.days.firstOrNull { it.date >= java.time.LocalDate.ofInstant(now, java.time.ZoneOffset.ofTotalSeconds(f.utcOffsetSeconds)) && it.temperatureMax >= HOT_C }
            ?.let { out += Advice(Advice.Kind.HOT, it.sunrise, null, it.temperatureMax) }
        return out.take(limit)
    }

    /** Wind as the board writes it: "W 18" (the direction it blows from, and km/h rounded). */
    fun wind(speedKmh: Double, fromDegrees: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val dir = names[(((fromDegrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        return "$dir ${speedKmh.roundToInt()}"
    }
}
