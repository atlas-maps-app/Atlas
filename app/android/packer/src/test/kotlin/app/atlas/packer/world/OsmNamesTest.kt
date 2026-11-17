package app.atlas.packer.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OpenStreetMap's names of the globe's places and waters by Wikidata id (world.yml's filter writes them, the globe
 * reads them): a place takes the name of the first node of its id within a degree of Natural Earth's point (a way's or
 * a relation's, which has no point here, anywhere), in its own script; a water the first water's of its id; lines that
 * are no element of an id, of no name or of no water or place are left out.
 */
class OsmNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun placesAndWatersTakeOpenStreetMapsNameByWikidataId() {
        val file = tmp.newFile("names.tsv").apply {
            writeText(
                listOf(
                    "Q1490\tn1\tplace=city\t35.6812\t139.7671\t東京都",
                    "Q1490\tn2\tplace=city\t35.69\t139.69\tTokyo again",
                    "Q649\tn3\tplace=city\t40.0\t37.6\tMoscow 18° off",
                    "Q649\tr4\tplace=city\t\t\tМосква",
                    "Q1386\tn5\tplace=village\t-16.5\t179.99\tNear the antimeridian",
                    "Q47690\tr6\tnatural=water\t\t\tIJsselmeer",
                    "Q47690\tr7\tplace=sea\t\t\tSecond",
                    "Q2\tw8\tnatural=wood\t\t\tA forest",
                    "Q3\tn9\tplace=city\t1\t1\t",
                    "Q04\tn10\tplace=city\t1\t1\tNo id",
                    "short\tline",
                ).joinToString("\n"),
            )
        }
        val names = OsmNames.read(file)
        assertEquals("東京都", names.place("Q1490", 35.68, 139.76))
        // a node over a degree away is not the place; a relation of its id has no point to hold it to
        assertEquals("Москва", names.place("Q649", 55.75, 37.62))
        // longitude's degrees narrow by the latitude and wrap at the antimeridian
        assertEquals("Near the antimeridian", names.place("Q1386", -16.4, -179.5))
        assertNull(names.place("Q1490", 0.0, 0.0))
        assertNull(names.place(null, 35.68, 139.76))
        assertNull(names.place("Q404", 35.68, 139.76))
        // a water by its first water's name; neither a place's name nor a forest's
        assertEquals("IJsselmeer", names.water("Q47690"))
        assertNull(names.water("Q1490"))
        assertNull(names.water("Q2"))
        // an empty name or an id that is not one is no element
        assertNull(names.place("Q3", 1.0, 1.0))
        assertNull(names.place("Q04", 1.0, 1.0))
    }
}
