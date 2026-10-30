package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** The sky as a WMO weather code says it, with the symbol the screens draw. */
enum class Sky(val symbol: String) {
    CLEAR("clear"), MAINLY_CLEAR("mainly-clear"), PARTLY_CLOUDY("partly-cloudy"), OVERCAST("overcast"), FOG("fog"),
    DRIZZLE("drizzle"), FREEZING_DRIZZLE("freezing-drizzle"), RAIN("rain"), FREEZING_RAIN("freezing-rain"), SNOW("snow"),
    SNOW_GRAINS("snow-grains"), SHOWERS("showers"), SNOW_SHOWERS("snow-showers"), THUNDERSTORM("thunderstorm"),
    THUNDERSTORM_HAIL("thunderstorm-hail"), UNKNOWN("unknown");

    val wet: Boolean get() = this in setOf(DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, SNOW, SNOW_GRAINS, SHOWERS, SNOW_SHOWERS, THUNDERSTORM, THUNDERSTORM_HAIL)

    companion object {
        /** WMO code table 4677 as Open-Meteo uses it. */
        fun of(code: Int?): Sky = when (code) {
            0 -> CLEAR
            1 -> MAINLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61, 63, 65 -> RAIN
            66, 67 -> FREEZING_RAIN
            71, 73, 75 -> SNOW
            77 -> SNOW_GRAINS
            80, 81, 82 -> SHOWERS
            85, 86 -> SNOW_SHOWERS
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> UNKNOWN
        }
    }
}

/** The weather now. Temperatures in °C, wind in km/h, pressure in hPa, precipitation in mm; NaN when not given. */
data class WeatherNow(
    val time: Instant,
    val temperature: Double,
    val feelsLike: Double,
    val humidity: Double,
    val sky: Sky,
    val code: Int?,
    val isDay: Boolean,
    val cloudCover: Double,
    val pressureMsl: Double,
    val surfacePressure: Double,
    val precipitation: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
    val uvIndex: Double,
)

/** One hour of forecast. */
data class WeatherHour(
    val time: Instant,
    val temperature: Double,
    val feelsLike: Double,
    val precipitationProbability: Double,
    val precipitation: Double,
    val sky: Sky,
    val code: Int?,
    val cloudCover: Double,
    val windSpeed: Double,
    val windDirection: Double,
    val windGusts: Double,
    val uvIndex: Double,
    val isDay: Boolean,
    val pressureMsl: Double,
    val visibility: Double,
)

/** One day of forecast, in the place's own time zone. */
data class WeatherDay(
    val date: java.time.LocalDate,
    val sky: Sky,
    val code: Int?,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val precipitationSum: Double,
    val precipitationProbabilityMax: Double,
    val precipitationHours: Double,
    val windSpeedMax: Double,
    val windGustsMax: Double,
    val windDirection: Double,
    val uvIndexMax: Double,
    val sunrise: Instant?,
    val sunset: Instant?,
    val daylightSeconds: Double,
)

/** Precipitation in one quarter hour, for "rain at 18:40". */
data class Precipitation15(val time: Instant, val mm: Double)

/**
 * A forecast for one place: now, the next four hours in quarters (for "rain at 18:40"), 48 hours, and 7 days.
 * [fetchedAt] says when it came; [kept] is true when it was read back from the phone because there was no signal.
 */
data class Forecast(
    val location: LatLon,
    val elevation: Double,
    val timezone: String,
    val utcOffsetSeconds: Int,
    val now: WeatherNow?,
    val quarters: List<Precipitation15>,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
    val fetchedAt: Instant,
    val kept: Boolean = false,
) {
    /** When rain (at least [mm] in a quarter or an hour) first comes after [from], or null if none is forecast. */
    fun nextRain(from: Instant, mm: Double = 0.1): Instant? {
        quarters.firstOrNull { !it.time.isBefore(from) && it.mm >= mm }?.let { return it.time }
        val quartersEnd = quarters.lastOrNull()?.time?.plusSeconds(900)
        return hours.firstOrNull { !it.time.isBefore(quartersEnd ?: from) && !it.time.isBefore(from.minusSeconds(3599)) && it.precipitation >= mm }?.time
    }

    /** When the rain that is falling at [from] stops, or null if it is dry then or does not stop within the forecast. */
    fun rainEnds(from: Instant, mm: Double = 0.1): Instant? {
        val wetNow = quarters.firstOrNull { !it.time.isAfter(from) && it.time.plusSeconds(900).isAfter(from) }?.let { it.mm >= mm }
            ?: hours.firstOrNull { !it.time.isAfter(from) && it.time.plusSeconds(3600).isAfter(from) }?.let { it.precipitation >= mm }
            ?: return null
        if (!wetNow) return null
        quarters.firstOrNull { it.time.isAfter(from) && it.mm < mm }?.let { return it.time }
        return hours.firstOrNull { it.time.isAfter(from) && it.precipitation < mm }?.time
    }

    /** The hour that contains [t], or null outside the forecast. */
    fun hourAt(t: Instant): WeatherHour? = hours.lastOrNull { !it.time.isAfter(t) }?.takeIf { java.time.Duration.between(it.time, t).seconds < 3600 }
}

