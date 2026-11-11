package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.TagFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner, 4 November: no preset maximum distance, the closest match instead of nothing, hours guessed by kind. */
class ClosestTest {
    private val here = Fixtures.westerkerk
    private val saturday = Fixtures.saturday
    private val now = saturday.toLocalDateTime()

    /** A node [north] metres north of the Westerkerk. */
    private fun place(id: Long, north: Double, vararg tags: Pair<String, String>) =
        Place(OsmId(OsmType.NODE, id), LatLon(here.lat + north / 111_320.0, here.lon), mapOf(*tags))

    private fun bakery(id: Long, north: Double, hours: String?) =
        place(id, north, *listOfNotNull("shop" to "bakery", "name" to "Bakker $id", hours?.let { "opening_hours" to it }).toTypedArray())

    private val bakeries = listOf(
        bakery(1, 100.0, "Mo-Sa 07:00-18:00"), bakery(2, 200.0, "Mo-Sa 07:30-18:00"),
        bakery(3, 300.0, "Mo-Sa 07:00-17:30"), bakery(4, 400.0, "Tu-Sa 08:00-18:00"),
    )

    @Test
    fun aPlaceWithoutHoursTakesTheHoursOfItsKindAround() {
        val records = (bakeries + bakery(10, 250.0, null)).map { PlaceRecord.of(it) }
        val book = HoursBook(records)
        val known = book.of(records.last())!!
        assertEquals(HoursFrom.KIND, known.from)
        assertFalse(known.confirmed)
        // the lower medians: 07:00 to 18:00 on a Saturday, as most of them keep
        assertEquals(true, known.hours.isOpen(now))
        assertEquals(false, known.hours.isOpen(now.withHour(19)))
        assertEquals(HoursFrom.OWN, book.of(records.first())!!.from)
        // the evaluator finds it open now, unconfirmed; at seven in the evening it is not
        val mem = MemoryPlaces(bakeries + bakery(10, 250.0, null))
        val q = Query.and(Query.tags("shop=bakery"), Query.OpenAt(OpenTime.Now))
        val at = EvalContext(location = here, now = saturday)
        val bare = OfflineEvaluator(mem).run(q, at).hits.single { it.place.id?.id == 10L }
        assertTrue(bare.hoursInferred && bare.open!!.isOpen)
        assertTrue(OfflineEvaluator(mem).run(q, at.copy(now = saturday.withHour(19))).hits.none { it.place.id?.id == 10L })
        // the hours' facet counts it open now, never confirmed
        val open = Facets.of(records, context = FacetContext(now, hours = book)).byId(Facets.OPEN)!!.value("now")!!
        assertEquals(5, open.count)
        assertEquals(1, open.unconfirmed)
    }

    @Test
    fun aBrandComesFirstAndAKindRarelyMappedOrUsuallyOpenIsNotGuessed() {
        fun starbucks(id: Long, north: Double, hours: String?) = place(
            id, north, *listOfNotNull("amenity" to "cafe", "name" to "Starbucks", "brand" to "Starbucks", "brand:wikidata" to "Q37158", hours?.let { "opening_hours" to it }).toTypedArray(),
        )
        val cafes = (1..3).map { place(20L + it, it * 50.0, "amenity" to "cafe", "name" to "Café $it", "opening_hours" to "Mo-Su 10:00-16:00") }
        val branches = listOf(starbucks(1, 10.0, "Mo-Su 07:00-21:00"), starbucks(2, 20.0, "Mo-Su 07:00-21:00"), starbucks(3, 30.0, "Mo-Su 07:00-21:00"), starbucks(4, 40.0, null))
        val book = HoursBook((cafes + branches).map { PlaceRecord.of(it) })
        val bare = book.of(PlaceRecord.of(branches.last()))!!
        assertEquals(HoursFrom.BRAND, bare.from)
        assertEquals(true, bare.hours.isOpen(now.withHour(20)))
        // three cafés of thirteen map hours: too few to say what the others keep
        val unmapped = (1..10).map { PlaceRecord.of(place(40L + it, it * 30.0, "amenity" to "cafe", "name" to "Koffie $it")) }
        val rare = HoursBook(cafes.map { PlaceRecord.of(it) } + unmapped)
        assertNull(rare.of(unmapped.first()))
        // a park is open without hours
        val parks = (1..4).map { PlaceRecord.of(place(60L + it, it * 40.0, "leisure" to "park", "name" to "Park $it", "opening_hours" to "Mo-Su 08:00-20:00")) }
        val bareParks = PlaceRecord.of(place(70, 90.0, "leisure" to "park", "name" to "Park zonder uren"))
        assertNull(HoursBook(parks + bareParks).of(bareParks))
    }

