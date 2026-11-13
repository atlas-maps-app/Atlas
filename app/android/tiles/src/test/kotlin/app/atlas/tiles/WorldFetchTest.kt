package app.atlas.tiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/**
 * The world's files on the phone (ENGINE.md step 6, "Fetch and keep"), from GitHub's releases faked by MockWebServer:
 * world.json from world-latest at most once a day, the index and the globe of the release it names at the first start
 * and at each new version (which drops the old one), each whole and of the SHA-256 world.json names before it is put
 * in place; the phone's world going on while world-latest does not answer; a pack's head by ranges, its bytes but its
 * chunks; and every request saying only who is asking.
 */
class WorldFetchTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("worldfetch")
    private val world = File(dir, "world")
    private val latest = gh.github.url("/atlas-maps-app/Atlas/releases/download/world-latest/").toString()
    private var clock = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
    private var drops = 0

    /** Amsterdam: its L6 tile (32, 21) and its L3 tile (4, 2); the open North Atlantic, which no pack holds. */
    private val amsterdam = 52.37 to 4.89
    private val ocean = 50.0 to -30.0

    @After
    fun close() {
        gh.close()
        dir.deleteRecursively()
    }

    private fun fetch() = WorldFetch(world, latest, now = { clock }, drop = { drops++ })

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /**
     * A pack's container as the packer writes it: the header, a manifest of [manifest] bytes, the directory, then
     * [sections] (each its name and length) in that order, each section's bytes its own.
     */
    private fun pack(manifest: Int, vararg sections: Pair<String, Int>): ByteArray {
        val names = sections.map { it.first.toByteArray() }
        val directory = names.sumOf { 1 + it.size + 29 }
        var at = 20L + manifest + directory
        val entries = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        for ((i, s) in sections.withIndex()) {
            entries.write(names[i].size)
            entries.write(names[i])
            entries.write(ByteBuffer.allocate(29).putLong(at).putLong(s.second.toLong()).putLong(s.second.toLong()).putInt(0).put(0).array())
            data.write(ByteArray(s.second) { (i * 31 + it * 7).toByte() })
            at += s.second
        }
        val header = ByteBuffer.allocate(20).put("ATLASPCK".toByteArray()).put(0).put(3).put(0).put(0).putInt(manifest).putInt(directory)
        return header.array() + ByteArray(manifest) { 'm'.code.toByte() } + entries.toByteArray() + data.toByteArray()
    }

    private class Published(val index: ByteArray, val globe: ByteArray, val packs: List<ByteArray>)

    /**
     * A release [tag] of the world as world.yml publishes it, and world-latest's world.json naming it: two fine packs
     * in one part and a coarse one in another, the index, the globe ([seed] makes another version's bytes).
     */
    private fun publish(tag: String, seed: Int = 0): Published {
        val fine = listOf(
            pack(100, "tiles" to 900, "reach" to 50, "chunks" to 3_000 + seed, "rhead" to 40, "style" to 300),
            pack(80, "tiles" to 500, "chunks" to 2_000, "style" to 200),
        )
        val coarse = pack(60, "tiles" to 300, "chunks" to 1_500)
        val mid = fine[0] + fine[1]
        val entries = listOf(
            WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, fine[0].size.toLong(), 85, intArrayOf(1, 2, 3, 4), sha(fine[0])),
            WorldIndex.Entry(6, 33, 21, 9, 11, 0, fine[0].size.toLong(), fine[1].size.toLong(), 85, intArrayOf(1, 2, 3, 4), sha(fine[1])),
            WorldIndex.Entry(3, 4, 2, 6, 8, 1, 0, coarse.size.toLong(), 84, intArrayOf(1, 2, 3, 4), sha(coarse)),
        )
        val parts = listOf(WorldIndex.Part("world-mid.000", mid.size.toLong()), WorldIndex.Part("world-coarse.000", coarse.size.toLong()))
        val index = ByteArrayOutputStream().also { WorldIndex(Instant.parse("2026-11-02T21:21:26Z"), parts, entries).write(it) }.toByteArray()
        val globe = ByteArray(4_000 + seed) { (it * 3 + 5 + seed).toByte() }
        gh.assets[WorldFetch.JSON] = json(tag, index, globe)
        gh.assets[WorldFetch.INDEX] = index
        gh.assets["world-mid.000"] = mid
        gh.assets["world-coarse.000"] = coarse
        gh.assets[WorldFetch.GLOBE] = globe
        return Published(index, globe, fine + coarse)
    }

    /** world-latest's world.json for release [tag] (its files on the fake GitHub, as world.yml's publish writes it). */
    private fun json(tag: String, index: ByteArray, globe: ByteArray, url: String = gh.github.url("/atlas-maps-app/Atlas/releases/download/$tag/").toString()): ByteArray {
        fun file(name: String, b: ByteArray) = """{"name": "$name", "bytes": ${b.size}, "sha256": "${hex(sha(b))}"}"""
        return """{"format": 1, "release": "$tag", "url": "$url", "date": "2026-11-02T21:21:26Z", "profile": "world",
            "index": ${file(WorldFetch.INDEX, index)}, "globe": ${file(WorldFetch.GLOBE, globe)}, "parts": []}""".toByteArray()
    }

    /** What was asked of the releases, but the keep list ([keepRequested]: asked with each release and once a day while it has none). */
    private fun requested(): List<String> = everyRequest().filterNot { it.endsWith("/" + WorldKeepList.NAME) }

    private fun keepRequested(): List<String> = everyRequest().filter { it.endsWith("/" + WorldKeepList.NAME) }

    private fun everyRequest(): List<String> = gh.githubRequests.map { it.url.encodedPath.removePrefix("/atlas-maps-app/Atlas/releases/download/") }

    /** A keep list of the world whose index is [index]: the Netherlands' two fine packs' tiles and its coarse pack's. */
    private fun keepList(index: ByteArray, release: String = "world-20261102"): ByteArray = """
        {"format": 1, "release": "$release", "index": {"bytes": ${index.size}, "sha256": "${hex(sha(index))}"},
         "about": "the world's tiles a kept country draws offline", "reach": {"6": 2548000, "11": 111000},
         "countries": [
           {"id": "netherlands", "name": "Netherlands", "country": "NL", "area": "poly", "bytes": 6400, "heads": 2990, "tiles": 5,
            "levels": {"6": {"tiles": 1, "bytes": 400}},
            "packs": [
              {"z": 6, "x": 32, "y": 21, "head": 1390, "bytes": 3000, "tiles": {"9": [262, 168], "11": [1050, 673, 1051, 673]}},
              {"z": 3, "x": 4, "y": 2, "head": 380, "bytes": 1200, "tiles": {"6": [32, 21], "7": [65, 42]}}
            ]},
           {"id": "luxembourg", "name": "Luxembourg", "bytes": 100, "tiles": 0, "packs": []}
         ]}
    """.trimIndent().toByteArray()

    private fun assertOnlyWhoIsAsking() {
        for (r in gh.githubRequests + gh.storageRequests) {
            assertEquals(Http.USER_AGENT, r.headers["User-Agent"])
            assertNull(r.headers["Authorization"])
            assertNull(r.headers["Cookie"])
            assertFalse(r.url.toString().contains("@"))
        }
    }

    @Test
    fun theReleaseWorldLatestNamesComesAtTheFirstStartAndWorldJsonOnceADay() {
        val p = publish("world-20261102")
        val fetch = fetch()
        assertNull(fetch.world)
        assertTrue(fetch.start())
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        assertEquals(3, fetch.index!!.entries.size)
        assertEquals(listOf("world-latest/world.json", "world-20261102/world.idx", "world-20261102/world-globe.atlas"), requested())
        // the same day asks nothing, nor does a second start of the app
        assertFalse(fetch.start())
        val next = fetch()
        assertTrue(next.start())
        assertEquals("world-20261102", next.world!!.release.tag)
        assertEquals(3, requested().size)
        // a day later world.json is asked for again: the same release, so nothing more comes and nothing is dropped
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.due())
        assertFalse(fetch.start())
        assertEquals(4, requested().size)
        assertEquals("world-latest/world.json", requested().last())
        assertFalse(fetch.due())
        assertEquals(0, drops)
        // a clock gone back asks again
        clock -= 2 * WorldFetch.DAY_MS
        assertTrue(fetch.due())
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") || it.name.endsWith(".new") })
        assertOnlyWhoIsAsking()
    }

    @Test
    fun theKeepListComesWithTheWorldWhereItsReleaseHasOne() {
        // a release without one: asked for once, the world goes on without it, and not asked again that day
        val p = publish("world-20261102")
        val fetch = fetch()
        assertTrue(fetch.start())
        assertNull(fetch.keep)
        assertEquals(listOf("world-20261102/world-keep.json"), keepRequested())
        assertFalse(fetch.start())
        assertTrue(fetch().start())
        assertEquals(1, keepRequested().size)
        // world.yml's refs job adds it to the release: it comes with the next day's look, and the start says so
        gh.assets[WorldKeepList.NAME] = keepList(p.index)
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        val keep = fetch.keep!!
        assertEquals("world-20261102", keep.release)
        assertEquals(listOf("netherlands", "luxembourg"), keep.countries.map { it.id })
        val nl = keep.country("netherlands")!!
        assertEquals(6400L, nl.bytes)
        assertEquals(5, nl.tiles)
        assertEquals(listOf(WorldKeepList.Tile(9, 262, 168), WorldKeepList.Tile(11, 1050, 673), WorldKeepList.Tile(11, 1051, 673)), nl.packs[0].tiles)
        assertEquals(listOf(6, 32, 21), nl.packs[0].let { listOf(it.z, it.x, it.y) })
        assertEquals(1390L, nl.packs[0].head)
        assertNull(keep.country("belgium"))
        // on the phone from then on: nothing more asked that day or the next, nor by the app's next start
        assertFalse(fetch.start())
        clock += WorldFetch.DAY_MS
        assertFalse(fetch.start())
        val next = fetch()
        assertTrue(next.start())
        assertEquals("netherlands", next.keep!!.countries[0].id)
        assertEquals(2, keepRequested().size)
        // a new version's list is its own: the old one's is not used, the new one's comes with it
        val q = publish("world-20261201", seed = 9)
        gh.assets[WorldKeepList.NAME] = keepList(p.index) // still the old world's: another index
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertNull(fetch.keep)
        assertFalse(File(world, WorldKeepList.NAME).exists())
        gh.assets[WorldKeepList.NAME] = keepList(q.index, "world-20261201")
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.keep!!.release)
        assertEquals(listOf("world-20261102/world-keep.json", "world-20261102/world-keep.json", "world-20261201/world-keep.json", "world-20261201/world-keep.json"), keepRequested())
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") })
        assertOnlyWhoIsAsking()
    }

    @Test
    fun aKeepListThatIsNotOneIsRefused() {
        val index = ByteArray(10) { it.toByte() }
        val good = String(keepList(index))
        assertEquals(2, WorldKeepList.read(good).countries.size)
        for (bad in listOf(
            good.replace("\"format\": 1", "\"format\": 2"),
            good.replace(hex(sha(index)), "abc"),
            good.replace("\"countries\"", "\"lands\""),
            good.replace("[262, 168]", "[262]"),
            good.replace("[262, 168]", "[262, 512]"), // no tile 512 at level 9
            good.replace("\"9\": [262, 168]", "\"nine\": [262, 168]"),
            good.replace("\"z\": 6, ", ""),
            "[]",
        )) {
            assertTrue(bad, runCatching { WorldKeepList.read(bad) }.isFailure)
        }
    }

    @Test
    fun aNewVersionReplacesTheOldOneAndDropsItsHeadsAndChunks() {
        publish("world-20261102")
        val fetch = fetch()
        fetch.start()
        val p = publish("world-20261201", seed = 9)
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.world!!.release.tag)
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertArrayEquals(sha(p.packs[0]), fetch.index!!.find(6, 32, 21)!!.sha256)
        assertEquals(1, drops)
        assertEquals(listOf("world-latest/world.json", "world-20261201/world.idx", "world-20261201/world-globe.atlas"), requested().takeLast(3))
        // the next start of the app finds the new version whole: nothing more asked, nothing dropped again
        val asked = requested().size
        assertTrue(fetch().start())
        assertEquals(asked, requested().size)
        assertEquals(1, drops)
    }

    @Test
    fun aNewVersionWaitsBesideAKeptWorldUntilItIsTaken() {
        // ENGINE.md step 11: a phone that keeps a country's world never stands without one at a new version
        val p = publish("world-20261102")
        gh.assets[WorldKeepList.NAME] = keepList(p.index)
        var held = true
        fun holding() = WorldFetch(world, latest, now = { clock }, drop = { drops++ }, hold = { held })
        val fetch = holding()
        assertTrue(fetch.start())
        assertNull(fetch.next)
        // a new version, whose release lists no keep yet: its index and globe come beside the phone's, which stay
        val q = publish("world-20261201", seed = 9)
        gh.assets.remove(WorldKeepList.NAME)
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        assertEquals("world-20261102", fetch.keep!!.release)
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertEquals("world-20261201", fetch.next!!.release.tag)
        assertNull(fetch.nextKeep)
        assertArrayEquals(q.index, File(world, WorldFetch.INDEX + ".next").readBytes())
        assertArrayEquals(q.globe, File(world, WorldFetch.GLOBE + ".next").readBytes())
        assertEquals(0, drops)
        assertEquals(listOf("world-latest/world.json", "world-20261201/world.idx", "world-20261201/world-globe.atlas"), requested().takeLast(3))
        assertEquals("world-20261201/world-keep.json", keepRequested().last())
        // a pack's bytes come from the world asked: the phone's own, or the one that waits
        val e = fetch.index!!.find(6, 33, 21)!!
        fetch.range(e, 0, 10)
        assertEquals("world-20261102/world-mid.000", everyRequest().last())
        fetch.range(fetch.next!!.index.find(6, 33, 21)!!, 0, 10, fetch.next)
        assertEquals("world-20261201/world-mid.000", everyRequest().last())
        fetch.head(fetch.next!!.index.find(3, 4, 2)!!, fetch.next)
        assertEquals("world-20261201/world-coarse.000", everyRequest().last())
        // the same day nothing more is asked, nor by the app's next start, which finds both worlds
        val asked = everyRequest().size
        assertFalse(fetch.start())
        val again = holding()
        assertTrue(again.start())
        assertEquals("world-20261102", again.world!!.release.tag)
        assertEquals("world-20261102", again.keep!!.release)
        assertEquals("world-20261201", again.next!!.release.tag)
        assertEquals(asked, everyRequest().size)
        // offline a day later: both go on
        gh.down = true
        clock += WorldFetch.DAY_MS
        assertFalse(fetch.start())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        assertEquals("world-20261201", fetch.next!!.release.tag)
        gh.down = false
        // world.yml's refs job adds the new version's list: it comes with the next look, and the start says so
        gh.assets[WorldKeepList.NAME] = keepList(q.index, "world-20261201")
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.nextKeep!!.release)
        assertEquals("world-20261102", fetch.keep!!.release)
        assertEquals("world-20261102", fetch.world!!.release.tag)
        // taken (the app held its keep whole): the new world's files in place, its list with them, nothing waiting
        assertTrue(fetch.adopt())
        assertEquals("world-20261201", fetch.world!!.release.tag)
        assertEquals("world-20261201", fetch.keep!!.release)
        assertNull(fetch.next)
        assertNull(fetch.nextKeep)
        assertArrayEquals(q.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(q.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertEquals("world-20261201", WorldKeepList.read(File(world, WorldKeepList.NAME).readText()).release)
        assertTrue(world.listFiles()!!.map { it.name }.toString(), world.listFiles()!!.none { it.name.endsWith(".next") || it.name.endsWith(".download") })
        assertFalse(fetch.adopt())
        // the keeper lets the old one's cache go: the fetch's own drop is for a world replaced without a keep
        assertEquals(0, drops)
        // whole for the next start of the app, and the next day's look
        val taken = everyRequest().size
        val later = holding()
        assertTrue(later.start())
        assertEquals("world-20261201", later.world!!.release.tag)
        assertEquals("world-20261201", later.keep!!.release)
        assertNull(later.next)
        assertEquals(taken, everyRequest().size)
        clock += WorldFetch.DAY_MS
        assertFalse(fetch.start())
        assertNull(fetch.next)
        assertOnlyWhoIsAsking()
    }

    @Test
    fun aWorldThatWaitsReplacesThePhonesOnceNoCountryIsKeptAndAFailedOneLeavesItWhole() {
        val p = publish("world-20261102")
        gh.assets[WorldKeepList.NAME] = keepList(p.index)
        var held = true
        val fetch = WorldFetch(world, latest, now = { clock }, drop = { drops++ }, hold = { held })
        assertTrue(fetch.start())
        // a new version whose globe comes cut short: nothing waits, the phone's world and its keep go on
        val q = publish("world-20261201", seed = 9)
        gh.assets[WorldKeepList.NAME] = keepList(q.index, "world-20261201")
        gh.assets[WorldFetch.GLOBE] = q.globe.copyOf(q.globe.size - 1)
        clock += WorldFetch.DAY_MS
        assertThrows(IOException::class.java) { fetch.start() }
        assertNull(fetch.next)
        assertEquals("world-20261102", fetch.world!!.release.tag)
        assertEquals("world-20261102", fetch.keep!!.release)
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        // and for the app's next start, offline
        gh.down = true
        val offline = WorldFetch(world, latest, now = { clock }, hold = { held })
        assertThrows(IOException::class.java) { offline.start() }
        assertEquals("world-20261102", offline.world!!.release.tag)
        assertEquals("world-20261102", offline.keep!!.release)
        gh.down = false
        // whole again: it waits
        gh.assets[WorldFetch.GLOBE] = q.globe
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.next!!.release.tag)
        assertEquals("world-20261201", fetch.nextKeep!!.release)
        assertEquals(0, drops)
        // the country is deleted before the new keep is whole: the next start replaces the phone's world, as any new
        // version does where none is kept, without fetching the index and globe again
        held = false
        val asked = requested().size
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.world!!.release.tag)
        assertEquals("world-20261201", fetch.keep!!.release)
        assertNull(fetch.next)
        assertEquals(1, drops)
        assertEquals(asked, requested().size)
        assertArrayEquals(q.index, File(world, WorldFetch.INDEX).readBytes())
        assertTrue(world.listFiles()!!.map { it.name }.toString(), world.listFiles()!!.none { it.name.endsWith(".next") || it.name.endsWith(".download") })
    }

    @Test
    fun aFileNotTheOneWorldJsonNamesIsNotPutInPlaceAndTheOldVersionGoesOn() {
        // the first start: an index that is not world.json's is refused, nothing left in place
        val p = publish("world-20261102")
        gh.assets[WorldFetch.INDEX] = p.index.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val fetch = fetch()
        assertThrows(PackDownloader.ChecksumException::class.java) { fetch.start() }
        assertFalse(File(world, WorldFetch.INDEX).exists())
        assertNull(fetch.world)
        // once the release holds it as named, the next start takes it (world.json not asked for again the same day)
        gh.assets[WorldFetch.INDEX] = p.index
        assertTrue(fetch.start())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        // a new version whose globe comes cut short: the old index and globe stay, the old version goes on, nothing dropped
        val q = publish("world-20261201", seed = 9)
        gh.assets[WorldFetch.GLOBE] = q.globe.copyOf(q.globe.size - 1)
        clock += WorldFetch.DAY_MS
        assertThrows(IOException::class.java) { fetch.start() }
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        assertEquals(0, drops)
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") || it.name.endsWith(".new") })
        // whole again: the new version comes at the next start
        gh.assets[WorldFetch.GLOBE] = q.globe
        assertTrue(fetch.start())
        assertEquals("world-20261201", fetch.world!!.release.tag)
        assertEquals(1, drops)
    }

    @Test
    fun withoutWorldLatestThePhonesWorldGoesOn() {
        // nothing published: no world, and the next start asks again
        val fetch = fetch()
        assertThrows(HttpStatusException::class.java) { fetch.start() }
        assertNull(fetch.world)
        assertTrue(fetch.due())
        // a world.json whose files are not on the scheme it came on (https on a phone, the fake's http here) is refused
        val p = publish("world-20261102")
        gh.assets[WorldFetch.JSON] = json("world-20261102", p.index, p.globe, url = "ftp://example.org/world/")
        assertThrows(IllegalArgumentException::class.java) { fetch.start() }
        assertNull(fetch.world)
        publish("world-20261102")
        assertTrue(fetch.start())
        // world-latest down a day later: the phone's world goes on, in this start of the app and the next
        gh.down = true
        clock += WorldFetch.DAY_MS
        assertFalse(fetch.start())
        assertEquals("world-20261102", fetch.world!!.release.tag)
        val next = fetch()
        assertTrue(next.start())
        assertEquals("world-20261102", next.world!!.release.tag)
        assertTrue(next.due())
    }

    @Test
    fun aPacksHeadIsItsBytesButItsChunksByRanges() {
        val p = publish("world-20261102")
        val fetch = fetch()
        // no range before the world is on the phone
        assertThrows(IOException::class.java) { fetch.range(WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, 10, 1, IntArray(4), ByteArray(32)), 0, 10) }
        fetch.start()
        val asked = gh.githubRequests.size
        // the second fine pack, from its place in its part: its bytes before its chunks and after them, in one request
        val e = fetch.index!!.find(6, 33, 21)!!
        val pack = p.packs[1]
        val (start, end) = WorldFetch.chunksOf(pack, pack.size.toLong())
        val head = fetch.head(e)
        assertEquals(listOf(0L, end), head.map { it.first })
        assertArrayEquals(pack.copyOf(start.toInt()), head[0].second)
        assertArrayEquals(pack.copyOfRange(end.toInt(), pack.size), head[1].second)
        assertEquals(asked + 1, gh.githubRequests.size)
        assertEquals("bytes=${e.offset}-${e.offset + minOf(pack.size, WorldFetch.HEAD_FIRST) - 1}", gh.githubRequests.last().headers["Range"])
        // a range of its chunks
        assertArrayEquals(pack.copyOfRange(start.toInt() + 10, start.toInt() + 110), fetch.range(e, start + 10, 100))
        assertEquals("bytes=${e.offset + start + 10}-${e.offset + start + 109}", gh.githubRequests.last().headers["Range"])
        // the coarse pack, whose chunks end it: its head is what comes before them
        val coarse = fetch.head(fetch.index!!.find(3, 4, 2)!!)
        assertEquals(1, coarse.size)
        assertArrayEquals(p.packs[2].copyOf(WorldFetch.chunksOf(p.packs[2], p.packs[2].size.toLong()).first.toInt()), coarse[0].second)
        // a server that ignores ranges, or answers from another byte: refused
        gh.ignoreRanges = true
        assertThrows(HttpStatusException::class.java) { fetch.range(e, start, 100) }
        gh.ignoreRanges = false
        gh.rangeFromStart = true
        assertThrows(HttpStatusException::class.java) { fetch.range(e, start, 100) }
        gh.rangeFromStart = false
        // a range past the pack is never asked for
        assertThrows(IllegalArgumentException::class.java) { fetch.range(e, e.length - 10, 11) }
        assertOnlyWhoIsAsking()
    }

    @Test
    fun aLargePacksHeadTakesARequestForEachPartPastItsFirstBytes() {
        // a directory past the first bytes, the tiles' section past them too, sections after the chunks
        val big = pack(WorldFetch.HEAD_FIRST + 1_000, "tiles" to 20_000, "chunks" to 90_000, "rhead" to 500, "style" to 7_000)
        val entry = WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, big.size.toLong(), 85, intArrayOf(1, 2, 3, 4), sha(big))
        val index = ByteArrayOutputStream().also { WorldIndex(Instant.EPOCH, listOf(WorldIndex.Part("world-mid.000", big.size.toLong())), listOf(entry)).write(it) }.toByteArray()
        val globe = ByteArray(100) { it.toByte() }
        gh.assets[WorldFetch.JSON] = json("world-20261102", index, globe)
        gh.assets[WorldFetch.INDEX] = index
        gh.assets[WorldFetch.GLOBE] = globe
        gh.assets["world-mid.000"] = big
        val fetch = fetch()
        fetch.start()
        val asked = gh.githubRequests.size
        val (start, end) = WorldFetch.chunksOf(big, big.size.toLong())
        val head = fetch.head(fetch.index!!.entries[0])
        assertArrayEquals(big.copyOf(start.toInt()), head[0].second)
        assertEquals(end, head[1].first)
        assertArrayEquals(big.copyOfRange(end.toInt(), big.size), head[1].second)
        // the first bytes, the directory's rest, the tiles' rest, the sections after the chunks; none of the chunks
        assertEquals(asked + 4, gh.githubRequests.size)
        val from = gh.storageRequests.filter { it.url.encodedPath.endsWith("world-mid.000") }.map { it.headers["Range"]!!.substringAfter('=').substringBefore('-').toLong() }
        assertEquals(4, from.size)
        assertTrue(from.toString(), from.none { it in start until end })
    }

    @Test
    fun followTakesTheWorldOnceOnlineTriesAgainAndLooksAgainEachDay() = runTest {
        val online = MutableStateFlow(false)
        val said = ArrayList<String>()
        var landed = 0
        val fetch = fetch()
        gh.down = true
        val follow = launch { fetch.follow(online, { said += it }, { landed++ }) }
        // offline, nothing is asked
        runCurrent()
        assertEquals(0, gh.githubRequests.size)
        // online with world-latest down: said, and tried again once the first wait is over
        online.value = true
        runCurrent()
        assertTrue(said.last(), said.last().startsWith("not on the phone yet: "))
        assertEquals(1, gh.githubRequests.size)
        advanceTimeBy(WorldFetch.FIRST_WAIT_MS - 1)
        runCurrent()
        assertEquals(1, gh.githubRequests.size)
        // the network lost and back: tried again at once (failing still, so the wait doubles)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(2, gh.githubRequests.size)
        // up again: the world comes once the doubled wait is over
        publish("world-20261102")
        gh.down = false
        advanceTimeBy(2 * WorldFetch.FIRST_WAIT_MS - 1)
        runCurrent()
        assertEquals(2, gh.githubRequests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("world.idx (3 packs) and world-globe.atlas of world-20261102 on the phone", said.last())
        assertEquals(1, landed)
        // offline at rest it asks nothing (nor spins); once a day has passed, world.json again
        online.value = false
        val asked = gh.githubRequests.size
        advanceTimeBy(WorldFetch.DUE_CHECK_MS * 3)
        runCurrent()
        assertEquals(asked, gh.githubRequests.size)
        clock += WorldFetch.DAY_MS
        online.value = true
        advanceTimeBy(WorldFetch.DUE_CHECK_MS)
        runCurrent()
        // and with it the keep list, which the release still lacks: asked for once a day
        assertEquals(asked + 2, gh.githubRequests.size)
        assertEquals(2, keepRequested().size)
        assertEquals("world-latest/world.json", requested().last())
        assertEquals(1, landed)
        follow.cancel()
    }

    @Test
    fun aPlacesPacksAreItsFineAndCoarseBlocks() {
        publish("world-20261102")
        val fetch = fetch()
        fetch.start()
        val index = fetch.index!!
        assertEquals(listOf("world/6/32/21", "world/3/4/2"), WorldFetch.packsAt(index, amsterdam.first, amsterdam.second).map(WorldFetch::idOf))
        assertEquals(emptyList<String>(), WorldFetch.packsAt(index, ocean.first, ocean.second).map(WorldFetch::idOf))
        assertEquals(32 to 21, WorldFetch.tileOf(52.37, 4.89, 6))
        assertEquals(4 to 2, WorldFetch.tileOf(52.37, 4.89, 3))
        assertEquals(0 to 0, WorldFetch.tileOf(89.0, -180.0, 6))
        assertEquals(63 to 63, WorldFetch.tileOf(-89.0, 179.999, 6))
    }
}
