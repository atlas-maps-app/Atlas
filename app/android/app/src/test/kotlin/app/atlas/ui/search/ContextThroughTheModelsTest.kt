package app.atlas.ui.search

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapStage
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.places.CategoryTree
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import app.atlas.ui.local.PicnicScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The country and the holidays a screen reads its places in come from the model that makes them, as the app takes them
 * (the app maintainer, 18 November 2026, batch 4 C26): the reviewer's findings 7 to 9 were held by tests that passed
 * the context by hand, which a model making it without the country, or a screen passing another, would pass. On King's
 * Day around the Westerkerk, a holiday the Netherlands alone keeps: the sheet's tiles and Picnic's cards count a place
 * shut on holidays as shut, and the home pins a saved supermarket named only "Albert Heijn" with Albert Heijn's sign, a
 * chain of the Netherlands and Belgium alone, and a fuel station named only "Aral" as a fuel station, Aral being a
 * German chain: with no country the first wore no sign, with Germany's the second wore Aral's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextThroughTheModelsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = Services.WESTERKERK

    /** King's Day 2026, a Monday, at noon in Amsterdam. */
    private val kingsDay: Clock = Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), ZoneId.of("Europe/Amsterdam"))

    private fun place(id: Long, dLat: Double, vararg tags: Pair<String, String>) =
        Place(OsmId(OsmType.NODE, 9_500 + id), LatLon(here.lat + dLat, here.lon), mapOf(*tags))

    /** Runs the main looper, up to [ms], until [done]; whether it came. */
    private fun until(ms: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        return done()
    }

    /** Every text on the screen. */
    private fun texts(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /**
     * Two benches either side, so that a test's store holds the Westerkerk ([app.atlas.search.covers]): what is read
     * there is the test's places, not the bundled pack's.
     */
    private val benches = listOf(LatLon(here.lat - 0.001, here.lon - 0.001), LatLon(here.lat + 0.002, here.lon + 0.001))
        .mapIndexed { i, p -> Place(OsmId(OsmType.NODE, 9_504L + i), p, mapOf("amenity" to "bench")) }

    /** The sheet's moment ([SearchSheetModel]): its drinks tile counts a bar shut on holidays as shut. */
    @Test
    fun theSheetsTilesKeepKingsDay() {
        val bar = place(1, 0.001, "amenity" to "bar", "name" to "Kroeg", "opening_hours" to "Mo-Sa 10:00-23:00; PH off")
        val s = testServices(clock = kingsDay, stores = listOf(MemoryPlaces(listOf(bar) + benches, country = null)))
        val m = SearchSheetModel(s, here, "en")
        m.moveTo(here)
        val drinks = CategoryTree.category("drinks")!!.id
        assertTrue("the grid came", until { m.state.value.gridOrigin != null && m.state.value.pulses[drinks] != null })
        // the regions are asked off the main thread, and the tiles counted again in what they say
        until(1_000) { false }
        val tile = m.state.value.pulses.getValue(drinks)
        assertEquals(1, tile.count)
        assertEquals("open on King's Day, read in ${m.state.value.moment.hours.country}", 0, tile.open)
        s.close()
    }

    /** Picnic's cards ([PicnicScreen], read in the hours where it looks): a playground shut on holidays says when it opens. */
    @Test
    fun picnicKeepsKingsDay() {
        val play = place(2, 0.001, "leisure" to "playground", "name" to "Speeltuin", "opening_hours" to "Mo-Sa 09:00-18:00; PH off")
        val s = testServices(clock = kingsDay, stores = listOf(MemoryPlaces(listOf(play) + benches, country = null)))
        compose.setContent { ProvideServices(s) { PicnicScreen(Dest.Picnic(), BackStackNavigator(listOf(Dest.Home()))) } }
        compose.waitUntil(20_000) { "Speeltuin" in texts() }
        // the hours where it looks are asked of the regions off the main thread
        until(2_000) { texts().any { "closed · opens" in it } }
        val said = texts()
        assertTrue("$said", said.any { "closed · opens" in it })
        assertFalse("$said", said.any { "open until" in it })
        s.close()
    }

    /**
     * The home's pins ([app.atlas.ui.home.HomeScreen], in the country of the sheet's moment): a saved fuel station named
     * only "Aral" is pinned as a fuel station, Aral trading in Germany and Luxembourg alone.
     */
    @Test
    fun theHomePinsAPlaceInTheCountryWhereItIs() {
        val ah = place(6, -0.002, "shop" to "supermarket", "name" to "Albert Heijn")
        val aral = place(3, 0.002, "amenity" to "fuel", "name" to "Aral")
        val s = testServices(clock = kingsDay)
        s.saved.save(ah)
        s.saved.save(aral)
        lateinit var stage: MapStage
        compose.setContent {
            stage = remember { MapStage() }
            CompositionLocalProvider(LocalMapStage provides stage) { AtlasRoot(s, BackStackNavigator(listOf(Dest.Home())), dark = false) }
        }
        fun pin(p: Place) = stage.shown()?.overlays?.markers?.firstOrNull { it.place?.key == p.key }
        assertTrue("the saved places were pinned", until { compose.waitForIdle(); pin(ah) != null && pin(aral) != null })
        // the sheet's moment asks the regions off the main thread, and the pins are drawn again in what they say
        until(2_000) { false }
        compose.waitForIdle()
        assertEquals("logo-albertheijn", pin(ah)!!.symbol)
        val symbol = pin(aral)!!.symbol
        assertFalse("pinned as $symbol", symbol.startsWith("logo-"))
        s.close()
    }
}
