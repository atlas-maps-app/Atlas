package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.BoardSymbols
import app.atlas.places.OpeningHoursContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** The shop package's logic on plain data: tours with closing times, runs, baskets, prices, the item table, the guides. */
class ShopLogicTest {
    private val here = LatLon(52.37452, 4.88377)
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val ctx = OpeningHoursContext.forPlace("NL", zone)
    private val tuesdayEvening = LocalDateTime.of(2026, 10, 13, 19, 30)

    private fun at(metresNorth: Double, metresEast: Double = 0.0) = Geo.destination(Geo.destination(here, 0.0, metresNorth), 90.0, metresEast)

    private fun shop(id: Long, name: String, tags: Map<String, String>, where: LatLon): ShopPlace =
        ShopPlace(Place(OsmId(OsmType.NODE, id), where, tags + ("name" to name)), name, Geo.distance(here, where))

    // ---- Tour ------------------------------------------------------------------------------------------------------

    @Test
    fun tourFindsTheShortWalkThroughThreeStops() {
        val stops = listOf(TourStop(at(600.0)), TourStop(at(200.0)), TourStop(at(400.0)))
        val r = Tour.solve(here, stops)
        assertEquals(listOf(1, 2, 0), r.order)
        assertTrue(r.feasible)
        assertEquals(600.0, r.metres, 5.0)
    }

    @Test
    fun tourKeepsAClosingTimeEvenWhenThatMeansALongerWalk() {
        // The far shop closes in 10 minutes: it must come first although the near ones are on the way.
        val far = TourStop(at(500.0), closesAt = 10.0)
        val a = TourStop(at(100.0))
        val b = TourStop(at(300.0))
        val r = Tour.solve(here, listOf(a, b, far))
        assertTrue(r.feasible)
        assertEquals(2, r.order.first())
    }

    @Test
    fun tourWaitsForAShopToOpen() {
        val r = Tour.solve(here, listOf(TourStop(at(100.0), opensAt = 30.0)))
        assertEquals(30.0, r.arrivals.single(), 1e-9)
    }

    @Test
    fun tourSaysWhenNoOrderKeepsEveryWindow() {
        val r = Tour.solve(here, listOf(TourStop(at(2000.0), closesAt = 5.0)))
        assertFalse(r.feasible)
    }

    @Test
    fun exactTourBeatsOrEqualsTheHeuristicOnTenStops() {
        val rnd = java.util.Random(7)
        val stops = List(10) { TourStop(at(rnd.nextDouble() * 800, rnd.nextDouble() * 800), serviceMinutes = 1.0) }
        val exact = Tour.solve(here, stops)
        // The same stops plus an eleventh nearby one go the heuristic way; it must stay close to the optimum.
        val heur = Tour.solve(here, stops + TourStop(at(1.0, 1.0), serviceMinutes = 0.0))
        assertTrue(heur.totalMinutes <= exact.totalMinutes * 1.15 + 1.0)
        assertEquals(10, exact.order.toSet().size)
        assertEquals(11, heur.order.toSet().size)
    }

    // ---- The run -------------------------------------------------------------------------------------------------------

    private val bakery = shop(1, "Bakker Bart", mapOf("shop" to "bakery", "opening_hours" to "Mo-Sa 08:00-19:45"), at(150.0))
    private val spice = shop(2, "Bazaar", mapOf("shop" to "spices", "opening_hours" to "Mo-Su 09:00-21:00"), at(300.0, 100.0))
    private val super1 = shop(3, "Albert Heijn", mapOf("shop" to "supermarket", "opening_hours" to "Mo-Su 08:00-22:00"), at(500.0))
    private val closedFar = shop(4, "Closed Greengrocer", mapOf("shop" to "greengrocer", "opening_hours" to "Mo-Fr 08:00-17:00"), at(100.0))
    private val shops = listOf(bakery, spice, super1, closedFar)

    private fun list(vararg texts: String) = texts.map { ListEntry(it) }

    @Test
    fun theTableKnowsWhereSourdoughAndOatMilkAreBought() {
        assertEquals(ShopKind.BAKERY, ListEntry("sourdough").kind)
        assertEquals(ShopKind.SUPERMARKET, ListEntry("oat milk").kind)
        assertEquals(ShopKind.BAKERY, ListEntry("brood").kind)
        assertEquals(ShopKind.SUPERMARKET, ListEntry("havermelk").kind)
        assertNull(ShopItems.classify("zzzzxq"))
        assertTrue(ShopItems.all.size >= 300)
        assertEquals(ShopItems.all.size, ShopItems.all.map { it.id }.toSet().size)
        assertTrue(ShopItems.all.all { it.names["en"].orEmpty().isNotEmpty() && it.names["nl"].orEmpty().isNotEmpty() })
    }

    @Test
    fun fewestStopsTakesOneSupermarketForEverythingItSells() {
        val run = RunPlanner.plan(list("milk", "eggs", "apples"), shops, here, tuesdayEvening, ctx, PlanBy.FEWEST)
        assertEquals(1, run.stopCount)
        assertTrue(run.unmet.isEmpty())
    }

