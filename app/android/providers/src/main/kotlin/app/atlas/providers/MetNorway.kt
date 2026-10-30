package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * MET Norway's Locationforecast 2.0: the Norwegian Meteorological Institute's global forecast, no key, CC BY 4.0,
 * "Data from MET Norway". It is the weather's second source: [Weather.forecast] asks it when Open-Meteo cannot answer,
 * so the weather (and the map's moment) never hangs on one service.
 *
 * MET asks for an identifying User-Agent ([Http] sends one) and coordinates with at most four decimals. It has no
 * visibility, no quarter-hour rain and no sunrise; those stay unknown (NaN, empty, null) in what it gives.
 */
object MetNorway {
    const val ID = "met-norway"

    val INFO = ProviderInfo(
        id = ID, name = "MET Norway", licence = "CC BY 4.0", attribution = "Data from MET Norway",
        url = "https://api.met.no", onByDefault = true,
        note = "The weather's second source, asked when Open-Meteo cannot answer.",
        hosts = listOf("api.met.no"),
    )

    /** The complete forecast for [at] from [base]. */
    fun url(base: String, at: LatLon): HttpUrl = base.toHttpUrl().newBuilder().addPathSegments("weatherapi/locationforecast/2.0/complete")
        .addQueryParameter("lat", "%.4f".format(java.util.Locale.ROOT, at.lat))
        .addQueryParameter("lon", "%.4f".format(java.util.Locale.ROOT, at.lon))
        .build()

    /**
     * MET's answer as a [Forecast] for [at], fetched at [fetchedAt]: now, the next 48 hours, and the days grouped in
     * [zone] (MET does not say the place's own time zone).
     */
    fun parse(json: JsonObject, at: LatLon, fetchedAt: Instant, zone: ZoneId): Forecast {
        val steps = json.obj("properties")?.arr("timeseries").orEmpty().mapNotNull { (it as? JsonObject)?.let(::step) }
        val hourly = steps.filter { it.hourly }
        val hours = hourly.filter { !it.time.isBefore(fetchedAt.minusSeconds(3599)) }.take(48).map { it.hour() }
        val nowStep = hourly.lastOrNull { !it.time.isAfter(fetchedAt) } ?: hourly.firstOrNull()
        val now = nowStep?.let { s ->
            WeatherNow(
                time = s.time, temperature = s.temperature, feelsLike = s.feelsLike, humidity = s.humidity,
                sky = Sky.of(s.code), code = s.code, isDay = s.isDay, cloudCover = s.cloudCover,
                pressureMsl = s.pressure, surfacePressure = Double.NaN, precipitation = s.precipitation,
                windSpeed = s.windKmh, windDirection = s.windDirection, windGusts = s.gustKmh, uvIndex = s.uv,
            )
        }
        val days = steps.groupBy { it.time.atZone(zone).toLocalDate() }.toSortedMap().entries
            .filter { (date, _) -> !date.isBefore(fetchedAt.atZone(zone).toLocalDate()) }
            .take(7)
            .map { (date, list) -> day(date, list) }
        val coords = json.obj("geometry")?.arr("coordinates").orEmpty()
        return Forecast(
            location = at,
            elevation = coords.getOrNull(2)?.asDouble() ?: Double.NaN,
            timezone = zone.id,
            utcOffsetSeconds = zone.rules.getOffset(fetchedAt).totalSeconds,
            now = now,
            quarters = emptyList(),
            hours = hours,
            days = days,
            fetchedAt = fetchedAt,
        )
    }

    /** One step of MET's series: an hour (with the next hour's rain and symbol) or, further out, six hours. */
    private class Step(
        val time: Instant,
        val hourly: Boolean,
        val temperature: Double,
        val humidity: Double,
        val cloudCover: Double,
        val pressure: Double,
        val windMs: Double,
        val windDirection: Double,
        val gustMs: Double,
        val uv: Double,
        val fog: Double,
        /** Rain over the step (one hour, or six), mm. */
        val precipitation: Double,
        val probability: Double,
        val code: Int?,
        val isDay: Boolean,
        val hoursLong: Int,
        val temperatureMax: Double,
        val temperatureMin: Double,
    ) {
        val windKmh get() = windMs * 3.6
        val gustKmh get() = gustMs * 3.6
        val feelsLike get() = apparent(temperature, humidity, windMs)

        fun hour() = WeatherHour(
            time = time, temperature = temperature, feelsLike = feelsLike, precipitationProbability = probability,
            precipitation = precipitation, sky = Sky.of(code), code = code, cloudCover = cloudCover, windSpeed = windKmh,
            windDirection = windDirection, windGusts = gustKmh, uvIndex = uv, isDay = isDay, pressureMsl = pressure,
            // MET has no visibility; a fog bank is the one sign of it it gives
            visibility = if (fog >= 50.0) 1_000.0 else Double.NaN,
        )
    }

    private fun step(o: JsonObject): Step? {
        val time = o.str("time")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val data = o.obj("data") ?: return null
        val d = data.obj("instant")?.obj("details") ?: return null
        val next1 = data.obj("next_1_hours")
        val next6 = data.obj("next_6_hours")
        val next = next1 ?: next6
        val symbol = next?.obj("summary")?.str("symbol_code")
        val uv = d.dbl("ultraviolet_index_clear_sky") ?: Double.NaN
        return Step(
            time = time,
            hourly = next1 != null,
            temperature = d.dbl("air_temperature") ?: Double.NaN,
            humidity = d.dbl("relative_humidity") ?: Double.NaN,
            cloudCover = d.dbl("cloud_area_fraction") ?: Double.NaN,
            pressure = d.dbl("air_pressure_at_sea_level") ?: Double.NaN,
            windMs = d.dbl("wind_speed") ?: Double.NaN,
            windDirection = d.dbl("wind_from_direction") ?: Double.NaN,
            gustMs = d.dbl("wind_speed_of_gust") ?: Double.NaN,
            uv = uv,
            fog = d.dbl("fog_area_fraction") ?: 0.0,
            precipitation = next?.obj("details")?.dbl("precipitation_amount") ?: Double.NaN,
            probability = next?.obj("details")?.dbl("probability_of_precipitation") ?: Double.NaN,
            code = code(symbol),
            isDay = day(symbol) ?: (uv > 0.0),
            hoursLong = if (next1 != null) 1 else 6,
            temperatureMax = next6?.obj("details")?.dbl("air_temperature_max") ?: Double.NaN,
            temperatureMin = next6?.obj("details")?.dbl("air_temperature_min") ?: Double.NaN,
        )
    }

    private fun day(date: java.time.LocalDate, steps: List<Step>): WeatherDay {
        val temps = steps.map { it.temperature } + steps.map { it.temperatureMax } + steps.map { it.temperatureMin }
        val known = temps.filter { !it.isNaN() }
        val rain = steps.filter { !it.precipitation.isNaN() }
        val code = steps.mapNotNull { it.code }.maxByOrNull { severity(it) }
        // the day's wind direction: the mean of the steps' directions as vectors, weighted by speed
        val x = steps.filter { !it.windDirection.isNaN() && !it.windMs.isNaN() }.sumOf { it.windMs * sin(Math.toRadians(it.windDirection)) }
        val y = steps.filter { !it.windDirection.isNaN() && !it.windMs.isNaN() }.sumOf { it.windMs * cos(Math.toRadians(it.windDirection)) }
        return WeatherDay(
            date = date, sky = Sky.of(code), code = code,
            temperatureMax = known.maxOrNull() ?: Double.NaN,
            temperatureMin = known.minOrNull() ?: Double.NaN,
            precipitationSum = rain.sumOf { it.precipitation },
            precipitationProbabilityMax = steps.map { it.probability }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
            precipitationHours = rain.filter { it.precipitation >= 0.1 * it.hoursLong }.sumOf { it.hoursLong }.toDouble(),
            windSpeedMax = steps.map { it.windKmh }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
            windGustsMax = steps.map { it.gustKmh }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
            windDirection = if (x == 0.0 && y == 0.0) Double.NaN else (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0,
            uvIndexMax = steps.map { it.uv }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
            sunrise = null, sunset = null, daylightSeconds = Double.NaN,
        )
    }

    /** Whether a MET symbol is the day's or the night's, from its suffix; null for symbols that have none. */
    private fun day(symbol: String?): Boolean? = when {
        symbol == null -> null
        symbol.endsWith("_day") -> true
        symbol.endsWith("_night") || symbol.endsWith("_polartwilight") -> false
        else -> null
    }

    /**
     * MET's symbol as the WMO code Open-Meteo gives, so [Sky] and the map's moment read both alike. Sleet becomes rain
     * of the same strength (WMO's mixed codes have no [Sky] of their own); anything with thunder is a thunderstorm.
     */
    internal fun code(symbol: String?): Int? {
        val s = symbol?.substringBefore('_') ?: return null
        if ("thunder" in s) return 95
        val heavy = s.startsWith("heavy")
        val light = s.startsWith("light")
        val base = s.removePrefix("heavy").removePrefix("light")
        return when (base) {
            "clearsky" -> 0
            "fair" -> 1
            "partlycloudy" -> 2
            "cloudy" -> 3
            "fog" -> 45
            "rain", "sleet" -> if (heavy) 65 else if (light) 61 else 63
            "rainshowers", "sleetshowers" -> if (heavy) 82 else if (light) 80 else 81
            "snow" -> if (heavy) 75 else if (light) 71 else 73
            "snowshowers" -> if (heavy) 86 else 85
            else -> null
        }
    }

    /** How much a WMO code says about a day: its worst weather wins the day's symbol. */
    private fun severity(code: Int): Int = when (code) {
        in 95..99 -> 100 + code
        in 45..48 -> 10
        else -> code
    }

    /** The apparent temperature (the Australian Bureau of Meteorology's), °C, from air °C, humidity % and wind m/s. */
    private fun apparent(t: Double, rh: Double, windMs: Double): Double {
        if (t.isNaN() || rh.isNaN() || windMs.isNaN()) return Double.NaN
        val e = rh / 100.0 * 6.105 * exp(17.27 * t / (237.7 + t))
        return t + 0.33 * e - 0.70 * windMs - 4.00
    }
}
