package app.atlas

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.atlas.search.PlaceStore
import app.atlas.services.AndroidLocationSource
import app.atlas.testing.TempFiles
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every service builds, on its own folders, and the real (non-test) set-up starts without network or permissions; its
 * place stores are attached from `places/`, and one that cannot be opened is listed instead of skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServicesTest {
    @Test
    fun everyServiceBuilds() {
        val s = testServices()
        assertNotNull(s.settings)
        assertNotNull(s.keys)
        assertFalse(s.connectivity.online.value)
        assertNotNull(s.http)
        assertNotNull(s.localData)
        assertNotNull(s.overpass)
        assertNotNull(s.weather)
        assertNotNull(s.ratings)
        assertEquals(listOf("osm-chargers", "ocm", "ndw", "bnetza"), s.chargers.map { it.id })
        assertNotNull(s.geocoder)
        assertNotNull(s.segments)
        assertNotNull(s.router)
        assertNotNull(s.search)
        assertNotNull(s.places)
        assertTrue(s.voice is app.atlas.ui.go.TtsVoice)
        assertTrue(s.location.isDemo)
        assertEquals(Services.WESTERKERK, s.location.last.value?.location)
        assertTrue(s.regions.regions.value.isEmpty())
        s.reloadRegions()
        assertEquals(1, s.regionsChanged.value)
        s.close()
    }

    @Test
    fun theRealSetUpStartsOffline() = runBlocking<Unit> {
        // Gone when the test JVM exits, as every test's folder is (it was left in the temp folder until 10 November, 6.8
        // MB a run). Not before: the real set-up wires the process's map engine to this folder's pack, and the screen
        // tests after it in this JVM draw their map with that engine (theirs, mapEngine = false, wire none).
        val root = TempFiles.dir("atlas-real")
        val s = Services(RuntimeEnvironment.getApplication(), ServicesConfig(root = root))
        try {
            assertTrue(s.location.source is AndroidLocationSource)
            assertFalse(s.location.permitted.value)
            // The map's and the features' data: the bundled Westerkerk pack, installed on first use, answers offline.
            assertEquals(app.atlas.services.PlaceOrigin.PACK, s.localData.address(Services.WESTERKERK)?.origin)
            assertTrue(root.resolve("maps/amsterdam.atlas").isFile)
            // The saved camera is read once at start; with none saved it is null.
            assertEquals(null, withTimeout(5_000) { s.startCamera.await() })
            s.useDemoLocation(Services.WESTERKERK)
            assertTrue(s.location.isDemo)
            assertEquals(Services.WESTERKERK, s.location.fixes().first().location)
        } finally {
            s.close()
        }
    }

    /** Sets a key of a store's meta table, as another version of Atlas or other category rules would have left it. */
    private fun meta(store: File, key: String, value: String) {
        val c = BundledSQLiteDriver().open(store.absolutePath)
        try { c.prepare("UPDATE meta SET value = '$value' WHERE key = '$key'").use { it.step() } } finally { c.close() }
    }

    @Test
    fun aStoreThatCannotBeOpenedIsListedNotSkipped() = runBlocking {
        val root = TempFiles.dir("atlas-stores")
        val damaged = File(root, "places/damaged.db").apply { parentFile.mkdirs(); writeText("not a database, though it is named as one") }
        val older = File(root, "places/older.db")
        PlaceStore.open(older).close()
        meta(older, "schema", "3")
        val s = Services(RuntimeEnvironment.getApplication(), ServicesConfig(root = root, offline = true, mapEngine = false))
        s.placeStores
        val refused = s.unreadableStores.value
        assertEquals(setOf(damaged, older), refused.keys)
        assertTrue(refused.getValue(older).otherVersion)
        assertFalse(refused.getValue(damaged).otherVersion)
        assertEquals(damaged.length(), refused.getValue(damaged).bytes)
        // one that is gone is no longer listed
        damaged.delete()
        s.reloadRegions()
        withTimeout(5_000) { s.unreadableStores.first { it.keys == setOf(older) } }
        s.close()
    }

    @Test
    fun aStoreFiledUnderOtherRulesAnswersAndIsFiledAnewInTheBackground() = runBlocking {
        val root = TempFiles.dir("atlas-stores")
        val store = File(root, "places/golf.db")
        val raw = TempFiles.file("golf", ".json").apply {
            writeText("""{"elements":[{"type":"node","id":1,"lat":52.37,"lon":4.88,"tags":{"leisure":"golf_course","name":"Spaarnwoude"}}]}""")
        }
        PlaceStore.open(store).use { it.importOverpass(raw, regionName = "Golf") }
        meta(store, "filed", "the rules of another day")
        val s = Services(RuntimeEnvironment.getApplication(), ServicesConfig(root = root, offline = true, mapEngine = false))
        assertEquals("Spaarnwoude", s.placeStores.text("Spaarnwoude").single().name())
        withTimeout(10_000) { s.regionsChanged.first { it > 0 } }
        assertTrue(s.unreadableStores.value.isEmpty())
        s.close()
        PlaceStore.open(store, refile = false).use { assertFalse(it.needsRefile) }
    }
}
