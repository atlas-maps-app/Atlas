package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.providers.OverpassElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A place store of schema 3, as packs-20261104 (the last packs published) and the Atlas before alpha 32 wrote it, opens in
 * this Atlas as one of its own (the user, 12 November: "When I download the map in the latest alpha it says places are
 * unreadable"): the outline and paths tables it lacks are made, empty, and its places, filed under the categories before
 * their regrouping of 5 November, are filed anew; then they are found by name and by kind.
 */
class StoreSchemaThreeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val golf = OsmId(OsmType.WAY, 1)
    private val cafe = OsmId(OsmType.NODE, 50)

    /** Kennemerland's golf course and a café as the packer of 4 November wrote them: schema 3, its packer named, no filing, no outlines or paths. */
    private fun packOfTheFourth(f: File) {
        PlaceStore.open(f, StoreFilingTest.FIFTH_OF_NOVEMBER).use { s ->
            s.writer(regionName = "Kennemerland").use { w ->
                w.add(OverpassElement(OsmType.WAY, 1, mapOf("leisure" to "golf_course", "name" to "Kennemer Golf"), center = LatLon(52.384, 4.570)))
                w.add(OverpassElement(OsmType.NODE, 50, mapOf("amenity" to "cafe", "name" to "Café Brinkmann"), 52.381, 4.631))
                w.finish()
            }
            assertEquals("other", s.get(golf)!!.category)
            s.locked {
                s.exec("DROP TABLE outline")
                s.exec("DROP TABLE paths")
                s.exec("UPDATE meta SET value = '3' WHERE key = 'schema'")
                s.exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('packer', 'atlas-packer/405740c1')")
                s.exec("DELETE FROM meta WHERE key = '${StoreRefile.FILED}'")
            }
        }
    }

    @Test
    fun aPackOfSchemaThreeOpensIsFiledAnewAndFindsItsPlaces() {
        val f = File(tmp.root, "kennemerland.places.db")
        packOfTheFourth(f)
        PlaceStore.open(f).use { s ->
            // of this Atlas's schema now, its places filed under its rules
            assertEquals(PlaceStore.SCHEMA.toString(), s.meta("schema"))
            assertEquals(Filing.current.fingerprint, s.meta(StoreRefile.FILED))
            assertEquals(1, s.refiled)
            assertEquals("sports", s.get(golf)!!.category)
            assertEquals("restaurants", s.get(cafe)!!.category)
            // by name
            assertEquals(listOf(cafe), s.text("brinkmann").map { it.id })
            assertEquals(listOf(golf), s.text("kennemer").map { it.id })
            // by kind: its category, the category's name in the text index, the evaluator
            assertEquals(listOf(golf), s.byCategory(listOf("sports")).map { it.id })
            assertTrue(s.byCategory(listOf("other")).isEmpty())
            assertEquals(listOf(golf), s.text("sportvelden").map { it.id })
            assertEquals(listOf(golf), OfflineEvaluator(s).run(Query.Category("sports"), EvalContext(limit = 10)).hits.map { it.record.id })
            assertEquals(listOf(cafe), s.byCategory(listOf("restaurants")).map { it.id })
            // it keeps no outlines, as a store from before them: a park's page draws without one
            assertNull(s.outline(golf))
            assertEquals(0L, s.locked { s.long("SELECT count(*) FROM outline") })
            assertEquals(0L, s.locked { s.long("SELECT count(*) FROM paths") })
        }
        // once: it opens as a store of this schema, filed
        PlaceStore.open(f).use { s ->
            assertEquals(0, s.refiled)
            assertFalse(s.needsRefile)
            assertEquals(listOf(cafe), s.text("brinkmann").map { it.id })
        }
    }

    @Test
    fun aPackOfSchemaThreeOpensAtOnceToBeFiledInTheBackground() {
        val f = File(tmp.root, "kennemerland.places.db")
        packOfTheFourth(f)
        // as the app opens a region's store: at once, filed afterwards
        PlaceStore.open(f, refile = false).use { s ->
            assertTrue(s.needsRefile)
            assertEquals(listOf(cafe), s.text("brinkmann").map { it.id })
            assertEquals(1, s.refile())
            assertEquals(listOf(golf), s.byCategory(listOf("sports")).map { it.id })
            assertFalse(s.needsRefile)
        }
    }

    @Test
    fun aStoreOfSchemaThreeTheFilingOfWhichWasCutShortGoesOnAtTheNextOpen() {
        val f = File(tmp.root, "kennemerland.places.db")
        packOfTheFourth(f)
        // opened (schema 4 written), closed before it was filed: the next open files it
        PlaceStore.open(f, refile = false).close()
        PlaceStore.open(f).use { s ->
            assertEquals(1, s.refiled)
            assertEquals("sports", s.get(golf)!!.category)
        }
    }
}