    /** The owner, 6 November, of two Birkenstock stores, one mapped closed: "the other one is probably closed too". */
    @Test
    fun oneOtherBranchLendsItsHours() {
        fun birkenstock(id: Long, north: Double, hours: String?) = place(
            id, north, *listOfNotNull("shop" to "shoes", "name" to "Birkenstock", "brand" to "Birkenstock", "brand:wikidata" to "Q648458", hours?.let { "opening_hours" to it }).toTypedArray(),
        )
        val bare = birkenstock(2, 400.0, null)
        val book = HoursBook(listOf(birkenstock(1, 100.0, "Mo-Fr 10:00-18:00"), bare).map { PlaceRecord.of(it) })
        val lent = book.of(PlaceRecord.of(bare))!!
        assertEquals(HoursFrom.BRAND, lent.from)
        assertFalse(lent.confirmed)
        // closed on the Saturday as its other branch is, open on the Friday
        assertEquals(false, lent.hours.isOpen(now))
        assertEquals(true, lent.hours.isOpen(now.minusDays(1)))
    }

    @Test
    fun noPlaceAsChosenShowsTheClosestWithWhatEachMisses() {
        val pool = listOf(
            bakery(1, 200.0, "Mo-Su 14:50-18:00"), // opens in 20 minutes
            bakery(2, 100.0, "Mo-Su 16:30-18:00"), // in two hours
            bakery(3, 300.0, null), // no hours, and too few of its kind to guess them
        ).map { PlaceRecord.of(it) }
        val node = Browse.kindOf(pool)!!
        val open = FacetSelection.NONE.toggle(Facets.OPEN, "now")
        val r = Browse.results(node, pool, here, Moment(saturday), open)
        assertTrue(r.closest)
        assertEquals(listOf(1L, 3L, 2L), r.hits.map { it.record.id!!.id })
        assertEquals(listOf(Miss.Opens(now.withHour(14).withMinute(50), 20)), r.hits.first().misses)
        assertEquals(listOf(Miss.HoursUnknown), r.hits[1].misses)
        // what is as chosen is no closest match
        assertFalse(Browse.results(node, pool, here, Moment(saturday.withHour(17))).closest)
        // a circle none is in: the nearest beyond it, saying how far
        val circle = Browse.results(node, pool, here, Moment(saturday), circle = 90.0)
        assertTrue(circle.closest)
        val first = circle.hits.first()
        assertEquals(2L, first.record.id!!.id)
        assertEquals(10.0, (first.misses.single() as Miss.Beyond).metres, 2.0)
        // inside the circle only the places in it are results
        assertEquals(listOf(2L), Browse.results(node, pool, here, Moment(saturday), circle = 150.0).hits.map { it.record.id!!.id })
    }

    @Test
    fun aPriceAskedLowerShowsTheNearestDearer() {
        val toilets = listOf(
            place(1, 50.0, "amenity" to "toilets", "fee" to "yes", "charge" to "0.50 EUR"),
            place(2, 60.0, "amenity" to "toilets", "fee" to "yes", "charge" to "3 EUR"),
            place(3, 70.0, "amenity" to "toilets"),
        ).map { PlaceRecord.of(it) }
        val node = Browse.kindOf(toilets)!!
        val free = Browse.results(node, toilets, here, Moment(saturday), FacetSelection.NONE.toggle(Facets.PRICE, "free"))
        assertTrue(free.closest)
        val by = free.hits.associate { it.record.id!!.id to it.misses }
        assertEquals(listOf(Miss.Price(1)), by[1L])
        assertEquals(listOf(Miss.Price(2)), by[2L])
        assertTrue(by.getValue(3L).single() is Miss.Lacks && (by.getValue(3L).single() as Miss.Lacks).unsaid)
        assertEquals(2L, free.hits.last().record.id!!.id)
    }

