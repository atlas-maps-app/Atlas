package app.atlas.ui.mine

import android.view.Display
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import app.atlas.Services
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * Every control a person can press on the mine screens says what it is to a screen reader: each clickable node has text or
 * a content description once its children are merged into it. An icon alone, with nothing said, is a button TalkBack reads
 * as "button" and nothing more.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class MineAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private fun phone(): Services = testServices(places = true).apply { keepFixtureForecast() }

    /** Shows [dest] and returns what is pressable on it without a name. */
    private fun unnamed(dest: Dest, services: Services = phone()): List<String> {
        compose.setContent { AtlasRoot(services, BackStackNavigator(listOf(Dest.Home(), dest)), dark = false) }
        compose.settle(30_000)
        repeat(3) { compose.waitForIdle(); Thread.sleep(150) }
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { n ->
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("").orEmpty()
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            cd.isBlank() && text.isBlank()
        }.map { "${it.boundsInRoot} ${it.config.map { (k, v) -> "${k.name}=$v" }}" }
    }

    private fun check(name: String, dest: Dest, services: Services = phone()) {
        val bad = unnamed(dest, services)
        assertTrue("$name has pressable things with no name: $bad", bad.isEmpty())
    }

    @Test fun offline() {
        val s = phone()
        val at = s.clock.instant().minus(Duration.ofHours(2))
        MineFixtures.region(s, "Amsterdam", MineFixtures.AMSTERDAM, mapMb = 410, placesMb = 180, routeMb = 310, at = at, voices = listOf("en", "nl"))
        s.mine.useDeps(MineFixtures.offlineDeps(s, s.clock.instant()))
        s.mine.engine.refresh()
        check("Offline", Dest.Offline, s)
    }

    @Test fun desk() = check("Mine", Dest.Mine)
    @Test fun settings() = check("Settings", Dest.Settings())
    @Test fun privacy() = check("Privacy", Dest.Privacy)
    @Test fun osmAccount() = check("OpenStreetMap account", Dest.OsmAccount)
    @Test fun about() = check("About", Dest.About)
    @Test fun newPhone() = check("New phone", Dest.NewPhone)
    @Test fun watch() = check("Watch", Dest.Watch)
    @Test fun weather() = check("Weather", Dest.Weather())
    @Test fun layers() {
        check("Layers", Dest.Layers)
        // the test's display has one mode, 60 Hz: no Fast refresh among the engine parts, as neither vote does anything
        compose.onAllNodesWithContentDescription("Fast refresh").assertCountEquals(0)
    }

    /** On a display with a mode above 60 Hz the engine parts' last row ends with the Fast refresh, named as the rest. */
    @Test fun layersFastRefresh() {
        val mode = { id: Int, hz: Float -> ShadowDisplayManager.ModeBuilder.modeBuilder(id).setWidth(824).setHeight(1830).setRefreshRate(hz).build() }
        ShadowDisplayManager.setSupportedModes(Display.DEFAULT_DISPLAY, mode(0, 60f), mode(1, 120f)) // 0: the mode it runs
        check("Layers (120 Hz)", Dest.Layers)
        compose.onNodeWithContentDescription("Fast refresh").assertHasClickAction()
    }
    @Test fun mapLook() = check("Map look", Dest.MapLook(), MineFixtures.withPalettes(phone(), drawn = false))
    @Test fun colourMap() = check("Colour the map", Dest.ColourMap, MineFixtures.withPalettes(phone(), drawn = true))

    @Test fun dataSources() {
        val s = phone()
        runBlocking { s.keys.set("foursquare", "fsq-test-key") }
        check("Data sources", Dest.DataSources(provider = "foursquare"), s)
    }

    @Test fun satellites() {
        val s = phone()
        s.location.use(MineFixtures.GnssPhone(Services.WESTERKERK, MineFixtures.sky()))
        check("Satellites", Dest.Satellites, s)
    }

    @Test fun charge() = check("Charge", Dest.Charge(Services.WESTERKERK.lat, Services.WESTERKERK.lon), MineFixtures.phoneWithRoadside())
    @Test fun chargeCard() = check("Charge card", Dest.Charge(Services.WESTERKERK.lat, Services.WESTERKERK.lon, select = "n9000001"), MineFixtures.phoneWithRoadside())
    @Test fun pump() = check("Pump", Dest.Pump(Services.WESTERKERK.lat, Services.WESTERKERK.lon), MineFixtures.phoneWithRoadside())
    @Test fun power() = check("Power", Dest.Power, MineFixtures.phoneWithRoadside())
    @Test fun water() = check("Water", Dest.Water(Services.WESTERKERK.lat, Services.WESTERKERK.lon), MineFixtures.phoneWithRoadside())
    @Test fun welcome1() = check("Welcome 1", Dest.Onboarding(0))
    @Test fun welcome2() = check("Welcome 2", Dest.Onboarding(1), phone().also { it.mine.useDeps(MineFixtures.packDeps(it, it.clock.instant())) })
    @Test fun welcome3() = check("Welcome 3", Dest.Onboarding(2))
    @Test fun pickerArea() = check("Picker", Dest.RegionPicker(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "area"), phone().also { it.mine.useDeps(MineFixtures.packDeps(it, it.clock.instant())) })
    @Test fun pickerCountry() = check("Picker (country)", Dest.RegionPicker(mode = "country"), phone().also { it.mine.useDeps(MineFixtures.packDeps(it, it.clock.instant())) })
}
