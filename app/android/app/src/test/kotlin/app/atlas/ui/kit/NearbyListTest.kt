package app.atlas.ui.kit

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.nav.ProvideServices
import app.atlas.services.NearbyPlaces
import app.atlas.services.PlaceFinder
import app.atlas.services.PlaceOrigin
import app.atlas.testing.Fixtures
import app.atlas.testing.TempFiles
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The nearby list shows what [shownAfter] makes of each answer (the app maintainer, 19 November 2026, batch 6 B9: the
 * list's one call of it taken out passed every test, as [NearbyShownTest] reads the helper alone): the packs' part with
 * three cafés, then a failure that holds one of them, shows the three, as on the category row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NearbyListTest {
    @get:Rule
    val compose = createComposeRule()

    private val centre = LatLon(52.0830, 4.3900)

    /** A café [m] metres north of the centre. */
    private fun cafe(id: Long, m: Double, name: String) =
        Place(OsmId.parse("n$id")!!, LatLon(centre.lat + m / 111_195.0, centre.lon), mapOf("amenity" to "cafe", "name" to name))

    @Test
    fun aFailureThatHoldsFewerKeepsWhatTheListShowed() {
        val names = listOf("Café Anna", "Café Bram", "Café Cor")
        val (a, b, c) = names.mapIndexed { i, n -> cafe(8_701L + i, 100.0 * (i + 1), n) }
        val answers = PlaceFinder { _, _, _, _ ->
            flowOf(NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK), NearbyPlaces(listOf(a), PlaceOrigin.PACK, "HTTP 504"))
        }
        val s = Services(
            RuntimeEnvironment.getApplication(),
            ServicesConfig(root = TempFiles.dir("atlas-nearby"), offline = true, mapEngine = false, clock = Fixtures.evening, placeFinder = answers),
        )
        compose.setContent { ProvideServices(s) { NearbyList("eat", centre, onPlace = {}) } }
        compose.waitForIdle()
        for (n in names) compose.onNodeWithText(n).assertExists()
        s.close()
    }
}
