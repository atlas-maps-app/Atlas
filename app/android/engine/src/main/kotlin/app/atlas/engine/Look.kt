package app.atlas.engine

import java.time.Instant

/**
 * How the map looks: a [theme], the [light] it is lit by, the building tradition (chosen by place unless [tradition]
 * names one), how calm its colours are ([calm] 0..1, or null for the default that ramps in below zoom 15) and how far
 * the leaves have turned ([autumn] 0..0.7, see [autumnOf]). Scenes are built for one look; a different look means
 * rebuilding them.
 */
class Look(
    val theme: Theme = Theme.DAY,
    light: Light = Light.PLEASANT,
    val calm: Float? = null,
    val tradition: String? = null,
    val autumn: Float = 0f,
) {
    /** The light the scenes are shaded with: never below 8° by day, so the daylight look never goes flat. */
    val light: Light = if (theme.night) light else light.atLeast(8.0)

    val shading: Shading by lazy { Shading(theme, this.light) }

    /** The building tradition for a place. */
    fun traditionAt(lat: Double, lon: Double): Tradition =
        tradition?.let { Traditions.get(it) } ?: Traditions.forLocation(lat, lon)

    /** How calm the colours are at [zoom]: full colour close up, a little softer as you zoom out. */
    fun calmAt(zoom: Double): Float = calm ?: defaultCalm(zoom)

    fun copy(
        theme: Theme = this.theme, light: Light = this.light, calm: Float? = this.calm, tradition: String? = this.tradition,
        autumn: Float = this.autumn,
    ) = Look(theme, light, calm, tradition, autumn)

    /** True when scenes built for [other] can be drawn for this look without rebuilding. */
    fun sameScenesAs(other: Look) =
        other.theme == theme && other.light == light && other.tradition == tradition && other.autumn == autumn

    companion object {
        val DAY = Look(Theme.DAY)
        val NIGHT = Look(Theme.NIGHT)
        val CHART = Look(Theme.CHART)
        val GRAY = Look(Theme.GRAY)

        /** Daylight with the real sun at [instant] over [lat], [lon], and the leaves of that day. */
        fun at(instant: Instant, lat: Double, lon: Double, theme: Theme = Theme.DAY) =
            Look(theme, Light.at(instant, lat, lon), autumn = autumnOf(instant))

        /**
         * How far broadleaved crowns have turned on the day of [instant]: none until mid-September, then a little more
         * each day up to 0.7 (the living map's generic season; the northern hemisphere's).
         */
        fun autumnOf(instant: Instant): Float {
            val d = instant.atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val dayOfYear = (d.monthValue - 1) * 30.5 + d.dayOfMonth
            return ((dayOfYear - 258.0) / 75.0).coerceIn(0.0, 0.7).toFloat()
        }

        /** The default calm: none from zoom 15 up, easing in to 0.55 at zoom 11 and below. */
        fun defaultCalm(zoom: Double): Float {
            val t = ((15.0 - zoom) / 4.0).coerceIn(0.0, 1.0)
            return (0.55 * t * t * (3 - 2 * t)).toFloat()
        }
    }
}
