package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlin.coroutines.cancellation.CancellationException

/**
 * The tile found for [target]: the data of [source], which is the target itself or an ancestor
 * (overzoom past the sources' last zoom, or a fallback for a missing tile).
 */
class TileHit(
    val target: TileId,
    val source: TileId,
    /** Raw MVT bytes of [source]. */
    val data: ByteArray,
    /** Which of the stack's sources answered. */
    val sourceIndex: Int,
    /** False when a source failed on the way (no network, say): a better tile may come later, so ask again. */
    val isFinal: Boolean,
) {
    /** The sub-square of [source] to clip to and the scale to draw it at. */
    val transform: TileTransform = TileTransform(source, target)
    val isExact: Boolean get() = source == target

    fun decode(only: Set<String>? = null): VectorTile = Mvt.decode(data, only)

    override fun toString(): String = "TileHit($target from $source, source #$sourceIndex${if (isFinal) "" else ", provisional"})"
}

/**
 * Tile sources in order of preference: the first that has a tile wins (offline packs first, then the cache, then online).
 * [bestTile] also overzooms past the sources' last zoom and falls back to the nearest ancestor a source has.
 * Recent tiles (including ancestors shared by many overzoomed children) are kept in memory up to [memoryBytes].
 */
class TileStack(
    val sources: List<TileSource>,
    private val memoryBytes: Long = 48L shl 20,
    /** How many zoom levels above a missing tile to look for a stand-in. */
    val maxFallback: Int = 6,
    private val clock: () -> Long = System::currentTimeMillis,
) : TileSource {
    constructor(vararg sources: TileSource) : this(sources.toList())

    private class Remembered(val data: ByteArray?, val sourceIndex: Int, val at: Long)

    private val memory = LinkedHashMap<TileId, Remembered>(256, 0.75f, true)
    private var memoryUsed = 0L

    override val minZoom: Int get() = sources.minOfOrNull { it.minZoom } ?: 0
    override val maxZoom: Int get() = sources.maxOfOrNull { it.maxZoom } ?: 14
    override val attribution: String get() = sources.map { it.attribution }.filter { it.isNotBlank() }.distinct().joinToString(" · ")
    override val bounds: BBox
        get() = sources.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: BBox.WORLD

    /** Exactly [id] from the first source that has it. */
    override suspend fun tile(id: TileId): ByteArray? {
        if (!id.isValid()) return null
        return lookup(id, BooleanArray(sources.size))?.data
    }

    /** The best tile to draw for [id]: itself, or the nearest ancestor any source has, with the transform to draw it. */
    suspend fun bestTile(id: TileId): TileHit? {
        if (!id.isValid()) return null
        val failed = BooleanArray(sources.size)
        val top = minOf(id.z, maxZoom)
        val bottom = maxOf(minZoom, top - maxFallback)
        for (z in top downTo bottom) {
            val a = id.ancestor(z)
            val found = lookup(a, failed) ?: continue
            return TileHit(id, a, found.data!!, found.sourceIndex, isFinal = failed.none { it })
        }
        return null
    }

    /** Forgets tiles held in memory, after a pack was added or updated. */
    fun clearMemory() = synchronized(memory) {
        memory.clear()
        memoryUsed = 0
    }

    override fun close() = sources.forEach { runCatching { it.close() } }

    private suspend fun lookup(id: TileId, failed: BooleanArray): Remembered? {
        val now = clock()
        synchronized(memory) {
            val m = memory[id]
            if (m != null) {
                if (m.data != null) return m
                if (now - m.at < MISS_TTL_MILLIS) return null
                memory.remove(id)
            }
        }
        var error = false
        for (i in sources.indices) {
            if (failed[i]) { error = true; continue }
            val s = sources[i]
            if (id.z < s.minZoom || id.z > s.maxZoom) continue
            val b = s.bounds
            if (b !== BBox.WORLD && !b.intersects(id.bbox)) continue
            val data = try {
                s.tile(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An IOException is the contract's "cannot now"; anything else (a pack closed or broken under the stack,
                // an SQLite error) is treated the same way, so one bad source never takes the whole map down.
                failed[i] = true
                error = true
                continue
            }
            if (data != null) return remember(id, Remembered(data, i, now))
        }
        if (!error) remember(id, Remembered(null, -1, now))
        return null
    }

    private fun remember(id: TileId, r: Remembered): Remembered {
        synchronized(memory) {
            memory.put(id, r)?.let { memoryUsed -= cost(it) }
            memoryUsed += cost(r)
            if (memoryUsed > memoryBytes) {
                val it = memory.entries.iterator()
                while (memoryUsed > memoryBytes && it.hasNext()) {
                    val e = it.next()
                    if (e.key == id) continue
                    memoryUsed -= cost(e.value)
                    it.remove()
                }
            }
        }
        return r
    }

    private fun cost(r: Remembered): Long = (r.data?.size ?: 0) + 64L

    private companion object {
        const val MISS_TTL_MILLIS = 5 * 60_000L
    }
}
