package app.atlas.places

import app.atlas.geo.LatLon
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FactsTest {
    private val monday = LocalDateTime.of(2026, 10, 12, 20, 0)
    private fun facts(vararg kv: Pair<String, String>, lang: String = "en", at: LocalDateTime = monday) =
        Facts.of(mapOf(*kv), lang, at)

    private fun List<Fact>.of(kind: FactKind) = filter { it.kind == kind }
    private fun List<Fact>.one(kind: FactKind) = single { it.kind == kind }

    @Test
    fun aRestaurant() {
        val f = facts(
            "amenity" to "restaurant", "name" to "Osteria Lume", "cuisine" to "italian;pizza", "opening_hours" to "Mo-Su 17:00-23:00",
            "outdoor_seating" to "yes", "wheelchair" to "limited", "diet:vegetarian" to "yes", "diet:vegan" to "only",
            "internet_access" to "wlan", "internet_access:fee" to "no", "payment:cash" to "no", "payment:visa" to "yes",
            "reservation" to "required", "takeaway" to "yes", "phone" to "+31 20 123 4567", "website" to "https://example.org",
        )
        val hours = f.one(FactKind.OPENING_HOURS)
        assertEquals(FactState.YES, hours.state)
        assertEquals("23:00", hours.value)
        assertEquals(FactKind.OPENING_HOURS, f.first().kind)
        val wc = f.one(FactKind.WHEELCHAIR)
        assertEquals(FactState.LIMITED, wc.state)
        assertEquals("Wheelchair Access", wc.label)
        assertEquals("Limited", wc.valueLabel)
        assertEquals("ui-wheelchair", wc.symbol)
        assertEquals(listOf("italian", "pizza"), f.of(FactKind.CUISINE).map { it.value })
        assertEquals("pinhead-pizza_slice", f.of(FactKind.CUISINE)[1].symbol)
        assertEquals("Italian", f.of(FactKind.CUISINE)[0].label)
        val diets = f.of(FactKind.DIET).associateBy { it.value }
        assertEquals(FactState.ONLY, diets["vegan"]!!.state)
        assertEquals("ui-vegan", diets["vegan"]!!.symbol)
        assertEquals(FactState.YES, diets["vegetarian"]!!.state)
        val wifi = f.one(FactKind.INTERNET)
        assertEquals("free", wifi.value)
        assertEquals("ui-wifi", wifi.symbol)
        val pay = f.of(FactKind.PAYMENT).associateBy { it.value }
        assertEquals(FactState.NO, pay["cash"]!!.state)
        assertEquals(FactState.YES, pay["cards"]!!.state)
        assertEquals(FactState.ONLY, f.one(FactKind.RESERVATION).state)
        assertEquals(FactState.YES, f.one(FactKind.TAKEAWAY).state)
        assertEquals("+31 20 123 4567", f.one(FactKind.PHONE).value)
        assertTrue(f.all { it.mapped })
        // sorted by priority
        assertEquals(f.map { it.priority }.sortedDescending(), f.map { it.priority })
    }

    @Test
    fun unmappedButImportantFactsAreAsked() {
        val f = facts("amenity" to "restaurant", "name" to "Nowhere")
        val asked = f.filter { !it.mapped }
        assertTrue(asked.all { it.state == FactState.UNKNOWN })
        val keys = asked.map { it.key }.toSet()
        assertTrue(keys.toString(), keys.containsAll(listOf("wheelchair", "outdoor_seating", "opening_hours", "cuisine", "diet:vegetarian")))
        // mapped wheelchair is not asked again
        assertFalse(facts("amenity" to "restaurant", "wheelchair" to "yes").any { !it.mapped && it.kind == FactKind.WHEELCHAIR })
        // a bench has nothing to ask about wheelchairs
        assertTrue(facts("amenity" to "bench").none { it.kind == FactKind.WHEELCHAIR })
        // a footway asks for its surface but not its access
        val path = Facts.of(mapOf("highway" to "footway"), "en", monday, geometry = Geometry.LINE)
        assertTrue(path.any { !it.mapped && it.key == "surface" })
        assertTrue(path.none { it.kind == FactKind.ACCESS })
    }

    @Test
    fun aToilet() {
        val f = facts("amenity" to "toilets", "fee" to "no", "wheelchair" to "yes", "changing_table" to "yes", "access" to "customers", "opening_hours" to "Mo-Su 08:00-22:00")
        assertEquals(FactState.NO, f.one(FactKind.FEE).state)
        assertEquals(FactState.YES, f.one(FactKind.CHANGING_TABLE).state)
        val access = f.one(FactKind.ACCESS)
        assertEquals(FactState.LIMITED, access.state)
        assertEquals("customers", access.value)
        assertEquals("Customers", access.valueLabel)
        assertTrue(f.none { it.kind == FactKind.TOILETS })
        assertEquals("22:00", f.one(FactKind.OPENING_HOURS).value)
    }

    @Test
    fun aChargerAndAFuelStation() {
        val c = facts(
            "amenity" to "charging_station", "capacity" to "4", "socket:type2" to "2", "socket:type2:output" to "22 kW",
            "socket:type2_combo" to "2", "socket:type2_combo:output" to "150 kW;50 kW", "socket:chademo" to "no", "fee" to "yes",
            "charge" to "0.59 EUR/kWh",
        )
        val sockets = c.of(FactKind.SOCKET).associateBy { it.label }
        assertEquals("2 × 22 kW", sockets["Type 2"]!!.value)
        assertEquals("2 × 150 kW", sockets["CCS"]!!.value)
        assertNull(sockets["CHAdeMO"])
        assertEquals("ui-plug", c.one(FactKind.CAPACITY).symbol)
        assertEquals("0.59 EUR/kWh", c.one(FactKind.CHARGE).value)
        assertEquals(FactState.YES, c.one(FactKind.FEE).state)
        val fuel = facts("amenity" to "fuel", "fuel:diesel" to "yes", "fuel:octane_95" to "yes", "fuel:lpg" to "no")
        assertEquals(setOf("diesel", "octane_95"), fuel.of(FactKind.FUEL).map { it.value }.toSet())
        assertEquals("Diesel", fuel.of(FactKind.FUEL).first { it.value == "diesel" }.label)
    }

    @Test
    fun recyclingSportsAndPaths() {
        val r = facts("amenity" to "recycling", "recycling:glass_bottles" to "yes", "recycling:paper" to "yes", "recycling:clothes" to "no")
        assertEquals(setOf("glass_bottles", "paper"), r.of(FactKind.RECYCLING).map { it.value }.toSet())
        val pitch = facts("leisure" to "pitch", "sport" to "soccer;basketball", "surface" to "artificial_turf", "lit" to "yes")
        assertEquals("maki-soccer", pitch.of(FactKind.SPORT).first().symbol)
        assertEquals(2, pitch.of(FactKind.SPORT).size)
        assertEquals(FactState.YES, pitch.one(FactKind.LIT).state)
        val path = Facts.of(mapOf("highway" to "path", "surface" to "gravel", "lit" to "no", "incline" to "8%"), "en", monday, geometry = Geometry.LINE)
        assertEquals(FactState.LIMITED, path.one(FactKind.SURFACE).state)
        assertEquals(FactState.NO, path.one(FactKind.LIT).state)
        assertEquals("8%", path.one(FactKind.INCLINE).value)
        // surface on a shop is not a path fact
        assertTrue(facts("shop" to "bakery", "surface" to "asphalt").none { it.kind == FactKind.SURFACE })
    }

    @Test
    fun moreKinds() {
        val f = facts(
            "tourism" to "hotel", "stars" to "4", "rooms" to "120", "smoking" to "outside", "dog" to "leashed", "air_conditioning" to "yes",
            "level" to "1", "wikidata" to "Q1", "wikipedia" to "nl:Hotel", "self_service" to "no", "toilets" to "yes", "indoor_seating" to "yes",
            "organic" to "only", "second_hand" to "yes", "drinking_water:refill" to "yes", "delivery" to "no", "drive_through" to "yes",
        )
        assertEquals("4", f.one(FactKind.STARS).value)
        assertEquals("120", f.one(FactKind.ROOMS).value)
        assertEquals(FactState.LIMITED, f.one(FactKind.SMOKING).state)
        assertEquals(FactState.LIMITED, f.one(FactKind.DOG).state)
        assertEquals("L1", f.one(FactKind.LEVEL).value)
        assertEquals(FactState.ONLY, f.one(FactKind.ORGANIC).state)
        assertEquals(FactState.NO, f.one(FactKind.DELIVERY).state)
        assertEquals(FactState.NO, f.one(FactKind.SELF_SERVICE).state)
        for (k in listOf(FactKind.AIR_CONDITIONING, FactKind.WIKIDATA, FactKind.WIKIPEDIA, FactKind.TOILETS, FactKind.INDOOR_SEATING, FactKind.SECOND_HAND, FactKind.BOTTLE_REFILL, FactKind.DRIVE_THROUGH)) {
            assertEquals(k.name, FactState.YES, f.one(k).state)
        }
        assertEquals("ui-smoke-off", facts("amenity" to "cafe", "smoking" to "no").one(FactKind.SMOKING).symbol)
        assertEquals("ui-wifi-off", facts("amenity" to "cafe", "internet_access" to "no").one(FactKind.INTERNET).symbol)
    }

    @Test
    fun hoursStatesAndLanguages() {
        val closed = facts("shop" to "bakery", "opening_hours" to "Mo-Sa 07:00-18:00")
        assertEquals(FactState.NO, closed.one(FactKind.OPENING_HOURS).state)
        assertEquals("07:00", closed.one(FactKind.OPENING_HOURS).value)
        assertEquals(FactState.UNKNOWN, facts("shop" to "bakery", "opening_hours" to "whenever").one(FactKind.OPENING_HOURS).state)
        assertEquals("24/7", facts("amenity" to "atm", "opening_hours" to "24/7").one(FactKind.OPENING_HOURS).value)
        // labels follow the language
        val nl = facts("amenity" to "restaurant", "wheelchair" to "yes", "diet:vegan" to "yes", "cuisine" to "italian", lang = "nl")
        assertEquals("Italiaans", nl.one(FactKind.CUISINE).label)
        assertEquals("Veganistisch", nl.one(FactKind.DIET).label)
        assertEquals("Rolstoeltoegankelijkheid", nl.one(FactKind.WHEELCHAIR).label)
        assertNotNull(nl.one(FactKind.WHEELCHAIR).valueLabel)
        // a place's own country gives the holidays: Christmas closes a "PH off" bakery
        val bakery = Place(null, LatLon(52.37, 4.88), mapOf("shop" to "bakery", "addr:country" to "NL", "opening_hours" to "Mo-Sa 07:00-18:00; PH off"))
        assertEquals(FactState.NO, Facts.of(bakery, "en", LocalDateTime.of(2026, 12, 25, 10, 0)).one(FactKind.OPENING_HOURS).state)
        assertEquals(FactState.YES, Facts.of(bakery, "en", LocalDateTime.of(2026, 12, 24, 10, 0)).one(FactKind.OPENING_HOURS).state)
    }

    @Test
    fun stateWords() {
        assertEquals(FactState.YES, Facts.stateOf("designated"))
        assertEquals(FactState.NO, Facts.stateOf("No"))
        assertEquals(FactState.LIMITED, Facts.stateOf("limited"))
        assertEquals(FactState.ONLY, Facts.stateOf("only"))
        assertNull(Facts.stateOf("wlan"))
        assertNull(Facts.stateOf(null))
    }

    @Test
    fun hoursSayTheDayWhenTheChangeIsNotTodayOrTomorrow() {
        val tags = mapOf("shop" to "bakery", "opening_hours" to "Mo-Fr 07:00-18:00")
        // Saturday noon: it opens on Monday, not at 07:00 tomorrow
        assertEquals("Mon 07:00", Facts.of(tags, "en", LocalDateTime.of(2026, 10, 17, 12, 0)).one(FactKind.OPENING_HOURS).value)
        val nl = Facts.of(tags, "nl", LocalDateTime.of(2026, 10, 17, 12, 0)).one(FactKind.OPENING_HOURS).value!!
        assertTrue(nl, nl.endsWith(" 07:00") && !nl.startsWith("Mon"))
        // Sunday noon: tomorrow, so the time alone
        assertEquals("07:00", Facts.of(tags, "en", LocalDateTime.of(2026, 10, 18, 12, 0)).one(FactKind.OPENING_HOURS).value)
        // hours that do not say give their reason
        val appt = Facts.of(mapOf("office" to "lawyer", "opening_hours" to "Mo-Fr 08:00-12:00 || \"by appointment\""), "en", monday.withHour(14))
        assertEquals(FactState.UNKNOWN, appt.one(FactKind.OPENING_HOURS).state)
        assertEquals("by appointment", appt.one(FactKind.OPENING_HOURS).value)
    }
}
