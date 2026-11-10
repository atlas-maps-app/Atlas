package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.providers.BathingSite
import app.atlas.providers.Forecast
import app.atlas.providers.Precipitation15
import app.atlas.providers.Sky
import app.atlas.providers.SwimAdvisory
import app.atlas.providers.WeatherDay
import app.atlas.providers.WaterKind
import app.atlas.providers.WaterQuality
import app.atlas.providers.WeatherHour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

class PurposeTest {
    private val here = LatLon(52.37, 4.88)
    private val saturday = Moment(Fixtures.saturday)
    private val now: LocalDateTime = Fixtures.saturday.toLocalDateTime()

    private fun place(id: Long, lat: Double, lon: Double, vararg tags: Pair<String, String>, bbox: BBox? = null, type: OsmType = OsmType.NODE) =
        PlaceRecord.of(Place(OsmId(type, id), LatLon(lat, lon), tags.toMap(), bbox))

    @Test
    fun everyKindHasAPurposeOfItsOwn() {
        assertEquals(Layout.NEAREST, Purposes.of(CategoryTree.category("toilets")!!).layout)
        assertEquals(TellKey.FEE, Purposes.of(CategoryTree.category("toilets")!!).tells.first())
        assertEquals(Layout.POWER, Purposes.of(CategoryTree.category("charging")!!).layout)
        assertEquals(Layout.BEST, Purposes.of(CategoryTree.category("restaurants")!!).layout)
        assertEquals(Layout.ERRAND, Purposes.of(CategoryTree.category("food_shops")!!).layout)
        assertEquals(Layout.WINDOW, Purposes.of(CategoryTree.category("parks")!!).layout)
        // a narrower kind takes its category's, unless it has one of its own
        val pizza = CategoryTree.preset("amenity/restaurant/pizza") ?: CategoryTree.all().first { it.kind == NodeKind.PRESET && it.category.id == "restaurants" }
        assertEquals(Purposes.of(CategoryTree.category("restaurants")!!), Purposes.of(pizza))
        val lake = CategoryTree.preset("natural/water/lake")!!
        assertEquals(Layout.WINDOW, Purposes.of(lake).layout)
        assertEquals("swim", Purposes.of(lake).parts)
        assertEquals(TellKey.SWIM, Purposes.of(lake).tells.first())
        // every kind, shelf to preset, has one, and its words are filters ("facet=value")
        for (n in CategoryTree.all()) {
            val p = Purposes.of(n)
            assertTrue("${n.id} orders", p.orders.isNotEmpty())
            for (w in p.words) assertTrue("${n.id}: $w", w.indexOf('=') > 0)
            assertEquals("${n.id} orders twice", p.orders.size, p.orders.toSet().size)
        }
        // every intent a purpose parts by exists
        assertTrue(CategoryTree.all().mapNotNull { Purposes.of(it).parts }.all { Intents.byId(it) != null })
    }

    @Test
    fun aRowSaysWhatItsKindIsFor() {
        val toilet = mapOf("amenity" to "toilets", "fee" to "no", "wheelchair" to "yes", "changing_table" to "yes")
        val tells = Tells.of(Purposes.of(CategoryTree.category("toilets")!!).tells, toilet, "nl", now)
        assertEquals(listOf(TellKey.FEE, TellKey.WHEELCHAIR, TellKey.CHANGING), tells.map { it.key })
        assertEquals("gratis", tells[0].text)
        assertEquals(TellTone.GOOD, tells[0].tone)
        // a charger leads with its power and its sockets
        val charger = mapOf("amenity" to "charging_station", "socket:type2_combo" to "2", "socket:type2_combo:output" to "150 kW", "socket:type2" to "1", "socket:type2:output" to "22 kW", "capacity" to "3")
        val c = Tells.of(Purposes.of(CategoryTree.category("charging")!!).tells, charger, "en", now)
        assertEquals("150 kW", c.first { it.key == TellKey.POWER }.text)
        assertEquals("CCS · Type 2", c.first { it.key == TellKey.SOCKETS }.text)
        assertEquals("3", c.first { it.key == TellKey.CAPACITY }.text)
        // every tell has a name of its own, for the hold that explains its symbol
        assertEquals(TellKey.entries.size, TellKey.entries.map { it.label("de") }.toSet().size)
        // what is not mapped is not said
        assertTrue(Tells.of(listOf(TellKey.FEE, TellKey.WHEELCHAIR), mapOf("amenity" to "toilets"), "en", now).isEmpty())
        // a tell checked long ago says so
        val old = Tells.of(listOf(TellKey.FEE), toilet + ("check_date:fee" to "2015-04-01"), "en", now).single()
        assertTrue(old.stale)
        assertEquals(2015, old.checked!!.year)
        assertFalse(Tells.of(listOf(TellKey.FEE), toilet + ("check_date:fee" to "2026-09-01"), "en", now).single().stale)
    }

