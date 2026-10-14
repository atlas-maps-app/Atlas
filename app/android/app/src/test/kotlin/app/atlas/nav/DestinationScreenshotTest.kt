package app.atlas.nav

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every destination CI opens, rendered through the real navigation with the test services, to a PNG. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class DestinationScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renders() {
        // The Amsterdam place store and a kept forecast, so each screen shows what it would at the Westerkerk.
        val services = testServices(places = true).apply { keepFixtureForecast() }
        val stack = CiScreens.stackFor(name)!!
        val nav = BackStackNavigator(stack)
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.settle()
        // The Map home reads its kept forecast off the main thread: wait for the temperature.
        if (name == "home") compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("°", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(nav.current == stack.last())
        val f = File(shotsDir, "screen_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = CiScreens.screens.keys.map { arrayOf<Any>(it) }
    }
}
