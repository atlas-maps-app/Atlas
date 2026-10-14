package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class RegionDownloaderTest {
    private val dir = tempDir("regions")
    private val server = FakeTileServer(Fixtures.all.associateWith { Fixtures.bytes(it) })
    private var now = 1_700_000_000_000L

    /** Exactly the fixture block at z14 (8413–8415 × 5383–5385), with its parents at z13 (2 × 2) and z12 (1 × 2). */
    private val plan: RegionPlan = run {
        val z14 = Fixtures.z14
        val west = z14.minOf { it.bbox.west } + 1e-6
        val east = z14.maxOf { it.bbox.east } - 1e-6
        val south = z14.minOf { it.bbox.south } + 1e-6
        val north = z14.maxOf { it.bbox.north } - 1e-6
        RegionPlanner.plan(BBox(west, south, east, north), 12, 14)
    }

    @After
    fun stop() = server.close()

    private fun downloader(source: OpenFreeMapSource = OpenFreeMapSource(server.tileJsonUrl, backoffMillis = 5, clock = { now })) =
        RegionDownloader(source, concurrency = 4, batchSize = 3, clock = { now })

    @Test
    fun downloadsAPackStoredGzipped() = runBlocking {
        assertEquals(9L + 4 + 2, plan.tileCount)
        val file = File(dir, "ams.mbtiles")
        val updates = downloader().download(plan, file, "Amsterdam").toList()
        val last = updates.last()
        assertTrue(last.finished)
        assertEquals(plan.tileCount, last.total)
        assertEquals(plan.tileCount, last.done)
        // Only 13/4207/2692 and 12/2103/1346 of the parents are in the fixtures; the server has no others.
        assertEquals(11L, last.fetched)
        assertEquals(4L, last.missing)
        assertEquals(0L, last.failed)
        assertTrue(updates.zipWithNext().all { (a, b) -> b.done >= a.done })
        assertTrue(updates.size > 3)
        assertTrue(server.maxInFlight.get() <= 4)
        MbTiles(file).use { pack ->
            assertEquals("gzip", pack.metadata("compression"))
            assertEquals("Amsterdam", pack.metadata("name"))
            assertEquals("12", pack.metadata("minzoom"))
            assertEquals("20261011_080001_pt", pack.metadata("atlas.version"))
            assertEquals("""{"vector_layers":[{"id":"water","fields":{}},{"id":"building","fields":{}}]}""", pack.metadata("json"))
            assertEquals(11L, pack.count())
            assertEquals(last.bytes, pack.dataBytes())
            for (id in Fixtures.z14) {
                assertTrue(Gzip.isGzip(pack.readStored(id)!!))
                assertArrayEquals(Fixtures.bytes(id), pack.tile(id))
            }
        }
        // A pack is a tile source.
        val stack = TileStack(MbTiles(file))
        assertEquals(TileId(14, 8414, 5384), stack.bestTile(TileId(17, 8414 * 8, 5384 * 8))!!.source)
        stack.close()
    }

    @Test
    fun resumesWhereItStopped() = runBlocking {
        val file = File(dir, "resume.mbtiles")
        // Stop after the first few progress reports: the collector cancels the download.
        downloader().download(plan, file, "Amsterdam").take(3).toList()
        val storedIds = MbTiles(file).use { db -> (12..14).flatMap { z -> db.tileKeys(z).map { TileId(z, (it ushr 32).toInt(), it.toInt()) } } }
        val stored = storedIds.size.toLong()
        assertTrue("stored $stored", stored in 1L..10L)
        val mark = server.requests.size
        val last = downloader().download(plan, file, "Amsterdam").toList().last()
        assertTrue(last.finished)
        assertEquals(stored, last.skipped)
        assertEquals(11L - stored, last.fetched)
        assertEquals(11L, MbTiles(file).use { it.count() })
        // The second run asked only for what was not stored (the missing tiles included).
        val asked = server.requests.drop(mark).map { it.url.encodedPath }.filter { it.endsWith(".pbf") }
        for (id in storedIds) assertTrue("$id asked again", asked.none { it.endsWith("/${id.z}/${id.x}/${id.y}.pbf") })
        assertTrue(asked.size >= plan.tileCount - stored)
    }

    @Test
    fun updatesOnlyWhatIsStale() = runBlocking {
        val file = File(dir, "update.mbtiles")
        downloader().download(plan, file, "Amsterdam").toList()
        val afterDownload = server.tileRequests.get()
        // Same release, fresh: nothing to fetch but the missing tiles.
        val same = downloader().update(plan, file, "Amsterdam", maxAgeMillis = 10_000).toList().last()
        assertEquals(11L, same.skipped)
        assertEquals(4, server.tileRequests.get() - afterDownload)
        // Same release, old: conditional requests, answered 304, keep the tiles.
        now += 20_000
        val old = downloader().update(plan, file, "Amsterdam", maxAgeMillis = 10_000).toList().last()
        assertEquals(0L, old.fetched)
        assertEquals(11L, old.skipped)
        assertTrue(server.requests.takeLast(11).count { it.headers["If-None-Match"] != null } >= 8)
        // A new release: every tile is fetched again and the pack says which release it holds.
        server.version = "20261018_000000_pt"
        val fresh = downloader().update(plan, file, "Amsterdam", maxAgeMillis = 10_000).toList().last()
        assertEquals(11L, fresh.fetched)
        MbTiles(file).use { assertEquals("20261018_000000_pt", it.metadata("atlas.version")) }
    }

    @Test
    fun anInterruptedUpdateKeepsTheReleaseItHolds() = runBlocking {
        val file = File(dir, "interrupted.mbtiles")
        downloader().download(plan, file, "Amsterdam").toList()
        server.version = "20261018_000000_pt"
        server.delayMillis = 30
        // The collector stops after the first batch: most tiles still belong to the old release.
        downloader().update(plan, file, "Amsterdam", maxAgeMillis = 10_000).take(2).toList()
        MbTiles(file).use { assertEquals("20261011_080001_pt", it.metadata("atlas.version")) }
        val done = downloader().update(plan, file, "Amsterdam", maxAgeMillis = 10_000).toList().last()
        assertTrue(done.finished)
        MbTiles(file).use { assertEquals("20261018_000000_pt", it.metadata("atlas.version")) }
    }

    @Test
    fun givesUpWhenTheServerKeepsFailing() = runBlocking {
        val file = File(dir, "fail.mbtiles")
        val source = OpenFreeMapSource(server.tileJsonUrl, backoffMillis = 1, maxAttempts = 1, clock = { now })
        source.tileJson()
        server.failNext.set(1_000)
        val dl = RegionDownloader(source, concurrency = 2, maxConsecutiveFailures = 5, clock = { now })
        val e = runCatching { dl.download(plan, file, "Amsterdam").toList() }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("in a row"))
        assertTrue(server.tileRequests.get() < 10)
    }

    @Test
    fun refusesPlansTooBigOrTooDeep() = runBlocking {
        val country = RegionPlanner.plan(BBox(2.0, 45.0, 16.0, 56.0), 0, 14)
        assertTrue(country.tileCount > 250_000)
        val e1 = runCatching { downloader().download(country, File(dir, "big.mbtiles"), "Big").toList() }.exceptionOrNull()
        assertTrue("$e1", e1 is IllegalArgumentException)
        val deep = RegionPlanner.plan(plan.bbox, 15, 15)
        val e2 = runCatching { downloader().download(deep, File(dir, "deep.mbtiles"), "deep").toList() }.exceptionOrNull()
        assertTrue("$e2", e2 is IllegalArgumentException)
        assertEquals(0, server.tileRequests.get())
    }

    @Test
    fun libraryKeepsManifests() = runBlocking {
        val lib = RegionLibrary(File(dir, "lib"), clock = { now })
        val progress = lib.download(downloader(), plan, "Amsterdam centrum").toList()
        assertTrue(progress.last().finished)
        val m = lib.list().single()
        assertEquals("amsterdam-centrum", m.id)
        assertEquals("Amsterdam centrum", m.name)
        assertEquals(12, m.minZoom)
        assertEquals(14, m.maxZoom)
        assertEquals(plan.tileCount, m.tileCount)
        assertEquals(lib.packFile(m.id).length(), m.bytes)
        assertEquals("20261011_080001_pt", m.version)
        assertTrue(m.complete)
        assertEquals(now, m.createdAt)
        val packs = lib.openPacks()
        assertEquals(1, packs.size)
        assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), packs[0].tile(Fixtures.CENTRE))
        packs.forEach { it.close() }
        assertTrue(lib.delete(m.id))
        assertTrue(lib.list().isEmpty())
        assertTrue(!lib.packFile(m.id).exists())
    }

    @Test
    fun manifestRoundTrips() {
        val m = RegionManifest("ams", "Amsterdam \"centre\"", BBox(4.85, 52.36, 4.92, 52.39), 0, 14, 412, 31_000_000, 1_760_000_000_000, 1_760_100_000_000, "20261011_080001_pt", true)
        assertEquals(m, RegionManifest.fromJson(m.toJson()))
        assertTrue(m.toJson().contains("\"created\":\"2025-"))
        assertEquals("s-hertogenbosch-noord", RegionManifest.idFor("'s-Hertogenbosch Noord"))
        assertEquals("zurich-sud", RegionManifest.idFor("Zürich Süd"))
    }

    @Test
    fun idsKeepRegionsInOtherScriptsApart() {
        // Each name is its own pack: a name the slug cannot spell gets a hash of itself.
        val names = listOf("東京", "大阪", "Москва", "Київ", "???", "!!!", "Straße", "Strasse")
        val ids = names.map { RegionManifest.idFor(it) }
        assertEquals(ids.toString(), names.size, ids.toSet().size)
        for (id in ids) assertTrue(id, id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")) && id.length <= 48)
        assertEquals(RegionManifest.idFor("東京"), RegionManifest.idFor("東京"))
        // A long name cut to length keeps apart from another with the same beginning.
        val long = "Provincie Noord-Holland en het Gooi, Vechtstreek en Waterland"
        assertTrue(RegionManifest.idFor(long) != RegionManifest.idFor("$long Noord"))
        assertTrue(RegionManifest.idFor(long).length <= 48)
    }

    @Test
    fun corruptBodiesAreNotStoredAsCurrent() = runBlocking {
        val file = File(dir, "corrupt.mbtiles")
        server.corruptTiles = true
        val bad = downloader().download(plan, file, "Amsterdam").toList().last()
        assertTrue(bad.finished)
        assertEquals(0L, bad.fetched)
        assertEquals(11L, bad.failed)
        assertEquals(0L, MbTiles(file).use { it.count() })
        // The next run (the proxy fixed) fetches them, since none was kept.
        server.corruptTiles = false
        val good = downloader().download(plan, file, "Amsterdam").toList().last()
        assertEquals(11L, good.fetched)
        MbTiles(file).use { pack -> for (id in Fixtures.z14) assertArrayEquals(Fixtures.bytes(id), pack.tile(id)) }
    }

    @Test
    fun anUpdateWithFailuresKeepsTheReleaseItHolds() = runBlocking {
        val file = File(dir, "partial.mbtiles")
        downloader().download(plan, file, "Amsterdam").toList()
        server.version = "20261018_000000_pt"
        server.failNext.set(3)
        val once = OpenFreeMapSource(server.tileJsonUrl, backoffMillis = 1, maxAttempts = 1, clock = { now })
        val partial = RegionDownloader(once, concurrency = 2, batchSize = 3, clock = { now }).update(plan, file, "Amsterdam").toList().last()
        assertTrue(partial.finished)
        assertEquals(3L, partial.failed)
        MbTiles(file).use { assertEquals("20261011_080001_pt", it.metadata("atlas.version")) }
        downloader().update(plan, file, "Amsterdam").toList()
        MbTiles(file).use { assertEquals("20261018_000000_pt", it.metadata("atlas.version")) }
    }

    @Test
    fun anUpdateKeepsTheRegionOnTheMap() = runBlocking {
        val lib = RegionLibrary(File(dir, "lib2"), clock = { now })
        lib.download(downloader(), plan, "Amsterdam").toList()
        assertTrue(lib.get("amsterdam")!!.complete)
        server.version = "20261018_000000_pt"
        server.delayMillis = 30
        // Stopped after its first batch, the update leaves a pack that still works.
        lib.download(downloader(), plan, "Amsterdam", update = true).take(2).toList()
        assertTrue(lib.get("amsterdam")!!.complete)
        assertEquals(1, lib.openPacks().onEach { it.close() }.size)
        // A first download stopped half-way is not offered as a map.
        lib.download(downloader(), plan, "Haarlem").take(2).toList()
        assertTrue(!lib.get("haarlem")!!.complete)
        assertEquals(1, lib.openPacks().onEach { it.close() }.size)
    }

    @Test
    fun aDownloadWritesNothingUntilCollected() {
        val lib = RegionLibrary(File(dir, "lib3"), clock = { now })
        lib.download(downloader(), plan, "Utrecht")
        assertEquals(null, lib.get("utrecht"))
        assertTrue(lib.list().isEmpty())
    }
}
