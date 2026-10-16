package app.atlas.ui.kit

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.atlas.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a [Sheet] rests: off screen, a peek of its top, half the screen, or nearly full. */
enum class SheetStop { HIDDEN, PEEK, HALF, FULL }

/**
 * The state of a [Sheet]: where it rests ([stop]), where it is while dragged, and how much of the screen it covers
 * ([visibleHeightPx], for the map's content padding). [stops] are the rests it may snap to.
 */
@Stable
class SheetState(initial: SheetStop = SheetStop.PEEK, val stops: Set<SheetStop> = setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL)) {
    var stop: SheetStop by mutableStateOf(initial)
        private set

    internal var offset by mutableFloatStateOf(Float.NaN)
    internal var anchors: Map<SheetStop, Float> by mutableStateOf(emptyMap())
    internal var containerHeight by mutableFloatStateOf(0f)
    private val mutex = MutatorMutex()

    /** Pixels of the screen the sheet covers now, 0 when hidden or before layout. */
    val visibleHeightPx: Float get() = if (offset.isNaN()) 0f else (containerHeight - offset).coerceAtLeast(0f)

    val isHidden: Boolean get() = stop == SheetStop.HIDDEN

    /** Glides to [target], if it is one of [stops] (or HIDDEN, always allowed). */
    suspend fun animateTo(target: SheetStop) {
        if (target != SheetStop.HIDDEN && target !in stops) return
        val to = anchors[target]
        if (to == null || offset.isNaN()) {
            stop = target
            return
        }
        mutex.mutate {
            animate(offset, to, 0f, SPRING) { v, _ -> offset = v }
            stop = target
        }
    }

    /** Jumps to [target] without animation. */
    fun snapTo(target: SheetStop) {
        stop = target
        anchors[target]?.let { offset = it }
    }

    internal fun dragBy(delta: Float): Float {
        if (offset.isNaN() || anchors.isEmpty()) return 0f
        val min = minAnchor()
        val max = maxAnchor()
        val before = offset
        offset = (offset + delta).coerceIn(min, max)
        return offset - before
    }

    internal suspend fun settle(velocity: Float) {
        if (offset.isNaN() || anchors.isEmpty()) return
        val candidates = anchors.filterKeys { it in stops || it == SheetStop.HIDDEN && stop == SheetStop.HIDDEN }
        val target = when {
            abs(velocity) > FLING_VELOCITY -> {
                // A fling goes to the next rest in its direction.
                val sorted = candidates.entries.sortedBy { it.value }
                if (velocity < 0) sorted.lastOrNull { it.value < offset - 1f } ?: sorted.first()
                else sorted.firstOrNull { it.value > offset + 1f } ?: sorted.last()
            }
            else -> candidates.entries.minBy { abs(it.value - offset) }
        }
        // Already resting there (a fling handed over by a list that hit its end): stay, rather than spring out and back.
        val v = if (abs(target.value - offset) < 1f) 0f else velocity
        mutex.mutate(MutatePriority.Default) {
            animate(offset, target.value, v, SPRING) { value, _ -> offset = value }
            stop = target.key
        }
    }

    internal fun minAnchor(): Float = anchors.filterKeys { it in stops }.values.minOrNull() ?: 0f
    internal fun maxAnchor(): Float = anchors.filterKeys { it in stops }.values.maxOrNull() ?: containerHeight

    internal fun updateAnchors(new: Map<SheetStop, Float>, height: Float) {
        containerHeight = height
        if (new == anchors) return
        anchors = new
        new[stop]?.let { offset = it }
    }

    companion object {
        private const val FLING_VELOCITY = 1200f
        private val SPRING = SpringSpec<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

        /** Keeps the rest across configuration changes. */
        fun saver(stops: Set<SheetStop>) = Saver<SheetState, String>(
            save = { it.stop.name },
            restore = { SheetState(SheetStop.valueOf(it), stops) },
        )
    }
}

@Composable
fun rememberSheetState(
    initial: SheetStop = SheetStop.PEEK,
    stops: Set<SheetStop> = setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL),
): SheetState = rememberSaveable(saver = SheetState.saver(stops)) { SheetState(initial, stops) }

/** A board's own sheet: its [surface] (shadow, corners, colour, clipped) and the colour of its [grabber]. */
class SheetLook(val surface: Modifier, val grabber: Color)

/**
 * A bottom sheet over the map, as on the Place, Route and Charge boards: a white surface with 32 dp top corners (or a
 * board's own [look]), a grabber, and rests at [peekHeight], half the screen ([halfFraction]) and nearly full (leaving
 * [fullTopGap]). Drag the grabber or the content; scrollable content inside hands the drag over at its top.
 */
@Composable
fun Sheet(
    state: SheetState,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 148.dp,
    halfFraction: Float = 0.52f,
    fullTopGap: Dp = 72.dp,
    look: SheetLook? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val c = Atlas.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val peek = with(density) { peekHeight.toPx() }
        val fullTop = with(density) { fullTopGap.toPx() }
        val anchors = remember(h, peek, fullTop, halfFraction) {
            mapOf(
                SheetStop.HIDDEN to h,
                SheetStop.PEEK to (h - peek).coerceAtLeast(fullTop),
                SheetStop.HALF to (h * (1f - halfFraction)).coerceAtLeast(fullTop),
                SheetStop.FULL to fullTop,
            )
        }
        SideEffect { state.updateAnchors(anchors, h) }
        val y = if (state.offset.isNaN()) anchors.getValue(state.stop) else state.offset
        val connection = remember(state) { SheetScrollConnection(state) }
        val dragState = rememberDraggableState { delta -> state.dragBy(delta) }
        val expand = stringResource(R.string.kit_sheet_expand)
        val collapse = stringResource(R.string.kit_sheet_collapse)
        Column(
            Modifier
                .offset { IntOffset(0, y.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (h - fullTop).toDp() })
                .then(look?.surface ?: Modifier.softShadow(Radii.sheet, c.shadow, 16.dp).clip(Radii.sheet).background(c.card))
                .nestedScroll(connection)
                .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> state.settle(v) })
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(expand) { scope.launch { state.animateTo(SheetStop.FULL) }; true },
                        CustomAccessibilityAction(collapse) { scope.launch { state.animateTo(SheetStop.PEEK) }; true },
                    )
                },
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(look?.grabber ?: c.line)
                        .semantics { contentDescription = expand },
                )
            }
            CompositionLocalProvider(LocalContentColor provides c.ink) { content() }
        }
    }
}

/** Lets a list inside the sheet move the sheet first when pulled at its top or pushed while the sheet is not full. */
private class SheetScrollConnection(private val state: SheetState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val dy = available.y
        // Pushing up while the sheet is below its top: the sheet rises before the content scrolls.
        return if (dy < 0 && source == NestedScrollSource.UserInput && state.offset > state.minAnchor()) Offset(0f, state.dragBy(dy))
        else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Pulling down past the content's top: the sheet follows.
        return if (available.y > 0 && source == NestedScrollSource.UserInput) Offset(0f, state.dragBy(available.y)) else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val notAtRest = state.anchors.values.none { abs(it - state.offset) < 1f }
        return if (notAtRest && (available.y < 0 && state.offset > state.minAnchor() || available.y > 0)) {
            state.settle(available.y)
            available
        } else Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settle(available.y)
        return available
    }
}
