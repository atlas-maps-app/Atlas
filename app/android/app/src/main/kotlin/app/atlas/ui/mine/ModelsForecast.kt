package app.atlas.ui.mine

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale

/** One weather model Open-Meteo can be asked for, with the colour the Forecast sources board gives it. */
enum class WeatherModel(val id: String, val label: String, val maker: String, val colour: Long) {
    ICON("icon_seamless", "ICON", "DWD, Germany", 0xFF2E7BFF),
    ECMWF("ecmwf_ifs025", "ECMWF", "European Centre, open data", 0xFF8E3CF7),
    GFS("gfs_seamless", "GFS", "NOAA, United States", 0xFFE8622C),
    AROME("meteofrance_seamless", "AROME", "Météo-France", 0xFF1E9E5A),
    MEPS("metno_seamless", "MEPS", "MET Norway", 0xFFB8860B),
}

/** One model's next 48 hours at a place: temperature in °C and precipitation in mm per hour (NaN where it has none). */
class ModelCurve(val model: WeatherModel, val temperature: DoubleArray, val rain: DoubleArray)

/** The models side by side for one place: the hours (epoch seconds), each model's curve, and when this was fetched. */
class ModelsForecast(val at: LatLon, val hours: LongArray, val curves: List<ModelCurve>, val fetchedAt: Instant, val kept: Boolean = false) {
    /** How many models have temperatures for most of the window. */
    val count: Int get() = curves.size

    /** The lowest and highest temperature of all models, for the chart's scale. */
    val range: Pair<Double, Double>? get() {
        val all = curves.flatMap { c -> c.temperature.filter { it.isFinite() } }
        return if (all.isEmpty()) null else all.min() to all.max()
    }

    /** At each hour, how many models forecast rain (at least [mm] in that hour). */
    fun rainAgreement(mm: Double = 0.1): IntArray = IntArray(hours.size) { h -> curves.count { c -> c.rain.getOrNull(h)?.let { it.isFinite() && it >= mm } == true } }

    /** The hour in which the most models are at or below freezing, and how many: null when none are. */
    fun frost(): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (h in hours.indices) {
            val n = curves.count { c -> c.temperature.getOrNull(h)?.let { it.isFinite() && it <= 0.0 } == true }
            if (n > 0 && (best == null || n > best.second)) best = h to n
        }
        return best
    }

    /** How many models are at or below freezing at some hour of the window. */
    fun frostModels(): Int = curves.count { c -> c.temperature.any { it.isFinite() && it <= 0.0 } }

    /** The spread of the models at hour [h] in °C (highest minus lowest). */
    fun spread(h: Int): Double? {
        val v = curves.mapNotNull { it.temperature.getOrNull(h)?.takeIf { t -> t.isFinite() } }
        return if (v.size < 2) null else v.max() - v.min()
    }

    companion object {
        /** The request for the models at [at]: hourly temperature and precipitation for 48 hours. */
        fun url(at: LatLon, base: String = "https://api.open-meteo.com"): String = base + "/v1/forecast?" + listOf(
            "latitude=" + String.format(Locale.ROOT, "%.4f", at.lat), "longitude=" + String.format(Locale.ROOT, "%.4f", at.lon),
            "hourly=temperature_2m,precipitation", "models=" + WeatherModel.entries.joinToString(",") { it.id },
            "forecast_hours=48", "timeformat=unixtime", "timezone=auto",
        ).joinToString("&")

        /** Reads Open-Meteo's answer with one column per model (`temperature_2m_icon_seamless`); a model with no data is left out. */
        fun parse(text: String, at: LatLon, fetchedAt: Instant, kept: Boolean = false): ModelsForecast? {
            val hourly = (Json.parseToJsonElement(text) as? JsonObject)?.get("hourly") as? JsonObject ?: return null
            val times = (hourly["time"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.toLongArray() ?: return null
            fun column(key: String): DoubleArray? = (hourly[key] as? JsonArray)?.let { a -> DoubleArray(times.size) { i -> a.getOrNull(i)?.let { e -> (e as? JsonPrimitive)?.doubleOrNull } ?: Double.NaN } }
            val curves = WeatherModel.entries.mapNotNull { m ->
                val t = column("temperature_2m_${m.id}") ?: column("temperature_2m")?.takeIf { WeatherModel.entries.size == 1 } ?: return@mapNotNull null
                if (t.count { it.isFinite() } < times.size / 2) return@mapNotNull null
                ModelCurve(m, t, column("precipitation_${m.id}") ?: DoubleArray(times.size) { Double.NaN })
            }
            return if (curves.isEmpty()) null else ModelsForecast(at, times, curves, fetchedAt, kept)
        }
    }
}

/** Fetches the models and keeps the last answer per place on the phone, so the screen has something with no signal. */
class ModelsFetcher(private val http: Http, private val dir: File?, private val clock: () -> Instant = Instant::now) {
    /** The models at [at]: fetched, or (offline) the last kept within [keptWithinM]; null when there is neither. */
    suspend fun get(at: LatLon, keptWithinM: Double = 25_000.0): ModelsForecast? = withContext(Dispatchers.IO) {
        try {
            val res = http.execute(http.request(ModelsForecast.url(at)).build())
            if (!res.ok) return@withContext kept(at, keptWithinM)
            val now = clock()
            val f = ModelsForecast.parse(res.text, at, now) ?: return@withContext kept(at, keptWithinM)
            runCatching { keep(at, res.text, now) }
            f
        } catch (e: IOException) {
            kept(at, keptWithinM)
        }
    }

    private fun file(at: LatLon) = File(dir, "models_${String.format(Locale.ROOT, "%.2f_%.2f", at.lat, at.lon)}.json")

    private fun keep(at: LatLon, text: String, now: Instant) {
        val d = dir ?: return
        d.mkdirs()
        file(at).writeText("${now.epochSecond}\n$text")
        d.listFiles { f -> f.name.startsWith("models_") }?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
    }

    fun kept(at: LatLon, withinM: Double = 25_000.0): ModelsForecast? {
        val d = dir ?: return null
        val files = d.listFiles { f -> f.name.startsWith("models_") && f.name.endsWith(".json") } ?: return null
        val oldest = clock().minusSeconds(3 * 24 * 3600L)
        return files.mapNotNull { f ->
            val parts = f.name.removePrefix("models_").removeSuffix(".json").split('_')
            val p = LatLon(parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null, parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null)
            Triple(f, p, Geo.distance(at, p))
        }.filter { it.third <= withinM }.sortedBy { it.third }.firstNotNullOfOrNull { (f, p, _) ->
            runCatching {
                val text = f.readText()
                val fetched = Instant.ofEpochSecond(text.substringBefore('\n').toLong())
                if (fetched.isBefore(oldest)) null else ModelsForecast.parse(text.substringAfter('\n'), p, fetched, kept = true)
            }.getOrNull()
        }
    }
}
