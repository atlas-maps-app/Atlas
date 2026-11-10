package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindNamesTest {
    @Test
    fun aKindIsCalledWhatItSells() {
        val clothes = Presets.byId("shop/clothes")!!
        assertEquals(listOf("Clothes", "Kleding", "Kleidung", "Vêtements"), KindNames.LANGS.map { clothes.name(it) })
        assertEquals("Gifts", Presets.byId("shop/gift")!!.name("en"))
        // the chips and a kind's header say it as well
        assertEquals("Clothes", CategoryTree.byId("preset:shop/clothes")!!.name("en"))
        // its own name stays: it titles a place with no name, and the search knows the kind by it
        assertEquals("Clothing Store", clothes.fullName("en"))
        // a - keeps id-tagging-schema's name in that language, as does a language the file has no column for
        assertEquals("Schoonheidssalon", Presets.byId("shop/beauty")!!.name("nl"))
        assertEquals(clothes.fullName("es"), clothes.name("es"))
        // a kind whose own name says it in a word has no line
        assertEquals("Bakery", Presets.byId("shop/bakery")!!.name("en"))
    }

    @Test
    fun everyLineNamesAKindAtlasKnowsInEveryLanguage() {
        val lines = Resources.text(KindNames.FILE)!!.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size >= 100)
        for (line in lines) {
            val cells = line.split('\t')
            assertEquals(line, 1 + KindNames.LANGS.size, cells.size)
            assertNotNull(line, Presets.byId(cells[0]))
            assertTrue(line, cells.all { it.isNotBlank() && it == it.trim() })
            // English always names it: that is what the line is for
            assertTrue(line, cells[1] != "-")
        }
        assertEquals(lines.size, KindNames.all().size)
    }

    @Test
    fun noNameIsStillAShops() {
        val shop = Regex("(?i)\\b(store|shop|seller|dealer|dealership)\\b|(winkel|zaak|handel|geschäft|laden)\\b|^(magasin|boutique)\\b")
        for ((kind, names) in KindNames.all()) for (n in names.filterNotNull()) assertFalse("$kind: $n", shop.containsMatchIn(n))
    }
}
