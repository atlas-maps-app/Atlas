package app.atlas.ui.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.FactKind
import app.atlas.places.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Quests from a place's unmapped facts, and the exact note an answer leaves for mappers. */
class QuestsTest {
    private val now = LocalDateTime.of(2026, 10, 13, 19, 30)

    /** Café Kalkhoven as mapped: no Wi-Fi, terrace or diet tags. */
    private val cafe = Place(
        OsmId.parse("n1147560517"), LatLon(52.3739012, 4.8830034),
        mapOf("amenity" to "cafe", "name" to "Café Kalkhoven", "opening_hours" to "Tu-Sa 09:00-24:00; Su 10:00-24:00", "wheelchair" to "no"),
        source = "osm",
    )

    @Test
    fun unmappedYesNoFactsBecomeQuestsMostImportantFirst() {
        val quests = Quests.of(Facts.of(cafe.tags, "en", now))
        assertTrue("quests: ${quests.map { it.key }}", quests.isNotEmpty())
        assertTrue(quests.size <= 3)
        // Wheelchair is mapped (no): never asked again.
        assertTrue(quests.none { it.fact.kind == FactKind.WHEELCHAIR })
        assertEquals(quests.sortedByDescending { it.fact.priority }, quests)
        val wifi = Quests.of(Facts.of(cafe.tags, "en", now), max = 10).firstOrNull { it.fact.kind == FactKind.INTERNET }
        if (wifi != null) {
            assertEquals("internet_access", wifi.key)
            assertEquals("wlan", wifi.valueFor(true))
            assertEquals("no", wifi.valueFor(false))
        }
    }

    @Test
    fun mappedFactsAskNothing() {
        val full = cafe.tags + mapOf("outdoor_seating" to "yes", "internet_access" to "wlan", "diet:vegetarian" to "yes", "takeaway" to "no", "toilets" to "yes")
        val quests = Quests.of(Facts.of(full, "en", now), max = 20)
        assertFalse(quests.any { it.key in setOf("outdoor_seating", "internet_access", "diet:vegetarian", "takeaway", "toilets") })
    }

    @Test
    fun theNoteSaysWhatWhereAndWhichTag() {
        val quest = Quests.of(Facts.of(cafe.tags, "en", now), max = 10).first()
        val note = Quests.noteText(cafe, quest, yes = true)
        val lines = note.lines()
        assertTrue(lines[0], lines[0].startsWith("Café Kalkhoven (Cafe) — https://www.openstreetmap.org/node/1147560517"))
        assertTrue(lines[1].endsWith("? A visitor answered: yes."))
        assertEquals("Suggested tag: ${quest.key}=${quest.valueFor(true)}", lines[2])
        assertTrue(Quests.noteText(cafe, quest, yes = false).endsWith("${quest.key}=no"))
    }

    @Test
    fun aPointNoteStartsWithWhereItIs() {
        assertEquals("Westermarkt 72\nThe bakery here closed.", Quests.pointNote("  The bakery here closed. ", "Westermarkt 72"))
        assertEquals("Bench missing", Quests.pointNote("Bench missing", null))
    }
}
