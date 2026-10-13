package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    private fun cat(vararg kv: Pair<String, String>) = Categories.of(mapOf(*kv))?.id

    @Test
    fun theMapHomeRow() {
        assertEquals(listOf("eat", "groceries", "shops", "stay", "charge", "nature", "fun", "water"), Categories.groups().map { it.id })
        val eat = Categories.byId("eat")!!
        assertTrue(eat.isGroup)
        assertEquals("Essen & Trinken", eat.name("de"))
        assertEquals("Eten & drinken", eat.name("nl-BE"))
        assertEquals("Eat & drink", eat.name("xx"))
        for (g in Categories.groups()) for (lang in Langs.bundled) assertTrue("${g.id} $lang", g.name(lang).isNotBlank())
        assertTrue(Categories.children("eat").map { it.id }.containsAll(listOf("restaurants", "drinks")))
    }

    @Test
    fun firstMatchWinsInThePrototypesOrder() {
        assertEquals("restaurants", cat("amenity" to "restaurant"))
        assertEquals("eat", Categories.groupOf(mapOf("amenity" to "cafe"))?.id)
        assertEquals("sports", cat("leisure" to "sports_centre", "sport" to "swimming"))
        assertEquals("swim", cat("leisure" to "swimming_pool"))
        assertNull(Categories.of(mapOf("leisure" to "swimming_pool", "access" to "private"))?.takeIf { it.id == "swim" })
        assertEquals("health", cat("amenity" to "pharmacy"))
        assertEquals("shopping", cat("shop" to "chemist"))
        assertEquals("drinks", cat("shop" to "alcohol"))
        assertEquals("food_shops", cat("shop" to "bakery"))
        assertEquals("groceries", Categories.groupOf(mapOf("shop" to "supermarket"))?.id)
        assertEquals("museums", cat("tourism" to "museum"))
        assertEquals("charging", cat("amenity" to "charging_station"))
        assertEquals("charge", Categories.groupOf(mapOf("amenity" to "charging_station"))?.id)
        assertEquals("boating", cat("leisure" to "marina"))
        assertEquals("nature_reserve", cat("boundary" to "protected_area", "protect_class" to "2"))
        assertEquals("heritage", cat("boundary" to "protected_area", "protect_class" to "22"))
        assertEquals("gym", cat("sport" to "fitness"))
        assertEquals("shopping", cat("sport" to "fitness", "shop" to "sports"))
        assertEquals("transit", cat("railway" to "tram_stop"))
        assertEquals("other", cat("amenity" to "fountain"))
        assertNull(cat("building" to "yes"))
    }

    @Test
    fun notPlaces() {
        assertNull(cat("amenity" to "parking_space"))
        assertNull(cat("disused:shop" to "yes", "amenity" to "cafe"))
        assertNull(cat("shop" to "vacant"))
        assertFalse(Categories.isPlace(emptyMap()))
        assertTrue(Categories.isPlace(mapOf("amenity" to "bench")))
    }

    @Test
    fun dataIsComplete() {
        val leaves = Categories.leaves()
        assertTrue(leaves.size >= 50)
        assertEquals(Categories.all().size, Categories.all().map { it.id }.toSet().size)
        for (c in Categories.all()) {
            assertTrue("${c.id} symbol ${c.symbol}", Symbols.has(c.symbol))
            assertTrue(c.id, c.colour ushr 24 == 0xFF)
            assertEquals(c.id, !c.isGroup, c.rule != null)
            assertTrue(c.id, c.name("en").isNotBlank() && c.name("fr").isNotBlank())
        }
        assertEquals(listOf("food_shops", "shopping"), Categories.byLegacyId("c19").map { it.id })
        assertNotNull(Categories.byId("defibrillator"))
    }

    @Test
    fun theWesterkerkExtract() {
        val counts = Fixtures.pois("ams").groupingBy { Categories.of(it.place.tags)?.id ?: "-" }.eachCount()
        println("categories around the Westerkerk: " + counts.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        assertTrue((counts["shopping"] ?: 0) > 150)
        assertTrue((counts["restaurants"] ?: 0) > 80)
    }
}
