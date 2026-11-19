package app.atlas.ui.mine.pump

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.search.MemoryPlaces
import app.atlas.testing.Fixtures
import app.atlas.testing.keepFixtureForecast
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * A station named only by its chain wears the chain's sign where the chain trades (the app maintainer, 18 November
 * 2026, batch 4 D, report 2's Left 5): the pump's rows matched the brand in no country, so an Aral in Berlin, Aral
 * trading in Germany and Luxembourg, showed its grade's name in the box where Aral's sign goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PumpBrandTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aStationNamedByItsChainWearsItsSignWhereItTrades() {
        val berlin = LatLon(52.5219, 13.4132)
        val aral = Place(
            OsmId(OsmType.NODE, 9_401), LatLon(berlin.lat + 0.002, berlin.lon),
            mapOf("amenity" to "fuel", "name" to "Aral", "fuel:e10" to "yes", "fuel:diesel" to "yes", "opening_hours" to "24/7"),
        )
        val s = Services(
            RuntimeEnvironment.getApplication(),
            ServicesConfig(
                root = Files.createTempDirectory("atlas-pump").toFile(), demoLocation = berlin, offline = true, mapEngine = false,
                placeStores = listOf(MemoryPlaces(listOf(aral), country = null)), clock = Fixtures.evening,
            ),
        ).also { it.keepFixtureForecast() }
        compose.setContent { AtlasRoot(s, BackStackNavigator(listOf(Dest.Home(), Dest.Pump(berlin.lat, berlin.lon))), dark = false) }
        val row = hasText("Aral") and hasClickAction()
        compose.waitUntil(20_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Aral's sign in the box, no grade's name there
        compose.onNode(row).assert(!hasText(Fuel.E10.label) and !hasText(Fuel.DIESEL.label))
        s.close()
    }
}
