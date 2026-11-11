package app.atlas.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.atlas.places.Categories
import app.atlas.testing.shotsDir
import app.atlas.ui.kit.AtlasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The home's own row as it is changed (the owner, 6 November: "Favorite re order customize?"): a long press lifts a
 * button and it follows the finger along the row, the row scrolling on under it at its end, and lands where it is let
 * go; while the row is changed each button has a "−" that takes it out, and the row ends in "+", Atlas's eight again
 * where it is no longer theirs, and done; a screen reader moves a button or takes it out among its actions; an empty
 * row keeps the way back. Each look to `row_<name>.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class OwnRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val playground = "preset:leisure/playground"
    private val ah = HomeRow.CHAIN + "albertheijn-1cf55d"

    /** What the row is told to keep, kept here as the settings would keep it. */
    private class Kept(ids: List<String>, editing: Boolean = false, selected: String? = null) {
        var ids by mutableStateOf(ids)
        var editing by mutableStateOf(editing)
        var selected by mutableStateOf(selected)
        var added = 0
    }

    /** The row as the home lays it out, 12 dp in from the screen's sides, over a stretch of the map's paper. */
    private fun show(k: Kept, dark: Boolean = false) {
        compose.setContent {
            AtlasTheme(dark = dark) {
                Box(Modifier.width(412.dp).background(if (dark) Color(0xFF1B1D22) else Color(0xFFEFE9DF)).padding(horizontal = 12.dp, vertical = 24.dp)) {
                    OwnRow(
                        k.ids.mapNotNull(HomeRow::resolve), k.selected, { k.selected = it.id },
                        editing = k.editing, onEditing = { k.editing = it },
                        onOrder = { k.ids = it }, onRemove = { id -> k.ids = k.ids - id },
                        onAdd = { k.added++ }, onReset = if (k.ids != HOME_CATEGORIES) ({ k.ids = HOME_CATEGORIES }) else null,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun name(id: String) = Categories.byId(id)!!.name("en")

    private fun capture(name: String) = compose.onRoot().captureRoboImage(File(shotsDir, "row_$name.png").absolutePath)

    private val slot get() = with(compose.density) { 52.dp.toPx() }

    @Test
    fun aLongPressDragsAButtonAlongTheRow() {
        val k = Kept(HOME_CATEGORIES)
        show(k)
        compose.onNodeWithContentDescription(name("eat")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) {
                moveBy(Offset(slot / 10, 0f))
                advanceEventTime(16)
            }
            up()
        }
        compose.waitForIdle()
        assertTrue("a long press starts the change", k.editing)
        assertEquals(listOf("groceries", "shops", "eat") + HOME_CATEGORIES.drop(3), k.ids)
        assertEquals("the finger let go is no press", null, k.selected)
    }

    @Test
    fun heldAtTheEndTheRowScrollsOnUnderIt() {
        val ids = HOME_CATEGORIES + listOf(playground, "preset:amenity/bench", "cat:toilets", ah)
        val k = Kept(ids)
        show(k)
        // from 34 dp in to 384, in the last 40 dp of the 412 dp screen, and held there while the row scrolls on
        val far = with(compose.density) { 350.dp.toPx() }
        compose.onNodeWithContentDescription(name("eat")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) {
                moveBy(Offset(far / 20, 0f))
                advanceEventTime(16)
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithContentDescription(name("eat")).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("carried to the row's far end: ${k.ids}", ids.lastIndex, k.ids.indexOf("eat"))
    }

    @Test
    fun theMinusTakesOneOutAndTheEndsButtonsAddResetAndEnd() {
        val k = Kept(HOME_CATEGORIES, editing = true)
        show(k)
        // Atlas's eight already: no way back to them to offer
        assertTrue(compose.onAllNodesWithDescription("Reset your row").isEmpty())
        // the row's end lies past the screen's edge: scrolled to, as a thumb would
        compose.onNodeWithContentDescription("Add to your row").performScrollTo().performClick()
        assertEquals(1, k.added)
        compose.onNodeWithContentDescription("Take ${name("water")} out of your row").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse("water" in k.ids)
        compose.onNodeWithContentDescription("Reset your row").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(HOME_CATEGORIES, k.ids)
        // done stays in view at the row's right end, the rest scrolling beside it
        compose.onNodeWithContentDescription("Done").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertFalse(k.editing)
        assertTrue("no way out once done", compose.onAllNodesWithDescription("Take ${name("eat")} out of your row").isEmpty())
        assertTrue(compose.onAllNodesWithDescription("Add to your row").isEmpty())
    }

    @Test
    fun aScreenReaderChangesTheRowAmongTheButtonsActions() {
        val k = Kept(HOME_CATEGORIES)
        show(k)
        val eat = compose.onNodeWithContentDescription(name("eat"))
        assertEquals("Change your row", eat.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        compose.runOnIdle { eat.fetchSemanticsNode().config[SemanticsActions.OnLongClick].action!!.invoke() }
        compose.waitForIdle()
        assertTrue(k.editing)
        fun act(label: String) = compose.runOnIdle {
            compose.onNodeWithContentDescription(name("eat")).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }.action()
        }
        assertTrue(act("Move right"))
        compose.waitForIdle()
        assertEquals("eat", k.ids[1])
        assertTrue(act("Move left"))
        compose.waitForIdle()
        assertEquals("eat", k.ids[0])
        assertFalse("at the start already, it goes no further left", act("Move left"))
        act("Take ${name("eat")} out of your row")
        compose.waitForIdle()
        assertFalse("eat" in k.ids)
    }

    @Test
    fun anEmptyRowKeepsTheWayBack() {
        val k = Kept(emptyList())
        show(k)
        compose.onNodeWithContentDescription("Add to your row").assertExists()
        capture("empty")
        compose.onNodeWithContentDescription("Reset your row").performClick()
        compose.waitForIdle()
        assertEquals(HOME_CATEGORIES, k.ids)
        capture("empty_back")
    }

    /** A family, a kind and a chain side by side, the kind chosen; then a chain chosen. */
    @Test
    fun familiesKindsAndChains() {
        val k = Kept(listOf("eat", playground, ah, "groceries", "cat:toilets", "water"), selected = playground)
        show(k)
        capture("own")
        k.selected = ah
        compose.waitForIdle()
        capture("own_chain_chosen")
    }

    @Test
    fun theRowBeingChanged() {
        show(Kept(listOf("eat", playground, ah, "groceries", "water"), editing = true))
        capture("editing")
    }

    @Test
    fun theRowBeingChangedInTheDark() {
        show(Kept(listOf("eat", playground, ah, "groceries", "water"), editing = true), dark = true)
        capture("editing_dark")
    }

    /**
     * Held and dragged 1.3 places along: lifted over the others, the one it passed slid into its old place, and its middle
     * under the finger, grown as it is.
     */
    @Test
    fun aButtonLifted() {
        val k = Kept(listOf("eat", playground, ah, "groceries", "water"))
        show(k)
        val start = compose.onNodeWithContentDescription(name("eat")).fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithContentDescription(name("eat")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(13) {
                moveBy(Offset(slot / 10, 0f))
                advanceEventTime(16)
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val held = compose.onNodeWithContentDescription(name("eat")).fetchSemanticsNode().boundsInRoot
        assertEquals("under the finger", start + 1.3f * slot, held.center.x, 3f)
        assertEquals("grown", with(compose.density) { 44.dp.toPx() } * 1.12f, held.width, 2f)
        capture("lifted")
        compose.onNodeWithContentDescription(name("eat")).performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(playground, "eat", ah, "groceries", "water"), k.ids)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithDescription(d: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(d)).fetchSemanticsNodes()
}
