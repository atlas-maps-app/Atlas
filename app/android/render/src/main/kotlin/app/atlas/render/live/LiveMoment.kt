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

    /**
     * [hours] at [zone]'s whole local hours: themselves where they stand on them, as in every zone of whole hours; in a
     * zone half or three quarters of an hour off (Kolkata, Kathmandu) each whole local hour between two of them an
     * hour apart, its numbers between theirs by the minutes, its wind's direction and weather code the nearer one's. So
     * the engine, which reads the record by the local hour, reads each hour's own weather.
     */
    fun wholeHours(hours: List<WxHour>, zone: ZoneId): List<WxHour> {
        val sorted = hours.sortedBy { it.time }
        if (sorted.all { ZonedDateTime.ofInstant(it.time, zone).let { z -> z.minute == 0 && z.second == 0 } }) return sorted
        val out = ArrayList<WxHour>(sorted.size)
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val span = b.time.epochSecond - a.time.epochSecond
            if (span <= 0 || span > 3600) continue
            // the whole local hour in (a, b]
            val z = ZonedDateTime.ofInstant(b.time, zone)
            val whole = z.minusMinutes(z.minute.toLong()).minusSeconds(z.second.toLong()).toInstant()
            if (!whole.isAfter(a.time)) continue
            val f = (whole.epochSecond - a.time.epochSecond).toDouble() / span
            fun mix(x: Double, y: Double) = x * (1 - f) + y * f
            val near = if (f < 0.5) a else b
            out += WxHour(
                whole, mix(a.cloudCover, b.cloudCover), mix(a.precipitation, b.precipitation), mix(a.windSpeed, b.windSpeed), near.windDirection,
                mix(a.windGusts, b.windGusts), mix(a.temperature, b.temperature), mix(a.pressureMsl, b.pressureMsl), mix(a.visibility, b.visibility), near.code,
                mix(a.cloudCoverLow, b.cloudCoverLow), mix(a.humidity, b.humidity),
            )
        }
        return out
    }

    /** [hours] as `wx.json`'s `hourly` record, in [zone]'s local time (at its whole hours: [wholeHours]). */
    fun hourly(hours: List<WxHour>, zone: ZoneId): Dict {
        val sorted = wholeHours(hours, zone)
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
     * Sets the engine's weather record and clock offset (`WXD`, `TZ_H` and the minutes past it, `TZ_M`, so the sun is
     * the instant's in a zone half an hour off too), which the render reads: call it under the
     * engine's lock, right before rendering. Returns the moment's name by the engine's rules.
     */
    fun install(at: Instant, zone: ZoneId, hours: List<WxHour>?, daylight: Boolean): String {
        val usable = hours?.takeIf { h -> h.any { !it.time.isAfter(at) } && h.any { it.time.isAfter(at) } } ?: overcast(at)
        var record = hourly(usable, zone)
        // the engine reads the hour's row and the next: where the forecast does not reach that far back (its first hour
        // in a zone half an hour off), the stand-in
        if (local(at, zone) !in (record["time"] as List<*>)) record = hourly(overcast(at), zone)
        Amsterdam.WXD = record
        val z = ZonedDateTime.ofInstant(at, zone)
        Amsterdam.TZ_H = Math.floorDiv(z.offset.totalSeconds, 3600)
        Amsterdam.TZ_M = Math.floorMod(z.offset.totalSeconds, 3600) / 60
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
