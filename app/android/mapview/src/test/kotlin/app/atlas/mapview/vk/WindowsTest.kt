package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant

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
