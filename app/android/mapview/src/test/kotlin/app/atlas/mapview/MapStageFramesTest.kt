package app.atlas.mapview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one map ([MapStage]) under a screen whose map window moves: a small map in a page that scrolls (the quests'), and
 * a screen sliding in over the map as a push does. The map follows its window frame by frame, laid out where the window
 * is, and the stage and the map do no work of composition for it: a frame that only moves the window recomposes neither.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapStageFramesTest {
    @get:Rule
    val compose = createComposeRule()

    // The composables run this frame, by the source info the compiler gives each one.
    private val runs = ArrayList<String>()

    @OptIn(InternalComposeTracingApi::class)
    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress() = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            runs += info
        }
        override fun traceEventEnd() {}
    }

    @OptIn(InternalComposeTracingApi::class)
    @After
    fun noTracer() = Composer.setTracer(null)

    /** The stage's and the map's composables run this frame. */
    private fun stageRuns(): Int = runs.count { it.startsWith("app.atlas.mapview.MapStageHost ") || "(AtlasMap.kt:" in it }

    /** The map's picture, where it lies (root px). */
    private fun mapBounds(): Rect =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Map")))
            .fetchSemanticsNodes().single().boundsInRoot

    private fun near(a: Rect, b: Rect) = abs(a.left - b.left) <= 1f && abs(a.top - b.top) <= 1f && abs(a.right - b.right) <= 1f && abs(a.bottom - b.bottom) <= 1f

    /** One frame after [move], drawn as a phone draws it: the stage's runs in it, and the map's bounds after it. */
    private fun frame(move: () -> Unit): Pair<Int, Rect> {
        compose.runOnIdle(move)
        runs.clear()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        return stageRuns() to mapBounds()
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun aSmallMapInAScrollingPageFollowsItsWindowWithoutRecomposing() {
        val stage = MapStage()
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 0.0)).apply { time = Fixtures.afternoon }
        lateinit var scroll: ScrollState
        var window = Rect.Zero
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MapStageHost(stage, cover = Color.White)
                CompositionLocalProvider(LocalMapStage provides stage) {
                    scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        Spacer(Modifier.height(300.dp))
                        StageMap(state, Modifier.fillMaxWidth().height(230.dp).onGloballyPositioned { window = it.boundsInRoot() })
                        Spacer(Modifier.height(1200.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("${mapBounds()} at $window", near(mapBounds(), window))

        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        val frames = (1..12).map { frame { scroll.dispatchRawDelta(17f) } }
        Composer.setTracer(null)
        compose.mainClock.autoAdvance = true

        println("small map scrolled: stage runs per frame ${frames.map { it.first }}")
        // where its window is, each frame: 12 × 17 px up by the end
        assertTrue("${frames.last().second} at $window", near(frames.last().second, window))
        assertEquals(600f - 12 * 17f, window.top, 1f)
        assertEquals("the stage's and the map's recompositions while the window only scrolls: ${frames.map { it.first }}", 0, frames.sumOf { it.first })
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun aScreenSlidingInOverTheMapKeepsItWholeAndRecomposesNothing() {
        val stage = MapStage()
        val state = MapState(CameraPosition(Fixtures.westermarkt, 16.0, 0.0, 0.0)).apply { time = Fixtures.afternoon }
        var shift by mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MapStageHost(stage, cover = Color.White)
                CompositionLocalProvider(LocalMapStage provides stage) {
                    // a push's slide: the screen in from a third of the width
                    StageMap(state, Modifier.fillMaxSize().offset { IntOffset(shift.roundToInt(), 0) })
                }
            }
        }
        compose.waitForIdle()
        val whole = mapBounds()
        assertEquals(0f, whole.left, 0f)

        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        val width = whole.width
        val frames = (10 downTo 0).map { k -> frame { shift = width / 3f * k / 10f } }
        Composer.setTracer(null)
        compose.mainClock.autoAdvance = true

        println("screen slid in: stage runs per frame ${frames.map { it.first }}, map ${frames.map { it.second.left.roundToInt() to it.second.width.roundToInt() }}")
        // the whole map under the screen all the way: it neither moves nor shrinks with the screen's slide
        frames.forEach { (_, b) -> assertTrue("$b, whole $whole", near(b, whole)) }
        assertEquals("the stage's and the map's recompositions while the screen only slides: ${frames.map { it.first }}", 0, frames.sumOf { it.first })
    }
}
