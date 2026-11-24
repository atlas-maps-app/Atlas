package app.atlas.mapview.vk

import app.atlas.render.Amsterdam
import app.atlas.render.live.Moment
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Whether a moment is the night's, as the core's theme block will flag it ([CoreMoment.write]'s ThemeBlock.NIGHT), worked
 * out with no core and no lock, so the main thread can paint the start's paper by it before the core has the moment:
 * - a named moment: by its name, [NIGHTS]: the engine's `THEMES` flag `night` and `wet` (the weather's 21:30 of
 *   30 September, `weather_theme`'s sun under -2°). A set of names rather than the engine's dict, which Regions
 *   rebuild under the engine's lock on other threads; PaperNightTest holds it to every name the engine has;
 * - the live one: the sun more than 2° under the horizon (ThemeBlock.NIGHT_BELOW) over [lat], [lon] at the moment's
 *   minute, as the engine's `weather_theme` asks its `sun_position` (Amsterdam.weatherTheme: the local hour and minute
 *   less the zone's offset, so the UTC minute). The forecast never makes a night.
 *
 * Its cost on the main thread: one sun position, a dozen trigonometric calls and no allocation but the minute's
 * LocalDateTime (PaperNightTest measures it).
 */
internal object PaperNight {
    fun of(moment: Moment, lat: Double, lon: Double): Boolean = when (moment) {
        is Moment.Named -> moment.name in NIGHTS
        is Moment.Live -> {
            val minute = LocalDateTime.ofInstant(moment.at.truncatedTo(ChronoUnit.MINUTES), ZoneOffset.UTC)
            Amsterdam.sunPosition(minute, lat, lon).second < app.atlas.mapcore.state.ThemeBlock.NIGHT_BELOW
        }
    }

    /** The named moments the engine's themes flag as the night's (CoreMoment.write sets ThemeBlock.NIGHT for them). */
    val NIGHTS: Set<String> = setOf("night", "wet")
}
