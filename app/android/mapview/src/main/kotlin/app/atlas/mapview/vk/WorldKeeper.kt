package app.atlas.mapview.vk

import app.atlas.mapcore.MapCore
import app.atlas.tiles.WorldFetch
import app.atlas.tiles.WorldIndex
import app.atlas.tiles.WorldKeepList
import java.io.File
import java.io.IOException

/**
 * The world a kept country keeps (ENGINE.md step 11): the tiles of the world a view of the country draws from 18 km
 * out to the globe, as the world's release lists them for it ([WorldFetch.keep], world-keep.json), fetched into the
 * core's world cache in [cache] and pinned there, out of its LRU and its [budget], while the country is kept; so a
 * phone that went offline still draws the country out to the globe (closer than 18 km its own tiles carry the map).
 *
 * [keep] takes the countries kept now, all of them: what they keep is pinned in place of what was pinned before, so a
 * country no longer kept gives its tiles back to the cache's LRU, and with [fetch] what the cache lacks of them is
 * fetched, a pack's head first (its chunks are known only by it), then its tiles' chunks by ranges, neighbours merged
 * as [WorldReads] merges a view's. What is on the phone stays there: a fetch that stops (no signal) goes on from where
 * it was the next time. A new version of the world drops the old one's cache, its keep with it (WorldFetch's drop); the
 * keep of the new one is fetched as it is asked for again. Blocking: call it off the main thread, one call at a time.
 */
