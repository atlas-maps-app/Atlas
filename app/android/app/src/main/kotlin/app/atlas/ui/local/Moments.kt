package app.atlas.ui.local

import app.atlas.geo.LatLon
import app.atlas.geo.Moon
import app.atlas.geo.Sun
import app.atlas.model.Place
import app.atlas.places.OpeningHours
import app.atlas.providers.Forecast
import app.atlas.providers.WeatherDay
import app.atlas.ui.local.astro.IssPass
import app.atlas.ui.local.astro.PlanetNight
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What is coming up near you, as data: the screens put it into words (and symbols). Each has the moment it happens
 * ([at]) and a [relevance] for sorting among the ones that happen at about the same time.
 */
sealed interface Moment {
    val at: Instant
    val relevance: Int

    /** Rain starts in [minutes] and lasts about [lasts] minutes (null: not known, or it stays wet). */
    data class RainStarts(override val at: Instant, val minutes: Int, val lasts: Int?) : Moment { override val relevance get() = 90 }

    /** Rain is falling now and stops at [at] (null stops: it is wet to the end of the forecast). */
    data class RainStops(override val at: Instant, val stops: Boolean) : Moment { override val relevance get() = 95 }

    data class Sunset(override val at: Instant) : Moment { override val relevance get() = 50 }
    data class GoldenHour(override val at: Instant) : Moment { override val relevance get() = 40 }
    data class Sunrise(override val at: Instant) : Moment { override val relevance get() = 30 }

    /** A saved place closes in [minutes]. */
    data class Closing(val place: Place, override val at: Instant, val minutes: Int) : Moment { override val relevance get() = 80 }

    /** A saved place opens in [minutes]. */
    data class Opening(val place: Place, override val at: Instant, val minutes: Int) : Moment { override val relevance get() = 60 }

    /** A market is open from [at] to [until], today or on [at]'s day. */
    data class Market(val place: Place, override val at: Instant, val until: Instant?, val distanceM: Double) : Moment { override val relevance get() = 45 }

    /** The space station passes over you. */
    data class Station(val pass: IssPass) : Moment {
        override val at: Instant get() = pass.peak
        override val relevance get() = 70
    }

    /** Planets that are up after dark. */
    data class Planets(override val at: Instant, val up: List<PlanetNight>) : Moment { override val relevance get() = 35 }

    /** A full or new Moon [at] that night. */
    data class MoonPhase(override val at: Instant, val full: Boolean) : Moment { override val relevance get() = 20 }
}

/** What goes into the moments: all of it known by the phone, none of it a guess. */
class MomentInputs(
    val now: Instant,
    val zone: ZoneId,
    val here: LatLon,
    val forecast: Forecast?,
    val saved: List<Place>,
    val markets: List<Found>,
    val station: IssPass?,
    val planets: List<PlanetNight>,
)

/** Builds the moments that are worth a line: those that happen in the next day (markets: this week), soonest first. */
object Moments {
    /** Closing or opening within this many minutes is worth saying. */
    const val SOON_MINUTES = 90L

    fun build(i: MomentInputs): List<Moment> {
        val out = ArrayList<Moment>()
        val horizon = i.now.plus(Duration.ofHours(24))
        rain(i)?.let { out += it }
        val today = LocalDate.ofInstant(i.now, i.zone)
        val sun = Sun.times(today, i.here, i.zone)
        val tomorrow = Sun.times(today.plusDays(1), i.here, i.zone)
        fun next(a: java.time.ZonedDateTime?, b: java.time.ZonedDateTime?): Instant? =
            listOfNotNull(a?.toInstant(), b?.toInstant()).firstOrNull { it.isAfter(i.now) }
        next(sun.goldenHourEveningStart, tomorrow.goldenHourEveningStart)?.takeIf { it.isBefore(horizon) }?.let { out += Moment.GoldenHour(it) }
        next(sun.sunset, tomorrow.sunset)?.takeIf { it.isBefore(horizon) }?.let { out += Moment.Sunset(it) }
        next(sun.sunrise, tomorrow.sunrise)?.takeIf { it.isBefore(horizon) }?.let { out += Moment.Sunrise(it) }
        out += places(i)
        out += markets(i)
        i.station?.takeIf { it.visible && it.peak.isAfter(i.now) && it.peak.isBefore(horizon) }?.let { out += Moment.Station(it) }
        val up = i.planets.filter { it.view != app.atlas.ui.local.astro.NightView.NOT_UP }
        if (up.isNotEmpty()) next(sun.civilDusk, tomorrow.civilDusk)?.let { out += Moment.Planets(it, up) }
        moon(i)?.let { out += it }
        return out.sortedWith(compareBy<Moment> { it.at }.thenByDescending { it.relevance })
    }

