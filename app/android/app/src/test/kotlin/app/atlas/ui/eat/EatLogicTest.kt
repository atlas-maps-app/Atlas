package app.atlas.ui.eat

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.providers.Trend
import app.atlas.services.PlaceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The eat package's logic on small hand-made places: no store, no Android. */
class EatLogicTest {
    private val centre = LatLon(52.37452, 4.88377)
    private val zone = ZoneId.of("Europe/Amsterdam")

    /** Tuesday 13 October 2026, 19:30 in Amsterdam. */
    private val evening = LocalDateTime.of(2026, 10, 13, 19, 30)

    private var nextId = 1L

    private fun place(dLat: Double, dLon: Double, vararg tags: Pair<String, String>): Place =
        Place(OsmId.parse("n${nextId++}"), LatLon(centre.lat + dLat, centre.lon + dLon), mapOf(*tags))

    private fun eat(p: Place) = EatPlace.of(p, centre, "en")!!

    private fun area(vararg places: Place) =
        EatArea(centre, EatArea.RADIUS_M, places.map { eat(it) }.sortedBy { it.distanceM }, PlaceOrigin.STORE, loading = false, country = "NL", zone = zone)

    @Test
    fun theTableMeansTomorrowForATimeLongPast() {
        val clock = Clock.fixed(Instant.parse("2026-10-13T17:30:00Z"), zone)
        assertEquals(LocalDateTime.of(2026, 10, 13, 19, 30), Table().at(clock))
        assertEquals(LocalDateTime.of(2026, 10, 13, 21, 0), Table(LocalTime.of(21, 0)).at(clock))
        assertEquals(LocalDateTime.of(2026, 10, 13, 19, 15), Table(LocalTime.of(19, 15)).at(clock))
        assertEquals(LocalDateTime.of(2026, 10, 14, 8, 0), Table(LocalTime.of(8, 0)).at(clock))
        assertEquals(Meal.DINNER, Meal.at(LocalTime.of(19, 30)))
        assertEquals(Meal.LATE, Meal.at(LocalTime.of(0, 30)))
        val state = TableState(clock)
        state.shift(20)
        assertEquals(LocalTime.of(19, 45), state.table.value.time)
        state.setParty(99)
        assertEquals(Table.MAX_PARTY, state.table.value.party)
    }

