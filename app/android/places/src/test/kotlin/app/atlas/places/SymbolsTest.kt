package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolsTest {
    /** The UI symbols the brief lists, by Atlas id. */
    private val uiSet = (
        "search mic back close share bookmark layers compass locate cube globe sun cloud cloud-off rain wind thermometer clock " +
            "calendar phone web star heart flame trending-up leaf wheelchair wifi wifi-off plug battery bolt car truck bike walk bus " +
            "train tram ship anchor sailboat plane parking fuel toilet shower baby dog cash card euro cup utensils beer wine bed tent " +
            "mountain tree waves music ticket shopping-bag basket gift scissors wrench hammer pill cross stethoscope headphones vibrate " +
            "eye eye-off filter sliders plus minus check x chevron-up chevron-down chevron-left chevron-right arrow-up arrow-down " +
            "arrow-left arrow-right turn-left turn-right u-turn roundabout merge fork flag map-pin navigation route download refresh " +
            "settings user users lock shield bell book camera image info help alert-triangle umbrella sunrise sunset moon droplet " +
            "snowflake smoke-off accessibility ramp stairs elevator door coins receipt tag percent scale ruler timer hourglass history " +
            "sparkles wand braces"
        ).split(' ')

    @Test
    fun iconsHaveViewBoxesAndStyles() {
        val cafe = Symbols.icon("maki-cafe")!!
        assertEquals(ViewBox(0f, 0f, 15f, 15f), cafe.viewBox)
        assertTrue(cafe.paths.all { it.fill && !it.stroke })
        val search = Symbols.icon("ui-search")!!
        assertEquals(24f, search.viewBox.width)
        assertTrue(search.paths.all { it.stroke && !it.fill })
        assertEquals(2f, search.strokeWidth)
        val logo = Symbols.icon("logo-albertheijn")!!
        assertTrue(logo.paths.all { it.fill })
        assertNull(Symbols.icon("maki-not-a-symbol"))
        assertFalse(Symbols.has("ui-not-a-symbol"))
        assertTrue(Symbols.icon("maki-cafe") === cafe)
    }

    @Test
    fun theUiSetIsComplete() {
        val missing = uiSet.filter { Symbols.icon("ui-$it") == null }
        assertTrue("missing UI symbols: $missing", missing.isEmpty())
        assertTrue(Symbols.ids("ui").size >= 200)
        for (id in Symbols.ids("ui")) assertTrue(id, Symbols.icon(id)!!.paths.all { it.stroke })
    }

    @Test
    fun everySymbolTheDataNamesExists() {
        for (p in Presets.all) p.icon?.let { assertTrue("${p.id}: $it", Symbols.has(it)) }
        for (v in Cuisine.known) assertTrue(v, Symbols.has(Cuisine.symbol(v)))
        for (k in FactKind.entries) assertTrue(k.name, Symbols.has(k.symbol))
        val factSymbols = listOf(
            "ui-vegan", "ui-moon-star", "ui-hexagram", "ui-wheat-off", "ui-milk-off", "ui-fish", "ui-wifi-off", "ui-cash", "ui-contactless",
            "ui-smartphone", "ui-smoke-off", "ui-parking", "ui-plug", "ui-unlock",
        )
        for (s in factSymbols) assertTrue(s, Symbols.has(s))
    }

    @Test
    fun symbolForTags() {
        assertEquals("maki-restaurant", Symbols.symbolFor(mapOf("amenity" to "restaurant")))
        assertEquals("maki-grocery", Symbols.symbolFor(mapOf("shop" to "supermarket")))
        assertEquals("maki-lodging", Symbols.symbolFor(mapOf("tourism" to "hotel")))
        assertEquals("maki-fast-food", Symbols.symbolFor(mapOf("amenity" to "fast_food", "cuisine" to "burger")))
        assertEquals("maki-shop", Symbols.symbolFor(mapOf("shop" to "flux_capacitors")))
        assertEquals(Symbols.MARKER, Symbols.symbolFor(emptyMap()))
        assertTrue(Symbols.symbolFor(mapOf("craft" to "carpenter")) != Symbols.MARKER)
    }

    @Test
    fun coloursByFamily() {
        fun fam(vararg kv: Pair<String, String>) = Symbols.familyOf(mapOf(*kv))
        assertEquals(PlaceFamily.FOOD, fam("amenity" to "cafe"))
        assertEquals(PlaceFamily.GROCERY, fam("shop" to "supermarket"))
        assertEquals(PlaceFamily.BEAUTY, fam("shop" to "hairdresser"))
        assertEquals(PlaceFamily.SHOP, fam("shop" to "shoes"))
        assertEquals(PlaceFamily.CULTURE, fam("tourism" to "museum"))
        assertEquals(PlaceFamily.CULTURE, fam("shop" to "art"))
        assertEquals(PlaceFamily.STAY, fam("tourism" to "hotel"))
        assertEquals(PlaceFamily.HEALTH, fam("amenity" to "pharmacy"))
        assertEquals(PlaceFamily.HEALTH, fam("healthcare" to "physiotherapist"))
        assertEquals(PlaceFamily.SPORT, fam("leisure" to "fitness_centre"))
        assertEquals(PlaceFamily.NATURE, fam("leisure" to "park"))
        assertEquals(PlaceFamily.CHARGE, fam("amenity" to "charging_station"))
        assertEquals(PlaceFamily.FUN, fam("leisure" to "playground"))
        assertEquals(PlaceFamily.WATER, fam("leisure" to "marina"))
        assertEquals(PlaceFamily.TRANSPORT, fam("highway" to "bus_stop"))
        assertEquals(PlaceFamily.SERVICE, fam("office" to "lawyer"))
        assertNull(fam("building" to "yes"))
        assertEquals(0xFFE07A1F.toInt(), Symbols.colourFor(mapOf("amenity" to "restaurant")))
        assertEquals(Palette.SERVICE, Symbols.colourFor(mapOf("building" to "yes")))
    }

    @Test
    fun iconsReplayScaled() {
        val icon = Symbols.icon("ui-plus")!!
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        icon.replay(object : PathSink {
            fun see(x: Float) { minX = minOf(minX, x); maxX = maxOf(maxX, x) }
            override fun moveTo(x: Float, y: Float) = see(x)
            override fun lineTo(x: Float, y: Float) = see(x)
            override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = see(x)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = see(x)
            override fun close() {}
        }, size = 48f, x = 100f)
        // Lucide's plus runs from 5 to 19 on its 24 grid: 110 to 138 at twice the size, moved by 100
        assertEquals(110f, minX, 0.01f)
        assertEquals(138f, maxX, 0.01f)
        assertNotNull(Symbols.icon("pinhead-taco"))
    }
}