    @Test
    fun aPostBoxSaysItsNextCollection() {
        val times = "Mo-Fr 09:00,17:00; Sa 12:00"
        // Saturday 14:30: Saturday's has gone, Monday's is next
        assertEquals(now.toLocalDate().plusDays(2).atTime(9, 0), Collections.next(times, now))
        assertEquals(now.toLocalDate().atTime(12, 0), Collections.next(times, now.withHour(10)))
        assertEquals(now.toLocalDate().plusDays(1).atTime(10, 0), Collections.next("Sa-Mo 10:00", now))
        assertNull(Collections.next("on request", now))
        val tell = Tells.of(listOf(TellKey.COLLECTION), mapOf("amenity" to "post_box", "collection_times" to times), "en", now.withHour(10)).single()
        assertEquals("12:00", tell.text)
    }

    @Test
    fun aLakeIsForSwimmingAsSurelyAsItsShoreSays() {
        assertEquals(Sure.NO, Certainty.swim(mapOf("natural" to "water", "water" to "lake", "swimming" to "no")))
        assertEquals(Sure.YES, Certainty.swim(mapOf("leisure" to "bathing_place")))
        assertEquals(Sure.MAYBE, Certainty.swim(mapOf("natural" to "water", "water" to "lake")))
        val swim = Intents.byId("swim")!!
        val box = BBox(4.870, 52.360, 4.890, 52.370)
        val lake = place(1, 52.365, 4.880, "natural" to "water", "water" to "lake", "name" to "Plas", bbox = box, type = OsmType.WAY)
        val other = place(2, 52.30, 4.80, "natural" to "water", "water" to "lake", "name" to "Ven", bbox = BBox(4.79, 52.295, 4.81, 52.305), type = OsmType.WAY)
        val far = place(3, 52.40, 4.95, "natural" to "water", "water" to "lake", "swimming" to "no", bbox = BBox(4.94, 52.395, 4.96, 52.405), type = OsmType.WAY)
        val spot = place(10, 52.3601, 4.8755, "leisure" to "bathing_place")
        val beach = place(11, 52.2945, 4.7995, "natural" to "beach")
        assertEquals(Sure.YES, Browse.certainty(swim, lake, listOf(spot, beach)))
        assertEquals(Sure.LIKELY, Browse.certainty(swim, other, listOf(spot, beach)))
        assertEquals(Sure.NO, Browse.certainty(swim, far, listOf(spot, beach)))
        // with swimming chosen on lakes, the results part by it, the spots telling for their lakes
        val res = Browse.results(CategoryTree.preset("natural/water/lake")!!, listOf(lake, other, far, spot, beach), here, saturday, aim = swim)
        assertEquals(listOf(Sure.YES, Sure.LIKELY, Sure.NO), res.parts.map { it.sure })
        assertEquals(lake.id, res.sure!!.hits.single().record.id)
        assertEquals(3, res.hits.size)
        assertEquals("swim spot", res.sure!!.hits.single().tells.first { it.key == TellKey.SWIM }.text)
        // without it, they are the lakes, unparted
        assertTrue(Browse.results(CategoryTree.preset("natural/water/lake")!!, listOf(lake, other, far), here, saturday).parts.isEmpty())
    }

    @Test
    fun officialBathingWatersTellForTheirLakes() {
        val swim = Intents.byId("swim")!!
        val lake = place(1, 52.365, 4.880, "natural" to "water", "water" to "lake", "name" to "Plas", bbox = BBox(4.870, 52.360, 4.890, 52.370), type = OsmType.WAY)
        val banned = place(2, 52.30, 4.80, "natural" to "water", "water" to "lake", bbox = BBox(4.79, 52.295, 4.81, 52.305), type = OsmType.WAY)
        val sites = listOf(
            BathingSite("NLBW_A", "Strand Plas", LatLon(52.3602, 4.8790), WaterKind.LAKE, WaterQuality.EXCELLENT, 2025),
            BathingSite("NLBW_B", "Ven Noord", LatLon(52.3049, 4.8000), WaterKind.LAKE, WaterQuality.GOOD, 2025, SwimAdvisory.BANNED),
            BathingSite("NLBW_C", "Zwemplek Elders", LatLon(52.3800, 4.9000), WaterKind.LAKE, WaterQuality.GOOD, 2025),
            BathingSite("NLBW_D", "Strand Zee", LatLon(52.3800, 4.6000), WaterKind.COASTAL, WaterQuality.GOOD, 2025),
        )
        val node = CategoryTree.preset("natural/water/lake")!!
        // without swimming chosen, a lake says what its bathing water is
        val plain = Browse.results(node, listOf(lake, banned), here, saturday, sites = sites)
        val plas = plain.hits.single { it.record.id == lake.id }
        assertEquals("NLBW_A", plas.site!!.id)
        assertEquals(listOf("bathing water", "excellent"), plas.tells.filter { it.key == TellKey.SWIM || it.key == TellKey.QUALITY }.map { it.text })
        assertEquals(2, plain.hits.size)
        // with it, the banned lake is not to swim in, a lake's bathing water at no lake found stands for itself, the sea's not
        val res = Browse.results(node, listOf(lake, banned), here, saturday, aim = swim, sites = sites)
        assertEquals(setOf(lake.id, null), res.sure!!.hits.map { it.record.id }.toSet())
        assertEquals("Zwemplek Elders", res.sure!!.hits.single { it.record.id == null }.record.name())
        val ven = res.hits.single { it.record.id == banned.id }
        assertEquals(Sure.NO, ven.sure)
        assertTrue(ven.tells.any { it.key == TellKey.ADVISORY && it.tone == TellTone.WARN })
        assertTrue(res.hits.none { it.site?.id == "NLBW_D" })
    }

