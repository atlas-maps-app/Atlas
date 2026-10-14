package app.atlas.nav

import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The frame around the screens: the same destination twice on the stack, and going back through it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AtlasRootTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSamePlaceTwiceOnTheStackShowsAndGoesBack() {
        // The Westerkerk, then what is around it, then the Westerkerk again from that list: one route, twice.
        val place = Dest.Place(52.37452, 4.88377, "w99205257", "Westerkerk")
        val nav = BackStackNavigator(listOf(Dest.Home(), place, Dest.WhatsHere(52.37452, 4.88377), place))
        compose.setContent { AtlasRoot(testServices(), nav, dark = false) }
        compose.waitForIdle()
        compose.runOnIdle { nav.back() }
        compose.waitForIdle()
        compose.runOnIdle { nav.back() }
        compose.waitForIdle()
        assertEquals(place, nav.current)
        compose.runOnIdle { nav.go(Dest.WhatsHere(52.37452, 4.88377)) }
        compose.waitForIdle()
        compose.runOnIdle { nav.go(place) }
        compose.waitForIdle()
        assertEquals(listOf(Dest.Home(), place, Dest.WhatsHere(52.37452, 4.88377), place), nav.stack.toList())
    }
}
