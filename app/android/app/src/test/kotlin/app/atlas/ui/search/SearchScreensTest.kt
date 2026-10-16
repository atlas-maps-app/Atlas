package app.atlas.ui.search

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.atlas.Services
import app.atlas.providers.Wikidata
import app.atlas.providers.WikidataRef
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.atlas.model.OsmId
import app.atlas.nav.AtlasRoot
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.testing.settle
import app.atlas.testing.shotsDir
import app.atlas.testing.testServices
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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
        val nav = open(Dest.Search(), services)
        compose.onNodeWithText("52.3745, 4.8838").assertExists()
        compose.onNodeWithText("Or just say it", substring = true, ignoreCase = true).assertExists()
        capture("search_empty")
        // The hub links on: brands, the wheel, the quests, the lists.
        compose.onNodeWithText("Brands").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Brands(), nav.current)
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithText("Where to?").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Where, nav.current)
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithText("My lists").performClick()
        compose.waitForIdle()
        assertEquals(Dest.Lists(), nav.current)
        // A coordinate typed before opens what is there.
        compose.runOnUiThread { nav.back() }
        compose.onNodeWithText("52.3745, 4.8838").performClick()
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

    /** Wikidata's Westerkerk as fetched once before (the providers' recorded answers), kept on the test phone. */
    private fun keepWesterkerkFacts(services: Services) {
        val fixtures = File(System.getProperty("atlas.repo")!!, "app/android/providers/src/test/resources/fixtures")
        fun entities(name: String) = Json.parseToJsonElement(File(fixtures, name).readText()).jsonObject["entities"]!!.jsonObject
        val labels = entities("wd_labels.json").mapValues { (_, v) ->
            v.jsonObject["labels"]?.jsonObject?.mapValues { it.value.jsonObject["value"]!!.jsonPrimitive.content }.orEmpty()
        }
        val e = Wikidata.parseEntity(entities("wd_entities.json")["Q1130722"]!!.jsonObject)
        fun named(refs: List<WikidataRef>) = refs.map { r -> WikidataRef(r.id, labels[r.id].orEmpty(), r.descriptions) }
        KeptFacts(File(services.dirs.files, "search/wikidata")).keep(e.copy(architects = named(e.architects), styles = named(e.styles), namedAfter = named(e.namedAfter)))
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

    @Test
    fun brands() {
        open(Dest.Brands())
        waitFor("Albert Heijn")
        capture("brands")
    }

    @Test
    fun brand() {
        open(Dest.Brand("albertheijn-1cf55d"))
        waitFor("branches")
        capture("brand")
    }

    @Test
    fun category() {
        open(Dest.Category("drinks"))
        waitFor("open now")
        capture("category_drinks")
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
    }
}
