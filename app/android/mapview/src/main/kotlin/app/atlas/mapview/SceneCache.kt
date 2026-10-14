package app.atlas.mapview

import app.atlas.engine.TileScene
import app.atlas.geo.TileId

/**
 * Built scenes by tile and look, least recently used first out, within a memory budget of [budgetBytes] (each scene's
 * own estimate, [TileScene.bytes]). Tiles the last frame drew are pinned: the budget may be overrun rather than drop a
 * scene still on screen. Thread-safe.
 */
class SceneCache(val budgetBytes: Long) {
    /** A scene's identity: the tile and the look it was built for. */
    data class Key(val id: TileId, val look: String)

    private val map = LinkedHashMap<Key, TileScene>(128, 0.75f, true)
    private val newest = HashMap<TileId, Key>()
    private val pinned = HashSet<TileId>()
    private var pinnedLook: String? = null

    /** Bytes held now. */
    var bytes = 0L
        private set

    val size: Int @Synchronized get() = map.size

    /** The scene of [id] for [look], marking it recently used. */
    @Synchronized
    fun get(id: TileId, look: String): TileScene? = map[Key(id, look)]

    @Synchronized
    fun contains(key: Key): Boolean = map.containsKey(key)

    /** The newest scene of [id] in any look: a stand-in while a new look builds. */
    @Synchronized
    fun any(id: TileId): TileScene? = newest[id]?.let { map[it] }

    @Synchronized
    fun put(key: Key, scene: TileScene) {
        map.put(key, scene)?.let { bytes -= it.bytes }
        bytes += scene.bytes
        newest[key.id] = key
        trim()
    }

    /**
     * Keeps the scenes of [tiles] (what the frame drew) from eviction until the next call: those of [look], and an older
     * look's only while the tile has none of [look] yet.
     */
    @Synchronized
    fun pinTiles(tiles: Collection<TileId>, look: String) {
        pinned.clear()
        pinned.addAll(tiles)
        pinnedLook = look
        trim()
    }

    @Synchronized
    fun clear() {
        map.clear()
        newest.clear()
        bytes = 0
    }

    private fun trim() {
        if (bytes <= budgetBytes) return
        val it = map.entries.iterator()
        while (bytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (e.key.id in pinned && (e.key.look == pinnedLook || !map.containsKey(Key(e.key.id, pinnedLook ?: "")))) continue
            bytes -= e.value.bytes
            it.remove()
            if (newest[e.key.id] == e.key) newest.remove(e.key.id)
        }
    }

    companion object {
        /** A budget for this process: a quarter of the heap, between 48 and 256 MB. */
        fun defaultBudget(): Long = (Runtime.getRuntime().maxMemory() / 4).coerceIn(48L shl 20, 256L shl 20)
    }
}
