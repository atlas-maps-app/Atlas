package app.atlas.ui.home

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapStage
import app.atlas.mapview.vk.ChromeShapes
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
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

/**
 * The work each frame does while the search's sheet glides over the Map home (the user, 11 November: "on the home
 * screen when the categories come up and the categiry chips dissapear, it lags"): the search opened from the search
 * bar, the sheet glided to full, the home's row riding its top edge and fading out once it is full. Frame by frame it
 * counts the Map home's and the sheet's recompositions (the Compose compiler's trace markers, by the file each
 * composable lies in) and the chrome's wakes of the map's core (ChromeShapes.onChange, each one a whole frame of the
 * map): the ones a fade alone causes (only an alpha changed) and the rest (a shape moved, came or went).
 *
 * Each run writes its frames to `build/outputs/frames/home_sheet_glide_<glass|solid>.tsv`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomeSheetFramesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var shapes: ChromeShapes
    private lateinit var stage: MapStage

    /** What one frame did: the sheet's move (px), the recompositions by file, the core's wakes. */
    private data class Frame(
        val index: Int, val sheetTop: Float, val moved: Boolean, val home: Int, val sheet: Int, val all: Int,
        val fadeWakes: Int, val otherWakes: Int,
    )

    // The trace markers' composables run this frame, by the source info the compiler gives each one.
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
        compose.setContent {
            val backdrop = rememberBackdrop()
            shapes = backdrop.shapes
            stage = remember { MapStage() }
            CompositionLocalProvider(LocalBackdrop provides backdrop, LocalMapStage provides stage) {
                AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false)
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.settleScreen()
    }

    /** One dp of the board's 412 across the screen, px. */
    private fun boardDp(): Float = compose.onRoot().fetchSemanticsNode().size.width / 412f

    /** The sheet's top edge (its grabber's top), px. */
    private fun sheetTop(): Float =
        compose.onAllNodes(hasContentDescription("Show more"), useUnmergedTree = true).fetchSemanticsNodes().first().positionInRoot.y

    /** The sheet's "Show more" (its accessibility action, as a tap on the grabber's): a glide to full. */
    private fun showMore() {
        val expand = compose.onAllNodes(
            SemanticsMatcher("has the sheet's Show more") { n ->
                n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Show more" } == true
            },
        ).fetchSemanticsNodes().single().config[SemanticsActions.CustomActions].first { it.label == "Show more" }
        compose.runOnIdle { expand.action() }
    }

    /** The chrome's shapes now, their count first: `vk chrome <n> shapes: …`, with no alpha (what a fade changes). */
    private fun shapesWithoutAlpha(): String = shapes.describe().replace(Regex(" alpha [0-9.]+"), "")

    private fun shapeCount(): Int = Regex("vk chrome (\\d+) shapes").find(shapes.describe())!!.groupValues[1].toInt()

    /**
     * Opens the search from the search bar, then glides the sheet from half to full (its "Show more", as a tap on the
     * grabber's action does) and steps the clock a frame at a time till the row has faded out and everything is still,
     * drawing each frame as a phone does. Returns the frames.
     */
    @OptIn(InternalComposeTracingApi::class)
    private fun glide(name: String): List<Frame> {
        compose.onNode(hasText("Search or ask")).performClick()
        compose.waitForIdle()
        compose.settleScreen()
        // As on a phone, where the core draws the map under the chrome, and so the glass under its frosted shapes:
        // Robolectric has no core to say so. The core follows that glass's moves and fades frame by frame, and a
        // keep-out alone only where its labels go.
        compose.runOnIdle { shapes.coreDraws = true }
        compose.waitForIdle()
        val before = shapeCount()

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
        showMore()

        val frames = ArrayList<Frame>()
        var top = sheetTop()
        var still = 0
        var i = 0
        while (i < MAX_FRAMES && still < STILL_FRAMES) {
            runs.clear()
            fadeWakes = 0
            otherWakes = 0
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // a phone draws every frame; Robolectric draws when asked
            compose.onRoot().captureToImage()
            val now = sheetTop()
            val f = Frame(
                i, now, now != top,
                home = runs.count { "(HomeScreen.kt:" in it }, sheet = runs.count { "(Sheet.kt:" in it }, all = runs.size,
                fadeWakes = fadeWakes, otherWakes = otherWakes,
            )
            frames += f
            // still once the row has gone: the spring's last sub-pixel frames move nothing the screen reads, and
            // the row fades only when the glide has ended
            val rowGone = shapeCount() <= before - 8
            still = if (rowGone && !f.moved && f.all == 0 && f.fadeWakes + f.otherWakes == 0) still + 1 else 0
            top = now
            i++
        }
        Composer.setTracer(null)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val out = File(shotsDir.parentFile, "frames").apply { mkdirs() }
        File(out, "home_sheet_glide_$name.tsv").writeText(
            "frame\tsheet top px\tmoved\thome recompositions\tsheet recompositions\tall recompositions\tfade wakes\tother wakes\n" +
                frames.joinToString("") { "${it.index}\t${it.sheetTop}\t${it.moved}\t${it.home}\t${it.sheet}\t${it.all}\t${it.fadeWakes}\t${it.otherWakes}\n" },
        )
        val moving = frames.filter { it.moved }
        println(
            "home sheet glide ($name): ${frames.size} frames, ${moving.size} moving the sheet; home recompositions " +
                "${frames.sumOf { it.home }} (${moving.sumOf { it.home }} in moving frames), sheet ${frames.sumOf { it.sheet }} " +
                "(${moving.sumOf { it.sheet }}), all ${frames.sumOf { it.all }}; core wakes: fade ${frames.sumOf { it.fadeWakes }}, " +
                "other ${frames.sumOf { it.otherWakes }} (${moving.sumOf { it.otherWakes }} in moving frames); shapes $before -> ${shapeCount()}",
        )
        assertTrue("the sheet glided: $frames", moving.size >= 5)
        // the control of the counts: where the sheet comes to rest its stop changes, which the home and the sheet
        // read, so a tracer that sees nothing, or source info written another way, fails the tests rather than passing
        assertTrue("the sheet's rest recomposes the home: $frames", frames.sumOf { it.home } >= 1)
        assertTrue("the sheet's rest recomposes the sheet: $frames", frames.sumOf { it.sheet } >= 1)
        assertTrue("the row's eight chips went with its fade: $before -> ${shapeCount()}", shapeCount() <= before - 8)
        return frames
    }

    /**
     * The frames in which the sheet only moved: each moving frame but the one it comes to rest in and the one after
     * (the rest changes the sheet's stop, which the screen reads: the row fades out then).
     */
    private fun onlyMoving(frames: List<Frame>): List<Frame> {
        val lastMove = frames.last { it.moved }.index
        return frames.filter { it.moved && it.index < lastMove }
    }

    @Test
    fun aFrameThatOnlyMovesTheSheetRecomposesNeitherTheHomeNorTheSheet() {
        show(glass = false)
        val frames = glide("solid")
        val moving = onlyMoving(frames)
        assertTrue("$frames", moving.size >= 4)
        assertEquals("the Map home's recompositions while the sheet only moves: $moving", 0, moving.sumOf { it.home })
        assertEquals("the sheet's recompositions while it only moves: $moving", 0, moving.sumOf { it.sheet })
    }

    @Test
    fun withTheGlassOffTheRowsFadeWakesTheCoreNotOnce() {
        show(glass = false)
        val frames = glide("solid")
        assertEquals("$frames", 0, frames.sumOf { it.fadeWakes })
    }

    @Test
    fun withTheGlassOnTheRowsFadeStillGivesTheCoreItsAlpha() {
        show(glass = true)
        val frames = glide("glass")
        // the core's glass under the chips fades with their tint, frame by frame
        assertTrue("$frames", frames.sumOf { it.fadeWakes } >= 3)
    }

    /** The chrome's shapes as they lie, root px (ChromeShapes.describe's boxes). */
    private fun shapeBoxes(): List<Rect> =
        Regex("(-?[0-9.]+) (-?[0-9.]+) (-?[0-9.]+) (-?[0-9.]+) r ").findAll(shapes.describe().substringAfter("shapes: "))
            .map { m -> m.groupValues.drop(1).map(String::toFloat).let { Rect(it[0], it[1], it[2], it[3]) } }.toList()

    /**
     * The column riding the sheet's edge, as it lies now, is where the map's labels keep clear: the attribution and every
     * chrome shape between it and the sheet's top edge (the row's chips) lie inside the screen's label keep-outs (dp),
     * top to bottom: a chip scrolled past the column's side (the row bleeds 12 dp over the screen's padding) is kept clear
     * where it lies over the column, most of its width. Returns how many shapes it checked.
     */
    private fun assertTheColumnIsKeptClear(at: String): Int {
        val d = boardDp()
        val keepOut = stage.shown()!!.overlays.labelKeepOut
        fun kept(px: Rect): Boolean {
            val b = Rect(px.left / d, px.top / d, px.right / d, px.bottom / d)
            return keepOut.any { it.left <= b.left + 0.5f && it.top <= b.top + 0.5f && it.right >= b.right - 0.5f && it.bottom >= b.bottom - 0.5f }
        }
        fun across(px: Rect): Boolean {
            val b = Rect(px.left / d, px.top / d, px.right / d, px.bottom / d)
            return keepOut.any {
                it.top <= b.top + 0.5f && it.bottom >= b.bottom - 0.5f && minOf(it.right, b.right) - maxOf(it.left, b.left) >= b.width / 2f
            }
        }
        val attribution = compose.onNode(hasText("© OpenStreetMap")).fetchSemanticsNode().boundsInRoot
        assertTrue("$at: the attribution $attribution (px) kept clear in one of $keepOut (dp)", kept(attribution))
        // the sheet's top edge: its grabber is 10 dp under it
        val edge = sheetTop() - 10f * d
        val over = shapeBoxes().filter { it.top >= attribution.top - 0.5f && it.bottom <= edge + 0.5f }
        for (b in over) assertTrue("$at: the shape $b (px) over the sheet kept clear in one of $keepOut (dp)", across(b))
        return over.size
    }

    @Test
    fun whereTheSheetComesToRestTheMapsLabelsKeepClearOfTheColumnOverIt() {
        show(glass = false)
        compose.onNode(hasText("Search or ask")).performClick()
        compose.waitForIdle()
        compose.settleScreen()
        // at half, the row's chips over the sheet
        assertTrue("the row's chips over the sheet at half", assertTheColumnIsKeptClear("half") >= 4)
        // glided to full, the row faded out: the attribution where it came to rest
        showMore()
        compose.waitForIdle()
        compose.settleScreen()
        assertTheColumnIsKeptClear("full")
    }

    private companion object {
        /** The most frames a glide is followed for (4 s). */
        const val MAX_FRAMES = 240

        /** Frames in a row with nothing done that end it. */
        const val STILL_FRAMES = 10
    }
}
