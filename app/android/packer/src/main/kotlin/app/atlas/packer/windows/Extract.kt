package app.atlas.packer.windows

import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import app.atlas.packer.pbf.TagView
import java.io.File
import java.util.BitSet

/**
 * What a window's answer can hold of a PBF extract, read once into memory: every node's position (12 bytes a node:
 * about 2 GB for the Netherlands), every tagged node with its tags (escaped as the answer prints them), every way as
 * its nodes (indices into the nodes) with its tags, and the relations the window query asks for (`type=multipolygon`, and
 * `boundary=national_park` or `protected_area`) with their members found among the nodes and ways. The extract must be
 * sorted by type, then id, as Geofabrik's are; an element of one that refers to an element it does not hold is kept
 * and marked, so a window holding it is left out.
 */
class Extract private constructor() {
    internal val nodes = NodeTable()

    /** The tagged nodes, the answer's node elements, in id order: each its node and its tags. */
    internal val taggedNode = IntPages()
    internal val taggedJson = LongPages()
    internal val taggedLength = IntPages()
    internal val taggedTwice = BitSet()

    internal val wayIds = LongPages()

    /** Way w's nodes are wayRefs[wayStart[w] until wayStart[w + 1]]: indices into [nodes], -1 for one not in the extract. */
    internal val wayStart = LongPages().apply { add(0) }
    internal val wayRefs = IntPages()
    internal val wayTags = LongPages()
    internal val wayTagLength = IntPages()

    /** The ways with a node the extract does not hold, and those with a key twice. */
    internal val wayBroken = BitSet()
    internal val wayTwice = BitSet()

    internal val relationIds = LongList()

    /** Relation r's members are members[relationStart[r] until relationStart[r + 1]]. */
    internal val relationStart = IntList().apply { add(0) }
    internal val memberType = ByteList()
    internal val memberRef = LongList()

    /** A member node's or way's index among [nodes] or the ways, -1 for one not in the extract or a relation. */
    internal val memberIndex = IntList()
    internal val memberRole = IntList()
    internal val relationTags = LongList()
    internal val relationTagLength = IntList()

    /** The relations a member of which is not in the extract (a node, a way, or a node of a member way), and those with a key twice. */
    internal val relationBroken = BitSet()
    internal val relationTwice = BitSet()

    /** The roles as escape_cstr writes them. */
    internal val roles = ArrayList<ByteArray>()
    private val roleIndex = HashMap<String, Int>()

    /** The elements' tags, as [AnswerJson.tags] keeps them. */
    internal val json = ByteStore()

    /** Which ways are members of a kept relation, and theirs as (way shl 32 or relation), sorted. */
    internal val memberWays = BitSet()
    internal var wayRelations = LongArray(0)
        private set

    /** Each kept relation's members that are nodes in the extract: (relation shl 32 or node), sorted. */
    internal var relationNodes = LongArray(0)
        private set

    val nodeCount: Long get() = nodes.size
    val taggedCount: Int get() = taggedNode.size.toInt()
    val wayCount: Int get() = wayIds.size.toInt()
    val relationCount: Int get() = relationIds.size
    val brokenWays: Int get() = wayBroken.cardinality()
    val brokenRelations: Int get() = relationBroken.cardinality()
    val twice: Int get() = taggedTwice.cardinality() + wayTwice.cardinality() + relationTwice.cardinality()

    /** Bytes held, roughly: the node table, the elements' tags, and the rest (the ways' arrays among it). */
    val bytes: Long get() = nodes.bytes + json.bytes + otherBytes
    val nodeBytes: Long get() = nodes.bytes
    val jsonBytes: Long get() = json.bytes
    val otherBytes: Long
        get() = taggedNode.bytes + taggedJson.bytes + taggedLength.bytes + wayIds.bytes + wayStart.bytes + wayRefs.bytes +
            wayTags.bytes + wayTagLength.bytes + memberRef.bytes * 3 + wayRelations.size * 8L + relationNodes.size * 8L

