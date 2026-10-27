package app.atlas.tiles

import app.atlas.geo.LatLon
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** A pack's region pack, the file kind "atlas". */
private val Pack.atlasFile: PackFile? get() = files.firstOrNull { it.kind == PackFile.ATLAS }

/**
 * GitHub releases on MockWebServer: `github` answers a release download with a 302 to `storage`, as GitHub does to
 * its asset host, and `storage` serves the bytes with Range support and an ETag.
 */
internal class FakeGitHub : AutoCloseable {
    val github = MockWebServer()
    val storage = MockWebServer()
    val assets = HashMap<String, ByteArray>()
    val storageRequests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    val githubRequests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())

    /** Serve whole files even when a range is asked (a server that ignores Range). */
    @Volatile var ignoreRanges = false
    @Volatile var down = false

    /** Answer a range with 206 but from the file's start (a confused proxy). */
    @Volatile var rangeFromStart = false

    init {
        github.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                githubRequests += request
                if (down) return MockResponse.Builder().code(503).build()
                val name = request.url.encodedPath.substringAfterLast('/')
                if (name !in assets) return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(302).setHeader("Location", storage.url("/assets/$name?sig=abc").toString()).build()
            }
        }
        storage.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                storageRequests += request
                val name = request.url.encodedPath.substringAfterLast('/')
                val body = assets[name] ?: return MockResponse.Builder().code(404).build()
                val etag = "\"${name.hashCode()}\""
                val range = request.headers["Range"]
                if (range == null || ignoreRanges) return MockResponse.Builder().code(200).setHeader("ETag", etag).body(Buffer().write(body)).build()
                if (rangeFromStart) {
                    return MockResponse.Builder().code(206).setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes 0-${body.size - 1}/${body.size}").body(Buffer().write(body)).build()
                }
                val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)!!
                val from = m.groupValues[1].toInt()
                if (from >= body.size) return MockResponse.Builder().code(416).build()
                val to = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                return MockResponse.Builder().code(206).setHeader("ETag", etag)
                    .setHeader("Content-Range", "bytes $from-$to/${body.size}")
                    .body(Buffer().write(body.copyOfRange(from, to + 1))).build()
            }
        }
        github.start()
        storage.start()
    }

    fun url(name: String): String = github.url("/atlas-maps-app/Atlas/releases/download/packs-20261101/$name").toString()

    override fun close() {
        github.close()
        storage.close()
    }
}

class PackCatalogTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("packs")

    @After
    fun close() = gh.close()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** A packs.json for the fake: Luxembourg whole, and a pack whose region pack was cut into three parts. */
    private fun publish(atlas: ByteArray, store: ByteArray, split: Int = 0): String {
        gh.assets["luxembourg.atlas"] = atlas
        val gz = gzip(store)
        gh.assets["luxembourg.places.db.gz"] = gz
        val parts = if (split > 0) atlas.toList().chunked(split).map { it.toByteArray() } else emptyList()
        parts.forEachIndexed { i, p -> gh.assets["big.atlas.%03d".format(i)] = p }
        val partsJson = parts.mapIndexed { i, p ->
            val name = "big.atlas.%03d".format(i)
            """{"name": "$name", "url": "${gh.url(name)}", "bytes": ${p.size}, "sha256": "${sha(p)}"}"""
        }
        val json = """{
          "format": 1, "generated": "2026-11-01T04:00:00Z",
          "attribution": "Map data © OpenStreetMap contributors (ODbL)",
          "packs": [
            {"id": "luxembourg", "name": "Luxembourg", "country": "LU", "bbox": [5.733, 49.4455, 6.5322, 50.185],
             "osm_timestamp": "2026-10-31T20:21:31Z", "release": "packs-20261101",
             "files": [
               {"kind": "atlas", "name": "luxembourg.atlas", "url": "${gh.url("luxembourg.atlas")}", "bytes": ${atlas.size}, "sha256": "${sha(atlas)}", "format": 2},
               {"kind": "places", "name": "luxembourg.places.db.gz", "url": "${gh.url("luxembourg.places.db.gz")}", "bytes": ${gz.size}, "sha256": "${sha(gz)}", "compression": "gzip", "raw_bytes": ${store.size}, "schema": 1}
             ]},
            {"id": "benelux", "name": "Benelux", "bbox": [2.5, 49.4, 7.3, 53.6], "files": [
               {"kind": "atlas", "name": "big.atlas", "bytes": ${atlas.size}, "sha256": "${sha(atlas)}", "parts": [${partsJson.joinToString(",")}]}
             ]},
            {"id": "broken", "name": "No files", "bbox": [0, 0, 1, 1], "files": []},
            {"name": "No id", "bbox": [0, 0, 1, 1], "files": []}
          ]}"""
        gh.assets["packs.json"] = json.toByteArray()
        return json
    }

    @Test
    fun theListIsParsed() {
        val list = PackCatalog.parse(publish(ByteArray(1000) { it.toByte() }, ByteArray(500), split = 400))
        assertEquals("2026-11-01T04:00:00Z", list.generated)
        assertEquals(listOf("luxembourg", "benelux"), list.packs.map { it.id })
        val lux = list.packs[0]
        assertEquals("LU", lux.country)
        assertEquals("packs-20261101", lux.release)
        assertEquals(java.time.Instant.parse("2026-10-31T20:21:31Z"), lux.osmTime)
        assertEquals(gh.url("luxembourg.atlas"), lux.atlasFile!!.url)
        assertEquals("gzip", lux.places!!.compression)
        assertEquals(500L, lux.places!!.rawBytes)
        assertEquals("1", lux.places!!.schema)
        assertEquals(1000L + lux.places!!.bytes, lux.downloadBytes)
        assertEquals(1500L, lux.diskBytes)
        val big = list.packs[1].atlasFile!!
        assertTrue(big.isSplit)
        assertEquals(listOf(400L, 400L, 200L), big.parts.map { it.bytes })
        assertNull(list.packs[1].places)
    }

    @Test
    fun theListIsFetchedThroughTheRedirectAndKeptForOffline() = runBlocking {
        publish(ByteArray(10), ByteArray(10), split = 4)
        val cache = File(dir, "packs.json")
        var now = 0L
        val catalog = PackCatalog(gh.url("packs.json"), cacheFile = cache, clock = { now })
        assertEquals(2, catalog.packs().size)
        assertEquals("Luxembourg", catalog.pack("luxembourg")!!.name)
        assertTrue(cache.isFile)
        // fresh in memory: no second request
        catalog.packs()
        assertEquals(1, gh.githubRequests.size)
        // stale and GitHub down: the copy in memory serves
        now += 7 * 3_600_000L
        gh.down = true
        assertEquals(2, catalog.packs().size)
        // a new catalog (the app restarted) offline: the file serves
        assertEquals(2, PackCatalog(gh.url("packs.json"), cacheFile = cache).packs().size)
        // nothing kept and offline: the error
        assertThrows(IOException::class.java) { runBlocking { PackCatalog(gh.url("packs.json")).packs() } }
        // packs around a point, smallest first
        gh.down = false
        assertEquals(listOf("luxembourg", "benelux"), catalog.covering(LatLon(49.61, 6.13)).map { it.id })
        assertEquals(listOf("benelux"), catalog.covering(LatLon(52.37, 4.89)).map { it.id })
    }

    @Test
    fun filesDownloadVerifiedResumedAndUnpacked() = runBlocking {
        val atlas = ByteArray(300_000) { (it * 7 + it / 1000).toByte() }
        val store = ByteArray(200_000) { (it % 251).toByte() }
        publish(atlas, store, split = 120_000)
        val catalog = PackCatalog(gh.url("packs.json"))
        val dl = PackDownloader()
        val lux = catalog.pack("luxembourg")!!
        val progress = ArrayList<PackDownloader.Progress>()
        val db = dl.download(lux.places!!, File(dir, "lux.db")) { progress += it }
        assertArrayEquals(store, db.readBytes())
        assertEquals(1.0, progress.last().fraction, 0.0)
        assertTrue(!File(dir, "lux.db.download").exists())

        // a split file, interrupted half way through its second part, resumes with a range
        val big = catalog.pack("benelux")!!.atlasFile!!
        val target = File(dir, "big.atlas")
        File(target.path + ".download").writeBytes(atlas.copyOf(180_000))
        gh.storageRequests.clear()
        dl.download(big, target)
        assertArrayEquals(atlas, target.readBytes())
        val asked = gh.storageRequests.map { it.url.encodedPath.substringAfterLast('/') to it.headers["Range"] }
        assertEquals(listOf("big.atlas.001" to "bytes=60000-", "big.atlas.002" to null), asked)

        // a server that ignores ranges: the part starts over, the result is still right
        gh.ignoreRanges = true
        File(target.path + ".download").writeBytes(atlas.copyOf(250_000))
        target.delete()
        dl.download(big, target)
        assertArrayEquals(atlas, target.readBytes())
    }

    @Test
    fun aCorruptDownloadIsRefused() = runBlocking {
        publish(ByteArray(5000) { it.toByte() }, ByteArray(10))
        val lux = PackCatalog(gh.url("packs.json")).pack("luxembourg")!!
        gh.assets["luxembourg.atlas"] = ByteArray(5000) { (it + 1).toByte() }
        val target = File(dir, "bad.atlas")
        assertThrows(PackDownloader.ChecksumException::class.java) { runBlocking { PackDownloader().download(lux.atlasFile!!, target) } }
        assertTrue(!File(target.path + ".download").exists())
        assertTrue(!target.exists())
    }

    @Test
    fun aRangeAnsweredFromTheStartRestartsThePart() = runBlocking {
        val atlas = ByteArray(300_000) { (it * 11).toByte() }
        publish(atlas, ByteArray(10), split = 120_000)
        val big = PackCatalog(gh.url("packs.json")).pack("benelux")!!.atlasFile!!
        val target = File(dir, "confused.atlas")
        File(target.path + ".download").writeBytes(atlas.copyOf(180_000))
        gh.rangeFromStart = true
        PackDownloader().download(big, target)
        assertArrayEquals(atlas, target.readBytes())
    }

    @Test
    fun aPartialDownloadOfAnotherFileIsStartedOver() = runBlocking {
        val old = ByteArray(1000) { it.toByte() }
        val new = ByteArray(1000) { (it * 3).toByte() }
        // the old file's download breaks off at 600 bytes, and the new one replaces it before it is resumed
        gh.assets["v1.atlas"] = old.copyOf(600)
        gh.assets["v2.atlas"] = new
        val v1 = PackFile(PackFile.ATLAS, "x.atlas", 1000, sha(old), listOf(PackPart(gh.url("v1.atlas"), 1000, null)))
        val v2 = PackFile(PackFile.ATLAS, "x.atlas", 1000, sha(new), listOf(PackPart(gh.url("v2.atlas"), 1000, null)))
        val target = File(dir, "x.atlas")
        assertThrows(IOException::class.java) { runBlocking { PackDownloader().download(v1, target) } }
        assertEquals(600L, File(target.path + ".download").length())
        gh.storageRequests.clear()
        PackDownloader().download(v2, target)
        assertArrayEquals(new, target.readBytes())
        // fetched whole, not finished on the old file's bytes
        assertEquals(listOf<String?>(null), gh.storageRequests.map { it.headers["Range"] })
        assertTrue(!File(target.path + ".download.id").exists())
    }

    @Test
    fun aStoreThatUnpacksToTheWrongSizeIsRefused() = runBlocking {
        publish(ByteArray(10), ByteArray(5000) { it.toByte() })
        val places = PackCatalog(gh.url("packs.json")).pack("luxembourg")!!.places!!.copy(rawBytes = 4999)
        val target = File(dir, "short.db")
        val e = assertThrows(IOException::class.java) { runBlocking { PackDownloader().download(places, target) } }
        assertTrue(e.message!!.contains("4999"))
        assertTrue(!target.exists())
        assertTrue(!File(target.path + ".unpacking").exists())
    }

    @Test(timeout = 30_000)
    fun aStalledDownloadStopsWhenCancelled() = runBlocking {
        // a connection that trickles a byte every two seconds: a read never times out, so only cancelling ends it
        MockWebServer().use { slow ->
            slow.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(200))).throttleBody(1, 2, TimeUnit.SECONDS).build())
            slow.start()
            val f = PackFile(PackFile.ATLAS, "slow.atlas", 200, null, listOf(PackPart(slow.url("/slow.atlas").toString(), 200, null)))
            val started = System.nanoTime()
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(500) { PackDownloader().download(f, File(dir, "slow.atlas")) } }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue("stopped after $seconds s", seconds < 5)
        }
    }

    @Test
    fun theDefaultListIsTheLatestRelease() {
        assertEquals("https://github.com/atlas-maps-app/Atlas/releases/download/packs-latest/packs.json", PackCatalog.DEFAULT_URL)
    }

    @Test
    fun theRealListWhenOnline() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("atlas.online") == "true")
        val list = try {
            PackCatalog().list()
        } catch (e: HttpStatusException) {
            // the workflow has not published yet
            org.junit.Assume.assumeTrue("no packs published yet (HTTP ${e.code})", e.code != 404)
            throw e
        }
        assertTrue(list.packs.isNotEmpty())
        assertTrue(list.packs.all { p -> p.files.all { it.kind == PackFile.ATLAS || it.kind == PackFile.PLACES } })
    }
}
