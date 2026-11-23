package app.atlas.ui.search

import android.os.Looper
import java.io.File
import com.github.takahirom.roborazzi.captureRoboImage
import app.atlas.testing.shotsDir
import app.atlas.testing.settle
import app.atlas.nav.AtlasRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.nav.BackStackNavigator
import app.atlas.nav.Dest
import app.atlas.nav.ProvideServices
import app.atlas.search.MemoryPlaces
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
import java.time.Duration
import java.time.LocalDateTime

/**
 * A typed search's place rows wear their chain's sign as a kind's rows and the same answer's pins do ([signOf], the
 * row's own country): a branch of a chain with a logo, Albert Heijn, and one of a chain with a colour and no logo,
 * Jumbo; a brand whose colour is not known, Ekoplaza, and a shop with no brand keep their kind's mark. Every way a
 * place comes into the typed list wears it (found by its words, by its id, online, and among the hits a chip leaves),
 * and the row's text, what TalkBack reads, and its tile's size stay as they were.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class TypedSignsTest {
    @get:Rule
    val compose = createComposeRule()

    private val here = Services.WESTERKERK
    private val jumbo = "jumbo-1cf55d"
    private val albertHeijn = "albertheijn-1cf55d"

    private fun place(id: Long, north: Double, tags: Map<String, String>) = Place(OsmId(OsmType.NODE, id), LatLon(here.lat + north, here.lon), tags)

    private val jumboTags = mapOf("shop" to "supermarket", "name" to "Jumbo", "brand" to "Jumbo", "brand:wikidata" to "Q2262314", "opening_hours" to "Mo-Su 08:00-22:00")
    private val ahTags = mapOf("shop" to "supermarket", "name" to "Albert Heijn", "brand" to "Albert Heijn", "brand:wikidata" to "Q1653985", "opening_hours" to "Mo-Su 08:00-12:00")
    private val ekoTags = mapOf("shop" to "supermarket", "name" to "Ekoplaza", "brand" to "Ekoplaza", "brand:wikidata" to "Q47017915")
    private val buurtTags = mapOf("shop" to "supermarket", "name" to "Buurtsuper")

    private val places = listOf(
        place(10, 0.001, jumboTags), place(12, -0.0012, ahTags), place(20, 0.002, ekoTags), place(21, -0.002, buurtTags),
        place(99, -0.003, mapOf("amenity" to "bench")),
    )

    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    /**
     * Each place row of the typed list as "name: tile | text | what is said", the tile as [TileMark] has it, the text and
     * what TalkBack reads as the row's merged semantics carry them.
     */
    private fun rows(): List<String> {
        compose.waitForIdle()
        val side = with(compose.density) { 40.dp.roundToPx() }
        // every tile of the list keeps a row's 40 dp
        for (t in compose.onAllNodes(SemanticsMatcher.keyIsDefined(TileMark), useUnmergedTree = true).fetchSemanticsNodes()) {
            assertEquals("a tile's side", side to side, t.size.width to t.size.height)
        }
        return compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().map { n ->
            val c = n.config
            val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" / ") { it.text }
            val said = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" / ")
            "${c.getOrNull(TileMark)} | $text | $said"
        }
    }

    @Test
    fun aTypedSearchsPlaceRowsWearTheSignOfTheirBrand() {
        val s = testServices(stores = listOf(MemoryPlaces(places, country = null)))
        val now = LocalDateTime.now(s.clock)
        val online = Place(null, LatLon(here.lat + 0.0015, here.lon), jumboTags, source = "photon")
        val onlineEko = Place(null, LatLon(here.lat - 0.0015, here.lon), ekoTags, source = "photon")
        fun words(text: String) = SearchViewModel(s, text, null, { here }, "en", online = { false })
        fun found(text: String, items: List<SearchItem>) = SearchViewModel(s, text, null, { here }, "en", search = { t, _ -> SearchResults(t, items, usedOnline = true) }, online = { true })
        var vm by mutableStateOf(words("supermarket"))
        var field by mutableStateOf("supermarket")
        compose.setContent {
            ProvideServices(s) {
                LazyColumn { typedPane(vm.state.value, vm, field, here, TravelMode.WALK, "en", now, BackStackNavigator(listOf(Dest.Home())), {}, {}) }
            }
        }
        fun show(text: String, next: SearchViewModel): List<String> {
            until { next.state.value.results != null && !next.state.value.searching }
            compose.runOnIdle { vm = next; field = text }
            return rows()
        }
        val seen = linkedMapOf<String, List<String>>()
        // by its words: the four supermarkets, each its own row
        seen["supermarket"] = show("supermarket", words("supermarket"))
        // a shop with no brand
        seen["buurtsuper"] = show("buurtsuper", words("buurtsuper"))
        // by its id
        seen["node/10"] = show("node/10", words("node/10"))
        // online, its sign from its tags
        seen["online"] = show("jumbo", found("jumbo", listOf(SearchItem.Online(online, 160.0, "ui.pin", 1.0), SearchItem.Online(onlineEko, 160.0, "ui.pin", 0.9))))
        // among the hits a chip leaves
        val chipped = words("supermarket")
        until { chipped.state.value.results != null && !chipped.state.value.searching }
        val chip = chipped.state.value.results!!.refinements.first().chip.value
        chipped.toggle(chip)
        until { chipped.state.value.refined != null }
        compose.runOnIdle { vm = chipped; field = "supermarket" }
        seen["chip $chip"] = rows()
        s.close()

        val j = "sign:$jumbo"
        val ah = "sign:$albertHeijn"
        val kind = "kind:maki-grocery"
        // the text and what is said, as the base had them (19 Nov, at be133f35): only the tiles change
        val expected = linkedMapOf(
            "supermarket" to listOf(
                "$j | Jumbo / open until 22:00 · Supermarket · 110 m / 2′ | 2 min",
                "$ah | Albert Heijn / closed · opens Wed 8:00 · Supermarket · 130 m / 3′ | 3 min",
                "$kind | Ekoplaza / Supermarket · 220 m / 4′ | 4 min",
            ),
            "buurtsuper" to listOf("$kind | Buurtsuper / Supermarket · 220 m / 4′ | 4 min"),
            "node/10" to listOf("$j | Jumbo / open until 22:00 · n10 · 110 m / 2′ | 2 min"),
            "online" to listOf(
                "$j | Jumbo / open until 22:00 · Supermarket · 160 m / 3′ | 3 min",
                "$kind | Ekoplaza / Supermarket · 160 m / 3′ | 3 min",
            ),
            "chip $chip" to listOf("$j | Jumbo / open until 22:00 · Supermarket · 110 m / 2′ | 2 min"),
        )
        assertTrue("the rows:\n" + seen.entries.joinToString("\n") { (k, v) -> "$k\n  " + v.joinToString("\n  ") }, seen == expected)
    }

    /** Two Jumbos, one open and one shut, with the places about them: the typed list for "jumbo" shows the brand's own row and its branches'. */
    private fun typedJumbo(dark: Boolean) {
        val shut = place(11, 0.0035, jumboTags + ("opening_hours" to "Mo-Su 08:00-12:00"))
        val s = testServices(stores = listOf(MemoryPlaces(places + shut, country = null)))
        compose.setContent { AtlasRoot(s, BackStackNavigator(listOf(Dest.Home(), Dest.Search("jumbo"))), dark = dark) }
        compose.settle(15_000)
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().size >= 2 }
    }

    /** Lets the screen settle: capture until three frames in a row come out the same, as [ColourOnlyBrandsTest] does. */
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

    private fun tiles() = compose.onAllNodes(hasTestTag(PLACE_TAG)).fetchSemanticsNodes().map { it.config.getOrNull(TileMark) }

    @Test
    fun aChainsNameTyped() {
        typedJumbo(dark = false)
        val tiles = tiles()
        capture("typed_chain")
        assertEquals(listOf("sign:$jumbo", "sign:$jumbo"), tiles)
    }

    @Test
    fun aChainsNameTypedAtNight() {
        typedJumbo(dark = true)
        val tiles = tiles()
        capture("typed_chain_dark")
        assertEquals(listOf("sign:$jumbo", "sign:$jumbo"), tiles)
    }
}
