package app.atlas.mapview

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import java.time.Instant

/**
 * Where the map looks. [zoom] is the Web Mercator zoom of 256-pixel tiles (fractional), [bearing] degrees clockwise
 * from north, [pitch] degrees from straight down (0) towards the horizon (up to about 70).
 */
@Immutable
data class CameraPosition(
    val target: LatLon,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
)

/** How the camera follows the phone. */
enum class Follow { NONE, POSITION, HEADING, COURSE }

/** The map's state, hoisted so screens can move the camera and read what is visible. */
@Stable
class MapState(initial: CameraPosition) {
    /** Setting it jumps the camera; [animateTo] glides. */
    var camera: CameraPosition by mutableStateOf(initial)

    var follow: Follow by mutableStateOf(Follow.NONE)

    /** The ground the view shows, updated by the view after each frame; null before the first one. */
    var visibleBounds: BBox? by mutableStateOf(null)
        internal set

    /** The moment the light and shadows are drawn for; null means now. */
    var time: Instant? by mutableStateOf(null)

    /** True while scenes for the view are still loading or building. */
    var loading: Boolean by mutableStateOf(false)
        internal set

    internal var animator: (suspend (CameraPosition, Int) -> Unit)? = null
    internal var fitter: (suspend (BBox, Int) -> Unit)? = null
    internal var projector: Projector? = null

    /** The view drawing this state, while it is on screen. */
    internal var controller: MapController? = null

    suspend fun animateTo(target: CameraPosition, durationMs: Int = 600) {
        animator?.invoke(target, durationMs) ?: run { camera = target }
    }

    /** Moves the camera so [box] fills the view inside its content padding, plus [paddingPx]. */
    suspend fun fit(box: BBox, paddingPx: Int = 48) {
        fitter?.invoke(box, paddingPx) ?: run { camera = camera.copy(target = box.center) }
    }

    /** Screen pixel to ground, or null off the ground (above the horizon) or before the first frame. */
    fun screenToLatLon(x: Float, y: Float): LatLon? = projector?.toLatLon(x, y)

    /** Ground to screen pixel, or null when behind the camera. */
    fun latLonToScreen(p: LatLon): Pair<Float, Float>? = projector?.toScreen(p)

    /** What the view registers so [screenToLatLon] and [latLonToScreen] work. */
    interface Projector {
        fun toLatLon(x: Float, y: Float): LatLon?
        fun toScreen(p: LatLon): Pair<Float, Float>?
    }
}
