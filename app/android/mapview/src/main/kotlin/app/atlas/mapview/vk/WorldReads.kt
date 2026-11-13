package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.tiles.WorldFetch
import app.atlas.tiles.WorldIndex
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The world's packs by ranges as a map's views want them (ENGINE.md step 6, "Fetch and keep"), into the core's world
 * cache in [cache] (the world's own LRU, [budget] bytes). Each [step] (the map asks twice a second, as for its windows)
 * takes, nearest first, the head of each block the view reaches without an open pack where the index has one
 * ([MapCore.worldBlocks]: open sea and the world beyond its blocks have none), then the chunks of the tiles the view
 * wants that the cache lacks ([MapCore.worldWants]), neighbours in a pack's order (its levels, each in Hilbert order)
 * merged into one range: [REQUESTS_PER_STEP] requests a step at most and none begun after [STEP_MS], as the levels'
 * reads are. A head kept makes the maps take their packs again (PackList lists a world pack once its head is in the
 * cache, so a place seen once opens offline); a chunk kept makes the core build its tile with its next frame. [said]
 * gets the `vk world` lines (CI's world phase reads them). Blocking: call it off the main thread; a step asked for while
 * one runs does nothing.
 */
class WorldReads(
    private val fetch: WorldFetch,
    private val cache: File,
    private val budget: () -> Long,
    private val online: () -> Boolean = { true },
    private val said: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val busy = AtomicBoolean(false)

    // the core told of the cache, and the budget it was told
    private var told: MapCore? = null
    private var toldBudget = 0L

    // each head asked for, by its pack's id and release, and when: not again within HEAD_AGAIN_MS
    private val heads = HashMap<String, Long>()

    // the chunks whose bytes the core refused (not their chunk's, or not kept), by pack identity and tile: not asked for again
    private val refused = HashSet<String>()

    /** A range of one pack to fetch: its entry, its pack's block, the bytes [start] to [end], and its first want's place in the core's order. */
    internal class Run(val entry: WorldIndex.Entry, val block: MapCore.Window, val start: Long, var end: Long, var rank: Int, var tiles: Int)

    /** One step for [core]'s map; returns whether a head came (the maps then take their packs again). */
    fun step(core: MapCore): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            return run(core)
        } finally {
            busy.set(false)
        }
    }

    private fun run(core: MapCore): Boolean {
        val world = fetch.world ?: return false
        val b = budget()
        if (told !== core || toldBudget != b) {
            val why = core.worldCache(cache.path, b)
            if (why.isNotEmpty()) {
                said("the world's cache not opened: $why")
                return false
            }
            told = core
            toldBudget = b
        }
        if (!online()) return false
        val started = clock()
        var requests = 0
        var came = 0
        for (block in core.worldBlocks()) {
            if (requests >= REQUESTS_PER_STEP || clock() - started > STEP_MS) break
            val e = world.index.find(block.z, block.x, block.y) ?: continue
            val id = WorldFetch.idOf(e)
            val key = "$id@${world.release.tag}"
            val asked = heads[key]
            if (asked != null && clock() - asked < HEAD_AGAIN_MS) continue
            heads[key] = clock()
            val ranges = runCatching { fetch.head(e) }.getOrElse {
                said("the head of $id not fetched: ${it.message}")
                return came > 0
            }
            requests += ranges.size
            val why = MapCore.worldHead(cache.path, b, MapCore.PackRef.WORLD_MID, e.length, e.sha256, ranges)
            if (why.isEmpty()) {
                came++
                said("the head of $id (${ranges.sumOf { it.second.size } / 1024} kB) on the phone")
            } else {
                said("the head of $id not kept: $why")
            }
        }
        val runs = runsOf(core.worldWants(), world.index, refused)
        var kept = 0
        var bytes = 0L
        var fetched = 0
        for (r in runs) {
            if (requests >= REQUESTS_PER_STEP || clock() - started > STEP_MS) break
            val got = runCatching { fetch.range(r.entry, r.start, (r.end - r.start).toInt()) }.getOrElse {
                said("chunks of ${WorldFetch.idOf(r.entry)} not fetched: ${it.message}")
                break
            }
            requests++
            fetched++
            bytes += got.size
            val k = core.worldKeep(r.block, r.entry.sha256, r.start, got) ?: continue
            kept += k.kept
            for (t in k.refused) refused += refusedKey(r.entry.sha256, t)
            if (k.refused.isNotEmpty()) said("${k.refused.size} chunks of ${WorldFetch.idOf(r.entry)} refused: not the ones its head names")
        }
        if (fetched > 0) said("$kept chunks (${bytes / 1024} kB) in $fetched ranges on the phone")
        return came > 0
    }

    companion object {
        /** A step's requests at most, and no request begun after [STEP_MS] (Windows' LEVELS_PER_STEP and LEVELS_STEP_MS). */
        const val REQUESTS_PER_STEP = 8
        const val STEP_MS = 1_500L

        /** A head asked for is not asked for again within this (its pack is listed once the head is kept). */
        const val HEAD_AGAIN_MS = 60_000L

        /** Chunks closer than this in a pack are fetched in one range with what lies between (kept too), up to [MAX_RANGE] bytes. */
        const val MERGE_GAP = 32L shl 10
        const val MAX_RANGE = 4L shl 20

        internal fun refusedKey(identity: ByteArray, tile: MapCore.Window): String = "${identity.contentHashCode()}/${tile.packed}"

        /**
         * The ranges to fetch for [wants] (the core's order, nearest first), in that order by each range's nearest want:
         * a want of a pack [index] holds at the core's identity (another version's is left: its map takes the new packs
         * soon), not [refused], merged with the wants of its pack within [MERGE_GAP] of each other up to [MAX_RANGE].
         */
        internal fun runsOf(wants: List<MapCore.WorldWant>, index: WorldIndex, refused: Set<String> = emptySet()): List<Run> =
            runsOf(wants, { w -> index.find(w.block.z, w.block.x, w.block.y) }, refused)

        /**
         * [runsOf] for wants whose packs [find] gives (a terrain want's: its block's pack of its identity, TerrainReads):
         * the same entry for each want of one pack.
         */
        internal fun runsOf(wants: List<MapCore.WorldWant>, find: (MapCore.WorldWant) -> WorldIndex.Entry?, refused: Set<String>): List<Run> {
            class Want(val w: MapCore.WorldWant, val e: WorldIndex.Entry, val rank: Int)
            val usable = ArrayList<Want>()
            for ((rank, w) in wants.withIndex()) {
                val e = find(w) ?: continue
                if (!e.sha256.contentEquals(w.identity) || refusedKey(w.identity, w.tile) in refused) continue
                if (w.length <= 0 || w.offset < 0 || w.offset + w.length > e.length) continue
                usable += Want(w, e, rank)
            }
            val runs = ArrayList<Run>()
            // a pack's wants together (its entry: one object a pack in its index)
            for ((_, group) in usable.groupBy { it.e }) {
                var run: Run? = null
                for (u in group.sortedBy { it.w.offset }) {
                    val end = u.w.offset + u.w.length
                    val r = run
                    if (r != null && u.w.offset <= r.end + MERGE_GAP && end - r.start <= MAX_RANGE) {
                        r.end = maxOf(r.end, end)
                        r.tiles++
                        r.rank = minOf(r.rank, u.rank)
                        continue
                    }
                    run = Run(u.e, u.w.block, u.w.offset, end, u.rank, 1)
                    runs += run
                }
            }
            return runs.sortedBy { it.rank }
        }
    }
}
