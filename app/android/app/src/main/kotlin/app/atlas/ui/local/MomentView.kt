package app.atlas.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.places.Localise
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** A moment put into words and symbols: the tile, the two lines, the reason, and where a tap goes. */
class MomentView(
    val moment: Moment,
    val symbol: String,
    val colour: Long,
    val title: String,
    val line: String,
    val because: String,
    val dest: Dest?,
)

private val HM = DateTimeFormatter.ofPattern("H:mm")

/** "in 25 min", "in 2 h 10 min": how far ahead [at] is. */
@Composable
internal fun inLabel(at: Instant, now: Instant): String {
    val minutes = Duration.between(now, at).toMinutes().coerceAtLeast(0)
    return if (minutes < 60) stringResource(R.string.moment_in_min, minutes.toInt())
    else if (minutes % 60 == 0L) stringResource(R.string.moment_in_h, (minutes / 60).toInt())
    else stringResource(R.string.moment_in_h_min, (minutes / 60).toInt(), (minutes % 60).toInt())
}

/** The words for [m] at [now] in [zone], with [here] for distances. */
@Composable
internal fun momentView(m: Moment, now: Instant, zone: ZoneId, here: LatLon, orbitAge: Double? = null): MomentView {
    val lang = remember { Locale.getDefault().language }
    fun t(i: Instant) = HM.format(i.atZone(zone))
    return when (m) {
        is Moment.RainStarts -> MomentView(
            m, "weather.rain.2", 0xFF5A7ACA, stringResource(R.string.moment_rain_from, t(m.at)),
            if (m.lasts != null) stringResource(R.string.moment_rain_lasts, inLabel(m.at, now), m.lasts) else inLabel(m.at, now),
            stringResource(R.string.moment_because_forecast), Dest.Sky,
        )
        is Moment.RainStops -> MomentView(
            m, "weather.rain.2", 0xFF5A7ACA, stringResource(R.string.moment_rain_until, t(m.at)), stringResource(R.string.moment_raining_now),
            stringResource(R.string.moment_because_forecast), Dest.Sky,
        )
        is Moment.Sunset -> MomentView(m, "weather.sunset", 0xFFE0592A, stringResource(R.string.moment_sunset, t(m.at)), inLabel(m.at, now), stringResource(R.string.moment_because_place), Dest.Weather())
        is Moment.GoldenHour -> MomentView(m, "weather.golden-hour", 0xFFE29A12, stringResource(R.string.moment_golden, t(m.at)), stringResource(R.string.moment_golden_line), stringResource(R.string.moment_because_place), null)
        is Moment.Sunrise -> MomentView(m, "weather.sunrise", 0xFFE29A12, stringResource(R.string.moment_sunrise, t(m.at)), inLabel(m.at, now), stringResource(R.string.moment_because_place), null)
        is Moment.Closing -> {
            val name = Localise.name(m.place.tags, lang) ?: ""
            MomentView(
                m, "ui.clock", 0xFFC8322A, stringResource(R.string.moment_closes, name, t(m.at)),
                stringResource(R.string.moment_place_line, m.minutes, app.atlas.ui.kit.shortDistance(Geo.distance(here, m.place.location))),
                stringResource(R.string.moment_because_saved), Dest.Place.of(m.place),
            )
        }
        is Moment.Opening -> {
            val name = Localise.name(m.place.tags, lang) ?: ""
            MomentView(
                m, "fact.open", 0xFF1E9E5A, stringResource(R.string.moment_opens, name, t(m.at)),
                stringResource(R.string.moment_place_line_opening, m.minutes, app.atlas.ui.kit.shortDistance(Geo.distance(here, m.place.location))),
                stringResource(R.string.moment_because_saved), Dest.Place.of(m.place),
            )
        }
        is Moment.Market -> {
            val name = Localise.name(m.place.tags, lang) ?: stringResource(R.string.season_market)
            val day = DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(m.at.atZone(zone))
            val open = m.at <= now
            val hours = if (open && m.until != null) stringResource(R.string.moment_market_open_until, t(m.until))
            else if (m.until != null) "$day ${t(m.at)}–${t(m.until)}" else "$day ${t(m.at)}"
            MomentView(m, "place.market.2", 0xFF2F7A3A, name, hours, stringResource(R.string.moment_because_market), Dest.Place.of(m.place))
        }
        is Moment.Station -> {
            val minutes = ((m.pass.visibleDuration.seconds + 30) / 60).toInt().coerceAtLeast(1)
            MomentView(
                m, "sky.satellite", 0xFF14203A, stringResource(R.string.moment_station, t(m.pass.peak)),
                stringResource(R.string.moment_station_line, minutes, m.pass.maxElevationDeg.roundToInt(), compassName(m.pass.riseAzimuthDeg), compassName(m.pass.setAzimuthDeg)),
                stringResource(R.string.moment_because_orbit), Dest.Sky,
            )
        }
        is Moment.Planets -> {
            val names = m.up.take(3).map { p ->
                val n = when (p.planet) {
                    app.atlas.ui.local.astro.Planet.MERCURY -> stringResource(R.string.cosmos_planet_mercury); app.atlas.ui.local.astro.Planet.VENUS -> stringResource(R.string.cosmos_planet_venus)
                    app.atlas.ui.local.astro.Planet.MARS -> stringResource(R.string.cosmos_planet_mars); app.atlas.ui.local.astro.Planet.JUPITER -> stringResource(R.string.cosmos_planet_jupiter)
                    app.atlas.ui.local.astro.Planet.SATURN -> stringResource(R.string.cosmos_planet_saturn); else -> ""
                }
                n
            }
            MomentView(m, "weather.sun.4", 0xFF5B3FD6, stringResource(R.string.moment_planets), names.joinToString(", "), stringResource(R.string.moment_because_computed), Dest.Planets)
        }
        is Moment.MoonPhase -> MomentView(
            m, "ui.moon", 0xFF3A3A40, stringResource(if (m.full) R.string.moment_full_moon else R.string.moment_new_moon),
            stringResource(if (m.full) R.string.moment_full_moon_line else R.string.moment_new_moon_line), stringResource(R.string.moment_because_computed), Dest.Moon,
        )
    }
}
