package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.Categories
import app.atlas.providers.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A store files its places under the category rules of the day it was written, and files them anew, once and with no
 * download, when it is opened under other rules: the categories.json of a later Atlas.
 */
class StoreFilingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val golf = OsmId(OsmType.WAY, 1)
    private val cafe = OsmId(OsmType.NODE, 50)

    /** Kennemerland's golf courses (ways) and a café, written under [filing]. */
    private fun kennemerland(f: File, filing: Filing, courses: Int = 1) = PlaceStore.open(f, filing).use { s ->
        s.writer(regionName = "Kennemerland").use { w ->
            for (i in 1..courses) {
                w.add(OverpassElement(OsmType.WAY, i.toLong(), mapOf("leisure" to "golf_course", "name" to "Kennemer Golf $i"), center = LatLon(52.383 + i * 1e-3, 4.570)))
            }
            w.add(OverpassElement(OsmType.NODE, 50, mapOf("amenity" to "cafe", "name" to "Café Brinkmann"), 52.381, 4.631))
            w.finish()
        }
        assertEquals("other", s.get(golf)!!.category)
    }

    @Test
    fun aStoreFiledUnderOtherRulesIsFiledAnewOnOpen() {
        val f = File(tmp.root, "kennemerland.db")
        kennemerland(f, FIFTH_OF_NOVEMBER)
        // a later Atlas, whose rules put golf courses in Sport
        PlaceStore.open(f).use { s ->
            assertEquals(1, s.refiled)
            val course = s.get(golf)!!
            assertEquals("sports", course.category)
            assertEquals("sport", Categories.byId(course.category!!)!!.shelf!!.id)
            assertEquals("restaurants", s.get(cafe)!!.category)
            // found under its category, and no longer under the old one
            assertEquals(listOf(golf), s.byCategory(listOf("sports")).map { it.id })
            assertTrue(s.byCategory(listOf("other")).isEmpty())
            assertEquals(listOf(golf), OfflineEvaluator(s).run(Query.Category("sports"), EvalContext(limit = 10)).hits.map { it.record.id })
            // by its category's name: the kind token on its line of the text index moved with it, the counts too
            assertEquals(listOf(golf), s.text("sportvelden").map { it.id })
            assertTrue(s.text("overig").none { it.id == golf })
            // and the typo vocabulary holds the new category's names
            assertTrue(s.fuzzyWords("sportveldn").toString(), "sportvelden" in s.fuzzyWords("sportveldn"))
            assertEquals(Filing.current.fingerprint, s.meta(StoreRefile.FILED))
            // all of it is in the file, open as the store still is: no page of it waits in the log beside it
            assertEquals(0L, File(f.path + "-wal").length())
        }
        // once: filed, it opens as it is
        PlaceStore.open(f).use { s ->
            assertEquals(0, s.refiled)
            assertEquals("sports", s.get(golf)!!.category)
        }
    }

    @Test
    fun aStoreWrittenBeforeFingerprintsIsFiledOnce() {
        val f = File(tmp.root, "older.db")
        kennemerland(f, FIFTH_OF_NOVEMBER)
        PlaceStore.open(f, FIFTH_OF_NOVEMBER).use { s -> s.locked { s.exec("DELETE FROM meta WHERE key = '${StoreRefile.FILED}'") } }
        PlaceStore.open(f).use { s ->
            assertEquals(1, s.refiled)
            assertEquals("sports", s.get(golf)!!.category)
        }
    }

    @Test
    fun aStoreOpenedAsFiledAnswersUntilItIsFiledAnew() {
        val f = File(tmp.root, "quick.db")
        kennemerland(f, FIFTH_OF_NOVEMBER)
        // what the app does: open at once, answer as filed, file anew in the background
        PlaceStore.open(f, refile = false).use { s ->
            assertTrue(s.needsRefile)
            assertEquals("other", s.get(golf)!!.category)
            assertEquals(1, s.refile())
            assertFalse(s.needsRefile)
            assertEquals("sports", s.get(golf)!!.category)
            assertEquals(listOf(golf), s.text("sportvelden").map { it.id })
            assertEquals(0, s.refile())
        }
        PlaceStore.open(f, refile = false).use { assertFalse(it.needsRefile) }
        // a new store has nothing to wait for
        PlaceStore.open(File(tmp.root, "new.db"), refile = false).use { assertFalse(it.needsRefile) }
    }

    @Test
    fun aRefileCutShortGoesOnAtTheNextOpen() {
        val f = File(tmp.root, "cut.db")
        kennemerland(f, FIFTH_OF_NOVEMBER, courses = 3)
        // the app killed at the café, the last place: the golf courses are filed, in a transaction each, but not counted
        PlaceStore.open(f, FIFTH_OF_NOVEMBER).use { s ->
            val dying = Filing(Filing.current.fingerprint) { tags -> check(tags["amenity"] != "cafe") { "killed" }; Categories.of(tags) }
            try {
                StoreRefile(s, dying, batch = 1).run()
                fail("the re-file was to stop")
            } catch (e: IllegalStateException) {
                assertEquals("killed", e.message)
            }
            assertEquals("sports", s.get(golf)!!.category)
            assertEquals("5 November", s.meta(StoreRefile.FILED))
        }
        PlaceStore.open(f).use { s ->
            assertEquals(0, s.refiled)
            assertEquals(3, s.byCategory(listOf("sports")).size)
            // counted all the same: the kinds' words find them
            assertEquals(3, s.text("sports grounds").size)
            assertEquals(Filing.current.fingerprint, s.meta(StoreRefile.FILED))
        }
    }

    @Test
    fun theFingerprintIsWhatAStoreKeepsOfTheRules() {
        val json = javaClass.classLoader!!.getResourceAsStream("atlas/places/categories.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val today = Filing.fingerprint(json)
        assertEquals(Filing.current.fingerprint, today)
        // colours, and the names of the families, are read as they are shown: nothing to file anew
        assertEquals(today, Filing.fingerprint(json.replace(Regex("\"colour\":\"#[0-9A-Fa-f]{6}\""), "\"colour\":\"#000000\"")))
        assertEquals(today, Filing.fingerprint(json.replaceFirst("\"Uitjes\"", "\"Uitgaan\"")))
        // a rule, a leaf's symbol, a leaf's name (its words are among a store's typos) and what is not a place are
        assertNotEquals(today, Filing.fingerprint(json.replaceFirst("golf_course|", "")))
        assertNotEquals(today, Filing.fingerprint(json.replaceFirst("\"maki-pitch\"", "\"maki-golf\"")))
        assertNotEquals(today, Filing.fingerprint(json.replaceFirst("\"Sportvelden\"", "\"Sportterreinen\"")))
        assertNotEquals(today, Filing.fingerprint(json.replaceFirst("or shop=vacant ", "")))
    }

    @Test
    fun aStoreFiledUnderOtherNamesTakesTodaysAmongItsTypos() {
        val f = File(tmp.root, "renamed.db")
        PlaceStore.open(f).use { s ->
            s.writer(regionName = "Kennemerland").use { w ->
                w.add(OverpassElement(OsmType.WAY, 1, mapOf("leisure" to "golf_course", "name" to "Kennemer Golf"), center = LatLon(52.384, 4.570)))
                w.finish()
            }
            // as if the sports grounds had had another Dutch name when it was filed: today's is not among its typos
            s.locked {
                s.exec("DELETE FROM word WHERE w = 'sportvelden'")
                s.exec("UPDATE meta SET value = 'other names' WHERE key = '${StoreRefile.FILED}'")
            }
        }
        PlaceStore.open(f, refile = false).use { s -> assertFalse("sportvelden" in s.fuzzyWords("sportveldn")) }
        PlaceStore.open(f).use { s ->
            assertEquals("no place is filed otherwise", 0, s.refiled)
            assertTrue("sportvelden" in s.fuzzyWords("sportveldn"))
            assertEquals("sports", s.get(golf)!!.category)
        }
    }

    companion object {
        /**
         * The rules of 5 November, before the catch-all Other gave up what a family plainly holds and nameless old buildings
         * stopped being sights (the rules of 6 November are categories.json's): near enough, for a store filed then.
         */
        internal val FIFTH_OF_NOVEMBER = Filing("5 November") { tags ->
            when {
                tags["leisure"] in OTHER_THEN || tags["club"] == "sport" || tags["amenity"] in setOf("planetarium", "lounger") -> Categories.byId("other")
                tags["historic"] in setOf("yes", "building") && "name" !in tags -> Categories.byId("sights")
                else -> Categories.of(tags)
            }
        }

        private val OTHER_THEN = setOf(
            "golf_course", "disc_golf_course", "horse_riding", "sports_hall", "bowling_alley", "miniature_golf", "indoor_play",
            "trampoline_park", "dog_park", "common", "bird_hide", "swimming_area", "bathing_place", "resort", "bandstand",
        )
    }
}
