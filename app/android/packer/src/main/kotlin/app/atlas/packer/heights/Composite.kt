package app.atlas.packer.heights

/**
 * How a window's sources combine (ENGINE.md part 6, "How sources combine"): the source of the highest priority gives
 * every sample it has; a hole within its fill distance of its samples is filled from its surroundings (water and
 * buildings, which a laser survey's terrain model leaves empty); beyond that the next source gives the sample, with the
 * offset between the two at the first one's edge feathered out over the feather distance (national datums differ by up
 * to a metre, and a surface model stands above a terrain model by tens of metres in forests); and where no source has a
 * sample it stands at 0 m (the open sea). The offset is taken where the fill ends: what the filled surface stands above
 * the next source there, carried outward ring by ring and faded to nothing at the feather's end.
 *
 * Distances are counted in rings: a node's ring is how many steps, the diagonal ones too, lie between it and the first
 * source's nearest sample. A node of ring k takes the mean of its neighbours of ring k − 1, so a node's value depends
 * only on the nodes within its ring of it: a window grown by the fill and feather distances gives its inner nodes the
 * values any other such window gives them.
 */
object Composite {
    /**
     * What [merge] made: the heights and each node's source: its index in the build's sources (below [FILLED]), with
     * [FILLED] added where the node was filled from its surroundings; [NONE] where no source has a sample.
     */
    class Merged(val heights: FloatArray, val source: ByteArray)

    const val FILLED = 0x40
    const val NONE: Byte = -1

    /** The source's index of a node's [source] byte, or −1 for none. */
    fun index(source: Byte): Int = if (source < 0) -1 else source.toInt() and (FILLED - 1)

    /**
     * [first] (null: it has nothing here) over [next] (null likewise) on a window [width] by [height]: [fill] and
     * [feather] the first one's distances in nodes, [firstIndex] its index among the build's sources. [next] is what
     * the sources after it gave, a merge's own result: [nextSource] names each of its nodes' source.
     */
    fun merge(first: FloatArray?, next: FloatArray?, nextSource: ByteArray?, width: Int, height: Int, fill: Int, feather: Int, firstIndex: Int): Merged {
        require(firstIndex in 0 until FILLED)
        val n = width * height
        val out = FloatArray(n)
        val src = ByteArray(n)
        val merged = Merged(out, src)
        fun fromNext(i: Int): Float {
            val v = next?.get(i) ?: Float.NaN
            if (v.isNaN() || nextSource == null || nextSource[i] < 0) {
                src[i] = NONE
                return 0f
            }
            src[i] = nextSource[i]
            return v
        }
        if (first == null) {
            for (i in 0 until n) out[i] = fromNext(i)
            return merged
        }
        // each node's ring: its steps from the first source's nearest sample, as far as the fill and the feather reach
        val reach = fill + feather
        val ring = IntArray(n) { if (first[it].isNaN()) Int.MAX_VALUE else 0 }
        var frontier = IntArray(n)
        var count = 0
        for (i in 0 until n) if (ring[i] == 0) frontier[count++] = i
        if (count == n) {
            for (i in 0 until n) {
                out[i] = first[i]
                src[i] = firstIndex.toByte()
            }
            return merged
        }
        // filled: the first source's own values and its fills; offset: what it stands above the next one
        val filled = FloatArray(n) { first[it] }
        val offset = FloatArray(n)
        for (i in 0 until n) if (ring[i] == 0) offset[i] = first[i] - (next?.get(i)?.takeIf { !it.isNaN() && nextSource != null && nextSource[i] >= 0 } ?: first[i])
        var nextFrontier = IntArray(n)
        var k = 0
        while (count > 0 && k < reach) {
            k++
            var found = 0
            for (f in 0 until count) {
                val i = frontier[f]
                val x = i % width
                val y = i / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val j = ny * width + nx
                    if (ring[j] != Int.MAX_VALUE) continue
                    ring[j] = k
                    nextFrontier[found++] = j
                }
            }
            // each node of this ring from its neighbours of the ring before, all of which are settled
            for (f in 0 until found) {
                val j = nextFrontier[f]
                val x = j % width
                val y = j / width
                var sum = 0.0
                var sumOffset = 0.0
                var m = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val i = ny * width + nx
                    if (ring[i] != k - 1) continue
                    sum += filled[i]
                    sumOffset += offset[i]
                    m++
                }
                filled[j] = (sum / m).toFloat()
                // within the fill the offset is the filled surface's own, so the next source meets it without a step
                val under = if (k <= fill && nextSource != null && nextSource[j] >= 0) next?.get(j) ?: Float.NaN else Float.NaN
                offset[j] = if (under.isNaN()) (sumOffset / m).toFloat() else filled[j] - under
            }
            val t = frontier
            frontier = nextFrontier
            nextFrontier = t
            count = found
        }
        for (i in 0 until n) {
            val r = ring[i]
            when {
                r == 0 -> {
                    out[i] = first[i]
                    src[i] = firstIndex.toByte()
                }
                r <= fill -> {
                    out[i] = filled[i]
                    src[i] = (firstIndex or FILLED).toByte()
                }
                else -> {
                    val v = fromNext(i)
                    // the offset at the first source's edge, fading to none at the feather's end; none where the next
                    // source has no sample either (the sea stands at 0 m)
                    out[i] = if (r <= reach && src[i] >= 0 && feather > 0) v + offset[i] * (1f - (r - fill).toFloat() / feather) else v
                }
            }
        }
        return merged
    }
}