    /** The name or the id of relation [id] of the extract, and its kind. */
    fun describeRelation(id: Long): String {
        var lo = 0
        var hi = relationIds.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = relationIds[mid]
            when {
                v < id -> lo = mid + 1
                v > id -> hi = mid - 1
                else -> return describe(mid)
            }
        }
        return "relation $id"
    }

    private fun describe(r: Int): String {
        val at = relationTags[r]
        val n = relationTagLength[r]
        val kept = if (n == 0) emptyList() else String(json.page(at), json.offset(at), n - 1, Charsets.UTF_8).split(AnswerJson.END.toInt().toChar())
        fun tag(k: String) = (0 until kept.size / 2).firstOrNull { kept[2 * it] == k }?.let { kept[2 * it + 1] }
        val missing = (relationStart[r] until relationStart[r + 1]).count { memberType[it].toInt() != PbfRelation.RELATION && memberIndex[it] < 0 }
        return "relation ${relationIds[r]} (${listOfNotNull(tag("type"), tag("boundary"), tag("name")).joinToString(" ")}; $missing of ${relationStart[r + 1] - relationStart[r]} members missing)"
    }

    fun nodeLat(n: Int): Double = OverpassBox.lat(nodes.latE7(n))
    fun nodeLon(n: Int): Double = OverpassBox.lon(nodes.lonE7(n))

    internal fun wayIndexOf(id: Long): Int {
        var lo = 0L
        var hi = wayIds.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = wayIds[mid]
            when {
                v < id -> lo = mid + 1
                v > id -> hi = mid - 1
                else -> return mid.toInt()
            }
        }
        return -1
    }

    /** The relations way [w] is a member of, as indices into [wayRelations]: from (inclusive) to (exclusive). */
    internal fun relationsOf(w: Int): Long {
        if (!memberWays[w]) return 0
        val key = w.toLong() shl 32
        var lo = 0
        var hi = wayRelations.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (wayRelations[mid] < key) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < wayRelations.size && (wayRelations[end] ushr 32).toInt() == w) end++
        return (lo.toLong() shl 32) or end.toLong()
    }

    private fun role(s: String): Int = roleIndex.getOrPut(s) {
        roles += JsonOut(s.length * 3 + 8).escaped(s).toByteArray()
        roles.size - 1
    }

    private inner class Reading : OsmHandler {
        private var kind = 0
        private var last = Long.MIN_VALUE
        private val out = JsonOut()

        private fun order(k: Int, id: Long) {
            if (k < kind || (k == kind && id <= last)) {
                throw PbfFormatException("the extract is not sorted by type, then id (at ${NAMES[k]} $id): sort it with `osmium sort`")
            }
            kind = k
            last = id
        }

        /** The tags, kept; their position and length into [at] and [length]. False when a key comes twice. */
        private fun keep(tags: TagView, at: LongPages, length: IntPages): Boolean {
            out.clear()
            val once = AnswerJson.tags(tags, out)
            at.add(if (out.size == 0) 0 else json.add(out.bytes, 0, out.size))
            length.add(out.size)
            return once
        }

        override fun node(node: PbfNode) {
            order(1, node.id)
            val n = nodes.add(node.id, node.latE7, node.lonE7)
            if (node.tags.isEmpty()) return
            if (!keep(node.tags, taggedJson, taggedLength)) taggedTwice.set(taggedNode.size.toInt())
            taggedNode.add(n)
        }

        override fun way(way: PbfWay) {
            order(2, way.id)
            val w = wayIds.size.toInt()
            wayIds.add(way.id)
            for (i in 0 until way.refCount) {
                val n = nodes.indexOf(way.ref(i))
                if (n < 0) wayBroken.set(w)
                wayRefs.add(n)
            }
            wayStart.add(wayRefs.size)
            if (!keep(way.tags, wayTags, wayTagLength)) wayTwice.set(w)
        }

        override fun relation(relation: PbfRelation) {
            order(3, relation.id)
            if (!asked(relation.tags)) return
            val r = relationIds.size
            relationIds.add(relation.id)
            var broken = false
            for (i in 0 until relation.memberCount) {
                val type = relation.memberType(i)
                val ref = relation.memberId(i)
                val index = when (type) {
                    PbfRelation.NODE -> nodes.indexOf(ref).also { if (it < 0) broken = true }
                    PbfRelation.WAY -> wayIndexOf(ref).also { if (it < 0 || wayBroken[it]) broken = true }
                    else -> -1
                }
                memberType.add(type.toByte())
                memberRef.add(ref)
                memberIndex.add(index)
                memberRole.add(role(relation.role(i)))
            }
            relationStart.add(memberRef.size)
            if (broken) relationBroken.set(r)
            out.clear()
            if (!AnswerJson.tags(relation.tags, out)) relationTwice.set(r)
            relationTags.add(if (out.size == 0) 0 else json.add(out.bytes, 0, out.size))
            relationTagLength.add(out.size)
        }
    }

    /** The members' indices: which ways belong to which relations, and the member nodes in the extract. */
    private fun index() {
        val ways = LongList()
        val points = LongList()
        for (r in 0 until relationIds.size) {
            for (m in relationStart[r] until relationStart[r + 1]) {
                val i = memberIndex[m]
                if (i < 0) continue
                when (memberType[m].toInt()) {
                    PbfRelation.WAY -> {
                        memberWays.set(i)
                        ways.add((i.toLong() shl 32) or r.toLong())
                    }
                    PbfRelation.NODE -> points.add((r.toLong() shl 32) or i.toLong())
                }
            }
        }
        wayRelations = ways.toSortedUnique()
        relationNodes = points.toSortedUnique()
    }

    companion object {
        private val NAMES = arrayOf("", "node", "way", "relation")

        /** The relations the window query asks for: `relation[type=multipolygon]`, `relation[boundary~"^(national_park|protected_area)$"]`. */
        fun asked(tags: Map<String, String>): Boolean {
            if (tags["type"] == "multipolygon") return true
            val b = tags["boundary"]
            return b == "national_park" || b == "protected_area"
        }

        /** Reads [pbf] whole, in one pass ([threads] inflating its blocks). */
        fun read(pbf: File, threads: Int = PbfReader.defaultThreads()): Extract {
            val e = Extract()
            PbfReader(pbf, threads).read(e.Reading())
            e.index()
            return e
        }
    }
}

