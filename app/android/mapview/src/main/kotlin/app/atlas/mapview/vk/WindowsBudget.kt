package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import java.io.File

/**
 * How much the windows' folder ([Windows]) may hold, and which windows go where it holds more. Since 30 October the
 * windows live in the app's files (MapEngine.keptDir), which Android never clears to free space, and every window taken
 * or built is kept, with its data beside it where it was built here (a dense window's data is megabytes, kept again by
 * each refresh after a week): without a budget the folder grew as long as the user looked at new places, where in the
 * cache Android at least took it back when the phone ran short (the user, 30 October: "are all sources reliable and
 * respond fast?").
 *
 * The budget ([limitOf]) is the smallest of [MAX_BYTES], a tenth of the volume and half of what the windows and the
 * space left free make up: 2 GiB hold more than the Netherlands' whole map as the phone's core builds it (1.6 GB on the
 * phone), so a user who looks over a whole country keeps it, while a day's travel takes tens of MB; a tenth of the
 * volume keeps a small phone's share small (1.6 GB of 16 GB); and as the phone fills, the windows give space back, as
 * Android took back the cache, so they never hold more than the space left free beside them.
 *
 * Where the folder holds more ([over]), [trim] evicts windows, each with all its files (its pack, its data, its mark),
 * until it holds at most [LOW] of the budget, so the next trim is a while off: never one the core wants or has in view,
 * nor one a step works on; first the windows taken from a country's tiles the phone keeps whole and reads ([keptPacks]:
 * they come back from the phone at once, byte for byte), then the rest, least recently drawn or taken first. Drawn: seen
 * in view in this process ([seen]); taken: its files' last time, when it came, was built or had its data checked,
 * which a window in view renews once a week. The folder is listed whole once, at the first trim (the app's first step),
 * and each window's files are looked at again after its work ([wrote]), so a step costs no listing. Files are counted by
 * the blocks they take on the disk. The packs' list and indexes kept beside the windows (`index/`, one per country) are
 * not counted, nor ever evicted. Thread-safe: both of the windows' lanes write.
 */
