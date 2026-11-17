package app.atlas.mapcore

/**
 * The world a kept country keeps, in the world's cache (ENGINE.md step 11; the core's tiles/Kept.hpp): the packs of the
 * world's mid-zoom base whose heads, and whose listed tiles' chunks, the cache holds out of its LRU and its budget while
 * the country is kept. The app's keeper (mapview's WorldKeeper) says which ([pin]), fetches what the cache lacks and
 * hands it over ([head], [range]), and lets what a world the phone left held go ([drop]).
 *
 * Pure Kotlin without Android's types, as [Native]: the keeper runs over it on the phone and, in `:mapcorecheck`'s JVM
 * tests, over the core's host build. Every call is off the main thread (the cache's first use in the process lists
 * its entries).
 */
object KeptWorld {
    /** The kind of a pack of the world's mid-zoom base (the core's tiles::PackKind::world_mid; MapCore.PackRef.WORLD_MID). */
    const val WORLD_MID = 3

    /** A SHA-256's bytes (world.idx's pack identities). */
    private const val SHA256 = 32

    /** A world pack of a keep: its [length] and [identity] (its SHA-256) as world.idx has them, and its [tiles] to keep ([key]s). */
    class Pack(val length: Long, val identity: ByteArray, val tiles: LongArray)

    /** A chunk a keep lacks: its pack's place among those asked about, its [tile] ([key]), its [offset] in the pack and its [length]. */
    class Chunk(val pack: Int, val tile: Long, val offset: Long, val length: Long)

    /**
     * Where a keep stands: the packs whose [heads] the cache lacks (their places; their chunks are not known yet), the
     * [chunks] it lacks of the others, the tiles asked for that no pack holds ([unknown]), and the chunks it holds
     * ([held], [heldBytes] stored bytes).
     */
    class State(val heads: List<Int>, val chunks: List<Chunk>, val unknown: Int, val held: Int, val heldBytes: Long)

    /** What [range] made of a range: the chunks it [kept], and how many it [refused] (their bytes were not theirs). */
    class Ranged(val kept: Int, val refused: Int)

    /** A tile's key as the core packs it (`z shl 48 or x shl 24 or y`). */
    fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    /**
     * Pins in the world's cache in [dir] everything [packs] keep, in place of what was pinned there, so the cache's LRU
     * never takes it and its [budget] does not count it: every pack's head, and each tile's chunk of the packs whose
     * heads the cache holds. Says what is lacking: the app fetches it, hands it over ([head], [range]) and asks again.
     * No packs: nothing is pinned. Null where the core does not load, the cache does not open or the pins cannot be
     * written.
     */
    fun pin(dir: String, budget: Long, packs: List<Pack>): State? {
        if (Native.load() != null) return null
        val identities = ByteArray(SHA256 * packs.size)
        packs.forEachIndexed { i, p -> p.identity.copyInto(identities, SHA256 * i, 0, SHA256) }
        val tiles = LongArray(packs.sumOf { it.tiles.size })
        var at = 0
        for (p in packs) {
            p.tiles.copyInto(tiles, at)
            at += p.tiles.size
        }
        val r = Native.worldKept(dir, budget, LongArray(packs.size) { packs[it].length }, identities, IntArray(packs.size) { packs[it].tiles.size }, tiles)
            ?: return null
        val heads = r[3].toInt()
        val first = 4 + heads
        return State(
            heads = List(heads) { r[4 + it].toInt() },
            chunks = List((r.size - first) / 4) { i -> Chunk(r[first + 4 * i].toInt(), r[first + 4 * i + 1], r[first + 4 * i + 2], r[first + 4 * i + 3]) },
            unknown = r[0].toInt(),
            held = r[1].toInt(),
            heldBytes = r[2],
        )
    }

    /**
     * The head of kept pack [pack] the app fetched, into the world's cache in [dir]: its bytes but its `chunks` section
     * as [ranges] (each its offset in the pack and its bytes), kept once the pack opens from them alone. "" or why not.
     */
    fun head(dir: String, budget: Long, pack: Pack, ranges: List<Pair<Long, ByteArray>>): String {
        Native.load()?.let { return it.ifEmpty { "the core did not load" } }
        return Native.worldHead(dir, budget, WORLD_MID, pack.length, pack.identity, LongArray(ranges.size) { ranges[it].first }, Array(ranges.size) { ranges[it].second })
    }

    /**
     * A range of the bytes of kept pack [pack] from [offset] the app fetched, into the world's cache in [dir]: its
     * whole chunks checked against their SHA-256 and kept. Null where the cache holds no head that opens the pack.
     */
    fun range(dir: String, budget: Long, pack: Pack, offset: Long, bytes: ByteArray): Ranged? {
        if (Native.load() != null) return null
        return Native.worldKeptRange(dir, budget, pack.length, pack.identity, offset, bytes)?.let { r -> Ranged(r[0].toInt(), r.size - 1) }
    }

    /**
     * Empties the world's cache in [dir] of the heads and chunks a world the phone left held there: with [pinned], of
     * all but what is pinned ([pin]: the kept countries' world of the world it took stays); without, of everything, the
     * pins too. "" or why not.
     */
    fun drop(dir: String, budget: Long, pinned: Boolean): String {
        Native.load()?.let { return it.ifEmpty { "the core did not load" } }
        return Native.worldDrop(dir, budget, pinned)
    }
}
