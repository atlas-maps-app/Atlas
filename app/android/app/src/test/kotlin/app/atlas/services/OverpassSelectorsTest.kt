package app.atlas.services

import app.atlas.places.Categories
import app.atlas.places.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassSelectorsTest {
    @Test
    fun orBecomesAUnionAndAndIsDistributed() {
        val f = TagFilter.parse("amenity=cafe and (wifi=yes or internet_access=wlan)")
        assertEquals(listOf("[\"amenity\"=\"cafe\"][\"wifi\"=\"yes\"]", "[\"amenity\"=\"cafe\"][\"internet_access\"=\"wlan\"]"), OverpassSelectors.of(f))
    }

    @Test
    fun numbersBecomeHasKeyAndUnsayableNegationsAreLeftToTheClient() {
        val f = TagFilter.parse("amenity=charging_station and \"socket:type2_combo:output\">=150kW")
        val s = OverpassSelectors.of(f)!!
        assertEquals(1, s.size)
        assertTrue(s[0], s[0].contains("socket:type2_combo:output") && s[0].contains("charging_station"))
        assertEquals(listOf("[\"leisure\"=\"pitch\"]"), OverpassSelectors.of(TagFilter.parse("leisure=pitch and not (sport=fitness or sport=yoga)")))
    }

    @Test
    fun aBareNegationIsRefusedRatherThanFetchingEverything() {
        assertNull(OverpassSelectors.of(TagFilter.parse("not (shop=books or shop=art)")))
    }

    @Test
    fun everyHomeCategoryCompiles() {
        for (id in listOf("eat", "groceries", "shops", "stay", "charge", "nature", "fun", "water", "toilets", "drinking_water", "food_shops", "other")) {
            val c = Categories.byId(id)!!
            val s = OverpassSelectors.of(LayeredPlaceFinder.filterOf(c))
            assertNotNull("$id has no selectors", s)
            assertTrue("$id: $s", s!!.all { it.startsWith("[") })
        }
    }

    /**
     * A shop that takes the post or dispenses medicine is found where Post or Health is asked for, near and online (the
     * app maintainer, 18 November 2026, batch 4 A6), though its kind stays the shop's.
     */
    @Test
    fun aShopThatTakesThePostIsAskedForWithThePost() {
        val partner = mapOf("shop" to "convenience", "post_office" to "post_partner", "name" to "Spar")
        val chemist = mapOf("shop" to "chemist", "healthcare" to "pharmacy", "name" to "Boots")
        assertTrue(LayeredPlaceFinder.filterOf(Categories.byId("post")!!).matches(partner))
        assertTrue(LayeredPlaceFinder.filterOf(Categories.byId("health")!!).matches(chemist))
        assertEquals("food_shops", Categories.of(partner)?.id)
        val s = OverpassSelectors.of(LayeredPlaceFinder.filterOf(Categories.byId("post")!!))!!
        assertTrue("$s", s.any { "\"shop\"" in it && "\"post_office\"" in it })
    }
}
