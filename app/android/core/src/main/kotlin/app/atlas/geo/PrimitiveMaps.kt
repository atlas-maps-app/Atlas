package app.atlas.geo

/** An open-addressing map from non-negative long keys to floats, without boxing. */
internal class LongFloatMap(expected: Int = 64) {
    private var keys: LongArray
    private var values: FloatArray
    private var mask: Int
    var size = 0; private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap) { EMPTY }
        values = FloatArray(cap)
        mask = cap - 1
    }

    /** The value for [key], or [missing]. */
    fun get(key: Long, missing: Float): Float {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return missing
            i = (i + 1) and mask
        }
    }

    /** Stores the smaller of [value] and what [key] holds. */
    fun putMin(key: Long, value: Float) {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) {
                if (value < values[i]) values[i] = value
                return
            }
            if (k == EMPTY) {
                keys[i] = key
                values[i] = value
                if (++size * 2 > keys.size) grow()
                return
            }
            i = (i + 1) and mask
        }
    }

    inline fun forEach(block: (key: Long, value: Float) -> Unit) {
        val ks = keysArray()
        val vs = valuesArray()
        for (i in ks.indices) if (ks[i] != EMPTY) block(ks[i], vs[i])
    }

    @PublishedApi internal fun keysArray() = keys
    @PublishedApi internal fun valuesArray() = values

    private fun slot(key: Long) = (mix(key) and mask.toLong()).toInt()

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { EMPTY }
        values = FloatArray(oldKeys.size * 2)
        mask = keys.size - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == EMPTY) continue
            var i = slot(k)
            while (keys[i] != EMPTY) i = (i + 1) and mask
            keys[i] = k
            values[i] = oldValues[j]
        }
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE
    }
}

/** An open-addressing map from non-negative long keys to ints, without boxing. */
internal class LongIntMap(expected: Int = 64) {
    private var keys: LongArray
    private var values: IntArray
    private var mask: Int
    var size = 0; private set

    init {
        var cap = 16
        while (cap < expected * 2) cap = cap shl 1
        keys = LongArray(cap) { LongFloatMap.EMPTY }
        values = IntArray(cap)
        mask = cap - 1
    }

    /** The value for [key], or -1. */
    fun get(key: Long): Int {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == LongFloatMap.EMPTY) return -1
            i = (i + 1) and mask
        }
    }

    fun put(key: Long, value: Int) {
        var i = slot(key)
        while (true) {
            val k = keys[i]
            if (k == key) { values[i] = value; return }
            if (k == LongFloatMap.EMPTY) {
                keys[i] = key
                values[i] = value
                if (++size * 2 > keys.size) grow()
                return
            }
            i = (i + 1) and mask
        }
    }

    private fun slot(key: Long) = (mix(key) and mask.toLong()).toInt()

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size * 2) { LongFloatMap.EMPTY }
        values = IntArray(oldKeys.size * 2)
        mask = keys.size - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == LongFloatMap.EMPTY) continue
            var i = slot(k)
            while (keys[i] != LongFloatMap.EMPTY) i = (i + 1) and mask
            keys[i] = k
            values[i] = oldValues[j]
        }
    }
}

/** A growable double array. */
internal class DoubleBuffer(capacity: Int = 16) {
    private var a = DoubleArray(capacity)
    var size = 0; private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(a.size * 2 + 4)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): DoubleArray = a.copyOf(size)
}

// A 64-bit finaliser (from SplitMix64) so grid keys spread over the table.
private fun mix(key: Long): Long {
    var z = key * -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}