    @Test
    fun resultsTakeTheOrderAsked() {
        val toilets = CategoryTree.category("toilets")!!
        val pool = listOf(
            place(1, 52.3701, 4.8801, "amenity" to "toilets", "fee" to "yes", "charge" to "0.50 EUR"),
            place(2, 52.3720, 4.8820, "amenity" to "toilets", "fee" to "no"),
            place(3, 52.3760, 4.8860, "amenity" to "toilets"),
            place(4, 52.3740, 4.8840, "amenity" to "toilets", "fee" to "yes"),
        )
        val nearest = Browse.results(toilets, pool, here, saturday, order = Order.NEAREST)
        assertEquals(Order.NEAREST, nearest.order)
        assertEquals(listOf(1L, 2L, 4L, 3L), nearest.hits.map { it.record.id!!.id })
        val cheapest = Browse.results(toilets, pool, here, saturday, order = Order.CHEAPEST)
        // free, half a euro, paid without a price, then the one that says nothing
        assertEquals(listOf(2L, 1L, 4L, 3L), cheapest.hits.map { it.record.id!!.id })
        // with free chosen, no row says "free"
        val free = Browse.results(toilets, pool, here, saturday, FacetSelection.NONE.toggle(Facets.PRICE, "free"))
        assertEquals(listOf(2L), free.hits.map { it.record.id!!.id })
        assertTrue(free.hits.single().tells.none { it.key == TellKey.FEE })
        // the kind's own order by default
        assertEquals(Purposes.of(toilets).order, Browse.results(toilets, pool, here, saturday).order)
    }

    @Test
    fun theOutlookWarnsOfTheStormThisAfternoon() {
        val start = Fixtures.saturday.withMinute(0)
        fun hour(h: Int, mm: Double = 0.0, sky: Sky = Sky.PARTLY_CLOUDY, gust: Double = 20.0, t: Double = 21.0) = WeatherHour(
            start.plusHours(h.toLong()).toInstant(), t, t, if (mm > 0) 80.0 else 5.0, mm, sky, null, 40.0, 10.0, 180.0, gust, 3.0, true, 1015.0, 20_000.0,
        )
        val day = WeatherDay(start.toLocalDate(), Sky.PARTLY_CLOUDY, null, 23.0, 14.0, 6.0, 80.0, 2.0, 20.0, 80.0, 180.0, 4.0, null, start.withHour(19).withMinute(5).toInstant(), 40_000.0)
        fun forecast(hours: List<WeatherHour>, quarters: List<Precipitation15> = emptyList()) =
            Forecast(here, 0.0, "Europe/Amsterdam", 7200, null, quarters, hours, listOf(day), start.toInstant())
        val stormy = forecast(listOf(hour(0), hour(1), hour(2, t = 24.0), hour(3, 4.0, Sky.THUNDERSTORM), hour(4, 1.0, Sky.RAIN), hour(5)))
        val o = Outlook.of(stormy, Fixtures.saturday)!!
        assertFalse(o.wetNow)
        assertEquals(start.plusHours(3).toLocalDateTime(), o.stormAt)
        assertEquals(WarningKind.STORM, o.warning()!!.kind)
        assertEquals(19, o.sunset!!.hour)
        assertEquals(24.0, o.warmest!!.tempC, 0.0)
        // until the storm it warns of nothing
        assertNull(o.warning(start.plusHours(2).toLocalDateTime()))
        // a storm in two hours spoils swimming now
        val july = Fixtures.saturday.withMonth(7)
        val m = Moment(july, WeatherNow(tempC = 26.0, sunny = true), outlook = o.copy(stormAt = july.toLocalDateTime().plusMinutes(90)))
        assertEquals(FitReason.STORM, Intents.byId("swim")!!.fit(m).second)
        // raining now: when it stops
        val wet = forecast(listOf(hour(0, 1.2, Sky.RAIN), hour(1, 0.8, Sky.RAIN), hour(2), hour(3)))
        val w = Outlook.of(wet, Fixtures.saturday)!!
        assertTrue(w.wetNow)
        assertEquals(WarningKind.DRY, w.warning()!!.kind)
        assertEquals(start.plusHours(2).toLocalDateTime(), w.dryAt)
        assertNotNull(Outlook.of(forecast(listOf(hour(0))), Fixtures.saturday))
        assertNull(Outlook.of(forecast(emptyList()), Fixtures.saturday))
    }

    @Test
    fun aHolidayRestsAsASundayDoes() {
        val tuesday = ZonedDateTime.of(2026, 12, 29, 11, 0, 0, 0, Fixtures.amsterdam)
        val work = Relevance.score("offices", Moment(tuesday)).first
        val holiday = Relevance.score("offices", Moment(tuesday, holiday = true)).first
        assertTrue(holiday < work)
    }
}
