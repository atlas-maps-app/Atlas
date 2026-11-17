package app.atlas.mapview.vk

import app.atlas.mapcore.KeptWorld
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
 * country no longer kept gives its tiles back to the cache's LRU, and with `fetch` what the cache lacks of them is
 * fetched, a pack's head first (its chunks are known only by it), then its tiles' chunks by ranges, neighbours merged
 * as [WorldReads] merges a view's. What is on the phone stays there: a fetch that stops (no signal) goes on from where
 * it was the next time.
 *
 * A new version of the world does not take the kept world away. While the phone's world keeps something for a kept
 * country, the new version waits beside it ([WorldFetch.next]); [keep] pins the old one's keep and the new one's
 * together, fetches the new one's alone (a chunk the two versions share is on the phone already: the cache knows a
 * chunk by its bytes), and once the new one's is whole takes the new world ([WorldFetch.adopt], then [adopted]), pins
 * its keep alone and lets the old one's heads and chunks go. Until then the phone draws the old world, offline as online. A new
 * version whose release lists no keep yet waits until it does (the list is asked for once a day); where nothing of
 * the old world is kept any more (the country was deleted), the new one is taken at once.
 *
 * Without Android's types, over [KeptWorld]: `:mapcorecheck` runs it on the JVM against the core's host build and a
 * local server. Blocking: call it off the main thread, one call at a time.
 */
