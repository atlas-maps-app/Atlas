package app.atlas.ui.search

import android.os.Looper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.TravelMode
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A row reads its place where the place is (the app maintainer, 18 November 2026, batch 4 B18): a saved place's hours
 * and an online find's with the holidays of its own country, its state's, on its own clock, and a typed place's chain
 * the one of its own country. They read the phone's: on a Dutch phone a saved café in Munich was open on Epiphany, a
 * bar in Tokyo closed at its 16:30, and an Aral in Berlin wore no Aral.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RowsWhereThePlaceIsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = Services.WESTERKERK

    /** Tuesday 6 January 2026, 08:30 in Amsterdam and Munich, 16:30 in Tokyo: Epiphany in Bavaria, no holiday at home. */
    private val epiphany = Clock.fixed(Instant.parse("2026-01-06T07:30:00Z"), ZoneId.of("Europe/Amsterdam"))

    private val munich = Place(
        OsmId(OsmType.NODE, 9_301), LatLon(48.1374, 11.5755),
        mapOf("name" to "Café am Dom", "amenity" to "cafe", "addr:country" to "DE", "addr:state" to "Bayern", "opening_hours" to "Mo-Sa 08:00-18:00; PH off"),
    )
    private val tokyo = Place(OsmId(OsmType.NODE, 9_302), LatLon(35.6938, 139.7034), mapOf("name" to "Bar Shinjuku", "amenity" to "bar", "opening_hours" to "Mo-Su 10:00-23:00"))

    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    /** A row whose merged text holds each of [texts]. */
    private fun row(vararg texts: String): SemanticsMatcher = texts.map { hasText(it) }.reduce { a, b -> a and b }

    private fun typed(s: Services, vararg items: SearchItem.Online) {
        val vm = SearchViewModel(s, "typed", null, { here }, "en", search = { t, _ -> SearchResults(t, items.toList(), usedOnline = true) }, online = { true })
        until { vm.state.value.results != null && !vm.state.value.searching }
        val now = LocalDateTime.now(s.clock)
        compose.setContent {
            ProvideServices(s) {
                LazyColumn { typedPane(vm.state.value, vm, "typed", here, TravelMode.WALK, "en", now, BackStackNavigator(listOf(Dest.Home())), {}, {}) }
            }
        }
    }

    @Test
    fun aSavedPlaceReadsItsOwnHolidaysAndClock() {
        val s = testServices(clock = epiphany)
        s.saved.save(munich)
        s.saved.save(tokyo)
        compose.setContent { ProvideServices(s) { ListsScreen(Dest.Lists(SavedPlaces.DEFAULT_LIST), BackStackNavigator(listOf(Dest.Home()))) } }
        compose.waitUntil(10_000) { compose.onAllNodes(row("Bar Shinjuku")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Epiphany in Bavaria, as the café's own state keeps it: shut until tomorrow; 16:30 in Tokyo: open
        compose.onNode(row("Café am Dom") and hasText("opens", substring = true)).assertExists()
        compose.onNode(row("Bar Shinjuku", "open")).assertExists()
        s.close()
    }

    @Test
    fun anOnlineFindReadsItsOwnClock() {
        val s = testServices(clock = epiphany)
        typed(s, SearchItem.Online(Place(null, tokyo.location, tokyo.tags, source = "photon"), 110.0, "ui.pin", 1.0))
        compose.waitForIdle()
        compose.onNode(row("Bar Shinjuku")).assertExists()
        compose.onNode(hasText("open until 23:00", substring = true)).assertExists()
        s.close()
    }

    /**
     * An Aral in Berlin, named only "Aral", wears Aral's sign as one that names Germany does: the two rows' tiles are
     * alike pixel for pixel (between the panel's rounded corners, the rows' texts the same).
     */
    @Test
    fun aTypedPlaceWearsTheChainOfItsOwnCountry() {
        val s = testServices(clock = epiphany)
        val aral = mapOf("name" to "Aral", "amenity" to "fuel")
        val label = mapOf("label" to "Alexanderplatz")
        typed(
            s,
            SearchItem.Online(Place(null, LatLon(52.5219, 13.4132), aral, source = "photon", extra = label), 110.0, "ui.pin", 1.0),
            SearchItem.Online(Place(null, LatLon(52.5229, 13.4132), aral + ("addr:country" to "DE"), source = "photon", extra = label), 110.0, "ui.pin", 0.9),
        )
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        val rows = compose.onAllNodes(hasTestTag(PLACE_TAG))
        val a = rows[0].captureToImage().toPixelMap()
        val b = rows[1].captureToImage().toPixelMap()
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val corner = with(compose.density) { PANEL_RADIUS.roundToPx() }
        var differ = 0
        for (y in corner until a.height - corner) for (x in 0 until a.width) if (a[x, y] != b[x, y]) differ++
        assertEquals("pixels unlike between the Aral named only so and the one that names Germany", 0, differ)
        s.close()
    }
}
