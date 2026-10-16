package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt.pf
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** One hour of a weather forecast, in the units Open-Meteo gives (%, mm, km/h, degrees, hPa, metres, °C). */
data class WxHour(
    val time: Instant,
    val cloudCover: Double,
    val precipitation: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
    val temperature: Double,
    val pressureMsl: Double,
    val visibility: Double,
    val code: Int?,
    /** Low cloud and humidity: the map's forecast lacks them; the engine reads but does not draw them. */
    val cloudCoverLow: Double = Double.NaN,
    val humidity: Double = Double.NaN,
)

/** Which light the engine draws: a moment the region defines by name, or now from the weather. */
sealed interface Moment {
    /** One of the engine's own moments (`THEMES`: sunny, grey, wet, night, golden, noon, and a region's own). */
    data class Named(val name: String) : Moment

    /** [at], lit by the forecast [hours] (null: none to hand, so grey by day and night after sunset). */
    data class Live(val at: Instant, val hours: List<WxHour>?) : Moment
}

/**
 * The live moment: the engine's `weather_theme` fed with a forecast in the shape of a region's `wx.json` (Open-Meteo's
 * hourly columns, local times) and the time now, so the light, the sky, the water, the rain and the shadows follow the
 * real weather and the real sun. The moment is named as the engine's own rules decide: `night` when the sun is more than
 * 2° under the horizon, `wet` when it rains, `grey` under 85% cloud or more, `sunny` otherwise.
 */
object LiveMoment {
    /** The key the live moment is kept under in the engine's `THEMES` while it renders. */
    const val KEY = "live"

    /** [hours] as `wx.json`'s `hourly` record, in [zone]'s local time. */
    fun hourly(hours: List<WxHour>, zone: ZoneId): Dict {
        val sorted = hours.sortedBy { it.time }
        fun col(f: (WxHour) -> Any?): List<Any?> = sorted.map(f)
        fun or(v: Double, other: Double) = if (v.isNaN()) other else v
        val m: Dict = LinkedHashMap()
        m["time"] = col { local(it.time, zone) }
        m["temperature_2m"] = col { or(it.temperature, 12.0) }
        m["relative_humidity_2m"] = col { or(it.humidity, 80.0) }
        m["cloud_cover"] = col { or(it.cloudCover, 100.0) }
        m["cloud_cover_low"] = col { or(it.cloudCoverLow, or(it.cloudCover, 100.0)) }
        m["precipitation"] = col { or(it.precipitation, 0.0) }
        m["rain"] = col { or(it.precipitation, 0.0) }
        m["weather_code"] = col { it.code ?: 3 }
        m["wind_speed_10m"] = col { or(it.windSpeed, 10.0) }
        // whole degrees, as Open-Meteo writes them (the engine keeps the value itself in the moment's weather)
        m["wind_direction_10m"] = col { or(it.windDirection, 225.0).let { d -> if (d == Math.rint(d) && Math.abs(d) < 1e9) d.toInt() else d } }
        m["wind_gusts_10m"] = col { or(it.windGusts, or(it.windSpeed, 10.0) * 1.8) }
        m["pressure_msl"] = col { or(it.pressureMsl, 1013.0) }
        m["visibility"] = col { or(it.visibility, 20000.0) }
        return m
    }

    /** Overcast hours around [at] with nothing falling: the stand-in when no forecast is to hand. */
    fun overcast(at: Instant): List<WxHour> {
        val start = at.minusSeconds(at.epochSecond % 3600).minusSeconds(24 * 3600L)
        return List(72) { i -> WxHour(start.plusSeconds(3600L * i), 100.0, 0.0, 10.0, 225.0, 18.0, 12.0, 1013.0, 20000.0, 3) }
    }

    /** `wx.json`'s time text for [t] in [zone]: `2026-10-01T16:00`. */
    fun local(t: Instant, zone: ZoneId): String {
        val z = ZonedDateTime.ofInstant(t, zone)
        return pf("%04d-%02d-%02dT%02d:00", z.year, z.monthValue, z.dayOfMonth, z.hour)
    }

    /**
     * Builds the live theme into the engine's `THEMES` under [KEY], for [at] in [zone] from [hours] (or [overcast]).
     * Sets the engine's weather record and clock offset (`WXD`, `TZ_H`), which the render reads: call it under the
     * engine's lock, right before rendering. Returns the moment's name by the engine's rules.
     */
    fun install(at: Instant, zone: ZoneId, hours: List<WxHour>?, daylight: Boolean): String {
        val usable = hours?.takeIf { h -> h.any { !it.time.isAfter(at) } && h.any { it.time.isAfter(at) } } ?: overcast(at)
        Amsterdam.WXD = hourly(usable, zone)
        val z = ZonedDateTime.ofInstant(at, zone)
        Amsterdam.TZ_H = Math.floorDiv(z.offset.totalSeconds, 3600)
        val date = listOf(z.year, z.monthValue, z.dayOfMonth)
        val hhmm = listOf(z.hour, z.minute)
        val th = Amsterdam.weatherTheme(KEY, date, hhmm, daylight)
        val name = nameOf(th)
        th["name"] = name
        Amsterdam.THEMES[KEY] = th
        return name
    }

    /** The engine's own name for a weather theme: night, wet, grey or sunny. */
    @Suppress("UNCHECKED_CAST")
    fun nameOf(th: Map<String, Any?>): String {
        val wx = th["wx"] as Map<String, Any?>
        return when {
            th["night"] == true -> "night"
            th["wet"] == true -> "wet"
            (wx["cloud"] as Number).toDouble() >= 0.85 -> "grey"
            else -> "sunny"
        }
    }
}
