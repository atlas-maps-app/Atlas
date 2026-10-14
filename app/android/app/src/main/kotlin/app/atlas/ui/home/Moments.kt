package app.atlas.ui.home

import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.providers.Forecast
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** What a moment is about. */
enum class MomentKind(val symbol: String) {
    RAIN("ui-cloud-rain"),
    DRY("ui-sun"),
    SUNSET("ui-sunset"),
    GOLDEN_HOUR("ui-sunset"),
    UV("ui-sun"),
    WIND("ui-wind"),
}

/** Something worth knowing in the next hours, at [at], with a [value] for UV and gusts. */
data class Moment(val kind: MomentKind, val at: Instant?, val value: Int? = null)

/**
 * The Map home's suggestions ("✦ 3"): rain coming or stopping, sunset and golden hour, a high UV index, strong gusts.
 * Only what the forecast and the sun say; nothing invented. Nearest first.
 */
object Moments {
    private val RAIN_AHEAD = Duration.ofHours(2)
    private val DRY_AHEAD = Duration.ofHours(3)
    private val SUN_AHEAD = Duration.ofHours(3)
    private val FRESH = Duration.ofMinutes(60)
    private const val UV_HIGH = 6.0
    private const val GUSTS_STRONG = 50.0

    fun of(forecast: Forecast?, at: LatLon, now: Instant, zone: ZoneId): List<Moment> {
        val out = ArrayList<Moment>()
        if (forecast != null && Duration.between(forecast.fetchedAt, now).toHours() < 12) {
            val ends = forecast.rainEnds(now)
            if (ends != null) {
                if (Duration.between(now, ends) <= DRY_AHEAD) out += Moment(MomentKind.DRY, ends)
            } else {
                forecast.nextRain(now)?.takeIf { it.isAfter(now) && Duration.between(now, it) <= RAIN_AHEAD }?.let { out += Moment(MomentKind.RAIN, it) }
            }
            uvNow(forecast, now)?.takeIf { it >= UV_HIGH }?.let { out += Moment(MomentKind.UV, null, it.roundToInt()) }
            forecast.hours.firstOrNull { !it.time.isBefore(now.minusSeconds(3599)) && it.time.isBefore(now.plus(SUN_AHEAD)) && it.windGusts >= GUSTS_STRONG }
                ?.let { out += Moment(MomentKind.WIND, maxOf(it.time, now), it.windGusts.roundToInt()) }
        }
        val date = now.atZone(zone).toLocalDate()
        val times = Sun.times(date, at.lat, at.lon, zone)
        times.goldenHourEveningStart?.toInstant()?.takeIf { it.isAfter(now) && Duration.between(now, it) <= SUN_AHEAD }
            ?.let { out += Moment(MomentKind.GOLDEN_HOUR, it) }
        times.sunset?.toInstant()?.takeIf { it.isAfter(now) && Duration.between(now, it) <= SUN_AHEAD }
            ?.let { out += Moment(MomentKind.SUNSET, it) }
        return out.sortedBy { it.at ?: now }
    }

    /**
     * The UV index at [now], by day only: the current conditions while under an hour old, else the forecast hour that
     * holds [now]; null when the forecast says nothing for now (a noon reading is no answer at five).
     */
    private fun uvNow(f: Forecast, now: Instant): Double? {
        f.now?.takeIf { Duration.between(it.time, now).abs() <= FRESH }?.let { return if (it.isDay) it.uvIndex else null }
        val h = f.hourAt(now) ?: return null
        return if (h.isDay) h.uvIndex else null
    }
}
