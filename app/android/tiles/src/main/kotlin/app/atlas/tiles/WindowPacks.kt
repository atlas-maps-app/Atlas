package app.atlas.tiles

import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * A window's data from Atlas's own packs (ENGINE.md step 3: the phone's first source for a window's data, before
 * Overpass): the published packs ([packs], packs.json's) whose box holds the window and that have windows' data, newest
 * first; from the first whose index lists the window, its bytes, read by HTTP range from the pack's windows file and
 * held to the index's SHA-256.
 *
 * Each pack's index ([WindowsIndex]) is downloaded whole once, held to packs.json's SHA-256, and kept in [dir] (one per
 * pack, the one of an older release removed), so a window costs one range of its own bytes. An index of another level
 * or window query than the phone's is not used. One instance per app; its calls block, on the windows' own thread.
 */
class WindowPacks(
    /** The published packs: the catalog's list (kept for offline), or throws where there is none. */
    packs: () -> List<Pack>,
    /** Where the packs' indexes are kept. */
    dir: File,
    client: OkHttpClient = Http.client,
    clock: () -> Long = System::currentTimeMillis,
) {
    /** A window's data: Overpass's answer for its box as of [date], gzipped, from pack [pack]. */
    class Data(val gz: ByteArray, val date: Instant, val pack: String)

    private val reads = PackReads(packs, dir, client, clock)

    /** The indexes read, by pack id: the index's SHA-256 and the index. */
    private val indexes = HashMap<String, Pair<String, WindowsIndex>>()

    /**
     * Window ([x], [y]) of level [z]'s data from the newest pack that holds it, of data newer than [newerThan] where one
     * is given; null where no pack holds it (none newer, or no list of packs to be had). Throws IOException where a pack
     * holds it but its bytes did not come whole, or were not the ones published.
     */
    fun window(z: Int, x: Int, y: Int, newerThan: Instant?): Data? {
        if (z != LEVEL) return null
        val list = reads.list() ?: return null
        val holding = list.filter { it.windows != null && PackReads.holds(it, x, y) }
        for (pack in holding.sortedByDescending { it.osmTime ?: Instant.EPOCH }) {
            val index = indexOf(pack) ?: continue
            if (newerThan != null && !index.date.isAfter(newerThan)) continue
            val e = index.find(x, y) ?: continue
            val bytes = reads.range(pack.windows!!, e.offset, e.length)
            if (!PackReads.sha256(bytes).contentEquals(e.sha256)) {
                throw IOException("window $z/$x/$y of pack ${pack.id}: not the bytes its index names")
            }
            return Data(bytes, index.date, pack.id)
        }
        return null
    }

    /** [pack]'s index, from memory, from the folder, else downloaded; null where it is not one for the phone's windows. */
    @Synchronized
    private fun indexOf(pack: Pack): WindowsIndex? {
        val file = pack.windowsIndex ?: return null
        val sha = file.sha256?.lowercase() ?: throw IOException("pack ${pack.id}: its windows' index names no SHA-256")
        val index = indexes[pack.id]?.takeIf { it.first == sha }?.second ?: run {
            val read = try {
                WindowsIndex.read(reads.index(pack, file, INDEX_SUFFIX))
            } catch (e: IllegalArgumentException) {
                throw IOException("pack ${pack.id}: its windows' index does not read: ${e.message}", e)
            }
            indexes[pack.id] = sha to read
            read
        }
        return index.takeIf { it.level == LEVEL && it.query == WindowsIndex.QUERY }
    }

    companion object {
        /** The level of the phone's windows (load::window_level). */
        const val LEVEL = PackReads.LEVEL

        /** How long no list of packs to be had keeps a window from asking for it again. */
        const val LIST_RETRY_MS = PackReads.LIST_RETRY_MS

        /** How a pack's windows' index is kept in the folder, after the pack's id and its SHA-256's first digits. */
        private const val INDEX_SUFFIX = ".idx"
    }
}
