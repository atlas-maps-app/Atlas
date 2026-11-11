package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.CategoryTree
import app.atlas.search.Browse
import app.atlas.search.EveryKind
import app.atlas.search.FacetSelection
import app.atlas.search.Facets
import app.atlas.search.KindView
import app.atlas.search.MemoryPlaces
import app.atlas.search.Miss
import app.atlas.search.ResultItem
import app.atlas.search.SearchContext
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.Sure
import app.atlas.search.TellKind
import kotlinx.coroutines.runBlocking
import java.time.ZonedDateTime
import app.atlas.services.PlaceOrigin
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The one search's model on the Amsterdam extract at the Westerkerk, on a Tuesday evening, offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchSheetModelTest {
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

    @Test
    fun theGridSaysWhatIsNear() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        assertTrue("nothing is read before the search opens", m.state.value.pulses.isEmpty())
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.pulses.isNotEmpty() }
        val s = m.state.value
        assertEquals(PlaceOrigin.STORE, s.gridOrigin)
        val restaurants = s.pulses.getValue("cat:restaurants")
        assertTrue("restaurants: ${restaurants.count}", restaurants.dense)
        // a Tuesday evening: how long the many restaurants near stay open
        assertEquals(TellKind.OPEN_TILL, restaurants.tell)
        assertTrue(restaurants.value!!.matches(Regex("\\d\\d:\\d\\d")))
        // the shelves count their kinds' places, and the Now shelf has what fits a Tuesday evening
        assertTrue(s.pulses.getValue("shelf:eat").count >= restaurants.count)
        assertTrue("now: ${s.now.map { it.node.id }}", s.now.isNotEmpty())
    }

    @Test
    fun aKindOpensWithItsPlacesKindsAndFilters() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.enter("drinks")
        until { m.state.value.results != null && !m.state.value.loading }
        val r = m.state.value.results!!
        assertEquals("cat:drinks", r.node.id)
        assertEquals(PlaceOrigin.STORE, m.state.value.origin)
        assertTrue("${r.hits.size} bars near", r.hits.size >= 8)
        assertTrue(r.subcategories.any { it.second > 0 })
        // open now counts what it keeps
        val open = r.facets.byId(Facets.OPEN)!!.value("now")!!
        m.toggle(Facets.OPEN, "now")
        until { m.state.value.results?.facets?.byId(Facets.OPEN)?.value("now")?.selected == true }
        assertEquals(open.count, m.state.value.results!!.hits.size)
        // narrowing to a narrower kind keeps what was chosen; up goes back to the kind
        val pub = r.subcategories.first { it.second > 0 }.first
        m.enter(pub.id)
        until { m.state.value.results?.node?.id == pub.id }
        assertTrue(m.state.value.selection.has(Facets.OPEN, "now"))
        m.up()
        until { m.state.value.results?.node?.id == "cat:drinks" }
        m.close()
        assertNull(m.state.value.node)
    }

    @Test
    fun aKeptFilterStaysOnInTheNextKind() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.enter("drinks")
        until { m.state.value.results != null && !m.state.value.loading }
        m.keep("wheelchair", "~yes")
        until { "wheelchair=~yes" in m.state.value.prefs.sticky }
        assertTrue("on at once in the kind open", m.state.value.selection.has("wheelchair", "~yes"))
        m.enter("restaurants")
        until { m.state.value.results?.node?.id == "cat:restaurants" && !m.state.value.loading }
        assertTrue(m.state.value.selection.has("wheelchair", "~yes"))
        assertTrue(m.state.value.results!!.hits.all { it.record.tags["wheelchair"] == "yes" || it.record.tags["wheelchair"] == "designated" })
        // let go, the kind shows every place again
        m.keep("wheelchair", "~yes")
        until { m.state.value.prefs.sticky.isEmpty() }
        assertTrue(!m.state.value.selection.has("wheelchair", "~yes"))
    }

    /** What the field finds for [text] at the Westerkerk, as the sheet's typed words do. */
    private fun typed(services: Services, text: String): SearchResults = runBlocking {
        services.search.search(text, SearchContext(location = Services.WESTERKERK, lang = "en", now = ZonedDateTime.now(services.clock), limit = SearchViewModel.LIMIT))
    }

    @Test
    fun typedWordsOpenInTheSheetOverTheMap() {
        val services = testServices(places = true)
        val m = SearchSheetModel(services, Services.WESTERKERK, "en")
        // a query of its own: every place it finds, not only the field's few, parted by how surely each has what it wants
        val cafes = typed(services, "cafe with toilets")
        assertTrue(m.open(cafes.items.filterIsInstance<SearchItem.Structured>().single(), cafes))
        until { m.state.value.results != null && !m.state.value.loading }
        val words = m.state.value.asked as Asked.Words
        assertEquals("cafe with toilets", words.text)
        val r = m.state.value.results!!
        assertEquals("preset:amenity/cafe", m.state.value.node?.id)
        assertTrue("${r.hits.size} cafés", r.hits.size > SearchViewModel.LIMIT)
        assertEquals(Sure.YES, r.parts.first().sure)
        // every tag of them a filter with its count, and a value chosen keeps those it says
        val open = r.facets.byId(Facets.OPEN)!!.value("now")!!
        m.toggle(Facets.OPEN, "now")
        until { m.state.value.results?.facets?.byId(Facets.OPEN)?.value("now")?.selected == true }
        assertEquals(open.count, m.state.value.results!!.hits.size)
        // the kinds among them narrow them, and up steps out of it, then out of the words
        val kind = r.subcategories.first().first
        m.narrow(kind)
        until { m.state.value.within?.id == kind.id && m.state.value.results?.hits?.all { app.atlas.search.Browse.holds(kind, it.record) } == true }
        m.up()
        until { m.state.value.within == null }
        m.close()
        assertNull(m.state.value.asked)

        // all a kind's filters: the kind's own view with them chosen
        val vegan = typed(services, "vegan restaurant open now")
        assertTrue(m.open(vegan.items.filterIsInstance<SearchItem.Structured>().single(), vegan))
        until { m.state.value.results != null && !m.state.value.loading }
        assertNull(m.state.value.asked)
        assertEquals("preset:amenity/restaurant", m.state.value.node?.id)
        assertTrue(m.state.value.selection.has(Facets.OPEN, "now"))
        assertTrue(m.state.value.selection.chosen.keys.any { it.startsWith("diet:vegan") })
    }

    @Test
    fun brandsGoodsOutingsAndCountsOpenInTheSheet() {
        val services = testServices(places = true)
        val m = SearchSheetModel(services, Services.WESTERKERK, "en")
        // a brand's branches one by one
        val ah = typed(services, "albert heijn")
        val brand = ah.items.filterIsInstance<SearchItem.BrandResult>().first()
        println("brands: " + ah.items.filterIsInstance<SearchItem.BrandResult>().joinToString { "${it.brand.id} ${it.count}" })
        assertTrue(m.open(brand, ah))
        until { m.state.value.results != null && !m.state.value.loading }
        val branches = m.state.value.results!!
        assertTrue(m.state.value.asked is Asked.Chain)
        println("branches: ${branches.hits.size} within ${m.state.value.radius} m, ${branches.hits.map { it.record.brand }.distinct()}")
        assertTrue("${branches.hits.size} branches", branches.hits.size >= minOf(brand.count, 3))
        assertTrue(branches.items.none { it is ResultItem.Bundle })
        assertTrue(branches.hits.all { it.record.brand == brand.brand.id })
        // goods: the places that surely sell them first
        val hooks = typed(services, "picture hooks")
        assertTrue(m.open(hooks.items.filterIsInstance<SearchItem.GoodsResult>().first(), hooks))
        until { m.state.value.results != null && !m.state.value.loading && m.state.value.asked is Asked.Sells }
        val sells = m.state.value.results!!
        assertTrue(sells.parts.isNotEmpty())
        assertEquals(sells.parts.map { it.sure }.sorted(), sells.parts.map { it.sure })
        // an outing: its stops timed from now
        val outing = typed(services, "coffee then dinner")
        val plan = outing.items.filterIsInstance<SearchItem.Structured>().single()
        assertTrue(plan.parsed.isPlan)
        assertTrue(m.open(plan, outing))
        until { (m.state.value.asked as? Asked.Outing)?.plan != null }
        assertEquals(2, (m.state.value.asked as Asked.Outing).plan!!.stops.size)
        // the next best places for a stop, one taken in its place
        val first = (m.state.value.asked as Asked.Outing).plan!!.stops.first()
        assertTrue(first.options.isNotEmpty() && first.options.none { it.first.place.key == first.hit?.place?.key })
        val other = first.options.first().first
        m.pick(first.step, other)
        until { (m.state.value.asked as? Asked.Outing)?.plan?.stops?.first()?.hit?.place?.key == other.place.key }
        m.refine(Refine.OPEN)
        until { (m.state.value.asked as? Asked.Outing)?.let { it.plan != null && Refine.OPEN in it.refine } == true }
        // how many: the places counted, with the count
        val benches = typed(services, "how many benches in this town")
        assertTrue(m.open(benches.items.filterIsInstance<SearchItem.Count>().single(), benches))
        until { m.state.value.results != null && !m.state.value.loading }
        val count = (m.state.value.asked as Asked.Words).count!!
        assertTrue("${count.total} benches", count.total > 20)
        assertEquals(count.total, m.state.value.results!!.hits.size)
        assertTrue(m.state.value.results!!.hits.all { it.record.tags["amenity"] == "bench" || it.record.tags["leisure"] == "picnic_table" || "bench" in it.record.tags })
    }

    /**
     * A brand's page and the ways back out of it: one its very name typed opened gives the words back to the field, one
     * a sign opened goes back to the grid, one reached from another chain's page goes back to that page.
     */
    @Test
    fun aBrandsPageStepsBackTheWayItCame() {
        val services = testServices(places = true)
        val m = SearchSheetModel(services, Services.WESTERKERK, "en")
        val ah = typed(services, "albert heijn")
        val named = ah.items.filterIsInstance<SearchItem.BrandResult>().first()
        assertTrue("$named", named.named)
        assertTrue(m.open(named, ah))
        assertEquals(true, (m.state.value.asked as? Asked.Chain)?.typed)
        assertEquals("albert heijn", m.backFromAsked())
        assertNull(m.state.value.asked)
        m.chain(named.brand)
        m.sibling(Brands.byId("jumbo-1cf55d")!!)
        assertEquals(named.brand.id, (m.state.value.asked as Asked.Chain).from?.brand?.id)
        assertNull(m.backFromAsked())
        assertEquals(named.brand.id, (m.state.value.asked as? Asked.Chain)?.brand?.id)
        assertNull(m.backFromAsked())
        assertNull(m.state.value.asked)
    }

    /**
     * A kind's row of narrower kinds pulled open ([SearchSheetModel.unfold]) stays open when one comes back up to it
     * from a narrower kind, and folds when the sheet goes back to the grid.
     */
    /**
     * One home, more doors (the owner, 6 November: "Doesnt sports somehow also belong in fun?", "A bakery might sell
     * pastries"): a kind Fun holds too and Sport at the end of its row open from it, back coming back to Fun; the
     * kinds that also sell a pastry shop's cakes open from its view, back coming back to it and on up the way it came.
     */
    @Test
    fun doorsLeadOnAndBackComesBack() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.pulses.isNotEmpty() }
        fun at(id: String) = until { m.state.value.results?.node?.id == id && !m.state.value.loading }
        m.enter("fun")
        at("shelf:fun")
        for (door in listOf("cat:climbing", "shelf:sport")) {
            m.enter(door)
            at(door)
            m.up()
            at("shelf:fun")
        }
        m.up()
        assertNull(m.state.value.node)
        m.enter("groceries")
        at("shelf:groceries")
        m.enter("preset:shop/pastry")
        until { m.state.value.alsoSold.isNotEmpty() }
        val also = m.state.value.alsoSold
        assertEquals(listOf("preset:shop/bakery", "preset:shop/supermarket"), also.map { it.first.id })
        assertTrue(also.all { it.second > 0 })
        m.enter("preset:shop/bakery", door = true)
        at("preset:shop/bakery")
        assertTrue("a bakery is named for nothing of the catalogue", m.state.value.alsoSold.isEmpty())
        m.up()
        at("preset:shop/pastry")
        // on up as it came: to the groceries, not first to the look-alike kind of their one category
        m.up()
        at("shelf:groceries")
    }

    /**
     * Kinds that belong to each other name each other (the owner, 6 November: "Miniature golf is in fun. Where is golf?
     * Link kinds that belong to each other."): a bike shop's view names the hire, the parking and the repair stands,
     * those around first with how many there are, the one none of is near last; a press opens one, whose view names the
     * shop, and back comes back.
     */
    @Test
    fun aKindNamesTheKindsThatBelongWithIt() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.pulses.isNotEmpty() }
        fun at(id: String) = until { m.state.value.results?.node?.id == id && !m.state.value.loading }
        m.enter("preset:shop/bicycle")
        until { m.state.value.seeAlso.isNotEmpty() }
        val see = m.state.value.seeAlso
        assertEquals(listOf("preset:amenity/bicycle_rental", "cat:bike_parking", "preset:amenity/bicycle_repair_station"), see.map { it.first.id })
        assertEquals(listOf(true, true, false), see.map { it.second > 0 })
        m.enter("preset:amenity/bicycle_rental", door = true)
        at("preset:amenity/bicycle_rental")
        until { m.state.value.seeAlso.isNotEmpty() }
        assertEquals("preset:shop/bicycle", m.state.value.seeAlso.first().first.id)
        m.up()
        at("preset:shop/bicycle")
        until { m.state.value.seeAlso.isNotEmpty() }
        assertEquals(see.map { it.first.id }, m.state.value.seeAlso.map { it.first.id })
    }

    /**
     * A kind's view shows its kin's places too when asked, and keeps that (the owner, 6 November: "Fill the view with them
     * and save that as a combined view if desired?", and of the cash machines "a bank is almost always an atm too"): the
     * pastry shops' view takes in the bakeries and the supermarkets, their places among its results, its row of kinds its
     * own; left and opened again it shows them still, and switched off, its own places alone. A cash machine's kin are the
     * banks and the change offices.
     */
    @Test
    fun aKindsViewShowsItsKinTooAndKeepsThat() {
        assertEquals(
            listOf("preset:amenity/bank", "preset:amenity/bureau_de_change"),
            SearchSheetModel.kin(CategoryTree.preset("amenity/atm")!!).map { it.id },
        )
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.pulses.isNotEmpty() }
        val pastry = CategoryTree.preset("shop/pastry")!!
        val kin = SearchSheetModel.kin(pastry)
        assertEquals(listOf("preset:shop/bakery", "preset:shop/supermarket"), kin.map { it.id })
        fun kinIn() = m.state.value.results?.hits.orEmpty().count { h -> kin.any { Browse.holds(it, h.record) } }
        fun settled() = until { m.state.value.results?.node?.id == pastry.id && !m.state.value.loading }
        m.enter(pastry.id)
        settled()
        val own = m.state.value.results!!.hits.map { it.record.id }.toSet()
        assertTrue(own.isNotEmpty() && kinIn() == 0)
        m.together(pastry, true)
        until { m.state.value.prefs.together == setOf(pastry.id) && kinIn() > 0 && !m.state.value.loading }
        val both = m.state.value.results!!
        assertTrue(both.hits.all { h -> Browse.holds(pastry, h.record) || kin.any { Browse.holds(it, h.record) } })
        assertTrue(both.subcategories.none { (k, _) -> kin.any { it.id == k.id } })
        // kept: away and back, they are there still
        m.enter("cat:restaurants")
        until { m.state.value.results?.node?.id == "cat:restaurants" && !m.state.value.loading }
        m.enter(pastry.id)
        settled()
        assertTrue(kinIn() > 0)
        m.together(pastry, false)
        until { m.state.value.prefs.together.isEmpty() && kinIn() == 0 && !m.state.value.loading }
        assertEquals(own, m.state.value.results!!.hits.map { it.record.id }.toSet())
    }

    @Test
    fun aKindsRowPulledOpenStaysOpen() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        m.enter("shops")
        until { m.state.value.results != null && !m.state.value.loading }
        val shops = m.state.value.node!!
        m.unfold(shops.id, true)
        val sub = m.state.value.results!!.subcategories.first { it.second > 0 }.first
        m.enter(sub.id)
        until { m.state.value.results?.node?.id == sub.id && !m.state.value.loading }
        m.up()
        until { m.state.value.node?.id == shops.id }
        assertTrue("open still, back up from ${sub.id}", shops.id in m.state.value.unfolded)
        m.unfold(shops.id, false)
        assertTrue(m.state.value.unfolded.isEmpty())
        m.unfold(shops.id, true)
        m.close()
        assertTrue("folded at the grid", m.state.value.unfolded.isEmpty())
    }

    /**
     * The plain list of every kind (the owner, 6 November: "a plain all kinds list row sortable with every tag/kind that
     * exists"): from the grid every kind around, sorted and narrowed; a kind on it opens and the way back is the list as
     * it was left, then the grid; from inside a kind every kind under it, the way back to that kind.
     */
    @Test
    fun theListOfEveryKindSortsAndLeadsBack() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        until { m.state.value.kinds.isNotEmpty() }
        m.openEvery()
        val all = m.state.value.every!!
        assertNull(all.scope)
        assertTrue("${all.kinds.size} kinds around", all.kinds.size > 40)
        // everything else on the map (a road, a canal) is behind a row of its own, as on the grid
        assertTrue(all.kinds.none { it.node.category.family == CategoryTree.OSM })
        assertTrue(all.everything.isNotEmpty() && all.everything.all { it.node.category.family == CategoryTree.OSM })
        // the chains behind a kind come with it: Albert Heijn behind the supermarkets
        assertTrue(all.chains["preset:shop/supermarket"].orEmpty().any { it.startsWith("albertheijn") })
        m.everyOrder(EveryKind.Order.NAME)
        m.everyOpen(true)
        val clothes = all.kinds.first { it.node.id == "preset:shop/clothes" }.node
        m.enterFromEvery(clothes.id)
        until { m.state.value.results?.node?.id == clothes.id && !m.state.value.loading }
        assertEquals(clothes.id, m.state.value.every?.entered)
        m.up()
        assertNull(m.state.value.node)
        m.state.value.every!!.let { e ->
            assertEquals(EveryKind.Order.NAME, e.order)
            assertTrue("still only those open", e.openOnly)
            assertNull(e.entered)
        }
        m.enterFromEvery(Browse.EVERYTHING.id)
        until { m.state.value.results?.node?.id == Browse.EVERYTHING.id && !m.state.value.loading }
        m.up()
        assertNull(m.state.value.node)
        assertEquals(EveryKind.Order.NAME, m.state.value.every?.order)
        m.up()
        assertNull(m.state.value.every)
        assertNull(m.state.value.node)
        // inside a kind: every kind among its places, in its place on the sheet
        m.enter("shops")
        until { m.state.value.results != null && !m.state.value.loading }
        val shops = m.state.value.node!!
        m.openEvery()
        until { m.state.value.every?.kinds?.isNotEmpty() == true }
        val under = m.state.value.every!!
        assertEquals(shops.id, under.scope?.id)
        assertNull(m.state.value.node)
        // its own and those it holds too (a market)
        assertTrue(
            "${under.kinds.size} kinds of shop",
            under.kinds.size > 10 && under.kinds.all { k -> CategoryTree.isUnder(k.node, shops) || shops.guests.any { CategoryTree.isUnder(k.node, it) } },
        )
        m.up()
        until { m.state.value.results?.node?.id == shops.id && !m.state.value.loading }
        assertNull(m.state.value.every)
    }

    /**
     * A kind's list holds every kind it holds, near or not (the owner, 6 November: "Theres no way to see every kind that
     * belongs to a category from within that category. Only the ones that exist nearby"), and looking further finds the
     * nearest of each of those none of which is near, wherever it is ("no way to then extend the search until results
     * for every kind turn up").
     */
    @Test
    fun aKindsListHoldsEveryKindAndLooksFurther() {
        val w = Services.WESTERKERK
        fun node(id: Long, north: Double, vararg tags: Pair<String, String>) = Place(OsmId(OsmType.NODE, id), LatLon(w.lat + north, w.lon), mapOf(*tags))
        // a store that holds the Westerkerk: playgrounds around it
        val near = listOf(-0.001, 0.001, 0.002).mapIndexed { i, north -> node(i + 1L, north, "leisure" to "playground") }
        // a bowling alley and an ice rink 60 km off, none of either near
        val far = listOf(node(11, 0.54, "leisure" to "bowling_alley", "name" to "Bowling"), node(12, 0.55, "leisure" to "ice_rink", "name" to "IJsbaan"))
        val m = SearchSheetModel(testServices(stores = listOf(MemoryPlaces(near + far))), w, "en")
        val outings = CategoryTree.shelf("fun")!!
        m.enter(outings.id)
        until { m.state.value.results != null && !m.state.value.loading }
        // as many near as Fun's view found in its circle
        val shown = m.state.value.results!!.hits.size
        m.openEvery()
        until { m.state.value.every?.kinds?.isNotEmpty() == true }
        val every = m.state.value.every!!
        val ids = every.kinds.map { it.node.id }
        assertTrue(EveryKind.of(outings).all { it.id in ids })
        assertTrue(shown > 0)
        assertEquals(shown, every.kinds.first { it.node.id == "preset:leisure/playground" }.count)
        every.kinds.first { it.node.id == "preset:leisure/bowling_alley" }.let { assertTrue(it.count == 0 && it.nearest == null) }
        assertEquals(Further.NOT_YET, every.further)
        m.lookFurther()
        until { m.state.value.every?.further == Further.DONE }
        val done = m.state.value.every!!
        for (id in listOf("preset:leisure/bowling_alley", "preset:leisure/ice_rink")) {
            val p = done.kinds.first { it.node.id == id }
            assertTrue("$id ${p.metres}", p.count == 0 && p.metres!! in 59_000.0..62_000.0)
        }
        // none of those none of which is near is left: each turned up somewhere, or nowhere; offline, nowhere is only
        // not on the phone
        assertTrue(done.kinds.filter { it.count == 0 }.all { it.nearest != null || it.node.id in done.nowhere })
        assertTrue(done.nowhere.isEmpty() || done.unasked)
        assertEquals("preset:leisure/playground", EveryKind.sorted(done.kinds, EveryKind.Order.MOST, "en").first().pulse.node.id)
        // a kind opened from the list shows its nearest, and back is the list as it was left
        m.enterFromEvery("preset:leisure/bowling_alley")
        until { m.state.value.results?.hits?.isNotEmpty() == true && !m.state.value.loading }
        m.up()
        assertEquals(Further.DONE, m.state.value.every?.further)
        assertEquals(done.nowhere, m.state.value.every?.nowhere)
    }

    @Test
    fun aKindNoneOfWhichIsNearShowsTheNearestWhereverTheyAre() {
        // the owner, 4 November: "Default should be no maximum distance pre set if there are no results"
        val w = Services.WESTERKERK
        fun node(id: Long, north: Double, east: Double, vararg tags: Pair<String, String>) = Place(OsmId(OsmType.NODE, id), LatLon(w.lat + north, w.lon + east), mapOf(*tags))
        // a store that holds the Westerkerk: cafés around it
        val near = listOf(-0.001 to -0.001, 0.001 to 0.001, -0.001 to 0.001).mapIndexed { i, (n, e) -> node(i + 1L, n, e, "amenity" to "cafe", "name" to "Café $i") }
        // no alpine hut is in the store or the map's packs around Amsterdam: these three are 60 km off
        val far = (1..3).map { node(10L + it, 0.54 + it * 0.001, 0.0, "tourism" to "alpine_hut", "name" to "Hut $it") }
        val services = testServices(stores = listOf(MemoryPlaces(near + far)))
        val m = SearchSheetModel(services, w, "en")
        m.enter(CategoryTree.of(mapOf("tourism" to "alpine_hut"))!!.id)
        until { m.state.value.results?.hits?.isNotEmpty() == true && !m.state.value.loading && m.state.value.noneWithin != null }
        val s = m.state.value
        assertTrue("none within ${s.noneWithin}", s.noneWithin!! >= SearchSheetModel.MAX_RADIUS_M)
        assertEquals(3, s.results!!.hits.size)
        assertTrue("60 km away: ${s.results!!.hits.map { it.metres }}", s.results!!.hits.all { it.metres > 55_000 })
        assertTrue("the reach is the circle used: ${s.radius}", s.radius >= s.results!!.hits.maxOf { it.metres })
    }

    @Test
    fun aKindNoneOfWhichIsAnywhereStopsLooking() {
        // no frozen food shop is in the canal ring's store, nor anywhere on a phone offline: the view says none, it
        // does not look for ever, and names the kinds near that sell it too
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.moveTo(Services.WESTERKERK)
        m.enter("preset:shop/frozen_food")
        until { m.state.value.results != null && !m.state.value.loading && m.state.value.alsoSold.isNotEmpty() }
        val s = m.state.value
        assertTrue(s.results!!.hits.isEmpty())
        assertNull(s.noneWithin)
        assertEquals(listOf("preset:shop/supermarket", "preset:shop/convenience"), s.alsoSold.map { it.first.id })
    }

    @Test
    fun aCircleAskedForThatHoldsNoneShowsTheClosestBeyondIt() {
        val m = SearchSheetModel(testServices(places = true), Services.WESTERKERK, "en")
        m.enter(KindView(CategoryTree.category("drinks")!!, FacetSelection.NONE, radius = 10.0))
        until { m.state.value.results != null && !m.state.value.loading }
        val s = m.state.value
        val r = s.results!!
        assertTrue(r.closest)
        assertTrue(r.hits.isNotEmpty() && r.hits.all { h -> h.misses.any { it is Miss.Beyond } })
        val beyond = r.hits.map { h -> h.misses.filterIsInstance<Miss.Beyond>().single().metres }
        assertTrue("the nearest beyond it first: ${beyond.take(5)}", beyond.first() <= beyond.max())
        assertTrue("the circle used, wider than asked: ${s.radius}", s.radius > 10.0)
        assertNull(s.noneWithin)
    }
}
