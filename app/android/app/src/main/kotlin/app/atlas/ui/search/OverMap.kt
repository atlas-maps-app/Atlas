package app.atlas.ui.search

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.Follow
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapPick
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.ShownMap
import app.atlas.mapview.StageMap
import app.atlas.nav.LocalServices
import app.atlas.ui.home.HomeViewModel
import app.atlas.ui.home.mapStyleOf
import app.atlas.ui.home.revealTarget
import kotlinx.coroutines.flow.map

/**
 * The map under a screen that opens over the map (a long press's point, a place): the one on screen ([shown]) as the
 * user left it, in its look, with nothing rebuilt (the user, 1 November: "when long pressing it shouldnt open that view
 * it should stay on the map"; "Search should take you to a place on the map and not show that view either"). Only where
 * none is shown (a link opened the app, or Android ended it while the screen was open) is [state] its own, at the Map
 * home's view of a link's point.
 */
@Stable
internal class OverMap(val shown: ShownMap?, val state: MapState)

@Composable
internal fun rememberOverMap(at: LatLon): OverMap {
    val stage = LocalMapStage.current
    return remember(at) {
        val shown = stage?.shown()
        OverMap(shown, shown?.state ?: MapState(CameraPosition(at, POINT_ZOOM, 0.0, HomeViewModel.DEFAULT_PITCH)))
    }
}

/**
 * [over]'s map with this screen's [overlays] and long presses: the shown map's look, padding and camera's listener, or,
 * for its own, the user's look ([mapStyleOf]) with [ownPadding].
 */
@Composable
internal fun OverStageMap(
    over: OverMap,
    overlays: MapOverlays,
    ownPadding: PaddingValues,
    onLongPress: (LatLon) -> Unit,
    onLongPressBuilding: (MapPick) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    hostModifier: Modifier = Modifier,
) {
    val shown = over.shown
    if (shown != null) {
        StageMap(
            state = shown.state, modifier = modifier, style = shown.style, overlays = overlays,
            contentPadding = shown.contentPadding, onLongPress = onLongPress, onLongPressBuilding = onLongPressBuilding,
            onCameraMove = shown::cameraMoved, debug = shown.debug, units = shown.units,
            labelTypeface = shown.labelTypeface, background = background, hostModifier = hostModifier,
        )
        return
    }
    val services = LocalServices.current
    val context = LocalContext.current
    val style by remember(services) { services.settings.data.map { mapStyleOf(it, services.ciTheme) } }
        .collectAsStateWithLifecycle(MapStyle(calm = true))
    val face = remember { runCatching { ResourcesCompat.getFont(context, R.font.instrument_serif_italic) }.getOrNull() }
    StageMap(
        state = over.state, modifier = modifier, style = style, overlays = overlays, contentPadding = ownPadding,
        onLongPress = onLongPress, onLongPressBuilding = onLongPressBuilding, labelTypeface = face,
        background = background, hostModifier = hostModifier,
    )
}

/**
 * Brings [at] into [band] (screen px) on [over]'s map as the screen opens ([goTarget]): a long press's point by the
 * least pan, nothing moving where it shows there already; with [go], further out than [NEAR_ZOOM] it goes there at
 * [GO_ZOOM].
 */
@Composable
internal fun BringInto(over: OverMap, at: LatLon, band: Rect, go: Boolean) {
    LaunchedEffect(over, at) {
        val map = over.state
        val to = goTarget(
            map.camera, at, band, go,
            toScreen = { p -> map.latLonToScreen(p)?.let { (x, y) -> Offset(x, y) } },
            toGround = { o -> map.screenToLatLon(o.x, o.y) },
            placing = { from, o -> map.placing(at, o.x, o.y, from) },
        ) ?: return@LaunchedEffect
        map.follow = Follow.NONE
        map.animateTo(to, if (to.zoom == map.camera.zoom) REVEAL_MS else GO_MS)
    }
}

/**
 * Where the camera goes for the ground at [place] to show inside [band] (screen px): null where it shows there already,
 * or before the view's first frame. By the least pan, the zoom, bearing and tilt kept ([revealTarget]): a long press's
 * point, or a place the view is near enough to show. With [go] (a search's result, a link, a list's place) from further
 * out than [NEAR_ZOOM] the camera goes to [GO_ZOOM], the place in the band's middle, the bearing and tilt kept
 * ([placing]: the camera given, moved so the place lands under the pixel).
 */
internal fun goTarget(
    camera: CameraPosition,
    place: LatLon,
    band: Rect,
    go: Boolean,
    toScreen: (LatLon) -> Offset?,
    toGround: (Offset) -> LatLon?,
    placing: (CameraPosition, Offset) -> CameraPosition?,
): CameraPosition? {
    if (go && camera.zoom < NEAR_ZOOM) return placing(camera.copy(zoom = GO_ZOOM), band.center)
    return revealTarget(camera.target, place, band, toScreen, toGround)?.let { camera.copy(target = it) }
}

/** Where a point or place opened over the map shows, with a sheet [half] of the screen up: [PLACE_SIDE], [PLACE_TOP], [PLACE_FOOT]. */
@Composable
internal fun BoxWithConstraintsScope.bandOver(half: Float): Rect = with(LocalDensity.current) {
    Rect(PLACE_SIDE.toPx(), (boardShift() + PLACE_TOP).toPx(), (maxWidth - PLACE_SIDE).toPx(), (maxHeight * (1f - half) - PLACE_FOOT).toPx())
}

/** How far in from the sides an opened place shows: its chosen pin's 44 px disc and a margin. */
private val PLACE_SIDE = 32.dp

/** How far down an opened place shows: under the Place board's buttons (44 px down, 48 tall) and its chosen pin (56 tall). */
private val PLACE_TOP = 156.dp

/** How far above the place's sheet (at its half stop) an opened place shows. */
private val PLACE_FOOT = 16.dp

/** The Map home's view of a link's point, where a screen over the map has none to take on. */
private const val POINT_ZOOM = 17.0

/** Nearer than this, a place the map goes to keeps the zoom: the view shows it among its streets already. */
internal const val NEAR_ZOOM = 15.5

/** The distance a place the map goes to is shown at, from further out: its building and the streets around. */
internal const val GO_ZOOM = 16.5

/** The glide that brings a point out from under its sheet, and the one that goes to a place. */
private const val REVEAL_MS = 450
private const val GO_MS = 800
