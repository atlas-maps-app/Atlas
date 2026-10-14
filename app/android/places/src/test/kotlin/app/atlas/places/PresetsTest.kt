package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private fun id(tags: Map<String, String>, g: Geometry = Geometry.POINT, country: String? = null) = Presets.match(tags, g, country)?.id

    @Test
    fun matchesTheMostSpecificPreset() {
        assertEquals("amenity/cafe", id(mapOf("amenity" to "cafe")))
        assertEquals("amenity/fast_food/burger", id(mapOf("amenity" to "fast_food", "cuisine" to "burger")))
        assertEquals("amenity/restaurant/italian", id(mapOf("amenity" to "restaurant", "cuisine" to "italian", "name" to "Da Mario")))
        assertEquals("shop/supermarket", id(mapOf("shop" to "supermarket", "brand" to "Albert Heijn")))
        assertEquals("shop/supermarket", id(mapOf("shop" to "supermarket"), Geometry.AREA))
        // addTags count: a pharmacy with its healthcare tag stays a pharmacy
        assertEquals("amenity/pharmacy", id(mapOf("amenity" to "pharmacy", "healthcare" to "pharmacy")))
        assertEquals("public_transport/platform/bus_point", id(mapOf("public_transport" to "platform", "bus" to "yes", "highway" to "bus_stop")))
        assertEquals("highway/bus_stop", id(mapOf("highway" to "bus_stop")))
    }

    @Test
    fun fallsBackSensibly() {
        // an unknown shop value is still a shop (matchScore 0.7), an unknown amenity the bare key preset
        assertEquals("shop", id(mapOf("shop" to "flux_capacitors")))
        assertEquals("amenity", id(mapOf("amenity" to "flux_capacitor")))
        assertEquals("address", id(mapOf("addr:street" to "Prinsengracht", "addr:housenumber" to "263")))
        assertNull(id(emptyMap()))
        assertNull(id(mapOf("note" to "nothing to see")))
        // a point tagged like a vertex preset (an entrance mapped as a lone node) still finds it
        assertEquals("entrance/main", id(mapOf("entrance" to "main")))
        assertEquals("entrance", id(mapOf("entrance" to "yes")))
        assertEquals(Presets.fallback(Geometry.AREA)?.id, "area")
    }

    @Test
    fun countryLimitedPresets() {
        val limited = Presets.all.first { it.countries.isNotEmpty() && it.searchable && it.tags.size >= 2 }
        val inside = limited.countries.first()
        assertTrue(limited.availableIn(inside))
        assertFalse(limited.availableIn("aq"))
        val g = limited.geometry.first()
        assertEquals(limited.id, Presets.match(limited.tags, g, inside)?.id)
        assertNotEquals(limited.id, Presets.match(limited.tags, g, "aq")?.id)
    }

    @Test
    fun namesInEveryLanguage() {
        val cafe = Presets.byId("amenity/pharmacy")!!
        assertEquals("Pharmacy Counter", cafe.name("en"))
        assertEquals("Apotheek", cafe.name("nl"))
        assertEquals("Apotheke", cafe.name("de-AT"))
        for (lang in Langs.bundled) assertTrue(lang, cafe.name(lang).isNotBlank())
        assertEquals(cafe.name("en"), cafe.name("xx"))
        assertTrue(Presets.byId("amenity/cafe")!!.terms("nl").contains("koffie"))
    }

    @Test
    fun iconsComeFromTheNearestPresetThatHasOne() {
        val italian = Presets.byId("amenity/restaurant/italian")!!
        assertEquals("maki-restaurant", Presets.iconOf(italian))
        val noIcon = Presets.all.first { it.icon == null && it.parentId != null && Presets.byId(it.parentId!!)?.icon != null }
        assertEquals(Presets.byId(noIcon.parentId!!)!!.icon, Presets.iconOf(noIcon))
    }

    @Test
    fun searchIsForgivingAndMultilingual() {
        assertEquals("amenity/cafe", Presets.search("cafe").first().preset.id)
        assertEquals("amenity/cafe", Presets.search("CAFÉ", "fr").first().preset.id)
        assertTrue(Presets.search("restuarant").take(3).any { it.preset.id == "amenity/restaurant" })
        assertTrue(Presets.search("restaur").take(3).any { it.preset.id == "amenity/restaurant" })
        val apotheek = Presets.search("apotheek", "nl").first()
        assertEquals("amenity/pharmacy", apotheek.preset.id)
        assertEquals("Apotheek", apotheek.name)
        // English always counts too
        assertTrue(Presets.search("pharmacy", "de").take(3).any { it.preset.id == "amenity/pharmacy" })
        assertTrue(Presets.search("Supermarkt", "de").take(3).any { it.preset.id == "shop/supermarket" })
        assertTrue(Presets.search("ev charger").take(5).any { it.preset.id == "amenity/charging_station" })
        assertTrue(Presets.search("xyzzyq").isEmpty())
        assertTrue(Presets.search("").isEmpty())
        assertEquals(5, Presets.search("shop", limit = 5).size)
        // results never repeat a preset and come best first
        val words = listOf("caf", "restaur", "apothe", "bakker", "supermarkt", "laadpaal", "toilet", "fietsenwinkel")
        repeat(3) { words.forEach { Presets.search(it, "nl") } }
        val t0 = System.nanoTime()
        repeat(5) { words.forEach { Presets.search(it, "nl") } }
        println("preset search: %.1f ms per query (nl and en)".format((System.nanoTime() - t0) / 1e6 / (5 * words.size)))
        val hits = Presets.search("bar", "en", 30)
        assertEquals(hits.size, hits.map { it.preset.id }.toSet().size)
        assertEquals(hits.map { it.score }.sortedDescending(), hits.map { it.score })
    }

    @Test
    fun everyLanguageFileLoads() {
        for (lang in Langs.bundled) assertTrue(lang, I18n.pack(lang).presets.size > 500)
        assertEquals("nb", Langs.resolve("no"))
        assertEquals("zh-CN", Langs.resolve("zh-Hans"))
        assertEquals("pt", Langs.resolve("pt_BR"))
        assertEquals("en", Langs.resolve(null))
    }

    @Test
    fun aWordStillBeingTypedForgivesAMissingOrExtraLetter() {
        // "restur": the a is missing, and the word is not finished; the old prefix check compared equal lengths only
        assertEquals(1, Text.prefixDistance("restur", "restaurant", 1))
        assertEquals(0, Text.prefixDistance("resta", "restaurant", 1))
        assertEquals(1, Text.prefixDistance("restaaur", "restaurant", 2))
        assertEquals(2, Text.prefixDistance("xyzzy", "restaurant", 1))
        assertEquals(0, Text.prefixDistance("", "cafe", 1))
        assertTrue(Presets.search("restur").take(3).any { it.preset.id == "amenity/restaurant" })
        assertTrue(Presets.search("supermrk", "nl").take(3).any { it.preset.id == "shop/supermarket" })
        assertTrue(Brands.search("albrt", "nl").take(3).any { it.wikidata == "Q1653985" })
        // a complete word still ranks above a fuzzy prefix
        assertEquals("amenity/restaurant", Presets.search("restaurant").first().preset.id)
    }

    @Test
    fun fallbacksFollowTheGeometry() {
        // id has no address preset for lines, and no "vertex" preset: a vertex falls back on "point"
        assertNull(id(mapOf("addr:street" to "Prinsengracht"), Geometry.LINE))
        assertEquals("address", id(mapOf("addr:street" to "Prinsengracht"), Geometry.AREA))
        assertEquals("point", Presets.fallback(Geometry.VERTEX)?.id)
        assertEquals("point", Presets.fallback(Geometry.POINT)?.id)
        assertEquals("line", Presets.fallback(Geometry.LINE)?.id)
        assertNull(Presets.fallback(Geometry.RELATION))
    }
}
