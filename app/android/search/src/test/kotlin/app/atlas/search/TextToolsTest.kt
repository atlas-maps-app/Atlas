package app.atlas.search

import app.atlas.model.OsmId
import app.atlas.model.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test
    fun tagsJsonRoundTrips() {
        val tags = mapOf(
            "name" to "Café \"De Molen\"", "note" to "line\nbreak\ttab \\ slash", "amenity" to "cafe",
            "name:ja" to "カフェ", "empty" to "", "control" to "a\u0001b",
        )
        assertEquals(tags, TagsJson.read(TagsJson.write(tags)))
        assertEquals(emptyMap<String, String>(), TagsJson.read("{}"))
        assertEquals(emptyMap<String, String>(), TagsJson.read(null))
        assertEquals(mapOf("a" to "1", "b" to "true"), TagsJson.read("""{ "a" : 1 , "b":true, "c": null }"""))
    }

    @Test
    fun idsPackAndUnpack() {
        for (id in listOf(OsmId(OsmType.NODE, 26973327), OsmId(OsmType.WAY, 99205257), OsmId(OsmType.RELATION, 12011952), OsmId(OsmType.NODE, 13_000_000_000))) {
            assertEquals(id, StoreSchema.unpack(StoreSchema.pack(id)))
        }
        val foreign = StoreSchema.foreignKey("ocm:52.37,4.88")
        assertTrue(foreign > 0)
        assertNull(StoreSchema.unpack(foreign))
        assertEquals(foreign, StoreSchema.foreignKey("ocm:52.37,4.88"))
    }

    @Test
    fun wordsFoldAndKeepTimesAndPunctuation() {
        val w = Words.split("Café 22:00, step-free & d'une Straße 2,5 km 24/7")
        assertEquals(listOf("cafe", "22:00", ",", "step", "free", "&", "d", "une", "strasse", "2,5", "km", "24/7"), w.map { it.text })
        assertEquals("Café", w[0].raw)
        assertTrue(w.first { it.text == "2,5" }.isNumber)
        assertFalse(w.first { it.text == "cafe" }.isNumber)
    }

    @Test
    fun gramsAndNameMatching() {
        assertEquals(4, Grams.of("hein").size)
        assertEquals(3, Grams.of("hein", prefix = true).size)
        assertEquals(1.0, NameMatch.score(listOf("westerkerk"), "westerkerk"), 1e-9)
        assertTrue(NameMatch.score(listOf("wester"), "westerkerk") > 0.9)
        val typo = NameMatch.score(listOf("westerkrk"), "westerkerk")
        assertTrue("typo $typo", typo in 0.5..0.8)
        assertTrue(NameMatch.score(listOf("albert", "hein"), "albert heijn") > 0.6)
        assertEquals(0.0, NameMatch.score(listOf("pizza"), "westerkerk"), 0.0)
        // a name that only touches the query scores below one that it covers
        assertTrue(NameMatch.score(listOf("cafe"), "cafe") > NameMatch.score(listOf("cafe"), "cafe de jaren aan het water"))
    }

    @Test
    fun nameKeysAndDocuments() {
        assertTrue(SearchText.isNameKey("name:nl"))
        assertTrue(SearchText.isNameKey("alt_name"))
        assertFalse(SearchText.isNameKey("name:etymology:wikidata"))
        assertFalse(SearchText.isNameKey("brand:wikidata"))
        val doc = SearchText.document(
            mapOf("name" to "Café Molen", "name:en" to "Mill Café", "cuisine" to "coffee_shop;cake", "addr:street" to "Westerstraat", "addr:housenumber" to "12A"),
            "amenity/cafe", "restaurants", listOf("en", "nl"),
        )
        assertTrue(doc.name, "cafe molen" in doc.name && "mill cafe" in doc.name)
        assertTrue(doc.other, "cake" in doc.other)
        assertTrue(doc.terms, "cafe" in doc.terms)
        assertEquals("westerstraat 12a", doc.addr)
    }

    @Test
    fun selectionKeepsPlacesAndNamedThings() {
        assertTrue(Selection.keeps(mapOf("amenity" to "cafe")))
        assertTrue(Selection.keeps(mapOf("addr:housenumber" to "1", "building" to "yes")))
        assertTrue(Selection.keeps(mapOf("highway" to "residential", "name" to "Prinsengracht")))
        assertFalse(Selection.keeps(mapOf("building" to "yes")))
        assertFalse(Selection.keeps(mapOf("highway" to "footway")))
        assertFalse(Selection.keeps(mapOf("type" to "route", "route" to "bus", "name" to "18"), isRelation = true))
        assertTrue(Selection.isTree(mapOf("natural" to "tree")))
        assertFalse(Selection.keeps(mapOf("natural" to "tree")))
    }
}
