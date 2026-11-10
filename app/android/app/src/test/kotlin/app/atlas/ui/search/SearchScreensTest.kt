package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.providers.Wikidata
import app.atlas.providers.WikidataRef
import app.atlas.search.MemoryPlaces
import app.atlas.testing.Fixtures
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search package's screens through the real navigation, on the Amsterdam extract and the fixture tiles at the
 * Westerkerk on a Tuesday evening, each to `find_<name>.png`, with the key content checked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SearchScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(dest: Dest, services: Services = testServices(places = true), dark: Boolean = false): BackStackNavigator {
        val nav = BackStackNavigator(listOf(Dest.Home(), dest))
        compose.setContent { AtlasRoot(services, nav, dark = dark) }
        compose.settle(15_000)
        return nav
    }

    /**
     * Lets the map draw: Robolectric draws a frame only when the screen is captured, so capture until three frames in a
     * row come out the same (the tiles are in, the camera has stopped).
     */
    private fun settleMap(timeoutMillis: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMillis
        var last: android.graphics.Bitmap? = null
        var same = 0
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val now = compose.onRoot().captureToImage().asAndroidBitmap()
            if (last != null && now.sameAs(last)) {
                if (++same >= 3) return
            } else {
                same = 0
            }
            last = now
            Thread.sleep(120)
        }
    }

    private fun capture(name: String): File {
        settleMap()
        val f = File(shotsDir, "find_$name.png")
        compose.onRoot().captureRoboImage(f.absolutePath)
        assertTrue(f.isFile && f.length() > 1000)
        return f
    }

    private fun waitFor(text: String, timeout: Long = 15_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun searchCafes() {
        open(Dest.Search("cafe"))
        waitFor("Kalkhoven")
        // The chips that follow a kind of place, and the open status in words.
        compose.onNodeWithText("Open now", substring = true).assertExists()
        capture("search_cafe")
    }

    @Test
    fun searchPharmacyAndBack() {
        val nav = open(Dest.Search("apotheek"))
        waitFor("BENU")
        capture("search_pharmacy")
        compose.onNodeWithText("BENU").performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.Place)
    }

    @Test
    fun searchEmptyWithRecents() {
        val services = testServices(places = true)
        val kalkhoven = services.placeStores.get(OsmId.parse("n1147560517")!!)!!.place
        services.saved.addRecent("9F469VFP+R4")
        services.saved.addRecent("Café Kalkhoven", kalkhoven)
        services.saved.addRecent("52.3745, 4.8838")
        services.saved.save(kalkhoven)
        val nav = open(Dest.Search(), services)
        compose.onNodeWithText("52.3745, 4.8838").assertExists()
        // the one search's collection of every kind of place, under the recents
        waitFor("Every kind")
        capture("search_empty")
        // The ways to find where they are used: the wheel first of the hero's picks, a list among what is yours, the
        // brands around under the kinds, the quests last.
        compose.onNodeWithContentDescription("Where to?").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Where, nav.current)
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithText("Saved").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Dest.Lists(SavedPlaces.DEFAULT_LIST), nav.current)
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithTag(GRID_TAG).performScrollToKey("brands")
        capture("search_brands")
        // A kind's chains share a stack: pressed, it is dealt out, its kind's disc first, which stacks it again.
        val stack = hasTestTag(BRAND_STACK_TAG)
        val stacks = compose.onAllNodes(stack).fetchSemanticsNodes()
        assertTrue("stacks: ${stacks.size}", stacks.isNotEmpty())
        val said = stacks.first().config[SemanticsProperties.ContentDescription].first()
        val top = said.substringAfter(": ").substringBefore(", ")
        compose.onAllNodes(stack).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).assertExists()
        assertEquals(stacks.size - 1, compose.onAllNodes(stack).fetchSemanticsNodes().size)
        assertTrue("$top dealt", compose.onAllNodes(hasContentDescription("$top, ", substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        capture("search_brands_dealt")
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).assertDoesNotExist()
        assertEquals(stacks.size, compose.onAllNodes(stack).fetchSemanticsNodes().size)
        compose.onNodeWithText("All brands").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Dest.Brands(), nav.current)
        compose.runOnUiThread { nav.back() }
        // the quests, the grid's last line, are counted as the grid is scrolled down towards them
        compose.onNodeWithTag(GRID_TAG).performScrollToKey("quests")
        waitFor("Map quests", 20_000)
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(hasText("MAP QUESTS"))
        capture("search_quests")
        compose.onNodeWithText("MAP QUESTS").performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.Quests)
        // A coordinate typed before opens what is there.
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(hasText("52.3745, 4.8838"))
        compose.onNodeWithText("52.3745, 4.8838").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.WhatsHere)
    }

    @Test
    fun query() {
        open(Dest.Query("cafe near bicycle rental 200m"))
        waitFor("Kalkhoven")
        compose.onNodeWithContentDescription("Overpass QL of this query").assertExists()
        capture("query")
        compose.onNodeWithContentDescription("Overpass QL of this query").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("out", substring = true).assertExists()
        capture("query_ql")
    }

    /** The providers' recorded answers from Wikidata ([name]'s "entities"). */
    private fun recorded(name: String) =
        Json.parseToJsonElement(File(System.getProperty("atlas.repo")!!, "app/android/providers/src/test/resources/fixtures/$name").readText()).jsonObject["entities"]!!.jsonObject

    /** Wikidata's Westerkerk as fetched once before (the providers' recorded answers), kept on the test phone. */
    private fun keepWesterkerkFacts(services: Services) {
        val labels = recorded("wd_labels.json").mapValues { (_, v) ->
            v.jsonObject["labels"]?.jsonObject?.mapValues { it.value.jsonObject["value"]!!.jsonPrimitive.content }.orEmpty()
        }
        val e = Wikidata.parseEntity(recorded("wd_entities.json")["Q1130722"]!!.jsonObject)
        fun named(refs: List<WikidataRef>) = refs.map { r -> WikidataRef(r.id, labels[r.id].orEmpty(), r.descriptions) }
        KeptFacts(File(services.dirs.files, "search/wikidata")).keep(e.copy(architects = named(e.architects), styles = named(e.styles), namedAfter = named(e.namedAfter)))
    }

    /**
     * Wikidata's Albert Heijn as fetched once before (the providers' recorded answer), kept on the test phone. The
     * recording holds the item alone, not the names of the items it links to: the company it belongs to (Q20539261) and
     * the town of its head office (Q211260) are named here as Wikidata names them, as the app's second call would.
     */
    private fun keepAlbertHeijnFacts(services: Services) {
        val e = Wikidata.parseEntity(recorded("wd_entities.json")["Q1653985"]!!.jsonObject)
        val names = mapOf("Q20539261" to "Ahold Delhaize", "Q211260" to "Zaandam")
        fun named(refs: List<WikidataRef>) = refs.map { r -> WikidataRef(r.id, names[r.id]?.let { mapOf("en" to it) }.orEmpty(), r.descriptions) }
        KeptFacts(File(services.dirs.files, "search/wikidata")).keep(e.copy(parents = named(e.parents), headquarters = named(e.headquarters)))
    }

    @Test
    fun placeWesterkerk() {
        val services = testServices(places = true).also { keepWesterkerkFacts(it) }
        open(Dest.Place(Services.WESTERKERK.lat, Services.WESTERKERK.lon, "w99205257", "Westerkerk"), services)
        waitFor("Westerkerk")
        // Its hours ("Mo-Fr 11:00-15:00") are closed on a Tuesday evening, the raw tags pill counts its tags.
        compose.onNode(hasContentDescription("tags", substring = true)).assertExists()
        capture("place_westerkerk")
        // The whole sheet: its Wikidata story and where everything comes from.
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.75f, endY = top + 10f) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top + 200f) }
        capture("place_westerkerk_more")
        waitFor("Hendrick de Keyser")
        // its article is read in the app; offline, the reader says so and leads on to Wikipedia
        compose.onNodeWithText("Read the article").performClick()
        waitFor("could not be read")
        compose.onNodeWithText("Read on Wikipedia").assertExists()
        capture("place_wiki")
    }

    @Test
    fun placeCafe() {
        open(Dest.Place(52.37390, 4.88300, "n1147560517", "Café Kalkhoven"))
        waitFor("Kalkhoven")
        capture("place_cafe")
    }

    @Test
    fun placeDark() {
        open(Dest.Place(52.37390, 4.88300, "n1147560517", "Café Kalkhoven"), dark = true)
        waitFor("Kalkhoven")
        capture("place_cafe_dark")
    }

    /**
     * The Brands board, rebuilt (the owner, 6 November: "Improve or re built the brand board along the way"): the chains
     * stacked by kind; a stack dealt out in its place, led by its kind's disc, which stacks it again; a chain of it
     * pressed opens its page.
     */
    @Test
    fun brands() {
        val nav = open(Dest.Brands())
        waitFor("Albert Heijn")
        capture("brands")
        val stack = hasTestTag(BRAND_STACK_TAG)
        val said = compose.onAllNodes(stack).fetchSemanticsNodes().first().config[SemanticsProperties.ContentDescription].first()
        val top = said.substringAfter(": ").substringBefore(", ")
        compose.onAllNodes(stack).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).assertExists()
        capture("brands_dealt")
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(BRAND_STACK_HEAD_TAG).assertDoesNotExist()
        compose.onAllNodes(stack).onFirst().performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("$top, ", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", (nav.current as? Dest.Brand)?.id?.isNotEmpty() == true)
    }

    @Test
    fun brandsDark() {
        open(Dest.Brands(), dark = true)
        waitFor("Albert Heijn")
        capture("brands_dark")
    }

    /** Today's hours pressed, or pressed again: the label its press is said by. */
    private fun weekKey(show: Boolean) = SemanticsMatcher("the week's hours ${if (show) "shown" else "hidden"} at a press") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == if (show) "Show the week's hours" else "Hide the week's hours"
    }

    /**
     * A brand's page (the owner, 6 November: "Multiple layouts, themed brand detail overview page that appears when you
     * clearly search for a specific brand and accessible from the brands menu", then "details about the brand,
     * everything thats useful"): its band in its own colours, what it is, what is known of it a tile each (today's hours
     * across its branches, where it trades, since when, whose it is, its head office, its website, its article), its
     * branches near; the week's hours a press from today's; its branches as cards; back leaves it.
     */
    @Test
    fun brand() {
        val services = testServices(places = true).also { keepAlbertHeijnFacts(it) }
        val nav = open(Dest.Brand("albertheijn-1cf55d"), services)
        waitFor("Dutch supermarket chain")
        waitFor("Ahold Delhaize")
        for (fact in listOf("Belgium, Netherlands", "1887", "Zaandam", "ah.nl", "Read the article")) compose.onNodeWithText(fact).assertExists()
        // today's hours come once its branches' week is worked out
        compose.waitUntil(15_000) { compose.onAllNodes(weekKey(show = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("brand")
        compose.onNode(weekKey(show = true)).performClick()
        waitFor("Opening hours this week")
        compose.onNode(weekKey(show = false)).assertExists()
        capture("brand_week")
        compose.onNode(weekKey(show = false)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show as cards").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Show as a list")).fetchSemanticsNodes().isNotEmpty() }
        capture("brand_cards")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Home(), nav.current)
    }

    @Test
    fun brandDark() {
        open(Dest.Brand("albertheijn-1cf55d"), testServices(places = true).also { keepAlbertHeijnFacts(it) }, dark = true)
        waitFor("Ahold Delhaize")
        capture("brand_dark")
    }

    /**
     * Birkenstock's page (the owner's example, 6 November): of its two stores near one maps its hours and the other
     * none, which takes the first's ("if the other branch is closed the other one is probably closed too"); under its
     * branches the other shoe chains around, each a press from its own page, Back coming back to Birkenstock's.
     */
    @Test
    fun brandBirkenstock() {
        open(Dest.Brand("birkenstock-9b62dc"))
        waitFor("Worldwide")
        // the lent hours come once its branches' week is worked out
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("≈", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(KIND_LIST_TAG).performScrollToKey("kin")
        waitFor("More of its kind near")
        capture("brand_birkenstock")
        val kin = hasAnyAncestor(hasTestTag(KIND_LIST_TAG)) and hasContentDescription(" nearby", substring = true) and hasClickAction()
        val other = compose.onAllNodes(kin).fetchSemanticsNodes().first().config[SemanticsProperties.ContentDescription].first().substringBefore(", ")
        compose.onAllNodes(kin).onFirst().performClick()
        waitFor("Hide $other")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Hide Birkenstock")
    }

    /** A brand's very name typed leads the answer as its page's band; the search key opens its page. */
    @Test
    fun brandNamed() {
        open(Dest.Search("albert heijn"))
        val named = hasContentDescription("All ", substring = true) and hasContentDescription("Albert Heijn", substring = true) and hasClickAction()
        compose.waitUntil(20_000) { compose.onAllNodes(named).fetchSemanticsNodes().isNotEmpty() }
        capture("brand_named")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("Belgium, Netherlands", 20_000)
        capture("brand_named_open")
    }

    @Test
    fun category() {
        open(Dest.Category("drinks"))
        waitFor("open now")
        capture("category_drinks")
    }

    /**
     * Fun's row of kinds ends with Sport, its pitches and gyms a press away and not on Fun's map (the owner, 6 November:
     * "Doesnt sports somehow also belong in fun?"); the climbing walls, ice rinks and pools it holds too follow its own
     * kinds where there are any, each in Sport's or Water's colour.
     */
    /**
     * Fun's two playgrounds, neither with a name of its own, lie on one deck (the owner, 6 November: "If there are
     * multiple like 2 playgrounds make that visible at first glance. A stack?", then "Yes, build decks"): "Playground",
     * 2 near; pressed, it spreads them out, each by the street it is on, and pressed again it stacks them.
     */
    @Test
    fun twoPlaygroundsOnOneDeck() {
        open(Dest.Category("fun"))
        val deck = hasTestTag(DECK_TAG) and hasText("Playground") and hasText("2 near", substring = true)
        compose.waitUntil(20_000) { compose.onAllNodes(deck).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().isEmpty())
        compose.onNode(deck).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().size == 2 }
        compose.waitForIdle()
        capture("category_fun_deck")
        compose.onNode(deck).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * Fuel holds the gas stations and the chargers (the owner, 6 November: "where is the fuel category? Gas and charging
     * belongs both into fuel"): its head says "Fuel"; the canal ring's chargers are near, its gas stations none.
     */
    @Test
    fun fuelHoldsGasAndCharging() {
        open(Dest.Category("charge"))
        waitFor("Charging")
        compose.onNode(hasText("Fuel")).assertExists()
        compose.waitForIdle()
        capture("category_fuel")
    }

    /** Fuel's card in the grid: a gas pump before a charging post, both right of its words, where the post stood alone. */
    @Test
    fun fuelsCardShowsAPumpBeforeAPost() {
        open(Dest.Search())
        waitFor("′ spare", 20_000)
        val fuel = SemanticsMatcher("Fuel's card") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.substringBefore(", ") == "Fuel" }
        }
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(fuel)
        compose.waitForIdle()
        capture("grid_fuel")
    }

    @Test
    fun funLeadsIntoSport() {
        open(Dest.Category("fun"))
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Sport") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        waitFor("Playground")
        capture("category_fun")
    }

    /**
     * A pastry shop's view names the kinds that also sell its cakes, the bakeries first, then the supermarkets (the
     * owner, 6 November: "A bakery might sell pastries"): its row of kinds ends with "See also", which brings them up
     * under the row ("Expand the see also rows right into view by sliding down?"); a press opens the bakeries, and back
     * comes back with them still up.
     */
    @Test
    fun alsoSoldAt() {
        open(Dest.Category("preset:shop/pastry"))
        // its "See also" comes once the places around are read
        compose.waitUntil(20_000) { compose.onAllNodes(seeAlsoChip).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(seeAlsoChip).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(ALSO_SOLD_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("kind_pastry_also")
        compose.onNode(hasAnyAncestor(hasTestTag(ALSO_SOLD_TAG)) and hasText("Bakery", substring = true) and hasClickAction()).performClick()
        // the bakeries' own view names nothing they also sell
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(ALSO_SOLD_TAG).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(ALSO_SOLD_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * A bike shop's view names the kinds that belong with it (the owner, 6 November: "Miniature golf is in fun. Where is
     * golf? Link kinds that belong to each other."): a pull down on its row brings them up under it ("Expand the see
     * also rows right into view by sliding down?"), the hire and the parking with how many are around, the repair
     * stands lighter, none near; a press opens the hire, whose "See also" names the shop, and back comes back. Its row
     * leads to the two wheels' board, which counts a bike shop among its repairs, not to the high street, which is every
     * shop's ("Why is there an arrow up to groceries at bikes?").
     */
    @Test
    fun seeAlso() {
        open(Dest.Category("preset:shop/bicycle"))
        compose.waitUntil(20_000) { compose.onAllNodes(seeAlsoChip).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Two wheels") and hasClickAction()).assertExists()
        assertTrue(compose.onAllNodes(hasText("High street")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(SUBS_TAG).performTouchInput { swipeDown(startY = top + 60f, endY = top + 300f) }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(SEE_ALSO_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("kind_bicycle_see_also")
        compose.onNode(hasAnyAncestor(hasTestTag(SEE_ALSO_TAG)) and hasText("Bike Rental", substring = true) and hasClickAction()).performClick()
        // the hire's row, folded, ends with its own "See also"; pressed, it names the shop
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(SEE_ALSO_TAG).fetchSemanticsNodes().isEmpty() && compose.onAllNodes(seeAlsoChip).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(seeAlsoChip).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasAnyAncestor(hasTestTag(SEE_ALSO_TAG)) and hasText("Bikes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasAnyAncestor(hasTestTag(SEE_ALSO_TAG)) and hasText("Bike Rental", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A kind with no narrower kinds, nothing to go to it for and no screen of its own, a cash machine, has a row for its
     * "See also" alone: pressed, the banks and change offices come up under it, lighter, none near.
     */
    @Test
    fun seeAlsoOnARowOfItsOwn() {
        open(Dest.Category("preset:amenity/atm"))
        compose.waitUntil(20_000) { compose.onAllNodes(seeAlsoChip).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(seeAlsoChip).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(SEE_ALSO_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("kind_atm_see_also")
    }

    /**
     * A frozen food shop's view, none near (the owner, 6 November: "why is there a frozen food category? Supermarkets have
     * frozen food"): the supermarkets and convenience stores that sell it too come first, and their switch shows their
     * places here ("Fill the view with them and save that as a combined view if desired?"), their symbols after the
     * title; off again, the view is the frozen food shops' alone.
     */
    @Test
    fun together() {
        open(Dest.Category("preset:shop/frozen_food"))
        // the kinds that sell it too come once the places around are read; below the sheet's fold, the list is scrolled to them
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag(ALSO_SOLD_TAG).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { compose.onNodeWithTag(KIND_LIST_TAG).performScrollToKey("also") }.isSuccess
        }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(TOGETHER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("kind_frozen_food")
        compose.onNodeWithTag(TOGETHER_TAG).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(joined).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasAnyAncestor(hasTestTag(KIND_LIST_TAG)) and hasText("Albert Heijn", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        capture("kind_frozen_together")
        compose.onNode(seeAlsoChip).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(TOGETHER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TOGETHER_TAG).performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(joined).fetchSemanticsNodes().isEmpty() }
    }

    /** The kind's title with the kinds shown with it, their symbols after its name, named to a screen reader. */
    private val joined = hasText("Frozen Food") and hasContentDescription("Supermarket") and hasContentDescription("Convenience Store")

    /** The row of kinds' "See also", which brings the other kinds up under it. */
    private val seeAlsoChip = hasAnyAncestor(hasTestTag(SUBS_TAG)) and hasText("See also", substring = true) and hasClickAction()

    /** The shops' many kinds on one map, each its symbol on the family's colour (README decision 12). */
    @Test
    fun shops() {
        open(Dest.Category("shops"))
        waitFor("open now")
        capture("category_shops")
    }

    /** Every tag of a kind's places is a filter with its count; one chosen counts the rest again. */
    @Test
    fun categoryFilters() {
        open(Dest.Category("restaurants"))
        waitFor("All filters")
        waitFor("Open now")
        compose.onNodeWithText("All filters").performClick()
        waitFor("Every tag of these places")
        waitFor("Cuisine")
        capture("category_filters")
    }

    /**
     * A filter tapped in the row under the kind's head is chosen, a tap from gone, and those worth a tap next count
     * again; how far it looks counts the minutes the next way at a tap; the filters then say how many have every one.
     */
    @Test
    fun categoryFilterRow() {
        open(Dest.Category("restaurants"))
        waitFor("All filters")
        waitFor("Open now")
        compose.onAllNodes(hasText("Open now") and hasClickAction()).onFirst().performClick()
        val chosen = SemanticsMatcher("takes a filter out") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Take it out" }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Open now") and chosen).fetchSemanticsNodes().isNotEmpty() }
        capture("category_chosen")
        // how far it looks, in minutes on foot; a tap counts them by bike
        compose.onNode(hasContentDescription("On foot", substring = true)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("By bike", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("category_reach")
        // with a filter chosen, the filters say how many of the kind have every one, and each chosen what it hides
        compose.onNodeWithText("All filters").performClick()
        waitFor("have everything you picked")
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("hides", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("category_meter")
    }

    /**
     * A kind's places as large cards to slide through (the user, 5 November): the key on the kind's band shows them so,
     * and every search after; the card in view chooses its pin; a slide brings another; a card opens its place, and back
     * comes back to the cards. A place's own photo stands at its card's top where the phone keeps one: the Westerkerk's,
     * kept from opening its page before; the rest wear their kind's scene and pin. The photo kept is a stand-in for its
     * Wikidata photo: Panoramax's picture by the Westerkerk ([app.atlas.ui.local.LocalFixtures.streetPhoto]), the City of
     * Amsterdam's, CC-BY-SA 4.0, credited as such.
     */
    @Test
    fun worshipAsCards() {
        val services = testServices(places = true)
        val entity = Wikidata.parseEntity(
            Json.parseToJsonElement(javaClass.getResource("/local/wikidata_westerkerk.json")!!.readText()).jsonObject["entities"]!!.jsonObject["Q1130722"]!!.jsonObject,
        )
        KeptFacts(File(services.dirs.files, "search/wikidata")).keep(entity)
        val photo = javaClass.getResourceAsStream("/local/pano_westerkerk.jpg")!!.readBytes()
        KeptImages(services.dirs.images).keep(entity.image!!, KeptImage(photo, "City of Amsterdam", "CC BY-SA 4.0"))
        val nav = open(Dest.Category("worship"), services)
        waitFor("Westerkerk")
        compose.onNodeWithContentDescription("Show as cards").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        // kept for every search after
        compose.waitUntil(5_000) { runBlocking { services.settings.data.first()[app.atlas.services.SettingsKeys.SEARCH_CARDS] } == true }
        compose.onNodeWithContentDescription("Show as a list").assertExists()
        capture("cards_worship")
        // a slide to the Westerkerk's card, its photo kept from before
        compose.onNodeWithTag(CARDS_TAG).performScrollToNode(hasText("Westerkerk"))
        compose.waitForIdle()
        capture("cards_worship_westerkerk")
        // the card opens its place; back comes back to the cards
        compose.onNode(hasText("Westerkerk") and hasAnyAncestor(hasTestTag(CARDS_TAG))).performClick()
        compose.waitForIdle()
        assertTrue("now on ${nav.current}", nav.current is Dest.Place)
        compose.runOnUiThread { nav.back() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The same cards at night: the kinds' scenes in their colours, the cards on the night's paper. */
    @Test
    fun restaurantsAsCardsAtNight() {
        val services = testServices(places = true)
        runBlocking { services.settings.edit { it[app.atlas.services.SettingsKeys.SEARCH_CARDS] = true } }
        open(Dest.Category("restaurants"), services, dark = true)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        capture("cards_restaurants_dark")
    }

    /**
     * A list speaks in families, a chain on its own sign (README decision 12): the groceries' green on every row and
     * pin but Albert Heijn's, whose branches are one deck under its blue sign; the groceries open on their kinds.
     */
    @Test
    fun groceriesMarkedByTheirLogos() {
        open(Dest.Category("groceries"))
        waitFor("Albert Heijn", 20_000)
        capture("category_groceries")
    }

    /** As cards, a chain without a photo of its own shows its fascia: its sign's colour edge to edge, its logo on it. */
    @Test
    fun groceriesAsCards() {
        val services = testServices(places = true)
        runBlocking { services.settings.edit { it[app.atlas.services.SettingsKeys.SEARCH_CARDS] = true } }
        open(Dest.Category("groceries"), services)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(CARDS_TAG)).fetchSemanticsNodes().isNotEmpty() }
        waitFor("Albert Heijn", 20_000)
        capture("cards_groceries")
    }

    /** Where the nearest is the point (a toilet), the way to it leads as a card of its own over the rest. */
    @Test
    fun categoryNearest() {
        open(Dest.Category("toilets"))
        waitFor("Nearest", 20_000)
        compose.onNodeWithContentDescription("Route there").assertExists()
        capture("category_nearest")
    }

    /**
     * Nothing just as asked: the closest instead, each row saying first what it misses (the owner, 4 November: "should
     * always show closest match if you drop or lower a requirement").
     */
    @Test
    fun closestMatches() {
        open(Dest.Search("bar within 20 m"))
        // the typed results first (the field says "bar" already): the search key opens the best of them, and does
        // nothing before they are in
        waitFor("read as a query", 20_000)
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("beyond the circle", 20_000)
        capture("closest")
    }

    /** A place kept in a list wears the list's bookmark on its rows. */
    @Test
    fun aKeptPlaceWearsItsBookmark() {
        val services = testServices(places = true)
        services.saved.save(services.placeStores.get(OsmId.parse("n1147560517")!!)!!.place)
        open(Dest.Search(), services)
        waitFor("Every kind", 20_000)
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(hasText("Eat & drink"))
        compose.onNodeWithText("Eat & drink").performClick()
        waitFor("Kalkhoven", 20_000)
        capture("kept")
        compose.onNodeWithContentDescription("Saved").assertExists()
    }

    /**
     * Typed words, and the keyboard's search key: what they asked opens over the map, the words as its title, what
     * they were read as each a tap from gone, and the same as a query a tap further.
     */
    @Test
    fun askedWords() {
        open(Dest.Search("cafe with wifi near bicycle rental"))
        waitFor("Kalkhoven", 20_000)
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("As a query", 20_000)
        capture("asked")
    }

    /** "How many": the number, how surely, of which kinds, as a card over the typed words and over the places. */
    @Test
    fun howMany() {
        open(Dest.Search("how many benches"))
        waitFor("found", 20_000)
        capture("count")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("As a query", 20_000)
        capture("count_open")
    }

    /** "How many … open": the number found and how many of them for sure, one open only by guessed hours being open perhaps. */
    @Test
    fun howManyOpen() {
        open(Dest.Search("how many restaurants open now"))
        waitFor("found", 20_000)
        capture("count_open_now")
    }

    /** An outing: each stop timed, the next places that would do a tap from taken, and the way past every stop. */
    @Test
    fun outing() {
        open(Dest.Search("coffee then dinner"))
        waitFor("a plan", 20_000)
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("Route past every stop", 20_000)
        capture("outing")
    }

    /** "Opening times": the week of the places' hours, a bar a day, today in words. */
    @Test
    fun hoursOverview() {
        open(Dest.Search("overview of cafe opening times"))
        waitFor("Opening hours this week", 20_000)
        capture("hours")
    }

    /**
     * Past the hero the grid takes the form of what its kinds are doing on a Tuesday at 19:30: a shelf of those about
     * to close, every kind, then those asleep till morning as a deck that fans out.
     */
    @Test
    fun gridByState() {
        open(Dest.Search())
        // the kinds' pulses are in once the shelf of those about to close says the time to spare
        waitFor("′ spare", 20_000)
        val grid = compose.onNodeWithTag(GRID_TAG)
        grid.performScrollToNode(hasText("Before they close"))
        capture("grid_closing")
        grid.performScrollToNode(hasText("Asleep till morning"))
        compose.onNodeWithText("Fan out", substring = true, ignoreCase = true).performClick()
        compose.waitForIdle()
        waitFor("Fold up")
        capture("grid_asleep")
    }

    /**
     * Near midnight the drinks are about to close, under their own glass (the owner, 9 November: "Drinks and nightlife
     * has pasta"); the plate stays with what is eaten, on the hero and Eat & drink's card.
     */
    @Test
    fun theDrinksCloseUnderTheirOwnGlass() {
        val late = java.time.Clock.fixed(java.time.Instant.parse("2026-10-13T21:50:00Z"), java.time.ZoneId.of("Europe/Amsterdam"))
        open(Dest.Search(), testServices(places = true, clock = late))
        waitFor("Drinks & nightlife", 20_000)
        capture("grid_drinks_closing")
    }

    /**
     * The plain list of every kind (the owner, 6 November): the grid's head says how many kinds are around and opens it,
     * the most first; A–Z with only those open now; a kind on it opens, and Back is the list again.
     */
    @Test
    fun everyKind() {
        open(Dest.Search())
        waitFor("′ spare", 20_000)
        val door = SemanticsMatcher("the list's door") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.matches(Regex("\\d+ kinds")) }
        }
        // the kinds are counted after the tiles' pulses: the door shows once they are
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag(GRID_TAG).performScrollToNode(door) }.isSuccess }
        capture("every_kind_door")
        compose.onNode(door).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EVERY_TAG).assertExists()
        capture("every_kind")
        compose.onNodeWithText("A–Z").performClick()
        compose.onNodeWithText("Open now").performClick()
        compose.waitForIdle()
        capture("every_kind_az_open")
        // the clothes shops are shut by 19:30: every kind again, to open theirs
        compose.onNodeWithText("Open now").performClick()
        compose.waitForIdle()
        // the last row: everything else on the map (a road, a canal), as the grid's last shelf
        compose.onNodeWithTag(EVERY_TAG).performScrollToKey("everything")
        capture("every_kind_end")
        compose.onNodeWithTag(EVERY_TAG).performScrollToNode(hasText("Clothes"))
        compose.onNodeWithText("Clothes").performClick()
        waitFor("All filters", 20_000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EVERY_TAG).assertExists()
    }

    /** From a family's chips, every kind under it on its band; Back is the family again. */
    @Test
    fun everyKindOfAFamily() {
        open(Dest.Category("shops"))
        waitFor("open now")
        compose.onNodeWithText("Every kind").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EVERY_TAG).assertExists()
        waitFor("kinds")
        capture("every_kind_shops")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("open now")
        compose.onNodeWithText("Every kind").assertExists()
    }

    /**
     * The groceries' narrower kinds run past the sheet's edge in one row; pulled down, the row unfolds into lines that
     * show every one (the owner, 6 November: "Pull down on the subcategory row to expand?"), the last a way to the list
     * of them all; pulled up, it folds into the one row again. Their things' own colours on the chips (bread brown,
     * cheese yellow).
     */
    @Test
    fun theKindsRowPullsOpen() {
        open(Dest.Category("groceries"))
        waitFor("Albert Heijn", 20_000)
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        // where they are laid out, scrolled out of sight or not (boundsInRoot is cut to what shows)
        fun row() = compose.onNodeWithTag(SUBS_TAG).fetchSemanticsNode().size
        fun last() = compose.onNodeWithText("Every kind").fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }
        val folded = row().height
        assertTrue("the last chip past the edge", last() > width)
        compose.onNodeWithTag(SUBS_TAG).performTouchInput { swipeDown(startY = top + 60f, endY = top + 300f) }
        compose.waitForIdle()
        assertTrue("unfolded into lines: ${row().height} from $folded", row().height > 2 * folded)
        assertTrue("every chip inside the edge", last() <= width)
        capture("kind_row_open")
        compose.onNodeWithTag(SUBS_TAG).performTouchInput { swipeUp(startY = top + 60f, endY = top - 180f) }
        compose.waitForIdle()
        assertEquals(folded, row().height)
        assertTrue("folded, the last chip past the edge again", last() > width)
    }

    /**
     * Fun has one kind near, and holds many more (the owner, 6 November: "Theres no way to see every kind that belongs to
     * a category from within that category"): its list shows them all, those none of which is near lighter after it,
     * and a press looks further for each ("no way to then extend the search until results for every kind turn up").
     */
    @Test
    fun everyKindOfFunAndFurther() {
        // past the widest circle a kind's view widens to: a bowling alley, an ice rink and a water park 30 to 55 km off
        val w = Services.WESTERKERK
        fun far(id: Long, north: Double, vararg tags: Pair<String, String>) = Place(OsmId(OsmType.NODE, 900_000_000L + id), LatLon(w.lat + north, w.lon), mapOf(*tags))
        val further = MemoryPlaces(
            listOf(
                far(1, 0.27, "leisure" to "bowling_alley", "name" to "Bowling"),
                far(2, 0.36, "leisure" to "ice_rink", "name" to "IJsbaan"),
                far(3, 0.50, "leisure" to "water_park", "name" to "Zwemparadijs"),
            ),
        )
        open(Dest.Category("fun"), testServices(stores = listOf(Fixtures.amsStore, further)))
        waitFor("Playground")
        compose.onNodeWithText("Every kind").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EVERY_TAG).assertExists()
        waitFor("none near")
        capture("every_kind_fun")
        compose.onNodeWithText("Look further").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Looking further…").fetchSemanticsNodes().isEmpty() }
        // pulled up, from the top: the one kind near, then those found further by how far, then those found nowhere
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.6f, endY = top + 10f) }
        compose.waitForIdle()
        compose.onNodeWithTag(EVERY_TAG).performScrollToIndex(0)
        compose.waitForIdle()
        capture("every_kind_fun_further")
        // the water park, 0.5° north: 55.7 km
        waitFor("56 km")
    }

    /** The groceries' kinds, many of them things with a colour of their own (bread, cheese, fish): their symbols on one list. */
    @Test
    fun everyGroceryKind() {
        open(Dest.Category("groceries"))
        waitFor("Albert Heijn", 20_000)
        compose.onNodeWithText("Every kind").performScrollTo().performClick()
        compose.waitForIdle()
        // the kinds among the family's places are counted once the list is open: its rows say how many are near
        compose.onNodeWithTag(EVERY_TAG).assertExists()
        waitFor(" near")
        capture("every_kind_groceries")
        // pulled up: every one of them, Albert Heijn's sign behind the supermarkets
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.6f, endY = top + 10f) }
        compose.waitForIdle()
        capture("every_kind_groceries_full")
    }

    /** The shelves past the row's eight wear scenes of their own as well: Sport's is a football before the goal. */
    @Test
    fun theShelvesPastTheRow() {
        open(Dest.Search())
        waitFor("′ spare", 20_000)
        // the card reads its name, then what it says now ("Sport, 12 open")
        val sport = SemanticsMatcher("Sport's card") { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.substringBefore(", ") == "Sport" }
        }
        compose.onNodeWithTag(GRID_TAG).performScrollToNode(sport)
        capture("grid_sport")
    }

    /** The Map home's category row and search bar open the one search in a sheet over the map. */
    @Test
    fun theHomeOpensTheSearchInPlace() {
        val services = testServices(places = true)
        val nav = BackStackNavigator(listOf(Dest.Home()))
        compose.setContent { AtlasRoot(services, nav, dark = false) }
        compose.settle(15_000)
        compose.onNodeWithContentDescription(app.atlas.places.Categories.byId("eat")!!.name("en")).performClick()
        waitFor("All filters", 20_000)
        assertEquals("the search opened over the map, not on a page", Dest.Home(), nav.current)
        capture("home_eat")
        // a kind stays when it is pulled down: it comes to rest at its peek, with its own way back
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.52f, endY = bottom * 0.75f) }
        compose.waitForIdle()
        // a kind from the row: its band's way back is the map again
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search or ask").performClick()
        waitFor("Every kind", 20_000)
        assertEquals(Dest.Home(), nav.current)
        capture("home_grid")
        // no arrow on the grid's band: pulled down it goes, straight from half
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.56f, endY = bottom * 0.98f) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Every kind")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Map").assertExists()
        // and a kind, pulled down from its peek
        compose.onNodeWithContentDescription(app.atlas.places.Categories.byId("eat")!!.name("en")).performClick()
        waitFor("All filters", 20_000)
        // on the band: the row of kinds under it keeps a pull of its own, for what else is near
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.52f, endY = bottom * 0.75f) }
        compose.waitForIdle()
        compose.onNodeWithText("All filters").assertExists()
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.84f, endY = bottom * 0.995f) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("All filters")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Map").assertExists()
    }

    @Test
    fun aPageOfTheSearchGoesWhenItIsPulledDown() {
        val nav = open(Dest.Search())
        waitFor("Every kind")
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeDown(startY = bottom * 0.12f, endY = bottom * 0.8f) }
        compose.waitUntil(10_000) { nav.current == Dest.Home() }
    }

    @Test
    fun whatsHere() {
        open(Dest.WhatsHere(Services.WESTERKERK.lat, Services.WESTERKERK.lon))
        waitFor("At this spot", 20_000)
        assertTrue(compose.onAllNodes(hasText("Westerkerk", substring = true)).fetchSemanticsNodes().isNotEmpty())
        capture("whatshere")
    }

    @Test
    fun whatsHereOnlyTiles() {
        // No region on the phone: the address comes from the map tiles alone.
        open(Dest.WhatsHere(52.37515, 4.88260), testServices())
        waitFor("Coordinates", 20_000)
        compose.settle(15_000)
        capture("whatshere_tiles")
    }

    @Test
    fun lists() {
        val services = testServices(places = true)
        val s = services.saved
        fun place(id: String) = services.placeStores.get(OsmId.parse(id)!!)!!.place
        s.save(place("w99205257"))
        val rainy = s.createList("Rainy day")
        s.save(place("n1147560517"), rainy)
        s.save(place("n9141449091"), rainy)
        val coffee = s.createList("Coffee crawl")
        for (id in listOf("n2720974480", "n10881672349", "n10920278785")) s.save(place(id), coffee)
        s.createList("Date night")
        s.createList("Kid-proof")
        open(Dest.Lists(rainy), services)
        assertTrue(compose.onAllNodes(hasText("Rainy day", substring = true)).fetchSemanticsNodes().size >= 2)
        capture("lists")
    }

    @Test
    fun ask() {
        val nav = open(Dest.Ask("a pub with a terrace, then dinner"))
        waitFor("Your plan", 20_000)
        capture("ask")
        assertEquals(Dest.Ask("a pub with a terrace, then dinner"), nav.current)
    }

    @Test
    fun quests() {
        open(Dest.Quests(Services.WESTERKERK.lat, Services.WESTERKERK.lon))
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("skip")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Yes").assertExists()
        capture("quests")
        // An answer shows the exact note before anything is sent, and offline it cannot go.
        compose.onNodeWithText("Yes").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("A visitor answered: yes", substring = true).assertExists()
        compose.onNodeWithText("Needs a connection", substring = true).assertExists()
        capture("quests_confirm")
    }

    @Test
    fun where() {
        open(Dest.Where)
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Spin the wheel", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("where")
        compose.onNodeWithContentDescription("Spin").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        waitFor("Open this place", 20_000)
        capture("where_landed")
        // A flick on the wheel, a quarter turn clockwise in an eighth of a second, sends it round: the card goes while it
        // spins and a place lands again.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Water, Odd", substring = true).performTouchInput {
            val hub = Offset(width * 0.5f, height * 168f / 320f)
            swipe(hub + Offset(width * 0.3f, 0f), hub + Offset(0f, width * 0.3f), 120)
        }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(compose.onAllNodes(hasText("Open this place")).fetchSemanticsNodes().isEmpty())
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(3_000)
        waitFor("Open this place", 20_000)
        capture("where_flung")
    }

    /**
     * Rain on the forecast the phone holds: the wheel starts indoors, its outdoor wedges grey and the chip on, and a spin
     * lands indoors.
     */
    @Test
    fun whereInTheRain() {
        val s = testServices(places = true).apply { assertTrue(keepFixtureForecast(rainMm = 0.8)) }
        open(Dest.Where, s)
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Spin the wheel", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("indoors").assertIsOn()
        capture("where_rain")
        compose.onNodeWithContentDescription("Spin").performClick()
        compose.mainClock.advanceTimeBy(3_000)
        waitFor("Open this place", 20_000)
        for (outdoors in listOf("WATER", "ODD", "VIEWS", "NATURE")) assertTrue(outdoors, compose.onAllNodes(hasText(outdoors)).fetchSemanticsNodes().isEmpty())
        capture("where_rain_landed")
    }
}
