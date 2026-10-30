package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Instant

/**
 * The windows' folder held to its budget (the user, 30 October: "are all sources reliable and respond fast?"; since
 * then the windows live in the app's files, which Android never clears): where it holds more, the windows taken from a
 * country's tiles the phone keeps whole go first, then the least recently drawn or taken, until it holds nine tenths of
 * its budget; never a window the core wants or has in view. The folder is looked at whole at the first step, and each
 * window again after its work.
 */
class WindowsBudgetTest {
    private val dir: File = Files.createTempDirectory("budget").toFile()
    private val day = 24 * 3600 * 1000L
    private var now = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()

    /** A block of the disk: what the budget counts a file by. */
    private val block = 4096L

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    /**
     * Window ([x], 5384)'s files as the windows keep them, all of time [at]: a pack of [pack] blocks, and its data of
     * [data] blocks where it was built here, else the mark that it came from the tiles of pack [from].
     */
    private fun window(x: Int, at: Long, pack: Int = 10, data: Int = 0, from: String? = null): MapCore.Window {
        val base = "14-$x-5384"
        File(dir, "$base.atlas").writeBytes(ByteArray(pack * block.toInt()))
        if (data > 0) File(dir, "$base.json.gz").writeBytes(ByteArray(data * block.toInt()))
        if (from != null) File(dir, "$base.tiles").writeText("2026-10-26T20:21:24Z $from\n")
        dir.listFiles()!!.filter { it.name.startsWith("$base.") }.forEach { it.setLastModified(at) }
        return MapCore.Window(14, x, 5384)
    }

    private fun held(w: MapCore.Window) = dir.list()!!.any { it.startsWith("${w.z}-${w.x}-${w.y}.") }

    @Test
    fun theKeptCountrysWindowsGoFirstThenTheLeastLatelyDrawnOrTakenNeverOneInView() {
        val fromKept = window(8401, now - 3600_000L, from = "netherlands")
        val oldest = window(8402, now - 30 * day, data = 20)
        val drawnLately = window(8403, now - 25 * day, data = 20)
        val inView = window(8404, now - 60 * day, data = 20)
        val fromGitHub = window(8405, now - 20 * day, from = "belgium")
        val lately = window(8406, now - 2 * day, data = 20)
        // the index of the packs beside the windows is neither counted nor evicted
        File(dir, "index").mkdirs()
        File(dir, "index/netherlands-0123456789abcdef.tiles.idx").writeBytes(ByteArray(64 * block.toInt()))
        val budget = WindowsBudget(dir, keptPacks = { setOf("netherlands") }, limit = { 100 * block }, clock = { now })
        // 11 + 30 + 30 + 30 + 11 + 30 blocks; never listed yet, so due
        assertTrue(budget.over())
        budget.seen(listOf(drawnLately))
        val gone = budget.trim(keep = setOf(inView))
        // down to 90 blocks: the kept country's window, though taken an hour ago, then the oldest by when each was
        // drawn or taken; the one drawn lately stays, though its files are older than the one from GitHub
        assertEquals(listOf(fromKept, oldest, fromGitHub), gone)
        assertEquals(90 * block, budget.bytes)
        for (w in gone) assertFalse(held(w))
        for (w in listOf(drawnLately, inView, lately)) assertTrue(held(w))
        assertTrue(File(dir, "index/netherlands-0123456789abcdef.tiles.idx").isFile)
        assertFalse(budget.over())
        // a window written: looked at again by itself, and over the budget once more
        val written = window(8407, now, pack = 15, data = 5)
        budget.wrote(written)
        assertEquals(110 * block, budget.bytes)
        assertTrue(budget.over())
        // the core wants it and has the others in view: nothing it may evict is left but the one not drawn lately
        assertEquals(listOf(lately), budget.trim(keep = setOf(written, drawnLately, inView)))
        assertEquals(80 * block, budget.bytes)
        // a window whose files are gone (another lane's eviction, a rebuild that failed) counts nothing
        File(dir, "14-8407-5384.atlas").delete()
        File(dir, "14-8407-5384.json.gz").delete()
        budget.wrote(written)
        assertEquals(60 * block, budget.bytes)
    }

