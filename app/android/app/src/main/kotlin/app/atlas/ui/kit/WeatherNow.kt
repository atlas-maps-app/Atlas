package app.atlas.ui.kit

import app.atlas.providers.Forecast
import app.atlas.providers.Sky
import java.time.Duration
import java.time.Instant

/** The weather to show for now: the sky, the temperature, whether it is day, and whether it came from a kept forecast. */
data class CurrentWeather(val sky: Sky, val temperature: Double, val isDay: Boolean, val kept: Boolean) {
    /** The symbol for this sky, by day or night. */
    val symbol: String get() = skySymbol(sky, isDay)

    companion object {
        private val FRESH = Duration.ofMinutes(60)

        /**
         * The weather at [now]: the forecast's current conditions while they are under an hour old, else the forecast
         * hour that holds [now] (a forecast kept offline still says what the sky should be doing); null when the
         * forecast does not reach [now] or has no temperature for it, rather than showing a stale sky or "NaN°".
         */
        fun of(forecast: Forecast?, now: Instant): CurrentWeather? {
            val f = forecast ?: return null
            f.now?.takeIf { Duration.between(it.time, now).abs() <= FRESH && it.temperature.isFinite() }
                ?.let { return CurrentWeather(it.sky, it.temperature, it.isDay, f.kept) }
            val h = f.hourAt(now)?.takeIf { it.temperature.isFinite() } ?: return null
            return CurrentWeather(h.sky, h.temperature, h.isDay, f.kept)
        }
    }
}

/** The weather symbol (Lucide) for a sky, by day or night. */
fun skySymbol(sky: Sky, day: Boolean): String = when (sky) {
    Sky.CLEAR -> if (day) "ui-sun" else "ui-moon"
    Sky.MAINLY_CLEAR, Sky.PARTLY_CLOUDY -> if (day) "ui-cloud-sun" else "ui-cloud-moon"
    Sky.OVERCAST -> "ui-cloud"
    Sky.FOG -> "ui-cloud-fog"
    Sky.DRIZZLE, Sky.FREEZING_DRIZZLE -> "ui-cloud-drizzle"
    Sky.RAIN, Sky.FREEZING_RAIN, Sky.SHOWERS -> "ui-cloud-rain"
    Sky.SNOW, Sky.SNOW_GRAINS, Sky.SNOW_SHOWERS -> "ui-cloud-snow"
    Sky.THUNDERSTORM, Sky.THUNDERSTORM_HAIL -> "ui-cloud-lightning"
    Sky.UNKNOWN -> "ui-cloud"
}
