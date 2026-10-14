package app.atlas.engine

/** A growable float list without boxing. */
internal class FloatList(capacity: Int = 64) {
    private var a = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(maxOf(8, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): FloatArray = a.copyOf(size)
}

/** A growable double list without boxing. */
internal class DoubleList(capacity: Int = 64) {
    private var a = DoubleArray(capacity)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(maxOf(8, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): DoubleArray = a.copyOf(size)
}

/** A growable int list without boxing. */
internal class IntList(capacity: Int = 64) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(maxOf(8, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}

/** A growable long list without boxing. */
internal class LongList(capacity: Int = 64) {
    private var a = LongArray(capacity)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(maxOf(8, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
}