/**
 * Node positions by id, for ids that come in ascending order: in pages of 2^16, each its first id and every node's id as
 * an offset from it (a new page where an offset would not fit an Int), its latitude and longitude in 1e-7 degrees. A
 * node's index is its page and place in it (an Int up to 2^31); a lookup is two binary searches.
 */
internal class NodeTable {
    private var firstIds = LongArray(64)
    private var fills = IntArray(64)
    private var offsets = arrayOfNulls<IntArray>(64)
    private var coords = arrayOfNulls<IntArray>(64)
    private var pages = 0

    // the page of the last lookup: a way's nodes lie close together
    private var cached = 0

    var size = 0L
        private set

    val bytes: Long get() = pages.toLong() * PAGE * 12

    /** Adds the node; its id is past every id before. Returns its index. */
    fun add(id: Long, latE7: Int, lonE7: Int): Int {
        var p = pages - 1
        if (p < 0 || fills[p] == PAGE || id - firstIds[p] > Int.MAX_VALUE) {
            require(pages < MAX_PAGES) { "more nodes than an index of 31 bits holds" }
            if (pages == firstIds.size) {
                firstIds = firstIds.copyOf(pages * 2)
                fills = fills.copyOf(pages * 2)
                offsets = offsets.copyOf(pages * 2)
                coords = coords.copyOf(pages * 2)
            }
            firstIds[pages] = id
            offsets[pages] = IntArray(PAGE)
            coords[pages] = IntArray(PAGE * 2)
            p = pages++
        }
        val j = fills[p]++
        offsets[p]!![j] = (id - firstIds[p]).toInt()
        coords[p]!![j * 2] = latE7
        coords[p]!![j * 2 + 1] = lonE7
        size++
        return (p shl BITS) or j
    }

    /** Node [id]'s index, or -1 when the table does not hold it. */
    fun indexOf(id: Long): Int {
        if (pages == 0 || id < firstIds[0]) return -1
        var p = cached
        if (!(p < pages && id >= firstIds[p] && (p == pages - 1 || id < firstIds[p + 1]))) {
            var lo = 0
            var hi = pages - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (firstIds[mid] <= id) lo = mid else hi = mid - 1
            }
            p = lo
            cached = p
        }
        val delta = id - firstIds[p]
        if (delta > Int.MAX_VALUE) return -1
        val j = java.util.Arrays.binarySearch(offsets[p]!!, 0, fills[p], delta.toInt())
        return if (j < 0) -1 else (p shl BITS) or j
    }

    fun id(n: Int): Long = firstIds[n ushr BITS] + offsets[n ushr BITS]!![n and MASK]
    fun latE7(n: Int): Int = coords[n ushr BITS]!![(n and MASK) * 2]
    fun lonE7(n: Int): Int = coords[n ushr BITS]!![(n and MASK) * 2 + 1]

    private companion object {
        const val BITS = 16
        const val PAGE = 1 shl BITS
        const val MASK = PAGE - 1
        const val MAX_PAGES = 1 shl (31 - BITS)
    }
}