class WindowsBudget(
    private val dir: File,
    /**
     * The packs whose country's tiles the phone keeps whole and the windows read (their ids, as a window's mark names
     * them: [Windows.tiledOf]); the app's. A window taken from them comes back from the phone at once.
     */
    private val keptPacks: () -> Set<String> = { emptySet() },
    /** The most the folder may hold, given what it holds now ([limitOf]'s by default). */
    private val limit: (held: Long) -> Long = { held -> limitOf(dir, held) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What each window's files take, as last looked at; filled by [trim]'s listing. */
    private val sizes = HashMap<MapCore.Window, Long>()

    /** When each window was last seen in view in this process ([seen]). */
    private val drawn = HashMap<MapCore.Window, Long>()

    /** What the windows take, as last looked at; -1 until [trim] listed the folder. */
    private var held = -1L

    /** What the windows take, as last looked at (bytes of the disk's blocks); -1 until the folder was listed. */
    val bytes: Long
        @Synchronized get() = held

    /** The core has [windows] in view now: drawn, so they go last. */
    @Synchronized
    fun seen(windows: Collection<MapCore.Window>) {
        if (windows.isEmpty()) return
        val now = clock()
        for (w in windows) drawn[w] = now
    }

    /** Window [w]'s work is over (its files written, replaced or removed): what they take is looked at again. */
    @Synchronized
    fun wrote(w: MapCore.Window) {
        if (held < 0) return
        val b = filesOf(w).sumOf { blocks(it.length()) }
        val before = if (b > 0) sizes.put(w, b) else sizes.remove(w)
        held += b - (before ?: 0L)
    }

    /** Whether [trim] is due: the folder was never listed, or holds more than its budget. */
    @Synchronized
    fun over(): Boolean = held < 0 || held > limit(held)

    /**
     * Lists the folder, and where it holds more than its budget evicts windows until it holds at most [LOW] of it: never
     * one of [keep] (the core wants it or has it in view), nor one [claim] refuses (a step works on it; [release] gives
     * it back after); first those taken from a country's tiles the phone keeps whole and reads ([keptPacks]), then the
     * least recently drawn or taken. Returns the windows evicted.
     */
    @Synchronized
    fun trim(
        keep: Set<MapCore.Window>,
        claim: (MapCore.Window) -> Boolean = { true },
        release: (MapCore.Window) -> Unit = {},
    ): List<MapCore.Window> {
        val files = HashMap<MapCore.Window, MutableList<File>>()
        for (f in dir.listFiles().orEmpty()) {
            val w = ownerOf(f.name) ?: continue
            if (f.isFile) files.getOrPut(w) { ArrayList() } += f
        }
        sizes.clear()
        held = 0L
        for ((w, fs) in files) {
            val b = fs.sumOf { blocks(it.length()) }
            sizes[w] = b
            held += b
        }
        val budget = limit(held)
        if (held <= budget) return emptyList()
        val target = (budget * LOW).toLong()
        val kept = runCatching { keptPacks() }.getOrDefault(emptySet())

        class Candidate(val w: MapCore.Window, val fromKept: Boolean, val at: Long)

        val order = files.filterKeys { it !in keep }.map { (w, fs) ->
            val mark = fs.firstOrNull { it.name.endsWith(MARK) }
            val from = if (kept.isEmpty() || mark == null) null else runCatching { mark.readText().trim().substringAfter(' ', "") }.getOrNull()
            Candidate(w, from != null && from in kept, maxOf(fs.maxOf { it.lastModified() }, drawn[w] ?: 0L))
        }.sortedWith(compareBy<Candidate> { !it.fromKept }.thenBy { it.at })
        val gone = ArrayList<MapCore.Window>()
        for (c in order) {
            if (held <= target) break
            if (!claim(c.w)) continue
            try {
                // its files as they are now: a step may have written one since the listing
                for (f in (files[c.w].orEmpty() + filesOf(c.w)).distinct()) f.delete()
                held -= sizes.remove(c.w) ?: 0L
                drawn.remove(c.w)
                gone += c.w
            } finally {
                release(c.w)
            }
        }
        return gone
    }

    /** Window [w]'s files that stay after a step: its pack, its data, its mark, by their names ([Windows]). */
    private fun filesOf(w: MapCore.Window): List<File> {
        val base = "${w.z}-${w.x}-${w.y}"
        return KEPT.map { File(dir, base + it) }.filter { it.isFile }
    }

    companion object {
        /** The most the windows may hold on any phone: 2 GiB. */
        const val MAX_BYTES = 2L shl 30

        /** What a trim leaves of the budget at most: nine tenths, so the next trim is a while off. */
        const val LOW = 0.9

        /** A block of the phone's disk: what a file of a few bytes takes there. */
        private const val BLOCK = 4096L

        /** A window's mark that its pack came from a country's tiles: the data's date, then the pack's id. */
        private const val MARK = ".tiles"

        /** The files a window keeps after a step: its pack, its data, its mark. */
        private val KEPT = listOf(".atlas", ".json.gz", MARK)

        /** A window's file of the windows' folder, by its name (`14-<x>-<y>.<what>`), its working files' too. */
        private val OWNED = Regex("""(\d+)-(\d+)-(\d+)\..+""")

        /** The window a file of the folder is one of ([Windows]' names), or null for another file. */
        internal fun ownerOf(name: String): MapCore.Window? {
            val m = OWNED.matchEntire(name) ?: return null
            val (z, x, y) = m.destructured
            return runCatching { MapCore.Window(z.toInt(), x.toInt(), y.toInt()) }.getOrNull()
        }

        private fun blocks(length: Long): Long = (length + BLOCK - 1) / BLOCK * BLOCK

        /** The budget of a folder [dir] whose windows take [held] bytes, on its volume ([limitOf]). */
        fun limitOf(dir: File, held: Long): Long {
            val on = generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: return MAX_BYTES
            return limitOf(on.totalSpace, on.usableSpace, held)
        }

        /**
         * The budget on a volume of [total] bytes with [free] of them left free, where the windows take [held]: the
         * smallest of [MAX_BYTES], a tenth of the volume, and half of [held] and [free]. [MAX_BYTES] where the volume
         * says nothing of its size.
         */
        fun limitOf(total: Long, free: Long, held: Long): Long =
            if (total <= 0) MAX_BYTES else minOf(MAX_BYTES, total / 10, (held + free.coerceAtLeast(0L)) / 2)
    }
}
