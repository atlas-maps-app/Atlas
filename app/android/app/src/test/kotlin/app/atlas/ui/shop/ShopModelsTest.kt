package app.atlas.ui.shop

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.services.PlaceOrigin
import app.atlas.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/** The shop models on the real shops, errands and needs around the Westerkerk (OpenStreetMap), Tuesday 19:30. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopModelsTest {
    private val here = Services.WESTERKERK
    private val at = LocalDateTime.of(2026, 10, 13, 19, 30)

    private val area: ShopArea by lazy {
        val found = ShopFixtures.store.inBox(BBox.around(here, 1_500.0)).map { it.place }
        val places = found.mapNotNull { ShopPlace.of(it, here, "en", nameless = true) }.sortedBy { it.distanceM }
        ShopArea(here, 1_500.0, places, PlaceOrigin.STORE, loading = false, zone = Fixtures.evening.zone)
    }

    @Test
    fun thePantryCountsRealShopsByShelf() {
        val counts = PantryCounts.of(area, at)
        assertTrue(counts.byShelf.getValue(Shelf.SUPER).value > 5)
        assertTrue(counts.byShelf.getValue(Shelf.BAKERY).value >= 1)
        assertTrue(counts.total.open)
        assertTrue(counts.total.value > counts.byShelf.getValue(Shelf.SUPER).value)
        // Late: only food shops that are open after ten.
        val late = PantryCounts.placesOf(Shelf.LATE, area, at)
        assertTrue(late.all { it.openAfter(at.toLocalDate(), 22, area.hours) })
    }

    @Test
    fun anAisleKeepsTheFiltersAndSortsByDistanceOrSize() {
        val spec = Shelf.SUPER.spec()
        val all = Aisles.view(spec, area, at)
        assertTrue(all.brands.any { it.name.contains("Albert Heijn") })
        val sunday = Aisles.view(spec, area, at, filters = setOf(AisleFilter.SUNDAY))
        assertTrue(sunday.shown.size <= all.shown.size)
        assertTrue(sunday.shown.all { it.openSunday(at.toLocalDate(), area.hours) == true })
        val ah = Aisles.view(spec, area, at, brand = all.brands.first { it.name.contains("Albert Heijn") }.key)
        assertTrue(ah.shown.all { it.name.contains("Albert Heijn") || it.tags["brand"] == "Albert Heijn" })
        val near = all.shown.map { it.distanceM }
        assertEquals(near.sorted(), near)
        val biggest = Aisles.view(spec, area, at, sort = AisleSort.BIGGEST).shown.mapNotNull { it.footprintM2 }
        assertEquals(biggest.sortedDescending(), biggest)
        val indie = Aisles.view(spec, area, at, indie = true)
        assertTrue(indie.shown.all { it.independent })
    }

    @Test
    fun theHighStreetFamiliesAreExclusiveAndCounted() {
        val c = StreetCounts.of(area, at, indie = false)
        assertTrue(c.byFamily.getValue(Family.FASHION).value > 10)
        assertEquals("Amsterdam", c.town)
        val indie = StreetCounts.of(area, at, indie = true)
        assertTrue(indie.total.value <= c.total.value)
    }

    @Test
    fun helpFindsWaterAndKeepsKindsApart() {
        val v = Helps.view(area, only = null)
        assertTrue(v.counts.getValue(HelpKind.WATER) > 10)
        assertTrue(v.rows.isNotEmpty())
        val only = Helps.view(area, HelpKind.WATER)
        assertTrue(only.rows.all { it.kind == HelpKind.WATER })
    }

    @Test
    fun theNearestToiletPointsTheRightWay() {
        val t = PointMe.targets(NeedKind.TOILET, area, here, emptySet(), at)
        assertTrue(t.isNotEmpty())
        val first = t.first()
        assertEquals(Geo.bearing(here, first.place.location), first.bearing, 1e-6)
        assertEquals(t.sortedBy { it.distanceM }.filter { Fact.CUSTOMERS !in it.facts && it.open != Open.CLOSED }.map { it.key }.take(3), t.filter { Fact.CUSTOMERS !in it.facts && it.open != Open.CLOSED }.map { it.key }.take(3))
        val free = PointMe.targets(NeedKind.TOILET, area, here, setOf(NeedFilter.FREE), at)
        assertTrue(free.all { Fact.PAID !in it.facts })
        // FREE is claimed only where the fee is mapped as "no".
        assertTrue(t.all { (Fact.FREE in it.facts) == (it.place.tags["fee"] == "no") })
        assertEquals(90.0, PointMe.relative(180.0, 90.0), 1e-9)
        assertEquals(-170.0, PointMe.relative(350.0, 160.0), 1e-9)
    }

    @Test
    fun waterFoodAndBeaconTargetsExist() {
        val w = PointMe.targets(NeedKind.WATER, area, here, setOf(NeedFilter.FREE), at)
        assertTrue(w.isNotEmpty() && w.first().distanceM < 1_500)
        val f = PointMe.targets(NeedKind.FOOD, area, here, emptySet(), at)
        assertTrue(f.isNotEmpty())
        val n = beaconOf(f.first(), here, 0.0)
        assertEquals(f.first().distanceM, n.distance, 1e-6)
        assertEquals(f.first().place.name, n.label)
    }

    @Test
    fun binsKnowTheNearestPerStream() {
        val v = BinsFinder.view(area, here)
        assertNotNull(v.trash)
        assertNotNull(v.recycling)
        assertTrue(v.byStream.containsKey(Stream.PAPER) || v.byStream.containsKey(Stream.MIXED))
        for ((s, t) in v.byStream) assertTrue(s in t.streams)
        val drops = BinsFinder.forStream(area, here, Stream.PAPER)
        assertEquals(drops.sortedBy { it.distanceM }.map { it.key }, drops.map { it.key })
    }

    @Test
    fun refillFillsTheJarByTheHoursLeft() {
        val v = Refills.view(RefillKind.WATER, area, at, setOf(RefillFilter.NOW), "en")
        assertTrue(v.rows.isNotEmpty())
        assertTrue(v.rows.all { it.fill in 0f..1f })
        assertTrue(v.counts.getValue(RefillKind.WATER) >= v.rows.size)
    }

    @Test
    fun errandsAreOneWalkThatKeepsClosingTimes() {
        val plan = ErrandsPlanner.plan(listOf(Errand.PHARMACY, Errand.CASH, Errand.POST), area.places, here, at, area.hours)
        assertTrue(plan.stops.isNotEmpty())
        assertEquals(plan.stops.size + plan.missing.size, 3)
        for (s in plan.stops) s.closesAt?.let { assertTrue(s.arriveAt <= it) }
        assertTrue(plan.minutes > 0 && plan.doneAt.isAfter(at))
        val all = ErrandsPlanner.plan(Errand.ring + Errand.more.take(3), area.places, here, at, area.hours)
        assertTrue(all.stops.size <= 8)
    }

    @Test
    fun strollWalksOneStreetInOrder() {
        val street = Stroll.street(ShopArea(here, 800.0, area.places.filter { it.tags["shop"] != null && it.distanceM <= 700 }, PlaceOrigin.STORE, false), here)
        assertNotNull(street)
        assertTrue(street!!.shops.size >= 3)
        assertTrue(street.start in street.shops.indices)
        if (street.name.isNotEmpty()) assertTrue(street.shops.count { it.tags["addr:street"] == street.name } >= 3)
    }

    @Test
    fun theListAndTheContainersAreKeptOnThePhone() {
        val dir = Files.createTempDirectory("atlas-shop-store").toFile()
        val list = ShoppingList(File(dir, "list.tsv"))
        assertTrue(list.add("  oat   milk "))
        assertFalse(list.add("Oat milk"))
        assertFalse(list.add("  "))
        list.add("eggs\twith tab")
        list.toggleHome(list.items.value.first { it.text == "eggs with tab" })
        val again = ShoppingList(File(dir, "list.tsv"))
        assertEquals(listOf("oat milk", "eggs with tab"), again.items.value.map { it.text })
        assertTrue(again.items.value[1].atHome)
        assertEquals(listOf("oat milk"), again.toBuy.map { it.text })
        val jars = ContainerBook(File(dir, "c.tsv"))
        jars.add(Container("1 L jar", "jar", 420))
        assertEquals(420, ContainerBook(File(dir, "c.tsv")).items.value.single().tareGrams)
        val notes = PriceNotes(File(dir, "p.tsv"))
        notes.add(PriceNote("limes", "n1", "AH", 1.5, "2026-10-13", 52.3, 4.9))
        notes.add(PriceNote("limes", "n1", "AH", 1.4, "2026-10-14", 52.3, 4.9))
        assertEquals(1.4, PriceNotes(File(dir, "p.tsv")).items.value.single().price, 1e-9)
    }

    @Test
    fun theFinderAsksTheStoreAndKeepsTheAnswerForAWhile() = runBlocking {
        val services = ShopFixtures.services()
        val repo = ShopRepository.of(services)
        val first = repo.area("t", Shelf.SUPER.filter, here).let { f -> var last: ShopArea? = null; f.collect { last = it }; last!! }
        assertTrue(first.places.isNotEmpty())
        assertEquals(PlaceOrigin.STORE, first.origin)
        val second = repo.area("t", Shelf.SUPER.filter, here).let { f -> var last: ShopArea? = null; f.collect { last = it }; last!! }
        assertEquals(first.places.map { it.key }, second.places.map { it.key })
        services.close()
    }
}
