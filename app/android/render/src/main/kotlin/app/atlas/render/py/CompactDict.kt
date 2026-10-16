package app.atlas.render.py

/**
 * An insertion-ordered [MutableMap] laid out as CPython lays out a dict: keys and values side by side in one flat array, in the
 * order they were added, with a small open-addressing index of slot numbers once the map outgrows a short scan.
 *
 * It behaves as [LinkedHashMap] does in insertion order: putting an existing key keeps its place, a removed and re-added key goes
 * last, and equals, hashCode and toString follow the [Map] contract. Null keys and values are allowed. Iterators are fail-fast and
 * support remove; entries write through with setValue. Not thread-safe for writes; reads alone may share it.
 *
 * Up to [SCAN] slots there is no index: a 64-bit filter of the keys' hashes turns most absent keys away, then a lookup scans the
 * keys by identity (literal keys and pooled strings meet themselves), then by hash and equality. Beyond that, each bucket of the
 * index holds a slot number and eight bits of the key's hash, so a probe that meets another key is turned away without reading
 * the table: 16-bit buckets serve up to 255 slots and 32-bit ones up to [MAX_CAPACITY]. A removed slot becomes a hole that
 * iteration skips; holes are squeezed out the next time the array fills.
 */
class CompactDict<K, V>(capacity: Int = 0) : MutableMap<K, V> {
    /** Keys at even positions, their values at the odd one after; a removed slot's key is [HOLE]. */
    private var table: Array<Any?> = if (checkCapacity(capacity) == 0) EMPTY else arrayOfNulls(2 * capacity)

    /** Null while scanning; else a ShortArray or IntArray of buckets, each a hash tag over slot + 1 (0 = empty). */
    private var index: Any? = if (capacity > SCAN) newIndex(capacity) else null

    /** Slots taken, holes included. */
    private var used = 0
    private var count = 0
    private var modCount = 0

    /** One bit per key's hash, so a scanned map turns most absent keys away at once (stale bits of removed keys only cost a scan). */
    private var filter = 0L

    /** A copy of [from], in its order. */
    constructor(from: Map<out K, V>) : this(from.size) {
        putAll(from)
    }

    override val size: Int get() = count

    override fun isEmpty(): Boolean = count == 0

    override fun containsKey(key: K): Boolean = find(key) >= 0

    override fun containsValue(value: V): Boolean {
        val t = table
        for (s in 0 until used) if (t[2 * s] !== HOLE && t[2 * s + 1] == value) return true
        return false
    }

    @Suppress("UNCHECKED_CAST")
    override fun get(key: K): V? {
        val s = find(key)
        return if (s < 0) null else table[2 * s + 1] as V
    }

    @Suppress("UNCHECKED_CAST")
    override fun getOrDefault(key: K, defaultValue: V): V {
        val s = find(key)
        return if (s < 0) defaultValue else table[2 * s + 1] as V
    }

    @Suppress("UNCHECKED_CAST")
    override fun put(key: K, value: V): V? {
        val s = find(key)
        if (s >= 0) {
            val old = table[2 * s + 1] as V
            table[2 * s + 1] = value
            return old
        }
        append(key, value)
        return null
    }

    override fun putAll(from: Map<out K, V>) {
        if (from is CompactDict<*, *>) {
            val t = from.table
            for (s in 0 until from.used) {
                val k = t[2 * s]
                @Suppress("UNCHECKED_CAST")
                if (k !== HOLE) put(k as K, t[2 * s + 1] as V)
            }
        } else {
            for ((k, v) in from) put(k, v)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun remove(key: K): V? {
        val s = find(key)
        if (s < 0) return null
        val old = table[2 * s + 1] as V
        removeAt(s)
        return old
    }

    override fun clear() {
        modCount++
        if (used == 0) return
        clearSlots()
        count = 0
    }

    override val keys: MutableSet<K> get() = Keys()

    override val values: MutableCollection<V> get() = Values()

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = Entries()

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is Map<*, *> || other.size != count) return false
        @Suppress("UNCHECKED_CAST")
        val m = other as Map<Any?, Any?>
        val t = table
        try {
            for (s in 0 until used) {
                val k = t[2 * s]
                if (k === HOLE) continue
                val v = t[2 * s + 1]
                if (v == null) {
                    if (m[k] != null || !m.containsKey(k)) return false
                } else if (v != m[k]) {
                    return false
                }
            }
        } catch (_: ClassCastException) {
            return false
        } catch (_: NullPointerException) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        val t = table
        for (s in 0 until used) {
            val k = t[2 * s]
            if (k !== HOLE) h += (k?.hashCode() ?: 0) xor (t[2 * s + 1]?.hashCode() ?: 0)
        }
        return h
    }

    override fun toString(): String {
        if (count == 0) return "{}"
        val b = StringBuilder("{")
        val t = table
        var first = true
        for (s in 0 until used) {
            val k = t[2 * s]
            if (k === HOLE) continue
            if (!first) b.append(", ")
            first = false
            val v = t[2 * s + 1]
            b.append(if (k === this) "(this Map)" else k).append('=').append(if (v === this) "(this Map)" else v)
        }
        return b.append('}').toString()
    }

