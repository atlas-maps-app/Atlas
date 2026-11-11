package app.atlas.ui.eat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which house a place wears, from its `cuisine` tag as mapped, and how the houses sit in the carte's families. */
class CuisineHouseTest {
    private fun of(cuisine: String) = CuisineHouse.of(cuisine.split(';').map { CuisineTaxonomy.canonical(it) }.distinct())

    @Test
    fun aPlaceWearsTheHouseOfItsFirstCuisineThatHasOne() {
        assertEquals(CuisineHouse.ITALIANA, of("pizza"))
        assertEquals(CuisineHouse.ITALIANA, of("italian_pizza"))
        assertEquals(CuisineHouse.JAPANESE, of("asian;sushi;thai"))
        assertEquals(CuisineHouse.THAI, of("thai;curry"))
        assertEquals(CuisineHouse.KOREAN, of("korean;barbecue"))
        assertEquals(CuisineHouse.DINER, of("chicken;burger"))
        assertEquals(CuisineHouse.MEXICANA, of("tacos"))
        assertEquals(CuisineHouse.CHINESE, of("hot_pot"))
        assertEquals(CuisineHouse.BISTROT, of("Crepe"))
    }

    @Test
    fun aCountrysOwnKitchenWithNoHouseComesFirst() {
        // Oude Wester, El Torado Grill and Casa del Toro, around the Westerkerk: a Dutch café and two Argentinian grills
        assertNull(of("dutch;regional;pizza;burger;sandwich;pasta;pancake"))
        assertNull(of("argentinian;steak_house;burger"))
        assertNull(of("argentinian;italian"))
    }

    @Test
    fun aPlaceInNoHouseKeepsItsPlate() {
        assertNull(of("fusion"))
        assertNull(of("seafood;sandwich"))
        assertNull(CuisineHouse.of(emptyList()))
    }

    @Test
    fun everyHouseSitsInItsFamilysRegionOfTheCarte() {
        for (h in CuisineHouse.entries) for (c in h.cuisines) assertEquals("$c of $h", h.family, CuisineFamily.of(c))
    }

    @Test
    fun noCuisineLivesInTwoHouses() {
        val all = CuisineHouse.entries.flatMap { it.cuisines }
        assertEquals(all.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }
}