/** Longs in pages of 2^20, so a country's worth grows without one big copy. */
internal class LongPages {
    private var pages = arrayOfNulls<LongArray>(16)
    var size = 0L
        private set

    val bytes: Long get() = ((size + PAGE - 1) / PAGE) * PAGE * 8L

    fun add(v: Long) {
        val p = (size ushr BITS).toInt()
        if (p == pages.size) pages = pages.copyOf(p * 2)
        (pages[p] ?: LongArray(PAGE).also { pages[p] = it })[(size and MASK).toInt()] = v
        size++
    }

    operator fun get(i: Long): Long = pages[(i ushr BITS).toInt()]!![(i and MASK).toInt()]
    operator fun get(i: Int): Long = get(i.toLong())

    private companion object {
        const val BITS = 20
        const val PAGE = 1 shl BITS
        const val MASK = PAGE - 1L
    }
}

/** Ints in pages of 2^20. */
internal class IntPages {
    private var pages = arrayOfNulls<IntArray>(16)
    var size = 0L
        private set

    val bytes: Long get() = ((size + PAGE - 1) / PAGE) * PAGE * 4L

    fun add(v: Int) {
        val p = (size ushr BITS).toInt()
        if (p == pages.size) pages = pages.copyOf(p * 2)
        (pages[p] ?: IntArray(PAGE).also { pages[p] = it })[(size and MASK).toInt()] = v
        size++
    }

    operator fun get(i: Long): Int = pages[(i ushr BITS).toInt()]!![(i and MASK).toInt()]
    operator fun get(i: Int): Int = get(i.toLong())

    private companion object {
        const val BITS = 20
        const val PAGE = 1 shl BITS
        const val MASK = PAGE - 1L
    }
}

/** Bytes in pages of 16 MiB, each piece whole in one page: a piece's position is its page shl 32 or its offset there. */
internal class ByteStore(private val pageBytes: Int = 1 shl 24) {
    private val pages = ArrayList<ByteArray>()
    private var fill = pageBytes

    var bytes = 0L
        private set

    fun add(b: ByteArray, from: Int, n: Int): Long {
        if (n > pageBytes) {
            pages += b.copyOfRange(from, from + n)
            fill = pageBytes
            bytes += n
            return (pages.size - 1).toLong() shl 32
        }
        if (fill + n > pageBytes) {
            pages += ByteArray(pageBytes)
            fill = 0
            bytes += pageBytes
        }
        System.arraycopy(b, from, pages[pages.size - 1], fill, n)
        val at = ((pages.size - 1).toLong() shl 32) or fill.toLong()
        fill += n
        return at
    }

    fun page(at: Long): ByteArray = pages[(at ushr 32).toInt()]
    fun offset(at: Long): Int = (at and 0xFFFF_FFFFL).toInt()
}

/** A growable list of longs. */
internal class LongList(capacity: Int = 16) {
    private var a = LongArray(capacity)
    var size = 0
        private set

    val bytes: Long get() = a.size * 8L

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(maxOf(16, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Long = a[i]

    /** The values sorted, each once. */
    fun toSortedUnique(): LongArray {
        val s = a.copyOf(size)
        s.sort()
        var n = 0
        for (i in s.indices) if (i == 0 || s[i] != s[n - 1]) s[n++] = s[i]
        return s.copyOf(n)
    }
}

/** A growable list of ints. */
internal class IntList(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(maxOf(16, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]

    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    fun clear() {
        size = 0
    }

    /** Keeps the first [n] values. */
    fun truncate(n: Int) {
        size = n
    }

    /** Sorts the values and keeps each once. */
    fun sortUnique() {
        java.util.Arrays.sort(a, 0, size)
        var n = 0
        for (i in 0 until size) if (i == 0 || a[i] != a[n - 1]) a[n++] = a[i]
        size = n
    }
}

/** A growable list of bytes. */
internal class ByteList(capacity: Int = 16) {
    private var a = ByteArray(capacity)
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == a.size) a = a.copyOf(maxOf(16, size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Byte = a[i]
}
