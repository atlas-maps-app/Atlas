package app.atlas.mapview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One map for the whole app. Screens come and go on the back stack, but the map stays: [MapStageHost] draws a single
 * [AtlasMap] at the app's root, under every screen, and each screen asks for it with [StageMap] where it would have
 * placed its own.
 *
 * The newest request is drawn, with that screen's camera, style, overlays and callbacks. A screen whose map fills the
 * root gets it full size, so the map stays put while the screens' chrome animates over it; a smaller one gets it at its
 * place. Nothing is rebuilt as screens change: the map's frames, and the core's surface and tiles, live as long as the
 * root. A screen without a map covers it with its own background, and with no request at all the host covers it.
 */
@Stable
class MapStage {
    internal val requests = mutableStateListOf<StageRequest>()

    /** The request drawn: the newest one with its place known. */
    internal val active: StageRequest? get() = requests.lastOrNull { it.args != null && it.bounds != null }
}

/** One screen's map: what [StageMap] was given, and its place in the root. */
@Stable
internal class StageRequest {
    var args by mutableStateOf<StageArgs?>(null)
    var bounds by mutableStateOf<Rect?>(null)
}

internal data class StageArgs(
    val state: MapState,
    val style: MapStyle,
    val overlays: MapOverlays,
    val contentPadding: PaddingValues,
    val onTap: (MapHit) -> Unit,
    val onLongPress: (LatLon) -> Unit,
    val onCameraMove: (camera: CameraPosition, byUser: Boolean) -> Unit,
    val debug: Boolean,
    val units: UnitSystem,
    val labelTypeface: android.graphics.Typeface?,
    val background: Color,
    val hostModifier: Modifier,
    val above: (@Composable BoxScope.() -> Unit)?,
)

/** The app's [MapStage]; null outside the root, where a screen (in a test or a preview) draws its map in place. */
val LocalMapStage = staticCompositionLocalOf<MapStage?> { null }

/**
 * A screen's map, with [AtlasMap]'s parameters: drawn by the root's one map ([LocalMapStage]) at this place, or in
 * place when there is no stage.
 *
 * [background] shows under the map until its first frame (the screen's own paper). [hostModifier] applies to the map
 * where it is drawn (the frosted glass's source, say) and [above] is drawn over the map there (the Map home's wash), so
 * both stay part of what the glass blurs.
 */
@Composable
fun StageMap(
    state: MapState,
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle(),
    overlays: MapOverlays = MapOverlays(),
    contentPadding: PaddingValues = PaddingValues(),
    onTap: (MapHit) -> Unit = {},
    onLongPress: (LatLon) -> Unit = {},
    onCameraMove: (camera: CameraPosition, byUser: Boolean) -> Unit = { _, _ -> },
    debug: Boolean = false,
    units: UnitSystem = UnitSystem.forLocale(Locale.getDefault()),
    /** The face of [MapOverlays.labels]: the boards' Instrument Serif italic. */
    labelTypeface: android.graphics.Typeface? = null,
    background: Color = Color.Unspecified,
    hostModifier: Modifier = Modifier,
    above: (@Composable BoxScope.() -> Unit)? = null,
) {
    val stage = LocalMapStage.current
    if (stage == null) {
        Box(modifier.under(background).then(hostModifier)) {
            AtlasMap(
                state = state, modifier = Modifier.matchParentSize(), style = style, overlays = overlays,
                contentPadding = contentPadding, onTap = onTap, onLongPress = onLongPress, onCameraMove = onCameraMove,
                debug = debug, units = units, labelTypeface = labelTypeface,
            )
            above?.invoke(this)
        }
        return
    }
    val request = remember { StageRequest() }
    SideEffect {
        request.args = StageArgs(
            state, style, overlays, contentPadding, onTap, onLongPress, onCameraMove, debug, units, labelTypeface,
            background, hostModifier, above,
        )
    }
    DisposableEffect(stage, request) {
        stage.requests.add(request)
        onDispose { stage.requests.remove(request) }
    }
    Box(modifier.onGloballyPositioned { request.bounds = it.boundsInRoot() })
}

/**
 * Draws [stage]'s one map under the screens: at the newest request's place (full size when that place is the whole
 * root), with the request's host modifier and the content above it. With no request it keeps the map, and covers it
 * with [cover] (the root's paper) until a screen asks again.
 */
@Composable
fun MapStageHost(stage: MapStage, cover: Color, modifier: Modifier = Modifier) {
    val last = remember { mutableStateOf<StageRequest?>(null) }
    val active = stage.active
    SideEffect { if (active != null) last.value = active }
    val request = active ?: last.value ?: return
    val args = request.args ?: return
    val bounds = request.bounds ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val full = bounds.width >= constraints.maxWidth - 1f && bounds.height >= constraints.maxHeight - 1f
        val place = if (full) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        }
        Box(place.under(args.background).then(args.hostModifier)) {
            AtlasMap(
                state = args.state, modifier = Modifier.matchParentSize(), style = args.style,
                overlays = args.overlays, contentPadding = args.contentPadding, onTap = args.onTap,
                onLongPress = args.onLongPress, onCameraMove = args.onCameraMove, debug = args.debug, units = args.units,
                labelTypeface = args.labelTypeface,
            )
            args.above?.invoke(this)
        }
        if (active == null) Box(Modifier.fillMaxSize().background(cover))
    }
}

/** [colour] under what follows, when it is given. */
private fun Modifier.under(colour: Color): Modifier = if (colour.isSpecified) background(colour) else this