/** Sea state now and by the hour, for boats: waves in metres, periods in seconds, currents in km/h, water in °C. */
data class MarineForecast(
    val location: LatLon,
    val now: SeaState?,
    val hours: List<SeaState>,
    val fetchedAt: Instant,
    /** True when it was read back from the phone because there was no signal or no answer. */
    val kept: Boolean = false,
)

data class SeaState(
    val time: Instant,
    val waveHeight: Double,
    val waveDirection: Double,
    val wavePeriod: Double,
    val windWaveHeight: Double,
    val swellHeight: Double,
    val swellDirection: Double,
    val swellPeriod: Double,
    val currentSpeed: Double,
    val currentDirection: Double,
    val waterTemperature: Double,
    val seaLevel: Double,
)

/**
 * Open-Meteo: forecasts from the national weather services' models, no key, CC BY 4.0, "Weather data by Open-Meteo.com".
 * The free API is for non-commercial use; with a commercial key (Settings) Atlas switches to Open-Meteo's customer host.
 *
 * Every forecast is kept in [cacheDir], so with no signal [forecast] returns the last one for the nearest place
 * (marked [Forecast.kept]) instead of failing: "Weather with no signal".
 */
class Weather(
    private val http: Http,
    private val cacheDir: File? = null,
    private val keys: Keys = Keys.NONE,
    private val base: String = "https://api.open-meteo.com",
    private val marineBase: String = "https://marine-api.open-meteo.com",
    private val customerBase: String = "https://customer-api.open-meteo.com",
    private val customerMarineBase: String = "https://customer-marine-api.open-meteo.com",
    /** MET Norway, the second source ([MetNorway]). */
    private val metBase: String = "https://api.met.no",
    /** The zone MET's days are grouped in: MET does not say the place's own. */
    private val zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    private fun key(): String? = keys.key(ID)?.takeIf { it.isNotBlank() }

    /**
     * The forecast for [at]: fetched from Open-Meteo, or when it cannot answer from MET Norway, or with no connection
     * the kept one within [keptWithinM] (marked kept). Throws when there is none of them.
     */
    suspend fun forecast(at: LatLon, keptWithinM: Double = 25_000.0): Forecast {
        val res = try {
            http.execute(http.request(forecastUrl(at)).build())
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return second(at) ?: kept(at, keptWithinM) ?: throw e
        }
        // A 400 is our mistake (Open-Meteo says why), other failures the server's; either way MET Norway answers instead,
        // failing that the kept forecast stands in, and only where neither does is the failure shown, a 400's reason with
        // it. Until 30 October a 400 (a key Open-Meteo no longer takes, for one) showed no forecast at all.
        if (!res.ok) {
            val why = if (res.code == 400) {
                ProviderException(ID, "Open-Meteo refused the request: " + (runCatching { res.json().asObject()?.str("reason") }.getOrNull() ?: res.text.take(200)))
            } else {
                HttpException(res.code, res.url, res.text.take(300))
            }
            return second(at) ?: kept(at, keptWithinM) ?: throw why
        }
        val now = clock()
        // an answer that is not Open-Meteo's forecast (a Wi-Fi's sign-in page answers 200 too) fails as the server would
        val forecast = runCatching { res.json().asObject()?.let { parseForecast(it, now) } }.getOrNull()
            ?: return second(at) ?: kept(at, keptWithinM) ?: throw ProviderException(ID, "Open-Meteo answered something unexpected: ${res.text.take(120)}")
        // A full or read-only disk loses only the offline copy, never the forecast that came.
        try {
            keep(at, res.text, now)
        } catch (_: IOException) {
        }
        return forecast
    }

    /** MET Norway's forecast for [at], for when Open-Meteo cannot answer; null when MET cannot either. */
    private suspend fun second(at: LatLon): Forecast? {
        val res = try {
            http.execute(http.request(MetNorway.url(metBase, at)).build())
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return null
        }
        if (!res.ok) return null
        val json = runCatching { res.json().asObject() }.getOrNull() ?: return null
        return runCatching { MetNorway.parse(json, at, clock(), zone()) }.getOrNull()?.takeIf { it.hours.isNotEmpty() }
    }

    /**
     * The forecast kept on the phone nearest [at] (within [withinM]), or null. One fetched more than [maxAge] ago has
     * run out (a forecast covers seven days) and is not offered.
     */
    suspend fun kept(at: LatLon, withinM: Double = 25_000.0, maxAge: Duration = 7.days): Forecast? =
        keptNearest(FORECAST, at, withinM, maxAge) { json, fetched -> parseForecast(json, fetched).copy(kept = true) }

    /**
     * The sea's forecast kept on the phone nearest [at] (within [withinM]), as [marine] fetched it, or null. Its hours
     * run for two days, so one fetched more than [maxAge] ago is not offered; the sea now is its hour of now.
     */
    suspend fun keptMarine(at: LatLon, withinM: Double = 25_000.0, maxAge: Duration = 2.days): MarineForecast? =
        keptNearest(MARINE, at, withinM, maxAge) { json, fetched ->
            parseMarine(json, fetched)?.let { f -> f.copy(now = f.hours.lastOrNull { !it.time.isAfter(clock()) } ?: f.now, kept = true) }
        }

    /** The answer kept under [prefix] nearest [at], within [withinM] and [maxAge], as [read] makes it of its JSON. */
    private suspend fun <T> keptNearest(prefix: String, at: LatLon, withinM: Double, maxAge: Duration, read: (JsonObject, Instant) -> T?): T? =
        withContext(Dispatchers.IO) {
            val dir = cacheDir?.let { File(it, "weather") } ?: return@withContext null
            val files = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") } ?: return@withContext null
            val oldest = clock().minusMillis(maxAge.inWholeMilliseconds)
            files.mapNotNull { f -> placeOf(f.name, prefix)?.let { f to Geo.distance(at, it) } }.filter { it.second <= withinM }.sortedBy { it.second }
                .firstNotNullOfOrNull { (file, _) ->
                    runCatching {
                        val lines = file.readText()
                        val fetched = Instant.ofEpochSecond(lines.substringBefore('\n').toLong())
                        if (fetched.isBefore(oldest)) null else read(ProviderJson.parseToJsonElement(lines.substringAfter('\n')).asObject()!!, fetched)
                    }.getOrNull()
                }
        }

    /**
     * Waves, swell, currents and water temperature at sea; null on land or inland, where the marine models have no data.
     * Each answer is kept on the phone, so with no connection (or no answer) the kept one nearest stands in ([keptMarine],
     * marked kept); throws when there is neither.
     */
    suspend fun marine(at: LatLon): MarineForecast? {
        val b = (if (key() != null) customerMarineBase else marineBase).toHttpUrl().newBuilder().addPathSegments("v1/marine")
            .addQueryParameter("latitude", c(at.lat)).addQueryParameter("longitude", c(at.lon))
            .addQueryParameter("current", MARINE_FIELDS.joinToString(",") { it.first })
            .addQueryParameter("hourly", MARINE_FIELDS.joinToString(",") { it.first } + ",swell_wave_direction,swell_wave_period")
            .addQueryParameter("forecast_days", "7").addQueryParameter("forecast_hours", "48")
            .addQueryParameter("timezone", "auto").addQueryParameter("timeformat", "unixtime")
        key()?.let { b.addQueryParameter("apikey", it) }
        val res = try {
            http.execute(http.request(b.build()).build())
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return keptMarine(at) ?: throw e
        }
        if (!res.ok) return keptMarine(at) ?: throw HttpException(res.code, res.url, res.text.take(300))
        val json = res.json().asObject() ?: return null
        val now = clock()
        val forecast = parseMarine(json, now) ?: return null
        runCatching { keep(at, res.text, now, MARINE) }
        return forecast
    }

    internal fun forecastUrl(at: LatLon): HttpUrl {
        val b = (if (key() != null) customerBase else base).toHttpUrl().newBuilder().addPathSegments("v1/forecast")
            .addQueryParameter("latitude", c(at.lat)).addQueryParameter("longitude", c(at.lon))
            .addQueryParameter("current", CURRENT.joinToString(","))
            .addQueryParameter("minutely_15", "precipitation")
            .addQueryParameter("hourly", HOURLY.joinToString(","))
            .addQueryParameter("daily", DAILY.joinToString(","))
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .addQueryParameter("forecast_hours", "48")
            .addQueryParameter("forecast_minutely_15", "16")
            .addQueryParameter("wind_speed_unit", "kmh")
            .addQueryParameter("timeformat", "unixtime")
        key()?.let { b.addQueryParameter("apikey", it) }
        return b.build()
    }

    private suspend fun keep(at: LatLon, text: String, now: Instant, prefix: String = FORECAST) {
        val dir = cacheDir?.let { File(it, "weather") } ?: return
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            File(dir, "$prefix${c2(at.lat)}_${c2(at.lon)}.json").writeText("${now.epochSecond}\n$text")
            // Keep the eight most recent places: home, work, and where you went.
            dir.listFiles { f -> f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }
    }

    companion object {
        const val ID = "open-meteo"
        private const val KEEP = 8
        private const val FORECAST = "forecast_"
        private const val MARINE = "marine_"

        val INFO = ProviderInfo(
            id = ID, name = "Open-Meteo", licence = "CC BY 4.0", attribution = "Weather data by Open-Meteo.com",
            url = "https://open-meteo.com", keyNeed = KeyNeed.OPTIONAL, onByDefault = true,
            note = "Free for non-commercial use, which Atlas is; a commercial Open-Meteo key can be added.",
            hosts = listOf("api.open-meteo.com", "marine-api.open-meteo.com", "customer-api.open-meteo.com", "customer-marine-api.open-meteo.com"),
        )

        val CURRENT = listOf(
            "temperature_2m", "apparent_temperature", "relative_humidity_2m", "is_day", "weather_code", "cloud_cover", "pressure_msl",
            "surface_pressure", "precipitation", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "uv_index",
        )
        val HOURLY = listOf(
            "temperature_2m", "apparent_temperature", "precipitation_probability", "precipitation", "weather_code", "cloud_cover",
            "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "uv_index", "is_day", "pressure_msl", "visibility",
        )
        val DAILY = listOf(
            "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_sum", "precipitation_probability_max",
            "precipitation_hours", "wind_speed_10m_max", "wind_gusts_10m_max", "wind_direction_10m_dominant", "uv_index_max",
            "sunrise", "sunset", "daylight_duration",
        )
        private val MARINE_FIELDS = listOf(
            "wave_height" to 0, "wave_direction" to 1, "wave_period" to 2, "wind_wave_height" to 3, "swell_wave_height" to 4,
            "ocean_current_velocity" to 5, "ocean_current_direction" to 6, "sea_surface_temperature" to 7, "sea_level_height_msl" to 8,
        )

        private fun c(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        private fun c2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

        private fun placeOf(name: String, prefix: String): LatLon? {
            val parts = name.removePrefix(prefix).removeSuffix(".json").split('_')
            if (parts.size != 2) return null
            return LatLon(parts[0].toDoubleOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
        }

        /** A whole column of numbers; nulls become NaN. */
        private fun JsonObject.column(key: String): DoubleArray {
            val a = this[key] as? JsonArray ?: return DoubleArray(0)
            return DoubleArray(a.size) { a[it].asDouble() ?: Double.NaN }
        }

        private fun DoubleArray.at(i: Int): Double = if (i < size) this[i] else Double.NaN

        private fun code(v: Double): Int? = if (v.isNaN()) null else v.toInt()

        /** Parses Open-Meteo's forecast answer (asked with `timeformat=unixtime`). */
        fun parseForecast(json: JsonObject, fetchedAt: Instant): Forecast {
            val cur = json.obj("current")?.let { o ->
                WeatherNow(
                    time = Instant.ofEpochSecond(o.long("time") ?: fetchedAt.epochSecond),
                    temperature = o.dbl("temperature_2m") ?: Double.NaN,
                    feelsLike = o.dbl("apparent_temperature") ?: Double.NaN,
                    humidity = o.dbl("relative_humidity_2m") ?: Double.NaN,
                    sky = Sky.of(o.int("weather_code")), code = o.int("weather_code"),
                    isDay = o.int("is_day") == 1,
                    cloudCover = o.dbl("cloud_cover") ?: Double.NaN,
                    pressureMsl = o.dbl("pressure_msl") ?: Double.NaN,
                    surfacePressure = o.dbl("surface_pressure") ?: Double.NaN,
                    precipitation = o.dbl("precipitation") ?: Double.NaN,
                    windSpeed = o.dbl("wind_speed_10m") ?: Double.NaN,
                    windDirection = o.dbl("wind_direction_10m") ?: Double.NaN,
                    windGusts = o.dbl("wind_gusts_10m") ?: Double.NaN,
                    uvIndex = o.dbl("uv_index") ?: Double.NaN,
                )
            }
            val quarters = json.obj("minutely_15")?.let { m ->
                val t = m.column("time")
                val p = m.column("precipitation")
                List(t.size) { Precipitation15(Instant.ofEpochSecond(t[it].toLong()), p.at(it)) }
            } ?: emptyList()
            val hours = json.obj("hourly")?.let { h ->
                val t = h.column("time")
                val temp = h.column("temperature_2m"); val feels = h.column("apparent_temperature")
                val prob = h.column("precipitation_probability"); val prec = h.column("precipitation"); val wc = h.column("weather_code")
                val cloud = h.column("cloud_cover"); val ws = h.column("wind_speed_10m"); val wd = h.column("wind_direction_10m")
                val wg = h.column("wind_gusts_10m"); val uv = h.column("uv_index"); val day = h.column("is_day")
                val pr = h.column("pressure_msl"); val vis = h.column("visibility")
                List(t.size) { i ->
                    WeatherHour(
                        Instant.ofEpochSecond(t[i].toLong()), temp.at(i), feels.at(i), prob.at(i), prec.at(i), Sky.of(code(wc.at(i))), code(wc.at(i)),
                        cloud.at(i), ws.at(i), wd.at(i), wg.at(i), uv.at(i), day.at(i) == 1.0, pr.at(i), vis.at(i),
                    )
                }
            } ?: emptyList()
            val offset = json.int("utc_offset_seconds") ?: 0
            val days = json.obj("daily")?.let { d ->
                val t = d.column("time")
                val wc = d.column("weather_code"); val tmax = d.column("temperature_2m_max"); val tmin = d.column("temperature_2m_min")
                val psum = d.column("precipitation_sum"); val pmax = d.column("precipitation_probability_max"); val ph = d.column("precipitation_hours")
                val wmax = d.column("wind_speed_10m_max"); val gmax = d.column("wind_gusts_10m_max"); val wdir = d.column("wind_direction_10m_dominant")
                val uv = d.column("uv_index_max"); val rise = d.column("sunrise"); val set = d.column("sunset"); val light = d.column("daylight_duration")
                List(t.size) { i ->
                    // Daily times are local midnight as a Unix time. The offset is today's, so across a clock change it is
                    // an hour out; landing at local noon keeps the date right either way.
                    val date = Instant.ofEpochSecond(t[i].toLong() + offset + 43_200).atOffset(ZoneOffset.UTC).toLocalDate()
                    WeatherDay(
                        date, Sky.of(code(wc.at(i))), code(wc.at(i)), tmax.at(i), tmin.at(i), psum.at(i), pmax.at(i), ph.at(i), wmax.at(i), gmax.at(i), wdir.at(i), uv.at(i),
                        rise.at(i).takeIf { !it.isNaN() }?.let { Instant.ofEpochSecond(it.toLong()) },
                        set.at(i).takeIf { !it.isNaN() }?.let { Instant.ofEpochSecond(it.toLong()) },
                        light.at(i),
                    )
                }
            } ?: emptyList()
            return Forecast(
                location = LatLon(json.dbl("latitude") ?: Double.NaN, json.dbl("longitude") ?: Double.NaN),
                elevation = json.dbl("elevation") ?: Double.NaN,
                timezone = json.str("timezone") ?: "GMT",
                utcOffsetSeconds = offset,
                now = cur, quarters = quarters, hours = hours, days = days, fetchedAt = fetchedAt,
            )
        }

        /** Parses Open-Meteo's marine answer; null when the point has no sea (every wave height null). */
        fun parseMarine(json: JsonObject, fetchedAt: Instant): MarineForecast? {
            fun state(time: Instant, get: (String) -> Double) = SeaState(
                time, get("wave_height"), get("wave_direction"), get("wave_period"), get("wind_wave_height"), get("swell_wave_height"),
                get("swell_wave_direction"), get("swell_wave_period"), get("ocean_current_velocity"), get("ocean_current_direction"),
                get("sea_surface_temperature"), get("sea_level_height_msl"),
            )
            val now = json.obj("current")?.let { o -> state(Instant.ofEpochSecond(o.long("time") ?: fetchedAt.epochSecond)) { o.dbl(it) ?: Double.NaN } }
            val hours = json.obj("hourly")?.let { h ->
                val t = h.column("time")
                val cols = HashMap<String, DoubleArray>()
                List(t.size) { i -> state(Instant.ofEpochSecond(t[i].toLong())) { k -> cols.getOrPut(k) { h.column(k) }.at(i) } }
            } ?: emptyList()
            if ((now == null || now.waveHeight.isNaN()) && hours.all { it.waveHeight.isNaN() }) return null
            return MarineForecast(LatLon(json.dbl("latitude") ?: Double.NaN, json.dbl("longitude") ?: Double.NaN), now?.takeUnless { it.waveHeight.isNaN() }, hours, fetchedAt)
        }
    }
}
