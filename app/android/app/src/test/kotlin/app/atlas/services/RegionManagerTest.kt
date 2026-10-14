package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.tiles.RegionDownloader
import app.atlas.tiles.RegionLibrary
import app.atlas.tiles.RegionManifest
import app.atlas.tiles.TileFetcher
import app.atlas.tiles.FetchResult
import app.atlas.geo.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// SQLite's native library can belong to one class loader only: tests that open a store run in Robolectric's, as the
// screen tests do.
/** The plain region manager: lists what the library holds, downloads map tiles, deletes, and says so. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionManagerTest {
    private val dir: File = Files.createTempDirectory("atlas-regions").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = AtomicInteger()

    /** Serves the fixture tiles as a network would, with a version. */
    private val fetcher = object : TileFetcher {
        val tiles = app.atlas.testing.FixtureTiles()
        override val minZoom = 0
        override val maxZoom = 14
        override val attribution = "test"
        override suspend fun version() = "fixture"
        override suspend fun fetch(id: TileId, etag: String?): FetchResult =
            tiles.tile(id)?.let { FetchResult.Fetched(it, gzipped = false, etag = null, version = "fixture") } ?: FetchResult.Missing
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun downloadsListsCoversAndDeletes() = runBlocking {
        val library = RegionLibrary(dir)
        val manager = LibraryRegionManager(library, { RegionDownloader(fetcher) }, scope) { changes.incrementAndGet() }
        // The fixture's z14 block around the Westerkerk.
        val box = BBox(4.875, 52.370, 4.890, 52.380)
        val id = manager.download(RegionRequest("Westerkerk", box, maxZoom = 14))
        assertEquals(RegionManifest.idFor("Westerkerk"), id)
        withTimeout(30_000) {
            while (manager.regions.value.none { it.id == id && it.complete && it.progress == null }) {
                manager.regions.value.firstOrNull { it.error != null }?.let { throw AssertionError("download failed: ${it.error}") }
                delay(50)
            }
        }
        val r = manager.regions.value.single()
        assertTrue(r.bytes > 0)
        assertEquals(setOf(RegionLayer.MAP), r.layers)
        assertEquals(r, manager.covering(LatLon(52.37452, 4.88377)))
        assertNull(manager.covering(LatLon(51.9, 4.47)))
        assertEquals(1, changes.get())
        manager.delete(id)
        assertTrue(manager.regions.value.isEmpty())
        assertEquals(2, changes.get())
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherLayersAreRefusedClearly() {
        val manager = LibraryRegionManager(RegionLibrary(dir), { RegionDownloader(fetcher) }, scope) {}
        manager.download(RegionRequest("Places", BBox(4.8, 52.3, 4.9, 52.4), setOf(RegionLayer.PLACES)))
    }

    /**
     * A tile service whose first download is stuck in blocking I/O (it cannot notice a cancel until [unstick]) and whose
     * later downloads wait, cancellably, at the start.
     */
    private inner class StuckFetcher : TileFetcher by fetcher {
        val started = AtomicInteger()
        val stuck = CountDownLatch(1)
        val firstIn = CountDownLatch(1)
        val secondIn = CountDownLatch(1)
        val secondCancelled = CountDownLatch(1)
        val firstOut = CountDownLatch(1)
        private val gate = CompletableDeferred<Unit>()

        fun unstick() = stuck.countDown()

        override suspend fun version(): String {
            when (started.incrementAndGet()) {
                1 -> {
                    firstIn.countDown()
                    stuck.await(20, TimeUnit.SECONDS)
                    firstOut.countDown()
                }
                else -> {
                    secondIn.countDown()
                    try {
                        gate.await()
                    } catch (e: CancellationException) {
                        secondCancelled.countDown()
                        throw e
                    }
                }
            }
            return "fixture"
        }
    }

    @Test
    fun aCancelledDownloadDoesNotForgetTheOneThatReplacedIt() = runBlocking {
        val stuck = StuckFetcher()
        val manager = LibraryRegionManager(RegionLibrary(dir), { RegionDownloader(stuck) }, scope) {}
        val request = RegionRequest("Westerkerk", BBox(4.875, 52.370, 4.890, 52.380), maxZoom = 14)
        val id = manager.download(request)
        assertTrue(stuck.firstIn.await(10, TimeUnit.SECONDS))
        // Stop, and start again at once: the first job is still busy writing when the second begins.
        manager.cancel(id)
        manager.download(request)
        assertTrue(stuck.secondIn.await(10, TimeUnit.SECONDS))
        stuck.unstick()
        assertTrue(stuck.firstOut.await(10, TimeUnit.SECONDS))
        delay(500)
        // The second download is still the one the manager knows: asking again does not start a third beside it…
        manager.download(request)
        delay(300)
        assertEquals(2, stuck.started.get())
        // …and cancelling stops it.
        manager.cancel(id)
        assertTrue("the running download was not cancelled", stuck.secondCancelled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun deleteStopsTheDownloadBeforeRemovingItsFiles() = runBlocking {
        val stuck = StuckFetcher()
        val library = RegionLibrary(dir)
        val manager = LibraryRegionManager(library, { RegionDownloader(stuck) }, scope) {}
        val id = manager.download(RegionRequest("Westerkerk", BBox(4.875, 52.370, 4.890, 52.380), maxZoom = 14))
        assertTrue(stuck.firstIn.await(10, TimeUnit.SECONDS))
        val deleting = scope.async { manager.delete(id) }
        delay(300)
        // The download is busy writing its pack: the files are not pulled from under it.
        assertTrue("deleted while the download still ran", deleting.isActive)
        stuck.unstick()
        withTimeout(10_000) { deleting.await() }
        assertTrue(stuck.firstOut.count == 0L)
        assertTrue(manager.regions.value.isEmpty())
        assertNull(library.get(id))
        assertTrue(!library.packFile(id).exists())
    }
}
