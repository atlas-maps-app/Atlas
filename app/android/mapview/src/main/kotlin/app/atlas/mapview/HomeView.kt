package app.atlas.mapview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import app.atlas.geo.LatLon
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.RegionFrame

/**
 * The Main board's view of the canal ring, the map home's first look: the Westermarkt low in the middle, the Jordaan
 * and the three canals ahead, Centraal and the IJ across the top. It is the engine's `grachten home` camera (target
 * 177 m west and 521 m north of the canal ring's origin, 52.3720 N 4.8910 E; yaw 52°, 44° below the horizon, 2300 m
 * from the target, a 36° vertical field of view, the principal point 440 dp down a 412 × 915 dp screen) in the map
 * view's terms: on such a screen with [padding], the map renders the board's SVG byte for byte.
 */
object HomeView {
    /** The canal ring's ground frame (data/regions/places.py: grachten's origin). */
    private val GRACHTEN = RegionFrame.region(LatLon(52.372, 4.891))

    /** The board's target: the Prinsengracht by the Westermarkt, the view's (-177, -521) in the canal ring's frame. */
    val TARGET = LatLon(GRACHTEN.lat(-177.0, -521.0), GRACHTEN.lon(-177.0, -521.0))

    const val BEARING = 52.0

    /** 44° below the horizon is 46° from straight down. */
    const val PITCH = 46.0

    /** The board's screen height, dp. */
    const val HEIGHT_DP = 915f

    /** How far the board's eye stands from the target, metres. */
    const val DISTANCE_M = 2300.0

    /** Where the target sits down the screen, as a share of its height: 440 of 915 dp. */
    const val PRINCIPAL_Y = 440f / 915f

    /** The zoom at which the eye stands [DISTANCE_M] from the target on a [HEIGHT_DP] tall view (≈ 15.84). */
    val ZOOM: Double = zoomForDistance(DISTANCE_M, HEIGHT_DP, TARGET.lat)

    /** The camera; pair it with [padding] so the target sits where the board has it. */
    val camera = CameraPosition(TARGET, ZOOM, BEARING, PITCH)

    /**
     * The Westerkerk's living map (amsterdam.py's main camera: target 10 m west and 30 m north of 52.3746 N 4.8840 E,
     * yaw 52°, 38° below the horizon, 430 m away): CI's home, drawn on its emulator from the canal ring CI puts there.
     */
    val westerkerk: CameraPosition by lazy {
        val f = RegionFrame.living()
        val t = LatLon(f.lat(-10.0, -30.0), f.lon(-10.0, -30.0))
        CameraPosition(t, zoomForDistance(430.0, HEIGHT_DP, t.lat), 52.0, 52.0)
    }

    /** The board's map: the live moment, and the home's own markers on it. */
    val style = MapStyle(layers = setOf(MapLayer.LABELS, MapLayer.BUILDINGS_3D, MapLayer.SHADOWS, MapLayer.TREES))

    /** Content padding that puts the principal point at [PRINCIPAL_Y] of a view [heightDp] tall (35 dp at the bottom on the board). */
    fun padding(heightDp: Float = HEIGHT_DP): PaddingValues = PaddingValues(bottom = ((1f - 2f * PRINCIPAL_Y) * heightDp).dp)

    /** The zoom whose camera stands [distanceM] from its target on a view [heightDp] tall, at the engine's 36° field of view. */
    fun zoomForDistance(distanceM: Double, heightDp: Float, lat: Double): Double = LiveCamera.zoom(lat, distanceM, heightDp.toDouble())
}