class WorldKeeper(
    private val fetch: WorldFetch,
    private val cache: File,
    private val budget: () -> Long,
    private val said: (String) -> Unit = {},
    private val adopted: () -> Unit = {},
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
     * or a list on the phone nothing is pinned or fetched (the state says none is listed). Where a new version of the
     * world waits, its keep is the one fetched, beside the old one's, and the state is the new one's. Throws
     * [IOException] where a fetch fails or the cache refuses what came; what was kept before then stays.
     */
    @Synchronized
    fun keep(countries: Set<String>, fetch: Boolean = true, report: (Float) -> Unit = {}): State {
        val world = this.fetch.world ?: return State(emptySet(), 0, 0L)
        val list = this.fetch.keep
        val listed = if (list == null) emptySet() else countries.filter { list.country(it) != null }.toSet()
        val mine = if (list == null) emptyList() else packsOf(list, world.index, listed)
        val staged = this.fetch.next
        if (staged != null) {
            val nextList = this.fetch.nextKeep
            if (mine.isEmpty()) {
                // nothing of the phone's world is kept: the new one at once, the old one's heads and chunks dropped,
                // and what the new one keeps as any world's
                take(staged)
                drop(staged, pinned = false)
                return keep(countries, fetch, report)
            }
            if (nextList != null) {
                val nextListed = countries.filter { nextList.country(it) != null }.toSet()
                val theirs = packsOf(nextList, staged.index, nextListed)
                // both pinned, the new one's fetched; the old one's stays as it is on the phone
                val state = hold(mine.map { it.kept } + theirs.map { it.kept }, theirs, mine.size, staged, fetch, report, nextListed)
                if (!state.whole) return state
                take(staged)
                // the new world's alone pinned from now on, and what the old one held besides let go
                val after = keep(countries, false)
                drop(staged, pinned = true)
                return State(after.listed, after.lacking, state.fetched)
            }
            // the new world lists no keep yet: the old one stays, kept as before
        }
        if (list == null) return State(emptySet(), 0, 0L)
        return hold(mine.map { it.kept }, mine, 0, world, fetch, report, listed)
    }

    /** Takes the world that waits as the phone's ([WorldFetch.adopt]), and says so ([adopted]: the maps list their world again). */
    private fun take(staged: WorldFetch.World) {
        val before = fetch.world?.release?.tag
        if (!fetch.adopt()) throw IOException("${staged.release.tag} does not wait any more")
        said("${staged.release.tag} taken in place of $before")
        adopted()
    }

    /** Lets go of the heads and chunks the world before [now] left in the cache: all of them, or with [pinned] all but the pinned keep. */
    private fun drop(now: WorldFetch.World, pinned: Boolean) {
        val why = KeptWorld.drop(cache.path, budget(), pinned)
        said(if (why.isEmpty()) "the heads and chunks of the world before ${now.release.tag} dropped" + (if (pinned) ", but the kept countries' world" else "") else "the old version's heads and chunks not dropped: $why")
    }

    /**
     * Pins [all] in the cache, and with [fetch] fetches what it lacks of [packs], which are [all] from place [first]
     * on and packs of [world]. The state counts what [packs] lack.
     */
    private fun hold(all: List<KeptWorld.Pack>, packs: List<Pack>, first: Int, world: WorldFetch.World, fetch: Boolean, report: (Float) -> Unit, listed: Set<String>): State {
        val dir = cache.path
        fun pin() = KeptWorld.pin(dir, budget(), all) ?: throw IOException("the world's cache does not take the keep")
        var state = pin()
        if (state.unknown > 0) said("the keep: ${state.unknown} tiles of the list are in no pack of the world on the phone")
        var heads = state.heads.filter { it >= first }
        var chunks = state.chunks.filter { it.pack >= first }
        if (!fetch || (heads.isEmpty() && chunks.isEmpty())) return State(listed, heads.size + chunks.size, 0L)
        // what this call sets out to fetch: the lacking heads as the list gives them, and the lacking chunks
        var total = heads.sumOf { packs[it - first].head } + chunks.sumOf { it.length }
        var done = 0L
        val progress = { report(if (total <= 0L) 1f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)) }
        progress()
        if (heads.isNotEmpty()) {
            for (i in heads) {
                val p = packs[i - first]
                val ranges = this.fetch.head(p.entry, world)
                val why = KeptWorld.head(dir, budget(), p.kept, ranges)
                if (why.isNotEmpty()) throw IOException("the head of ${WorldFetch.idOf(p.entry)} not kept: $why")
                done += p.head
                progress()
            }
            // the heads name their chunks: pinned now, and the lacking ones known
            state = pin()
            heads = state.heads.filter { it >= first }
            chunks = state.chunks.filter { it.pack >= first }
            if (heads.isNotEmpty()) throw IOException("${heads.size} heads of the keep do not open their packs")
            total = done + chunks.sumOf { it.length }
        }
        var refused = 0
        for (run in runsOf(chunks)) {
            val p = packs[run.pack - first]
            val got = this.fetch.range(p.entry, run.start, (run.end - run.start).toInt(), world)
            val kept = KeptWorld.range(dir, budget(), p.kept, run.start, got)
                ?: throw IOException("chunks of ${WorldFetch.idOf(p.entry)} not kept: its head is gone from the cache")
            refused += kept.refused
            done += run.bytes
            progress()
        }
        val after = pin()
        val lacking = after.heads.count { it >= first } + after.chunks.count { it.pack >= first }
        said("the keep of ${listed.sorted().joinToString(", ")} in ${world.release.tag}: ${after.held} chunks (${after.heldBytes / 1024} kB) on the phone, ${done / 1024} kB fetched" +
            (if (lacking > 0) ", $lacking lacking" else "") + (if (refused > 0) ", $refused refused" else ""))
        if (lacking > 0) throw IOException("$lacking chunks of the keep were not kept")
        return State(listed, 0, done)
    }

    /** A pack of the keep: its entry in its world's index, its head's bytes as the list gives them, and what the core is asked. */
    internal class Pack(val entry: WorldIndex.Entry, val head: Long, val kept: KeptWorld.Pack)

    /** A range of one pack to fetch: its pack's place, the bytes [start] to [end], and its chunks' bytes in it. */
    internal class Run(val pack: Int, val start: Long, var end: Long, var bytes: Long)

    companion object {
        /**
         * The packs [countries] keep in [list], each once with the tiles of every country that keeps it, as [index]
         * (the list's world's) has them; a pack the index lacks is left out (the list is checked against the index
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
                Pack(e, heads[key] ?: 0L, KeptWorld.Pack(e.length, e.sha256, ts.map { KeptWorld.key(it.z, it.x, it.y) }.toLongArray()))
            }
        }

        /**
         * The ranges to fetch for the lacking [chunks]: each pack's in the order they lie in it, those within
         * [WorldFetch.MERGE_GAP] of each other in one range with what lies between, up to [WorldFetch.MAX_RANGE] bytes.
         */
        internal fun runsOf(chunks: List<KeptWorld.Chunk>): List<Run> {
            val runs = ArrayList<Run>()
            for ((pack, group) in chunks.groupBy { it.pack }) {
                var run: Run? = null
                for (c in group.sortedBy { it.offset }) {
                    val end = c.offset + c.length
                    val r = run
                    if (r != null && c.offset <= r.end + WorldFetch.MERGE_GAP && end - r.start <= WorldFetch.MAX_RANGE) {
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
