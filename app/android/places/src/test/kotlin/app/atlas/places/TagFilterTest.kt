package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TagFilterTest {
    private val cafe = mapOf("amenity" to "cafe", "outdoor_seating" to "yes", "cuisine" to "coffee_shop;cake", "access" to "yes")

    @Test
    fun theBriefsExample() {
        val f = TagFilter.parse("amenity=cafe and outdoor_seating=yes and (cuisine~coffee|cake) and not access=private")
        assertTrue(f.matches(cafe))
        assertFalse(f.matches(cafe + ("access" to "private")))
        assertFalse(f.matches(cafe + ("cuisine" to "italian")))
        assertFalse(f.matches(cafe - "outdoor_seating"))
        assertEquals(setOf("amenity", "outdoor_seating", "cuisine", "access"), f.keys())
    }

    @Test
    fun termsOfEveryKind() {
        assertTrue(TagFilter.parse("amenity").matches(cafe))
        assertFalse(TagFilter.parse("shop").matches(cafe))
        assertTrue(TagFilter.parse("!shop").matches(cafe))
        assertTrue(TagFilter.parse("amenity=*").matches(cafe))
        assertTrue(TagFilter.parse("shop!=*").matches(cafe))
        assertTrue(TagFilter.parse("amenity!=bar").matches(cafe))
        assertTrue("!= holds when the key is missing", TagFilter.parse("shop!=bakery").matches(cafe))
        assertTrue(TagFilter.parse("amenity~^ca").matches(cafe))
        assertTrue("regex ignores case", TagFilter.parse("amenity~CAFE").matches(cafe))
        assertTrue(TagFilter.parse("amenity!~bar|pub").matches(cafe))
        assertTrue(TagFilter.parse("~^out~yes").matches(cafe))
        assertFalse(TagFilter.parse("~^diet:").matches(cafe))
        assertTrue(TagFilter.parse("\"name:nl\"").matches(mapOf("name:nl" to "x")))
        assertTrue(TagFilter.parse("name='Café de Jaren'").matches(mapOf("name" to "Café de Jaren")))
        assertTrue(TagFilter.parse("*").matches(emptyMap()))
    }

    @Test
    fun semicolonListsMatchEachPart() {
        assertTrue(TagFilter.parse("cuisine=cake").matches(cafe))
        assertTrue(TagFilter.parse("cuisine=coffee_shop").matches(cafe))
        assertFalse(TagFilter.parse("cuisine=coffee").matches(cafe))
        assertTrue(TagFilter.parse("cuisine~^(cake|pie)$").matches(cafe))
        assertFalse(TagFilter.parse("cuisine!=cake").matches(cafe))
        assertTrue(TagFilter.parse("cuisine~^cake$").matches(mapOf("cuisine" to "pizza; cake")))
    }

    @Test
    fun numbersWithUnits() {
        val bridge = mapOf("maxheight" to "4.2", "maxweight" to "3.5 t", "socket:type2_combo:output" to "150 kW;50 kW", "maxspeed" to "30 mph")
        assertTrue(TagFilter.parse("maxheight>=4 m").matches(bridge))
        assertFalse(TagFilter.parse("maxheight>=4.5m").matches(bridge))
        assertTrue(TagFilter.parse("maxheight<14 ft").matches(bridge))
        assertTrue(TagFilter.parse("maxweight<3600 kg").matches(bridge))
        assertFalse(TagFilter.parse("maxweight>3.5t").matches(bridge))
        assertTrue(TagFilter.parse("\"socket:type2_combo:output\">=100 kW").matches(bridge))
        assertTrue(TagFilter.parse("socket:type2_combo:output>=0.1MW").matches(bridge))
        assertTrue(TagFilter.parse("maxspeed>40 km/h").matches(bridge))
        assertFalse(TagFilter.parse("maxspeed>50 km/h").matches(bridge))
        assertTrue(TagFilter.parse("maxheight>=4,2").matches(bridge))
        assertFalse("a missing key fails a comparison", TagFilter.parse("width>1").matches(bridge))
        assertFalse("other dimensions do not compare", TagFilter.parse("maxweight<4 m").matches(bridge))
        assertTrue(TagFilter.parse("maxheight>=12'6\"").matches(mapOf("maxheight" to "13'0\"")))
    }

    @Test
    fun precedenceAndGrouping() {
        // and binds tighter than or; a space is an and; not binds tightest
        val f = TagFilter.parse("shop=bakery or amenity=cafe outdoor_seating=yes")
        assertTrue(f.matches(mapOf("shop" to "bakery")))
        assertTrue(f.matches(cafe))
        assertFalse(f.matches(mapOf("amenity" to "cafe")))
        val g = TagFilter.parse("(shop=bakery or amenity=cafe) and outdoor_seating=yes")
        assertFalse(g.matches(mapOf("shop" to "bakery")))
        assertTrue(TagFilter.parse("not (shop or office)").matches(cafe))
        assertTrue(TagFilter.parse("amenity=cafe && !shop || office").matches(cafe))
        assertTrue(TagFilter.parse("amenity=bar | amenity=cafe").matches(cafe))
        assertTrue(TagFilter.parse("NOT shop AND amenity").matches(cafe))
        assertTrue(TagFilter.parse("note=x").matches(mapOf("note" to "x")))
    }

    @Test
    fun textRoundTrips() {
        val texts = listOf(
            "amenity=cafe and outdoor_seating=yes and (cuisine~coffee|cake) and not access=private",
            "maxheight>=4.2 m", "\"name:nl\"=\"De Kas\"", "shop!=bakery", "not (shop or office)", "~^diet:~^(yes|only)$",
            "socket:type2>=2", "amenity=bar or amenity=pub",
        )
        for (t in texts) {
            val f = TagFilter.parse(t)
            assertEquals(t, f, TagFilter.parse(f.toText()))
        }
        assertEquals("maxheight>=4.2 m", TagFilter.parse("maxheight >= 4.2m").toText())
    }

    @Test
    fun overpassSelectors() {
        assertEquals("[\"amenity\"=\"cafe\"][!\"fee\"][\"cuisine\"~\"pizza\",i]", TagFilter.parse("amenity=cafe and not fee and cuisine~pizza").toOverpass())
        assertNull(TagFilter.parse("amenity=cafe or shop").toOverpass())
        assertNull(TagFilter.parse("maxheight>4").toOverpass())
    }

    @Test
    fun literalListsUseASet() {
        assertEquals(setOf("bar", "pub"), TagFilter.literalAlternatives("^(bar|pub)$"))
        assertEquals(setOf("bar"), TagFilter.literalAlternatives("^bar$"))
        assertNull("alternation without a group is not anchored on both sides", TagFilter.literalAlternatives("^bar|pub$"))
        assertNull(TagFilter.literalAlternatives("^(ba.|pub)$"))
        assertNull(TagFilter.literalAlternatives("bar"))
        val f = TagFilter.parse("amenity~^(bar|pub)$")
        assertTrue(f.matches(mapOf("amenity" to "PUB")))
        assertFalse(f.matches(mapOf("amenity" to "pubs")))
        assertTrue(f.matches(mapOf("amenity" to "cafe;pub")))
    }

    @Test
    fun errorsCarryAPosition() {
        for (bad in listOf("", "amenity=", "(amenity=cafe", "amenity=cafe)", "maxheight>=tall", "maxheight>=12'x", "a~(", "\"open")) {
            try {
                TagFilter.parse(bad)
                fail("parsed '$bad'")
            } catch (e: TagFilterException) {
                assertTrue(e.index >= 0)
            }
        }
        assertNull(TagFilter.parseOrNull("amenity=cafe and"))
    }

    @Test
    fun aKeyThatLooksLikeAUnitStartsTheNextTerm() {
        // "a" is also the ampere: after a number it was read as the unit and the "=b" that followed failed to parse
        val f = TagFilter.parse("capacity>=10 a=b")
        assertEquals(TagFilter.And(listOf(TagFilter.Compare("capacity", CompareOp.GE, 10.0), TagFilter.Equals("a", "b"))), f)
        assertTrue(f.matches(mapOf("capacity" to "12", "a" to "b")))
        assertEquals(TagFilter.Compare("maxweight", CompareOp.LT, 7.5, "t"), TagFilter.parse("maxweight<7.5 t"))
        assertEquals(2, (TagFilter.parse("maxweight<7.5 t t=x") as TagFilter.And).parts.size)
    }

    @Test
    fun operatorsWithoutSpacesEndAValue() {
        val or = TagFilter.parse("amenity=cafe||shop=bakery")
        assertEquals(TagFilter.Or(listOf(TagFilter.Equals("amenity", "cafe"), TagFilter.Equals("shop", "bakery"))), or)
        assertTrue(or.matches(mapOf("shop" to "bakery")))
        val and = TagFilter.parse("amenity=cafe&&outdoor_seating=yes")
        assertTrue(and.matches(cafe))
        assertFalse(and.matches(mapOf("amenity" to "cafe")))
        // a value that holds the operators keeps them when quoted, and is written back quoted
        val odd = TagFilter.Equals("name", "a||b")
        assertEquals(odd, TagFilter.parse(odd.toText()))
    }

    @Test
    fun everyFilterReadsBackEqual() {
        val filters = listOf(
            TagFilter.KeyMatches("^diet:"), TagFilter.KeyMatches("^diet:", "^only$"), TagFilter.Equals("k", "*"),
            TagFilter.NotEquals("k", "*"), TagFilter.Compare("k", CompareOp.GT, 1e20), TagFilter.Compare("k", CompareOp.LE, 0.00001, "m"),
            TagFilter.Compare("maxspeed", CompareOp.LT, 30.0, "mph"), TagFilter.parse("(a or b) or c"), TagFilter.parse("a and (b and c)"),
            TagFilter.Not(TagFilter.parse("a or b")),
        )
        for (f in filters) assertEquals(f.toText(), f, TagFilter.parse(f.toText()))
        assertEquals(TagFilter.Or(listOf(TagFilter.Has("a"), TagFilter.Has("b"), TagFilter.Has("c"))), TagFilter.parse("(a or b) or c"))
        assertEquals("k>100000000000000000000", TagFilter.Compare("k", CompareOp.GT, 1e20).toText())
        assertTrue(TagFilter.KeyMatches("^diet:").matches(mapOf("diet:vegan" to "yes")))
    }

    @Test
    fun overpassSelectsAtLeastWhatTheFilterMatches() {
        // Overpass compares whole values: cuisine=pizza must still find "italian;pizza"
        assertEquals("[\"cuisine\"~\"(^|;) *(pizza) *(;|$)\"]", TagFilter.parse("cuisine=pizza").toOverpass())
        assertEquals("[\"sport\"~\"(^|;) *(climbing|skateboard) *(;|$)\",i]", TagFilter.parse("sport~^(skateboard|climbing)$").toOverpass())
        // main keys stay exact, so Overpass can use its index
        assertEquals("[\"amenity\"=\"cafe\"]", TagFilter.parse("amenity=cafe").toOverpass())
        assertEquals("[\"amenity\"~\"^(bar|pub)$\",i]", TagFilter.parse("amenity~^(bar|pub)$").toOverpass())
        // an anchored pattern on a list key that is not a word list cannot be said: no selector rather than too few
        assertNull(TagFilter.parse("cuisine~^ital.*$").toOverpass())
        assertEquals("[\"cuisine\"~\"ital\",i]", TagFilter.parse("cuisine~ital").toOverpass())
        // regex characters in a value are escaped, quotes and backslashes are escaped for QL
        assertEquals("[\"cuisine\"~\"(^|;) *(a\\\\.b) *(;|$)\"]", TagFilter.Equals("cuisine", "a.b").toOverpass())
        // negations
        assertEquals("[\"cuisine\"!=\"pizza\"]", TagFilter.parse("not cuisine=pizza").toOverpass())
        assertEquals("[\"cuisine\"~\"(^|;) *(pizza) *(;|$)\"]", TagFilter.parse("not cuisine!=pizza").toOverpass())
        assertEquals(TagFilter.parse("sport~^(skateboard|climbing)$").toOverpass(), TagFilter.parse("not sport!~^(skateboard|climbing)$").toOverpass())
        assertEquals("[\"amenity\"=\"cafe\"][\"cuisine\"~\"(^|;) *(cake) *(;|$)\"]", TagFilter.parse("amenity=cafe and cuisine=cake").toOverpass())
        // the selector's regex agrees with the filter on every part of a list
        val pattern = Regex("(^|;) *(pizza) *(;|$)")
        for (v in listOf("pizza", "italian;pizza", "pizza; italian", "italian; pizza ;kebab")) {
            assertTrue(v, TagFilter.parse("cuisine=pizza").matches(mapOf("cuisine" to v)))
            assertTrue(v, pattern.containsMatchIn(v))
        }
        assertFalse(pattern.containsMatchIn("pizzeria;italian"))
    }
}
