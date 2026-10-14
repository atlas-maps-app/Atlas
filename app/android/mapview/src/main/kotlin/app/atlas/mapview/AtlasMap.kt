package app.atlas.mapview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.atlas.engine.Extras
import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.geo.UnitSystem
import app.atlas.tiles.TileSource
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The map: Atlas's own engine drawn on a Canvas, with gestures (pan, pinch, rotate, two-finger tilt, double tap,
 * long press, two-finger measure) and the overlays on top.
 *
 * Scenes are built and frames are made off the main thread; the main thread only draws them (one `drawVertices` per
 * batch) and the overlays, projected with the frame's own camera. Nothing is redrawn unless the camera, the overlays,
 * an animation or newly built scenes change something. [extras] gives real trees for a tile from the app's place
 * store; [debug] shows frame time, tiles and vertices over the map; [units] are the measuring tape's.
 */
@Composable
fun AtlasMap(
    state: MapState,
    tiles: TileSource?,
    modifier: Modifier = Modifier,
    style: MapStyle = MapStyle(),
    overlays: MapOverlays = MapOverlays(),
    contentPadding: PaddingValues = PaddingValues(),
    onTap: (MapHit) -> Unit = {},
    onLongPress: (LatLon) -> Unit = {},
    onCameraMove: (camera: CameraPosition, byUser: Boolean) -> Unit = { _, _ -> },
    debug: Boolean = false,
    extras: (suspend (TileId) -> Extras?)? = null,
    units: UnitSystem = UnitSystem.forLocale(Locale.getDefault()),
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val controller = remember(state) { MapController(state, scope) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val cameraMove by rememberUpdatedState(onCameraMove)

    val insets = with(density) {
        Insets(
            contentPadding.calculateStartPadding(direction).toPx(), contentPadding.calculateTopPadding().toPx(),
            contentPadding.calculateEndPadding(direction).toPx(), contentPadding.calculateBottomPadding().toPx(),
        )
    }
    SideEffect {
        controller.density = density.density
        controller.style = style
        controller.overlays = overlays
        if (controller.gestures.config.measureEnabled != overlays.measure || controller.gestures.config.touchSlop != 8f * density.density) {
            controller.gestures.config = GestureConfig.forDensity(density.density, overlays.measure)
        }
        controller.debug = debug
        controller.units = units
        controller.insets = insets
        controller.onTap = { tap(it) }
        controller.onLongPress = { longPress(it) }
        controller.onCameraMove = { c, u -> cameraMove(c, u) }
        controller.setSource(tiles)
        controller.setExtras(extras)
    }
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.detach() }
    }
    LaunchedEffect(controller) {
        // Anything that changes the picture asks for a frame; the frame loop conflates the requests.
        launch { snapshotFlow { controller.overlays.markers }.collect { controller.warm(it) } }
        launch {
            snapshotFlow {
                Inputs(
                    state.camera, state.time, controller.style, controller.size, controller.insets, controller.lookAhead,
                    controller.overlays.location,
                )
            }.collect { controller.update() }
        }
        snapshotFlow { state.follow to controller.overlays.location }.collect { controller.followUpdate() }
    }
    Box(
        modifier
            .clipToBounds()
            .semantics { contentDescription = "Map" }
            .onSizeChanged { controller.size = it.width to it.height }
            .pointerInput(controller) { pointers(controller) }
            .drawBehind {
                // Reading the tick subscribes this draw (and only the draw) to new frames.
                controller.frameTick.intValue
                drawIntoCanvas { controller.draw(it.nativeCanvas) }
            },
    )
}

/** What a frame depends on, for change detection. */
private data class Inputs(
    val camera: CameraPosition,
    val time: java.time.Instant?,
    val style: MapStyle,
    val size: Pair<Int, Int>,
    val insets: Insets,
    val lookAhead: Float,
    /** A new fix: the moment moved on, so the light may have. */
    val location: UserLocation?,
)

/** Feeds the pointer events of the view to the controller's gesture machine, with its timeouts. */
private suspend fun PointerInputScope.pointers(controller: MapController) {
    val g = controller.gestures
    try {
        awaitPointerEventScope {
            var last = 0L
            while (true) {
                val deadline = g.deadline
                val event = if (deadline != null) {
                    withTimeoutOrNull(maxOf(1L, deadline - last)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    last = deadline!!
                    g.timeout(deadline)
                    continue
                }
                var time = last
                for (c in event.changes) {
                    time = maxOf(time, c.uptimeMillis)
                    val id = c.id.value
                    when {
                        c.pressed && !c.previousPressed -> g.down(id, c.position.x, c.position.y, c.uptimeMillis)
                        !c.pressed && c.previousPressed -> g.up(id, c.position.x, c.position.y, c.uptimeMillis)
                        c.pressed && c.position != c.previousPosition -> g.move(id, c.position.x, c.position.y, c.uptimeMillis)
                    }
                }
                last = time
                g.frame(time)
                for (c in event.changes) if (c.pressed || c.previousPressed) c.consume()
            }
        }
    } finally {
        g.cancel()
    }
}
