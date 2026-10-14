package app.atlas.packer

/**
 * Where every node is, by id, so ways and relations can be placed: 12 bytes a node. Ids arrive ascending (as in a file
 * sorted by type then id) and are kept in pages of [PAGE] entries as offsets from the page's first id, coordinates as
 * 1e-7 degree integers beside them; a lookup is two binary searches. Ids that arrive out of order switch it to a hash
 * index over the same pages: more memory, the same answers.
 */
class NodeLocations {
    private var firstIds = LongArray(64)
    private var fills = IntArray(64)
    private var idPages = arrayOfNulls<IntArray>(64)
    private var coordPages = arrayOfNulls<IntArray>(64)
    private var pages = 0
    private var lastId = Long.MIN_VALUE
    private var hash: LongLongMap? = null
    private var cachedPage = -1

    /** Nodes stored. */
    var size = 0L
        private set

    /** True while every id came in ascending order (the compact layout). */
    val isSorted: Boolean get() = hash == null

    /** Stores node [id] at ([latE7], [lonE7]); a later position for the same id replaces the earlier one. */
    fun put(id: Long, latE7: Int, lonE7: Int) {
        val h = hash
        if (h == null && size > 0 && id <= lastId) {
            if (id == lastId) {
                val i = indexOf(id)
                set(i, latE7, lonE7)
                return
            }
            toHash()
        }
        val index = append(id, latE7, lonE7)
        hash?.put(id, index)
        if (id > lastId) lastId = id
        size++
    }

    /** The index of node [id] for [lat] and [lon], or -1 when it is not here. */
    fun indexOf(id: Long): Long {
        hash?.let { return it.get(id) }
        if (pages == 0 || id < firstIds[0]) return -1
        val p = page(id)
        val delta = id - firstIds[p]
        if (delta > Int.MAX_VALUE) return -1
        val j = java.util.Arrays.binarySearch(idPages[p]!!, 0, fills[p], delta.toInt())
        return if (j < 0) -1 else (p.toLong() shl PAGE_BITS) + j
    }

    /** Latitude in 1e-7 degrees of the node at [index]. */
    fun lat(index: Long): Int = coordPages[(index ushr PAGE_BITS).toInt()]!![(index and MASK).toInt() * 2]

    /** Longitude in 1e-7 degrees of the node at [index]. */
    fun lon(index: Long): Int = coordPages[(index ushr PAGE_BITS).toInt()]!![(index and MASK).toInt() * 2 + 1]

    /** Lets go of everything stored (a country's worth of memory). */
    fun clear() {
        firstIds = LongArray(64)
        fills = IntArray(64)
        idPages = arrayOfNulls(64)
        coordPages = arrayOfNulls(64)
        pages = 0
        lastId = Long.MIN_VALUE
        hash = null
        cachedPage = -1
        size = 0
    }

    /** Bytes held, roughly. */
    val bytes: Long get() = pages.toLong() * PAGE * 12 + (hash?.bytes ?: 0)

    private fun page(id: Long): Int {
        val c = cachedPage
        if (c >= 0 && id >= firstIds[c] && (c == pages - 1 || id < firstIds[c + 1])) return c
        var lo = 0
        var hi = pages - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstIds[mid] <= id) lo = mid else hi = mid - 1
        }
        cachedPage = lo
        return lo
    }

    private fun append(id: Long, latE7: Int, lonE7: Int): Long {
        var p = pages - 1
        val sortedMode = hash == null
        // a new page when this one is full, or (sorted) when the id is too far from the page's first for an Int offset
        if (p < 0 || fills[p] == PAGE || (sortedMode && id - firstIds[p] > Int.MAX_VALUE)) p = newPage(id)
        val j = fills[p]
        idPages[p]!![j] = if (sortedMode) (id - firstIds[p]).toInt() else 0
        val c = coordPages[p]!!
        c[j * 2] = latE7
        c[j * 2 + 1] = lonE7
        fills[p] = j + 1
        return (p.toLong() shl PAGE_BITS) + j
    }

    private fun set(index: Long, latE7: Int, lonE7: Int) {
        val c = coordPages[(index ushr PAGE_BITS).toInt()]!!
        val j = (index and MASK).toInt() * 2
        c[j] = latE7
        c[j + 1] = lonE7
    }

    private fun newPage(id: Long): Int {
        if (pages == firstIds.size) {
            val n = pages * 2
            firstIds = firstIds.copyOf(n)
            fills = fills.copyOf(n)
            idPages = idPages.copyOf(n)
            coordPages = coordPages.copyOf(n)
        }
        firstIds[pages] = id
        idPages[pages] = IntArray(PAGE)
        coordPages[pages] = IntArray(PAGE * 2)
        cachedPage = -1
        return pages++
    }

    /** Builds the hash index over what is stored so far; from now on ids go in any order. */
    private fun toHash() {
        val h = LongLongMap((size * 2).coerceAtMost(Int.MAX_VALUE / 4L).toInt().coerceAtLeast(1024))
        for (p in 0 until pages) {
            val ids = idPages[p]!!
            for (j in 0 until fills[p]) h.put(firstIds[p] + ids[j], (p.toLong() shl PAGE_BITS) + j)
        }
        hash = h
    }

    companion object {
        const val PAGE_BITS = 16
        const val PAGE = 1 shl PAGE_BITS
        private const val MASK = (PAGE - 1).toLong()
    }
}

/** An open-addressing map from long keys to long values, without boxing; [get] answers -1 for a missing key. */
class LongLongMap(expected: Int = 64) {
    private var keys: LongArray
    private var values: LongArray
    private var used: BooleanArray
    private var mask: Int

    var size = 0
        private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap)
        values = LongArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    val bytes: Long get() = keys.size * 17L

    fun get(key: Long): Long {
        var i = slot(key)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and mask
        }
        return -1
    }

    operator fun contains(key: Long): Boolean {
        var i = slot(key)
        while (used[i]) {
            if (keys[i] == key) return true
            i = (i + 1) and mask
        }
        return false
    }

    fun put(key: Long, value: Long) {
        var i = slot(key)
        while (used[i]) {
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        values[i] = value
        if (++size * 2 > keys.size) grow()
    }

    private fun slot(key: Long): Int {
        var z = key * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return ((z xor (z ushr 31)) and mask.toLong()).toInt()
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        values = LongArray(oldKeys.size * 2)
        used = BooleanArray(oldKeys.size * 2)
        mask = keys.size - 1
        for (j in oldKeys.indices) {
            if (!oldUsed[j]) continue
            var i = slot(oldKeys[j])
            while (used[i]) i = (i + 1) and mask
            used[i] = true
            keys[i] = oldKeys[j]
            values[i] = oldValues[j]
        }
    }
}