    @Test
    fun bestGoesToTheBakerForTheBread() {
        val run = RunPlanner.plan(list("sourdough", "milk"), shops, here, tuesdayEvening, ctx, PlanBy.BEST)
        val bread = run.stops.first { s -> s.items.any { it.text == "sourdough" } }
        assertEquals("Bakker Bart", bread.shop.name)
    }

    @Test
    fun aShopThatHasClosedIsNeverAStop() {
        val run = RunPlanner.plan(list("carrots"), shops, here, tuesdayEvening, ctx, PlanBy.FEWEST)
        assertTrue(run.stops.none { it.shop.name == "Closed Greengrocer" })
    }

    @Test
    fun theBakeryClosingWarningShowsWhenItCloseswithinTheHour() {
        val late = LocalDateTime.of(2026, 10, 13, 19, 20)
        val run = RunPlanner.plan(list("sourdough"), shops, here, late, ctx, PlanBy.FEWEST)
        val s = run.stops.single()
        assertEquals("Bakker Bart", s.shop.name)
        assertTrue(s.closingSoon)
        assertEquals(LocalDateTime.of(2026, 10, 13, 19, 45), s.closesAt)
    }

    @Test
    fun anItemNobodyOpenSellsIsReportedNotInvented() {
        val run = RunPlanner.plan(list("sourdough"), listOf(super1.let { shop(9, "Closed Bakery", mapOf("shop" to "bakery", "opening_hours" to "Mo-Fr 07:00-12:00"), at(100.0)) }), here, tuesdayEvening, ctx, PlanBy.FEWEST)
        assertTrue(run.stops.isEmpty())
        assertEquals(1, run.unmet.size)
    }

    @Test
    fun cheapestUsesThePricesItIsGiven() {
        val other = shop(5, "Dirk", mapOf("shop" to "supermarket", "opening_hours" to "Mo-Su 08:00-22:00"), at(800.0))
        val price = { e: ListEntry, s: ShopPlace -> if (s.name == "Dirk") 1.0 else 2.0 }
        val run = RunPlanner.plan(list("milk"), listOf(super1, other), here, tuesdayEvening, ctx, PlanBy.CHEAPEST, price)
        assertEquals("Dirk", run.stops.single().shop.name)
        assertEquals(1.0, run.basket!!.total, 1e-9)
    }

    @Test
    fun theRunsRouteEndsAtTheLastStopWithTheOthersOnTheWay() {
        val run = RunPlanner.plan(list("sourdough", "saffron"), shops, here, tuesdayEvening, ctx, PlanBy.BEST)
        val r = routeOf(run)
        assertEquals(run.stops.last().shop.name, r.toName)
        assertEquals(run.stopCount - 1, r.via?.split('|')?.size ?: 0)
    }

    // ---- Prices -----------------------------------------------------------------------------------------------------------

