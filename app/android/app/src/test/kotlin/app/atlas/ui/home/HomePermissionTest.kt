package app.atlas.ui.home

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.testServices
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The location asked for on the Map home, and allowed elsewhere (the system settings) while the app waited. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class HomePermissionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allowedInTheSettingsTheCardGoesOnReturn() {
        val services = testServices(demo = false)
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Allow").assertExists()
        // "Allow" sent the user to the app's settings, where they allowed the location; then they come back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // The answer travels through the location source's flows off the main thread.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Allow").assertDoesNotExist()
    }
}
