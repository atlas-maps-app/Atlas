package app.atlas.services

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import app.atlas.geo.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The settings and key stores, on DataStore files in a temporary folder. */
class StoresTest {
    private val dir: File = Files.createTempDirectory("atlas-stores").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun theCameraAndUnitsAreKept() = runBlocking {
        val s = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
        assertNull(s.camera.first())
        assertEquals(Units.METRIC, s.units.first())
        val cam = SavedCamera(LatLon(52.37452, 4.88377), 16.5, 20.0, 50.0)
        s.saveCamera(cam)
        assertEquals(cam, s.camera.first())
        s.set(SettingsKeys.UNITS, Units.NAUTICAL.name)
        assertEquals(Units.NAUTICAL, s.units.first())
        val mine = stringPreferencesKey("mine.voice")
        assertEquals("warm", s.flow(mine, "warm").first())
        s.set(mine, "calm")
        assertEquals("calm", s.flow(mine).first())
        s.set(mine, null)
        assertNull(s.flow(mine).first())
    }

    @Test
    fun keysAreReadAtOnceAndBlankRemoves() = runBlocking {
        val k = KeyStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "keys.preferences_pb") }, scope)
        assertNull(k.key("ocm"))
        k.set("ocm", "  abc123 ")
        assertEquals("abc123", k.key("ocm"))
        k.set("ocm", " ")
        assertNull(k.key("ocm"))
        k.set("foursquare", "f")
        k.clear()
        assertNull(k.key("foursquare"))
    }
}
