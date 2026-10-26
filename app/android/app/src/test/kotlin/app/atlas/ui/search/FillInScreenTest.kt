package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.testing.shotsDir
import app.atlas.ui.kit.BoardScale
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The FillIn board's four states as the screen draws them over a dark map (the board: canvas/project/FillIn.dc.html,
 * its picture cache/pictures/img/FillIn.jpg), each to `find_fillin_<state>.png`, with the board's texts checked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class FillInScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val leliegracht = mapOf(
        "building" to "commercial", "height" to "16.1", "start_date" to "1673",
        "addr:street" to "Leliegracht", "addr:housenumber" to "49",
    )
    private val asks = FillIn.asks(leliegracht)
    private val brown = FillIn.COLOURS.first { it.id == "brown" }
    private val gabled = FillIn.ROOFS.first { it.shape == "gabled" }

    private fun show(state: FillInState, signedIn: Boolean = true, picked: MutableList<Any> = mutableListOf()) {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0D14))) {
                BoardScale {
                    FillInView(
                        state = state,
                        head = "Leliegracht 49 · built 1673 · 16.1 m",
                        elementLine = "way 266616341 · Leliegracht 49",
                        tags = leliegracht,
                        signedIn = signedIn,
                        message = null,
                        pinAt = Offset(185f, 166f),
                        onBack = {},
                        onColour = { picked += it },
                        onRoof = { picked += it },
                        onNext = {},
                        onSkip = {},
                        onRestart = {},
                        onSend = {},
                        onMore = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(shotsDir, "find_fillin_$name.png").absolutePath)

    @Test
    fun theColourQuestion() {
        val picked = mutableListOf<Any>()
        show(FillInState(asks), picked = picked)
        compose.onNodeWithText("Fill in the gaps").assertExists()
        compose.onNodeWithText("Leliegracht 49 · built 1673 · 16.1 m").assertExists()
        compose.onNodeWithText("QUESTION 1 OF 2").assertExists()
        compose.onNodeWithText("building:colour is a guess").assertExists()
        compose.onNodeWithText("What colour is the front?").assertExists()
        compose.onNodeWithText("Colour not mapped").assertExists()
        compose.onNodeWithText("1 / 2").assertExists()
        shot("colour")
        compose.onNodeWithContentDescription("Brown brick").performClick()
        assertEquals(listOf<Any>(brown), picked)
    }

    @Test
    fun theRoofQuestion() {
        show(FillInState(asks, step = 1, colour = brown))
        compose.onNodeWithText("QUESTION 2 OF 2").assertExists()
        compose.onNodeWithText("← colour").assertExists()
        compose.onNodeWithText("What shape is the roof, seen from the street?").assertExists()
        compose.onNodeWithText("Roof shape not mapped").assertExists()
        shot("roof")
    }

    @Test
    fun theReview() {
        show(FillInState(asks, step = 2, colour = brown, roof = gabled))
        compose.onNodeWithText("YOUR EDIT TO OPENSTREETMAP").assertExists()
        compose.onNodeWithText("way 266616341 · Leliegracht 49").assertExists()
        compose.onNodeWithText("+ building:colour=#5c3a30").assertExists()
        compose.onNodeWithText("+ roof:shape=gabled").assertExists()
        compose.onNodeWithText("  building=commercial").assertExists()
        compose.onNodeWithText("  … 3 more tags").assertExists()
        compose.onNodeWithText("Still guessed after this: building:levels · roof:colour · roof:height").assertExists()
        compose.onNodeWithText("Send 3 tags").assertExists()
        compose.onNodeWithText("Your edit").assertExists()
        shot("review")
    }

    @Test
    fun signedOutTheEditStaysOnThePhone() {
        show(FillInState(asks, step = 2, colour = brown, roof = gabled), signedIn = false)
        compose.onNodeWithText("Kept on this phone until you sign in to OpenStreetMap. Edits are public and show your user name.").assertExists()
    }

    @Test
    fun done() {
        show(FillInState(asks, step = 3, colour = brown, roof = gabled, sent = true))
        compose.onNodeWithText("Queued · thank you").assertExists()
        compose.onNodeWithText("Goes to OpenStreetMap on Wi-Fi: brown brick, gable to the street roof").assertExists()
        compose.onNodeWithText("Back to the living map").assertExists()
        shot("done")
    }
}
