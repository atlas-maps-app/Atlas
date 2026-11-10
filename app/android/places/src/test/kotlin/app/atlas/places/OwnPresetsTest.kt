package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kinds of Atlas's own, where id-tagging-schema keeps no preset (`own_presets.tsv`): a national park, a kind among
 * the nature reserves, named so in a row, a chip and the search (the owner, 9 November: "Yes, do it" to "Give national
 * parks a kind of their own, so their rows say "National park"?").
 */
class OwnPresetsTest {
    private val park = mapOf("boundary" to "national_park", "name" to "De Hoge Veluwe")

    @Test
    fun aNationalParkIsAKindOfItsOwnAmongTheNatureReserves() {
        // however it is drawn: a row asks as a point, a store's outline is an area, a boundary relation one too
        for (g in listOf(Geometry.POINT, Geometry.AREA, Geometry.RELATION)) assertEquals("$g", "boundary/national_park", Presets.match(park, g)?.id)
        // tagged a reserve as well, it is the national park
        assertEquals("boundary/national_park", Presets.match(park + ("leisure" to "nature_reserve"), Geometry.AREA)?.id)
        assertEquals("leisure/nature_reserve", Presets.match(mapOf("leisure" to "nature_reserve"), Geometry.AREA)?.id)
        val p = Presets.byId("boundary/national_park")!!
        assertEquals(listOf("National Park", "Nationaal park", "Nationalpark", "Parc national"), OwnPresets.LANGS.map { p.name(it) })
        // a language Atlas does not name it in says the English
        assertEquals("National Park", p.name("es"))
        // its place page asks for a reserve's facts
        assertEquals(Presets.byId("leisure/nature_reserve")!!.fields, p.fields)
        assertTrue("opening_hours" in p.fields)
        // its own node under the nature reserves, beside the reserve's
        val node = CategoryTree.of(park, Geometry.AREA)!!
        assertEquals("preset:boundary/national_park", node.id)
        assertEquals("cat:nature_reserve", node.parentId)
        assertEquals("National Park", node.name("en"))
        assertTrue(CategoryTree.category("nature_reserve")!!.children.map { it.id }.containsAll(listOf("preset:leisure/nature_reserve", node.id)))
    }

    @Test
    fun theNationalParkWearsTheBoardsShieldAndTheReserveItsLeaf() {
        // the Camp board draws "National parks" with the shield and "Nature reserves" with a leaf
        assertEquals("place.national-park", Symbols.symbolFor(park, Geometry.AREA))
        assertEquals("place.national-park", CategoryTree.of(park, Geometry.AREA)!!.symbol)
        val reserve = Symbols.symbolFor(mapOf("leisure" to "nature_reserve", "name" to "Ilperveld"), Geometry.AREA)
        assertEquals("fact.vegan", Symbols.boardId(reserve))
        assertFalse(Symbols.boardId(reserve) == Symbols.boardId("place.national-park"))
    }

    @Test
    fun aNationalParkIsFoundByItsNameInFourLanguages() {
        val asked = listOf("national park" to "en", "national parks" to "en", "nationaal park" to "nl", "nationale parken" to "nl", "Nationalpark" to "de", "parc national" to "fr", "parcs nationaux" to "fr")
        for ((word, lang) in asked) assertEquals("$word ($lang)", "boundary/national_park", Presets.search(word, lang, limit = 1).firstOrNull()?.preset?.id)
    }

    @Test
    fun everyLineIsAKindIdTaggingSchemaLacksWithItsSymbolAndNames() {
        val bundled = Resources.json("presets.json").obj!!["presets"]!!.arr!!.mapNotNull { it.obj?.get("id")?.str }.toSet()
        assertTrue(OwnPresets.lines.isNotEmpty())
        for (line in OwnPresets.lines) {
            val id = line.id
            assertFalse("$id is id-tagging-schema's", id in bundled)
            assertTrue(id, line.tags.isNotEmpty() && line.tags.none { (k, v) -> k.isEmpty() || v.isEmpty() || v == "*" })
            assertTrue(id, line.geometry.any { it == Geometry.POINT || it == Geometry.AREA })
            line.facts?.let { assertTrue("$id: facts of $it", it in bundled) }
            for (lang in OwnPresets.LANGS) assertTrue("$id: $lang", line.text[lang]?.name?.isNotBlank() == true)
            // a kind Atlas lists, drawn by its line in kinds.tsv
            val p = Presets.byId(id)
            assertNotNull(id, p)
            assertTrue(id, p in Kinds.listed())
            assertNotNull("$id has no line in kinds.tsv", Kinds.line(id))
            assertEquals(id, Kinds.line(id)!!.symbol, Kinds.symbolOf(p!!))
        }
    }
}
