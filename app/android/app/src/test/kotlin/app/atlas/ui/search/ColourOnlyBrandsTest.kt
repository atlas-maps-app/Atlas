package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerPainter
import app.atlas.ui.kit.BrandSigns
import androidx.compose.ui.test.hasAnyAncestor
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

    /**
     * A deck's title is the brand's name as the street says it: "Jumbo", the table's `brand`, not its name "Jumbo
     * (Europe)", which tells the Dutch and Belgian chain from Chile's.
     */
    @Test
    fun aDecksTitleIsTheBrandsStreetName() {
        open(Dest.Category("preset:shop/supermarket"), jumbo(10, 0.001), jumbo(11, 0.0025))
        val deck = hasTestTag(DECK_TAG) and hasText("2 near", substring = true)
        compose.waitUntil(20_000) { compose.onAllNodes(deck).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue("the deck's title", compose.onAllNodes(deck and hasText("Jumbo")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no \"Jumbo (Europe)\"", compose.onAllNodes(hasText("(Europe)", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /** What the picture at the top of the one card of [name] shows ([PictureMark]), its card in view. */
    private fun pictureOf(name: String): String? {
        val card = hasTestTag(PLACE_TAG) and hasText(name)
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return compose.onNode(card).fetchSemanticsNode().config.getOrNull(PictureMark)
    }

    /**
     * As cards, a chain's fascia comes before its cuisine's house (the owner's "instead of the generic or cuisine art"):
     * a New York Pizza, which has a colour and no logo and serves pizza, is green with its name, not the Italian house.
     */
    @Test
    fun aPizzaChainsCardWearsItsFasciaNotItsCuisinesHouse() {
        open(Dest.Category("preset:amenity/fast_food"), place(40, 0.001, mapOf("amenity" to "fast_food", "cuisine" to "pizza", "name" to "New York Pizza", "brand" to "New York Pizza", "brand:wikidata" to "Q2639128")), cards = true)
        val shown = pictureOf("New York Pizza")
        capture("chain_card_colour_only")
        assertEquals("fascia:newyorkpizza-29392a", shown)
    }

    /** The same rule for a chain with a logo: a McDonald's, which serves burgers, wears its arches, not the diner's house. */
    @Test
    fun aBurgerChainsCardWearsItsFasciaNotItsCuisinesHouse() {
        open(Dest.Category("preset:amenity/fast_food"), place(41, 0.001, mapOf("amenity" to "fast_food", "cuisine" to "burger", "name" to "McDonald's", "brand" to "McDonald's", "brand:wikidata" to "Q38076")), cards = true)
        val shown = pictureOf("McDonald's")
        capture("chain_card_logo")
        assertEquals("fascia:mcdonalds-d35a75", shown)
    }

    /** Four eateries of the fast-food view: two chains whose kind and cuisine have one word, two places whose differ. */
    private val eateries = arrayOf(
        place(40, 0.001, mapOf("amenity" to "fast_food", "cuisine" to "pizza", "name" to "New York Pizza", "brand" to "New York Pizza", "brand:wikidata" to "Q2639128")),
        place(41, 0.0016, mapOf("amenity" to "fast_food", "cuisine" to "burger", "name" to "McDonald's", "brand" to "McDonald's", "brand:wikidata" to "Q38076")),
        place(42, -0.001, mapOf("amenity" to "fast_food", "cuisine" to "kebab", "name" to "Snackbar Ali")),
        place(43, -0.0016, mapOf("amenity" to "fast_food", "cuisine" to "sandwich", "name" to "Broodje Bert")),
        place(44, 0.0022, mapOf("amenity" to "fast_food", "cuisine" to "italian;pizza", "name" to "Pizzeria Roma")),
        place(45, -0.0022, mapOf("amenity" to "fast_food", "name" to "Snackbar De Hoek", "takeaway" to "only")),
    )

    /** The text of the one place [name] names, as its merged semantics carry it: its name, its line, its minutes. */
    private fun textOf(name: String): String {
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty()) compose.onNode(hasTestTag(CARDS_TAG)).performScrollToNode(row(name))
        compose.waitUntil(20_000) { compose.onAllNodes(row(name)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return compose.onNode(row(name)).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)!!.joinToString(" / ") { it.text }
    }

    private val eaterNames = listOf("New York Pizza", "McDonald's", "Snackbar Ali", "Broodje Bert", "Pizzeria Roma", "Snackbar De Hoek")

    /**
     * A card's line does not say one word twice: where a fact in words is the kind's own word, as a pizza place's cuisine
     * is, the kind says it once in its place (pictures 9 and 10 read "Pizza · Pizza", "Burger · Burger"). A card whose
     * kind and facts differ reads as before, to the letter.
     */
    @Test
    fun aCardsLineSaysAWordOnce() {
        open(Dest.Category("preset:amenity/fast_food"), *eateries, cards = true)
        val lines = eaterNames.map { textOf(it) }
        assertEquals(
            listOf(
                "New York Pizza / Pizza",
                "McDonald's / Burger",
                "Snackbar Ali / Kebab",
                "Broodje Bert / Sandwich",
                "Pizzeria Roma / Pizza · Italian",
                "Snackbar De Hoek / takeaway only",
            ).joinToString("\n"),
            lines.joinToString("\n"),
        )
    }

    /** A row's line the same: its facts in words, then its kind, the kind's own word said once. */
    @Test
    fun aRowsLineSaysAWordOnce() {
        open(Dest.Category("preset:amenity/fast_food"), *eateries)
        val lines = eaterNames.map { textOf(it) }
        assertEquals(
            listOf(
                "New York Pizza / Pizza · 110 m / 2′",
                "McDonald's / Burger · 180 m / 3′",
                "Snackbar Ali / Kebab · 110 m / 2′",
                "Broodje Bert / Sandwich · 180 m / 3′",
                "Pizzeria Roma / Italian · Pizza · 240 m / 4′",
                "Snackbar De Hoek / takeaway only · 240 m / 4′",
            ).joinToString("\n"),
            lines.joinToString("\n"),
        )
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

    /**
     * The letter on a sign with no logo is the name's first letter or digit, upper case, else "?" ([BrandSigns.letter]),
     * the rule the map's painter draws a pin's by: "'t Zusje" a T, where the row wrote the apostrophe.
     */
    @Test
    fun aSignsLetterIsTheNamesFirstLetterOrDigit() {
        val letters = mapOf(
            "'t Zusje" to "T", "(A)ngelo" to "A", "#1 Kapsalon" to "1", "★★" to "?", "" to "?", "   " to "?",
            "jumbo" to "J", "'9 Straatjes" to "9", "Albert Heijn" to "A",
        )
        for ((name, letter) in letters) assertEquals("the letter of \"$name\"", letter, BrandSigns.letter(name))
    }

    /**
     * A row and its pin wear the same letter: the row's sign writes [BrandSigns.letter] of the name, and the painter's pin
     * drawn from the name is the pin drawn from that letter alone, pixel for pixel; so either side's rule moving by
     * itself fails here.
     */
    @Test
    fun aRowAndItsPinWearTheSameLetter() {
        var name by androidx.compose.runtime.mutableStateOf("'t Zusje")
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.testTag("sign")) {
                app.atlas.ui.kit.BrandSign(null, name, ROW_TILE)
            }
        }
        fun pin(label: String): android.graphics.Bitmap {
            val side = 120
            val b = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
            MarkerPainter(2f).draw(android.graphics.Canvas(b), Marker("p", here, "shop", 0xFF5A5F6C, MarkerStyle.BRAND, label = label, logo = "nobrand"), side / 2f, side / 2f, 0)
            return b
        }
        for (n in listOf("'t Zusje", "(A)ngelo", "#1 Kapsalon", "★★", "jumbo", "'9 Straatjes")) {
            val letter = BrandSigns.letter(n)
            compose.runOnIdle { name = n }
            compose.waitForIdle()
            compose.onNode(hasTestTag("sign")).assertExists()
            assertTrue("the row of \"$n\" writes $letter", compose.onAllNodes(hasText(letter) and hasAnyAncestor(hasTestTag("sign")), useUnmergedTree = true).fetchSemanticsNodes().size == 1)
            assertTrue("the pin of \"$n\" is the pin of $letter", pin(n).sameAs(pin(letter)))
        }
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
