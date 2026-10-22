package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
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
 * whose data does not come waits twice as long after each failure in a row, one whose data the core refuses a day.
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
    }

    private val fetched = ArrayList<DoubleArray>()
    private val diffs = ArrayList<Pair<String, String>>()
    private var whole: () -> ByteArray = { answer("2026-10-22T11:59:00Z") }
    private var changes: (String, String) -> ByteArray = { _, _ -> "<osm/>".toByteArray() }
    private val windows = Windows(
        dir,
        fetch = { b, file -> fetched += b; file.writeBytes(whole()) },
        fetchDiff = { _, since, notBefore -> diffs += since to notBefore; changes(since, notBefore) },
        clock = { now },
    )

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
        // once its data comes, it is built, and its failures are forgotten: the next waits five minutes again
        now += Windows.RETRY_CAP_MS + 1
        whole = { answer("2026-10-22T11:59:00Z") }
        assertTrue(windows.step(core))
        assertEquals(13, fetched.size)
        whole = { throw IOException("no server answered") }
        assertFalse(windows.step(core))
        assertEquals(14, fetched.size)
        now += Windows.RETRY_MS + 1
        assertFalse(windows.step(core))
        assertEquals(15, fetched.size)
    }
}