    @Test
    fun aQueryPartsIntoWhatItsPlacesAreAndWhatTheyAreToMeet() {
        val q = Query.Sort(Query.and(Query.tags("amenity=cafe"), Query.tags("outdoor_seating=yes"), Query.OpenAt(OpenTime.Now), Query.InArea(null, "Jordaan")), SortBy.DISTANCE)
        val (base, needs) = Closest.split(q)!!
        assertEquals(Query.Sort(Query.and(Query.tags("amenity=cafe"), Query.InArea(null, "Jordaan")), SortBy.DISTANCE), base)
        assertEquals(listOf(Query.tags("outdoor_seating=yes"), Query.OpenAt(OpenTime.Now)), needs)
        assertNull(Closest.split(Query.tags("amenity=cafe")))
        val (rest, areas) = Closest.withoutAreas(base)
        assertEquals(Query.Sort(Query.tags("amenity=cafe"), SortBy.DISTANCE), rest)
        assertEquals(1, areas.size)

        val mem = MemoryPlaces(
            listOf(
                place(1, 100.0, "amenity" to "cafe", "name" to "Buiten", "outdoor_seating" to "yes", "opening_hours" to "Mo-Su 15:00-20:00"),
                place(2, 50.0, "amenity" to "cafe", "name" to "Binnen", "outdoor_seating" to "no", "opening_hours" to "Mo-Su 08:00-20:00"),
                place(3, 300.0, "amenity" to "cafe", "name" to "Later", "opening_hours" to "Mo-Su 18:00-20:00"),
            ),
        )
        val ctx = EvalContext(location = here, now = saturday)
        val (b, n) = Closest.split(Query.and(Query.tags("amenity=cafe"), Query.tags("outdoor_seating=yes"), Query.OpenAt(OpenTime.Now)))!!
        val hits = OfflineEvaluator(mem).closest(b, n, ctx).hits
        assertEquals(listOf("Buiten", "Binnen", "Later"), hits.map { it.record.name() })
        assertEquals(listOf(Miss.Opens(now.withHour(15).withMinute(0), 30)), hits[0].misses)
        assertEquals(Miss.Lacks("outdoor_seating", (hits[1].misses.single() as Miss.Lacks).label, (hits[1].misses.single() as Miss.Lacks).symbol, unsaid = false), hits[1].misses.single())
        assertEquals(2, hits[2].misses.size)
    }

    @Test
    fun nothingAsAskedAnywhereShowsTheClosestAndACountStaysAsAsked() {
        val engine = SearchEngine(Fixtures.store)
        val ctx = SearchContext(location = here, lang = "en", now = saturday)
        val text = "shop=bakery and diet:vegan=only and toilets=yes and wheelchair=yes"
        val r = runBlocking { engine.answer(text, ctx) }!!
        val s = r.items.filterIsInstance<SearchItem.Structured>().single()
        val hits = s.results.first().hits
        assertTrue(hits.isNotEmpty() && hits.all { it.tags["shop"] == "bakery" })
        assertTrue(s.loosened.isNotEmpty() && s.loosened.all { it.aspect == LooseAspect.WISH })
        assertTrue(hits.all { it.misses.isNotEmpty() })
        assertTrue(hits.zipWithNext().all { (a, b) -> a.misses.size <= b.misses.size })
        // a loosened answer says what each misses too: the bakeries by when they open
        val late = runBlocking { engine.answer("bakery open now", ctx.copy(now = saturday.withHour(4))) }!!
        val night = late.items.filterIsInstance<SearchItem.Structured>().single().results.first().hits
        assertTrue(night.isNotEmpty() && night.all { h -> h.misses.single().let { it is Miss.Opens || it is Miss.HoursUnknown } })
        // how many: none as asked, the closest shown
        val count = runBlocking { engine.answer("how many bakeries open now", ctx.copy(now = saturday.withHour(4))) }!!
        assertEquals(0, count.items.filterIsInstance<SearchItem.Count>().single().answer.total)
        assertTrue(count.items.filterIsInstance<SearchItem.Structured>().single().results.first().hits.isNotEmpty())
    }

    @Test
    fun aKindNoneOfWhichIsNearIsLookedForWhereverTheRegionsHoldIt() {
        // more cafés than a seed is read at once for, all of them 60 km away: the nearest still come
        val far = (0 until (OfflineEvaluator.WIDE_SEED.toInt() + 50)).map { i ->
            Place(OsmId(OsmType.NODE, 1_000L + i), LatLon(here.lat + 0.54 + (i % 100) * 0.0001, here.lon + (i / 100) * 0.0001), mapOf("amenity" to "cafe"))
        }
        val hits = OfflineEvaluator(MemoryPlaces(far)).run(Query.Tags(TagFilter.Equals("amenity", "cafe")), EvalContext(location = here, now = saturday, limit = 5)).hits
        assertEquals(5, hits.size)
        assertTrue(hits.all { it.distance!! in 55_000.0..70_000.0 })
    }
}
