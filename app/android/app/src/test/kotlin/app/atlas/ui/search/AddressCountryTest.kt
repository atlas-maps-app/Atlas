package app.atlas.ui.search

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * An address as the country of the map at the place writes it, where the place names none of its own (the reviewer, 17
 * November, finding 11: the rows, the place's page, a list's GPX and the search wrote every address street first,
 * "Rue de Rivoli 12"): a row's line, a list's waypoints, each in its own country, and the place's page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class AddressCountryTest {
    @get:Rule
    val compose = createComposeRule()

    private val paris = LatLon(48.8556, 2.3601)
    private val bakery = Place(OsmId.parse("n7001"), paris, mapOf("name" to "Chez Lucien", "shop" to "bakery",
        "addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:postcode" to "75004", "addr:city" to "Paris"), source = "osm")
    private val cafe = Place(OsmId.parse("n1147560517"), LatLon(52.3739012, 4.8830034), mapOf("name" to "Café Kalkhoven", "amenity" to "cafe",
        "addr:street" to "Prinsengracht", "addr:housenumber" to "283", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam"), source = "osm")

    @Test
    fun aRowWritesAnAddressAsItsCountryDoes() {
        assertEquals("12 Rue de Rivoli, 75004 Paris", addressOf(bakery, "FR"))
        assertEquals("Prinsengracht 283, 1016 GV Amsterdam", addressOf(cafe, "NL"))
    }

    @Test
    fun aListsWaypointsEachTakeTheirOwnCountry() {
        val gpx = SavedExport.gpx("Trip", listOf(bakery, cafe), Instant.parse("2026-10-13T17:30:00Z"), "en") { if (it.lat < 50) "FR" else "NL" }
        assertTrue(gpx, gpx.contains("<desc>12 Rue de Rivoli, 75004 Paris</desc>"))
        assertTrue(gpx, gpx.contains("<desc>Prinsengracht 283, 1016 GV Amsterdam</desc>"))
    }

    @Test
    fun thePlacesPageWritesItAsFranceDoes() {
        val bench = Place(OsmId.parse("n7002"), LatLon(paris.lat - 0.001, paris.lon - 0.001), mapOf("amenity" to "bench"), source = "osm")
        val s = testServices(stores = listOf(MemoryPlaces(listOf(bakery, bench), country = null)))
        val nav = BackStackNavigator(listOf(Dest.Place(paris.lat, paris.lon, "n7001", "Chez Lucien")))
        compose.setContent { AtlasRoot(s, nav, dark = false) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Rue de Rivoli", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue("as France writes it", compose.onAllNodesWithText("12 Rue de Rivoli, 75004 Paris").fetchSemanticsNodes().isNotEmpty())
        assertTrue("never street first", compose.onAllNodesWithText("Rue de Rivoli 12", substring = true).fetchSemanticsNodes().isEmpty())
        s.close()
    }
}
