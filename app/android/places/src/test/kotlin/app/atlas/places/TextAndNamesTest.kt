package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndNamesTest {
    @Test
    fun folding() {
        assertEquals("cafe bar", Text.fold("Café-Bar"))
        assertEquals("strasse", Text.fold("Straße"))
        assertEquals("smorrebrod", Text.fold("Smørrebrød"))
        assertEquals("lodz", Text.fold("Łódź"))
        assertEquals("mcdonalds", Text.fold("McDonald’s"))
        assertEquals("", Text.fold(""))
        assertEquals("東京", Text.fold("東京"))
    }

    @Test
    fun editDistance() {
        assertEquals(0, Text.distance("cafe", "cafe"))
        assertEquals(1, Text.distance("cafe", "cafes"))
        assertEquals(1, Text.distance("restuarant", "restaurant")) // a transposition counts once
        assertEquals(2, Text.distance("kitten", "sitten", 5) + 1)
        assertEquals(3, Text.distance("abc", "xyz", 5))
        assertEquals(2, Text.distance("abcdef", "uvwxyz", 1))
        assertEquals(0, Text.typosAllowed(3))
        assertEquals(1, Text.typosAllowed(5))
        assertEquals(2, Text.typosAllowed(9))
    }

    @Test
    fun units() {
        assertEquals(Measure(4.2, Dimension.LENGTH), Units.parse("4.2 m"))
        assertEquals(Measure(3.5, Dimension.MASS), Units.parse("3.5t"))
        assertEquals(Measure(150.0, Dimension.POWER), Units.parse("150 kW"))
        assertEquals(2000.0, Units.parse("2 MW")!!.value, 1e-9)
        assertEquals(3.81, Units.parse("12'6\"")!!.value, 1e-9)
        assertEquals(48.28032, Units.parse("30 mph")!!.value, 1e-6)
        assertEquals(Measure(7.0, Dimension.NONE), Units.parse("7"))
        assertEquals(4.5, Units.parse("4,5 m")!!.value, 1e-9)
        assertNull(Units.parse("tall"))
        assertNull(Units.parse("4 parsecs"))
        assertEquals(listOf(22.0, 11.0), Units.parseAll("22 kW;11 kW").map { it.value })
        assertTrue(Units.isUnit("KW"))
        assertEquals(Dimension.SPEED, Units.dimensionOf("knots"))
    }

    @Test
    fun cuisine() {
        assertEquals(listOf("italian", "pizza"), Cuisine.values(mapOf("cuisine" to "Italian; pizza ;")))
        assertEquals("pinhead-sushi", Cuisine.symbol("japanese"))
        assertEquals(Symbols.DEFAULT_FOOD, Cuisine.symbol("martian"))
        assertEquals("IT", Cuisine.country("italian"))
        assertNull(Cuisine.country("pizza"))
        assertEquals("Italian", Cuisine.name("italian"))
        assertEquals("Italiaans", Cuisine.name("italian", "nl"))
        assertEquals("Italienisch", Cuisine.name("italian", "de"))
        assertEquals("Martian cooking", Cuisine.name("martian_cooking"))
        assertTrue(Cuisine.known.size > 140)
    }

    @Test
    fun names() {
        val t = mapOf("name" to "Westerkerk", "name:en" to "Western Church", "name:pt" to "Igreja Ocidental", "brand" to "X", "ref" to "7")
        assertEquals("Western Church", Localise.name(t, "en"))
        assertEquals("Westerkerk", Localise.name(t, "nl"))
        assertEquals("Igreja Ocidental", Localise.name(t, "pt-BR"))
        assertEquals("Westerkerk", Localise.name(t))
        assertEquals("Albert Heijn", Localise.name(mapOf("brand" to "Albert Heijn", "shop" to "supermarket")))
        assertEquals("Albert Heijn", Localise.name(mapOf("brand" to "AH", "brand:nl" to "Albert Heijn"), "nl"))
        assertEquals("GVB", Localise.name(mapOf("operator" to "GVB")))
        assertEquals("A10", Localise.name(mapOf("ref" to "A10")))
        assertNull(Localise.name(mapOf("amenity" to "bench")))
        assertNull(Localise.name(mapOf("name" to "  ")))
    }

    @Test
    fun addressesByCountry() {
        val nl = mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:postcode" to "1016 GV", "addr:city" to "Amsterdam", "addr:country" to "NL")
        assertEquals("Prinsengracht 263, 1016 GV Amsterdam", Localise.address(nl))
        val de = mapOf("addr:street" to "Unter den Linden", "addr:housenumber" to "77", "addr:postcode" to "10117", "addr:city" to "Berlin")
        assertEquals(listOf("Unter den Linden 77", "10117 Berlin"), Localise.addressLines(de, "DE"))
        val fr = mapOf("addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:postcode" to "75004", "addr:city" to "Paris")
        assertEquals("12 Rue de Rivoli, 75004 Paris", Localise.address(fr, "FR"))
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10", "addr:postcode" to "SW1A 2AA", "addr:city" to "London")
        assertEquals("10 Downing Street, London SW1A 2AA", Localise.address(gb, "gb"))
        val us = mapOf("addr:street" to "Pennsylvania Avenue NW", "addr:housenumber" to "1600", "addr:postcode" to "20500", "addr:city" to "Washington", "addr:state" to "DC")
        assertEquals(listOf("1600 Pennsylvania Avenue NW", "Washington, DC 20500"), Localise.addressLines(us, "US"))
        assertEquals("Dam, Amsterdam", Localise.address(mapOf("addr:street" to "Dam", "addr:city" to "Amsterdam")))
        assertEquals("Hoeve 3, 1234 AB", Localise.address(mapOf("addr:place" to "Hoeve", "addr:housenumber" to "3", "addr:postcode" to "1234 AB")))
        assertNull(Localise.address(mapOf("name" to "x")))
        assertEquals(AddressStyle.BRITISH, Localise.styleFor("ie"))
        assertEquals(AddressStyle.STREET_FIRST, Localise.styleFor(null))
    }
}
