package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The windows the phone builds and builds again (ENGINE.md step 3), with a core and servers of the test's own: a window
 * the core wants is fetched whole, built and its data kept; one in view is left alone for a week, then asked only for
 * the changes since its data's date and built from the merge; nothing changed only marks it checked; a refused merge, no
 * changes or data fetched whole a month ago fetch it whole; an answer older than the kept data is never built; a window
 * whose data does not come waits twice as long after each failure in a row, one whose data the core refuses a day; a
 * window built lately is not fetched again though the core still wants its place. The user's edits are built into
 * every window and never kept with its data; an edit builds the windows whose boxes hold its place again from their
 * kept data, before anything else.
 */
class WindowsTest {
    private val dir: File = Files.createTempDirectory("windows").toFile()
    private val w = MapCore.Window(14, 8414, 5383)
    private var now = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val day = 24 * 3600 * 1000L

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun answer(base: String, whole: String? = null, id: Int = 1): ByteArray =
        (
            "{\n  \"version\": 0.6,\n  \"osm3s\": {\n    \"timestamp_osm_base\": \"$base\"\n  },\n" +
                (if (whole != null) "  \"atlas\": {\n    \"whole\": \"$whole\"\n  },\n" else "") +
                "  \"elements\": [\n\n{\n  \"type\": \"node\",\n  \"id\": $id,\n  \"lat\": 52.0,\n  \"lon\": 4.0\n}\n\n  ]\n}\n"
            ).toByteArray()

    /** A kept data file's JSON. */
    private fun unzipped(f: File): String {
        val plain = File(dir, "unzipped.json")
        Windows.unzip(f, plain)
        return plain.readText().also { plain.delete() }
    }

    private fun text(b: ByteBuffer): String {
        val bytes = ByteArray(b.remaining())
        b.duplicate().get(bytes)
        return String(bytes)
    }

    private inner class Core : Windows.Core {
        var wanted = listOf<MapCore.Window>()
        var inView = listOf<MapCore.Window>()
        val built = ArrayList<String>()
        val merged = ArrayList<Pair<String, String>>()
        var mergeWith: (File, ByteBuffer) -> ByteArray? = { _, _ -> null }
        var refusal = ""

        override fun wanted() = wanted
        override fun inView() = inView
        override fun box(w: MapCore.Window) = doubleArrayOf(52.36, 4.87, 52.38, 4.89)
        override fun build(w: MapCore.Window, json: File, file: File): String {
            built += json.readText()
            if (refusal.isNotEmpty()) return refusal
            file.writeText("pack ${built.size}")
            return ""
        }

        override fun merge(w: MapCore.Window, kept: File, diff: ByteBuffer, out: File): Boolean {
            merged += kept.readText() to text(diff)
            val bytes = mergeWith(kept, diff) ?: return false
            out.writeBytes(bytes)
            return true
        }

        /** The edits' JSON of each edit asked. */
        val edited = ArrayList<String>()

        /** The data with the edits; null where none applies. */
        var editWith: (File) -> ByteArray? = { null }
        var editRefusal: String? = null

        override fun edit(w: MapCore.Window, kept: File, edits: ByteBuffer, out: File): Boolean {
            edited += text(edits)
            editRefusal?.let { throw IllegalStateException(it) }
            val bytes = editWith(kept) ?: return false
            out.writeBytes(bytes)
            return true
        }
    }

    private val fetched = ArrayList<DoubleArray>()
    private val diffs = ArrayList<Pair<String, String>>()
    private var whole: () -> ByteArray = { answer("2026-10-22T11:59:00Z") }
    private var changes: (String, String) -> ByteArray = { _, _ -> "<osm/>".toByteArray() }
    private var drawn = listOf<UserEdit>()
    private val windows = Windows(
        dir,
        fetch = { b, file -> fetched += b; file.writeBytes(whole()) },
        fetchDiff = { _, since, notBefore -> diffs += since to notBefore; changes(since, notBefore) },
        clock = { now },
        edits = { drawn },
    )

    /** FillIn's answer for a building whose colour is a guess. */
    private val brown = UserEdit("way", 266556292L, mapOf("building:colour" to "#5c3a30"), mapOf("building:colour" to null))

    /** The window built once, whole, from data of 2026-10-22T11:59:00Z. */
    private fun built(core: Core) {
        core.wanted = listOf(w)
        assertTrue(windows.step(core))
        core.wanted = emptyList()
        core.inView = listOf(w)
    }

    @Test
    fun aWindowTheCoreWantsIsFetchedWholeBuiltAndItsDataKept() {
        val core = Core()
        built(core)
        assertEquals(1, fetched.size)
        assertEquals(listOf(String(answer("2026-10-22T11:59:00Z"))), core.built)
        assertTrue(windows.fileOf(w).exists())
        assertEquals("14-8414-5383.json.gz", windows.dataOf(w).name)
        assertEquals(String(answer("2026-10-22T11:59:00Z")), unzipped(windows.dataOf(w)))
        assertEquals(now, windows.dataOf(w).lastModified())
        val head = Windows.Head.of(answer("2026-10-22T11:59:00Z"))!!
        assertEquals(Instant.parse("2026-10-22T11:59:00Z"), head.base)
        assertEquals(head.base, head.whole)
    }

