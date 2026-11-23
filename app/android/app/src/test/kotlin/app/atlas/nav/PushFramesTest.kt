package app.atlas.nav

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapStage
import app.atlas.mapview.vk.ChromeShapes
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import app.atlas.ui.home.settleScreen
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.rememberBackdrop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The work each frame does while a screen opens over the map: What's here pushed over the Map home, sliding in from a
 * third of the width as the home fades. Frame by frame it counts the recompositions of the one map's stage and of the
 * map (MapStage.kt's host, AtlasMap.kt), of the screen opening (WhatsHereScreen.kt and its map, OverMap.kt), the
 * chrome's wakes of the map's core (a fade's alone, where only an alpha changed, and the rest), and where the map lies.
 *
 * Each run writes its frames to `build/outputs/frames/push_whats_here_<glass|solid>.tsv`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class PushFramesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var shapes: ChromeShapes
    private lateinit var navigator: BackStackNavigator

    private data class Frame(
        val index: Int, val stage: Int, val screen: Int, val all: Int, val fadeWakes: Int, val otherWakes: Int, val map: Rect,
    )

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

    private fun show(glass: Boolean) {
        val services = testServices().apply { keepFixtureForecast() }
        runBlocking { services.settings.set(SettingsKeys.FROSTED_GLASS, glass) }
        navigator = BackStackNavigator(listOf(Dest.Home()))
        compose.setContent {
            val backdrop = rememberBackdrop()
            shapes = backdrop.shapes
            val stage = remember { MapStage() }
            CompositionLocalProvider(LocalBackdrop provides backdrop, LocalMapStage provides stage) {
                AtlasRoot(services, navigator, dark = false)
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settleScreen()
    }

    /** The map's picture, where it lies (root px). */
    private fun mapBounds(): Rect =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Map")))
            .fetchSemanticsNodes().single().boundsInRoot

    private fun shapesWithoutAlpha(): String = shapes.describe().replace(Regex(" alpha [0-9.]+"), "")

    /** Pushes What's here over the Map home and steps the clock [FRAMES] frames, drawing each as a phone does. */
    @OptIn(InternalComposeTracingApi::class)
    private fun push(name: String): List<Frame> {
        var fadeWakes = 0
        var otherWakes = 0
        var last = shapesWithoutAlpha()
        val wake = shapes.onChange
        shapes.onChange = {
            val now = shapesWithoutAlpha()
            if (now == last) fadeWakes++ else otherWakes++
            last = now
            wake?.invoke()
        }
        Composer.setTracer(tracer)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { navigator.go(Dest.WhatsHere(52.37452, 4.88377)) }

        val frames = (0 until FRAMES).map { i ->
            runs.clear()
            fadeWakes = 0
            otherWakes = 0
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onRoot().captureToImage()
            Frame(
                i,
                stage = runs.count { it.startsWith("app.atlas.mapview.MapStageHost ") || "(AtlasMap.kt:" in it },
                screen = runs.count { "(WhatsHereScreen.kt:" in it || "(OverMap.kt:" in it },
                all = runs.size, fadeWakes = fadeWakes, otherWakes = otherWakes, map = mapBounds(),
            )
        }
        Composer.setTracer(null)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // the control of the counts: the push's first frames compose the screen it opens and give the stage its map,
        // so a tracer that sees nothing, or source info written another way, fails the tests rather than passing them
        assertTrue("the push's first frames recompose the screen: $frames", frames.sumOf { it.screen } >= 1)
        assertTrue("the push's first frames recompose the stage: $frames", frames.sumOf { it.stage } >= 1)

        val out = File(shotsDir.parentFile, "frames").apply { mkdirs() }
        File(out, "push_whats_here_$name.tsv").writeText(
            "frame\tstage recompositions\tscreen recompositions\tall recompositions\tfade wakes\tother wakes\tmap left px\tmap width px\n" +
                frames.joinToString("") { "${it.index}\t${it.stage}\t${it.screen}\t${it.all}\t${it.fadeWakes}\t${it.otherWakes}\t${it.map.left}\t${it.map.width}\n" },
        )
        println(
            "push What's here ($name): ${frames.size} frames; stage recompositions ${frames.sumOf { it.stage }} in " +
                "${frames.count { it.stage > 0 }} frames, screen ${frames.sumOf { it.screen }} in ${frames.count { it.screen > 0 }}, " +
                "all ${frames.sumOf { it.all }}; core wakes: fade ${frames.sumOf { it.fadeWakes }}, other ${frames.sumOf { it.otherWakes }}; " +
                "map left/width ${frames.map { "${it.map.left.toInt()}/${it.map.width.toInt()}" }}",
        )
        return frames
    }

    @Test
    fun theMapStaysWholeUnderTheScreenSlidingIn() {
        show(glass = false)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val frames = push("solid")
        frames.forEach { f ->
            assertTrue("frame ${f.index}: the map at ${f.map}, the screen $root", abs(f.map.left - root.left) <= 1f && abs(f.map.width - root.width) <= 1f)
        }
    }

    @Test
    fun mostFramesOfTheSlideRecomposeNeitherTheStageNorTheScreen() {
        show(glass = false)
        val slide = push("solid").take(SLIDE_FRAMES)
        val quiet = slide.count { it.stage == 0 && it.screen == 0 }
        // the push itself (the new screen's first frames, its map asked for) and a lookup's answer may recompose; the
        // slide alone does not
        assertTrue("frames of the slide that recompose neither: $quiet of ${slide.size}: $slide", quiet >= SLIDE_FRAMES - 5)
    }

    @Test
    fun withTheGlassOffThePushsFadesWakeTheCoreNotOnce() {
        show(glass = false)
        val frames = push("solid")
        assertEquals("$frames", 0, frames.sumOf { it.fadeWakes })
    }

    @Test
    fun withTheGlassOnThePushsFadesStillGiveTheCoreTheirAlpha() {
        show(glass = true)
        // as on a phone, where the core draws the glass: while it does not, a frosted shape's wake waits for rest
        compose.runOnIdle { shapes.coreDraws = true }
        val frames = push("glass")
        assertTrue("$frames", frames.sumOf { it.fadeWakes } >= 3)
    }

    private companion object {
        const val FRAMES = 40
        // 260 ms at 16 ms a frame
        const val SLIDE_FRAMES = 16
    }
}
