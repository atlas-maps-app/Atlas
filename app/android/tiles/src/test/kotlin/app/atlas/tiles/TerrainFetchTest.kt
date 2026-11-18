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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant

/**
 * The terrain's files on the phone (ENGINE.md part 6, step 2), from GitHub's releases faked by MockWebServer:
 * terrain.json from terrain-latest at most once a day, a region's index once the view reaches its box, each whole and
 * of the SHA-256 terrain.json names before it is put in place; a pack's head and its chunks by ranges; a new release
 * replacing the old one's indexes (and dropping its heads and chunks); the phone's terrain going on offline; the map's
 * credit line's short names; and every request saying only who is asking.
 */
class TerrainFetchTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("terrainfetch")
    private val terrain = File(dir, "terrain")
    private val latest = gh.github.url("/atlas-maps-app/Atlas/releases/download/terrain-latest/").toString()
    private var clock = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
    private var drops = 0

    /** Amsterdam's L9 tile, its neighbour to the east, and Bergen's. */
    private val amsterdam = WorldFetch.tileOf(52.37, 4.89, 9)
    private val east = amsterdam.first + 1 to amsterdam.second
    private val bergen = WorldFetch.tileOf(60.39, 5.32, 9)

    @After
    fun close() {
        gh.close()
        dir.deleteRecursively()
    }

    private fun fetch() = TerrainFetch(terrain, latest, now = { clock }, drop = { drops++ })

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** A terrain pack's container: the header, a manifest, the directory, then its sections (tiles, chunks, rhead, style). */
    private fun pack(seed: Int, chunks: Int): ByteArray {
        val sections = listOf("tiles" to 300, "chunks" to chunks, "rhead" to 40, "style" to 20)
        val names = sections.map { it.first.toByteArray() }
        val directory = names.sumOf { 1 + it.size + 29 }
        val manifest = 90
        var at = 20L + manifest + directory
        val entries = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        for ((i, s) in sections.withIndex()) {
            entries.write(names[i].size)
            entries.write(names[i])
            entries.write(ByteBuffer.allocate(29).putLong(at).putLong(s.second.toLong()).putLong(s.second.toLong()).putInt(0).put(0).array())
            data.write(ByteArray(s.second) { (i * 31 + it * 7 + seed).toByte() })
            at += s.second
        }
        val header = ByteBuffer.allocate(20).put("ATLASPCK".toByteArray()).put(0).put(3).put(0).put(0).putInt(manifest).putInt(directory)
        return header.array() + ByteArray(manifest) { 'm'.code.toByte() } + entries.toByteArray() + data.toByteArray()
    }

    private class Region(val id: String, val index: ByteArray, val packs: List<ByteArray>)

    /** A region's packs, one an L9 block, end to end in one part, and its index. */
    private fun region(id: String, blocks: List<Pair<Int, Int>>, seed: Int): Region {
        val packs = blocks.mapIndexed { i, _ -> pack(seed + i, 2_000 + 100 * i + seed) }
        var offset = 0L
        val entries = blocks.mapIndexed { i, (x, y) ->
            WorldIndex.Entry(9, x, y, 12, 14, 0, offset, packs[i].size.toLong(), 21, intArrayOf(1, 2, 3, 4), sha(packs[i])).also { offset += packs[i].size }
        }
        val part = packs.reduce { a, b -> a + b }
        val index = ByteArrayOutputStream().also { WorldIndex(Instant.parse("2026-11-12T00:00:00Z"), listOf(WorldIndex.Part("$id-terrain.000", part.size.toLong())), entries).write(it) }.toByteArray()
        gh.assets["$id-terrain.idx"] = index
        gh.assets["$id-terrain.000"] = part
        return Region(id, index, packs)
    }

    /** terrain-latest's terrain.json for release [tag], naming [regions] (its files on the fake GitHub, as heights-list writes it). */
    private fun json(tag: String, regions: List<Pair<Region, String>>, url: String = gh.github.url("/atlas-maps-app/Atlas/releases/download/$tag/").toString()): ByteArray {
        val r = regions.joinToString(", ") { (g, box) ->
            """{"id": "${g.id}", "box": $box, "index": {"name": "${g.id}-terrain.idx", "bytes": ${g.index.size}, "sha256": "${hex(sha(g.index))}"}}"""
        }
        return """{"format": 1, "release": "$tag", "url": "$url",
            "sources": [{"id": "glo30", "name": "Copernicus", "credit": "produced using Copernicus WorldDEM-30"},
                        {"id": "ahn4", "name": "AHN", "credit": "AHN, through PDOK. CC0."},
                        {"id": "kartverket-dtm1", "name": "Kartverket", "credit": "© Kartverket."}],
            "regions": [$r]}""".toByteArray()
    }

    private val netherlandsBox = "[50.75, 3.36, 53.56, 7.23]"
    private val norwayBox = "[60.0, 4.5, 60.8, 6.0]"

    /** A release [tag]: the Netherlands (Amsterdam and its eastern neighbour) and Norway (Bergen). */
    private fun publish(tag: String, seed: Int = 0): Pair<Region, Region> {
        val nl = region("netherlands", listOf(amsterdam, east), seed)
        val no = region("norway", listOf(bergen), seed + 50)
        gh.assets[TerrainFetch.JSON] = json(tag, listOf(nl to netherlandsBox, no to norwayBox))
        return nl to no
    }

    private fun requested(): List<String> = gh.githubRequests.map { it.url.encodedPath.removePrefix("/atlas-maps-app/Atlas/releases/download/") }

    private fun assertOnlyWhoIsAsking() {
        for (r in gh.githubRequests + gh.storageRequests) {
            assertEquals(Http.USER_AGENT, r.headers["User-Agent"])
            assertNull(r.headers["Authorization"])
            assertNull(r.headers["Cookie"])
            assertFalse(r.url.toString().contains("@"))
        }
    }

    @Test
    fun theListComesOnceADayAndARegionsIndexOnceTheViewReachesIt() {
        val (nl, _) = publish("terrain-20261113")
        val fetch = fetch()
        assertNull(fetch.terrain)
        assertTrue(fetch.start())
        assertEquals(listOf("terrain-latest/terrain.json"), requested())
        assertEquals("terrain-20261113", fetch.list!!.release)
        assertEquals(listOf("netherlands", "norway"), fetch.list!!.regions.map { it.id })
        // no index before the view reaches a region; Amsterdam's block brings the Netherlands' alone
        assertEquals(emptyList<TerrainFetch.Pack>(), fetch.packsAt(listOf(amsterdam)))
        val said = ArrayList<String>()
        assertEquals(listOf("netherlands"), fetch.take(listOf(amsterdam), said::add))
        assertEquals("netherlands-terrain.idx (2 packs) of terrain-20261113 on the phone", said.single())
        assertArrayEquals(nl.index, File(terrain, "netherlands-terrain.idx").readBytes())
        assertFalse(File(terrain, "norway-terrain.idx").exists())
        assertEquals("terrain-20261113/netherlands-terrain.idx", requested().last())
        // asked again, nothing more comes
        assertEquals(emptyList<String>(), fetch.take(listOf(amsterdam, east)))
        assertEquals(2, requested().size)
        val packs = fetch.packsAt(listOf(east, amsterdam, bergen))
        assertEquals(listOf("terrain/netherlands-terrain/9/${east.first}/${east.second}", "terrain/netherlands-terrain/9/${amsterdam.first}/${amsterdam.second}"), packs.map { it.id })
        assertArrayEquals(sha(nl.packs[1]), packs[0].entry.sha256)
        // the same day asks nothing, nor does a second start of the app, which finds the index it kept
        assertFalse(fetch.start())
        val next = fetch()
        assertTrue(next.start())
        assertEquals(2, requested().size)
        assertEquals(2, next.packsAt(listOf(amsterdam, east)).size)
        // a day later terrain.json is asked for again: the same release, nothing dropped
        clock += WorldFetch.DAY_MS
        assertTrue(fetch.due())
        assertFalse(fetch.start())
        assertEquals("terrain-latest/terrain.json", requested().last())
        assertEquals(0, drops)
        assertTrue(terrain.listFiles()!!.none { it.name.endsWith(".download") || it.name.endsWith(".new") })
        assertOnlyWhoIsAsking()
    }

    @Test
    fun aPacksHeadAndChunksComeByRanges() {
        val (nl, _) = publish("terrain-20261113")
        val fetch = fetch()
        fetch.start()
        fetch.take(listOf(amsterdam))
        val p = fetch.packsAt(listOf(east)).single()
        val pack = nl.packs[1]
        val (start, end) = WorldFetch.chunksOf(pack, pack.size.toLong())
        val asked = gh.githubRequests.size
        val head = fetch.head(p)
        assertEquals(listOf(0L, end), head.map { it.first })
        assertArrayEquals(pack.copyOf(start.toInt()), head[0].second)
        assertArrayEquals(pack.copyOfRange(end.toInt(), pack.size), head[1].second)
        assertEquals(asked + 1, gh.githubRequests.size)
        assertEquals("terrain-20261113/netherlands-terrain.000", requested().last())
        assertEquals("bytes=${p.entry.offset}-${p.entry.offset + pack.size - 1}", gh.githubRequests.last().headers["Range"])
        // a range of its chunks
        assertArrayEquals(pack.copyOfRange(start.toInt() + 10, start.toInt() + 110), fetch.range(p, start + 10, 100))
        assertEquals("bytes=${p.entry.offset + start + 10}-${p.entry.offset + start + 109}", gh.githubRequests.last().headers["Range"])
        // a server that ignores ranges, or answers from another byte: refused
        gh.ignoreRanges = true
        assertThrows(HttpStatusException::class.java) { fetch.range(p, start, 100) }
        gh.ignoreRanges = false
        gh.rangeFromStart = true
        assertThrows(HttpStatusException::class.java) { fetch.range(p, start, 100) }
        gh.rangeFromStart = false
        // a range past the pack is never asked for
        assertThrows(IllegalArgumentException::class.java) { fetch.range(p, p.entry.length - 10, 11) }
        assertOnlyWhoIsAsking()
    }

    @Test
    fun anIndexNotTheOneTheListNamesIsRefusedAndAskedForAgainLater() {
        val (nl, _) = publish("terrain-20261113")
        gh.assets["netherlands-terrain.idx"] = nl.index.copyOf().also { it[30] = (it[30] + 1).toByte() }
        val fetch = fetch()
        fetch.start()
        val said = ArrayList<String>()
        assertEquals(emptyList<String>(), fetch.take(listOf(amsterdam), said::add))
        assertTrue(said.single(), said.single().startsWith("netherlands-terrain.idx not on the phone yet: ") && said.single().endsWith("is not the file terrain.json names"))
        assertFalse(File(terrain, "netherlands-terrain.idx").exists())
        assertEquals(emptyList<TerrainFetch.Pack>(), fetch.packsAt(listOf(amsterdam)))
        // not asked for again at once; once the wait is over, and the release holds it as named, it comes
        gh.assets["netherlands-terrain.idx"] = nl.index
        val asked = gh.githubRequests.size
        assertEquals(emptyList<String>(), fetch.take(listOf(amsterdam)))
        assertEquals(asked, gh.githubRequests.size)
        clock += TerrainFetch.INDEX_AGAIN_MS
        assertEquals(listOf("netherlands"), fetch.take(listOf(amsterdam)))
        assertEquals(2, fetch.packsAt(listOf(amsterdam, east)).size)
        // a list whose files are not on the scheme it came on (https on a phone, the fake's http here) is refused
        gh.assets[TerrainFetch.JSON] = json("terrain-20261201", listOf(nl to netherlandsBox), url = "ftp://example.org/terrain/")
        clock += WorldFetch.DAY_MS
        assertThrows(IllegalArgumentException::class.java) { TerrainList.read(gh.assets[TerrainFetch.JSON]!!.decodeToString(), "http") }
        assertFalse(fetch.start())
        assertEquals("terrain-20261113", fetch.list!!.release)
    }

    @Test
    fun aNewReleaseReplacesTheOldOnesIndexesAndDropsItsHeadsAndChunks() {
        publish("terrain-20261113")
        val fetch = fetch()
        fetch.start()
        fetch.take(listOf(amsterdam))
        fetch.take(listOf(bergen))
        assertTrue(File(terrain, "norway-terrain.idx").isFile)
        // the next release: the Netherlands built again, Norway no longer listed
        val nl = region("netherlands", listOf(amsterdam, east), 9)
        gh.assets[TerrainFetch.JSON] = json("terrain-20261201", listOf(nl to netherlandsBox))
        // its index comes cut short first: the old release goes on, nothing dropped
        gh.assets["netherlands-terrain.idx"] = nl.index.copyOf(nl.index.size - 1)
        clock += WorldFetch.DAY_MS
        assertThrows(java.io.IOException::class.java) { fetch.start() }
        assertEquals(0, drops)
        assertTrue(File(terrain, "norway-terrain.idx").isFile)
        // whole: the new index replaces the old one, Norway's is deleted, the old heads and chunks dropped
        gh.assets["netherlands-terrain.idx"] = nl.index
        assertTrue(fetch.start())
        assertEquals("terrain-20261201", fetch.list!!.release)
        assertEquals(1, drops)
        assertArrayEquals(nl.index, File(terrain, "netherlands-terrain.idx").readBytes())
        assertFalse(File(terrain, "norway-terrain.idx").exists())
        assertArrayEquals(sha(nl.packs[0]), fetch.packsAt(listOf(amsterdam)).single().entry.sha256)
        assertEquals("terrain-20261201/netherlands-terrain.idx", requested().last())
        assertTrue(terrain.listFiles()!!.none { it.name.endsWith(".download") || it.name.endsWith(".new") })
        // the next start of the app finds the new release whole: nothing more asked, nothing dropped again
        val asked = requested().size
        assertTrue(fetch().start())
        assertEquals(asked, requested().size)
        assertEquals(1, drops)
    }

    @Test
    fun offlineThePhonesTerrainGoesOn() {
        // nothing published: no terrain, and the next start asks again
        val fetch = fetch()
        assertThrows(HttpStatusException::class.java) { fetch.start() }
        assertNull(fetch.terrain)
        assertTrue(fetch.due())
        val (nl, _) = publish("terrain-20261113")
        assertTrue(fetch.start())
        fetch.take(listOf(amsterdam))
        // terrain-latest down a day later: the phone's list and index go on, in this start of the app and the next
        gh.down = true
        clock += WorldFetch.DAY_MS
        assertFalse(fetch.start())
        assertEquals("terrain-20261113", fetch.list!!.release)
        val next = fetch()
        assertTrue(next.start())
        assertEquals("terrain-20261113", next.list!!.release)
        assertArrayEquals(sha(nl.packs[0]), next.packsAt(listOf(amsterdam)).single().entry.sha256)
        // a region it never kept stays without its index while offline, asked for nothing more than once
        val asked = gh.githubRequests.size
        assertEquals(emptyList<String>(), next.take(listOf(bergen)))
        assertEquals(emptyList<String>(), next.take(listOf(bergen)))
        assertEquals(asked + 1, gh.githubRequests.size)
        assertTrue(next.due())
    }

    @Test
    fun theCreditLineNamesCopernicusFirst() {
        publish("terrain-20261113")
        val fetch = fetch()
        assertEquals(emptyList<String>(), fetch.creditsOf(listOf("ahn4", "glo30")))
        fetch.start()
        assertEquals(listOf("Copernicus", "AHN"), fetch.creditsOf(listOf("ahn4", "glo30")))
        assertEquals(listOf("Copernicus", "Kartverket"), fetch.creditsOf(listOf("glo30", "kartverket-dtm1", "glo30")))
        assertEquals(listOf("AHN"), fetch.creditsOf(listOf("ahn4", "nowhere")))
        assertEquals(emptyList<String>(), fetch.creditsOf(emptyList()))
    }

    @Test
    fun followTakesTheListOnceOnline() = runTest {
        val online = MutableStateFlow(false)
        val said = ArrayList<String>()
        var landed = 0
        val fetch = fetch()
        publish("terrain-20261113")
        val follow = launch { fetch.follow(online, { said += it }, { landed++ }) }
        runCurrent()
        assertEquals(0, gh.githubRequests.size)
        online.value = true
        runCurrent()
        assertEquals("terrain.json of terrain-20261113 (2 regions, 0 indexes kept) on the phone", said.last())
        assertEquals(1, landed)
        // once a day has passed, terrain.json again; the same release lands nothing
        clock += WorldFetch.DAY_MS
        advanceTimeBy(WorldFetch.DUE_CHECK_MS)
        runCurrent()
        assertEquals(listOf("terrain-latest/terrain.json", "terrain-latest/terrain.json"), requested())
        assertEquals(1, landed)
        follow.cancel()
    }

    @Test
    fun aListNamesItsRegionsIndexesByTheirRegion() {
        val ok = """{"format": 1, "release": "t", "url": "https://example.org/t", "sources": [],
            "regions": [{"id": "netherlands", "box": [50, 3, 53, 7], "index": {"name": "netherlands-terrain.idx", "bytes": 10, "sha256": "${"ab".repeat(32)}"}}]}"""
        val list = TerrainList.read(ok)
        assertEquals("https://example.org/t/", list.url)
        assertEquals("netherlands-terrain", list.regions.single().name)
        for (bad in listOf("../netherlands-terrain.idx", "norway-terrain.idx", "netherlands.idx")) {
            assertThrows(IllegalArgumentException::class.java) { TerrainList.read(ok.replace("netherlands-terrain.idx", bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { TerrainList.read(ok.replace("\"format\": 1", "\"format\": 2")) }
        assertThrows(IllegalArgumentException::class.java) { TerrainList.read(ok.replace("[50, 3, 53, 7]", "[50, 3, 53]")) }
        assertTrue(TerrainFetch.meets(list.regions.single(), amsterdam.first, amsterdam.second))
        assertFalse(TerrainFetch.meets(list.regions.single(), bergen.first, bergen.second))
    }
}
