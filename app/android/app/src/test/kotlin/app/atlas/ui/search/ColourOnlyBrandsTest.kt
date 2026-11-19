package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.MarkerStyle
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.places.OpeningHoursContext
import app.atlas.places.PublicHolidays
import app.atlas.search.MemoryPlaces
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.TravelMode
import app.atlas.services.SettingsKeys
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/**
 * A brand with a colour of its own and no logo wears its sign on the search's rows and pins, its first letter on its
 * colour, not its kind's symbol on its family's (the owner, 17 November: "There are brands with only a colour and name,
 * these can show their colour in search results views too, instead of the generic or cuisine art"): a place's row, a
 * chain's deck, a kind's nearest card and a pin. The Dutch Jumbo's yellow and Geldmaat's are known, their logos are
 * not bundled. A brand whose colour is not known, Ekoplaza, keeps its kind's mark: never a colour made up for it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class ColourOnlyBrandsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = Services.WESTERKERK
    private val jumbo = "jumbo-1cf55d"
    private val geldmaat = "geldmaat-5333ab"

    private fun place(id: Long, north: Double, tags: Map<String, String>) = Place(OsmId(OsmType.NODE, id), LatLon(here.lat + north, here.lon), tags)

    private fun jumbo(id: Long, north: Double) = place(id, north, mapOf("shop" to "supermarket", "name" to "Jumbo", "brand" to "Jumbo", "brand:wikidata" to "Q2262314"))

    private val ekoplaza = place(20, 0.002, mapOf("shop" to "supermarket", "name" to "Ekoplaza", "brand" to "Ekoplaza", "brand:wikidata" to "Q47017915"))

    private val cash = place(30, 0.0008, mapOf("amenity" to "atm", "name" to "Geldmaat", "brand" to "Geldmaat", "brand:wikidata" to "Q74051230", "operator" to "Geldmaat"))

    /** A bench to the south, so that the places the phone holds cover the Westerkerk between. */
    private val bench = place(99, -0.003, mapOf("amenity" to "bench"))

    private fun open(dest: Dest, vararg places: Place, dark: Boolean = false, cards: Boolean = false) {
        val s = testServices(stores = listOf(MemoryPlaces(places.toList() + bench, country = null)))
        if (cards) runBlocking { s.settings.edit { it[SettingsKeys.SEARCH_CARDS] = true } }
        compose.setContent { AtlasRoot(s, BackStackNavigator(listOf(Dest.Home(), dest)), dark = dark) }
        compose.settle(15_000)
    }

    /** Lets the map draw: capture until three frames in a row come out the same, as [SearchScreensTest] does. */
    private fun capture(name: String) {
        val end = System.currentTimeMillis() + 30_000
        var last: android.graphics.Bitmap? = null
        var same = 0
        while (System.currentTimeMillis() < end && same < 3) {
            compose.waitForIdle()
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            if (last != null && now.sameAs(last)) same++ else same = 0
            last = now
            Thread.sleep(120)
        }
        val f = File(shotsDir, "find_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
    }

    /**
     * Three supermarkets about the Westerkerk, each on its own pin: the Dutch Jumbo on its yellow with its letter, Albert
     * Heijn on its blue with its logo, Ekoplaza on its family's colour with its kind's symbol; their rows the same.
     */
    private val three = arrayOf(
        jumbo(10, 0.0012),
        place(12, 0.0, mapOf("shop" to "supermarket", "name" to "Albert Heijn", "brand" to "Albert Heijn", "brand:wikidata" to "Q1653985"))
            .copy(location = LatLon(here.lat + 0.0004, here.lon + 0.0022)),
        ekoplaza.copy(location = LatLon(here.lat - 0.0008, here.lon - 0.0018)),
    )

    /** The mark of the tile in the one node [matcher] finds: "sign:" and the brand's id, or "kind:" and a symbol. */
    private fun tileOf(matcher: SemanticsMatcher): String? {
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return compose.onNode(matcher).fetchSemanticsNode().config.getOrNull(TileMark)
    }

    private fun row(name: String) = hasTestTag(PLACE_TAG) and hasText(name)

    @Test
    fun aPlacesRowWearsTheSignOfItsBrand() {
        open(Dest.Category("preset:shop/supermarket"), jumbo(10, 0.001), ekoplaza)
        assertEquals("sign:$jumbo", tileOf(row("Jumbo")))
    }

    @Test
    fun aChainsDeckWearsItsSign() {
        open(Dest.Category("preset:shop/supermarket"), jumbo(10, 0.001), jumbo(11, 0.0025))
        assertEquals("sign:$jumbo", tileOf(hasTestTag(DECK_TAG) and hasText("2 near", substring = true)))
    }

    @Test
    fun aKindsNearestCardWearsTheSignOfItsBrand() {
        open(Dest.Category("preset:amenity/atm"), cash)
        assertEquals("sign:$geldmaat", tileOf(hasText("Geldmaat") and hasText("NEAREST", substring = true, ignoreCase = true)))
    }

    @Test
    fun aBrandWhoseColourIsNotKnownKeepsItsKindsMark() {
        open(Dest.Category("preset:shop/supermarket"), jumbo(10, 0.001), ekoplaza)
        val tile = tileOf(row("Ekoplaza"))
        assertTrue("Ekoplaza's tile: $tile", tile?.startsWith("kind:") == true)
    }

    /** The picture: the three pins over the map and their rows under it, by day. */
    @Test
    fun threeSignsOverTheMap() {
        open(Dest.Category("preset:shop/supermarket"), *three)
        val tile = tileOf(row("Jumbo"))
        capture("colour_only_signs")
        assertEquals("sign:$jumbo", tile)
    }

    /** The same at night, where a sign keeps its colour on the night's map. */
    @Test
    fun threeSignsOverTheMapAtNight() {
        open(Dest.Category("preset:shop/supermarket"), *three, dark = true)
        val tile = tileOf(row("Jumbo"))
        capture("colour_only_signs_dark")
        assertEquals("sign:$jumbo", tile)
    }

    /**
     * As cards, the picture at a card's top: a chain's fascia, its sign's colour edge to edge (the owner's "instead of
     * the generic or cuisine art"), the Jumbo's as Albert Heijn's.
     */
    @Test
    fun threeSignsAsCards() {
        open(Dest.Category("preset:shop/supermarket"), *three, cards = true)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Jumbo")).fetchSemanticsNodes().isNotEmpty() }
        capture("colour_only_cards")
    }

    /** The pins over the map, the typed search's and a kind's alike ([pinOf]): the brand's sign, its colour and its id. */
    @Test
    fun aPinWearsTheSignOfItsBrand() {
        val hours = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"), country = "NL")
        val items = listOf(jumbo(10, 0.001), ekoplaza).mapIndexed { i, p ->
            SearchItem.Online(Place(null, p.location, p.tags, source = "photon"), 110.0 + i, "ui.pin", 1.0 - i * 0.1)
        }
        val pins = typedMarkers(SearchResults("supermarket", items), LocalDateTime.of(2026, 10, 13, 18, 0), here, TravelMode.WALK, hours)
        val (yellow, eko) = pins
        assertEquals(MarkerStyle.BRAND, yellow.style)
        assertEquals(jumbo, yellow.logo)
        assertEquals(0xFFFFD500L, yellow.colour)
        // a brand whose colour is not known: its kind's pin, on its family's colour
        assertEquals(MarkerStyle.PIN, eko.style)
        assertEquals(null, eko.logo)
    }
}
