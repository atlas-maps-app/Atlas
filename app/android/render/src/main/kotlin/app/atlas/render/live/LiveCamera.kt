package app.atlas.render.live

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.render.py.CrMath
import app.atlas.render.py.PyMath
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * A region's ground frame: the engine's local metres around the region's origin, x east and z south, as
 * `render/amsterdam.py:22 XZ` computes them (`x = (lon - LON0) * KX`, `z = -(lat - LAT0) * KZ`). The map's overlays,
 * gestures and taps use the same frame, so they land exactly where the engine drew.
 */
data class RegionFrame(val lat0: Double, val lon0: Double, val kx: Double, val kz: Double) {
    fun x(lat: Double, lon: Double): Double = (lon - lon0) * kx
    fun z(lat: Double, lon: Double): Double = -(lat - lat0) * kz
    fun lat(x: Double, z: Double): Double = lat0 - z / kz
    fun lon(x: Double, z: Double): Double = lon0 + x / kx

    companion object {
        const val KZ = 110540.0

        /** The living map's place (regions.py's reset_place): the Westerkerk, with KX from Python's own `math.cos`. */
        fun living(): RegionFrame = RegionFrame(52.3746, 4.8840, 111320 * PyMath.cos(PyMath.radians(52.3746)), KZ)

        /** A region's place (regions.py's configure): its origin, with KX from the correctly rounded cosine. */
        fun region(origin: LatLon): RegionFrame = RegionFrame(origin.lat, origin.lon, 111320 * CrMath.cos(PyMath.radians(origin.lat)), KZ)
    }
}

/**
 * Where the map looks, in the map's terms: the target, the zoom (Web Mercator, 256-pixel tiles in dp), the bearing
 * (degrees clockwise from north), the pitch (degrees from straight down), and the view: [widthDp] × [heightDp] with
 * the principal point [cyDp] down from the top.
 */
data class LiveView(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val bearing: Double,
    val pitch: Double,
    val widthDp: Int,
    val heightDp: Int,
    val cyDp: Double = heightDp / 2.0,
) {
    val target: LatLon get() = LatLon(lat, lon)

    /** How far the engine's eye stands from the target, metres. */
    val distance: Double get() = LiveCamera.distance(lat, zoom, heightDp.toDouble())
}

/**
 * The map camera and the engine's camera are one: the engine's `Camera` with `yaw` = bearing, `pitch` = 90° − the map's
 * pitch, a 36° vertical field of view, and the eye standing back [distance] metres so that, looking straight down, a dp
 * at the target spans the zoom's Web Mercator metres (256-pixel tiles, as the map's zoom has always meant).
 */
object LiveCamera {
    const val FOV = 36.0

    /** The engine's focal length, dp: (H/2) / tan(fov/2). */
    fun focal(heightDp: Double): Double = heightDp / 2 / tan(FOV * PI / 360)

    /** The eye's distance from the target for [zoom] at [lat] on a view [heightDp] tall. */
    fun distance(lat: Double, zoom: Double, heightDp: Double): Double = focal(heightDp) * Mercator.metresPerPixel(lat, zoom)

    /** The zoom whose eye stands [dist] metres from the target: the inverse of [distance]. */
    fun zoom(lat: Double, dist: Double, heightDp: Double): Double = ln(Mercator.metresPerUnit(lat) * focal(heightDp) / (256.0 * dist)) / ln(2.0)

    /** The engine's pitch (degrees below the horizon) for the map's pitch (degrees from straight down). */
    fun enginePitch(mapPitch: Double): Double = 90.0 - mapPitch

    /** The map's bearing for an engine yaw, in 0 until 360. */
    fun bearing(yaw: Double): Double = ((yaw % 360.0) + 360.0) % 360.0
}
