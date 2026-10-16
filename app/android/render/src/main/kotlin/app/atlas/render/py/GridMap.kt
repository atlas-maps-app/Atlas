package app.atlas.render.py

/**
 * A map from a grid cell's packed key ([key]) to a value: open addressing over a mixed hash, iterated in insertion order.
 *
 * It stands where the port had a `HashMap<Long, …>` of cells. Those keys, `(i shl 32) xor j`, hash in `java.lang.Long` to
 * `i xor j`, so a city's cells fell into a few hundred buckets and every lookup walked a tree bin. Lookups give the same
 * values as the HashMap did; iteration, which the HashMap gave in bucket order, here follows insertion, as Python's dict.
 */
class GridMap<V : Any>(expected: Int = 16) {
    private var keys = LongArray(0)
    private var vals = arrayOfNulls<Any>(0)
    private var slots = IntArray(0)
    private var mask = 0

    /** How many cells hold a value. */
    var size: Int = 0
        private set

    init {
        allocate(expected)
    }

    private fun allocate(expected: Int) {
        var cap = 8
        while (cap * 3 < expected.coerceAtLeast(1) * 4) cap = cap shl 1 // load factor 0.75
        slots = IntArray(cap) { -1 }
        mask = cap - 1
        if (keys.size < expected) {
            keys = keys.copyOf(expected.coerceAtLeast(4))
            vals = vals.copyOf(expected.coerceAtLeast(4))
        }
    }

    private fun slotOf(key: Long): Int {
        var s = mix(key) and mask
        while (true) {
            val e = slots[s]
            if (e < 0 || keys[e] == key) return s
            s = (s + 1) and mask
        }
    }

    /** The value at [key], or null. */
    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Long): V? {
        val e = slots[slotOf(key)]
        return if (e < 0) null else vals[e] as V
    }

    /** True when [key] holds a value. */
    operator fun contains(key: Long): Boolean = slots[slotOf(key)] >= 0

    /** Puts [value] at [key], replacing what was there (a replaced cell keeps its place in the order). */
    operator fun set(key: Long, value: V) {
        val s = slotOf(key)
        val e = slots[s]
        if (e >= 0) vals[e] = value else insert(s, key, value)
    }

    /** The value at [key], first putting [make]'s there when it has none. */
    @Suppress("UNCHECKED_CAST")
    inline fun getOrPut(key: Long, make: () -> V): V = get(key) ?: make().also { set(key, it) }

    private fun insert(slot: Int, key: Long, value: V) {
        if (size == keys.size) {
            val n = keys.size * 2
            keys = keys.copyOf(n); vals = vals.copyOf(n)
        }
        keys[size] = key; vals[size] = value
        slots[slot] = size
        size++
        if (size * 4 > slots.size * 3) rehash()
    }

    private fun rehash() {
        val cap = slots.size shl 1
        slots = IntArray(cap) { -1 }
        mask = cap - 1
        for (e in 0 until size) {
            var s = mix(keys[e]) and mask
            while (slots[s] >= 0) s = (s + 1) and mask
            slots[s] = e
        }
    }

    /** Each cell's key and value, in insertion order. */
    @Suppress("UNCHECKED_CAST")
    inline fun forEach(action: (Long, V) -> Unit) {
        for (e in 0 until size) action(keyAt(e), valueAt(e) as V)
    }

    /** The key of the [e]th cell inserted. */
    fun keyAt(e: Int): Long = keys[e]

    /** The value of the [e]th cell inserted. */
    fun valueAt(e: Int): Any? = vals[e]

    companion object {
        /** The packed key of cell (i, j), as the port has always packed them: `(i shl 32) xor (j and 0xffffffff)`. */
        fun key(i: Long, j: Long): Long = (i shl 32) xor (j and 0xffffffffL)

        /** The packed key of cell (i, j). */
        fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

        /** A slot index from a packed key: MurmurHash3's 64-bit finaliser, so both halves of the key reach the low bits. */
        internal fun mix(key: Long): Int {
            var h = key xor (key ushr 33)
            h *= -49064778989728563L // 0xff51afd7ed558ccd
            h = h xor (h ushr 33)
            h *= -4265267296055464877L // 0xc4ceb9fe1a85ec53
            return (h xor (h ushr 33)).toInt()
        }
    }
}

/** A set of packed grid keys ([GridMap.key]): open addressing over the same mixed hash as [GridMap]. */
class GridSet(expected: Int = 16) {
    private var table = LongArray(0)
    private var used = BooleanArray(0)
    private var mask = 0

    /** How many keys are in the set. */
    var size: Int = 0
        private set

    init {
        var cap = 8
        while (cap * 3 < expected.coerceAtLeast(1) * 4) cap = cap shl 1
        table = LongArray(cap); used = BooleanArray(cap); mask = cap - 1
    }

    /** True when [key] is in the set. */
    operator fun contains(key: Long): Boolean {
        var s = GridMap.mix(key) and mask
        while (used[s]) {
            if (table[s] == key) return true
            s = (s + 1) and mask
        }
        return false
    }

    /** Adds [key]; true when it was not there yet. */
    fun add(key: Long): Boolean {
        var s = GridMap.mix(key) and mask
        while (used[s]) {
            if (table[s] == key) return false
            s = (s + 1) and mask
        }
        table[s] = key; used[s] = true; size++
        if (size * 4 > table.size * 3) grow()
        return true
    }

    private fun grow() {
        val oldT = table; val oldU = used
        val cap = table.size shl 1
        table = LongArray(cap); used = BooleanArray(cap); mask = cap - 1
        for (s in oldT.indices) {
            if (!oldU[s]) continue
            var t = GridMap.mix(oldT[s]) and mask
            while (used[t]) t = (t + 1) and mask
            table[t] = oldT[s]; used[t] = true
        }
    }
}
