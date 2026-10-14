package app.atlas.ui.eat

import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.providers.Rating
import app.atlas.providers.Trend
import app.atlas.search.Query
import app.atlas.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every eat & drink screen's model on the real places around the Westerkerk (OpenStreetMap, `data/eat/raw.json`), on
 * Tuesday 13 October 2026 at 19:30, offline: what the screens show is what these return.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EatModelsTest {
    private val area: EatArea by lazy {
        runBlocking { withTimeout(60_000) { EatRepository.of(EatFixtures.services()).area(Services.WESTERKERK).first { !it.loading } } }
    }
    private val evening = LocalDateTime.now(Fixtures.evening)
    private val zone = ZoneId.of("Europe/Amsterdam")

    @Test
    fun theTableCountsOpenPlaces() {
        val t0 = System.nanoTime()
        val c = EatSets.counts(area, evening)
        val ms = (System.nanoTime() - t0) / 1e6
        println("table at 19:30: $c in $ms ms")
        assertTrue(c.carte.open && c.carte.value > 100)
        assertTrue(c.cafe.open && c.cafe.value > 10)
        assertTrue(c.bar.open && c.bar.value > 50)
        assertTrue("late ${c.late}", c.late.value > 10)
        assertNotNull(c.lateUntil)
        assertTrue(c.sweets.value > 5 && c.market.value > 0)
        // Open counts never exceed the places.
        assertTrue(c.carte.value <= EatSets.carte(area.places).size)
        assertTrue("counts took $ms ms", ms < 2_000)
    }

    @Test
    fun surpriseIsOpenAndNear() {
        repeat(20) { seed ->
            val p = EatSets.surprise(area, evening, AreaRatings.OFFLINE, kotlin.random.Random(seed))!!
            assertEquals(Openness.OPEN, p.openness(evening, area.hours))
            assertTrue(p.walkMinutes <= 12)
        }
    }

    @Test
    fun theCarteGroupsByFamily() {
        val carte = CarteModel.build(area, evening, CarteSort.NEAREST, emptySet(), AreaRatings.OFFLINE, "en")
        println("carte: ${carte.counted} open of ${carte.total}; " + carte.chapters.joinToString { "${it.family.code} ${it.lines.size}" })
        assertTrue(carte.countsOpen)
        val families = carte.chapters.map { it.family }
        assertTrue(CuisineFamily.ITALY in families && CuisineFamily.JAPAN in families && CuisineFamily.EUROPE in families)
        // Chapters keep the thumb index's order; within each, nearest first, the unmapped kitchens last.
        assertEquals(families, families.sortedBy { it.ordinal })
        for (ch in carte.chapters) {
            val mapped = ch.lines.filter { !it.cuisine.startsWith("unmapped") }
            assertEquals(mapped.map { it.nearestWalk }, mapped.map { it.nearestWalk }.sorted())
            assertTrue(ch.lines.dropWhile { !it.cuisine.startsWith("unmapped") }.all { it.cuisine.startsWith("unmapped") })
        }
        val japan = carte.chapter(CuisineFamily.JAPAN)!!
        assertTrue(japan.lines.any { it.cuisine == "ramen" } && japan.lines.any { it.cuisine == "sushi" })
        val az = CarteModel.build(area, evening, CarteSort.A_Z, emptySet(), AreaRatings.OFFLINE, "en")
        val italy = az.chapter(CuisineFamily.ITALY)!!.lines.filter { !it.cuisine.startsWith("unmapped") }.map { it.name }
        assertEquals(italy, italy.sortedBy { it.lowercase() })
        val vegan = CarteModel.build(area, evening, CarteSort.NEAREST, setOf(Diet.VEGAN), AreaRatings.OFFLINE, "en")
        assertTrue(vegan.total in 1 until carte.total)
        assertTrue(CarteModel.restaurants(area, setOf(Diet.VEGAN)).all { it.diet("vegan") })
        assertFalse(carte.anyRated)
    }

    @Test
    fun theGuestCheckNarrowsLineByLine() {
        val pad = OrderPad(
            time = LocalTime.of(19, 30), party = 2, minutes = 120, cuisines = listOf("italian", "japanese"),
            not = listOf(OrderPad.FAST_FOOD), outside = true, wishes = setOf(Wish.DOGS), maxWalk = 15,
        )
        val r = OrderModel.evaluate(pad, evening, zone, area, AreaRatings.OFFLINE, "en")
        println("order: " + r.lines.joinToString(" → ") { "${it.line} ${it.count}" } + " in ${r.micros} µs; served ${r.served.map { it.place.name() }}")
        val hard = r.lines.filter { it.line != Line.WISHES }.map { it.count }
        assertEquals(hard, hard.sortedDescending())
        assertTrue(r.base > 100)
        assertEquals(r.lines.last().count, r.total)
        assertTrue(r.served.size <= 12 && r.served.size == minOf(12, r.total))
        // Every place served fits every hard line.
        for (h in r.served) {
            val p = area[h.place.key]!!
            assertTrue(p.cuisines.any { it == "italian" || it == "japanese" })
            assertTrue(p.outside && p.kind != EatKind.FAST_FOOD)
            assertTrue(Geo.distance(area.centre, p.location) <= OrderModel.walkMetres(15) + 1)
            assertTrue(p.openness(evening, area.hours).isOpen)
        }
        val ql = OrderModel.ql(pad, evening, area)
        assertTrue(ql, ql.contains("around:") && ql.contains("cuisine") && ql.contains("outdoor_seating"))
        assertTrue(OrderModel.query(pad, evening, area) is Query.And)
        val presets = listOf(OrderPad.DATE_NIGHT, OrderPad.QUICK_LUNCH, OrderPad.KIDS).map { OrderModel.evaluate(it, evening, zone, area, AreaRatings.OFFLINE, "en").total }
        println("presets: $presets")
    }

    @Test
    fun theWaiterUnderstands() {
        val a = Waiter.ask("pizza open now", area, evening, zone, "en")
        assertTrue(a.hits.isNotEmpty())
        assertTrue(a.hits.all { h -> area[h.place.key]!!.let { "pizza" in it.cuisines || it.name.contains("pizz", ignoreCase = true) } })
        val nl = Waiter.ask("terras", area, evening, zone, "nl")
        assertTrue(nl.total > 0)
    }

    @Test
    fun theBarCardCountsAndCrawls() {
        val card = BarModel.card(area, evening, null, emptySet())
        println("bar: ${card.open} open, last call ${card.lastCall}, tabs ${card.tabCounts}, moods ${card.moodCounts}")
        assertTrue(card.countsOpen && card.open > 50)
        assertNotNull(card.lastCall)
        assertTrue(card.lastCall!!.isAfter(evening.plusHours(4)))
        assertTrue(card.tabCounts.getValue(BarTab.PUBS) > 50)
        // Open first, nearest first among them.
        val open = card.venues.takeWhile { it.openness.isOpen }
        assertTrue(open.isNotEmpty())
        assertEquals(open.map { it.place.distanceM }, open.map { it.place.distanceM }.sorted())
        val pubs = BarModel.card(area, evening, BarTab.PUBS, setOf(BarMood.TERRACE))
        assertTrue(pubs.venues.all { it.place.kind == EatKind.PUB && it.place.outside })
        val crawl = BarModel.crawl(area.centre, emptyList(), card.venues)
        assertEquals(3, crawl.stops.size)
        assertTrue(crawl.stops.all { it.openness(evening, area.hours).isOpen })
    }

    @Test
    fun theChalkboardListsCafes() {
        val board = CafeModel.board(area, evening, null, emptySet())
        println("cafe: ${board.open} open, kinds ${board.kindCounts}, toggles ${board.toggleCounts}, special ${board.special?.place?.name}")
        assertTrue(board.countsOpen && board.open > 10)
        assertTrue(board.kindCounts.getValue(CafeKind.COFFEE) > 50)
        assertTrue(board.kindCounts.getValue(CafeKind.BAKERY) > 10)
        val wifi = CafeModel.board(area, evening, CafeKind.COFFEE, setOf(CafeToggle.WIFI))
        assertTrue(wifi.lines.isNotEmpty() && wifi.lines.all { it.place.wifi })
        val morning = CafeModel.board(area, evening.withHour(10), null, emptySet())
        assertNotNull(morning.special)
    }

    @Test
    fun theTicketMachineSellsRamen() {
        val world = CuisineWorlds.JAPAN
        val m = WorldModel.machine(world, world.plankIndex("ramen"), area, evening, emptySet(), AreaRatings.OFFLINE)
        println("ramen: ${m.open} open of ${m.buttons.size}; planks ${m.plankCounts}")
        assertTrue(m.buttons.size >= 10)
        assertTrue(m.buttons.all { "ramen" in it.place.cuisines || "tsukemen" in it.place.cuisines })
        val states = m.buttons.map { it.openness }
        assertEquals(states, states.sortedBy { listOf(Openness.OPEN, Openness.CLOSING_SOON, Openness.UNKNOWN, Openness.CLOSED).indexOf(it) })
        assertTrue(m.plankCounts.getValue("sushi") > 3)
        assertTrue(m.buttons.filter { it.openness == Openness.CLOSED }.all { it.opens != null || !it.place.hasHours })
    }

    @Test
    fun lateBitesAreOpenPastMidnight() {
        val wall = LateModel.wall(area, evening, null, emptySet(), LateSort.NEAREST)
        println("late: ${wall.count} till ${wall.till}; " + wall.tiles.joinToString { "${it.first} ${it.second}" })
        val night = LateModel.night(evening)
        assertEquals(LocalDateTime.of(2026, 10, 14, 0, 30), night)
        assertTrue(wall.count > 10)
        assertTrue(wall.spots.all { it.place.openness(night, area.hours).isOpen })
        assertEquals(9, wall.tiles.size)
    }

    @Test
    fun sweetsAndTheMarket() {
        val sweets = SweetsModel.case(area, evening, null, emptySet())
        println("sweets: ${sweets.open} open; ${sweets.tubs}")
        assertTrue(sweets.tubs.getValue(Tub.GELATO) > 5 && sweets.tubs.getValue(Tub.BAKERY) > 10)
        val gelato = SweetsModel.case(area, evening, Tub.GELATO, emptySet())
        assertTrue(gelato.spots.all { Tub.GELATO.has(it.place) })
        val market = MarketModel.board(area, evening, null, emptySet())
        println("market: ${market.count}; ${market.stalls}; " + market.week.joinToString { "${it.date.dayOfWeek} ${it.sessions.map { s -> s.place.name }}" })
        val saturday = market.week.first { it.date.dayOfWeek == DayOfWeek.SATURDAY }
        assertTrue(saturday.sessions.any { it.what == "Biologische Boerenmarkt" })
        assertTrue(market.stalls.getValue(Stall.CHEESE) > 10)
    }

    @Test
    fun ratingsRankWhenAProviderGaveThem() {
        // Three real restaurants given ratings as a provider would return them: the lists follow those, and only those.
        val three = EatSets.carte(area.places).filter { it.openness(evening, area.hours).isOpen }.take(3)
        val stars = listOf(3.9, 4.7, 4.2)
        val trend = Trend(last30 = 5, last90 = 9, last365 = 12, total = 20, velocity = 2.5, momentum = 0.2, rising = true, newcomer = false, firstReview = null, lastReview = null)
        val ratings = AreaRatings(
            three.mapIndexed { i, p -> p.key to Rating("Mangrove", stars[i], 10 + i, priceLevel = i + 1, trend = trend.takeIf { i == 2 }) }.toMap(),
            AreaRatings.State.READY,
        )
        val top = EatMapModel.view(area, evening, EatTab.TOP, emptySet(), ratings, emptySet())
        assertTrue(top.ranked)
        assertEquals(listOf(three[1], three[2], three[0]).map { it.key }, top.places.map { it.key })
        val trending = EatMapModel.view(area, evening, EatTab.TRENDING, emptySet(), ratings, emptySet())
        assertEquals(listOf(three[2].key), trending.places.map { it.key })
        assertEquals(2, top.filterCounts.getValue(EatFilter.RATED))
        assertEquals(2, top.filterCounts.getValue(EatFilter.PRICE))
        val carte = CarteModel.build(area, evening, CarteSort.BEST_RATED, emptySet(), ratings, "en")
        assertTrue(carte.anyRated)
        // Lines are cuisines: two of the three may share one, which then shows the better rating.
        val best = carte.chapters.flatMap { it.lines }.mapNotNull { it.bestStars }
        assertTrue(best.size in 1..3)
        assertEquals(4.7, best.max(), 0.001)
        // Price: "≤ $" keeps the cheap one and every place no provider priced.
        val cheap = OrderModel.evaluate(OrderPad(maxPrice = 1, minutes = 0), evening, zone, area, ratings, "en")
        assertTrue(cheap.served.none { it.place.key == three[1].key || it.place.key == three[2].key })
        assertEquals(3, cheap.pricesKnown)
    }

    @Test
    fun theEatMapIsHonestOffline() {
        val v = EatMapModel.view(area, evening, EatTab.TRENDING, emptySet(), AreaRatings.OFFLINE, emptySet())
        assertFalse(v.ranked)
        assertTrue(v.places.isNotEmpty())
        assertTrue(v.places.first().openness(evening, area.hours).isOpen)
        val open = EatMapModel.view(area, evening, EatTab.TOP, setOf(EatFilter.OPEN, EatFilter.VEGAN), AreaRatings.OFFLINE, emptySet())
        assertTrue(open.places.all { it.openness(evening, area.hours).isOpen && (it.diet("vegan") || it.diet("vegetarian")) })
        assertEquals(0, v.filterCounts.getValue(EatFilter.RATED))
        val loved = v.places.take(2).map { it.key }.toSet()
        val mine = EatMapModel.view(area, evening, EatTab.LOVED, emptySet(), AreaRatings.OFFLINE, loved)
        assertTrue(mine.ranked)
        assertEquals(loved, mine.places.map { it.key }.toSet())
    }
}
