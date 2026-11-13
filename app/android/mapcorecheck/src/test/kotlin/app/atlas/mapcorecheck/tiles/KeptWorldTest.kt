package app.atlas.mapcorecheck.tiles

import app.atlas.mapcore.KeptWorld
import app.atlas.mapcore.Native
import app.atlas.mapview.vk.WorldKeeper
import app.atlas.packer.world.LandBase
import app.atlas.packer.world.StagePTags
import app.atlas.packer.world.WorldCut
import app.atlas.packer.world.WorldGeometry
import app.atlas.packer.world.WorldPack
import app.atlas.packer.world.WorldTiles
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldFetch
import app.atlas.tiles.WorldIndex
import app.atlas.tiles.WorldKeepList
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections

/**
 * The world a kept country keeps (ENGINE.md step 11), the app's keeper (mapview's WorldKeeper over tiles' WorldFetch)
 * against the core's own cache (the host build: tiles/Kept.hpp through the façade) and a server on this machine that
 * serves two releases of a small world as GitHub serves a release's files, by ranges: world packs the packer's own
 * world code writes (its land, tiles, packs, parts and index), so the heads the keeper fetches open in the core and
 * the chunks it fetches are the ones their heads name.
 *
 * Held: a country's keep comes whole into the cache, pinned out of a budget far smaller than it, across a connection
 * that drops, and is all there offline; a new version waits beside the phone's world while its keep is fetched, the
 * old one's whole on the phone the whole time (a fetch that fails included), a chunk both versions share is not fetched
 * again, and once the new one's is whole the phone takes it and lets the old one's go; a country no longer kept
 * leaves nothing pinned, and a new version then replaces the phone's at once.
 */
class KeptWorldTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }

        /**
         * The world cache's budget here: far under what the small world's country keeps (its chunks alone weigh some
         * 2.8 kB, its heads more), so a whole keep is held only by its pins, which the budget does not count.
         */
        const val BUDGET = 512L

        const val NL = "netherlands"
    }

    private val root: File = Files.createTempDirectory("atlas-keptworld").toFile()
    private val server = Served()
    private var clock = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()

    @After
    fun close() {
        server.close()
        root.deleteRecursively()
    }

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** A release of the world as the server holds it: its files by name, its index and its packs' bytes by their entries. */
    private class Release(val tag: String, val files: Map<String, ByteArray>, val index: WorldIndex, val packs: Map<WorldIndex.Entry, ByteArray>, val keepBytes: Long, val tiles: Int)

    /**
     * A world of one fine pack (block 6/32/21: tiles of L9 to L11) and one coarse pack (block 3/4/2: L6 to L8) about
     * the Netherlands, land all over but, with [lake], a lake in the L11 tile 1050/673, so that tile and the ones over
     * it differ from the world without the lake and its neighbours do not. Its keep list keeps every tile of both
     * packs for the Netherlands.
     */
    private fun release(tag: String, lake: Boolean): Release {
        val dir = File(root, tag).apply { mkdirs() }
        val f = WorldGeometry.FLOAT
        fun box(w: Double, s: Double, e: Double, n: Double): Geometry =
            f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))
        var ground = box(-2.0, 46.0, 12.0, 58.0)
        if (lake) ground = ground.difference(box(4.64, 52.30, 4.68, 52.34))
        val land = LandBase(listOf(WorldGeometry.worldOfDegrees(ground)))
        val cut = WorldCut(StagePTags.STAGE_P)
        fun tile(z: Int, x: Int, y: Int): Format3Writer.Encoded {
            val pieces = land.cut(z, x, y)
            return checkNotNull(WorldTiles.fitted(cut, z, x, y, emptyList()) { t -> land.land(z, x, y, pieces, t) }.encoded) { "no tile $z/$x/$y" }
        }
        val fine = listOf(tile(9, 262, 168), tile(10, 525, 336), tile(10, 524, 336), tile(11, 1050, 673), tile(11, 1051, 673))
        val coarse = listOf(tile(6, 32, 21), tile(7, 65, 42), tile(8, 131, 84), tile(8, 130, 84))
        val keys = mapOf(
            Triple(6, 32, 21) to listOf(Triple(9, 262, 168), Triple(10, 525, 336), Triple(10, 524, 336), Triple(11, 1050, 673), Triple(11, 1051, 673)),
            Triple(3, 4, 2) to listOf(Triple(6, 32, 21), Triple(7, 65, 42), Triple(8, 131, 84), Triple(8, 130, 84)),
        )
        val source = "0".repeat(64)
        val fineFile = File(dir, "fine.atlas")
        WorldPack.write(fine, WorldPack.Spec("world-mid/6/32/21", 6, 32, 21, WorldTiles.FINE, null, listOf("a test's land"), source), fineFile)
        val coarseFile = File(dir, "coarse.atlas")
        WorldPack.write(coarse, WorldPack.Spec("world-coarse/3/4/2", 3, 4, 2, WorldTiles.COARSE, null, listOf("a test's land"), source), coarseFile)
        val parts = ArrayList<WorldIndex.Part>()
        val entries = WorldPack.parts(
            dir, "world-mid",
            listOf(fineFile to { part: Int, offset: Long, length: Long, s: ByteArray -> WorldIndex.Entry(6, 32, 21, 9, 11, part, offset, length, fine.size, Grid.tileBox(6, 32, 21), s) }),
            0, parts,
        ) + WorldPack.parts(
            dir, WorldTiles.COARSE_PARTS,
            listOf(coarseFile to { part: Int, offset: Long, length: Long, s: ByteArray -> WorldIndex.Entry(3, 4, 2, 6, 8, part, offset, length, coarse.size, Grid.tileBox(3, 4, 2), s) }),
            parts.size, parts,
        )
        val index = WorldIndex(Instant.parse("2026-11-02T21:21:26Z"), parts, entries)
        val idx = ByteArrayOutputStream().also { index.write(it) }.toByteArray()
        // the globe is fetched whole and never opened here: any bytes of its length and SHA-256
        val globe = ByteArray(3_000) { (it * 7 + tag.length).toByte() }
        val packs = mapOf(index.find(6, 32, 21)!! to fineFile.readBytes(), index.find(3, 4, 2)!! to coarseFile.readBytes())
        var keepBytes = 0L
        val listed = packs.entries.joinToString(",\n") { (e, bytes) ->
            val (start, end) = WorldFetch.chunksOf(bytes, bytes.size.toLong())
            val head = bytes.size - (end - start)
            keepBytes += bytes.size
            val levels = keys.getValue(Triple(e.z, e.x, e.y)).groupBy { it.first }.entries
                .joinToString(", ") { (z, ts) -> "\"$z\": [${ts.joinToString(", ") { "${it.second}, ${it.third}" }}]" }
            """{"z": ${e.z}, "x": ${e.x}, "y": ${e.y}, "head": $head, "bytes": ${end - start}, "tiles": {$levels}}"""
        }
        val keep = """{"format": 1, "release": "$tag", "index": {"bytes": ${idx.size}, "sha256": "${hex(sha(idx))}"},
            "countries": [{"id": "$NL", "name": "Netherlands", "bytes": $keepBytes, "tiles": ${fine.size + coarse.size}, "packs": [$listed]}]}"""
        fun file(name: String, b: ByteArray) = """{"name": "$name", "bytes": ${b.size}, "sha256": "${hex(sha(b))}"}"""
        val json = """{"format": 1, "release": "$tag", "url": "${server.url}$tag/", "date": "2026-11-02T21:21:26Z", "profile": "world",
            "index": ${file(WorldFetch.INDEX, idx)}, "globe": ${file(WorldFetch.GLOBE, globe)}, "parts": []}"""
        val files = HashMap<String, ByteArray>()
        files[WorldFetch.JSON] = json.toByteArray()
        files[WorldFetch.INDEX] = idx
        files[WorldFetch.GLOBE] = globe
        files[WorldKeepList.NAME] = keep.toByteArray()
        for (p in parts) files[p.name] = File(dir, p.name).readBytes()
        return Release(tag, files, index, packs, keepBytes, fine.size + coarse.size)
    }

    /** Publishes [r]: its files under its tag, and world-latest's world.json naming it. */
    private fun publish(r: Release) {
        for ((name, bytes) in r.files) server.files["${r.tag}/$name"] = bytes
        server.files["world-latest/${WorldFetch.JSON}"] = r.files.getValue(WorldFetch.JSON)
    }

    /** What the core is asked to keep of [r] for the Netherlands: every tile of its packs. */
    private fun kept(r: Release): List<KeptWorld.Pack> {
        val list = WorldKeepList.read(String(r.files.getValue(WorldKeepList.NAME)))
        return list.country(NL)!!.packs.map { p ->
            val e = r.index.find(p.z, p.x, p.y)!!
            KeptWorld.Pack(e.length, e.sha256, p.tiles.map { KeptWorld.key(it.z, it.x, it.y) }.toLongArray())
        }
    }

    /** The cache's pins as it wrote them (its `pinned` file's lines). */
    private fun pins(cache: File): Int = File(cache, "pinned").takeIf { it.isFile }?.readLines()?.count { it.isNotBlank() } ?: 0

    @Test
    fun aKeptCountrysWorldComesWholeAndANewVersionTakesItsPlaceOnlyOnceItsOwnIs() {
        val v1 = release("world-20261102", lake = false)
        val v2 = release("world-20261201", lake = true)
        publish(v1)
        val world = File(root, "phone/world")
        val cache = File(root, "phone/cache").apply { mkdirs() }
        val said = Collections.synchronizedList(ArrayList<String>())
        var countries = emptySet<String>()
        var adopted = 0
        val fetch = WorldFetch(world, server.url + "world-latest/", now = { clock }, drop = { error("no world is replaced without its keep here") }, hold = { countries.isNotEmpty() })
        val keeper = WorldKeeper(fetch, cache, budget = { BUDGET }, said = { said += it }, adopted = { adopted++ })
        assertTrue(fetch.start())
        assertEquals(v1.keepBytes, keeper.bytes(NL))
        assertNull(keeper.bytes("belgium"))

        // no country kept: nothing pinned, nothing fetched; a country the world lists nothing for keeps nothing
        val asked = server.requests.size
        assertTrue(keeper.keep(emptySet()).whole)
        assertEquals(emptySet<String>(), keeper.keep(setOf("belgium")).listed)
        assertEquals(0, pins(cache))
        assertEquals(asked, server.requests.size)

        // the Netherlands kept, the phone on a metered network (nothing fetched): its two heads pinned and lacking
        countries = setOf(NL)
        val bare = keeper.keep(countries, fetch = false)
        assertEquals(setOf(NL), bare.listed)
        assertEquals(2, bare.lacking)
        assertEquals(2, pins(cache))
        assertEquals(asked, server.requests.size)
        // the connection drops after the first head: the keep is not whole, and says so
        server.failAfter = server.requests.size + 2
        assertThrows(IOException::class.java) { keeper.keep(countries) }
        server.failAfter = Int.MAX_VALUE
        assertFalse(keeper.keep(countries, fetch = false).whole)
        // again: what came stays, the rest comes, and every tile of the list is on the phone, pinned
        val reports = ArrayList<Float>()
        val whole = keeper.keep(countries) { reports += it }
        assertTrue(whole.whole)
        assertTrue(whole.fetched > 0)
        assertEquals(1f, reports.last())
        assertTrue(reports.toString(), reports.zipWithNext().all { (a, b) -> a <= b })
        val state1 = KeptWorld.pin(cache.path, BUDGET, kept(v1))!!
        assertEquals(v1.tiles, state1.held)
        assertEquals(0, state1.unknown)
        assertTrue(state1.heads.isEmpty() && state1.chunks.isEmpty())
        assertEquals(2 + v1.tiles, pins(cache))
        // several times the cache's budget, which does not count it
        assertTrue("${state1.heldBytes} bytes kept in a budget of $BUDGET", state1.heldBytes > 4 * BUDGET)
        assertTrue(Native.worldHeld(cache.path, BUDGET, v1.packs.keys.flatMap { it.sha256.toList() }.toByteArray())!!.all { it })
        // offline: the keep is whole without a request, in this start of the app and the next
        server.down = true
        val before = server.requests.size
        assertTrue(keeper.keep(countries).whole)
        assertEquals(before, server.requests.size)
        val restarted = WorldFetch(world, server.url + "world-latest/", now = { clock }, hold = { true })
        assertTrue(restarted.start())
        assertTrue(WorldKeeper(restarted, cache, budget = { BUDGET }).keep(countries).whole)
        server.down = false

        // a new version of the world: it waits, and the phone's world and its keep stay as they were
        publish(v2)
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals(v1.tag, fetch.world!!.release.tag)
        assertEquals(v2.tag, fetch.next!!.release.tag)
        val waiting = keeper.keep(countries, fetch = false)
        assertFalse(waiting.whole)
        assertEquals(0, adopted)
        fun oldWhole() {
            val s = KeptWorld.pin(cache.path, BUDGET, kept(v1) + kept(v2))!!
            assertTrue("the old world's keep lacks ${s.heads.count { it < 2 }} heads and ${s.chunks.count { it.pack < 2 }} chunks", s.heads.none { it < 2 } && s.chunks.none { it.pack < 2 })
            assertTrue(Native.worldHeld(cache.path, BUDGET, v1.packs.keys.flatMap { it.sha256.toList() }.toByteArray())!!.all { it })
        }
        oldWhole()
        // its keep's fetch fails: the old world still the phone's, its keep still whole
        server.failAfter = server.requests.size
        assertThrows(IOException::class.java) { keeper.keep(countries) }
        server.failAfter = Int.MAX_VALUE
        assertEquals(v1.tag, fetch.world!!.release.tag)
        assertEquals(0, adopted)
        oldWhole()
        assertArrayEquals(v1.files.getValue(WorldFetch.INDEX), File(world, WorldFetch.INDEX).readBytes())
        // only the new version's files are asked for from then on
        val from = server.requests.size
        val taken = keeper.keep(countries)
        assertTrue(taken.whole)
        assertTrue(server.requests.drop(from).toString(), server.requests.drop(from).all { it.startsWith(v2.tag + "/") })
        // whole: the phone takes the new world, its keep alone pinned
        assertEquals(1, adopted)
        assertEquals(v2.tag, fetch.world!!.release.tag)
        assertNull(fetch.next)
        assertArrayEquals(v2.files.getValue(WorldFetch.INDEX), File(world, WorldFetch.INDEX).readBytes())
        val state2 = KeptWorld.pin(cache.path, BUDGET, kept(v2))!!
        assertEquals(v2.tiles, state2.held)
        assertTrue(state2.heads.isEmpty() && state2.chunks.isEmpty())
        // a tile the lake does not touch is the same chunk in both versions, on the phone already: what the keeper
        // fetched for the new version (its heads, and the chunks the cache lacked) is less than its keep weighs
        assertTrue(said.toString(), said.any { it.startsWith("the keep of $NL in ${v2.tag}") })
        assertTrue("${taken.fetched} bytes fetched of a keep of ${v2.keepBytes}", taken.fetched in 1 until v2.keepBytes)
        val v1Fine = v1.packs.keys.first { it.z == 6 }
        val v2Fine = v2.packs.keys.first { it.z == 6 }
        assertFalse("the lake changes the fine pack", v1Fine.sha256.contentEquals(v2Fine.sha256))
        // the old world's own heads are gone from the cache (a pack both versions hold alike stays, being the new one's too)
        for ((e, _) in v1.packs) {
            val same = v2.packs.keys.any { it.sha256.contentEquals(e.sha256) }
            assertEquals("the head of ${e.z}/${e.x}/${e.y} of the old world", same, Native.worldHeld(cache.path, BUDGET, e.sha256)!![0])
        }
        // offline at once after: the new world's keep is whole
        server.down = true
        assertTrue(keeper.keep(countries).whole)
        server.down = false

        // the country deleted: nothing pinned any more
        countries = emptySet()
        assertTrue(keeper.keep(countries).whole)
        assertEquals(0, pins(cache))
    }

    @Test
    fun aNewVersionIsTakenAtOnceWhereNothingOfTheOldOneIsKeptAnyMore() {
        val v1 = release("world-20261102", lake = false)
        val v2 = release("world-20261201", lake = true)
        publish(v1)
        val world = File(root, "phone/world")
        val cache = File(root, "phone/cache2").apply { mkdirs() }
        var adopted = 0
        val fetch = WorldFetch(world, server.url + "world-latest/", now = { clock }, hold = { true })
        val keeper = WorldKeeper(fetch, cache, budget = { BUDGET }, adopted = { adopted++ })
        assertTrue(fetch.start())
        assertTrue(keeper.keep(setOf(NL)).whole)
        publish(v2)
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals(v2.tag, fetch.next!!.release.tag)
        // the country was deleted meanwhile: the new world at once, the old one's heads and chunks dropped, none pinned
        assertTrue(keeper.keep(emptySet()).whole)
        assertEquals(1, adopted)
        assertEquals(v2.tag, fetch.world!!.release.tag)
        assertEquals(0, pins(cache))
        assertTrue(Native.worldHeld(cache.path, BUDGET, v1.packs.keys.flatMap { it.sha256.toList() }.toByteArray())!!.none { it })
        // kept again: the new world's keep
        assertTrue(keeper.keep(setOf(NL)).whole)
        assertEquals(v2.tiles, KeptWorld.pin(cache.path, BUDGET, kept(v2))!!.held)
    }

    /** A server on this machine: files by path, whole or by one range, as a release's assets come; what was asked. */
    private class Served : AutoCloseable {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val requests: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** Every request is refused (the phone offline, as far as a fetch can tell). */
        @Volatile var down = false

        /** Requests from this one on (counted from the first) are refused: a connection that drops. */
        @Volatile var failAfter = Int.MAX_VALUE

        private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                val path = x.requestURI.path.removePrefix("/")
                val n = synchronized(requests) { requests += path; requests.size }
                val body = files[path]
                val range = x.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                when {
                    down || n > failAfter -> x.sendResponseHeaders(503, -1)
                    body == null -> x.sendResponseHeaders(404, -1)
                    range != null -> {
                        val from = range.groupValues[1].toInt()
                        val to = (range.groupValues[2].toIntOrNull() ?: body.size - 1).coerceAtMost(body.size - 1)
                        x.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                        x.sendResponseHeaders(206, (to - from + 1).toLong())
                        x.responseBody.use { it.write(body, from, to - from + 1) }
                    }
                    else -> {
                        x.sendResponseHeaders(200, body.size.toLong())
                        x.responseBody.use { it.write(body) }
                    }
                }
                x.close()
            }
            start()
        }

        val url: String = "http://127.0.0.1:${http.address.port}/"

        override fun close() = http.stop(0)
    }
}
