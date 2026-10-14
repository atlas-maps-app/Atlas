package app.atlas.providers

/** A small thread-safe least-recently-used map with optional expiry, for answers we must not ask for twice. */
internal class Lru<K, V>(private val max: Int, private val ttlMs: Long = Long.MAX_VALUE, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > max
    }

    @Synchronized
    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (ttlMs != Long.MAX_VALUE && clock() - e.at > ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, clock())
    }

    @Synchronized
    fun clear() = map.clear()
}