    @Test
    fun openPricesReportsParseFromTheRealResponseShape() {
        val text = File(System.getProperty("atlas.repo"), "app/android/app/src/test/resources/shop/open_prices_amsterdam.json").readText()
        val reports = OpenPrices.parse(text)
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.all { it.price > 0 && it.shopOsmId.matches(Regex("[nwr]\\d+")) })
        assertTrue(reports.any { it.shopName != null })
        // OFF names carry doubled HTML escapes; they come out clean.
        assertTrue(reports.none { it.productName?.contains("&amp;") == true })
    }

    @Test
    fun aBasketComparesTheSameProductAtEveryShopAndMarksTheGaps() {
        val e1 = ListEntry("limes")
        val e2 = ListEntry("feta")
        fun rep(code: String, shop: String, price: Double, ago: Int) = PriceReport(code, "p$code", null, price, "EUR", java.time.LocalDate.of(2026, 10, 13).minusDays(ago.toLong()), shop, shop, null, here)
        val data = PriceData(
            mapOf(
                "limes" to listOf(rep("1", "n1", 1.8, 3), rep("1", "n2", 1.5, 7), rep("1", "n3", 2.1, 12), rep("2", "n1", 9.0, 1)),
                "feta" to listOf(rep("3", "n1", 3.99, 3), rep("3", "n2", 4.99, 7)),
            ),
            emptyList(),
        )
        val c = BasketCompare.compare(listOf(e1, e2), data, emptyList(), here, java.time.LocalDate.of(2026, 10, 13))
        assertEquals(3, c.baskets.size)
        // n1 and n2 have both lines; n3 is missing the feta.
        assertEquals(2, c.baskets[0].known)
        assertEquals(1, c.baskets.last().known)
        assertEquals(3..12, c.freshness)
        // The reference product for limes is the one three shops price (code 1), not the pricier single one.
        assertEquals("1", c.products["limes"]?.code)
        assertEquals(1.5, c.baskets.first { it.store.key == "n2" }.lines[0].price!!, 1e-9)
        assertNotNull(c.split)
        assertTrue(c.split!!.saving >= 0.0)
    }

    @Test
    fun yourOwnNoteBeatsAnOlderReportAtTheSameShop() {
        val e = ListEntry("limes")
        val data = PriceData(mapOf("limes" to listOf(PriceReport("1", "x", null, 2.0, "EUR", java.time.LocalDate.of(2026, 9, 1), "n1", "AH", null, here))), emptyList())
        val note = PriceNote("limes", "n1", "AH", 1.2, "2026-10-10", here.lat, here.lon)
        val idx = PriceIndex.of(listOf(e), data, listOf(note))
        assertEquals(1.2, idx.price(e, "n1")!!, 1e-9)
        assertTrue(idx.quote(e, "n1")!!.own)
    }

    // ---- Finding things -------------------------------------------------------------------------------------------------------

    @Test
    fun findKnowsPictureHooksAndTheGroceries() {
        val hooks = FindModel.resolve("picture hooks", "en")!!
        assertEquals(FindAnswer.Source.GOODS, hooks.source)
        assertEquals(5, hooks.sellers.first().likelihood)
        assertTrue(hooks.sellers.size >= 4)
        assertEquals(FindAnswer.Source.LIST, FindModel.resolve("oat milk", "en")!!.source)
        assertEquals(FindAnswer.Source.GOODS, FindModel.resolve("schilderijhaakjes", "nl")!!.source)
        assertNull(FindModel.resolve("qqqxzzv", "en"))
    }

    @Test
    fun findSplitsAQuestionIntoThingsAndSeesOpenNow() {
        val (things, open) = FindModel.parts("oat milk and saffron, open now")
        assertEquals(listOf("oat milk", "saffron"), things)
        assertTrue(open)
    }

    // ---- Waste, strolls, needs --------------------------------------------------------------------------------------------------

    @Test
    fun theWasteGuideSendsThingsToTheirStream() {
        assertEquals(Stream.BATTERIES, WasteGuide.lookup("batteries", "en")?.stream)
        assertEquals(Stream.PAPER, WasteGuide.lookup("pizza box", "en")?.stream)
        assertEquals(Stream.ORGANIC, WasteGuide.lookup("schillen", "nl")?.stream)
        assertNull(WasteGuide.lookup("a unicorn", "en"))
    }

    @Test
    fun strollOrdersShopsAlongTheLineTheyLieOn() {
        val s = listOf(300.0, 100.0, 200.0).mapIndexed { i, n -> shop(20L + i, "S$i", mapOf("shop" to "gift"), at(n)) }
        val (ordered, bearing) = Stroll.along(s, here)
        assertEquals(listOf("S1", "S2", "S0"), ordered.map { it.name })
        assertEquals(0.0, bearing, 5.0)
    }

    @Test
    fun theHeadingFollowsTheBackOfAPhoneHeldUpright() {
        // Upright phone facing east: device -Z (the camera) points east. Rotation matrix rows: east, north, up of x, y, z.
        val m = floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, -1f, 0f)
        assertEquals(90.0, HeadingFilter.fromRotation(m), 1e-6)
        val flatFacingNorth = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(0.0, HeadingFilter.fromRotation(flatFacingNorth), 1e-6)
    }

    @Test
    fun theFilterTurnsTheShortWayThroughNorth() {
        val f = HeadingFilter(gain = 0.5)
        f.add(350.0)
        val h = f.add(10.0)
        assertTrue(h > 350.0 || h < 10.0)
    }

    @Test
    fun everyBoardSymbolTheScreensDrawExists() {
        val ids = ShelfArts.values.map { it.symbol } + FamilyArts.values.map { it.symbol } + StreamArts.values.map { it.symbol } +
            ShopKind.entries.map { it.glyph } + listOf("ui.back", "ui.list.2", "ui.list", "ui.tag", "ui.route", "ui.star", "ui.add-person", "ui.camera", "ui.clock", "ui.pin", "ui.phone", "ui.grid", "ui.mail", "ui.lock.2", "ui.edit", "ui.heart.3", "ui.volume", "ui.calendar", "ui.brands", "ui.layers", "ui.check", "ui.search", "ui.bookmark", "ui.plus", "ui.arrow-up.2", "mode.walk", "mode.walk.5", "nav.direction", "need.water", "need.water.2", "need.food", "fact.container", "fact.card", "fact.wheelchair", "fact.baby", "fact.person", "fact.second-hand", "fact.independent", "fact.vegan.2", "fact.delivery", "shop.self-checkout", "place.pharmacy", "place.dry-cleaning", "place.laundry", "place.key-cutting", "place.print", "place.shoe-repair", "place.tailor", "place.fridge", "place.food-bank", "place.soup-kitchen", "place.shop", "place.books", "place.drinking-fountain", "sign.warning", "refill.jar", "bin.deposit", "bin.pizza-box", "bin.plastic-bag", "bin.takeout-tub", "tab.map")
        val missing = ids.distinct().filterNot { BoardSymbols.has(it) }
        assertTrue("missing symbols: $missing", missing.isEmpty())
    }
}