    @Test
    fun opennessReadsTheHours() {
        val p = eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "A", "opening_hours" to "Mo-Su 12:00-20:00"))
        val a = area()
        assertEquals(Openness.CLOSING_SOON, p.openness(evening, a.hours))
        assertEquals(Openness.OPEN, p.openness(evening.withHour(15), a.hours))
        assertEquals(Openness.CLOSED, p.openness(evening.withHour(21), a.hours))
        assertEquals(LocalDateTime.of(2026, 10, 14, 12, 0), p.opensAt(evening.withHour(21), a.hours))
        assertEquals(LocalDateTime.of(2026, 10, 13, 20, 0), p.closesAt(evening, a.hours))
        val none = eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "B"))
        assertEquals(Openness.UNKNOWN, none.openness(evening, a.hours))
    }

    @Test
    fun cuisinesFindTheirFamilies() {
        assertEquals(CuisineFamily.JAPAN, CuisineFamily.of("ramen"))
        assertEquals(CuisineFamily.ITALY, CuisineFamily.of("italian_pizza"))
        assertEquals(CuisineFamily.EUROPE, CuisineFamily.of("regional", "NL"))
        assertEquals(CuisineFamily.MORE, CuisineFamily.of("regional"))
        assertEquals(CuisineFamily.AMERICAS, CuisineFamily.of("surinamese"))
        assertEquals("pancake", CuisineTaxonomy.canonical("Pancakes"))
        val p = eat(place(0.0, 0.001, "amenity" to "restaurant", "name" to "X", "cuisine" to "seafood;sushi"))
        assertEquals("sushi", CarteModel.lineOf(p, "NL"))
        val q = eat(place(0.0, 0.001, "amenity" to "fast_food", "name" to "Y"))
        assertEquals(CarteModel.UNMAPPED_FAST_FOOD, CarteModel.lineOf(q, "NL"))
    }

    @Test
    fun theOrderPadIsKeptAndReadBack() {
        val pad = OrderPad.DATE_NIGHT.copy(cuisines = listOf("japanese", "korean"), maxPrice = 2, card = true)
        assertEquals(pad, OrderBook.parse(OrderBook.write(pad)))
        assertEquals(OrderPad(), OrderBook.parse("nonsense\nparty=abc"))
        val dir = Files.createTempDirectory("order").toFile()
        val book = OrderBook(File(dir, "order.txt"))
        book.set(pad)
        book.bookmark()
        assertEquals(pad, OrderBook(File(dir, "order.txt")).saved.value)
        assertEquals("45′", durationText(45))
        assertEquals("1½ h", durationText(90))
        assertEquals("2 h", durationText(120))
    }

    @Test
    fun theShortlistSurvivesARestart() {
        val dir = Files.createTempDirectory("pad").toFile()
        val file = File(dir, "shortlist.tsv")
        val a = eat(place(0.001, 0.0, "amenity" to "bar", "name" to "Tab\tName"))
        val b = eat(place(0.002, 0.0, "amenity" to "pub", "name" to "Pub"))
        val pad = Shortlist(file)
        assertTrue(pad.toggle(a))
        assertTrue(pad.toggle(b))
        assertEquals(listOf(b.key, a.key), Shortlist(file).items.value.map { it.key })
        assertFalse(pad.toggle(a))
        assertEquals(listOf("Pub"), Shortlist(file).items.value.map { it.name })
    }

    @Test
    fun barTabsAndMoodsReadTags() {
        val wine = eat(place(0.001, 0.0, "amenity" to "bar", "name" to "Wijnbar Boelen"))
        val tapas = eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "Tapas Bar"))
        val brewery = eat(place(0.001, 0.0, "amenity" to "pub", "name" to "Proeflokaal", "microbrewery" to "yes", "outdoor_seating" to "yes"))
        assertTrue(BarTab.WINE.has(wine))
        assertFalse(BarModel.named(tapas, BarModel.BEER_WORDS))
        assertTrue(BarTab.BEER.has(brewery))
        assertTrue(BarTab.PUBS.has(brewery))
        assertTrue(BarTab.GARDENS.has(brewery))
        val hooks = BarModel.hooks(brewery, evening, area())
        assertEquals(listOf(Hook.Brews, Hook.Terrace), hooks)
        val plain = eat(place(0.001, 0.0, "amenity" to "pub", "name" to "Plain", "addr:street" to "Prinsengracht"))
        assertEquals(listOf(Hook.On("Prinsengracht")), BarModel.hooks(plain, evening, area()))
    }

    @Test
    fun theCrawlWalksTheShortestWay() {
        // Three bars on a line north of here, given in the worst order.
        val far = eat(place(0.006, 0.0, "amenity" to "bar", "name" to "Far"))
        val near = eat(place(0.002, 0.0, "amenity" to "bar", "name" to "Near"))
        val mid = eat(place(0.004, 0.0, "amenity" to "bar", "name" to "Mid"))
        val crawl = BarModel.crawl(centre, listOf(far, near, mid), emptyList())
        assertEquals(listOf("Near", "Mid", "Far"), crawl.stops.map { it.name })
        // 667 m straight, with the city's detour, is about 11 minutes on foot.
        assertTrue("${crawl.minutes}", crawl.minutes in 10..12)
        assertEquals(0, BarModel.crawl(centre, emptyList(), emptyList()).stops.size)
    }

    @Test
    fun lateNightNeedsHoursPastMidnight() {
        val kebab = place(0.001, 0.0, "amenity" to "fast_food", "name" to "Kebab", "cuisine" to "kebab", "opening_hours" to "Mo-Su 11:00-03:00")
        val pizza = place(0.002, 0.0, "amenity" to "fast_food", "name" to "Pizza", "cuisine" to "pizza", "opening_hours" to "Mo-Su 11:00-23:00")
        val fries = place(0.003, 0.0, "amenity" to "fast_food", "name" to "Frites", "cuisine" to "friture", "opening_hours" to "24/7")
        val a = area(kebab, pizza, fries)
        val wall = LateModel.wall(a, evening, null, emptySet(), LateSort.NEAREST)
        assertEquals(listOf("Kebab", "Frites"), wall.spots.map { it.place.name })
        assertEquals(LocalDateTime.of(2026, 10, 14, 3, 0), wall.till)
        assertEquals(9, wall.tiles.size)
        assertEquals(1, wall.tiles.first { it.first == Bite.KEBAB }.second)
        val latest = LateModel.wall(a, evening, null, emptySet(), LateSort.LATEST)
        assertEquals("Frites", latest.spots.first().place.name)
        val always = LateModel.wall(a, evening, null, setOf(LateChip.ALWAYS), LateSort.NEAREST)
        assertEquals(listOf("Frites"), always.spots.map { it.place.name })
        // After midnight it is still the same night.
        assertEquals(EatSets.nightOf(evening), EatSets.nightOf(evening.plusHours(6)))
    }

    @Test
    fun marketDaysComeFromTheRealWeek() {
        val noorder = place(
            0.002, 0.0, "amenity" to "marketplace", "name" to "Noordermarkt",
            "opening_hours" to "Mo 09:00-13:00 open \"Brocante\"; Sa 09:00-16:00 open \"Biologische Boerenmarkt\"",
        )
        val week = MarketModel.week(listOf(eat(noorder)), area(noorder), evening.toLocalDate())
        assertEquals(7, week.size)
        assertEquals(DayOfWeek.MONDAY, week.first().date.dayOfWeek)
        assertEquals("Brocante", week[0].sessions.single().what)
        assertTrue(week[1].sessions.isEmpty())
        val saturday = week[5].sessions.single()
        assertEquals(LocalTime.of(9, 0), saturday.from)
        assertEquals(LocalTime.of(16, 0), saturday.to)
        assertEquals("Biologische Boerenmarkt", saturday.what)
    }

    @Test
    fun theMachinePrintsTheSameTicketAllDay() {
        val ramen = place(0.001, 0.0, "amenity" to "restaurant", "name" to "Ramen Tei", "cuisine" to "ramen", "opening_hours" to "Mo-Su 17:00-22:00", "payment:cash" to "only")
        val sushi = place(0.002, 0.0, "amenity" to "restaurant", "name" to "Sushi", "cuisine" to "sushi", "opening_hours" to "Mo-Su 21:00-23:00")
        val a = area(ramen, sushi)
        val world = CuisineWorlds.JAPAN
        // With one shop each, "the plank with most places" is the first of them.
        assertEquals(world.plankIndex("sushi"), WorldModel.plankIndex(world, a, -1))
        assertEquals(world.plankIndex("ramen"), WorldModel.plankIndex(world, a, world.plankIndex("ramen")))
        val m = WorldModel.machine(world, world.plankIndex("sushi"), a, evening, emptySet(), AreaRatings.OFFLINE)
        val b = m.buttons.single()
        assertEquals(Openness.CLOSED, b.openness)
        assertEquals(LocalDateTime.of(2026, 10, 13, 21, 0), b.opens)
        val r = WorldModel.machine(world, world.plankIndex("ramen"), a, evening, emptySet(), AreaRatings.OFFLINE).buttons.single()
        assertTrue(r.cashOnly)
        val t1 = WorldModel.ticket(r, evening)
        val t2 = WorldModel.ticket(r, evening.plusHours(2))
        assertEquals(t1.number, t2.number)
        assertEquals(4, t1.number.length)
        assertEquals(MealTicket.Pays.CASH_ONLY, t1.pays)
        assertEquals(CuisineWorlds.JAPAN to world.plankIndex("soba"), CuisineWorlds.route("soba"))
        assertEquals(-1, CuisineWorlds.route("world:japanese")?.second)
        assertNull(CuisineWorlds.route("tacos"))
    }

    @Test
    fun ringsAndSparklines() {
        val p = eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "R", "opening_hours" to "Mo-Su 12:00-21:30"))
        val a = area()
        assertEquals(0.5f, EatMapModel.ring(p, evening, a)!!, 0.01f)
        assertNull(EatMapModel.ring(p, evening.withHour(22), a))
        val trend = Trend(last30 = 6, last90 = 9, last365 = 15, total = 27, velocity = 3.0, momentum = 0.0, rising = true, newcomer = false, firstReview = null, lastReview = null)
        val line = EatMapModel.sparkline(trend)!!
        assertEquals(4, line.size)
        assertEquals(6f, line[3], 0.001f)
        assertNull(EatMapModel.sparkline(null))
    }

    @Test
    fun theCafeSpecialIsBrunchOnlyInTheMorning() {
        val brunch = place(0.001, 0.0, "amenity" to "cafe", "name" to "Brunch", "cuisine" to "breakfast", "opening_hours" to "Mo-Su 08:00-16:00")
        val late = place(0.002, 0.0, "amenity" to "cafe", "name" to "Late", "opening_hours" to "Mo-Su 08:00-23:00")
        val a = area(brunch, late)
        val morning = CafeModel.special(EatSets.cafes(a.places), a, evening.withHour(10))
        assertEquals("Brunch", morning?.place?.name)
        assertTrue(morning!!.brunch)
        val night = CafeModel.special(EatSets.cafes(a.places), a, evening)
        assertEquals("Late", night?.place?.name)
        assertFalse(night!!.brunch)
        assertEquals(LocalDateTime.of(2026, 10, 14, 7, 30), CafeModel.morningOf(evening))
        val coffeeshop = eat(place(0.001, 0.0, "amenity" to "cafe", "name" to "Smoky", "cuisine" to "soft_drugs"))
        assertFalse(CafeKind.COFFEE.has(coffeeshop))
        assertNotNull(CafeKind.entries.firstOrNull { it.has(eat(brunch)) })
    }

    @Test
    fun theOrderMatchesTheOtherSpellingsOfACuisine() {
        val doner = place(0.001, 0.0, "amenity" to "restaurant", "name" to "Doner Huis", "cuisine" to "doner", "opening_hours" to "Mo-Su 11:00-23:00")
        val pizza = place(0.002, 0.0, "amenity" to "restaurant", "name" to "Pizzeria", "cuisine" to "pizza", "opening_hours" to "Mo-Su 11:00-23:00")
        val a = area(doner, pizza)
        assertTrue(CuisineTaxonomy.spellings("kebab").containsAll(listOf("kebab", "doner", "kebap")))
        val kebab = OrderModel.evaluate(OrderPad(cuisines = listOf("kebab")), evening, zone, a, AreaRatings.OFFLINE, "en")
        assertEquals(listOf("Doner Huis"), kebab.served.map { it.place.name() })
        val notKebab = OrderModel.evaluate(OrderPad(not = listOf("kebab")), evening, zone, a, AreaRatings.OFFLINE, "en")
        assertEquals(listOf("Pizzeria"), notKebab.served.map { it.place.name() })
    }

    @Test
    fun aKitchenNobodyMappedGetsAnEmptyPlate() {
        val unknown = eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "Mystery"))
        val fastFood = eat(place(0.001, 0.0, "amenity" to "fast_food", "name" to "Snack"))
        assertEquals(Dish.EMPTY, dishOf(unknown, "NL"))
        assertEquals(Dish.EMPTY, dishOf(fastFood, "NL"))
        assertEquals(Dish.PIZZA, dishOf(eat(place(0.001, 0.0, "amenity" to "restaurant", "name" to "P", "cuisine" to "pizza")), "NL"))
    }

    @Test
    fun theTicketNumberAlwaysHasFourFigures() {
        val a = area()
        for (i in 0 until 400) {
            val p = eat(place(0.001, 0.001 * i, "amenity" to "restaurant", "name" to "R$i", "cuisine" to "ramen"))
            val b = MachineButton(p, Openness.OPEN, null, null, null, vegan = false, cashOnly = false)
            val n = WorldModel.ticket(b, evening.plusDays(i.toLong())).number
            assertTrue(n, Regex("\\d{4}").matches(n))
        }
        assertTrue(a.places.isEmpty())
    }

    @Test
    fun aTimeOnAnotherDayNamesTheDay() {
        val tomorrowNoon = LocalDateTime.of(2026, 10, 14, 12, 0)
        assertEquals("12:30", dayTime(evening.withHour(12), evening, "en"))
        assertEquals("Wed 12:00", dayTime(tomorrowNoon, evening, "en"))
        assertEquals("Wed 09:30", dayTime(tomorrowNoon.withHour(9).withMinute(30), evening, "en", HM_PADDED))
    }

    @Test
    fun theWaiterAnswersAnythingYouTypeOrSay() {
        val pizza = place(0.001, 0.0, "amenity" to "restaurant", "name" to "Pizzeria Roma", "cuisine" to "pizza", "opening_hours" to "Mo-Su 11:00-23:00")
        val a = area(pizza)
        val odd = listOf(
            "", "   ", "((", "[", "\\", "*", "a within", "within 200 m of", "near", "open at 99:99", "open until", "\ud83d\udca5", "vegan\u0000pizza", "pizza ".repeat(2_000),
            "pizza open now", "pizza near the Westerkerk", "A within 200 m of B", "terras met bier", "cuisine=", "=pizza", "cuisine~[", "dog~(",
        )
        for (text in odd) {
            val answer = Waiter.ask(text, a, evening, zone, "en")
            assertEquals(text.take(20), text, answer.text)
        }
        assertEquals(listOf("Pizzeria Roma"), Waiter.ask("pizza", a, evening, zone, "en").hits.map { it.place.name() })
    }
}
