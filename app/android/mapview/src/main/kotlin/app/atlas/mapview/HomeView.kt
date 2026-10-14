package app.atlas.mapview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * The Main board's view of the canal ring, the map home's first look: the Westermarkt low in the middle, the Jordaan
 * and the three canals ahead, Centraal and the IJ across the top. It is the Python engine's `grachten home` camera
 * (target 177 m west and 521 m north of 52.3720 N 4.8910 E, yaw 52°, 44° below the horizon, 2300 m from the target,
 * a 36° vertical field of view, the principal point 440 dp down a 412 × 915 dp screen) in the map view's terms.
 */
object HomeView {
    /** The board's target: the Prinsengracht by the Westermarkt. */
    val TARGET = LatLon(52.37671, 4.88840)

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
     * The board's map: the real daylight (or night), labels, 3D buildings, shadows and trees, and no points of interest
     * from the tiles; the home's own markers stand for places.
     */
    val style = MapStyle(layers = setOf(MapLayer.LABELS, MapLayer.BUILDINGS_3D, MapLayer.SHADOWS, MapLayer.TREES))

    /** Content padding that puts the principal point at [PRINCIPAL_Y] of a view [heightDp] tall (35 dp at the bottom on the board). */
    fun padding(heightDp: Float = HEIGHT_DP): PaddingValues = PaddingValues(bottom = ((1f - 2f * PRINCIPAL_Y) * heightDp).dp)

    /**
     * The zoom whose camera stands [distanceM] from its target on a view [heightDp] tall at the map view's 36° field of
     * view: the camera stands back so that, looking straight down, a dp at the target spans the zoom's Mercator metres.
     */
    fun zoomForDistance(distanceM: Double, heightDp: Float, lat: Double): Double {
        val focalDp = heightDp / 2.0 / tan(FOV_DEG * PI / 360)
        val metresPerDp = distanceM / focalDp
        return ln(Mercator.metresPerUnit(lat) / (256.0 * metresPerDp)) / ln(2.0)
    }

    private const val FOV_DEG = 36.0
}
