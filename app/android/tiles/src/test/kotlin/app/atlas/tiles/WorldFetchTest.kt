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
import java.security.MessageDigest
import java.time.Instant

/**
 * The world's files on the phone (ENGINE.md step 5, world-test's fetch), from GitHub's releases faked by MockWebServer:
 * world.idx and the globe at the first start, then the parts holding a place's fine and coarse packs as a view first
 * enters it, each once, whole and checked against the index before the core may list it; a part whose pack is not the
 * one the index names, an answer cut short and a release without the file leave nothing in place; and every request
 * says only who is asking.
 */
class WorldFetchTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("worldfetch")
    private val world = File(dir, "world")
    private val base = gh.github.url("/atlas-maps-app/Atlas/releases/download/world-test/").toString()

    /** Amsterdam: its L6 tile (32, 21) and its L3 tile (4, 2); the open North Atlantic, which no pack holds. */
    private val amsterdam = 52.37 to 4.89
    private val ocean = 50.0 to -30.0

    @After
    fun close() {
        gh.close()
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private class Published(val index: ByteArray, val mid: ByteArray, val coarse: ByteArray, val globe: ByteArray)

    /** A world as `packer world` and `packer globe` write it: two fine packs and a coarse one in their parts, the index. */
    private fun publish(): Published {
        val fine = listOf(ByteArray(3_000) { (it * 7).toByte() }, ByteArray(2_000) { (it * 11 + 1).toByte() })
        val coarse = ByteArray(1_500) { (it * 13 + 2).toByte() }
        val mid = fine[0] + fine[1]
        val entries = listOf(
            WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, fine[0].size.toLong(), 85, intArrayOf(1, 2, 3, 4), sha(fine[0])),
            WorldIndex.Entry(6, 33, 21, 9, 11, 0, fine[0].size.toLong(), fine[1].size.toLong(), 85, intArrayOf(1, 2, 3, 4), sha(fine[1])),
            WorldIndex.Entry(3, 4, 2, 6, 8, 1, 0, coarse.size.toLong(), 84, intArrayOf(1, 2, 3, 4), sha(coarse)),
        )
        val parts = listOf(WorldIndex.Part("world-mid.000", mid.size.toLong()), WorldIndex.Part("world-coarse.000", coarse.size.toLong()))
        val index = ByteArrayOutputStream().also { WorldIndex(Instant.parse("2026-10-31T21:12:00Z"), parts, entries).write(it) }.toByteArray()
        val globe = ByteArray(4_000) { (it * 3 + 5).toByte() }
        gh.assets[WorldFetch.INDEX] = index
        gh.assets["world-mid.000"] = mid
        gh.assets["world-coarse.000"] = coarse
        gh.assets[WorldFetch.GLOBE] = globe
        return Published(index, mid, coarse, globe)
    }

    private fun requested(): List<String> = gh.githubRequests.map { it.url.encodedPath.substringAfterLast('/') }

    @Test
    fun theIndexAndTheGlobeComeAtTheFirstStartAndAPlacesPartsAsAViewFirstEntersIt() {
        val p = publish()
        val fetch = WorldFetch(world, base)
        assertNull(fetch.index)
        // before the index, no place is entered
        assertEquals(emptyList<String>(), fetch.enter(amsterdam.first, amsterdam.second))
        fetch.start()
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertEquals(3, fetch.index!!.entries.size)
        assertEquals(listOf(WorldFetch.INDEX, WorldFetch.GLOBE), requested())
        // what a place lacks (CI's world script asks it too): Amsterdam both parts, fine first; the open sea none
        assertEquals(listOf(0, 1), WorldFetch.missing(world, fetch.index!!, amsterdam.first, amsterdam.second))
        assertEquals(emptyList<Int>(), WorldFetch.missing(world, fetch.index!!, ocean.first, ocean.second))
        // Amsterdam: its fine pack's part and its coarse pack's, whole
        assertEquals(listOf("world-mid.000", "world-coarse.000"), fetch.enter(amsterdam.first, amsterdam.second))
        assertArrayEquals(p.mid, File(world, "world-mid.000").readBytes())
        assertArrayEquals(p.coarse, File(world, "world-coarse.000").readBytes())
        assertEquals(emptyList<Int>(), WorldFetch.missing(world, fetch.index!!, amsterdam.first, amsterdam.second))
        // each once: again, a neighbouring block in the same part, a second start and the open sea ask for nothing
        val asked = requested().size
        assertEquals(emptyList<String>(), fetch.enter(amsterdam.first, amsterdam.second))
        assertEquals(emptyList<String>(), fetch.enter(52.37, 8.0)) // L6 (33, 21)
        assertEquals(emptyList<String>(), fetch.enter(ocean.first, ocean.second))
        WorldFetch(world, base).start()
        assertEquals(asked, requested().size)
        // nothing half written is left beside them
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") })
        // and every request said only who was asking
        for (r in gh.githubRequests + gh.storageRequests) {
            assertEquals(Http.USER_AGENT, r.headers["User-Agent"])
            assertNull(r.headers["Authorization"])
            assertNull(r.headers["Cookie"])
            assertFalse(r.url.toString().contains("@"))
        }
    }

    @Test
    fun aPartWhosePackIsNotTheIndexsOrOneCutShortIsNotPutInPlace() {
        val p = publish()
        val fetch = WorldFetch(world, base)
        fetch.start()
        // a byte of the second fine pack changed: the part is refused, the coarse one never asked for meanwhile
        gh.assets["world-mid.000"] = p.mid.copyOf().also { it[3_500] = (it[3_500] + 1).toByte() }
        assertThrows(PackDownloader.ChecksumException::class.java) { fetch.enter(amsterdam.first, amsterdam.second) }
        assertFalse(File(world, "world-mid.000").exists())
        // a part shorter than the index says
        gh.assets["world-mid.000"] = p.mid.copyOf(p.mid.size - 1)
        assertThrows(java.io.IOException::class.java) { fetch.enter(amsterdam.first, amsterdam.second) }
        assertFalse(File(world, "world-mid.000").exists())
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") })
        // once the release holds the part as indexed, it comes
        gh.assets["world-mid.000"] = p.mid
        assertEquals(listOf("world-mid.000", "world-coarse.000"), fetch.enter(amsterdam.first, amsterdam.second))
        assertArrayEquals(p.mid, File(world, "world-mid.000").readBytes())
    }

    @Test
    fun aReleaseWithoutTheWorldLeavesNothingInPlace() {
        // world-test not published yet: no index, no globe, and the next start asks again
        val fetch = WorldFetch(world, base)
        assertThrows(HttpStatusException::class.java) { fetch.start() }
        assertFalse(File(world, WorldFetch.INDEX).exists())
        assertNull(fetch.index)
        // the index published but not the globe: the index stays, the globe is asked for at the next start
        val p = publish()
        gh.assets.remove(WorldFetch.GLOBE)
        assertThrows(HttpStatusException::class.java) { fetch.start() }
        assertArrayEquals(p.index, File(world, WorldFetch.INDEX).readBytes())
        assertFalse(File(world, WorldFetch.GLOBE).exists())
        gh.assets[WorldFetch.GLOBE] = p.globe
        fetch.start()
        assertArrayEquals(p.globe, File(world, WorldFetch.GLOBE).readBytes())
        assertTrue(world.listFiles()!!.none { it.name.endsWith(".download") })
    }

    @Test
    fun whatFailedIsTriedAgainForTheLatestPlaceAtRestAndAtOnceWhereTheNetworkComesBack() = runTest {
        val p = publish()
        val fetch = WorldFetch(world, base)
        val places = MutableStateFlow<WorldFetch.Place?>(null)
        val online = MutableStateFlow(true)
        val said = ArrayList<String>()
        var landed = 0
        val follow = launch { fetch.follow(places, online, { said += it }, { landed++ }) }
        runCurrent()
        assertEquals(listOf("world.idx (3 packs) and world-globe.atlas on the phone"), said)
        // the view enters Amsterdam's block as the network goes (CI's emulator moved to its mobile network, 1 November)
        gh.down = true
        places.value = WorldFetch.Place(amsterdam.first, amsterdam.second)
        runCurrent()
        assertTrue(said.last(), said.last().startsWith("a block's packs not fetched: "))
        val asked = gh.githubRequests.size
        // a pan meanwhile asks nothing before the wait is over
        places.value = WorldFetch.Place(52.36, 4.90)
        advanceTimeBy(WorldFetch.FIRST_WAIT_MS - 1)
        runCurrent()
        assertEquals(asked, gh.githubRequests.size)
        // the network lost and back: tried again at once, for the latest place (failing still, so the wait doubles)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(asked + 1, gh.githubRequests.size)
        // and at rest, once the doubled wait is over, the parts come
        gh.down = false
        advanceTimeBy(2 * WorldFetch.FIRST_WAIT_MS - 1)
        runCurrent()
        assertEquals(asked + 1, gh.githubRequests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("parts world-mid.000, world-coarse.000 on the phone", said.last())
        assertArrayEquals(p.mid, File(world, "world-mid.000").readBytes())
        assertArrayEquals(p.coarse, File(world, "world-coarse.000").readBytes())
        assertEquals(2, landed)
        // the place done, a map at rest asks nothing more
        val all = gh.githubRequests.size
        places.value = WorldFetch.Place(52.36, 4.90)
        advanceTimeBy(WorldFetch.LONGEST_WAIT_MS)
        runCurrent()
        assertEquals(all, gh.githubRequests.size)
        follow.cancel()
    }

    @Test
    fun aPlacesTilesAreWebMercatorsAtTheBlocksLevels() {
        assertEquals(32 to 21, WorldFetch.tileOf(52.37, 4.89, 6))
        assertEquals(4 to 2, WorldFetch.tileOf(52.37, 4.89, 3))
        assertEquals(0 to 0, WorldFetch.tileOf(89.0, -180.0, 6))
        assertEquals(63 to 63, WorldFetch.tileOf(-89.0, 179.999, 6))
    }
}
