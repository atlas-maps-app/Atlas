package app.atlas.ui.search

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.Follow
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapPick
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.ShownMap
import app.atlas.mapview.StageMap
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.ui.home.HomeViewModel
import app.atlas.ui.home.mapStyleOf
import app.atlas.ui.home.revealTarget
import kotlinx.coroutines.flow.map

/**
 * The map under a screen that opens over the map (a long press's point, a place, a search's or a list's places): the one
 * on screen ([shown]) as the user left it, in its look, with nothing rebuilt (the user, 1 November: "when long pressing
 * it shouldnt open that view it should stay on the map"; "Search should take you to a place on the map and not show
 * that view either"; "There is too much re loading the map"). Only where none is shown (a link opened the app, or
 * Android ended it while the screen was open) is [state] its own, at the view the screen's own map had.
 */
@Stable
internal class OverMap(val shown: ShownMap?, val state: MapState)

/** [OverMap] for a point or place at [at]: its own map, where none is shown, at the Map home's view of a link's point. */
@Composable
internal fun rememberOverMap(at: LatLon): OverMap = rememberOverMap(at) { CameraPosition(at, POINT_ZOOM, 0.0, HomeViewModel.DEFAULT_PITCH) }

/** [OverMap], asked again when [key] changes; its own map, where none is shown, at [own]. */
@Composable
internal fun rememberOverMap(key: Any?, own: () -> CameraPosition): OverMap {
    val stage = LocalMapStage.current
    return remember(key) {
        val shown = stage?.shown()
        OverMap(shown, shown?.state ?: MapState(own()))
    }
}

/** The user's look, as the Map home draws it ([mapStyleOf]): what a screen's own map takes. */
@Composable
internal fun userLook(): MapStyle {
    val services = LocalServices.current
    val style by remember(services) { services.settings.data.map { mapStyleOf(it, services.ciTheme) } }
        .collectAsStateWithLifecycle(MapStyle(calm = true))
    return style
}

/**
 * [over]'s map with this screen's [overlays], taps and long presses: the shown map's look, padding and camera's listener,
 * or, for its own, the user's look ([userLook]) with [ownPadding]. Where the screen's places show is its own band
 * ([bandBetween] its padding), whichever map it is.
 */
@Composable
internal fun OverStageMap(
    over: OverMap,
    overlays: MapOverlays,
    ownPadding: PaddingValues,
    onLongPress: (LatLon) -> Unit = {},
    onLongPressBuilding: ((MapPick) -> Unit)? = null,
    modifier: Modifier = Modifier,
    background: Color = Color.Unspecified,
    hostModifier: Modifier = Modifier,
    onTap: (MapHit) -> Unit = {},
    featureOf: (suspend (MapPick) -> Place?)? = null,
) {
    val shown = over.shown
    if (shown != null) {
        StageMap(
            state = shown.state, modifier = modifier, style = shown.style, overlays = overlays,
            contentPadding = shown.contentPadding, onTap = onTap, onLongPress = onLongPress,
            onLongPressBuilding = onLongPressBuilding, featureOf = featureOf, onCameraMove = shown::cameraMoved, debug = shown.debug,
            units = shown.units, labelTypeface = shown.labelTypeface, background = background, hostModifier = hostModifier,
        )
        return
    }
    val context = LocalContext.current
    val face = remember { runCatching { ResourcesCompat.getFont(context, R.font.instrument_serif_italic) }.getOrNull() }
    StageMap(
        state = over.state, modifier = modifier, style = userLook(), overlays = overlays, contentPadding = ownPadding,
        onTap = onTap, onLongPress = onLongPress, onLongPressBuilding = onLongPressBuilding, featureOf = featureOf,
        labelTypeface = face,
        background = background, hostModifier = hostModifier,
    )
}

/** A screen's band between [top] and [bottom] of its map's own padding (screen px): where its places show. */
@Composable
internal fun BoxWithConstraintsScope.bandBetween(top: Dp, bottom: Dp): Rect = with(LocalDensity.current) {
    Rect(0f, top.toPx(), maxWidth.toPx(), (maxHeight - bottom).toPx())
}

/**
 * Glides [over]'s map so [place] lands in [band]'s middle, seen as [view] sees it (its zoom, bearing and tilt): where a
 * screen's list shows its chosen place. Before the view's first frame, [view] at the place (its own map's padding is
 * the band's).
 */
internal suspend fun OverMap.centre(place: LatLon, band: Rect, view: CameraPosition, durationMs: Int) {
    val to = state.placing(place, band.center.x, band.center.y, view) ?: view.copy(target = place)
    state.follow = Follow.NONE
    state.animateTo(to, durationMs)
}

/** This view, or from further out than [NEAR_ZOOM] the same at [zoom]: a screen's places among their streets. */
internal fun CameraPosition.near(zoom: Double): CameraPosition = if (this.zoom < NEAR_ZOOM) copy(zoom = zoom) else this

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
 * A card's view from above (the user, 1 November: "A place card can get a button"): the map on screen goes to [at] as the
 * card's own preview showed it before the screens took on the map on screen, [zoom] at [pitch]°, in the band's middle
 * with the bearing kept; pressed again, back to the view it came from. Nothing is rebuilt, and the look stays the map's.
 */
@Stable
internal class ViewFromAbove(private val over: OverMap, private val at: LatLon, private val zoom: Double, private val pitch: Double) {
    /** The view the button left; null while the map is not taken above the place. */
    var before by mutableStateOf<CameraPosition?>(null)
        private set

    suspend fun toggle(band: Rect) {
        val map = over.state
        val back = before
        if (back != null) {
            before = null
            map.animateTo(back, GO_MS)
            return
        }
        val to = map.placing(at, band.center.x, band.center.y, map.camera.copy(zoom = zoom, pitch = pitch)) ?: return
        before = map.camera
        map.follow = Follow.NONE
        map.animateTo(to, GO_MS)
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

/** The views from above: a point's (What's here) and a place's, as their screens' own maps showed them until 1 November. */
internal const val POINT_ABOVE_ZOOM = 17.4
internal const val POINT_ABOVE_PITCH = 40.0
internal const val PLACE_ABOVE_ZOOM = 17.6
internal const val PLACE_ABOVE_PITCH = 48.0

/** The Map home's view of a link's point, where a screen over the map has none to take on. */
private const val POINT_ZOOM = 17.0

/** Nearer than this, a place the map goes to keeps the zoom: the view shows it among its streets already. */
internal const val NEAR_ZOOM = 15.5

/** The distance a place the map goes to is shown at, from further out: its building and the streets around. */
internal const val GO_ZOOM = 16.5

/** The glide that brings a point out from under its sheet, and the one that goes to a place. */
private const val REVEAL_MS = 450
private const val GO_MS = 800
