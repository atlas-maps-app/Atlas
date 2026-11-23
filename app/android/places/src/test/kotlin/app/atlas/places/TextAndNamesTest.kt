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
        // the place's own addr:country before the country of the map there
        assertEquals("Prinsengracht 263, 1016 GV Amsterdam", Localise.address(nl, "FR"))
        val de = mapOf("addr:street" to "Unter den Linden", "addr:housenumber" to "77", "addr:postcode" to "10117", "addr:city" to "Berlin")
        assertEquals(listOf("Unter den Linden 77", "10117 Berlin"), Localise.addressLines(de, "DE"))
        val fr = mapOf("addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:postcode" to "75004", "addr:city" to "Paris")
        assertEquals("12 Rue de Rivoli, 75004 Paris", Localise.address(fr, "FR"))
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10", "addr:postcode" to "SW1A 2AA", "addr:city" to "London")
        assertEquals("10 Downing Street, London SW1A 2AA", Localise.address(gb, "gb"))
        val us = mapOf("addr:street" to "Pennsylvania Avenue NW", "addr:housenumber" to "1600", "addr:postcode" to "20500", "addr:city" to "Washington", "addr:state" to "DC")
        assertEquals(listOf("1600 Pennsylvania Avenue NW", "Washington, DC 20500"), Localise.addressLines(us, "US"))
        assertEquals("Dam, Amsterdam", Localise.address(mapOf("addr:street" to "Dam", "addr:city" to "Amsterdam"), "NL"))
        assertEquals("Hoeve 3, 1234 AB", Localise.address(mapOf("addr:place" to "Hoeve", "addr:housenumber" to "3", "addr:postcode" to "1234 AB"), null))
        assertNull(Localise.address(mapOf("name" to "x"), "NL"))
        assertEquals(AddressStyle.BRITISH, Localise.styleFor("ie"))
        assertEquals(AddressStyle.STREET_FIRST, Localise.styleFor(null))
    }

    /**
     * A unit stands where its country writes it: after the street in America (the reviewer, 17 November, finding 11: it
     * stood between the number and the street, "1600 Suite 200 Pennsylvania Avenue NW"); before the street line in
     * Britain and France, "Flat 2, 10 Downing Street" (the app maintainer, 18 November 2026, batch 4 B23); where the
     * street comes first, after the number, as before.
     */
    @Test
    fun aUnitStandsWhereItsCountryWritesIt() {
        val us = mapOf("addr:street" to "Pennsylvania Avenue NW", "addr:housenumber" to "1600", "addr:unit" to "Suite 200", "addr:postcode" to "20500",
            "addr:city" to "Washington", "addr:state" to "DC")
        assertEquals(listOf("1600 Pennsylvania Avenue NW, Suite 200", "Washington, DC 20500"), Localise.addressLines(us, "US"))
        val fr = mapOf("addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:unit" to "Bâtiment B", "addr:postcode" to "75004", "addr:city" to "Paris")
        assertEquals("Bâtiment B, 12 Rue de Rivoli, 75004 Paris", Localise.address(fr, "FR"))
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10", "addr:unit" to "Flat 2", "addr:city" to "London")
        assertEquals("Flat 2, 10 Downing Street, London", Localise.address(gb, "GB"))
        assertEquals(listOf("Flat 2, Downing Street", "London"), Localise.addressLines(gb - "addr:housenumber", "GB"))
        val nl = mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263", "addr:unit" to "A", "addr:city" to "Amsterdam")
        assertEquals("Prinsengracht 263 A, Amsterdam", Localise.address(nl, "NL"))
    }

    /**
     * A unit of one letter joins its number in Britain, Ireland and the Crown dependencies: "10A Downing Street", not
     * "A, 10 Downing Street" (the app maintainer, 18 November 2026, batch 5 B11). In France, Luxembourg and Monaco such
     * a unit is mostly a building and "12B Rue de Rivoli" reads as another number: it stays before the street, as batch
     * 4 left it (19 November 2026, batch 6 B14). A longer unit stays before the street, and where the street comes
     * first after the number, as before.
     */
    @Test
    fun aUnitOfOneLetterJoinsItsNumber() {
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10", "addr:unit" to "A", "addr:city" to "London")
        for (c in listOf("GB", "IE", "IM", "JE", "GG")) assertEquals(c, "10A Downing Street, London", Localise.address(gb, c))
        val fr = mapOf("addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:unit" to "B", "addr:postcode" to "75004", "addr:city" to "Paris")
        for (c in listOf("FR", "LU", "MC")) assertEquals(c, "B, 12 Rue de Rivoli, 75004 Paris", Localise.address(fr, c))
        assertEquals("Flat 2, 10 Downing Street, London", Localise.address(gb + ("addr:unit" to "Flat 2"), "GB"))
        assertEquals("Downing Street 10 A, London", Localise.address(gb, "NL"))
    }

    /** A digit is no letter: a unit "2" at number 10 stays before the street, never "102 Downing Street" (batch 6). */
    @Test
    fun aUnitOfOneDigitStaysApart() {
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10", "addr:unit" to "2", "addr:city" to "London")
        assertEquals("2, 10 Downing Street, London", Localise.address(gb, "GB"))
        assertEquals("2, 10 Downing Street, London", Localise.address(gb, "IE"))
    }

    /**
     * A unit joins only a number of digits alone: with "10A" or "10-12" it would read as another number, "10AB" or
     * "10-12A", so it stays before the street line there, as before (batch 5 B11); with no number there is nothing to
     * join.
     */
    @Test
    fun aUnitJoinsOnlyANumberOfDigitsAlone() {
        val gb = mapOf("addr:street" to "Downing Street", "addr:housenumber" to "10A", "addr:unit" to "B", "addr:city" to "London")
        assertEquals("B, 10A Downing Street, London", Localise.address(gb, "GB"))
        assertEquals("A, 10-12 Downing Street, London", Localise.address(gb + mapOf("addr:housenumber" to "10-12", "addr:unit" to "A"), "GB"))
        assertEquals("A, Downing Street, London", Localise.address(gb - "addr:housenumber" + ("addr:unit" to "A"), "GB"))
        assertEquals("Flat 2, 10 Downing Street, London", Localise.address(gb + mapOf("addr:housenumber" to "10", "addr:unit" to "Flat 2"), "GB"))
    }

    @Test
    fun compatibilityFormsAndLanguageSubtags() {
        assertEquals("cafe", Text.fold("ｃａｆｅ"))
        assertEquals("fish", Text.fold("ﬁsh"))
        assertEquals("istanbul", Text.fold("İstanbul"))
        assertTrue(Presets.search("ｃａｆｅ").first().preset.id == "amenity/cafe")
        // a script subtag is tried before the bare language
        val t = mapOf("name" to "台北101", "name:zh-Hant" to "臺北101", "name:zh" to "台北101大楼")
        assertEquals("臺北101", Localise.name(t, "zh-Hant-TW"))
        assertEquals("台北101大楼", Localise.name(t, "zh-CN"))
        assertEquals("台北101大楼", Localise.name(t, "ZH"))
    }
}
