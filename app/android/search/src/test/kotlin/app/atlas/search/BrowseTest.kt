package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class BrowseTest {
    private val here = Fixtures.westerkerk
    private val pool: List<PlaceRecord> by lazy { Fixtures.store.within(here, 1_500.0) }
    private val saturday = Moment(Fixtures.saturday)
    private val lateSaturday = Moment(Fixtures.saturday.withHour(23).withMinute(30))
    private val categories by lazy { CategoryTree.all().filter { it.kind == NodeKind.CATEGORY } }

    @Test
    fun everyTileSaysItsOwnThing() {
        val pulses = Browse.pulses(categories, pool, here, saturday)
        for (p in pulses.filter { it.count > 0 }) println("${p.node.name()}: ${p.count} near, ${p.open} open (${p.unconfirmed} by brand), ${p.tell} ${p.value}, nearest ${p.minutes} min")
        val restaurants = pulses.first { it.node.id == "cat:restaurants" }
        assertTrue(restaurants.dense)
        assertEquals(TellKind.OPEN_COUNT, restaurants.tell)
        assertEquals(restaurants.open.toString(), restaurants.value)
        // few near: the nearest, and how long it takes
        val sparse = pulses.filter { it.count in 1 until CategoryPulse.DENSE && it.tell == TellKind.NEAREST }
        assertTrue(sparse.isNotEmpty())
        for (p in sparse) assertEquals(Travel.minutes(p.metres!!, TravelMode.WALK).toString(), p.value)
        // none near says nothing
        assertTrue(pulses.filter { it.count == 0 }.all { it.tell == TellKind.NONE && it.nearest == null })
        // late on a Saturday the bars say how long they stay open, the museums when they open
        val night = Browse.pulses(categories, pool, here, lateSaturday).associateBy { it.node.category.id }
        assertEquals(TellKind.OPEN_TILL, night.getValue("drinks").tell)
        assertTrue(night.getValue("drinks").value!! < "06:00")
        // the time the last of them closes, for any tile that has one open
        val until = night.getValue("drinks").until!!
        assertEquals(night.getValue("drinks").value, "%02d:%02d".format(until.hour, until.minute))
        assertTrue(pulses.filter { it.open == 0 }.all { it.until == null })
        assertEquals(TellKind.OPENS_AT, night.getValue("museums").tell)
        // with none open, when the first opens; of those open, how many never close
        val museums = night.getValue("museums")
        assertEquals(museums.value, "%02d:%02d".format(museums.opensAt!!.hour, museums.opensAt!!.minute))
        assertTrue(pulses.filter { it.open > 0 }.all { it.opensAt == null })
        assertTrue(pulses.all { it.endless <= it.open })
        assertTrue("something near is open day and night", night.values.any { it.endless > 0 })
    }

    /**
     * A tile's time is its places' own hours' where any of them says it, else a guess's, marked so: when the last
     * closes, or with none open when the first opens (the owner, 5 November: "Should it primarily show whats surely
     * open only?").
     */
    @Test
    fun aTilesTimesKnowWhenOnlyAGuessGivesThem() {
        val drinks = CategoryTree.category("drinks")!!
        var id = 900_000L
        fun bar(dLat: Double, hours: String?) = PlaceRecord.of(
            Place(OsmId(OsmType.NODE, id++), LatLon(here.lat + dLat, here.lon), listOfNotNull("amenity" to "bar", "name" to "Bar $id", hours?.let { "opening_hours" to it }).toMap()),
        )
        // three bars a little off keep late hours: the one that maps none goes by them
        val around = (1..3).map { bar(0.003 + 0.001 * it, "Mo-Su 12:00-03:00") }
        val early = bar(0.001, "Mo-Su 16:00-23:00")
        val guessed = bar(0.002, null)
        fun pulse(places: List<PlaceRecord>, hour: Int) = Browse.pulses(
            listOf(drinks), places, here, Moment(Fixtures.saturday.withHour(hour).withMinute(0)), HoursBook(listOf(early, guessed) + around),
        ).single()
        // the guess keeps the bar open till 3:00, the bar of its own hours till 23:00: the tile says 23:00
        val evening = pulse(listOf(early, guessed), 21)
        assertEquals(2 to 1, evening.open to evening.unconfirmed)
        assertEquals(23, evening.until!!.hour)
        assertFalse(evening.untilGuessed)
        val alone = pulse(listOf(guessed), 21)
        assertEquals(3, alone.until!!.hour)
        assertTrue("only the guess says when it closes", alone.untilGuessed)
        // none open in the morning: the first to open by its own hours, else by a guess
        val morning = pulse(listOf(early, guessed), 10)
        assertEquals(0, morning.open)
        assertEquals(16, morning.opensAt!!.hour)
        assertFalse(morning.opensGuessed)
        val asleep = pulse(listOf(guessed), 10)
        assertEquals(12, asleep.opensAt!!.hour)
        assertTrue("only the guess says when it opens", asleep.opensGuessed)
    }

    /** The time picker's curve: how many are open each hour of the day asked about, over what the other filters keep. */
    @Test
    fun theFiltersCountWhatIsOpenEachHour() {
        val restaurants = CategoryTree.category("restaurants")!!
        val res = Browse.results(restaurants, pool, here, saturday)
        assertEquals(24, res.byHour.size)
        assertEquals(Fixtures.saturday.toLocalDate().atTime(Browse.CURVE_FROM, 0), res.hoursFrom)
        fun at(r: CategoryResults, hour: Int) = r.byHour[(hour - Browse.CURVE_FROM + 24) % 24]
        println("restaurants open by hour from ${res.hoursFrom}: ${res.byHour}")
        assertTrue(at(res, 19) > at(res, 5))
        assertTrue(res.byHour.all { it <= res.hits.size })
        // open now chosen, the curve still counts every hour; another filter narrows it
        val openNow = Browse.results(restaurants, pool, here, saturday, FacetSelection.NONE.toggle(Facets.OPEN, "now"))
        assertEquals(res.byHour, openNow.byHour)
        assertTrue(openNow.hits.size < res.hits.size)
        val stepFree = Browse.results(restaurants, pool, here, saturday, FacetSelection.NONE.toggle("wheelchair", "~yes"))
        assertTrue(stepFree.byHour.indices.all { stepFree.byHour[it] <= res.byHour[it] })
        assertTrue(stepFree.byHour.sum() < res.byHour.sum())
        // in the small hours the day is still the evening's
        val night = Browse.results(restaurants, pool, here, Moment(Fixtures.saturday.withHour(2)))
        assertEquals(Fixtures.saturday.toLocalDate().minusDays(1).atTime(Browse.CURVE_FROM, 0), night.hoursFrom)
    }

    /**
     * A shelf's view puts a kind's places with no name of their own on one deck, nearest first, where two or more of them
     * are among its places (the owner, 6 November, of Fun's two playgrounds: "If there are multiple like 2 playgrounds
     * make that visible at first glance. A stack?", then "Yes, build decks"); the kind's own view lists each, and no
     * place with a name lies on one.
     */
    @Test
    fun aShelfPutsAKindsUnnamedPlacesOnOneDeck() {
        val playgrounds = Browse.results(CategoryTree.shelf("fun")!!, pool, here, saturday).items.filterIsInstance<ResultItem.Unnamed>()
            .single { d -> d.hits.all { it.record.place.tags["leisure"] == "playground" } }
        assertEquals(2, playgrounds.hits.size)
        assertEquals(playgrounds.hits.sortedBy { it.metres }, playgrounds.hits)
        // the playgrounds' own view: each its row
        val own = Browse.results(CategoryTree.category("playground")!!, pool, here, saturday)
        assertEquals(2, own.items.size)
        assertTrue(own.items.none { it is ResultItem.Deck })
        // the services: the benches and the bins each on a deck of their own, the toilets on one whatever they flush
        // with, every place once
        val services = Browse.results(CategoryTree.shelf("service")!!, pool, here, saturday)
        val decks = services.items.filterIsInstance<ResultItem.Unnamed>()
        println("decks: " + decks.joinToString { "${it.kind.id} ×${it.hits.size}" })
        assertTrue(decks.any { it.kind.id == "preset:amenity/bench" && it.hits.size >= 10 })
        val toilets = decks.single { it.kind.id == "preset:amenity/toilets" }
        assertTrue(toilets.hits.any { it.node?.id != toilets.kind.id })
        assertEquals(services.hits.size, services.items.sumOf { it.hits.size })
        assertEquals(decks.size, decks.map { it.kind.id }.distinct().size)
        for (d in decks) {
            assertTrue(d.hits.size >= 2)
            assertTrue(d.hits.all { it.record.name() == null && CategoryTree.isUnder(it.node!!, d.kind) })
        }
    }

    @Test
    fun aBrandsBranchesShareOneRow() {
        val restaurants = CategoryTree.category("restaurants")!!
        val res = Browse.results(restaurants, pool, here, saturday)
        val bundles = res.items.filterIsInstance<ResultItem.Bundle>()
        println("bundles: " + bundles.joinToString { "${it.name} ×${it.hits.size}" })
        assertTrue(bundles.size >= 5)
        // every place once, and more of the others in view
        assertEquals(res.hits.size, res.items.sumOf { it.hits.size })
        assertTrue(res.items.size < res.hits.size)
        for (b in bundles) {
            assertTrue(b.hits.size >= 2)
            assertEquals(b.hits.sortedBy { it.metres }, b.hits)
        }
        // searching for the brand itself lists its branches one by one
        val brand = bundles.first().brand
        val own = Browse.results(restaurants, pool, here, saturday, brandSearched = brand)
        assertTrue(own.items.none { it is ResultItem.Bundle && it.brand == brand })
    }

    @Test
    fun eachCategoryRanksItsOwnWay() {
        // a toilet near comes first
        val toilets = Browse.results(CategoryTree.category("toilets")!!, pool, here, saturday).hits
        assertTrue(toilets.size > 5)
        assertTrue(toilets.take(3).map { it.metres }.average() < toilets.map { it.metres }.average())
        // a restaurant open comes first
        val restaurants = Browse.results(CategoryTree.category("restaurants")!!, pool, here, saturday).hits
        assertTrue(restaurants.take(20).all { it.open?.isOpen == true })
        // the time to reach follows the way of getting about
        val byBike = Browse.results(CategoryTree.category("restaurants")!!, pool, here, saturday.copy(mode = TravelMode.BIKE)).hits
        val far = byBike.maxBy { it.metres }
        assertEquals(Travel.minutes(far.metres, TravelMode.BIKE), far.minutes)
        // the kind of thing stands beside each result: a pizzeria is a pizzeria
        val pizza = restaurants.first { it.record.tags["cuisine"] == "pizza" && it.record.tags["amenity"] == "restaurant" }
        assertEquals("preset:amenity/restaurant/pizza", pizza.node?.id)
    }

    @Test
    fun aCategoryNarrowsIntoItsKinds() {
        val restaurants = CategoryTree.category("restaurants")!!
        val res = Browse.results(restaurants, pool, here, saturday)
        val subs = res.subcategories
        println("kinds: " + subs.take(8).joinToString { "${it.first.name()} ${it.second}" })
        assertEquals(restaurants.children.size, subs.size)
        assertTrue(subs.first().second > 50)
        val cafe = CategoryTree.preset("amenity/cafe")!!
        assertEquals(subs.first { it.first == cafe }.second, Browse.results(cafe, pool, here, saturday).hits.size)
        // the shelves narrow into their categories, and the shelf of everything on the map into its keys
        val osm = Browse.results(CategoryTree.shelf(CategoryTree.OSM)!!, pool, here, saturday)
        println("on the map: " + osm.subcategories.filter { it.second > 0 }.joinToString { "${it.first.name()} ${it.second}" })
        assertTrue(osm.subcategories.any { it.first.kind == NodeKind.KEY && it.second > 0 })
        // a kind holding them all is no step: the shops' one category is passed for its kinds, and among cafes alone the
        // restaurants narrow into the cafes' own kinds
        val shops = Browse.results(CategoryTree.category("shops")!!, pool, here, saturday).subcategories.filter { it.second > 0 }
        println("shops: " + shops.take(6).joinToString { "${it.first.name()} ${it.second}" })
        assertTrue(shops.size > 1 && shops.none { it.first.kind == NodeKind.CATEGORY })
        val cafes = pool.filter { Browse.holds(cafe, it) }
        assertTrue(Browse.results(restaurants, cafes, here, saturday).subcategories.none { it.first == cafe })
    }

    /**
     * The groceries open on their kinds, what people come for most leading them, the supermarkets and then the
     * convenience stores (the owner, 6 November: "it makes sense that these two sit at the front because thats what
     * people primarily search for when looking for groceries"), then the rest by how many are near, the specialists they
     * name last after them (the frozen food shops), those with none near last of all.
     */
    @Test
    fun whatPeopleComeForMostLeadsTheKinds() {
        val shelf = CategoryTree.shelf("groceries")!!
        val groceries = CategoryTree.category("food_shops")!!
        val subs = Browse.results(shelf, pool, here, saturday).subcategories
        println("groceries: " + subs.joinToString { "${it.first.name()} ${it.second}" })
        val near = subs.takeWhile { it.second > 0 }
        assertTrue(subs.drop(near.size).all { it.second == 0 })
        assertEquals(listOf("preset:shop/supermarket", "preset:shop/convenience"), near.take(2).map { it.first.id })
        val standing = near.map { groceries.standing(it.first) }
        assertEquals(standing.sorted(), standing)
        val between = near.filter { groceries.standing(it.first) == 0 }
        assertEquals(between.sortedByDescending { it.second }, between)
        assertEquals(subs.drop(near.size).map { groceries.standing(it.first) }.sorted(), subs.drop(near.size).map { groceries.standing(it.first) })
    }

    @Test
    fun aTypedQuerysPlacesAreAKindsResults() {
        val engine = SearchEngine(Fixtures.store)
        val ctx = SearchContext(location = here, now = Fixtures.saturday, limit = 400)
        fun answer(text: String) = kotlinx.coroutines.runBlocking { engine.answer(text, ctx) }!!.items.filterIsInstance<SearchItem.Structured>().single()
        // what the query wants and tags may not say parts the results; each keeps what it is near
        val cafes = answer("cafe with toilets")
        val hits = cafes.results.first().hits
        assertTrue(hits.size > 30)
        val res = Browse.asked(hits, here, saturday)
        println("cafe with toilets: ${res.node.id} · " + res.parts.joinToString { "${it.sure} ${it.hits.size}" } + " · kinds " + res.subcategories.joinToString { "${it.first.name()} ${it.second}" })
        assertEquals("preset:amenity/cafe", res.node.id)
        assertEquals(Sure.YES, res.parts.first().sure)
        assertTrue(res.parts.size >= 2)
        assertEquals(hits.size, res.hits.size)
        // every tag of them a filter with its count
        assertTrue(res.facets.facets.size > 5)
        // an order asked for stays the query's own
        val old = answer("oldest church")
        val oldest = Browse.asked(old.results.first().hits, here, saturday, order = Order.ASKED)
        assertEquals(old.results.first().hits.map { it.record.place.key }, oldest.hits.map { it.record.place.key })
        // places of many kinds stand on the plain list, their categories a tap narrower; one of them narrows them
        val mixed = Browse.asked(answer("built before 1700").results.first().hits, here, saturday)
        println("built before 1700: ${mixed.node.id} · " + mixed.subcategories.take(6).joinToString { "${it.first.name()} ${it.second}" })
        assertTrue(mixed.subcategories.size >= 2)
        val first = mixed.subcategories.first()
        val narrowed = Browse.asked(answer("built before 1700").results.first().hits, here, saturday, within = first.first)
        assertEquals(first.second, narrowed.hits.size)
        assertTrue(CategoryTree.isUnder(narrowed.node, first.first))
    }

    @Test
    fun intentsFollowTheWeather() {
        val lake = mapOf("natural" to "water", "water" to "lake")
        assertTrue(Intents.of(lake).map { it.id }.containsAll(listOf("swim", "sunset")))
        val summer = Moment(ZonedDateTime.of(2026, 7, 18, 15, 0, 0, 0, Fixtures.amsterdam), WeatherNow(tempC = 27.0, sunny = true))
        val swim = Intents.byId("swim")!!
        assertTrue(swim.fit(summer).first >= 0.9)
        assertEquals(0.0 to FitReason.STORM, swim.fit(summer.copy(weather = WeatherNow(tempC = 27.0, storm = true))))
        assertEquals(FitReason.COLD, swim.fit(summer.copy(weather = WeatherNow(tempC = 16.0))).second)
        val rainy = Moment(Fixtures.saturday, WeatherNow(tempC = 12.0, rain = true))
        assertEquals(FitReason.SEASON, swim.fit(rainy).second)
        val now = Intents.now(rainy).map { it.first.id }
        println("a rainy October Saturday: $now")
        assertTrue("shelter" in now)
        assertFalse("picnic" in now)
        // somewhere dry is for the rain, a swim for a day known to be warm
        assertFalse("shelter" in Intents.now(Moment(Fixtures.saturday)).map { it.first.id })
        assertTrue(swim.fit(Moment(ZonedDateTime.of(2026, 7, 18, 15, 0, 0, 0, Fixtures.amsterdam))).first < 0.6)
        // the sunset fits in the hour and a half before it
        val sunset = Intents.byId("sunset")!!
        val evening = Moment(Fixtures.saturday.withHour(18).withMinute(0), sunset = java.time.LocalTime.of(18, 50))
        assertEquals(FitReason.SUNSET, sunset.fit(evening).second)
    }

    @Test
    fun theMomentOrdersTheCategories() {
        val ids = Categories.leaves().map { it.id }
        val night = Relevance.order(ids, lateSaturday)
        println("late Saturday: " + night.take(8))
        assertTrue(night.indexOf("drinks") < night.indexOf("museums"))
        assertTrue(Relevance.atNight("fuel") && Relevance.atNight("health") && Relevance.atNight("drinks"))
        assertFalse(Relevance.atNight("playground") || Relevance.atNight("museums") || Relevance.atNight("post"))
        // rain sends people indoors
        val rain = Relevance.order(ids, saturday.copy(weather = WeatherNow(tempC = 12.0, rain = true)))
        assertTrue(rain.indexOf("museums") < rain.indexOf("parks"))
        // what the searcher keeps choosing rises
        val laundry = Relevance.order(ids, saturday.copy(picks = mapOf("cat:laundry" to 9, "cat:restaurants" to 2)))
        assertTrue(laundry.indexOf("laundry") < Relevance.order(ids, saturday).indexOf("laundry"))
    }

    @Test
    fun travelTimes() {
        assertEquals(17, Travel.minutes(1_000.0, TravelMode.WALK))
        assertEquals(6, Travel.minutes(1_000.0, TravelMode.BIKE))
        assertTrue(Travel.minutes(20_000.0, TravelMode.CAR) < Travel.minutes(20_000.0, TravelMode.BIKE))
        assertEquals(Travel.minutes(500.0, TravelMode.WALK), Travel.minutes(500.0, TravelMode.TRANSIT))
        assertEquals("1 h 05", Travel.format(65))
        assertEquals("6′", Travel.format(6))
    }

    @Test
    fun everyKindAroundIsListedOnceAndSorts() {
        // the owner, 6 November: "a plain all kinds list row sortable with every tag/kind that exists"
        val kinds = EveryKind.around(pool, here, saturday)
        assertEquals(kinds.size, kinds.map { it.node.id }.toSet().size)
        assertTrue(kinds.size.toString(), kinds.size > 60)
        // each place's own kind is there, counted as its tile would count it, but a family's own node beside its kinds:
        // it would count them all again ("Shops, 227" among the shops)
        fun catchAll(n: CategoryNode) = n.kind != NodeKind.PRESET && kinds.any { it.node.id != n.id && CategoryTree.isUnder(it.node, n) }
        for (r in pool.take(300)) {
            val k = CategoryTree.of(r.tags, PlaceRecord.geometryOf(r.place))?.takeIf { it.kind != NodeKind.SHELF } ?: continue
            assertTrue(k.id, catchAll(k) || kinds.any { it.node.id == k.id })
        }
        assertTrue(kinds.filter { catchAll(it.node) }.joinToString { it.node.id }, kinds.none { catchAll(it.node) })
        val clothes = kinds.first { it.node.id == "preset:shop/clothes" }
        assertEquals(pool.count { Browse.holds(clothes.node, it) }, clothes.count)
        // sorted, each kind's narrower ones after it in the same order (the owner, 6 November: "Organic supermarket belongs
        // to supermarket?"), counted in its own count
        fun siblings(rows: List<EveryKind.Row>): List<List<CategoryPulse>> {
            val out = ArrayList<List<CategoryPulse>>()
            fun walk(from: Int, depth: Int): Int {
                var i = from
                val level = ArrayList<CategoryPulse>()
                while (i < rows.size && rows[i].depth >= depth) {
                    if (rows[i].depth == depth) { level += rows[i].pulse; i++ } else i = walk(i, depth + 1)
                }
                out += level
                return i
            }
            walk(0, 0)
            return out
        }
        val most = EveryKind.sorted(kinds, EveryKind.Order.MOST, "en")
        assertEquals(kinds.size, most.size)
        assertTrue(siblings(most).all { level -> level.zipWithNext().all { (a, b) -> a.count >= b.count } })
        for ((i, r) in most.withIndex()) if (r.depth > 0) {
            val broader = most.subList(0, i).last { it.depth == r.depth - 1 }.pulse
            assertTrue("${r.pulse.node.id} under ${broader.node.id}", CategoryTree.isUnder(r.pulse.node, broader.node) && broader.count >= r.pulse.count)
        }
        val organic = most.indexOfFirst { it.pulse.node.id == "preset:shop/supermarket/organic" }
        if (organic >= 0) assertEquals("preset:shop/supermarket", most.subList(0, organic).last { it.depth == most[organic].depth - 1 }.pulse.node.id)
        println("nested: " + most.filter { it.depth > 0 }.take(12).joinToString { it.pulse.node.id })
        assertTrue(most.any { it.depth > 0 })
        val nearest = EveryKind.sorted(kinds, EveryKind.Order.NEAREST, "en")
        assertTrue(siblings(nearest).all { level -> level.zipWithNext().all { (a, b) -> (a.metres ?: Double.MAX_VALUE) <= (b.metres ?: Double.MAX_VALUE) } })
        val names = siblings(EveryKind.sorted(kinds, EveryKind.Order.NAME, "en"))
        assertTrue(names.all { level -> level.map { it.node.name("en") } == level.map { it.node.name("en") }.sortedWith(java.text.Collator.getInstance(java.util.Locale.ENGLISH)) })
        assertTrue(EveryKind.sorted(kinds, EveryKind.Order.MOST, "en", openOnly = true).all { it.pulse.open > 0 })
        // the chains behind a kind: Albert Heijn's two supermarkets near the Westerkerk, and none of one branch
        val chains = EveryKind.chains(kinds, pool)
        val supermarkets = chains.getValue("preset:shop/supermarket")
        assertTrue(supermarkets.toString(), supermarkets.any { it.startsWith("albertheijn") } && supermarkets.size <= 3)
        for ((id, brands) in chains) {
            val node = kinds.first { it.node.id == id }.node
            for (b in brands) assertTrue("$id $b", pool.count { it.brand == b && Browse.holds(node, it) } >= 2)
        }
        // a family's: every kind under its shelf, and only those
        val shops = CategoryTree.shelf("shops")!!
        val theirs = EveryKind.around(pool, here, saturday, scope = shops)
        assertTrue(theirs.isNotEmpty() && theirs.all { CategoryTree.isUnder(it.node, shops) })
        assertEquals(kinds.filter { CategoryTree.isUnder(it.node, shops) }.map { it.node.id }.toSet(), theirs.map { it.node.id }.toSet())
    }

    /**
     * Every kind a category holds, near or not (the owner, 6 November: "Theres no way to see every kind that belongs to
     * a category from within that category. Only the ones that exist nearby"): its presets at any depth, and for a shelf
     * those of the kinds it holds too; the ones near as they are around, then the rest, none of which is near.
     */
    @Test
    fun everyKindACategoryHoldsNearOrNot() {
        val outings = CategoryTree.shelf("fun")!!
        val all = EveryKind.of(outings)
        val ids = all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in listOf("preset:leisure/playground", "preset:amenity/cinema", "preset:leisure/bowling_alley", "preset:leisure/ice_rink", "preset:leisure/water_park")) {
            assertTrue(id, id in ids)
        }
        // what it holds too comes through its guests; Sport's own kinds do not
        assertTrue(all.all { n -> CategoryTree.isUnder(n, outings) || outings.guests.any { CategoryTree.isUnder(n, it) } })
        assertFalse("preset:leisure/fitness_centre" in ids)
        // a kind with nothing narrower holds none; one with narrower ones holds those
        assertTrue(EveryKind.of(CategoryTree.preset("leisure/bowling_alley")!!).isEmpty())
        val restaurant = CategoryTree.preset("amenity/restaurant")!!
        val cuisines = EveryKind.of(restaurant)
        assertTrue(cuisines.isNotEmpty() && cuisines.all { it.id != restaurant.id && CategoryTree.isUnder(it, restaurant) })
        for (shelf in CategoryTree.shelves()) println("every kind of ${shelf.id}: ${EveryKind.of(shelf).size}")
        // near first, as they are around, then the rest, each once
        val shops = CategoryTree.shelf("shops")!!
        val near = EveryKind.around(pool, here, saturday, scope = shops)
        val listed = EveryKind.all(shops, near)
        assertEquals(near, listed.take(near.size))
        assertEquals(listed.size, listed.map { it.node.id }.toSet().size)
        assertTrue(listed.drop(near.size).all { it.count == 0 && it.nearest == null })
        assertTrue(EveryKind.of(shops).all { n -> listed.any { it.node.id == n.id } })
        // the most first: those none of which is near come after all those near, a narrower kind none of which is near
        // among them, not under its broader one near
        assertEquals(near.size, EveryKind.sorted(listed, EveryKind.Order.MOST, "en").indexOfFirst { it.pulse.count == 0 })
    }
}
