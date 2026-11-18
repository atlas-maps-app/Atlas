package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.tiles.TerrainFetch
import app.atlas.tiles.WorldIndex
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The terrain's packs by ranges as a map's views want them (ENGINE.md part 6, step 2), into the world's cache in [cache]
 * (the world's own LRU, [budget] bytes), as [WorldReads] reads the world's. Each [step] (the map asks twice a second,
 * beside the world's) takes the index of each region whose box the drawn tiles' L9 blocks meet ([MapCore.terrainBlocks],
 * nearest first; TerrainFetch.take), then the head of each of those blocks' packs that the cache does not hold, then the
 * chunks of the tiles the map wants that the cache lacks ([MapCore.terrainWants]), neighbours in a pack merged into one
 * range ([WorldReads.runsOf]): [WorldReads.REQUESTS_PER_STEP] requests a step at most and none begun after
 * [WorldReads.STEP_MS]. An index or a head kept makes the maps take their packs again (PackList lists a terrain pack once
 * its head is in the cache, so a place seen once has its relief offline); a chunk kept gives the core its tile's heights
 * with its next frame. Where none have come, the ground draws flat. [said] gets the `vk terrain` lines. Blocking: call
 * it off the main thread; a step asked for while one runs does nothing.
 */
class TerrainReads(
    private val fetch: TerrainFetch,
    private val cache: File,
    private val budget: () -> Long,
    private val online: () -> Boolean = { true },
    private val said: (String) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Whether the world's cache holds each pack's head, by their SHA-256s (MapCore.worldHeld). */
    private val held: (List<ByteArray>) -> BooleanArray = { ids -> MapCore.worldHeld(cache.path, budget(), ids) },
) {
    private val busy = AtomicBoolean(false)

    // the core told of the cache, and the budget it was told
    private var told: MapCore? = null
    private var toldBudget = 0L

    // each head asked for, by its pack's id and release, and when: not again within HEAD_AGAIN_MS
    private val heads = HashMap<String, Long>()

    // the chunks whose bytes the core refused, by pack identity and tile: not asked for again
    private val refused = HashSet<String>()

    /** One step for [core]'s map; returns whether an index or a head came (the maps then take their packs again). */
    fun step(core: MapCore): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            return run(core)
        } finally {
            busy.set(false)
        }
    }

    /**
     * The short names of the sources of the relief [core]'s map drew last ([MapCore.terrainSources]), as its credit line
     * names them (TerrainFetch.creditsOf: "Copernicus" first). Off the main thread.
     */
    fun credits(core: MapCore): List<String> = fetch.creditsOf(core.terrainSources())

    private fun run(core: MapCore): Boolean {
        val terrain = fetch.terrain ?: return false
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
        val blocks = core.terrainBlocks().filter { it.z == TerrainFetch.BLOCK }.map { it.x to it.y }
        if (blocks.isEmpty()) return false
        // the regions' indexes the view reaches, then the heads of the blocks' packs the cache does not hold
        var requests = 0
        var came = fetch.take(blocks, said).size
        requests += came
        val packs = fetch.packsAt(blocks)
        val release = terrain.list.release
        val wanted = packs.filter { p ->
            val asked = heads["${p.id}@$release"]
            asked == null || clock() - asked >= HEAD_AGAIN_MS
        }
        val holds = if (wanted.isEmpty()) BooleanArray(0) else held(wanted.map { it.entry.sha256 })
        for ((i, p) in wanted.withIndex()) {
            if (requests >= WorldReads.REQUESTS_PER_STEP || clock() - started > WorldReads.STEP_MS) break
            if (i < holds.size && holds[i]) continue
            heads["${p.id}@$release"] = clock()
            val ranges = runCatching { fetch.head(p) }.getOrElse {
                said("the head of ${p.id} not fetched: ${it.message}")
                return came > 0
            }
            requests += ranges.size
            val why = MapCore.worldHead(cache.path, b, MapCore.PackRef.TERRAIN_COUNTRY, p.entry.length, p.entry.sha256, ranges)
            if (why.isEmpty()) {
                came++
                said("the head of ${p.id} (${ranges.sumOf { it.second.size } / 1024} kB) on the phone")
            } else {
                said("the head of ${p.id} not kept: $why")
            }
        }
        // the chunks the map wants, by the packs of their blocks of their identity
        val wants = core.terrainWants()
        val runs = runsOf(wants, fetch.packsAt(wants.filter { it.block.z == TerrainFetch.BLOCK }.map { it.block.x to it.block.y }.distinct()), refused)
        var kept = 0
        var bytes = 0L
        var fetched = 0
        for ((r, p) in runs) {
            if (requests >= WorldReads.REQUESTS_PER_STEP || clock() - started > WorldReads.STEP_MS) break
            val got = runCatching { fetch.range(p, r.start, (r.end - r.start).toInt()) }.getOrElse {
                said("chunks of ${p.id} not fetched: ${it.message}")
                break
            }
            requests++
            fetched++
            bytes += got.size
            val k = core.terrainKeep(r.entry.sha256, r.start, got) ?: continue
            kept += k.kept
            for (t in k.refused) refused += WorldReads.refusedKey(r.entry.sha256, t)
            if (k.refused.isNotEmpty()) said("${k.refused.size} chunks of ${p.id} refused: not the ones its head names")
        }
        if (fetched > 0) said("$kept chunks (${bytes / 1024} kB) in $fetched ranges on the phone")
        return came > 0
    }

    companion object {
        /** A head asked for is not asked for again within this (its pack is listed once the head is kept), as the world's. */
        const val HEAD_AGAIN_MS = WorldReads.HEAD_AGAIN_MS

        /**
         * The ranges to fetch for [wants] (the core's order, nearest first), as [WorldReads.runsOf] merges them, each with
         * its pack: a want's pack is the one of [packs] (the indexes' packs of the wants' blocks) of its block and its
         * identity, so a block two regions hold has the ranges of each from its own index; a want of no such pack
         * (another release's, or a region whose index has not come) is left out.
         */
        internal fun runsOf(wants: List<MapCore.WorldWant>, packs: List<TerrainFetch.Pack>, refused: Set<String> = emptySet()): List<Pair<WorldReads.Run, TerrainFetch.Pack>> {
            val byEntry = HashMap<WorldIndex.Entry, TerrainFetch.Pack>()
            for (p in packs) byEntry[p.entry] = p
            val runs = WorldReads.runsOf(wants, { w ->
                packs.firstOrNull { p ->
                    val e = p.entry
                    e.z == w.block.z && e.x == w.block.x && e.y == w.block.y && e.sha256.contentEquals(w.identity)
                }?.entry
            }, refused)
            return runs.mapNotNull { r -> byEntry[r.entry]?.let { r to it } }
        }
    }
}
