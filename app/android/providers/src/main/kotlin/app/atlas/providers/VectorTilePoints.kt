package app.atlas.providers

import java.io.IOException

/** Bytes that are not a vector tile: a field cut short, a wire type the format has none of. */
class VectorTileException(message: String) : IOException(message)

/**
 * The points of a Mapbox Vector Tile (version 2): every point feature's position in the tile's grid and the values of
 * the attributes asked for. Lines and polygons are passed over; each point of a multipoint is a point of its own.
 */
object VectorTilePoints {
    /**
     * One layer's points: positions in its grid of [extent] (a point in the tile's buffer lies outside 0 until
     * [extent]) and, per key asked, every point's value (null where it has none); values are kept as text.
     */
    class Points(val layer: String, val extent: Int, val x: IntArray, val y: IntArray, val values: List<Array<String?>>) {
        val size: Int get() = x.size
    }

    /** The points of every layer of [tile] (unpacked), with their values of [keys]. */
    fun read(tile: ByteArray, keys: List<String>): List<Points> {
        val out = ArrayList<Points>()
        val r = Reader(tile, 0, tile.size)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) out += layer(r.sub(), keys) else r.skip(wire)
        }
        return out
    }

    private fun layer(r: Reader, keys: List<String>): Points {
        var name = ""
        var extent = 4096
        val features = ArrayList<IntRange>()
        val keyNames = ArrayList<String>()
        val values = ArrayList<String?>()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> name = r.string()
                field == 2 && wire == 2 -> { val n = r.length(); features += r.at until r.at + n; r.at += n }
                field == 3 && wire == 2 -> keyNames += r.string()
                field == 4 && wire == 2 -> values += value(r.sub())
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        // the tile's key numbers of the keys asked, -1 for one it does not have
        val wanted = IntArray(keyNames.size) { -1 }
        for ((i, k) in keys.withIndex()) keyNames.indexOf(k).takeIf { it >= 0 }?.let { wanted[it] = i }
        val xs = IntBuilder()
        val ys = IntBuilder()
        val columns = keys.map { ArrayList<String?>() }
        val row = arrayOfNulls<String>(keys.size)
        for (range in features) {
            val f = Reader(r.bytes, range.first, range.last + 1)
            var type = 0
            var tags: IntArray? = null
            var geometry: IntArray? = null
            while (f.more()) {
                val (field, wire) = f.tag()
                when {
                    field == 2 -> tags = f.uints(wire, tags)
                    field == 3 && wire == 0 -> type = f.varint().toInt()
                    field == 4 -> geometry = f.uints(wire, geometry)
                    else -> f.skip(wire)
                }
            }
            if (type != POINT || geometry == null) continue
            row.fill(null)
            tags?.let { t ->
                var i = 0
                while (i + 1 < t.size) {
                    val k = t[i]
                    val v = t[i + 1]
                    if (k in wanted.indices && wanted[k] >= 0 && v in values.indices) row[wanted[k]] = values[v]
                    i += 2
                }
            }
            // MoveTo commands, each with its points as zigzag deltas from the last
            var cx = 0
            var cy = 0
            var i = 0
            while (i < geometry.size) {
                val command = geometry[i] and 7
                val count = geometry[i] ushr 3
                i++
                if (command != MOVE_TO) break
                repeat(count) {
                    if (i + 1 >= geometry.size) return@repeat
                    cx += zigzag(geometry[i])
                    cy += zigzag(geometry[i + 1])
                    i += 2
                    xs.add(cx)
                    ys.add(cy)
                    for (c in columns.indices) columns[c].add(row[c])
                }
            }
        }
        return Points(name, extent, xs.toArray(), ys.toArray(), columns.map { it.toTypedArray() })
    }

    /** A value as text: a string, or a number or boolean written out. */
    private fun value(r: Reader): String? {
        var out: String? = null
        while (r.more()) {
            val (field, wire) = r.tag()
            out = when {
                field == 1 && wire == 2 -> r.string()
                field == 2 && wire == 5 -> java.lang.Float.intBitsToFloat(r.fixed32()).toString()
                field == 3 && wire == 1 -> java.lang.Double.longBitsToDouble(r.fixed64()).toString()
                (field == 4 || field == 5) && wire == 0 -> r.varint().toString()
                field == 6 && wire == 0 -> r.varint().let { (it ushr 1) xor -(it and 1) }.toString()
                field == 7 && wire == 0 -> (r.varint() != 0L).toString()
                else -> { r.skip(wire); out }
            }
        }
        return out
    }

    private fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)

    private const val POINT = 1
    private const val MOVE_TO = 1

    /** A protocol buffer read in place, between [at] and [end] of [bytes]. */
    private class Reader(val bytes: ByteArray, var at: Int, val end: Int) {
        fun more() = at < end

        fun varint(): Long {
            var shift = 0
            var v = 0L
            while (true) {
                if (at >= end || shift > 63) throw VectorTileException("a field cut short")
                val b = bytes[at++].toInt() and 0xff
                v = v or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return v
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val t = varint()
            return (t ushr 3).toInt() to (t and 7).toInt()
        }

        fun length(): Int {
            val n = varint()
            if (n < 0 || n > end - at) throw VectorTileException("a field of $n bytes in ${end - at}")
            return n.toInt()
        }

        fun sub(): Reader {
            val n = length()
            return Reader(bytes, at, at + n).also { at += n }
        }

        fun string(): String {
            val n = length()
            return String(bytes, at, n, Charsets.UTF_8).also { at += n }
        }

        fun fixed32(): Int {
            if (end - at < 4) throw VectorTileException("a field cut short")
            var v = 0
            for (k in 0 until 4) v = v or ((bytes[at + k].toInt() and 0xff) shl (8 * k))
            at += 4
            return v
        }

        fun fixed64(): Long {
            if (end - at < 8) throw VectorTileException("a field cut short")
            var v = 0L
            for (k in 0 until 8) v = v or ((bytes[at + k].toLong() and 0xff) shl (8 * k))
            at += 8
            return v
        }

        /** A repeated unsigned field, packed (one length-delimited run) or one value at a time, added to [to]. */
        fun uints(wire: Int, to: IntArray?): IntArray {
            val got = IntBuilder()
            to?.forEach { got.add(it) }
            if (wire == 2) {
                // the length first: it moves past itself
                val n = length()
                val stop = at + n
                while (at < stop) got.add(varint().toInt())
            } else if (wire == 0) {
                got.add(varint().toInt())
            } else {
                skip(wire)
            }
            return got.toArray()
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { if (end - at < 8) throw VectorTileException("a field cut short"); at += 8 }
                2 -> { val n = length(); at += n }
                5 -> { if (end - at < 4) throw VectorTileException("a field cut short"); at += 4 }
                else -> throw VectorTileException("wire type $wire")
            }
        }
    }

    private class IntBuilder {
        private var a = IntArray(16)
        private var n = 0

        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }

        fun toArray(): IntArray = a.copyOf(n)
    }
}