    private fun rain(i: MomentInputs): Moment? {
        // A forecast that does not reach now (a kept one, days old) says nothing about the rain: no line, rather than "dry".
        val f = i.forecast?.takeIf { it.hourAt(i.now) != null } ?: return null
        val ends = f.rainEnds(i.now)
        if (ends != null) return Moment.RainStops(ends, stops = true)
        val start = f.nextRain(i.now) ?: return null
        val minutes = Duration.between(i.now, start).toMinutes().toInt().coerceAtLeast(0)
        if (minutes > 12 * 60) return null
        val stop = f.rainEnds(start.plusSeconds(60))
        return Moment.RainStarts(start, minutes, stop?.let { Duration.between(start, it).toMinutes().toInt() })
    }

    private fun places(i: MomentInputs): List<Moment> {
        val local = LocalDateTime.ofInstant(i.now, i.zone)
        val out = ArrayList<Moment>()
        for (p in i.saved) {
            val hours = OpeningHours.parseOrNull(p.tags["opening_hours"]) ?: continue
            val st = hours.status(local)
            if (st.unknown) continue
            val change = st.changesAt ?: continue
            val minutes = Duration.between(local, change).toMinutes()
            if (minutes < 0 || minutes > SOON_MINUTES) continue
            val at = change.atZone(i.zone).toInstant()
            if (st.isOpen && !st.openEnd && st.nextChangeKind == app.atlas.places.ChangeKind.CLOSES) out += Moment.Closing(p, at, minutes.toInt())
            else if (!st.isOpen && st.nextChangeKind == app.atlas.places.ChangeKind.OPENS) out += Moment.Opening(p, at, minutes.toInt())
        }
        return out.distinctBy { (it as? Moment.Closing)?.place?.key ?: (it as Moment.Opening).place.key }.take(3)
    }

    private fun markets(i: MomentInputs): List<Moment> {
        val out = ArrayList<Moment>()
        val local = LocalDateTime.ofInstant(i.now, i.zone)
        for (m in i.markets.take(8)) {
            val hours = OpeningHours.parseOrNull(m.place.tags["opening_hours"]) ?: continue
            // The first stretch of opening within a week: now (until it closes) or the next opening.
            var from = local
            repeat(3) {
                val st = hours.status(from)
                if (st.unknown) return@repeat
                val change = st.changesAt ?: return@repeat
                if (change.isAfter(local.plusDays(7))) return@repeat
                if (st.isOpen) {
                    out += Moment.Market(m.place, i.now, change.atZone(i.zone).toInstant(), m.distanceM)
                    return@repeat
                }
                val close = hours.status(change).changesAt
                out += Moment.Market(m.place, change.atZone(i.zone).toInstant(), close?.atZone(i.zone)?.toInstant(), m.distanceM)
                return@repeat
            }
        }
        // One market a day is plenty; the nearest ones first.
        return out.distinctBy { (it as Moment.Market).place.key }.sortedBy { (it as Moment.Market).at }.take(2)
    }

    private fun moon(i: MomentInputs): Moment? {
        val night = i.now.plus(Duration.ofHours(12))
        val p = Moon.phase(night)
        return when {
            p.illumination >= 0.985 -> Moment.MoonPhase(night, full = true)
            p.illumination <= 0.015 -> Moment.MoonPhase(night, full = false)
            else -> null
        }
    }

    // ---- the week ----------------------------------------------------------------------------------------------------

    /** A day of the week strip and the one-line note under its hand-drawn circle. */
    class Day(val day: WeatherDay, val note: DayNote)

    /** What a day's forecast says in a few words. */
    enum class DayNote { DRY_WARM, DRY, SHOWERS, WET, STORM, SNOW, COLD }

    /** The day's note: how wet it is first, then how cold. */
    fun note(d: WeatherDay): DayNote = when {
        d.sky == app.atlas.providers.Sky.THUNDERSTORM || d.sky == app.atlas.providers.Sky.THUNDERSTORM_HAIL -> DayNote.STORM
        d.sky.wet && d.temperatureMax <= 1.0 -> DayNote.SNOW
        d.precipitationProbabilityMax >= 70 || d.precipitationSum >= 5.0 -> DayNote.WET
        d.precipitationProbabilityMax >= 40 -> DayNote.SHOWERS
        d.temperatureMax <= 5.0 -> DayNote.COLD
        d.temperatureMax >= 18.0 -> DayNote.DRY_WARM
        else -> DayNote.DRY
    }

    /** The index of the best day to be outside: dry first, then mild; the day the board circles by hand. */
    fun bestDay(days: List<WeatherDay>): Int = days.indices.maxByOrNull { idx ->
        val d = days[idx]
        val comfort = -kotlin.math.abs(d.temperatureMax - 21.0)
        -d.precipitationProbabilityMax * 1.0 - d.precipitationSum * 4 + comfort * 0.8 - d.windSpeedMax * 0.3
    } ?: 0
}
