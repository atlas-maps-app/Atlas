package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.ui.kit.hasSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

/** What the review of the shop package found: links and numbers a tag can spoil, claims the map does not make, and strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopReviewTest {
    private val repo = File(System.getProperty("atlas.repo"))
    private val shopSources = File(repo, "app/android/app/src/main/kotlin/app/atlas/ui/shop")
    private val res = File(repo, "app/android/app/src/main/res")
    private val here = LatLon(52.37452, 4.88377)

    private fun place(id: Long, tags: Map<String, String>, where: LatLon = Geo.destination(here, 0.0, 80.0)): ShopPlace =
        ShopPlace(Place(OsmId(OsmType.NODE, id), where, tags), tags["name"] ?: "x", Geo.distance(here, where))

    @Test
    fun everySymbolIdInTheSourcesIsOneAtlasCanDraw() {
        // A missing id is drawn as a map pin, silently: scan every literal that is written like a symbol id.
        val like = Regex("\"((?:ui|place|fact|need|bin|shop|grocery|food|refill|market|sweets|mode|nav|sign|cat|pantry|tab)[.-][a-z0-9][a-z0-9.-]*)\"")
        val missing = HashSet<String>()
        for (f in shopSources.listFiles { it.extension == "kt" }.orEmpty()) {
            for (m in like.findAll(f.readText())) if (!hasSymbol(m.groupValues[1])) missing += f.name + ": " + m.groupValues[1]
        }
        assertTrue("symbols Atlas cannot draw: $missing", missing.isEmpty())
    }

    @Test
    fun everyTranslationHasTheSamePlaceholdersAsTheEnglish() {
        fun load(dir: String): Map<String, String> {
            val text = File(res, "$dir/strings_shop.xml").readText()
            return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val args = Regex("%(?:\\d+\\$)?[0-9.]*[sdf]")
        val en = load("values")
        for (lang in listOf("nl", "de", "fr")) {
            val other = load("values-$lang")
            for ((k, v) in en) {
                val t = other[k] ?: continue
                assertEquals("$lang $k", args.findAll(v).map { it.value }.sorted().toList(), args.findAll(t).map { it.value }.sorted().toList())
            }
            // Dutch is the language the boards were written for: nothing may be missing.
            if (lang == "nl") assertEquals("missing in nl", emptySet<String>(), en.keys - other.keys)
        }
    }

    @Test
    fun aWebsiteTagOpensOnlyAsHttpOrHttps() {
        assertEquals("https://example.org", webAddress("example.org"))
        assertEquals("http://example.org/a", webAddress(" http://example.org/a "))
        assertEquals("https://example.org", webAddress("https://example.org"))
        assertNull(webAddress("javascript:alert(1)"))
        assertNull(webAddress("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(webAddress("file:///sdcard/x"))
        assertNull(webAddress("two words.org"))
        assertNull(webAddress("http://"))
        assertNull(webAddress(""))
    }

    @Test
    fun aPhoneTagDialsTheFirstNumberAndNeverAnEmptyOne() {
        assertEquals("tel:+31206247766", dialIntent("+31 20 624 7766; +31 20 555 0100")!!.data.toString())
        assertNull(dialIntent("call us"))
        assertNull(dialIntent(""))
    }

    @Test
    fun aPriceIsWrittenTheWayThePhoneWritesNumbers() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("nl", "NL"))
            val nl = money(21.4, "EUR")
            assertTrue(nl, nl.contains("21,40"))
            Locale.setDefault(Locale.US)
            assertEquals("€21.40", money(21.4, "EUR"))
            assertTrue(money(3.0, "XXX-NOT-A-CURRENCY").contains("3.00"))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun aToiletIsCalledFreeOnlyWhereTheMapSaysSo() {
        val unknownFee = place(1, mapOf("amenity" to "toilets", "name" to "Restroom"))
        val free = place(2, mapOf("amenity" to "toilets", "fee" to "no", "name" to "Free"))
        val paid = place(3, mapOf("amenity" to "toilets", "fee" to "yes", "name" to "Paid"))
        assertFalse(Fact.FREE in PointMe.facts(NeedKind.TOILET, unknownFee))
        assertFalse(Fact.PAID in PointMe.facts(NeedKind.TOILET, unknownFee))
        assertTrue(Fact.FREE in PointMe.facts(NeedKind.TOILET, free))
        assertTrue(Fact.PAID in PointMe.facts(NeedKind.TOILET, paid))
        // The FREE filter still keeps the one nobody tagged (most toilets are untagged) and drops the paid one.
        val area = ShopArea(here, 500.0, listOf(unknownFee, free, paid), null, loading = false)
        val kept = PointMe.targets(NeedKind.TOILET, area, here, setOf(NeedFilter.FREE), LocalDateTime.of(2026, 10, 13, 12, 0)).map { it.place.name }
        assertEquals(setOf("Restroom", "Free"), kept.toSet())
        // Food: FREE keeps only what the map says is free.
        val fridge = place(4, mapOf("amenity" to "fridge", "name" to "Fridge"))
        val cafe = place(5, mapOf("amenity" to "cafe", "name" to "Cafe"))
        val foodArea = ShopArea(here, 500.0, listOf(fridge, cafe), null, loading = false)
        val foodFree = PointMe.targets(NeedKind.FOOD, foodArea, here, setOf(NeedFilter.FREE), LocalDateTime.of(2026, 10, 13, 12, 0)).map { it.place.name }
        assertEquals(listOf("Fridge"), foodFree)
    }

    @Test
    fun theKilometresOfARunAreStreetKilometresLikeItsMinutes() {
        val shop = place(9, mapOf("shop" to "supermarket", "name" to "Albert"), Geo.destination(here, 0.0, 500.0))
        val ctx = app.atlas.places.OpeningHoursContext.forPlace("NL", java.time.ZoneId.of("Europe/Amsterdam"))
        val run = RunPlanner.plan(listOf(ListEntry("milk")), listOf(shop), here, LocalDateTime.of(2026, 10, 13, 12, 0), ctx, PlanBy.FEWEST)
        assertEquals(1, run.stops.size)
        // 500 m as the crow flies is about 650 m of streets, which is what the walking minutes were counted over.
        assertEquals(650.0, run.metres, 10.0)
        val walkMinutes = run.stops.first().walkMinutes
        assertEquals(run.metres / (4.8 * 1000 / 60), walkMinutes.toDouble(), 1.0)
    }

    @Test
    fun pricesInTwoCurrenciesAreNotAddedUpAndAShopWithoutANameIsLeftOut() {
        val day = java.time.LocalDate.of(2026, 10, 10)
        fun report(shop: String, name: String?, price: Double, currency: String) =
            PriceReport("123", "Milk", "1 l", price, currency, day, shop, name, null, here)
        val data = PriceData(
            mapOf("milk" to listOf(report("n1", "Dirk", 1.0, "EUR"), report("n2", "Jumbo", 1.1, "EUR"), report("n3", "Migros", 2.0, "CHF"), report("n4", null, 0.5, "EUR"))),
            emptyList(),
        )
        val index = PriceIndex.of(listOf(ListEntry("milk")), data, emptyList())
        assertEquals("EUR", index.currency)
        assertEquals(setOf("n1", "n2"), index.stores.keys)
    }

    @Test
    fun aBubblerIsNotABottleFillerAndAnUntaggedTapIsNotCalledOne() {
        val bubbler = place(11, mapOf("amenity" to "drinking_water", "fountain" to "bubbler", "bottle" to "no", "name" to "Bubbler"))
        val filler = place(12, mapOf("amenity" to "drinking_water", "bottle" to "yes", "name" to "Filler"))
        val plain = place(13, mapOf("amenity" to "drinking_water", "name" to "Tap"))
        val refill = place(14, mapOf("amenity" to "drinking_water", "fountain" to "bottle_refill", "name" to "Refill"))
        assertTrue(Fact.BOTTLE in PointMe.facts(NeedKind.WATER, refill))
        assertTrue(Fact.NO_BOTTLE in PointMe.facts(NeedKind.WATER, bubbler))
        assertFalse(Fact.BOTTLE in PointMe.facts(NeedKind.WATER, bubbler))
        assertTrue(Fact.BOTTLE in PointMe.facts(NeedKind.WATER, filler))
        assertFalse(Fact.BOTTLE in PointMe.facts(NeedKind.WATER, plain))
        val area = ShopArea(here, 500.0, listOf(bubbler, filler, plain, refill), null, loading = false)
        val kept = PointMe.targets(NeedKind.WATER, area, here, setOf(NeedFilter.BOTTLE), LocalDateTime.of(2026, 10, 13, 12, 0)).map { it.place.name }
        assertEquals(setOf("Filler", "Tap", "Refill"), kept.toSet())
    }

    @Test
    fun theTrueDirectionIsNotTheMagneticOne() {
        // The rotation sensor's north is magnetic: Amsterdam's declination is a few degrees east, and it must be added.
        val d = android.hardware.GeomagneticField(52.37f, 4.88f, 0f, java.time.Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()).declination
        assertTrue(d in 0.5f..8f)
    }

    @Test
    fun theErrandsWalkIsPlannedFastEvenForSixErrandsAtThreePlacesEach() {
        // 3^6 combinations of places, each walked in the best order: it runs off the main thread, but must stay quick.
        val kinds = listOf(Errand.PHARMACY, Errand.PARCEL, Errand.CASH, Errand.CLEANING, Errand.POST, Errand.KEYS)
        var id = 1L
        val places = kinds.flatMapIndexed { k, e ->
            (0 until 3).map { j ->
                val tags = ErrandTags.of(e)
                place(id++, tags + ("name" to "${e.name}-$j"), Geo.destination(here, k * 60.0, 150.0 + 90.0 * j + 40.0 * k))
            }
        }
        val started = System.nanoTime()
        val plan = ErrandsPlanner.plan(kinds, places, here, LocalDateTime.of(2026, 10, 13, 12, 0), app.atlas.places.OpeningHoursContext.forPlace("NL", java.time.ZoneId.of("Europe/Amsterdam")))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(6, plan.stops.size + plan.missing.size)
        assertNotNull(plan.stops.firstOrNull())
        assertTrue("took $ms ms", ms < 4_000)
    }
}

/** A tag set that satisfies the rule of an errand, for tests. */
private object ErrandTags {
    fun of(e: Errand): Map<String, String> = when (e) {
        Errand.PHARMACY -> mapOf("amenity" to "pharmacy")
        Errand.PARCEL -> mapOf("amenity" to "parcel_locker")
        Errand.CASH -> mapOf("amenity" to "atm")
        Errand.CLEANING -> mapOf("shop" to "dry_cleaning")
        Errand.POST -> mapOf("amenity" to "post_office")
        Errand.KEYS -> mapOf("craft" to "key_cutter")
        else -> error("not used")
    }
}
