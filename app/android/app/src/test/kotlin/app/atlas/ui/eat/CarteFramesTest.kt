package app.atlas.ui.eat

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.settle
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La Carte scrolled a line at a time: the screen works out the chapter in view (its tab sticks out of the binder's
 * index) as the list scrolls, and does no work of composition for a line passing that leaves the chapter as it was.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class CarteFramesTest {
    @get:Rule
    val compose = createComposeRule()

    // The composables run since the last step, by the source info the compiler gives each one.
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

    /** The binder's tab that sticks out (38 px wide on the board, the others 30): the chapter in view. */
    private fun currentTab(): String? {
        val dp = compose.onRoot().fetchSemanticsNode().size.width / 412f
        return compose.onAllNodes(SemanticsMatcher("a tab of the index") { n -> n.config.getOrNull(SemanticsProperties.ContentDescription) != null && n.config.getOrNull(SemanticsActions.OnClick) != null })
            .fetchSemanticsNodes()
            .filter { it.size.height in (44 * dp).toInt()..(48 * dp).toInt() && it.size.width in (36 * dp).toInt()..(40 * dp).toInt() }
            .firstOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun aLinePassingWithinAChapterRecomposesNotTheScreen() {
        compose.setContent { AtlasRoot(EatFixtures.services(), BackStackNavigator(listOf(Dest.Home(), Dest.Eat("carte"))), dark = false) }
        compose.settle(30_000)
        val list = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).fetchSemanticsNodes()
            .maxBy { it.size.height }
        val scrollTo = list.config[SemanticsActions.ScrollToIndex].action!!

        Composer.setTracer(tracer)
        var screen = 0
        var changes = 0
        var tab = currentTab()
        val steps = (1..STEPS).map { i ->
            runs.clear()
            compose.runOnIdle { scrollTo(i) }
            compose.waitForIdle()
            val now = currentTab()
            if (now != tab) changes++
            tab = now
            val n = runs.count { it.startsWith("app.atlas.ui.eat.CarteScreen ") }
            screen += n
            Triple(i, now, n)
        }
        Composer.setTracer(null)

        println("La Carte scrolled $STEPS lines: the screen recomposed $screen times, the chapter in view changed $changes times; $steps")
        assertTrue("the menu has chapters to pass: $steps", changes >= 2)
        assertTrue("the screen recomposed $screen times for $changes changes of chapter: $steps", screen <= changes)
    }

    private companion object {
        const val STEPS = 40
    }
}
