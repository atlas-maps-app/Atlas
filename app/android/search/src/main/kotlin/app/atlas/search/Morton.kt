package app.atlas.search

/**
 * A point as one integer key: its 1e-7 degree longitude and latitude, bits interleaved (a Z-order curve). Points near
 * each other get near keys, so a table keyed this way is its own spatial index: a box is a few ranges of keys
 * ([ranges]), with no index beside it. The trees are kept like this.
 */
internal object Morton {
    private const val LON_OFFSET = 1_800_000_000L
    private const val LAT_OFFSET = 900_000_000L

    fun encode(latE7: Int, lonE7: Int): Long = spread(lonE7 + LON_OFFSET) or (spread(latE7 + LAT_OFFSET) shl 1)

    fun latE7(code: Long): Int = (compact(code ushr 1) - LAT_OFFSET).toInt()

    fun lonE7(code: Long): Int = (compact(code) - LON_OFFSET).toInt()

    /**
     * Inclusive key ranges, as pairs (low, high), that hold every point in the box of 1e-7 degree corners (and maybe a
     * few more around it: callers check the points). At most about [maxRanges] ranges, merged where they touch.
     */
    fun ranges(southE7: Int, westE7: Int, northE7: Int, eastE7: Int, maxRanges: Int = 48): LongArray {
        if (southE7 > northE7 || westE7 > eastE7) return LongArray(0)
        val x0 = westE7 + LON_OFFSET
        val x1 = eastE7 + LON_OFFSET
        val y0 = southE7 + LAT_OFFSET
        val y1 = northE7 + LAT_OFFSET
        val out = ArrayList<LongArray>()
        var cells = longArrayOf(0L, 0L)
        var depth = 0
        while (cells.isNotEmpty()) {
            val shift = 32 - depth
            val partial = LongArrayBuilder()
            var i = 0
            while (i < cells.size) {
                val cx = cells[i]
                val cy = cells[i + 1]
                i += 2
                val cellX0 = cx shl shift
                val cellX1 = ((cx + 1) shl shift) - 1
                val cellY0 = cy shl shift
                val cellY1 = ((cy + 1) shl shift) - 1
                if (cellX1 < x0 || cellX0 > x1 || cellY1 < y0 || cellY0 > y1) continue
                val inside = cellX0 >= x0 && cellX1 <= x1 && cellY0 >= y0 && cellY1 <= y1
                if (inside || depth == 32) out += range(cx, cy, shift) else { partial.add(cx); partial.add(cy) }
            }
            if (partial.size == 0) break
            // refining four ways past the budget: the cells left partly in are taken whole
            if (out.size + partial.size * 2 > maxRanges) {
                var j = 0
                while (j < partial.size) { out += range(partial[j], partial[j + 1], shift); j += 2 }
                break
            }
            val next = LongArrayBuilder()
            var j = 0
            while (j < partial.size) {
                val cx = partial[j] shl 1
                val cy = partial[j + 1] shl 1
                next.add(cx); next.add(cy)
                next.add(cx + 1); next.add(cy)
                next.add(cx); next.add(cy + 1)
                next.add(cx + 1); next.add(cy + 1)
                j += 2
            }
            cells = next.toArray()
            depth++
        }
        out.sortBy { it[0] }
        val merged = LongArrayBuilder()
        for (r in out) {
            if (merged.size > 0 && r[0] <= merged[merged.size - 1] + 1) {
                if (r[1] > merged[merged.size - 1]) merged[merged.size - 1] = r[1]
            } else {
                merged.add(r[0]); merged.add(r[1])
            }
        }
        return merged.toArray()
    }

    private fun range(cx: Long, cy: Long, shift: Int): LongArray {
        val prefix = spread(cx) or (spread(cy) shl 1)
        val bits = 2 * shift
        if (bits >= 64) return longArrayOf(0L, Long.MAX_VALUE)
        val low = prefix shl bits
        return longArrayOf(low, low or ((1L shl bits) - 1))
    }

    /** The low 32 bits of [v] spread to the even bits. */
    private fun spread(v: Long): Long {
        var x = v and 0xFFFFFFFFL
        x = (x or (x shl 16)) and 0x0000FFFF0000FFFFL
        x = (x or (x shl 8)) and 0x00FF00FF00FF00FFL
        x = (x or (x shl 4)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x shl 2)) and 0x3333333333333333L
        x = (x or (x shl 1)) and 0x5555555555555555L
        return x
    }

    private fun compact(v: Long): Long {
        var x = v and 0x5555555555555555L
        x = (x or (x ushr 1)) and 0x3333333333333333L
        x = (x or (x ushr 2)) and 0x0F0F0F0F0F0F0F0FL
        x = (x or (x ushr 4)) and 0x00FF00FF00FF00FFL
        x = (x or (x ushr 8)) and 0x0000FFFF0000FFFFL
        x = (x or (x ushr 16)) and 0x00000000FFFFFFFFL
        return x
    }
}

/** A growable array of longs. */
internal class LongArrayBuilder(capacity: Int = 16) {
    private var a = LongArray(capacity)

    var size = 0
        private set

    operator fun get(i: Int) = a[i]

    operator fun set(i: Int, v: Long) {
        a[i] = v
    }

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(maxOf(16, size * 2))
        a[size++] = v
    }

    fun toArray(): LongArray = a.copyOf(size)
}