    // ---------------------------------------------------------------- slots

    /** The slot holding [key], or -1. */
    private fun find(key: Any?): Int {
        val t = table
        val h = key?.hashCode() ?: 0
        val ix = index ?: return scan(t, key, h)
        val m = h * GOLDEN
        val tag = m and 0xFF
        when (ix) {
            is ShortArray -> {
                val mask = ix.size - 1
                var b = m ushr shiftOf(ix.size)
                while (true) {
                    val e = ix[b].toInt()
                    if (e == 0) return -1
                    if ((e ushr 8) and 0xFF == tag) {
                        val s = (e and 0xFF) - 1
                        if (matches(t[2 * s], key)) return s
                    }
                    b = (b + 1) and mask
                }
            }
            else -> {
                ix as IntArray
                val mask = ix.size - 1
                var b = m ushr shiftOf(ix.size)
                while (true) {
                    val e = ix[b]
                    if (e == 0) return -1
                    if (e ushr 24 == tag) {
                        val s = (e and 0xFFFFFF) - 1
                        if (matches(t[2 * s], key)) return s
                    }
                    b = (b + 1) and mask
                }
            }
        }
    }

    /** [find] without an index: by identity first, then by hash and equality. */
    private fun scan(t: Array<Any?>, key: Any?, h: Int): Int {
        if ((filter and bit(h)) == 0L) return -1
        val n = 2 * used
        var i = 0
        while (i < n) {
            if (t[i] === key) return i shr 1
            i += 2
        }
        if (key == null) return -1
        i = 0
        while (i < n) {
            val k = t[i]
            if (k != null && k !== HOLE && k.hashCode() == h && key == k) return i shr 1
            i += 2
        }
        return -1
    }

    private fun append(key: Any?, value: Any?) {
        if (2 * used == table.size) makeRoom()
        val s = used++
        table[2 * s] = key
        table[2 * s + 1] = value
        count++
        modCount++
        val h = key?.hashCode() ?: 0
        filter = filter or bit(h)
        index?.let { link(it, h, s) }
    }

    private fun removeAt(s: Int) {
        table[2 * s] = HOLE
        table[2 * s + 1] = null
        count--
        modCount++
        if (count == 0) {
            clearSlots()
        } else if (index == null) {
            while (used > 0 && table[2 * (used - 1)] === HOLE) table[2 * --used] = null
        }
    }

    /** Empties every slot and bucket, keeping the arrays. */
    private fun clearSlots() {
        java.util.Arrays.fill(table, 0, 2 * used, null)
        when (val ix = index) {
            is ShortArray -> ix.fill(0)
            is IntArray -> ix.fill(0)
        }
        used = 0
        filter = 0L
    }

    /** Squeezes out the holes, growing by half when there are few. */
    private fun makeRoom() {
        val cap = table.size / 2
        val newCap = when {
            cap > 0 && count <= cap - (cap shr 2) - 1 -> cap
            cap >= MAX_CAPACITY -> throw IllegalStateException("a dict of more than $MAX_CAPACITY keys")
            else -> maxOf(MIN_GROWN, minOf(MAX_CAPACITY, cap + (cap shr 1) + 1))
        }
        val old = table
        val t = if (newCap == cap) old else arrayOfNulls(2 * newCap)
        var j = 0
        var f = 0L
        for (s in 0 until used) {
            val k = old[2 * s]
            if (k === HOLE) continue
            f = f or bit(k?.hashCode() ?: 0)
            t[2 * j] = k
            t[2 * j + 1] = old[2 * s + 1]
            j++
        }
        if (t === old) java.util.Arrays.fill(t, 2 * j, 2 * used, null)
        table = t
        used = j
        filter = f
        val ix = if (newCap > SCAN) newIndex(newCap) else null
        if (ix != null) for (s in 0 until j) link(ix, t[2 * s]?.hashCode() ?: 0, s)
        index = ix
    }

    /** Files slot [s], whose key hashes to [h], in the first free bucket of its probe sequence. */
    private fun link(ix: Any, h: Int, s: Int) {
        val m = h * GOLDEN
        val tag = m and 0xFF
        when (ix) {
            is ShortArray -> {
                val mask = ix.size - 1
                var b = m ushr shiftOf(ix.size)
                while (ix[b].toInt() != 0) b = (b + 1) and mask
                ix[b] = ((tag shl 8) or (s + 1)).toShort()
            }
            else -> {
                ix as IntArray
                val mask = ix.size - 1
                var b = m ushr shiftOf(ix.size)
                while (ix[b] != 0) b = (b + 1) and mask
                ix[b] = (tag shl 24) or (s + 1)
            }
        }
    }

    // ---------------------------------------------------------------- views

