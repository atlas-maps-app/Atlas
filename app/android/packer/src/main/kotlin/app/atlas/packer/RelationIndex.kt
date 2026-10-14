package app.atlas.packer

import app.atlas.packer.pbf.PbfRelation

/**
 * Every relation's members by relation id, gathered in a first pass, so a relation that holds other relations can be
 * given a box, and so the second pass knows which ways the kept relations need. Members are kept in flat primitive
 * arrays: about nine bytes each.
 */
class RelationIndex {
    private val slots = LongLongMap(1024)
    private var starts = IntArray(1024)
    private var keptFlags = BooleanArray(1024)
    private var memberIds = LongArray(16 * 1024)
    private var memberTypes = ByteArray(16 * 1024)
    private var members = 0

    /** Relations indexed. */
    var size = 0
        private set

    /** Indexes [r]'s members; [kept] marks a relation the store keeps. */
    fun add(r: PbfRelation, kept: Boolean) {
        if (r.id in slots) return
        if (size + 1 >= starts.size) {
            starts = starts.copyOf(starts.size * 2)
            keptFlags = keptFlags.copyOf(starts.size)
        }
        val n = r.memberCount
        if (members + n > memberIds.size) {
            var c = memberIds.size
            while (c < members + n) c *= 2
            memberIds = memberIds.copyOf(c)
            memberTypes = memberTypes.copyOf(c)
        }
        starts[size] = members
        keptFlags[size] = kept
        for (i in 0 until n) {
            memberIds[members] = r.memberId(i)
            memberTypes[members] = r.memberType(i).toByte()
            members++
        }
        slots.put(r.id, size.toLong())
        size++
        starts[size] = members
    }

    /** The slot of relation [id], or -1 when it was not in the file. */
    fun slot(id: Long): Int = slots.get(id).toInt()

    fun memberCount(slot: Int): Int = starts[slot + 1] - starts[slot]
    fun memberId(slot: Int, i: Int): Long = memberIds[starts[slot] + i]
    fun memberType(slot: Int, i: Int): Int = memberTypes[starts[slot] + i].toInt()

    /**
     * The ids of the ways the kept relations hold, directly or through relations down to [maxDepth] levels, sorted
     * and without repeats, for a binary search per way in the second pass.
     */
    fun neededWays(maxDepth: Int = MAX_DEPTH): LongArray {
        var out = LongArray(1024)
        var n = 0
        val seen = HashSet<Int>()
        fun visit(slot: Int, depth: Int) {
            for (i in 0 until memberCount(slot)) {
                when (memberType(slot, i)) {
                    PbfRelation.WAY -> {
                        if (n == out.size) out = out.copyOf(n * 2)
                        out[n++] = memberId(slot, i)
                    }
                    PbfRelation.RELATION -> if (depth < maxDepth) {
                        val s = slot(memberId(slot, i))
                        if (s >= 0 && seen.add(s)) visit(s, depth + 1)
                    }
                }
            }
        }
        for (s in 0 until size) {
            if (!keptFlags[s]) continue
            seen.clear()
            seen.add(s)
            visit(s, 0)
        }
        val sorted = out.copyOf(n)
        sorted.sort()
        var m = 0
        for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[m++] = sorted[i]
        return sorted.copyOf(m)
    }

    companion object {
        /** How deep relations of relations are followed for boxes. */
        const val MAX_DEPTH = 3
    }
}