    @Test
    fun aWindowAStepWorksOnIsNeverEvicted() {
        val busy = window(8401, now - 40 * day, data = 20)
        val idle = window(8402, now - 10 * day, data = 20)
        val budget = WindowsBudget(dir, limit = { 50 * block }, clock = { now })
        assertEquals(listOf(idle), budget.trim(emptySet(), claim = { it != busy }))
        assertTrue(held(busy))
    }

    @Test
    fun theBudgetIsAtMostTwoGiBATenthOfTheVolumeAndHalfOfWhatTheWindowsAndTheFreeSpaceMakeUp() {
        val gb = 1_000_000_000L
        // a phone of 128 GB with room: 2 GiB; one of 16 GB: 1.6 GB
        assertEquals(2L shl 30, WindowsBudget.limitOf(128 * gb, 60 * gb, 0L))
        assertEquals(1_600_000_000L, WindowsBudget.limitOf(16 * gb, 10 * gb, 0L))
        // a phone running short: the windows give space back, so they hold no more than is left free beside them
        assertEquals(1_250_000_000L, WindowsBudget.limitOf(128 * gb, gb, 1_500_000_000L))
        assertEquals(750_000_000L, WindowsBudget.limitOf(128 * gb, 0L, 1_500_000_000L))
        // a volume that says nothing of its size
        assertEquals(WindowsBudget.MAX_BYTES, WindowsBudget.limitOf(0L, 0L, 0L))
        // this Mac's, for a folder not made yet: its volume's
        val limit = WindowsBudget.limitOf(File(dir, "a/b"), Long.MAX_VALUE / 4)
        assertEquals(minOf(WindowsBudget.MAX_BYTES, dir.totalSpace / 10), limit)
    }

    /** A core of the test's own: it wants [wanted], has [inView] in view, and builds a pack of ten blocks. */
    private class Core : Windows.Core {
        var wanted = listOf<MapCore.Window>()
        var inView = listOf<MapCore.Window>()
        override fun wanted() = wanted
        override fun inView() = inView
        override fun box(w: MapCore.Window) = doubleArrayOf(52.36, 4.87, 52.38, 4.89)
        override fun build(w: MapCore.Window, json: File, file: File): String {
            file.writeBytes(ByteArray(10 * 4096))
            return ""
        }

        override fun merge(w: MapCore.Window, kept: File, diff: ByteBuffer, out: File) = false
    }

    @Test
    fun theWindowsHoldTheirFolderToItsBudgetAtTheFirstStepAndAfterEachWrite() {
        val older = window(8401, now - 45 * day, data = 20)
        val old = window(8402, now - 40 * day, data = 20)
        val inView = window(8403, now - day, data = 20)
        val answer = "{\"version\":0.6,\"osm3s\":{\"timestamp_osm_base\":\"2026-10-30T11:59:00Z\"},\"elements\":[]}"
        val windows = Windows(
            dir, fetch = { _, file -> file.writeText(answer) }, clock = { now },
            budget = WindowsBudget(dir, limit = { 70 * block }, clock = { now }),
        )
        val core = Core().apply { this.inView = listOf(inView) }
        // the app's start: 90 blocks, over the budget of 70: the oldest goes, down to 63 or less
        assertTrue(windows.step(core))
        assertFalse(held(older))
        assertTrue(held(old))
        assertTrue(held(inView))
        // under the budget, a step that writes nothing evicts nothing
        assertFalse(windows.step(core))
        assertTrue(held(old))
        // a window built: 11 blocks more, over the budget again; the one neither wanted nor in view goes
        val wanted = MapCore.Window(14, 8404, 5384)
        core.wanted = listOf(wanted)
        assertTrue(windows.step(core))
        assertTrue(windows.fileOf(wanted).isFile)
        assertTrue(windows.dataOf(wanted).isFile)
        assertFalse(held(old))
        assertTrue(held(inView))
    }
}