    /** Atlas's packs as a window's first source: what each asked got, and the windows and dates asked. */
    private var packed: (MapCore.Window, Instant?) -> Windows.Packed? = { _, _ -> null }
    private val packsAsked = ArrayList<Pair<MapCore.Window, Instant?>>()
    private val withPacks = Windows(
        dir,
        fetch = { b, file -> fetched += b; file.writeBytes(whole()) },
        fetchDiff = { _, since, notBefore -> diffs += since to notBefore; changes(since, notBefore) },
        clock = { now },
        packs = { w, after -> packsAsked += w to after; packed(w, after) },
    )

    private fun gzip(b: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** The Netherlands' pack of 26 October: Overpass's answer for the window as of the extract's date. */
    private val nl = answer("2026-10-26T20:21:24Z", id = 8)

    @Test
    fun aWindowAPackHoldsIsBuiltFromThePacksDataWithNoServerAsked() {
        packed = { _, _ -> Windows.Packed(gzip(nl), Instant.parse("2026-10-26T20:21:24Z"), "netherlands") }
        val core = Core()
        core.wanted = listOf(w)
        assertTrue(withPacks.step(core))
        assertEquals(listOf(w to null), packsAsked)
        assertTrue(fetched.isEmpty())
        assertEquals(listOf(String(nl)), core.built)
        // kept as any window's data: its date is the extract's, and it counts as fetched now
        assertEquals(String(nl), unzipped(withPacks.dataOf(w)))
        assertEquals(Instant.parse("2026-10-26T20:21:24Z"), Windows.headOf(withPacks.dataOf(w))!!.base)
        assertEquals(now, withPacks.dataOf(w).lastModified())
        // a week later Overpass is asked for the changes since the extract's date, as for any window
        core.wanted = emptyList()
        core.inView = listOf(w)
        now += 8 * day
        core.mergeWith = { _, _ -> answer("2026-10-30T11:58:00Z", whole = "2026-10-26T20:21:24Z", id = 9) }
        assertTrue(withPacks.step(core))
        assertEquals(listOf("2026-10-26T19:51:24Z" to "2026-10-26T20:21:24Z"), diffs)
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aWindowNoPackHoldsOrWhosePackFailsIsFetchedFromOverpass() {
        val core = Core()
        core.wanted = listOf(w)
        // none holds it
        assertTrue(withPacks.step(core))
        assertEquals(1, fetched.size)
        assertEquals(listOf(String(answer("2026-10-22T11:59:00Z"))), core.built)
        // a pack holds it, but its bytes did not come, or do not read
        for (failing in listOf<(MapCore.Window, Instant?) -> Windows.Packed?>(
            { _, _ -> throw IOException("HTTP 503 for bytes 0 to 120 of netherlands.windows") },
            { _, _ -> Windows.Packed(nl, Instant.parse("2026-10-26T20:21:24Z"), "netherlands") },
        )) {
            packed = failing
            dir.listFiles().orEmpty().forEach { it.delete() }
            assertTrue(withPacks.step(core))
        }
        assertEquals(3, fetched.size)
        assertEquals(3, packsAsked.size)
    }

    @Test
    fun aWindowFetchedWholeAgainTakesAPacksDataOnlyWhereItIsNewerThanItsOwn() {
        packed = { _, after -> Windows.Packed(gzip(nl), Instant.parse("2026-10-26T20:21:24Z"), "netherlands").takeIf { after == null } }
        val core = Core()
        core.wanted = listOf(w)
        assertTrue(withPacks.step(core))
        core.wanted = emptyList()
        core.inView = listOf(w)
        // a week on, no server gives the changes: fetched whole, from Overpass, as the pack is no newer
        now += 8 * day
        changes = { _, _ -> throw IOException("no server answered") }
        whole = { answer("2026-11-03T12:00:00Z", id = 10) }
        assertTrue(withPacks.step(core))
        assertEquals(w to Instant.parse("2026-10-26T20:21:24Z"), packsAsked.last())
        assertEquals(1, fetched.size)
        assertEquals(String(answer("2026-11-03T12:00:00Z", id = 10)), core.built.last())
        // the month's new pack, newer than the data kept, is taken instead of Overpass
        now += 8 * day
        val next = answer("2026-11-09T20:21:00Z", id = 11)
        packed = { _, after -> Windows.Packed(gzip(next), Instant.parse("2026-11-09T20:21:00Z"), "netherlands").takeIf { after!!.isBefore(Instant.parse("2026-11-09T20:21:00Z")) } }
        assertTrue(withPacks.step(core))
        assertEquals(1, fetched.size)
        assertEquals(String(next), core.built.last())
    }

    /** The countries' tiles as a window's first source: what each asked got, and the windows asked. */
    private var tiled: (MapCore.Window) -> Windows.Tiled? = { null }
    private val tilesAsked = ArrayList<MapCore.Window>()

    /** The windows' status while the tiles were asked. */
    private val statusAsked = ArrayList<Windows.Status>()
    private lateinit var withTiles: Windows

    init {
        withTiles = Windows(
            dir,
            fetch = { b, file -> fetched += b; file.writeBytes(whole()) },
            fetchDiff = { _, since, notBefore -> diffs += since to notBefore; changes(since, notBefore) },
            clock = { now },
            edits = { drawn },
            packs = { w, after -> packsAsked += w to after; packed(w, after) },
            tiles = { w -> tilesAsked += w; statusAsked += withTiles.status; tiled(w) },
        )
    }

    /** The Netherlands' tiles of 26 October: the window's pack, as the phone's core builds it from [nl]. */
    private val nlDate = Instant.parse("2026-10-26T20:21:24Z")
    private val nlPack = "the Netherlands' pack of 14/8414/5383".toByteArray()

    /** The window taken from the Netherlands' tiles, in view. */
    private fun takenFromTheTiles(core: Core) {
        tiled = { Windows.Tiled(nlPack, nlDate, "netherlands") }
        core.wanted = listOf(w)
        assertTrue(withTiles.step(core))
        core.wanted = emptyList()
        core.inView = listOf(w)
    }

    @Test
    fun aWindowThePhoneHasNoneOfIsTakenFromItsCountrysTilesWithNoDataFetchedOrBuilt() {
        val core = Core()
        takenFromTheTiles(core)
        assertEquals(listOf(w), tilesAsked)
        // written in its place, with the date of the data it was built from beside it
        assertArrayEquals(nlPack, withTiles.fileOf(w).readBytes())
        assertEquals("2026-10-26T20:21:24Z netherlands", withTiles.tiledOf(w).readText().trim())
        assertEquals(now, withTiles.tiledOf(w).lastModified())
        assertEquals(setOf("14-8414-5383.atlas", "14-8414-5383.tiles"), dir.list()!!.toSet())
        // nothing fetched or built: it keeps no data
        assertTrue(fetched.isEmpty())
        assertTrue(packsAsked.isEmpty())
        assertTrue(core.built.isEmpty())
        // the map's status showed the range fetch as fetching from the country's tiles, and the windows rest after it
        assertTrue((statusAsked.single() as Windows.Status.Fetching).tiles)
        assertEquals(Windows.Status.Idle, withTiles.status)
        // not asked for again while the core still wants its place, nor refreshed within the week
        core.wanted = listOf(w)
        repeat(10) {
            now += 500
            assertFalse(withTiles.step(core))
        }
        now += 6 * day
        assertFalse(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        assertTrue(fetched.isEmpty())
        assertTrue(packsAsked.isEmpty())
    }

    @Test
    fun offlineAWindowComesOnlyFromTheTilesThePhoneKeepsAndTheRestWaitForTheNetwork() {
        var net = false
        val offline = Windows(
            dir,
            fetch = { b, file -> fetched += b; file.writeBytes(whole()) },
            clock = { now },
            packs = { w, after -> packsAsked += w to after; packed(w, after) },
            tiles = { w -> tilesAsked += w; tiled(w) },
            online = { net },
        )
        val core = Core()
        val other = MapCore.Window(14, 8415, 5383)
        // the kept country holds the first window: taken offline
        tiled = { x -> if (x == w) Windows.Tiled(nlPack, nlDate, "netherlands") else null }
        core.wanted = listOf(w, other)
        assertTrue(offline.step(core))
        assertArrayEquals(nlPack, offline.fileOf(w).readBytes())
        // the other it does not hold: no data fetched, no failure counted, and not asked of the tiles again while offline
        assertFalse(offline.step(core))
        assertEquals(listOf(w, other), tilesAsked)
        repeat(5) {
            now += 500
            assertFalse(offline.step(core))
        }
        assertEquals(2, tilesAsked.size)
        assertTrue(fetched.isEmpty())
        assertTrue(packsAsked.isEmpty())
        assertEquals(Windows.Status.Idle, offline.status)
        // nor is a window refreshed offline, however old
        core.wanted = emptyList()
        core.inView = listOf(w)
        now += 30 * day
        assertFalse(offline.step(core))
        assertTrue(fetched.isEmpty())
        // the network back: the other is asked again at once, and built from its data as before
        net = true
        core.wanted = listOf(other)
        assertTrue(offline.step(core))
        assertEquals(3, tilesAsked.size)
        assertEquals(1, fetched.size)
    }

    @Test
    fun whatTheFolderHoldsIsCountedForTheMapsBoard() {
        val core = Core()
        takenFromTheTiles(core)
        val built = MapCore.Window(14, 8415, 5383)
        tiled = { null }
        core.wanted = listOf(built)
        assertTrue(withTiles.step(core))
        val kept = Windows.kept(dir)
        assertEquals(1, kept.tiled)
        assertEquals(1, kept.built)
        assertEquals(2, kept.places)
        assertEquals(withTiles.fileOf(w).length() + withTiles.fileOf(built).length() + withTiles.dataOf(built).length(), kept.bytes)
        // within a box: the Netherlands' holds both, Paris's neither
        assertEquals(2, Windows.kept(dir, doubleArrayOf(50.7, 3.3, 53.6, 7.2)).places)
        assertEquals(Windows.Kept(0, 0, 0L), Windows.kept(dir, doubleArrayOf(48.8, 2.2, 48.9, 2.5)))
        assertEquals(Windows.Kept(0, 0, 0L), Windows.kept(null))
    }

    @Test
    fun aWindowTheTilesHoldWithNothingToDrawIsMarkedAndNotAskedForAgainUntilItIsDue() {
        tiled = { Windows.Tiled(ByteArray(0), nlDate, "netherlands") }
        packed = { _, _ -> Windows.Packed(gzip(nl), nlDate, "netherlands") }
        val core = Core()
        core.wanted = listOf(w)
        // nothing to hand the core: no pack, the mark alone (PackList lists none)
        assertFalse(withTiles.step(core))
        assertFalse(withTiles.fileOf(w).exists())
        assertEquals(setOf("14-8414-5383.tiles"), dir.list()!!.toSet())
        assertEquals(Windows.Status.Idle, withTiles.status)
        repeat(10) {
            now += 500
            assertFalse(withTiles.step(core))
        }
        now += 6 * day
        assertFalse(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        assertTrue(packsAsked.isEmpty())
        // a week on, the core still wanting its place: its release's data from the packs, built as data from now on
        now += 2 * day
        assertTrue(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        assertEquals(listOf(w to null), packsAsked)
        assertEquals(listOf(String(nl)), core.built)
        assertEquals(String(nl), unzipped(withTiles.dataOf(w)))
        assertFalse(withTiles.tiledOf(w).exists())
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aWindowNoCountrysTilesHoldOrWhoseTilesFailIsBuiltFromItsDataAsBefore() {
        val core = Core()
        core.wanted = listOf(w)
        // no country holds it, or another core built its tiles: its data, as before
        assertTrue(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        assertEquals(1, fetched.size)
        assertEquals(listOf(String(answer("2026-10-22T11:59:00Z"))), core.built)
        assertFalse(withTiles.tiledOf(w).exists())
        // a country holds it, but its bytes did not come whole: why, for the Maps board, until a window comes from them
        assertNull(withTiles.tilesProblem)
        tiled = { throw IOException("window 14/8414/5383 of pack netherlands: not the tiles its index names") }
        dir.listFiles().orEmpty().forEach { it.delete() }
        assertTrue(withTiles.step(core))
        assertEquals(2, tilesAsked.size)
        assertEquals(2, fetched.size)
        assertFalse(withTiles.tiledOf(w).exists())
        assertEquals("window 14/8414/5383 of pack netherlands: not the tiles its index names", withTiles.tilesProblem)
        // a window built from data is not taken from the tiles again, though they hold it: it goes on from its data
        tiled = { Windows.Tiled(nlPack, nlDate, "netherlands") }
        core.wanted = emptyList()
        core.inView = listOf(w)
        now += 8 * day
        core.mergeWith = { _, _ -> null }
        assertFalse(withTiles.step(core))
        assertEquals(2, tilesAsked.size)
        assertEquals(1, diffs.size)
    }

    @Test
    fun aWindowWhoseTilesFailedIsAskedOfThemAgainInHalfAMinuteNotAfterItsDatasWait() {
        val core = Core()
        core.wanted = listOf(w)
        // GitHub's range failed once, and every Overpass server with it: its data waits five minutes
        tiled = { throw IOException("timeout") }
        whole = { throw IOException("every server is busy") }
        assertFalse(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        assertEquals(1, fetched.size)
        assertEquals(now + Windows.RETRY_MS, (withTiles.status as Windows.Status.Waiting).until)
        // within the tiles' half minute nothing is asked
        now += Windows.TILES_RETRY_MS - 1_000
        assertFalse(withTiles.step(core))
        assertEquals(1, tilesAsked.size)
        // after it the tiles are asked again, whatever its data's wait; missing again, they wait twice as long
        now += 2_000
        assertFalse(withTiles.step(core))
        assertEquals(2, tilesAsked.size)
        assertTrue(withTiles.status is Windows.Status.Waiting)
        now += Windows.TILES_RETRY_MS + 1_000
        assertFalse(withTiles.step(core))
        assertEquals(2, tilesAsked.size)
        // GitHub answers again: the window comes from the tiles, its data neither fetched again nor waited for
        tiled = { Windows.Tiled(nlPack, nlDate, "netherlands") }
        now += Windows.TILES_RETRY_MS
        assertTrue(withTiles.step(core))
        assertEquals(3, tilesAsked.size)
        assertArrayEquals(nlPack, withTiles.fileOf(w).readBytes())
        assertEquals(1, fetched.size)
        assertEquals(Windows.Status.Idle, withTiles.status)
        assertNull(withTiles.tilesProblem)
    }

    @Test
    fun whileAWindowsDataComesTheWindowsTheTilesHoldStillComeFromThem() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val near = MapCore.Window(14, 8415, 5383)
        val asked: MutableList<MapCore.Window> = Collections.synchronizedList(ArrayList())
        val lanes = Windows(
            dir,
            fetch = { _, file -> started.countDown(); release.await(20, TimeUnit.SECONDS); file.writeBytes(whole()) },
            clock = { now },
            tiles = { x -> asked += x; if (x == near) Windows.Tiled(nlPack, nlDate, "netherlands") else null },
        )
        val core = Core()
        // the nearest window no country's tiles hold: its data, from a server that takes its time
        core.wanted = listOf(w, near)
        var first = false
        val data = Thread { first = lanes.step(core) }.apply { start() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        assertFalse((lanes.status as Windows.Status.Fetching).tiles)
        // meanwhile the next step takes the other window from its country's tiles, at once
        assertTrue(lanes.step(core))
        assertArrayEquals(nlPack, lanes.fileOf(near).readBytes())
        assertEquals(listOf(w, near), asked.toList())
        // the window under way is the data's alone: no step takes it from the tiles, nor asks for its data again
        assertFalse(lanes.step(core))
        assertEquals(2, asked.size)
        assertFalse((lanes.status as Windows.Status.Fetching).tiles)
        release.countDown()
        data.join(20_000)
        assertTrue(first)
        assertEquals("pack 1", lanes.fileOf(w).readText())
        assertEquals(Windows.Status.Idle, lanes.status)
    }

    @Test
    fun aWindowFromTheTilesIsDueAWeekOnAndTakesItsReleasesDataThenTheChangesSince() {
        packed = { _, _ -> Windows.Packed(gzip(nl), nlDate, "netherlands") }
        val core = Core()
        takenFromTheTiles(core)
        now += 8 * day
        val merged = answer("2026-11-03T11:58:00Z", whole = "2026-10-26T20:21:24Z", id = 9)
        core.mergeWith = { _, _ -> merged }
        assertTrue(withTiles.step(core))
        // its release's data, whatever the window held, kept without a build: its pack is that data's
        assertEquals(listOf(w to null), packsAsked)
        // then the changes since its date, merged and built
        assertEquals(listOf("2026-10-26T19:51:24Z" to "2026-10-26T20:21:24Z"), diffs)
        assertEquals(String(nl), core.merged.single().first)
        assertEquals(listOf(String(merged)), core.built)
        assertEquals(String(merged), unzipped(withTiles.dataOf(w)))
        assertEquals(now, withTiles.dataOf(w).lastModified())
        assertFalse(withTiles.tiledOf(w).exists())
        assertTrue(fetched.isEmpty())
        // from then on a window built from data: the next week, the changes since the merge's date
        now += 8 * day
        core.mergeWith = { _, _ -> null }
        assertFalse(withTiles.step(core))
        assertEquals("2026-11-03T11:28:00Z" to "2026-11-03T11:58:00Z", diffs.last())
        assertEquals(1, tilesAsked.size)
    }

    @Test
    fun aDueWindowFromTheTilesWhereNothingChangedKeepsItsPackAndItsReleasesData() {
        packed = { _, _ -> Windows.Packed(gzip(nl), nlDate, "netherlands") }
        val core = Core()
        takenFromTheTiles(core)
        now += 8 * day
        core.mergeWith = { _, _ -> null }
        assertFalse(withTiles.step(core))
        assertTrue(core.built.isEmpty())
        assertArrayEquals(nlPack, withTiles.fileOf(w).readBytes())
        assertEquals(String(nl), unzipped(withTiles.dataOf(w)))
        assertEquals(now, withTiles.dataOf(w).lastModified())
        assertEquals(setOf("14-8414-5383.atlas", "14-8414-5383.json.gz"), dir.list()!!.toSet())
        // no server gives the changes: the window stays due, and is fetched whole, never from data older than its tiles
        now += 8 * day
        changes = { _, _ -> throw IOException("no server answered") }
        whole = { answer("2026-06-01T08:52:28Z", id = 6) }
        assertFalse(withTiles.step(core))
        assertEquals(1, fetched.size)
        assertTrue(core.built.isEmpty())
    }

    @Test
    fun whereNoPackGivesItsReleasesDataADueWindowFromTheTilesIsFetchedWholeNeverFromOlderData() {
        val core = Core()
        takenFromTheTiles(core)
        now += 8 * day
        // a server months behind: never built, the window waits and keeps its pack from the tiles
        whole = { answer("2026-06-01T08:52:28Z", id = 6) }
        assertFalse(withTiles.step(core))
        assertEquals(listOf(w to null), packsAsked.toList())
        assertEquals(1, fetched.size)
        assertTrue(core.built.isEmpty())
        assertArrayEquals(nlPack, withTiles.fileOf(w).readBytes())
        assertTrue(withTiles.tiledOf(w).exists())
        // a newer answer: built from it, a window built from data from then on
        now += Windows.RETRY_MS + 1
        whole = { answer("2026-11-03T12:00:00Z", id = 10) }
        assertTrue(withTiles.step(core))
        assertEquals(2, fetched.size)
        assertEquals(listOf(String(answer("2026-11-03T12:00:00Z", id = 10))), core.built)
        assertFalse(withTiles.tiledOf(w).exists())
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), Windows.headOf(withTiles.dataOf(w))!!.base)
    }

    @Test
    fun theUsersEditsReachingAWindowFromTheTilesBuildItFromItsReleasesDataWithTheEdits() {
        packed = { _, _ -> Windows.Packed(gzip(nl), nlDate, "netherlands") }
        val core = Core()
        takenFromTheTiles(core)
        drawn = listOf(brown)
        core.editWith = { kept -> (kept.readText() + " edited").toByteArray() }
        withTiles.edited(52.37, 4.88)
        assertTrue(withTiles.step(core))
        // its release's data, kept as it came, built with the edit; no server asked
        assertEquals(listOf(w to null), packsAsked)
        assertEquals(listOf(String(nl) + " edited"), core.built)
        assertEquals(String(nl), unzipped(withTiles.dataOf(w)))
        assertEquals(setOf("14-8414-5383.atlas", "14-8414-5383.json.gz"), dir.list()!!.toSet())
        assertTrue(fetched.isEmpty())
        assertTrue(diffs.isEmpty())
        // its data counts as fetched when its pack came from the tiles: it is still due a week after that
        now += 7 * day + 1
        core.mergeWith = { _, _ -> null }
        assertFalse(withTiles.step(core))
        assertEquals(listOf("2026-10-26T19:51:24Z" to "2026-10-26T20:21:24Z"), diffs)
    }

    @Test
    fun aWindowInViewIsLeftAloneForAWeekThenAskedOnlyForTheChangesAndBuiltFromTheMerge() {
        val core = Core()
        built(core)
        now += 6 * day
        assertFalse(windows.step(core))
        assertTrue(diffs.isEmpty())
        now += 2 * day
        val merged = answer("2026-10-30T11:58:00Z", whole = "2026-10-22T11:59:00Z", id = 2)
        core.mergeWith = { _, _ -> merged }
        changes = { _, _ -> "<osm>the changes</osm>".toByteArray() }
        assertTrue(windows.step(core))
        // since the kept data's date, less half an hour; from a server not behind it
        assertEquals(listOf("2026-10-22T11:29:00Z" to "2026-10-22T11:59:00Z"), diffs)
        assertEquals(1, fetched.size)
        assertEquals(String(answer("2026-10-22T11:59:00Z")) to "<osm>the changes</osm>", core.merged.single())
        assertEquals(String(merged), core.built.last())
        assertEquals(String(merged), unzipped(windows.dataOf(w)))
        val head = Windows.Head.of(merged)!!
        assertEquals(Instant.parse("2026-10-30T11:58:00Z"), head.base)
        assertEquals(Instant.parse("2026-10-22T11:59:00Z"), head.whole)
        // and the next week asks from the merge's date
        now += 8 * day
        assertTrue(windows.step(core))
        assertEquals("2026-10-30T11:28:00Z" to "2026-10-30T11:58:00Z", diffs.last())
    }

    @Test
    fun nothingChangedOnlyMarksTheWindowChecked() {
        val core = Core()
        built(core)
        now += 8 * day
        core.mergeWith = { _, _ -> null }
        assertFalse(windows.step(core))
        assertEquals(1, core.built.size)
        assertEquals(now, windows.dataOf(w).lastModified())
        now += 6 * day
        assertFalse(windows.step(core))
        assertEquals(1, diffs.size)
    }

    @Test
    fun aRefusedMergeOrNoChangesFetchTheWindowWhole() {
        val core = Core()
        built(core)
        now += 8 * day
        core.mergeWith = { _, _ -> throw IllegalStateException("the diff is refused: unsupported") }
        whole = { answer("2026-10-30T12:00:00Z", id = 3) }
        assertTrue(windows.step(core))
        assertEquals(2, fetched.size)
        assertEquals(String(answer("2026-10-30T12:00:00Z", id = 3)), core.built.last())
        now += 8 * day
        changes = { _, _ -> throw IOException("no server answered") }
        whole = { answer("2026-11-07T12:00:00Z", id = 4) }
        assertTrue(windows.step(core))
        assertEquals(3, fetched.size)
        assertEquals(String(answer("2026-11-07T12:00:00Z", id = 4)), core.built.last())
    }

    @Test
    fun dataFetchedWholeFourWeeksAgoIsFetchedWholeAgain() {
        val core = Core()
        built(core)
        now += 8 * day
        core.mergeWith = { _, _ -> answer("2026-10-30T11:58:00Z", whole = "2026-10-22T11:59:00Z", id = 2) }
        assertTrue(windows.step(core))
        now += 21 * day // the data was fetched whole 29 days ago
        whole = { answer("2026-11-20T12:00:00Z", id = 5) }
        assertTrue(windows.step(core))
        assertEquals(1, diffs.size)
        assertEquals(2, fetched.size)
        assertEquals(Instant.parse("2026-11-20T12:00:00Z"), Windows.headOf(windows.dataOf(w))!!.whole)
    }

    @Test
    fun withNoChangesToAskForAnAgedWindowIsFetchedWholeButNeverFromOlderData() {
        val plain = Windows(dir, fetch = { b, file -> fetched += b; file.writeBytes(whole()) }, clock = { now })
        val core = Core()
        core.wanted = listOf(w)
        assertTrue(plain.step(core))
        core.wanted = emptyList()
        core.inView = listOf(w)
        now += 8 * day
        whole = { answer("2026-06-01T08:52:28Z", id = 6) }
        assertFalse(plain.step(core))
        now += Windows.RETRY_MS + 1
        whole = { answer("2026-10-30T12:00:00Z", id = 7) }
        assertTrue(plain.step(core))
        assertEquals(3, fetched.size)
        assertTrue(diffs.isEmpty())
        assertEquals(Instant.parse("2026-10-30T12:00:00Z"), Windows.headOf(plain.dataOf(w))!!.base)
    }

    @Test
    fun anAnswerOlderThanTheKeptDataIsNeverBuiltAndTheWindowWaits() {
        val core = Core()
        built(core)
        now += 8 * day
        core.mergeWith = { _, _ -> throw IllegalStateException("the merge is refused") }
        whole = { answer("2026-06-01T08:52:28Z", id = 6) } // a server months behind
        assertFalse(windows.step(core))
        assertEquals(1, core.built.size)
        assertEquals(String(answer("2026-10-22T11:59:00Z")), unzipped(windows.dataOf(w)))
        // tried again after five minutes, not before
        now += Windows.RETRY_MS / 2
        assertFalse(windows.step(core))
        assertEquals(2, fetched.size)
        now += Windows.RETRY_MS
        whole = { answer("2026-10-30T12:00:00Z", id = 7) }
        assertTrue(windows.step(core))
        assertEquals(3, fetched.size)
    }

    @Test
    fun aWindowWhoseDataTheCoreRefusesIsAskedForAgainOnlyADayLater() {
        val core = Core()
        core.wanted = listOf(w)
        core.refusal = "the data does not read: duplicate at byte 12"
        assertFalse(windows.step(core))
        assertEquals(1, fetched.size)
        assertFalse(windows.fileOf(w).exists())
        // the same data and core refuse it again: not asked for in five minutes, nor in half a day
        now += Windows.RETRY_MS + 1
        assertFalse(windows.step(core))
        now += day / 2
        assertFalse(windows.step(core))
        assertEquals(1, fetched.size)
        // a day after
        now += day / 2
        core.refusal = ""
        assertTrue(windows.step(core))
        assertEquals(2, fetched.size)
        assertTrue(windows.fileOf(w).exists())
    }

    @Test
    fun aWindowWhoseDataDoesNotComeWaitsTwiceAsLongAfterEachFailureInARow() {
        val core = Core()
        core.wanted = listOf(w)
        whole = { throw IOException("no server answered") }
        assertFalse(windows.step(core))
        assertEquals(1, fetched.size)
        // five minutes after the first failure; then ten after the second
        now += Windows.RETRY_MS + 1
        assertFalse(windows.step(core))
        assertEquals(2, fetched.size)
        now += Windows.RETRY_MS + 1
        assertFalse(windows.step(core))
        assertEquals(2, fetched.size)
        now += Windows.RETRY_MS
        assertFalse(windows.step(core))
        assertEquals(3, fetched.size)
        // however often it fails, two hours at most
        for (times in 4..12) {
            now += Windows.RETRY_CAP_MS + 1
            assertFalse(windows.step(core))
            assertEquals(times, fetched.size)
        }
        assertEquals(Windows.RETRY_MS, Windows.waitAfter(refused = false, times = 1))
        assertEquals(2 * Windows.RETRY_MS, Windows.waitAfter(refused = false, times = 2))
        assertEquals(Windows.RETRY_CAP_MS, Windows.waitAfter(refused = false, times = 12))
        assertEquals(Windows.RETRY_CAP_MS, Windows.waitAfter(refused = false, times = 1000))
        assertEquals(Windows.REFUSED_MS, Windows.waitAfter(refused = true, times = 1))
        // once its data comes, it is built, and its failures are forgotten: once its data has aged (a window built lately
        // is not fetched again, though the core still wants it), the next failure waits five minutes again
        now += Windows.RETRY_CAP_MS + 1
        whole = { answer("2026-10-22T11:59:00Z") }
        assertTrue(windows.step(core))
        assertEquals(13, fetched.size)
        now += Windows.REFRESH_MS + 1
        whole = { throw IOException("no server answered") }
        assertFalse(windows.step(core))
        assertEquals(14, fetched.size)
        now += Windows.RETRY_MS + 1
        assertFalse(windows.step(core))
        assertEquals(15, fetched.size)
    }

    @Test
    fun aWindowBuiltLatelyIsNotFetchedAgainThoughTheCoreStillWantsItsPlace() {
        // A window that holds nothing to draw (the core holds no tile there, so it keeps wanting the place), or one built
        // while the map has not handed the core its packs again. Until 22 October it was fetched again every half second.
        val core = Core()
        core.wanted = listOf(w)
        assertTrue(windows.step(core))
        repeat(10) {
            now += 500
            assertFalse(windows.step(core))
        }
        now += 6 * day
        assertFalse(windows.step(core))
        assertEquals(1, fetched.size)
        // the next window the core wants is fetched at once
        val next = MapCore.Window(14, 8415, 5383)
        core.wanted = listOf(w, next)
        assertTrue(windows.step(core))
        assertEquals(2, fetched.size)
        assertTrue(windows.fileOf(next).exists())
        // a week on, it is fetched whole again, never built from data older than it keeps, and built from newer data
        core.wanted = listOf(w)
        now += day
        whole = { answer("2026-06-01T08:52:28Z", id = 6) } // a server months behind
        assertFalse(windows.step(core))
        assertEquals(3, fetched.size)
        assertEquals(2, core.built.size)
        now += Windows.RETRY_MS + 1
        whole = { answer("2026-10-29T12:00:00Z", id = 8) }
        assertTrue(windows.step(core))
        assertEquals(4, fetched.size)
        assertEquals(String(answer("2026-10-29T12:00:00Z", id = 8)), core.built.last())
        assertEquals(String(answer("2026-10-29T12:00:00Z", id = 8)), unzipped(windows.dataOf(w)))
    }

    @Test
    fun theUsersEditsAreBuiltIntoTheWindowAndItsDataKeptWithoutThem() {
        val core = Core()
        core.editWith = { kept -> (kept.readText() + " edited").toByteArray() }
        drawn = listOf(brown)
        built(core)
        assertEquals(listOf(String(answer("2026-10-22T11:59:00Z")) + " edited"), core.built)
        assertEquals(1, core.edited.size)
        assertEquals(
            """[{"type":"way","id":266556292,"tags":[{"key":"building:colour","value":"#5c3a30","seen":null}]}]""",
            core.edited[0],
        )
        assertEquals(String(answer("2026-10-22T11:59:00Z")), unzipped(windows.dataOf(w)))
        assertEquals(setOf("14-8414-5383.atlas", "14-8414-5383.json.gz"), dir.list()!!.toSet())
    }

    @Test
    fun whereNoEditAppliesOrTheyDoNotReadTheWindowIsBuiltFromItsData() {
        val core = Core()
        drawn = listOf(brown)
        core.editRefusal = "the edits are refused: edit 0 is of a area"
        built(core)
        assertEquals(listOf(String(answer("2026-10-22T11:59:00Z"))), core.built)
        core.editRefusal = null
        windows.edited(52.37, 4.88)
        assertTrue(windows.step(core))
        assertEquals(2, core.edited.size)
        assertEquals(String(answer("2026-10-22T11:59:00Z")), core.built.last())
    }

    @Test
    fun anEditBuildsTheWindowsHoldingItsPlaceAgainFromTheirKeptDataFirst() {
        val core = Core()
        built(core)
        assertTrue(core.edited.isEmpty()) // no edits: none asked
        drawn = listOf(brown)
        core.editWith = { kept -> (kept.readText() + " edited").toByteArray() }
        // a place beyond its box: nothing to build
        windows.edited(52.30, 4.80)
        assertFalse(windows.step(core))
        // within it: built again from its kept data with the edit, before the window the core wants, no server asked
        core.wanted = listOf(MapCore.Window(14, 8415, 5383))
        windows.edited(52.37, 4.88)
        windows.edited(52.371, 4.881) // the same window once
        val fetches = fetched.size
        assertTrue(windows.step(core))
        assertEquals(fetches, fetched.size)
        assertEquals(String(answer("2026-10-22T11:59:00Z")) + " edited", core.built.last())
        assertEquals(String(answer("2026-10-22T11:59:00Z")), unzipped(windows.dataOf(w)))
        assertEquals(setOf("14-8414-5383.atlas", "14-8414-5383.json.gz"), dir.list()!!.toSet())
        assertTrue(windows.step(core))
        assertEquals(fetches + 1, fetched.size)
    }

    @Test
    fun aWindowTheCoreRefusesToBuildAgainForAnEditKeepsItsPack() {
        val core = Core()
        built(core)
        drawn = listOf(brown)
        core.refusal = "the data does not read"
        windows.edited(52.37, 4.88)
        assertFalse(windows.step(core))
        assertTrue(windows.fileOf(w).exists())
        assertEquals("pack 1", windows.fileOf(w).readText())
    }

    @Test
    fun theStatusSaysWhatTheWindowsDoForTheMap() {
        val core = Core()
        assertEquals(Windows.Status.Idle, windows.status)
        // no server answers: the window waits, and the map can say for how long
        core.wanted = listOf(w)
        whole = { throw java.io.IOException("at capacity") }
        assertFalse(windows.step(core))
        val waiting = windows.status as Windows.Status.Waiting
        assertFalse(waiting.refused)
        assertEquals(Windows.RETRY_MS, windows.remaining(waiting))
        now += Windows.RETRY_MS / 2
        assertEquals(Windows.RETRY_MS / 2, windows.remaining(waiting))
        // the data comes on the next try: built, and the windows rest
        now += Windows.RETRY_MS
        whole = { answer("2026-10-22T11:59:00Z") }
        assertTrue(windows.step(core))
        assertEquals(Windows.Status.Idle, windows.status)
        // the core refuses another window's data: it waits a day, refused
        core.wanted = listOf(MapCore.Window(14, 8415, 5383))
        core.refusal = "not a window"
        assertFalse(windows.step(core))
        assertTrue((windows.status as Windows.Status.Waiting).refused)
    }
}
