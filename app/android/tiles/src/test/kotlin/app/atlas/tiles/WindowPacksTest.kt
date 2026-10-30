package app.atlas.tiles

import app.atlas.geo.BBox
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * A window's data from Atlas's own packs, on GitHub releases faked by MockWebServer: the index downloaded once and
 * kept, each window by a range of its own bytes (two where it spans two parts), held to the index; the newest pack that
 * holds a window, and only data newer than the window keeps; nothing where no pack holds it.
 */
class WindowPacksTest {
    private val gh = FakeGitHub()
    private val dir = tempDir("windowpacks")
    private val date = Instant.parse("2026-11-01T20:21:24Z")

    /** The Westerkerk's window (52.3745, 4.8840) and two of its neighbours. */
    private val westerkerk = 8414 to 5384
    private val cells = listOf(8413 to 5384, westerkerk, 8414 to 5385)

    @After
    fun close() {
        gh.close()
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = sha(b).joinToString("") { "%02x".format(it) }

    private fun gzip(s: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    /** A region's windows as `packer windows` writes them: each window's answer gzipped, back to back, and the index. */
    private class Region(val windows: ByteArray, val index: ByteArray, val data: Map<Pair<Int, Int>, ByteArray>)

    /** [pad] random hex digits in each window's data make it as big as a dense window's (about half as many bytes gzipped). */
    private fun region(at: Instant, of: List<Pair<Int, Int>> = cells, query: Int = WindowsIndex.QUERY, pad: Int = 0): Region {
        val out = ByteArrayOutputStream()
        val entries = ArrayList<WindowsIndex.Entry>()
        val data = HashMap<Pair<Int, Int>, ByteArray>()
        val random = java.util.Random(7)
        for ((x, y) in of.sortedWith(compareBy({ it.first }, { it.second }))) {
            val filler = CharArray(pad) { "0123456789abcdef"[random.nextInt(16)] }.concatToString()
            val gz = gzip("""{"version":0.6,"osm3s":{"timestamp_osm_base":"$at"},"elements":[{"type":"node","id":$x$y,"lat":52.37,"lon":4.88,"tags":{"x":"$at","pad":"$filler"}}]}""")
            entries += WindowsIndex.Entry(x, y, out.size().toLong(), gz.size, sha(gz))
            data[x to y] = gz
            out.write(gz)
        }
        val windows = out.toByteArray()
        val index = ByteArrayOutputStream().also { WindowsIndex.of(14, query, at, windows.size.toLong(), entries).write(it) }.toByteArray()
        return Region(windows, index, data)
    }

    /** [r] published as pack [id]: its windows file in parts of [split] bytes (whole when 0), its index whole. */
    private fun publish(id: String, r: Region, at: Instant, split: Int = 0, bbox: BBox = BBox(3.3, 50.7, 7.2, 53.6)): Pack {
        val chunks = if (split > 0) r.windows.toList().chunked(split).map { it.toByteArray() } else listOf(r.windows)
        val parts = chunks.mapIndexed { i, c ->
            val name = if (split > 0) "$id.windows.%03d".format(i) else "$id.windows"
            gh.assets[name] = c
            PackPart(gh.url(name), c.size.toLong(), null)
        }
        gh.assets["$id.windows.idx"] = r.index
        return Pack(
            id, id, "NL", bbox, at.toString(), "packs-20261102",
            listOf(
                PackFile(PackFile.WINDOWS, "$id.windows", r.windows.size.toLong(), hex(r.windows), parts),
                PackFile(PackFile.WINDOWS_INDEX, "$id.windows.idx", r.index.size.toLong(), hex(r.index), listOf(PackPart(gh.url("$id.windows.idx"), r.index.size.toLong(), null))),
            ),
        )
    }

    private fun ranges() = gh.storageRequests.map { it.headers["Range"] }

    @Test
    fun aWindowsDataComesByARangeOfItsOwnBytesWithTheIndexDownloadedOnce() {
        val r = region(date)
        val list = listOf(publish("netherlands", r, date))
        val packs = WindowPacks({ list }, dir)
        val got = packs.window(14, westerkerk.first, westerkerk.second, null)!!
        assertArrayEquals(r.data[westerkerk], got.gz)
        assertEquals(date, got.date)
        assertEquals("netherlands", got.pack)
        // the index whole, then the window's own bytes
        val start = r.data.getValue(8413 to 5384).size
        assertEquals(listOf(null, "bytes=$start-${start + got.gz.size - 1}"), ranges())
        // its neighbour: its bytes alone
        assertArrayEquals(r.data[8414 to 5385], packs.window(14, 8414, 5385, null)!!.gz)
        assertEquals(3, gh.storageRequests.size)
        // the app started again: the index kept in the folder serves
        assertArrayEquals(r.data[8413 to 5384], WindowPacks({ list }, dir).window(14, 8413, 5384, null)!!.gz)
        assertEquals(4, gh.storageRequests.size)
        assertEquals("bytes=0-${start - 1}", ranges().last())
    }

    @Test
    fun aWindowNoPackHoldsComesFromNone() {
        val list = listOf(publish("netherlands", region(date), date))
        val packs = WindowPacks({ list }, dir)
        // in the box, not in the index (the open sea beyond the polygon, or data the extract did not hold whole)
        assertNull(packs.window(14, 8414, 5386, null))
        // beyond the pack's box: its index is not even read
        val far = WindowPacks({ list }, tempDir("far"))
        val before = gh.storageRequests.size
        assertNull(far.window(14, 0, 0, null))
        assertEquals(before, gh.storageRequests.size)
        // another level than the phone's windows, no list of packs to be had
        assertNull(packs.window(13, 4207, 2692, null))
        // no list to be had: asked for again only half a minute on
        var now = 0L
        var asked = 0
        val offline = WindowPacks({ asked++; if (now < WindowPacks.LIST_RETRY_MS) throw IOException("HTTP 404 fetching the pack list") else list }, dir, clock = { now })
        assertNull(offline.window(14, 8414, 5384, null))
        now = WindowPacks.LIST_RETRY_MS - 1
        assertNull(offline.window(14, 8414, 5384, null))
        assertEquals(1, asked)
        now = WindowPacks.LIST_RETRY_MS
        assertEquals("netherlands", offline.window(14, 8414, 5384, null)!!.pack)
        assertEquals(2, asked)
        // an index made for another window query is not used
        val other = listOf(publish("other", region(date, query = WindowsIndex.QUERY + 1), date))
        assertNull(WindowPacks({ other }, tempDir("other")).window(14, 8414, 5384, null))
    }

    @Test
    fun onlyDataNewerThanAWindowKeepsIsTakenFromTheNewestPackThatHoldsIt() {
        val older = date.minusSeconds(30 * 86_400L)
        val list = listOf(
            publish("old", region(older), older),
            // the newer pack holds two of the three windows
            publish("new", region(date, cells.take(2)), date),
        )
        val packs = WindowPacks({ list }, dir)
        assertEquals("new", packs.window(14, westerkerk.first, westerkerk.second, null)!!.pack)
        assertEquals("old", packs.window(14, 8414, 5385, null)!!.pack)
        assertEquals("new", packs.window(14, westerkerk.first, westerkerk.second, older)!!.pack)
        assertNull(packs.window(14, westerkerk.first, westerkerk.second, date))
        assertNull(packs.window(14, 8414, 5385, older))
    }

    @Test
    fun aWindowAcrossTwoPartsComesInARangeOfEach() {
        val r = region(date)
        val first = r.data.getValue(8413 to 5384).size
        // the first part ends inside the Westerkerk's window
        val list = listOf(publish("netherlands", r, date, split = first + 10))
        val got = WindowPacks({ list }, dir).window(14, westerkerk.first, westerkerk.second, null)!!
        assertArrayEquals(r.data[westerkerk], got.gz)
        assertEquals(listOf(null, "bytes=$first-${first + 9}", "bytes=0-${got.gz.size - 11}"), ranges())
    }

    @Test
    fun aDenseWindowOnASlowLineStillComesAndAServerThatFailsLeavesItToOverpass() {
        val r = region(date, pad = 600_000)
        val list = listOf(publish("netherlands", r, date))
        // the app's shared client limits a whole call to 60 s: here to a second, with a window that takes two to come
        val shared = okhttp3.OkHttpClient.Builder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val packs = WindowPacks({ list }, dir, shared)
        assertEquals("netherlands", packs.window(14, 8413, 5384, null)!!.pack)
        gh.bytesPerSecond = 150_000
        val t0 = System.nanoTime()
        val got = packs.window(14, westerkerk.first, westerkerk.second, null)!!
        assertTrue("took ${(System.nanoTime() - t0) / 1e9} s", System.nanoTime() - t0 > 1_200_000_000L)
        assertArrayEquals(r.data[westerkerk], got.gz)
        // GitHub answers 503: the window's data does not come, and Windows asks Overpass for it (its packed() takes the throw)
        gh.bytesPerSecond = 0
        gh.down = true
        assertThrows(IOException::class.java) { packs.window(14, 8414, 5385, null) }
    }

    @Test
    fun bytesThatAreNotThePublishedOnesAreRefused() {
        val r = region(date)
        val list = listOf(publish("netherlands", r, date))
        val packs = WindowPacks({ list }, dir)
        // a window's bytes changed after its index was made
        gh.assets["netherlands.windows"] = r.windows.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertThrows(IOException::class.java) { packs.window(14, 8414, 5385, null) }
        // a server that sends the whole file for a range
        gh.assets["netherlands.windows"] = r.windows
        gh.ignoreRanges = true
        assertThrows(IOException::class.java) { packs.window(14, 8414, 5385, null) }
        gh.ignoreRanges = false
        assertArrayEquals(r.data[8414 to 5385], packs.window(14, 8414, 5385, null)!!.gz)
        // an index that is not the one packs.json names
        gh.assets["netherlands.windows.idx"] = region(date, cells.take(1)).index
        assertThrows(IOException::class.java) { WindowPacks({ list }, tempDir("again")).window(14, 8414, 5385, null) }
    }
}