class WorldKeeper(
    private val fetch: WorldFetch,
    private val cache: File,
    private val budget: () -> Long,
    private val said: (String) -> Unit = {},
) {
    /** Where a keep stands after [keep]. */
    class State(
        /** The countries asked for that the world's list has ([WorldKeepList.country]); the others keep nothing. */
        val listed: Set<String>,
        /** Heads and chunks still lacking (0: everything the countries keep is on the phone). */
        val lacking: Int,
        /** The bytes fetched by this call. */
        val fetched: Long,
    ) {
        val whole: Boolean get() = lacking == 0
    }

    /**
     * What keeping [country]'s world fetches, in bytes, as the world on the phone lists it; null where it lists none
     * (a world without a list, or a country it does not name).
     */
    fun bytes(country: String): Long? = fetch.keep?.country(country)?.bytes

    /**
     * Pins the world of [countries] (their regions' ids) and of no other, and with [fetch] fetches what the cache lacks
     * of it, saying how far it is through [report] (0 to 1 of the bytes this call set out to fetch). Without a world
     * or a list on the phone nothing is pinned or fetched (the state says none is listed). Throws [IOException] where
     * a fetch fails or the cache refuses what came; what was kept before then stays.
     */
    @Synchronized
    fun keep(countries: Set<String>, fetch: Boolean = true, report: (Float) -> Unit = {}): State {
        val world = this.fetch.world
        val list = this.fetch.keep
        if (world == null || list == null) return State(emptySet(), 0, 0L)
        val listed = countries.filter { list.country(it) != null }.toSet()
        val packs = packsOf(list, world.index, listed)
        val dir = cache.path
        var state = MapCore.worldKept(dir, budget(), packs.map { it.kept }) ?: throw IOException("the world's cache does not take the keep")
        if (state.unknown > 0) said("the keep: ${state.unknown} tiles of the list are in no pack of the world on the phone")
        if (!fetch || (state.heads.isEmpty() && state.chunks.isEmpty())) return State(listed, state.heads.size + state.chunks.size, 0L)
        // what this call sets out to fetch: the lacking heads as the list gives them, and the lacking chunks
        var total = state.heads.sumOf { packs[it].head } + state.chunks.sumOf { it.length }
        var done = 0L
        val progress = { report(if (total <= 0L) 1f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
        progress()
        if (state.heads.isNotEmpty()) {
            for (i in state.heads) {
                val p = packs[i]
                val ranges = this.fetch.head(p.entry)
                val why = MapCore.worldHead(dir, budget(), MapCore.PackRef.WORLD_MID, p.entry.length, p.entry.sha256, ranges)
                if (why.isNotEmpty()) throw IOException("the head of ${WorldFetch.idOf(p.entry)} not kept: $why")
                done += p.head
                progress()
            }
            // the heads name their chunks: pinned now, and the lacking ones known
            state = MapCore.worldKept(dir, budget(), packs.map { it.kept }) ?: throw IOException("the world's cache does not take the keep")
            if (state.heads.isNotEmpty()) throw IOException("${state.heads.size} heads of the keep do not open their packs")
            total = done + state.chunks.sumOf { it.length }
        }
        var refused = 0
        for (run in runsOf(state.chunks)) {
            val p = packs[run.pack]
            val got = this.fetch.range(p.entry, run.start, (run.end - run.start).toInt())
            val kept = MapCore.worldKeptRange(dir, budget(), p.kept, run.start, got)
                ?: throw IOException("chunks of ${WorldFetch.idOf(p.entry)} not kept: its head is gone from the cache")
            refused += kept.refused.size
            done += run.bytes
            progress()
        }
        val after = MapCore.worldKept(dir, budget(), packs.map { it.kept }) ?: throw IOException("the world's cache does not take the keep")
        val lacking = after.heads.size + after.chunks.size
        said("the keep of ${listed.sorted().joinToString(", ")}: ${after.held} chunks (${after.heldBytes / 1024} kB) on the phone, ${done / 1024} kB fetched" +
            (if (lacking > 0) ", $lacking lacking" else "") + (if (refused > 0) ", $refused refused" else ""))
        if (lacking > 0) throw IOException("$lacking chunks of the keep were not kept")
        return State(listed, 0, done)
    }

    /** A pack of the keep: its entry in the phone's index, its head's bytes as the list gives them, and what the core is asked. */
    internal class Pack(val entry: WorldIndex.Entry, val head: Long, val kept: MapCore.KeptPack)

    /** A range of one pack to fetch: its pack's place, the bytes [start] to [end], and its chunks' bytes in it. */
    internal class Run(val pack: Int, val start: Long, var end: Long, var bytes: Long)

    companion object {
        /**
         * The packs [countries] keep in [list], each once with the tiles of every country that keeps it, as [index]
         * (the phone's world's) has them; a pack the index lacks is left out (the list is checked against the index
         * before, so none is).
         */
        internal fun packsOf(list: WorldKeepList, index: WorldIndex, countries: Set<String>): List<Pack> {
            val tiles = LinkedHashMap<Triple<Int, Int, Int>, LinkedHashSet<WorldKeepList.Tile>>()
            val heads = HashMap<Triple<Int, Int, Int>, Long>()
            for (id in countries.sorted()) {
                for (p in list.country(id)?.packs.orEmpty()) {
                    val key = Triple(p.z, p.x, p.y)
                    tiles.getOrPut(key) { LinkedHashSet() }.addAll(p.tiles)
                    heads[key] = p.head
                }
            }
            return tiles.mapNotNull { (key, ts) ->
                val e = index.find(key.first, key.second, key.third) ?: return@mapNotNull null
                Pack(e, heads[key] ?: 0L, MapCore.KeptPack(e.length, e.sha256, ts.map { MapCore.Window(it.z, it.x, it.y) }))
            }
        }

        /**
         * The ranges to fetch for the lacking [chunks]: each pack's in the order they lie in it, those within
         * [WorldReads.MERGE_GAP] of each other in one range with what lies between, up to [WorldReads.MAX_RANGE] bytes.
         */
        internal fun runsOf(chunks: List<MapCore.KeptChunk>): List<Run> {
            val runs = ArrayList<Run>()
            for ((pack, group) in chunks.groupBy { it.pack }) {
                var run: Run? = null
                for (c in group.sortedBy { it.offset }) {
                    val end = c.offset + c.length
                    val r = run
                    if (r != null && c.offset <= r.end + WorldReads.MERGE_GAP && end - r.start <= WorldReads.MAX_RANGE) {
                        r.end = maxOf(r.end, end)
                        r.bytes += c.length
                        continue
                    }
                    run = Run(pack, c.offset, end, c.length)
                    runs += run
                }
            }
            return runs
        }
    }
}
