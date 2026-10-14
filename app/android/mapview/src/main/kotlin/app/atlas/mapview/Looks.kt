package app.atlas.mapview

import app.atlas.engine.FrameStyle
import app.atlas.engine.Light
import app.atlas.engine.Look
import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong
import app.atlas.engine.Theme as EngineTheme

/** The engine look for a style, with the key scenes are cached under (the same key means the same scenes). */
class ResolvedLook(val look: Look, val key: String, val night: Boolean)

/** From the view's [MapStyle] and the moment to the engine's [Look] and [FrameStyle]. */
object Looks {
    /** Below this sun elevation (degrees) AUTO turns to the night look: civil twilight is over. */
    const val NIGHT_BELOW = -4.0

    /** The light is recomputed for steps of this many minutes and this many degrees of place, so scenes rebuild rarely. */
    const val TIME_STEP_MINUTES = 10L
    const val PLACE_STEP_DEGREES = 0.5

    /** The look for [style] at [time] over [at]. */
    fun resolve(style: MapStyle, time: Instant, at: LatLon): ResolvedLook {
        val t = quantise(time)
        val lat = floor(at.lat / PLACE_STEP_DEGREES) * PLACE_STEP_DEGREES + PLACE_STEP_DEGREES / 2
        val lon = floor(at.lon / PLACE_STEP_DEGREES) * PLACE_STEP_DEGREES + PLACE_STEP_DEGREES / 2
        val sun = Sun.position(t, lat, lon)
        val light = Light(sun.azimuthDeg, sun.altitudeDeg)
        var theme = when (style.theme) {
            Theme.AUTO -> if (sun.altitudeDeg < NIGHT_BELOW) EngineTheme.NIGHT else EngineTheme.DAY
            Theme.DAY -> EngineTheme.DAY
            Theme.NIGHT -> EngineTheme.NIGHT
            Theme.CHART -> EngineTheme.CHART
            Theme.GRAY -> EngineTheme.GRAY
        }
        if (MapLayer.SHADOWS !in style.layers && theme.shadowOpacity > 0f) theme = theme.copy(shadowOpacity = 0f)
        val look = Look(theme, light, autumn = Look.autumnOf(t))
        val l = look.light
        val key = "${theme.name}|${"%.1f".format(java.util.Locale.ROOT, l.azimuth)}|${"%.1f".format(java.util.Locale.ROOT, l.elevation)}|" +
            "${if (theme.shadowOpacity > 0f) "s" else "-"}|${"%.2f".format(java.util.Locale.ROOT, look.autumn)}"
        return ResolvedLook(look, key, theme.night)
    }

    /** The cell of the light's grid [time] and [at] fall in: equal cells resolve to equal looks. */
    fun cell(time: Instant, at: LatLon): Long {
        val t = quantise(time).toEpochMilli() / 60_000
        val la = floor(at.lat / PLACE_STEP_DEGREES).toLong() and 0x3FF
        val lo = floor(at.lon / PLACE_STEP_DEGREES).toLong() and 0x3FF
        return (t shl 20) or (la shl 10) or lo
    }

    /** [time] rounded to [TIME_STEP_MINUTES]. */
    fun quantise(time: Instant): Instant {
        val step = TIME_STEP_MINUTES * 60_000.0
        return Instant.ofEpochMilli((time.toEpochMilli() / step).roundToLong() * step.toLong())
    }

    /**
     * The per-frame choices for [style] at [zoom]: labels and symbols as its layers say (symbol sizes in dp, which the
     * engine scales by the camera's density); calm a little stronger than the engine's own ramp when [MapStyle.calm],
     * never close up.
     */
    fun frameStyle(style: MapStyle, zoom: Double): FrameStyle {
        val calm = if (style.calm) calmFor(zoom) else null
        return FrameStyle(
            calm = calm,
            labels = MapLayer.LABELS in style.layers,
            pois = MapLayer.POIS in style.layers,
            poiSize = POI_SIZE_DP + 4f,
        )
    }

    /** The calm look: the engine's ramp plus a soft wash that fades out between zoom 16.5 and 18. */
    fun calmFor(zoom: Double): Float {
        val base = Look.defaultCalm(zoom)
        val close = ((18.0 - zoom) / 1.5).coerceIn(0.0, 1.0).toFloat()
        return maxOf(base, 0.28f * close)
    }

    /** The diameter of an engine point of interest's symbol, dp. */
    const val POI_SIZE_DP = 20f
}
