package app.atlas.mapview.vk

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * [ChromeFade]: a chrome shape under a fade gives the core the alpha its layer is drawn at, frame by frame, from the
 * first frame it comes in (never opaque before its fade) until it goes with its content, under AnimatedVisibility and
 * under AnimatedContent (a NavDisplay entry's scope: the entering and the exiting content each their own), and under
 * two fades the product of both. The alpha its layer is drawn at is the fade's value at its transition's play time
 * (linear fades here: the play time over the fade's length), which Compose's graphics layer takes as its alpha.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
class ChromeFadeTest {
    @get:Rule
    val compose = createComposeRule()

    private val shapes = ChromeShapes().apply { coreDraws = true }

    // Each pill's enter-exit transition, by its top, as composed.
    private val transitions = HashMap<Int, Transition<EnterExitState>>()

    /**
     * The alphas the core reads now, by each shape's top (px): 1 - fade / 127 of each shape written, once the window
     * has drawn the frame (Robolectric draws it only when asked, and a capture asks: a phone draws every frame).
     */
    private fun alphas(): Map<Int, Float> {
        compose.onRoot().captureToImage()
        val out: ByteBuffer = CameraWriter.allocate()
        CameraWriter.header(out, 1, 0)
        CameraWriter.frame(out, 400, 800, 400, 800, 1.0)
        shapes.write(out)
        return (0 until out.getInt(CameraWriter.KEEP_OUT_COUNT_AT)).associate { i ->
            val o = CameraWriter.KEEP_OUT_AT + i * CameraWriter.KEEP_OUT_SIZE
            out.getFloat(o + 4).toInt() to 1f - ((out.get(o + 23).toInt() and 0xFF) ushr 1) / 127f
        }
    }

    /**
     * The main clock on by [ms] (a frame when 0), and the main thread's work it leaves done: the frames' layouts and
     * draws, which the test reads after it (the clock stands still between the calls).
     */
    private fun advance(ms: Long = 0L) {
        if (ms == 0L) compose.mainClock.advanceTimeByFrame() else compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** Changes state on the UI thread, and lets the change reach the recomposer before the clock moves on. */
    private fun change(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    /** A white pill 120 × 40 px at ([x], [y]) px, one of the chrome's shapes, its fade's transition kept by its top. */
    @Composable
    private fun Pill(x: Int, y: Int, transition: Transition<EnterExitState>) {
        SideEffect { transitions[y] = transition }
        Box(
            Modifier
                .offset { IntOffset(x, y) }
                .size(120.dp, 40.dp)
                .chromeShape(shapes, RoundedCornerShape(20.dp), 20.dp, 1.6f)
                .background(Color.White, RoundedCornerShape(20.dp)),
        )
    }

    /** A linear fade's alpha [ms] long, entering or exiting, at the play time of the pill at [top]'s transition. */
    private fun linear(top: Int, ms: Int): Float {
        val t = transitions.getValue(top)
        val k = (t.playTimeNanos / (ms * 1e6)).coerceIn(0.0, 1.0).toFloat()
        return if (t.targetState == EnterExitState.Visible) k else 1f - k
    }

    /** The pill at [top] gives the core [expected], within the fade's 1/127. */
    private fun assertAlpha(top: Int, expected: Float) {
        val written = alphas().getValue(top)
        assertTrue("written $written, the layer's $expected", abs(written - expected) <= 0.5f / 127f + 1e-4f)
    }

    @Test
    fun aPillUnderAnAnimatedVisibilityGivesItsLayersAlphaFromItsFirstFrame() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AnimatedVisibility(
                    visible,
                    enter = fadeIn(tween(160, easing = LinearEasing)),
                    exit = fadeOut(tween(160, easing = LinearEasing)),
                ) {
                    ChromeFade(this) { Pill(100, 200, transition) }
                }
            }
        }
        advance()
        assertEquals(emptyMap<Int, Float>(), alphas())
        change { visible = true }
        advance()
        // The frame that first lays the pill out writes it at the fade's start: never opaque before the fade.
        assertAlpha(200, linear(200, 160))
        assertTrue("${alphas()}", alphas().getValue(200) < 0.25f)
        advance(64)
        assertAlpha(200, linear(200, 160))
        val rising = alphas().getValue(200)
        assertTrue("$rising", rising > 0.2f && rising < 0.9f)
        advance(200)
        assertEquals(1f, alphas().getValue(200), 0f)
        change { visible = false }
        advance()
        advance(64)
        assertAlpha(200, linear(200, 160))
        val falling = alphas().getValue(200)
        assertTrue("$falling", falling > 0.1f && falling < 0.8f)
        advance(200)
        // Gone with its content: its slot went with it.
        assertEquals(emptyMap<Int, Float>(), alphas())
    }

    @Test
    fun underAnimatedContentTheEnteringAndTheExitingPillEachGiveTheirOwn() {
        // NavDisplay's push: the new entry fades in over 200 ms as the old one fades out over 160.
        var page by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AnimatedContent(
                    page,
                    transitionSpec = {
                        fadeIn(tween(200, easing = LinearEasing)) togetherWith fadeOut(tween(160, easing = LinearEasing))
                    },
                ) { p ->
                    ChromeFade(this) { Pill(100, if (p == 0) 100 else 400, transition) }
                }
            }
        }
        advance()
        assertEquals(mapOf(100 to 1f), alphas())
        change { page = 1 }
        advance()
        advance(80)
        assertAlpha(100, linear(100, 160))
        assertAlpha(400, linear(400, 200))
        val a = alphas()
        assertTrue("$a", a.getValue(400) > 0.2f && a.getValue(400) < 0.8f && a.getValue(100) > 0.1f && a.getValue(100) < 0.8f)
        advance(300)
        assertEquals(mapOf(400 to 1f), alphas())
    }

    @Test
    fun underTwoFadesThePillGivesTheProductOfBoth() {
        var outer by mutableStateOf(true)
        var inner by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        lateinit var outerTransition: Transition<EnterExitState>
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AnimatedVisibility(outer, enter = fadeIn(tween(400, easing = LinearEasing)), exit = fadeOut(tween(400, easing = LinearEasing))) {
                    outerTransition = transition
                    ChromeFade(this) {
                        AnimatedVisibility(inner, enter = fadeIn(tween(400, easing = LinearEasing)), exit = fadeOut(tween(400, easing = LinearEasing))) {
                            ChromeFade(this) { Pill(100, 300, transition) }
                        }
                    }
                }
            }
        }
        advance()
        assertEquals(mapOf(300 to 1f), alphas())
        change {
            outer = false
            inner = false
        }
        advance()
        advance(160)
        val outerAlpha = 1f - (outerTransition.playTimeNanos / 400e6).coerceIn(0.0, 1.0).toFloat()
        assertAlpha(300, outerAlpha * linear(300, 400))
        val both = alphas().getValue(300)
        // Each fade near 0.6 by now: their product, well under either.
        assertTrue("$both", both > 0.15f && both < 0.55f)
    }
}