    private abstract inner class Cursor<T> : MutableIterator<T> {
        private var next = skip(0)
        private var last = -1
        private var expected = modCount

        private fun skip(from: Int): Int {
            var i = from
            while (i < used && table[2 * i] === HOLE) i++
            return i
        }

        override fun hasNext(): Boolean = next < used

        protected fun nextSlot(): Int {
            if (modCount != expected) throw ConcurrentModificationException()
            if (next >= used) throw NoSuchElementException()
            last = next
            next = skip(next + 1)
            return last
        }

        override fun remove() {
            check(last >= 0) { "next() first" }
            if (modCount != expected) throw ConcurrentModificationException()
            removeAt(last)
            last = -1
            expected = modCount
            next = skip(minOf(next, used))
        }
    }

    private inner class Keys : AbstractMutableSet<K>() {
        override val size: Int get() = count

        override fun contains(element: K): Boolean = find(element) >= 0

        override fun add(element: K): Boolean = throw UnsupportedOperationException()

        override fun remove(element: K): Boolean {
            val s = find(element)
            if (s < 0) return false
            removeAt(s)
            return true
        }

        override fun clear() = this@CompactDict.clear()

        override fun iterator(): MutableIterator<K> = object : Cursor<K>() {
            @Suppress("UNCHECKED_CAST")
            override fun next(): K = table[2 * nextSlot()] as K
        }
    }

    private inner class Values : AbstractMutableCollection<V>() {
        override val size: Int get() = count

        override fun contains(element: V): Boolean = containsValue(element)

        override fun add(element: V): Boolean = throw UnsupportedOperationException()

        override fun clear() = this@CompactDict.clear()

        override fun iterator(): MutableIterator<V> = object : Cursor<V>() {
            @Suppress("UNCHECKED_CAST")
            override fun next(): V = table[2 * nextSlot() + 1] as V
        }
    }

    private inner class Entries : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int get() = count

        override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
            val s = find(element.key)
            return s >= 0 && table[2 * s + 1] == element.value
        }

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

        override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean {
            val s = find(element.key)
            if (s < 0 || table[2 * s + 1] != element.value) return false
            removeAt(s)
            return true
        }

        override fun clear() = this@CompactDict.clear()

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> = object : Cursor<MutableMap.MutableEntry<K, V>>() {
            @Suppress("UNCHECKED_CAST")
            override fun next(): MutableMap.MutableEntry<K, V> {
                val s = nextSlot()
                return Entry(s, table[2 * s] as K, table[2 * s + 1] as V)
            }
        }
    }

    /** One slot, writing through while its key still sits there, and keeping its last value once detached (as a HashMap node does). */
    private inner class Entry(private val slot: Int, override val key: K, private var last: V) : MutableMap.MutableEntry<K, V> {
        private fun live(): Boolean = slot < used && table[2 * slot] === key

        @Suppress("UNCHECKED_CAST")
        override val value: V
            get() = if (live()) (table[2 * slot + 1] as V).also { last = it } else last

        @Suppress("UNCHECKED_CAST")
        override fun setValue(newValue: V): V {
            val old: V
            if (live()) {
                old = table[2 * slot + 1] as V
                table[2 * slot + 1] = newValue
            } else {
                old = last
            }
            last = newValue
            return old
        }

        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && key == other.key && value == other.value

        override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)

        override fun toString(): String = "$key=$value"
    }

    private companion object {
        /** Slots up to which lookups scan instead of keeping an index. */
        const val SCAN = 8

        /** The first capacity of a map that started empty or full. */
        const val MIN_GROWN = 4

        /** The most keys a dict holds: slot numbers fill the low 24 bits of a bucket (a dict this big would not fit a phone's heap). */
        const val MAX_CAPACITY = 0xFFFFFF

        /** 2^32 / φ, odd: Fibonacci hashing spreads keys like `(i shl 32) xor j` and short strings over the buckets. */
        const val GOLDEN = -0x61c88647

        @JvmField
        val EMPTY = arrayOfNulls<Any?>(0)

        /** A removed slot's key: equal to nothing. */
        @JvmField
        val HOLE = Any()

        fun checkCapacity(capacity: Int): Int {
            require(capacity in 0..MAX_CAPACITY) { "a dict's capacity of $capacity keys is outside 0..$MAX_CAPACITY" }
            return capacity
        }

        /** An index for [cap] slots, at most three quarters full, with buckets as narrow as the slot numbers allow. */
        fun newIndex(cap: Int): Any {
            var n = 16
            while (n.toLong() * 3 < cap.toLong() * 4) n = n shl 1
            return if (cap <= 0xFF) ShortArray(n) else IntArray(n)
        }

        /** The shift that keeps the top log2([n]) bits of a mixed hash: its first bucket. */
        fun shiftOf(n: Int): Int = Integer.numberOfLeadingZeros(n) + 1

        /** The [filter] bit of a hash: the top six bits after Fibonacci mixing. */
        fun bit(h: Int): Long = 1L shl ((h * GOLDEN) ushr 26)

        /** Whether the key [k] in a slot whose hash tag matched is [key]. */
        fun matches(k: Any?, key: Any?): Boolean = k === key || (key != null && k !== HOLE && key == k)
    }
}
