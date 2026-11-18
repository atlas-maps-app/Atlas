package app.atlas.ui.mine

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.services.SettingsKeys
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two rows that open the Map look, Settings' and the Layers sheet's, say the same: the look's name, then the theme
 * where it isn't Auto (the app lead, 17 November).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MapLookRowsTest {
    @get:Rule
    val compose = createComposeRule()

    /** A phone whose map draws the saved palette Canal evening, taken whole as a tap takes it, in [theme]. */
    private fun phone(theme: String?): Services = MineFixtures.withPalettes(testServices(places = false).apply { keepFixtureForecast() }, drawn = false).also { s ->
        runBlocking {
            s.settings.edit { m ->
                MapLookWrites.take(m, MineFixtures.CANAL_EVENING, MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES]).single().code)
                if (theme == null) m.remove(SettingsKeys.MAP_THEME) else m[SettingsKeys.MAP_THEME] = theme
            }
        }
    }

    private fun says(dest: Dest, services: Services, value: String) {
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home(), dest))) }
        compose.settle(30_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(value)).performScrollTo().assertExists()
    }

    @Test
    fun settingsSaysTheLookAndTheTheme() = says(Dest.Settings(), phone("NIGHT"), "Canal evening · Night")

    @Test
    fun layersSaysTheLookAndTheTheme() = says(Dest.Layers, phone("NIGHT"), "Canal evening · Night")

    @Test
    fun settingsSaysTheLookAloneWhereTheThemeIsAuto() = says(Dest.Settings(), phone(null), "Canal evening")
}
