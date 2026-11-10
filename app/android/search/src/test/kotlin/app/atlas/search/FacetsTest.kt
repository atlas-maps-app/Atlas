package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FacetsTest {
    private val now = Fixtures.saturday.toLocalDateTime()

    /** The restaurants, cafés and snack bars within a kilometre of the Westerkerk. */
    private val places: List<PlaceRecord> by lazy {
        Fixtures.store.byCategory(listOf("restaurants"), null).filter { it.distanceTo(Fixtures.westerkerk) < 1_000 }
    }

    private fun context(lang: String = "en") = FacetContext(now, lang, HoursBook(places))

    @Test
    fun notASingleTagIsLeftOut() {
        val set = Facets.of(places, context = context())
        println("${places.size} places, ${set.facets.size} facets; best: " + set.facets.take(12).joinToString { "${it.label} (${it.mapped})" })
        assertTrue(places.size > 300)
        for (r in places) for ((k, v) in r.tags) {
            val id = when (k) {
                "opening_hours" -> Facets.OPEN
                "fee", "charge", "price" -> Facets.PRICE
                else -> k
            }
            val f = set.byId(id)
            assertNotNull("$k on ${r.id}", f)
            if (f!!.kind == FacetKind.FLAG || f.kind == FacetKind.VALUES) for (part in v.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val vid = if (f.kind == FacetKind.FLAG) Facts.stateOf(part)?.let { "~" + it.name.lowercase(Locale.ROOT) } ?: part else part
                assertTrue("$k=$part on ${r.id}", f.value(vid)!!.count > 0)
            }
        }
        // each facet counts every place once: by its values, or as not mapping it
        val diet = set.byId("diet:vegan")!!
        assertEquals(FacetKind.FLAG, diet.kind)
        assertEquals(places.size, diet.over)
        assertEquals(places.size - diet.mapped, diet.value(Facets.MISSING)!!.count)
        assertEquals("Vegan", diet.label)
        assertEquals(FacetSection.FOOD, diet.section)
        val cuisine = set.byId("cuisine")!!
        assertEquals(FacetKind.VALUES, cuisine.kind)
        assertEquals("Pizza", cuisine.value("pizza")!!.label)
        assertTrue(cuisine.values.first().count >= cuisine.values[1].count)
        assertEquals(FacetKind.MAPPED, set.byId("phone")!!.kind)
        assertEquals(FacetKind.VALUES, set.byId("addr:street")!!.kind)
        assertEquals("Street", set.byId("addr:street")!!.label)
        assertEquals(FacetSection.ADDRESS, set.byId("addr:street")!!.section)
        // the hours lead
        assertEquals(Facets.OPEN, set.facets.first().id)
        val wheelchair = set.byId("wheelchair")!!
        assertEquals("~yes", wheelchair.values.first().id)
        assertEquals("Step-free", wheelchair.values.first().chip)
    }

    @Test
    fun countsFollowWhatIsChosen() {
        val none = Facets.of(places, context = context())
        val vegan = FacetSelection.NONE.toggle("diet:vegan", "~yes").toggle("diet:vegan", "~only")
        val set = Facets.of(places, vegan, context())
        assertEquals(none.byId("diet:vegan")!!.value("~yes")!!.count + none.byId("diet:vegan")!!.value("~only")?.count.let { it ?: 0 }, set.kept.size)
        assertTrue(set.kept.all { Facts.stateOf(it.tags["diet:vegan"])?.name in setOf("YES", "ONLY") })
        // the chosen facet still counts over every place, so another of its values can be added
        assertEquals(places.size, set.byId("diet:vegan")!!.over)
        assertTrue(set.byId("diet:vegan")!!.value("~yes")!!.selected)
        // every other facet counts over the vegan places only
        val outdoor = set.byId("outdoor_seating")!!
        assertEquals(set.kept.size, outdoor.over)
        assertEquals(set.kept.count { Facts.stateOf(it.tags["outdoor_seating"])?.name == "YES" }, outdoor.value("~yes")?.count ?: 0)
        // or within a facet, and across facets
        val two = vegan.toggle("outdoor_seating", "~yes")
        val both = Facets.of(places, two, context())
        assertEquals(outdoor.value("~yes")!!.count, both.kept.size)
        assertTrue(both.kept.all { r -> Facets.keeps(r, two, context()) })
        val italianOrPizza = Facets.of(places, FacetSelection.NONE.toggle("cuisine", "italian").toggle("cuisine", "pizza"), context())
        assertEquals(places.count { r -> r.tags["cuisine"]?.split(';')?.map { it.trim() }?.any { it == "italian" || it == "pizza" } == true }, italianOrPizza.kept.size)
        // not mapped is a value too: the places a quest would ask about
        val unknown = Facets.of(places, FacetSelection.NONE.toggle("wheelchair", Facets.MISSING), context())
        assertTrue(unknown.kept.isNotEmpty() && unknown.kept.all { "wheelchair" !in it.tags })
        // a selection is kept as ids
        assertEquals(two, FacetSelection.of(two.ids()))
    }

    @Test
    fun openNowAtATimeAndByTheBranches() {
        val at = now.toLocalDate().plusDays(1).atTime(9, 0)
        val set = Facets.of(places, context = context().copy(at = at))
        val open = set.byId(Facets.OPEN)!!
        val ids = open.values.map { it.id }
        assertEquals("now", ids.first())
        assertEquals("at:$at", ids[1])
        assertEquals("Open Sun 09:00", open.values[1].label)
        assertTrue(open.value("now")!!.count in 1 until places.size)
        assertTrue(open.value("at:$at")!!.count < open.value("now")!!.count)
        assertTrue(open.value("tonight")!!.count > 0)
        // branches that map no hours are open by their brand's other branches, never confirmed
        assertTrue(open.value("now")!!.unconfirmed > 0)
        val nl = Facets.of(places, context = context("nl").copy(at = at)).byId(Facets.OPEN)!!
        assertEquals("Nu open", nl.values.first().label)
        assertEquals("Open zo 09:00", nl.values[1].label)
        // the brand's branches' hours fill in for one that maps none
        val book = HoursBook(places)
        val bare = places.first { it.brand != null && it.tags["opening_hours"] == null && book.of(it) != null }
        assertFalse(book.of(bare)!!.confirmed)
        println("${bare.name()} has no hours: by its brand ${book.of(bare)!!.hours.raw}")
    }

    @Test
    fun priceFreeAndBands() {
        fun place(id: Long, vararg tags: Pair<String, String>) = PlaceRecord.of(Place(OsmId(OsmType.NODE, id), LatLon(52.37, 4.88), mapOf("amenity" to "toilets") + tags))
        val toilets = listOf(
            place(1, "fee" to "no"),
            place(2, "fee" to "yes", "charge" to "0.50 EUR"),
            place(3, "fee" to "yes", "charge" to "€1,00"),
            place(4, "fee" to "yes"),
            place(5, "charge" to "EUR 3"),
            place(6),
        )
        val price = Facets.of(toilets, context = FacetContext(now)).byId(Facets.PRICE)!!
        assertEquals(listOf("free", "paid", "band:0-2", "band:2-5", Facets.MISSING), price.values.map { it.id })
        assertEquals(listOf(1, 4, 2, 1, 1), price.values.map { it.count })
        assertEquals("≤ 2 €", price.value("band:0-2")!!.label)
        assertEquals(FacetSection.COST, price.section)
        val free = Facets.of(toilets, FacetSelection.NONE.toggle(Facets.PRICE, "free"), FacetContext(now))
        assertEquals(listOf(1L), free.kept.map { it.id!!.id })
        assertEquals(Facets.Amount(2.5, "€"), Facets.amount("€2,50"))
        assertEquals(Facets.Amount(2.0, "€"), Facets.amount("EUR2"))
        assertNull(Facets.amount("yes"))
    }

    @Test
    fun chipsNarrowAndSpeakPlainly() {
        val chips = Facets.of(places, context = context()).chips(6)
        println("chips: " + chips.joinToString { "${it.second.chip} ${it.second.count}" })
        assertEquals("Open now", chips.first().second.chip)
        for ((f, v) in chips) assertTrue("${f.id}=${v.id}", v.count in 1 until f.over)
        // no names, addresses or mapping notes on chips
        assertTrue(chips.none { it.first.section in setOf(FacetSection.NAMES, FacetSection.ADDRESS, FacetSection.MAPPING, FacetSection.CONTACT) })
        // the chosen ones lead
        val sel = FacetSelection.NONE.toggle("outdoor_seating", "~yes")
        assertEquals("outdoor_seating", Facets.of(places, sel, context()).chips().first().first.id)
    }

    @Test
    fun aValueOnlyFoundElsewhereCanStillBeChosen() {
        val near = places.filter { it.distanceTo(Fixtures.westerkerk) < 250 }
        val far = places - near.toSet()
        val set = Facets.of(near, context = context(), elsewhere = far)
        val cuisine = set.byId("cuisine")!!
        val elsewhereOnly = cuisine.values.filter { it.count == 0 && it.elsewhere > 0 }
        assertTrue(elsewhereOnly.isNotEmpty())
        // near values first, then those only found further away
        val firstFar = cuisine.values.indexOfFirst { it.count == 0 }
        assertTrue(cuisine.values.drop(firstFar).all { it.count == 0 || it.id == Facets.MISSING })
    }
}
