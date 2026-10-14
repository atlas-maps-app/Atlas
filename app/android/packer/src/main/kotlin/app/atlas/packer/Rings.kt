package app.atlas.packer

import app.atlas.geo.LatLon

/**
 * Joins the member ways of a multipolygon or boundary into closed rings, end to end and turned as needed, and finds the
 * centroid of the outer rings less the inner ones. Chains that do not close (a relation cut at the extract's edge) are
 * left out; when nothing closes there is no centroid and the caller falls back to the box.
 */
class Rings {
    private val outer = ArrayList<IntArray>()
    private val inner = ArrayList<IntArray>()

    /** Adds a member way's points ((lat, lon) pairs in 1e-7 degrees); [isInner] for role "inner". */
    fun add(points: IntArray, isInner: Boolean) {
        if (points.size < 4) return
        (if (isInner) inner else outer) += points
    }

    /** The rings that close, outer and inner, each as (lat, lon) pairs ending on their first point. */
    fun rings(): Pair<List<IntArray>, List<IntArray>> = assemble(outer) to assemble(inner)

    /** The area-weighted centroid of the outer rings minus the holes, or null when no ring closes. */
    fun centroid(): LatLon? {
        val (outs, ins) = rings()
        if (outs.isEmpty()) return null
        val first = outs[0]
        val m = Shapes.Moments(first[0], first[1])
        for (r in outs) m.addRing(r, 0, r.size / 2 - 1, sign = 1.0)
        val withHoles = Shapes.Moments(first[0], first[1])
        for (r in outs) withHoles.addRing(r, 0, r.size / 2 - 1, sign = 1.0)
        for (r in ins) withHoles.addRing(r, 0, r.size / 2 - 1, sign = -1.0)
        // holes that do not lie in the outer rings (bad data) could flip the sign: then ignore them
        return withHoles.centroid() ?: m.centroid()
    }

    private fun assemble(parts: List<IntArray>): List<IntArray> {
        val rings = ArrayList<IntArray>()
        val open = ArrayList<IntArray>()
        for (p in parts) if (closed(p)) rings += p else open += p
        if (open.isEmpty()) return rings
        // every open way by both its ends
        val byEnd = HashMap<Long, MutableList<Int>>()
        for ((i, p) in open.withIndex()) {
            byEnd.getOrPut(key(p, 0)) { ArrayList(2) } += i
            byEnd.getOrPut(key(p, p.size / 2 - 1)) { ArrayList(2) } += i
        }
        val used = BooleanArray(open.size)
        for (start in open.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = Chain(open[start])
            val startKey = key(open[start], 0)
            var guard = open.size
            while (chain.endKey() != startKey && guard-- > 0) {
                val end = chain.endKey()
                val next = byEnd[end]?.firstOrNull { !used[it] } ?: break
                used[next] = true
                val p = open[next]
                chain.append(p, reversed = key(p, 0) != end)
            }
            if (chain.endKey() == startKey && chain.points >= 4) rings += chain.toArray()
        }
        return rings
    }

    private fun closed(p: IntArray): Boolean {
        val n = p.size / 2
        return n >= 4 && p[0] == p[2 * n - 2] && p[1] == p[2 * n - 1]
    }

    /** A growing ring: points appended from ways, the shared end point once. */
    private class Chain(first: IntArray) {
        private var c = first.copyOf(maxOf(first.size * 2, 16))
        private var size = first.size
        val points: Int get() = size / 2

        fun endKey(): Long = key(c, size / 2 - 1)

        fun append(p: IntArray, reversed: Boolean) {
            val n = p.size / 2
            if (size + p.size > c.size) c = c.copyOf(maxOf(c.size * 2, size + p.size))
            for (k in 1 until n) {
                val j = if (reversed) n - 1 - k else k
                c[size++] = p[2 * j]
                c[size++] = p[2 * j + 1]
            }
        }

        fun toArray(): IntArray = c.copyOf(size)
    }

    private companion object {
        fun key(p: IntArray, i: Int): Long = (p[2 * i].toLong() shl 32) or (p[2 * i + 1].toLong() and 0xffffffffL)
    }
}
