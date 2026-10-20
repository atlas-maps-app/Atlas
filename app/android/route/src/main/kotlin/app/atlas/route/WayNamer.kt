package app.atlas.route

import app.atlas.geo.LatLon

/**
 * Names the street at a point, travelling on [bearing] (degrees). BRouter's data has no names or refs,
 * so the app answers from its own map data; null when it does not know. It is called on the router's background threads,
 * possibly for several routes at once, so it must be thread-safe.
 */
fun interface WayNamer {
    fun name(at: LatLon, bearing: Double): String?

    /**
     * The namer for one route, told first every point it will be asked about, so it can fetch what it lacks in one go
     * (the app's: one request for the points no map data on the phone covers). By default, this namer itself.
     */
    fun forPoints(points: List<LatLon>): WayNamer = this
}
