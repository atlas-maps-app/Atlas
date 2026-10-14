package app.atlas.packer.pbf

import java.time.Instant

/** Receives the elements of a PBF file in file order. The objects passed are reused: copy what you keep. */
interface OsmHandler {
    fun node(node: PbfNode) {}
    fun way(way: PbfWay) {}
    fun relation(relation: PbfRelation) {}
}

/**
 * The tags of the element being read, as a map that reads the block's string table in place: no map is built per
 * element. It is reused for the next element, so call [toMap] for a copy.
 */
class TagView internal constructor() : AbstractMap<String, String>() {
    internal var strings: Array<String> = EMPTY
    private var keyIds = IntArray(16)
    private var valueIds = IntArray(16)
    private var count = 0

    override val size: Int get() = count

    fun key(i: Int): String = strings[keyIds[i]]
    fun value(i: Int): String = strings[valueIds[i]]

    override fun get(key: String): String? {
        for (i in 0 until count) if (strings[keyIds[i]] == key) return strings[valueIds[i]]
        return null
    }

    override fun containsKey(key: String): Boolean {
        for (i in 0 until count) if (strings[keyIds[i]] == key) return true
        return false
    }

    override fun isEmpty(): Boolean = count == 0

    override val entries: Set<Map.Entry<String, String>> = object : AbstractSet<Map.Entry<String, String>>() {
        override val size: Int get() = count
        override fun iterator(): Iterator<Map.Entry<String, String>> = object : Iterator<Map.Entry<String, String>> {
            var i = 0
            override fun hasNext() = i < count
            override fun next(): Map.Entry<String, String> {
                if (i >= count) throw NoSuchElementException()
                val e = Entry(key(i), value(i))
                i++
                return e
            }
        }
    }

    // the keys straight from the string table, without an entry per tag: every tagged way is asked for its names
    override val keys: Set<String> = object : AbstractSet<String>() {
        override val size: Int get() = count
        override fun contains(element: String): Boolean = containsKey(element)
        override fun iterator(): Iterator<String> = object : Iterator<String> {
            var i = 0
            override fun hasNext() = i < count
            override fun next(): String {
                if (i >= count) throw NoSuchElementException()
                return key(i++)
            }
        }
    }

    /** A copy that outlives the element, in file order. */
    fun toMap(): Map<String, String> {
        val m = LinkedHashMap<String, String>(count * 2)
        for (i in 0 until count) m[key(i)] = value(i)
        return m
    }

    internal fun clear() {
        count = 0
    }

    internal fun add(k: Int, v: Int) {
        if (k !in strings.indices || v !in strings.indices) throw PbfFormatException("a tag points past the string table ($k=$v of ${strings.size})")
        if (count == keyIds.size) {
            keyIds = keyIds.copyOf(count * 2)
            valueIds = valueIds.copyOf(count * 2)
        }
        keyIds[count] = k
        valueIds[count] = v
        count++
    }

    private class Entry(override val key: String, override val value: String) : Map.Entry<String, String>

    private companion object {
        val EMPTY = emptyArray<String>()
    }
}

/** What every element has: an id, tags, and (when the file carries metadata) a version and the time of its last edit. */
abstract class PbfElement internal constructor() {
    var id: Long = 0
        internal set
    var version: Int = 0
        internal set

    /** Epoch milliseconds of the last edit, or 0 when the file has no metadata. */
    var timestampMillis: Long = 0
        internal set
    val tags: TagView = TagView()

    /** The last edit as an ISO 8601 instant, or null. */
    val timestamp: Instant? get() = if (timestampMillis > 0) Instant.ofEpochMilli(timestampMillis) else null
}

/** A node: a position in nanodegrees, as the file stores it. */
class PbfNode internal constructor() : PbfElement() {
    var latNano: Long = 0
        internal set
    var lonNano: Long = 0
        internal set
    val lat: Double get() = latNano * 1e-9
    val lon: Double get() = lonNano * 1e-9

    /** The position in 1e-7 degrees, the precision OSM itself keeps. */
    val latE7: Int get() = e7(latNano)
    val lonE7: Int get() = e7(lonNano)

    private fun e7(nano: Long): Int = Math.floorDiv(nano + 50, 100).toInt()
}

/**
 * A way: its node ids in order and, in a file written with locations on ways (`osmium add-locations-to-ways`, which
 * leaves out untagged nodes), each node's position too.
 */
class PbfWay internal constructor() : PbfElement() {
    internal var refArray = LongArray(256)
    var refCount: Int = 0
        internal set
    private var latArray = IntArray(256)
    private var lonArray = IntArray(256)
    private var locationCount = 0

    fun ref(i: Int): Long = refArray[i]

    /** True when the way ends where it starts (a ring) and has at least three corners. */
    val isClosed: Boolean get() = refCount >= 4 && refArray[0] == refArray[refCount - 1]

    /** True when the file gave this way's node positions ([latE7], [lonE7]) beside its refs. */
    val hasLocations: Boolean get() = refCount > 0 && locationCount == refCount

    /** Latitude of node [i] in 1e-7 degrees, when [hasLocations]; outside ±90° where the writer did not know it. */
    fun latE7(i: Int): Int = latArray[i]

    /** Longitude of node [i] in 1e-7 degrees, when [hasLocations]; outside ±180° where the writer did not know it. */
    fun lonE7(i: Int): Int = lonArray[i]

    /** True when node [i] has a real position on the way (writers mark a node they could not find out of range). */
    fun hasLocation(i: Int): Boolean = hasLocations && latArray[i] in -MAX_LAT..MAX_LAT && lonArray[i] in -MAX_LON..MAX_LON

    internal fun addRef(id: Long) {
        if (refCount == refArray.size) refArray = refArray.copyOf(refCount * 2)
        refArray[refCount++] = id
    }

    internal fun clearLocations() {
        locationCount = 0
    }

    /** Sets node [i]'s latitude ([isLat]) or longitude, in nanodegrees, as the lat and lon fields arrive. */
    internal fun setLocation(i: Int, nano: Long, isLat: Boolean) {
        if (i >= latArray.size) {
            val n = maxOf(latArray.size * 2, i + 1)
            latArray = latArray.copyOf(n)
            lonArray = lonArray.copyOf(n)
        }
        val e7 = Math.floorDiv(nano + 50, 100).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        if (isLat) latArray[i] = e7 else lonArray[i] = e7
    }

    internal fun locationsRead(lats: Int, lons: Int) {
        locationCount = if (lats == lons) lats else 0
    }

    private companion object {
        const val MAX_LAT = 900_000_000
        const val MAX_LON = 1_800_000_000
    }
}

/** A relation: its members with their types, ids and roles. */
class PbfRelation internal constructor() : PbfElement() {
    internal var ids = LongArray(64)
    internal var types = ByteArray(64)
    internal var roles = IntArray(64)
    var memberCount: Int = 0
        internal set

    fun memberId(i: Int): Long = ids[i]

    /** [NODE], [WAY] or [RELATION]. */
    fun memberType(i: Int): Int = types[i].toInt()
    fun role(i: Int): String = tags.strings[roles[i]]

    internal fun ensure(n: Int) {
        if (n <= ids.size) return
        var c = ids.size
        while (c < n) c *= 2
        ids = ids.copyOf(c)
        types = types.copyOf(c)
        roles = roles.copyOf(c)
    }

    companion object {
        const val NODE = 0
        const val WAY = 1
        const val RELATION = 2
    }
}
