package app.atlas

import app.atlas.services.AndroidLocationSource
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
import java.nio.file.Files

/** Every service builds, on its own folders, and the real (non-test) set-up starts without network or permissions. */
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
        assertNotNull(s.tiles.value)
        assertNotNull(s.overpass)
        assertNotNull(s.weather)
        assertNotNull(s.ratings)
        assertEquals(2, s.chargers.size)
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
    fun theRealSetUpStartsOffline() = runBlocking {
        val root = Files.createTempDirectory("atlas-real").toFile()
        val s = Services(RuntimeEnvironment.getApplication(), ServicesConfig(root = root))
        assertTrue(s.location.source is AndroidLocationSource)
        assertFalse(s.location.permitted.value)
        // The tile stack is built off the main thread: offline packs (none) and OpenFreeMap through the cache.
        withTimeout(10_000) { while (s.tiles.value == null) kotlinx.coroutines.delay(20) }
        assertNotNull(s.tiles.value)
        assertTrue(root.resolve("cache/tiles/cache.mbtiles").isFile)
        // The saved camera is read once at start; with none saved it is null.
        assertEquals(null, withTimeout(5_000) { s.startCamera.await() })
        s.useDemoLocation(Services.WESTERKERK)
        assertTrue(s.location.isDemo)
        assertEquals(Services.WESTERKERK, s.location.fixes().first().location)
        s.close()
    }
}
