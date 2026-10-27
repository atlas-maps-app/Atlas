package app.atlas.tiles

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * The index of a region's windows' data, Atlas's own packs (ENGINE.md step 3: the phone takes a window's data from
 * them before it asks Overpass): which L14 windows the region's `windows` file holds, where each one's data lies in
 * that file, and its SHA-256. A window's data is what Overpass answers the phone's query of the window's box ([QUERY]:
 * every tagged node, every way, the multipolygons and parks, `out geom`, each relation's members whole) as of the
 * extract's [date], gzipped: the file the phone keeps beside a window it built. `packer windows` writes both files from
 * a Geofabrik extract on GitHub; the phone downloads the index whole (packs.json gives its SHA-256) and a window's data
 * by an HTTP range, which it holds to the index's SHA-256.
 *
 * Little-endian: "ATWI", the format's version (u16), the level (u8), 0 (u8), the count (u32), the query's version (u16),
 * 0 (u16), the date (i64, seconds since the epoch), the windows file's length (u64); then the windows sorted by x, then
 * y, each x (u32), y (u32), offset (u64), length (u32) and SHA-256 (32 bytes).
 */
class WindowsIndex private constructor(
    /** The windows' level: 14, the phone's windows' (load::window_level). */
    val level: Int,
    /** The window query the data answers: [QUERY] when it is the phone's. */
    val query: Int,
    /** The data's date: the extract's, each window's `timestamp_osm_base`. */
    val date: Instant,
    /** The windows file's length in bytes, all its parts together. */
    val dataBytes: Long,
    private val xs: IntArray,
    private val ys: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val hashes: ByteArray,
) {
    /** One window's data: bytes [offset] to [offset] + [length] of the windows file, whose SHA-256 is [sha256]. */
    class Entry(val x: Int, val y: Int, val offset: Long, val length: Int, val sha256: ByteArray)

    /** How many windows the region holds. */
    val size: Int get() = xs.size

    operator fun get(i: Int): Entry =
        Entry(xs[i], ys[i], offsets[i], lengths[i], hashes.copyOfRange(i * HASH_BYTES, (i + 1) * HASH_BYTES))

    /** Window ([x], [y])'s data; null where the region holds none for it. */
    fun find(x: Int, y: Int): Entry? {
        var lo = 0
        var hi = xs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(xs[mid], ys[mid], x, y)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return get(mid)
            }
        }
        return null
    }

    /** The index as [read] reads it. */
    fun write(out: OutputStream) {
        val b = ByteBuffer.allocate(HEAD_BYTES + size * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC.toByteArray(Charsets.US_ASCII)).putShort(VERSION.toShort()).put(level.toByte()).put(0)
        b.putInt(size).putShort(query.toShort()).putShort(0).putLong(date.epochSecond).putLong(dataBytes)
        for (i in 0 until size) {
            b.putInt(xs[i]).putInt(ys[i]).putLong(offsets[i]).putInt(lengths[i]).put(hashes, i * HASH_BYTES, HASH_BYTES)
        }
        out.write(b.array())
    }

    companion object {
        const val MAGIC = "ATWI"

        /** The format's version this reader knows; an index of a later one is refused. */
        const val VERSION = 1

        /**
         * The window query whose answers the phone's windows are built from: `OverpassClient.windowQuery` of
         * `load::window_box` (the window's L14 tile and a margin of 200 m). Raised with any change to either, so the
         * phone never takes data a pack made for another box or query.
         */
        const val QUERY = 1

        const val HEAD_BYTES = 32
        const val ENTRY_BYTES = 52
        private const val HASH_BYTES = 32

        private fun compare(x0: Int, y0: Int, x1: Int, y1: Int): Int =
            if (x0 != x1) Integer.compareUnsigned(x0, x1) else Integer.compareUnsigned(y0, y1)

        /** An index of [entries] (any order; each window once) in a windows file of [dataBytes]. */
        fun of(level: Int, query: Int, date: Instant, dataBytes: Long, entries: List<Entry>): WindowsIndex {
            val sorted = entries.sortedWith { a, b -> compare(a.x, a.y, b.x, b.y) }
            val hashes = ByteArray(sorted.size * HASH_BYTES)
            sorted.forEachIndexed { i, e ->
                require(e.sha256.size == HASH_BYTES) { "window ${e.x}/${e.y}: a SHA-256 of ${e.sha256.size} bytes" }
                e.sha256.copyInto(hashes, i * HASH_BYTES)
            }
            return checked(
                WindowsIndex(
                    level, query, date, dataBytes,
                    IntArray(sorted.size) { sorted[it].x }, IntArray(sorted.size) { sorted[it].y },
                    LongArray(sorted.size) { sorted[it].offset }, IntArray(sorted.size) { sorted[it].length }, hashes,
                ),
            )
        }

        /** The index in [bytes]; throws IllegalArgumentException where they are not one this reader knows. */
        fun read(bytes: ByteArray): WindowsIndex {
            require(bytes.size >= HEAD_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "not a windows index" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(4)
            val version = b.short.toInt() and 0xFFFF
            require(version == VERSION) { "a windows index of version $version, this reader knows $VERSION" }
            val level = b.get().toInt() and 0xFF
            b.get()
            val count = b.int
            val query = b.short.toInt() and 0xFFFF
            b.short
            val date = Instant.ofEpochSecond(b.long)
            val dataBytes = b.long
            require(count >= 0 && bytes.size.toLong() == HEAD_BYTES + count.toLong() * ENTRY_BYTES) {
                "a windows index of ${bytes.size} bytes for $count windows"
            }
            val xs = IntArray(count)
            val ys = IntArray(count)
            val offsets = LongArray(count)
            val lengths = IntArray(count)
            val hashes = ByteArray(count * HASH_BYTES)
            for (i in 0 until count) {
                xs[i] = b.int
                ys[i] = b.int
                offsets[i] = b.long
                lengths[i] = b.int
                b.get(hashes, i * HASH_BYTES, HASH_BYTES)
            }
            return checked(WindowsIndex(level, query, date, dataBytes, xs, ys, offsets, lengths, hashes))
        }

        /** [index], once its windows are in order, each once, and each one's data lies within the windows file. */
        private fun checked(index: WindowsIndex): WindowsIndex = index.apply {
            require(level in 0..30) { "windows of level $level" }
            require(dataBytes >= 0) { "a windows file of $dataBytes bytes" }
            for (i in 0 until size) {
                require(i == 0 || compare(xs[i - 1], ys[i - 1], xs[i], ys[i]) < 0) { "window ${xs[i]}/${ys[i]} out of order or twice" }
                val bound = 1L shl level
                require(Integer.toUnsignedLong(xs[i]) < bound && Integer.toUnsignedLong(ys[i]) < bound) {
                    "window ${xs[i]}/${ys[i]} beyond level $level"
                }
                require(lengths[i] > 0 && offsets[i] >= 0 && offsets[i] + lengths[i] <= dataBytes) {
                    "window ${xs[i]}/${ys[i]}: bytes ${offsets[i]} to ${offsets[i] + lengths[i]} of a file of $dataBytes"
                }
            }
        }
    }
}
