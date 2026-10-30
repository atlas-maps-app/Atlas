package app.atlas.tiles

import app.atlas.geo.BBox
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant

/**
 * A window's pack from its country's tiles, on GitHub releases faked by MockWebServer: the index downloaded once and
 * kept beside the windows' data indexes, each window by a range of its own bytes (two where it spans two parts), held
 * to the index, its style written back whole from the country's; a window with nothing to draw answered without
 * bytes; the newest country that holds a window; each phone its own core's tiles where a country publishes two cores';
 * and no answer where no country holds it, or where another core built the tiles.
 */
class WindowTilesTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("windowtiles")
    private val date = Instant.parse("2026-10-26T20:21:24Z")

    /** The phone's core's window version (MapCore.Window.version). */
    private val version = "core-p8 s1.1-0123456789abcdef"

    /** The Westerkerk's window (52.3745, 4.8840), a neighbour, and a window of the IJ with nothing to draw. */
    private val westerkerk = 8414 to 5384
    private val neighbour = 8413 to 5384
    private val water = 8414 to 5385
    private val cells = listOf(neighbour, westerkerk, water)

    @After
    fun close() {
        gh.close()
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = sha(b).joinToString("") { "%02x".format(it) }

    /**
     * A country's tiles as `packer country` writes them: each drawn window's pack as the tiles hold it ([stored]), back
     * to back, and the index; and each window's pack as the core reads it ([packs]).
     */
    private class Country(
        val tiles: ByteArray,
        val index: ByteArray,
        val stored: Map<Pair<Int, Int>, ByteArray>,
        val packs: Map<Pair<Int, Int>, ByteArray>,
    )

    private fun country(at: Instant = date, core: String = version, of: List<Pair<Int, Int>> = cells, empty: Set<Pair<Int, Int>> = setOf(water)): Country {
        val out = ByteArrayOutputStream()
        val entries = ArrayList<TilesIndex.Entry>()
        val stored = HashMap<Pair<Int, Int>, ByteArray>()
        val packs = HashMap<Pair<Int, Int>, ByteArray>()
        // the country's style: the first window's in the file
        var country: ByteArray? = null
        for ((x, y) in of.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (x to y in empty) {
                entries += TilesIndex.Entry(x, y, out.size().toLong(), 0, ByteArray(32))
                continue
            }
            // a window's pack, as far as the phone's reader of the tiles cares: its container, its style the last section
            val style = windowStyle(x, y)
            val pack = storedPack("{\"window\":\"14/$x/$y\",\"of\":\"$at\",\"by\":\"$core\"}", listOf("chunks" to ByteArray(100 + x % 2) { (it * x).toByte() }, "style" to style))
            val b = CountryStyle.store(pack, style, country ?: style.also { country = it })
            entries += TilesIndex.Entry(x, y, out.size().toLong(), b.size, sha(b))
            stored[x to y] = b
            packs[x to y] = pack
            out.write(b)
        }
        val tiles = out.toByteArray()
        val index = ByteArrayOutputStream().also { TilesIndex.of(14, at, tiles.size.toLong(), core, country ?: ByteArray(0), entries).write(it) }.toByteArray()
        return Country(tiles, index, stored, packs)
    }

    /**
     * [c] published as pack [id]: its tiles in parts of [split] bytes (whole when 0), its index whole with the core
     * version packs.json names ([core]: none where null).
     */
    private fun publish(id: String, c: Country, at: Instant, split: Int = 0, core: String? = version, bbox: BBox = BBox(3.3, 50.7, 7.2, 53.6)): Pack {
        val chunks = if (split > 0) c.tiles.toList().chunked(split).map { it.toByteArray() } else listOf(c.tiles)
        val parts = chunks.mapIndexed { i, b ->
            val name = if (split > 0) "$id.tiles.%03d".format(i) else "$id.tiles"
            gh.assets[name] = b
            PackPart(gh.url(name), b.size.toLong(), null)
        }
        gh.assets["$id.tiles.idx"] = c.index
        return Pack(
            id, id, "NL", bbox, at.toString(), "packs-20261102",
            listOf(
                PackFile(PackFile.TILES, "$id.tiles", c.tiles.size.toLong(), hex(c.tiles), parts),
                PackFile(
                    PackFile.TILES_INDEX, "$id.tiles.idx", c.index.size.toLong(), hex(c.index),
                    listOf(PackPart(gh.url("$id.tiles.idx"), c.index.size.toLong(), null)), coreVersion = core,
                ),
            ),
        )
    }

    private fun ranges() = gh.storageRequests.map { it.headers["Range"] }

    @Test
    fun aWindowsPackComesByARangeOfItsOwnBytesWithTheIndexDownloadedOnce() {
        val c = country()
        val list = listOf(publish("netherlands", c, date))
        val tiles = WindowTiles({ list }, dir)
        val got = tiles.window(14, westerkerk.first, westerkerk.second, version)!!
        assertArrayEquals(c.packs[westerkerk], got.bytes)
        assertTrue(got.drawn)
        assertEquals(date, got.date)
        assertEquals("netherlands", got.pack)
        // the index whole, then the window's own bytes as the tiles hold them
        val start = c.stored.getValue(neighbour).size
        val length = c.stored.getValue(westerkerk).size
        assertEquals(listOf(null, "bytes=$start-${start + length - 1}"), ranges())
        // its neighbour: its bytes alone
        assertArrayEquals(c.packs[neighbour], tiles.window(14, neighbour.first, neighbour.second, version)!!.bytes)
        assertEquals(3, gh.storageRequests.size)
        // kept beside the windows' data indexes, apart from them
        assertEquals(listOf("netherlands-${hex(c.index).take(16)}.tiles.idx"), dir.list()!!.toList())
        // the app started again: the index kept in the folder serves
        assertArrayEquals(c.packs[westerkerk], WindowTiles({ list }, dir).window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
        assertEquals(4, gh.storageRequests.size)
        assertEquals("bytes=$start-${start + length - 1}", ranges().last())
    }

    @Test
    fun anIndexNeverPassesThroughTheAppsHttpCache() {
        // the app hands the windows its own client, which keeps answers in a disk cache: an index is kept in the folder
        val c = country()
        val list = listOf(publish("netherlands", c, date))
        val cache = okhttp3.Cache(tempDir("http"), 64L shl 20)
        val app = okhttp3.OkHttpClient.Builder().cache(cache).build()
        assertArrayEquals(c.packs[westerkerk], WindowTiles({ list }, dir, app).window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
        assertEquals(0L, cache.size())
        assertEquals(0, cache.requestCount())
    }

    @Test
    fun aCountryKeptOnThePhoneIsReadThereWithoutTheNetwork() {
        val c = country()
        val list = listOf(publish("netherlands", c, date))
        val kept = tempDir("kept")
        val tilesFile = java.io.File(kept, "netherlands.tiles").apply { writeBytes(c.tiles) }
        val indexFile = java.io.File(kept, "netherlands.tiles.idx").apply { writeBytes(c.index) }
        val tiles = WindowTiles({ list }, dir, local = { _, _ -> WindowTiles.Local(tilesFile, indexFile) })
        assertArrayEquals(c.packs[westerkerk], tiles.window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
        assertArrayEquals(c.packs[neighbour], tiles.window(14, neighbour.first, neighbour.second, version)!!.bytes)
        assertFalse(tiles.window(14, water.first, water.second, version)!!.drawn)
        // neither the index nor a window's bytes were asked of GitHub, nor the index kept in the windows' folder
        assertTrue(gh.storageRequests.isEmpty())
        assertTrue(dir.list().isNullOrEmpty())
        // a copy whose bytes are not the published ones gives way to the published tiles, by a range
        val spoilt = c.tiles.copyOf().also { b -> for (i in b.indices) b[i] = (b[i].toInt() xor 0x5A).toByte() }
        tilesFile.writeBytes(spoilt)
        val again = WindowTiles({ list }, tempDir("again"), local = { _, _ -> WindowTiles.Local(tilesFile, indexFile) })
        assertArrayEquals(c.packs[westerkerk], again.window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
        val start = c.stored.getValue(neighbour).size
        assertEquals(listOf("bytes=$start-${start + c.stored.getValue(westerkerk).size - 1}"), ranges())
        kept.deleteRecursively()
    }

    @Test
    fun aWindowWithNothingToDrawIsAnsweredWithNoBytesAndNoRange() {
        val list = listOf(publish("netherlands", country(), date))
        val got = WindowTiles({ list }, dir).window(14, water.first, water.second, version)!!
        assertFalse(got.drawn)
        assertEquals(0, got.bytes.size)
        assertEquals(date, got.date)
        // the index alone was asked for
        assertEquals(listOf<String?>(null), ranges())
    }

    @Test
    fun aWindowAcrossTwoPartsComesInARangeOfEach() {
        val c = country()
        val first = c.stored.getValue(neighbour).size
        // the first part ends inside the Westerkerk's window
        val list = listOf(publish("netherlands", c, date, split = first + 10))
        val got = WindowTiles({ list }, dir).window(14, westerkerk.first, westerkerk.second, version)!!
        assertArrayEquals(c.packs[westerkerk], got.bytes)
        assertEquals(listOf(null, "bytes=$first-${first + 9}", "bytes=0-${c.stored.getValue(westerkerk).size - 11}"), ranges())
    }

    @Test
    fun anotherCoresTilesAreNotTaken() {
        // packs.json names another core's version: its index is not even downloaded
        val other = "core-p7 s1.1-fedcba9876543210"
        val older = listOf(publish("netherlands", country(core = other), date, core = other))
        assertNull(WindowTiles({ older }, dir).window(14, westerkerk.first, westerkerk.second, version))
        assertTrue(gh.storageRequests.isEmpty())
        // nor where packs.json names none
        val unnamed = listOf(publish("netherlands", country(), date, core = null))
        assertNull(WindowTiles({ unnamed }, tempDir("unnamed")).window(14, westerkerk.first, westerkerk.second, version))
        assertTrue(gh.storageRequests.isEmpty())
        // an index of another core than packs.json names: read, and not taken
        val wrong = listOf(publish("netherlands", country(core = other), date, core = version))
        assertNull(WindowTiles({ wrong }, tempDir("wrong")).window(14, westerkerk.first, westerkerk.second, version))
        assertEquals(listOf<String?>(null), ranges())
        // the phone's own core's tiles
        val own = listOf(publish("netherlands", country(), date))
        assertArrayEquals(country().packs[westerkerk], WindowTiles({ own }, tempDir("own")).window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
    }

    @Test
    fun aCountryPublishingTwoCoresTilesGivesEachPhoneItsOwn() {
        // a republish keeps the tiles of the core before ahead of the new ones (packer's Manifest.keepingTiles), for
        // the phones not updated yet
        val before = "core-p7 s1.1-fedcba9876543210"
        val old = country(date.minusSeconds(86_400L), core = before)
        val new = country()
        val kept = publish("netherlands-before", old, date, core = before)
        val own = publish("netherlands", new, date)
        val list = listOf(own.copy(files = kept.files + own.files))
        fun asked() = gh.storageRequests.map { it.url.encodedPath.substringAfterLast('/') }
        // the phone of this core: its own tiles; the other core's index is not even downloaded
        val got = WindowTiles({ list }, dir).window(14, westerkerk.first, westerkerk.second, version)!!
        assertArrayEquals(new.packs[westerkerk], got.bytes)
        assertEquals(date, got.date)
        assertEquals(listOf("netherlands.tiles.idx", "netherlands.tiles"), asked())
        // a phone of the core before: the tiles kept for it, and nothing of the new ones
        gh.storageRequests.clear()
        val older = WindowTiles({ list }, tempDir("before")).window(14, westerkerk.first, westerkerk.second, before)!!
        assertArrayEquals(old.packs[westerkerk], older.bytes)
        assertEquals(date.minusSeconds(86_400L), older.date)
        assertEquals(listOf("netherlands-before.tiles.idx", "netherlands-before.tiles"), asked())
        // and a core of neither: none
        assertNull(WindowTiles({ list }, tempDir("neither")).window(14, westerkerk.first, westerkerk.second, "core-p6 s1.1-fedcba9876543210"))
    }

    @Test
    fun aWindowNoCountryHoldsGetsNoAnswer() {
        val list = listOf(publish("netherlands", country(), date))
        val tiles = WindowTiles({ list }, dir)
        // in the box, not in the index (the sea beyond the country's polygon)
        assertNull(tiles.window(14, 8414, 5386, version))
        // beyond the pack's box: its index is not even read
        val before = gh.storageRequests.size
        assertNull(WindowTiles({ list }, tempDir("far")).window(14, 0, 0, version))
        assertEquals(before, gh.storageRequests.size)
        // another level than the phone's windows
        assertNull(tiles.window(13, 4207, 2692, version))
        // a pack with the windows' data alone
        val data = list.map { p -> p.copy(files = p.files.filter { it.kind != PackFile.TILES }) }
        assertNull(WindowTiles({ data }, tempDir("data")).window(14, westerkerk.first, westerkerk.second, version))
        // no list to be had: asked for again only half a minute on
        var now = 0L
        var asked = 0
        val offline = WindowTiles({ asked++; if (now < PackReads.LIST_RETRY_MS) throw IOException("HTTP 404 fetching the pack list") else list }, dir, clock = { now })
        assertNull(offline.window(14, westerkerk.first, westerkerk.second, version))
        now = PackReads.LIST_RETRY_MS - 1
        assertNull(offline.window(14, westerkerk.first, westerkerk.second, version))
        assertEquals(1, asked)
        now = PackReads.LIST_RETRY_MS
        assertEquals("netherlands", offline.window(14, westerkerk.first, westerkerk.second, version)!!.pack)
        assertEquals(2, asked)
    }

    @Test
    fun theNewestCountrysTilesThatHoldTheWindowAreTaken() {
        val older = date.minusSeconds(30 * 86_400L)
        val list = listOf(
            publish("old", country(older), older),
            // the newer tiles hold two of the three windows
            publish("new", country(date, of = cells.take(2)), date),
        )
        val tiles = WindowTiles({ list }, dir)
        assertEquals("new", tiles.window(14, westerkerk.first, westerkerk.second, version)!!.pack)
        assertEquals("old", tiles.window(14, water.first, water.second, version)!!.pack)
        assertEquals(older, tiles.window(14, water.first, water.second, version)!!.date)
    }

    @Test
    fun bytesThatAreNotThePublishedOnesAreRefused() {
        val c = country()
        val list = listOf(publish("netherlands", c, date))
        val tiles = WindowTiles({ list }, dir)
        // a window's bytes changed after its index was made
        gh.assets["netherlands.tiles"] = c.tiles.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertThrows(IOException::class.java) { tiles.window(14, westerkerk.first, westerkerk.second, version) }
        // a server that sends the whole file for a range
        gh.assets["netherlands.tiles"] = c.tiles
        gh.ignoreRanges = true
        assertThrows(IOException::class.java) { tiles.window(14, westerkerk.first, westerkerk.second, version) }
        gh.ignoreRanges = false
        assertArrayEquals(c.packs[westerkerk], tiles.window(14, westerkerk.first, westerkerk.second, version)!!.bytes)
        // an index that is not the one packs.json names
        gh.assets["netherlands.tiles.idx"] = country(of = cells.take(1)).index
        assertThrows(IOException::class.java) { WindowTiles({ list }, tempDir("again")).window(14, westerkerk.first, westerkerk.second, version) }
        // an index of other tiles than the file packs.json lists beside it
        val p = publish("netherlands", c, date)
        val shorter = p.copy(
            files = p.files.map { f ->
                if (f.kind != PackFile.TILES) f else f.copy(bytes = f.bytes - 1, parts = listOf(f.parts.single().copy(bytes = f.bytes - 1)))
            },
        )
        assertThrows(IOException::class.java) { WindowTiles({ listOf(shorter) }, tempDir("shorter")).window(14, westerkerk.first, westerkerk.second, version) }
    }
}
