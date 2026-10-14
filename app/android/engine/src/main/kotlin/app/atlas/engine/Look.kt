package app.atlas.engine

import java.time.Instant

/**
 * How the map looks: a [theme], the [light] it is lit by, the building tradition (chosen by place unless [tradition]
 * names one) and how calm its colours are ([calm] 0..1, or null for the default that ramps in below zoom 15).
 * Scenes are built for one look; a different look means rebuilding them.
 */
class Look(
    val theme: Theme = Theme.DAY,
    light: Light = Light.PLEASANT,
    val calm: Float? = null,
    val tradition: String? = null,
) {
    /** The light the scenes are shaded with: never below 8° by day, so the daylight look never goes flat. */
    val light: Light = if (theme.night) light else light.atLeast(8.0)

    val shading: Shading by lazy { Shading(theme, this.light) }

    /** The building tradition for a place. */
    fun traditionAt(lat: Double, lon: Double): Tradition =
        tradition?.let { Traditions.get(it) } ?: Traditions.forLocation(lat, lon)

    /** How calm the colours are at [zoom]: full colour close up, a little softer as you zoom out. */
    fun calmAt(zoom: Double): Float = calm ?: defaultCalm(zoom)

    fun copy(theme: Theme = this.theme, light: Light = this.light, calm: Float? = this.calm, tradition: String? = this.tradition) =
        Look(theme, light, calm, tradition)

    /** True when scenes built for [other] can be drawn for this look without rebuilding. */
    fun sameScenesAs(other: Look) = other.theme == theme && other.light == light && other.tradition == tradition

    companion object {
        val DAY = Look(Theme.DAY)
        val NIGHT = Look(Theme.NIGHT)
        val CHART = Look(Theme.CHART)
        val GRAY = Look(Theme.GRAY)

        /** Daylight with the real sun at [instant] over [lat], [lon]. */
        fun at(instant: Instant, lat: Double, lon: Double, theme: Theme = Theme.DAY) = Look(theme, Light.at(instant, lat, lon))

        /** The default calm: none from zoom 15 up, easing in to 0.55 at zoom 11 and below. */
        fun defaultCalm(zoom: Double): Float {
            val t = ((15.0 - zoom) / 4.0).coerceIn(0.0, 1.0)
            return (0.55 * t * t * (3 - 2 * t)).toFloat()
        }
    }
}
