package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.OpeningHours
import app.atlas.places.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class OfflineEvaluatorTest {
    private val store get() = Fixtures.store
    private val evaluator by lazy { OfflineEvaluator(store) }
    private val ctx = Fixtures.context().copy(limit = 1000)

    private fun run(q: Query, c: EvalContext = ctx) = evaluator.run(q, c)
    private fun all(filter: String) = store.byTag(TagFilter.parse(filter).keys().first(), null).filter { TagFilter.parse(filter).matches(it.tags) }

    @Test
    fun tagQueriesMatchABruteForceScan() {
        for (text in listOf("amenity=cafe and outdoor_seating=yes", "cuisine~pizza", "amenity~^(bar|pub)$ and not cuisine", "shop=bakery or shop=pastry", "diet:vegan=yes")) {
            val f = TagFilter.parse(text)
            val expected = store.inBox(store.bounds!!).filter { f.matches(it.tags) }.map { it.place.key }.toSet()
            val got = run(Query.Tags(f)).hits.map { it.place.key }.toSet()
            assertEquals(text, expected, got)
            assertTrue(text, expected.isNotEmpty())
        }
    }

    @Test
    fun aNearBKeepsTheNearestBAndItsDistance() {
        val q = QueryParser.parse("cafe near bicycle rental 200m").query
        val hits = run(q).hits
        val rentals = all("amenity=bicycle_rental")
        val cafes = all("amenity=cafe")
        val expected = cafes.filter { c -> rentals.any { it.distanceTo(c.location) <= 200 } }.map { it.place.key }.toSet()
        assertEquals(expected, hits.map { it.place.key }.toSet())
        assertTrue(hits.size >= 5)
        for (h in hits) {
            val n = h.near.single()
            assertEquals(rentals.minOf { it.distanceTo(h.record.location) }, n.distance, 0.01)
            assertTrue(n.distance <= 200)
            assertEquals("bicycle_rental", n.record.tags["amenity"])
        }
        // ranked: closer to the searcher and to the dock comes first
        assertTrue(hits.first().distance!! < 150)
    }

    @Test
    fun aNearBNearCChainFromTheQueryBoard() {
        val rentals = all("amenity=bicycle_rental")
        val parks = all("leisure=park")
        fun expected(dock: Double, park: Double) = all("amenity=cafe").filter { c ->
            rentals.any { it.distanceTo(c.location) <= dock } && parks.any { it.distanceTo(c.location) <= park }
        }.map { it.place.key }.toSet()
        // the board's words, with the defaults (200 m each): around the Westerkerk the parks are a little further
        val board = run(QueryParser.parse("café near a bike dock & a park").query)
        assertEquals(expected(200.0, 200.0), board.hits.map { it.place.key }.toSet())
        // the same chain with its own distances
        val q = QueryParser.parse("café 200 m from a bike dock and 400 m from a park").query
        val chain = (q as Query.Near)
        assertEquals(400.0, chain.maxDistance, 0.0)
        assertEquals(200.0, (chain.subject as Query.Near).maxDistance, 0.0)
        val result = run(q)
        val want = expected(200.0, 400.0)
        assertTrue("some cafés sit between a dock and a park", want.size >= 4)
        assertEquals(want, result.hits.map { it.place.key }.toSet())
        for (h in result.hits) {
            assertEquals(2, h.near.size)
            assertEquals("bicycle_rental", h.near[0].record.tags["amenity"])
            assertEquals("park", h.near[1].record.tags["leisure"])
            // a park is an area: the distance is to its outline's box, never more than to its centre
            assertTrue(h.near[1].distance <= Geo.distance(h.record.location, h.near[1].record.location) + 0.01)
        }
    }

    @Test
    fun notNearExcludes() {
        val q = Query.NotNear(Query.tags("amenity=cafe"), Query.tags("amenity~^(bar|pub)$"), 50.0)
        val bars = all("amenity~^(bar|pub)$")
        val hits = run(Query.and(q, Query.Within(Fixtures.westerkerk, 800.0))).hits
        assertTrue(hits.isNotEmpty())
        for (h in hits) assertTrue(bars.none { it.distanceTo(h.record.location) <= 50 })
        val cafesNearBars = all("amenity=cafe").count { c -> Geo.distance(c.location, Fixtures.westerkerk) <= 800 && bars.any { it.distanceTo(c.location) <= 50 } }
        assertTrue(cafesNearBars > 0)
    }

    @Test
    fun openNowAtAFixedInstant() {
        val q = QueryParser.parse("vegan restaurant open now").query
        val hits = run(q).hits
        assertTrue(hits.size >= 5)
        val at = Fixtures.saturday.toLocalDateTime()
        for (h in hits) {
            assertTrue(h.tags["diet:vegan"] in setOf("yes", "only"))
            assertEquals(true, OpeningHours.parse(h.tags.getValue("opening_hours")).isOpen(at))
            assertTrue(h.open!!.isOpen)
        }
        // at four in the morning far fewer are open
        val night = run(q, ctx.copy(now = Fixtures.saturday.withHour(4))).hits
        assertTrue(night.size < hits.size)
        // on Sunday: open at any time of the day
        val sunday = run(Query.and(Query.tags("amenity=restaurant"), Query.OpenAt(OpenTime.OnDay(DayOfWeek.SUNDAY))))
        assertTrue(sunday.total > 50)
    }

    @Test
    fun toiletsUsuallyOpenWhenNoHoursAreMapped() {
        val q = QueryParser.parse("step-free toilet, open now").query
        val hits = run(q).hits
        assertTrue(hits.any { it.place.id == OsmId(OsmType.NODE, 5831919166) }) // Westerkerk WC, no hours mapped
        assertTrue(hits.all { it.tags["wheelchair"] in setOf("yes", "designated") || it.tags["toilets:wheelchair"] == "yes" })
    }

    @Test
    fun withinAreaRouteSortAndLimit() {
        val within = run(Query.and(Query.tags("amenity=cafe"), Query.Within(null, 300.0))).hits
        assertTrue(within.isNotEmpty())
        assertTrue(within.all { it.distance!! <= 300 })
        val box = BBox(4.880, 52.372, 4.886, 52.376)
        val inBox = run(Query.and(Query.tags("amenity=restaurant"), Query.InArea(box))).hits
        assertTrue(inBox.isNotEmpty() && inBox.all { it.record.location in box })
        val nearest = run(Query.Limit(Query.Sort(Query.tags("shop=bakery"), SortBy.DISTANCE), 3)).hits
        assertEquals(3, nearest.size)
        assertTrue(nearest[0].distance!! <= nearest[1].distance!! && nearest[1].distance!! <= nearest[2].distance!!)
        // along the Raadhuisstraat, west to east
        val line = listOf(LatLon(52.3740, 4.8790), LatLon(52.3742, 4.8838), LatLon(52.3737, 4.8905))
        val along = run(Query.and(Query.tags("amenity=restaurant"), Query.AlongRoute(line, 60.0))).hits
        assertTrue(along.isNotEmpty())
        for (h in along) {
            assertTrue((h.along ?: -1.0) >= 0)
            assertTrue(app.atlas.geo.Polyline(line).snap(h.record.location)!!.offset <= 66)
        }
        val open = run(Query.Sort(Query.and(Query.tags("amenity=cafe"), Query.OpenAt(OpenTime.Now)), SortBy.OPEN_LONGEST)).hits
        val closes = open.mapNotNull { it.open?.changesAt }
        assertEquals(closes.sortedDescending(), closes)
    }

    @Test
    fun namesTypesIdsAndBooleanLogic() {
        val kerk = run(Query.Name("westerkrk")).hits
        assertEquals(OsmId(OsmType.WAY, 99205257), kerk.first().place.id)
        val nodes = run(Query.and(Query.tags("amenity=bench"), Query.Type(OsmType.NODE))).hits
        assertTrue(nodes.isNotEmpty() && nodes.all { it.place.id!!.type == OsmType.NODE })
        val one = run(Query.Id(OsmId(OsmType.NODE, 26973327))).hits
        assertEquals(1, one.size)
        val orQ = run(Query.Or(listOf(Query.tags("shop=bakery"), Query.tags("shop=cheese")))).hits
        assertTrue(orQ.any { it.tags["shop"] == "bakery" } && orQ.any { it.tags["shop"] == "cheese" })
        val notQ = run(Query.and(Query.tags("amenity=restaurant"), Query.Not(Query.tags("cuisine~italian|pizza")))).hits
        assertTrue(notQ.none { it.tags["cuisine"]?.contains("pizza") == true || it.tags["cuisine"]?.contains("italian") == true })
        val cat = run(Query.Category("drinks")).hits
        assertTrue(cat.isNotEmpty() && cat.all { it.record.category == "drinks" })
        val group = run(Query.Category("eat")).total
        assertTrue(group > run(Query.Category("restaurants")).total)
        val brand = run(Query.Brand("albertheijn-1cf55d")).hits
        assertEquals(2, brand.size)
        assertFalse(run(Query.Newer(java.time.Instant.parse("2020-01-01T00:00:00Z"))).hits.isNotEmpty())
    }

    @Test
    fun aFailedAlternativeLeavesNoReasonBehind() {
        // the first alternative's café test passes, its wifi test fails; the hit must not claim "amenity=cafe"
        val q = Query.Or(listOf(
            Query.and(Query.tags("amenity=cafe"), Query.tags("internet_access=no_such_value")),
            Query.tags("shop=bakery"),
        ))
        val hits = run(Query.and(q, Query.Within(Fixtures.westerkerk, 600.0))).hits
        assertTrue(hits.isNotEmpty())
        for (h in hits) {
            assertEquals("bakery", h.tags["shop"])
            assertEquals(listOf("shop=bakery"), h.why)
        }
        // categories and brands say so
        assertTrue(run(Query.Category("drinks")).hits.all { "category:drinks" in it.why })
        assertTrue(run(Query.Brand("albertheijn-1cf55d")).hits.all { "brand:albertheijn-1cf55d" in it.why })
    }

    @Test
    fun preferencesRankWithoutFiltering() {
        val plain = run(Query.tags("amenity=cafe")).total
        val withOutlet = run(Query.and(Query.tags("amenity=cafe"), Query.Prefer(Query.tags("outdoor_seating=yes"), 2.0)))
        assertEquals(plain, withOutlet.total)
        val top = withOutlet.hits.take(10)
        assertTrue(top.count { it.tags["outdoor_seating"] == "yes" } >= 7)
    }

    @Test
    fun plansSearchEachStepNearTheLast() {
        val p = QueryParser.parse("quiet café with an outlet, then dinner")
        val results = evaluator.plan(p.steps, ctx.copy(limit = 5))
        assertEquals(2, results.size)
        val cafe = results[0].hits.first()
        val dinner = results[1].hits.first()
        assertEquals("cafe", cafe.tags["amenity"])
        assertEquals("restaurant", dinner.tags["amenity"])
        assertTrue(Geo.distance(cafe.record.location, dinner.record.location) < 300)
    }

    @Test
    fun regionsSpanStoresAndDropDuplicates() {
        val a = PlaceStore.inMemory().also { it.importOverpass(Fixtures.eat) }
        val b = PlaceStore.inMemory().also { it.importOverpass(Fixtures.errands) }
        val c = PlaceStore.inMemory().also { it.importOverpass(Fixtures.eat) }
        val regions = Regions(listOf(a, b, c))
        val r = OfflineEvaluator(regions).run(Query.tags("amenity=cafe"), ctx)
        assertEquals(OfflineEvaluator(a).run(Query.tags("amenity=cafe"), ctx).total, r.total)
        val toilets = OfflineEvaluator(regions).run(Query.tags("amenity=toilets"), ctx).total
        assertTrue(toilets >= OfflineEvaluator(b).run(Query.tags("amenity=toilets"), ctx).total)
        // a chain across regions: cafés (one file) near toilets (another)
        val cross = OfflineEvaluator(regions).run(Query.Near(Query.tags("amenity=cafe"), Query.tags("amenity=toilets"), 100.0), ctx)
        assertTrue(cross.total > 0)
        assertEquals(1, regions.text("westerkerk").count { it.place.id == OsmId(OsmType.NODE, 5831919166) })
        listOf(a, b, c).forEach { it.close() }
    }

    @Test
    fun aLimitBelowOneFindsNothingAndDoesNotThrow() {
        assertEquals(0, run(Query.Limit(Query.tags("amenity=cafe"), -1)).hits.size)
        assertEquals(0, run(Query.Limit(Query.tags("amenity=cafe"), 0)).hits.size)
        assertTrue(run(Query.Limit(Query.tags("amenity=cafe"), -1)).total > 0)
        // the power syntax does not take a count below one
        val p = QueryParser.parse("amenity=cafe limit:-5")
        assertTrue(p.query is Query.Tags)
        assertEquals(listOf("limit:-5"), p.unknown)
        assertTrue(run(p.query).hits.isNotEmpty())
        assertEquals(listOf("sort:sideways"), QueryParser.parse("amenity=cafe sort:sideways").unknown)
    }

    @Test
    fun aNearSetAcrossACountryMissesNothingPolewards() {
        // a frame centred at 49.5° stretches east-west metres by 10% at 54°: the R-tree must look that much wider
        fun place(id: Long, lat: Double, lon: Double, tags: Map<String, String>) =
            app.atlas.model.Place(OsmId(OsmType.NODE, id), LatLon(lat, lon), tags)
        val cafe = place(1, 54.0, 5.0, mapOf("amenity" to "cafe"))
        val east = Geo.destination(cafe.location, 90.0, 195.0)
        val places = listOf(
            cafe,
            place(2, east.lat, east.lon, mapOf("amenity" to "bicycle_rental")),
            place(3, 45.0, 5.0, mapOf("amenity" to "bicycle_rental")),
            place(4, 45.0, 6.0, mapOf("amenity" to "bicycle_rental")),
        )
        val mem = MemoryPlaces(places)
        val hits = OfflineEvaluator(mem).run(Query.Near(Query.tags("amenity=cafe"), Query.tags("amenity=bicycle_rental"), 200.0), EvalContext()).hits
        assertEquals(1, hits.size)
        assertEquals(195.0, hits.single().near.single().distance, 0.5)
        // and "not near" agrees
        val far = OfflineEvaluator(mem).run(Query.NotNear(Query.tags("amenity=cafe"), Query.tags("amenity=bicycle_rental"), 200.0), EvalContext()).hits
        assertTrue(far.isEmpty())
    }

    @Test
    fun aFailedAlternativeLeavesNoRelevanceBehind() {
        // the first alternative's name test passes (lowering the relevance), its wifi test fails
        val q = Query.Or(listOf(
            Query.and(Query.Name("cafe"), Query.tags("internet_access=no_such_value")),
            Query.tags("amenity=cafe"),
        ))
        val hits = run(Query.and(q, Query.Within(Fixtures.westerkerk, 400.0))).hits
        assertTrue(hits.any { it.record.name()?.lowercase()?.contains("caf") == true })
        for (h in hits) assertEquals(h.toString(), 1.0, h.relevance, 0.0)
    }

    @Test
    fun aNearClauseThatIsNotTheSeedStillMatchesABruteForceJoin() {
        // the cafés are the seed; the parks are fetched once around them, not for every café
        val parks = all("leisure=park")
        val q = Query.and(Query.tags("amenity=cafe"), Query.Near(Query.All, Query.tags("leisure=park"), 150.0), Query.Within(Fixtures.westerkerk, 1_500.0))
        val expected = all("amenity=cafe").filter { c ->
            c.distanceTo(Fixtures.westerkerk) <= 1_500 && parks.any { it.distanceTo(c.location) <= 150 }
        }.map { it.place.key }.toSet()
        assertTrue(expected.size >= 5)
        assertEquals(expected, run(q).hits.map { it.place.key }.toSet())
    }
}
