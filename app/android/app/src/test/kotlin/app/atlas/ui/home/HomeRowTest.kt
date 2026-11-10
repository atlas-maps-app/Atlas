package app.atlas.ui.home

import android.os.Looper
import app.atlas.Services
import app.atlas.places.Brands
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.search.EveryKind
import app.atlas.search.Query
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.services.SettingsKeys
import app.atlas.testing.testServices
import app.atlas.ui.search.SearchSheetModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The home's own row ([HomeRow]; the owner, 6 November: "Favorite re order customize?", then "Yes, build it"): Atlas's
 * eight till it is changed, then its ids in the order they were put, a family, a kind and a chain each a button of its
 * own; added at its end, taken out, moved and brought back to Atlas's eight, kept on the phone; an id that stands for
 * nothing any more left out without a word. A kind's or a chain's star puts it in the row, and the list of every kind
 * opened from the row adds the kind pressed, or the kind or chain typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeRowTest {
    private fun idle(ms: Long = 50) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Runs the main looper until [done] or the time is up, letting background threads work meanwhile. */
    private fun until(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    private val playground = "preset:leisure/playground"
    private val ah = HomeRow.CHAIN + "albertheijn-1cf55d"

    @Test
    fun atlasEightTillItIsChanged() {
        assertEquals(HOME_CATEGORIES, HomeRow.ids(null))
        // Atlas's eight are not kept: a row no one changed goes on following Atlas's
        assertNull(HomeRow.keep(HOME_CATEGORIES))
        val mine = listOf(playground, "eat", ah)
        assertEquals(mine, HomeRow.ids(HomeRow.keep(mine)))
        // an empty row is kept as one, and twice the same id is once
        assertEquals(emptyList<String>(), HomeRow.ids(HomeRow.keep(emptyList())))
        assertEquals(listOf("eat", "fun"), HomeRow.ids("eat||fun|eat"))
        assertFalse(HomeRow.read(null).own)
        assertTrue(HomeRow.read("").own)
    }

    @Test
    fun addedAtTheEndMovedAndTakenOut() {
        val ids = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "d"), HomeRow.with(ids, "d"))
        assertEquals("once in the row", ids, HomeRow.with(ids, "b"))
        assertEquals(listOf("b", "c", "a"), HomeRow.moved(ids, 0, 2))
        assertEquals(listOf("c", "a", "b"), HomeRow.moved(ids, 2, 0))
        assertEquals(listOf("b", "a", "c"), HomeRow.moved(ids, 0, 1))
        assertEquals("past the end, to the end", listOf("b", "c", "a"), HomeRow.moved(ids, 0, 9))
        assertEquals("none there, nothing moves", ids, HomeRow.moved(ids, 5, 0))
    }

    @Test
    fun eachIdIsAFamilyAKindOrAChain() {
        val eat = HomeRow.resolve("eat") as HomeRow.Family
        assertEquals(HOME_CATEGORY_LOOK.getValue("eat").symbol, eat.look.symbol)
        val kind = HomeRow.resolve(playground) as HomeRow.Kind
        assertEquals("Playground", kind.name("en"))
        // a family's shelf of its own is a kind of the row's ("Culture")
        assertEquals(NodeKind.SHELF, (HomeRow.resolve("shelf:culture") as HomeRow.Kind).node.kind)
        val chain = HomeRow.resolve(ah) as HomeRow.Chain
        assertEquals("Albert Heijn", chain.name("en"))
        for (gone in listOf("brand:no-such-chain-000000", "preset:no/such", "ufo-landings")) assertNull(gone, HomeRow.resolve(gone))
        // in order, what stands for nothing left out
        assertEquals(listOf("water", playground, ah), HomeRow.buttons("water|ufo-landings|$playground|$ah").map { it.id })
        // a family's view goes by the family's id, any other kind's by its node's, a chain's by its brand's
        assertEquals("eat", HomeRow.idOf(CategoryTree.category("eat")!!))
        assertEquals("cat:restaurants", HomeRow.idOf(CategoryTree.category("restaurants")!!))
        assertEquals(ah, HomeRow.idOf(Brands.byId("albertheijn-1cf55d")!!))
    }

    @Test
    fun keptOnThePhone() = runBlocking {
        val settings = testServices().settings
        suspend fun kept() = HomeRow.ids(settings.flow(SettingsKeys.HOME_ROW).first())
        HomeRow.edit(settings) { HomeRow.with(it, playground) }
        assertEquals(HOME_CATEGORIES + playground, kept())
        HomeRow.edit(settings) { HomeRow.moved(it, it.lastIndex, 0) }
        assertEquals(playground, kept().first())
        HomeRow.edit(settings) { it - "eat" }
        assertFalse("eat" in kept())
        // an id that stands for nothing any more goes at the next change
        settings.set(SettingsKeys.HOME_ROW, "ufo-landings|water")
        HomeRow.edit(settings) { HomeRow.with(it, "fun") }
        assertEquals(listOf("water", "fun"), kept())
        // Atlas's eight again: nothing kept, the row follows Atlas's
        HomeRow.edit(settings) { HOME_CATEGORIES }
        assertNull(settings.flow(SettingsKeys.HOME_ROW).first())
    }

    /** The button lit is the kind's, else the nearest broader kind in the row; a chain's page lights its chain. */
    @Test
    fun theButtonLitForWhatTheSheetShows() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        val pizza = CategoryTree.all().first { it.preset?.tags?.get("cuisine") == "pizza" && it.preset?.tags?.get("amenity") == "restaurant" }
        m.enter(pizza.id)
        assertEquals("cat:restaurants", HomeRow.chosen(m.state.value, listOf("eat", "cat:restaurants")))
        assertEquals("eat", HomeRow.chosen(m.state.value, HOME_CATEGORIES))
        assertNull(HomeRow.chosen(m.state.value, listOf("water", ah)))
        m.chain(Brands.byId("albertheijn-1cf55d")!!)
        assertEquals(ah, HomeRow.chosen(m.state.value, listOf("groceries", ah)))
    }

    /**
     * A kind's star puts it at the row's end and takes it out; the list opened from the row leads with the families,
     * each with its kinds around under it, everything else on the map left out, and the kind pressed on it, or the kind
     * or chain typed, goes into the row as the list closes.
     */
    @Test
    fun aStarAndTheListToAddFrom() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.kinds.isNotEmpty() }
        assertEquals(HOME_CATEGORIES, m.state.value.prefs.row)
        m.star(playground, true)
        until { playground in m.state.value.prefs.row }
        assertEquals(HOME_CATEGORIES + playground, m.state.value.prefs.row)
        m.star("eat", false)
        until { "eat" !in m.state.value.prefs.row }
        // opened from inside a kind, the list is the whole one all the same
        m.enter("drinks")
        m.openEvery(picking = true)
        val e = m.state.value.every!!
        assertTrue(e.picking)
        assertNull(e.scope)
        assertNull("out of the kind", m.state.value.node)
        assertTrue(e.everything.isEmpty())
        assertTrue(e.kinds.none { it.node.category.family == CategoryTree.OSM })
        val rows = EveryKind.sorted(e.kinds, e.order, "en")
        assertEquals("the families lead", NodeKind.SHELF, rows.first().pulse.node.kind)
        val eat = rows.indexOfFirst { it.pulse.node.id == "shelf:eat" }
        assertEquals(0, rows[eat].depth)
        assertTrue("Eat's kinds under it", rows[eat + 1].depth > 0 && CategoryTree.isUnder(rows[eat + 1].pulse.node, rows[eat].pulse.node))
        m.pickForRow(HomeRow.idOf(rows[eat].pulse.node))
        assertNull("the list closes", m.state.value.every)
        until { m.state.value.prefs.row.last() == "eat" }
        // typed: the chain the words name, the kind they name
        m.openEvery(picking = true)
        val brand = Brands.byId("albertheijn-1cf55d")!!
        assertTrue(m.open(SearchItem.BrandResult(brand, "logo-albertheijn", Query.Category("supermarkets"), 3, 1.0), SearchResults("appie", emptyList())))
        assertNull(m.state.value.every)
        assertNull("nothing else opens", m.state.value.asked)
        until { ah in m.state.value.prefs.row }
        m.openEvery(picking = true)
        assertTrue(m.open(SearchItem.Kind("Bench", "maki-bench", Query.Tags(CategoryTree.byId("preset:amenity/bench")!!.filter), 4, 1.0), SearchResults("bench", emptyList())))
        until { "preset:amenity/bench" in m.state.value.prefs.row }
    }
}
