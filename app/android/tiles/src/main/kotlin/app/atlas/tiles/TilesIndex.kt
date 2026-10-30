package app.atlas.tiles

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * The index of a country's tiles, Atlas's own pack of the map (ENGINE.md step 3, one pack per country): which L14
 * windows the country's `tiles` file holds, where each one's pack lies in that file, and its SHA-256. A window's pack is
 * the format-3 pack the core builds for the window from its data in the country's windows' data (`packer windows`,
 * [WindowsIndex]), as the phone would build it (`load::build_window`): the same tiles, with its chunks and style
 * compressed at zstd's level 19 where the phone writes them as stored blocks. `packer country` writes both files on
 * GitHub; the phone downloads the index whole once (packs.json gives its SHA-256) and a window's pack by an HTTP range,
 * which it holds to the index's SHA-256, and keeps it where it keeps the windows it builds.
 *
 * A window whose box holds nothing to draw (the open sea, a lake's middle) is listed with no bytes, so the phone asks no
 * one for it. Only a phone whose core builds windows of [version] takes the packs: another core's would be other tiles.
 *
 * Little-endian: "ATTI", the format's version (u16), the level (u8), 0 (u8), the count (u32), the windows' data's date
 * (i64, seconds since the epoch), the tiles file's length (u64), the windows' version's length (u16) and its UTF-8
 * bytes (`load::window_version`: the core's stage P version and its style's); then the windows sorted by x, then y,
 * each x (u32), y (u32), offset (u64), length (u32, 0 where it holds nothing to draw) and SHA-256 (32 bytes; zeros
 * where it holds nothing).
 */
class TilesIndex private constructor(
    /** The windows' level: 14, the phone's windows' (load::window_level). */
    val level: Int,
    /** The date of the data the windows were built from: the extract's, its windows' data's. */
    val date: Instant,
    /** The tiles file's length in bytes, all its parts together. */
    val tilesBytes: Long,
    /** The core's version the packs were built with (MapCore.Window.version): its stage P's and its style's. */
    val version: String,
    private val xs: IntArray,
    private val ys: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val hashes: ByteArray,
) {
    /** One window's pack: bytes [offset] to [offset] + [length] of the tiles file, whose SHA-256 is [sha256]; none where [length] is 0. */
    class Entry(val x: Int, val y: Int, val offset: Long, val length: Int, val sha256: ByteArray) {
        /** Whether the window holds anything to draw. */
        val drawn: Boolean get() = length > 0
    }

    /** How many windows the country holds. */
    val size: Int get() = xs.size

    operator fun get(i: Int): Entry =
        Entry(xs[i], ys[i], offsets[i], lengths[i], hashes.copyOfRange(i * HASH_BYTES, (i + 1) * HASH_BYTES))

    /** Window ([x], [y])'s pack; null where the country holds none for it. */
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
        val v = version.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(HEAD_BYTES + v.size + size * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC.toByteArray(Charsets.US_ASCII)).putShort(VERSION.toShort()).put(level.toByte()).put(0)
        b.putInt(size).putLong(date.epochSecond).putLong(tilesBytes).putShort(v.size.toShort()).put(v)
        for (i in 0 until size) {
            b.putInt(xs[i]).putInt(ys[i]).putLong(offsets[i]).putInt(lengths[i]).put(hashes, i * HASH_BYTES, HASH_BYTES)
        }
        out.write(b.array())
    }

    companion object {
        const val MAGIC = "ATTI"

        /** The format's version this reader knows; an index of another is refused. */
        const val VERSION = 1

        /** The head's fixed bytes, before the version's text. */
        const val HEAD_BYTES = 30
        const val ENTRY_BYTES = 52
        private const val HASH_BYTES = 32

        private fun compare(x0: Int, y0: Int, x1: Int, y1: Int): Int =
            if (x0 != x1) Integer.compareUnsigned(x0, x1) else Integer.compareUnsigned(y0, y1)

        /** An index of [entries] (any order; each window once) in a tiles file of [tilesBytes], built by core [version]. */
        fun of(level: Int, date: Instant, tilesBytes: Long, version: String, entries: List<Entry>): TilesIndex {
            val sorted = entries.sortedWith { a, b -> compare(a.x, a.y, b.x, b.y) }
            val hashes = ByteArray(sorted.size * HASH_BYTES)
            sorted.forEachIndexed { i, e ->
                require(e.sha256.size == HASH_BYTES) { "window ${e.x}/${e.y}: a SHA-256 of ${e.sha256.size} bytes" }
                e.sha256.copyInto(hashes, i * HASH_BYTES)
            }
            return checked(
                TilesIndex(
                    level, date, tilesBytes, version,
                    IntArray(sorted.size) { sorted[it].x }, IntArray(sorted.size) { sorted[it].y },
                    LongArray(sorted.size) { sorted[it].offset }, IntArray(sorted.size) { sorted[it].length }, hashes,
                ),
            )
        }

        /** The index in [bytes]; throws IllegalArgumentException where they are not one this reader knows. */
        fun read(bytes: ByteArray): TilesIndex {
            require(bytes.size >= HEAD_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "not a tiles index" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            b.position(4)
            val version = b.short.toInt() and 0xFFFF
            require(version == VERSION) { "a tiles index of version $version, this reader knows $VERSION" }
            val level = b.get().toInt() and 0xFF
            b.get()
            val count = b.int
            val date = Instant.ofEpochSecond(b.long)
            val tilesBytes = b.long
            val textLength = b.short.toInt() and 0xFFFF
            require(count >= 0 && bytes.size.toLong() == HEAD_BYTES + textLength + count.toLong() * ENTRY_BYTES) {
                "a tiles index of ${bytes.size} bytes for $count windows"
            }
            val text = String(bytes, HEAD_BYTES, textLength, Charsets.UTF_8)
            b.position(HEAD_BYTES + textLength)
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
            return checked(TilesIndex(level, date, tilesBytes, text, xs, ys, offsets, lengths, hashes))
        }

        /** [index], once its windows are in order, each once, and each one's pack lies within the tiles file. */
        private fun checked(index: TilesIndex): TilesIndex = index.apply {
            require(level in 0..30) { "windows of level $level" }
            require(tilesBytes >= 0) { "a tiles file of $tilesBytes bytes" }
            require(version.isNotEmpty()) { "a tiles index without the core's version" }
            for (i in 0 until size) {
                require(i == 0 || compare(xs[i - 1], ys[i - 1], xs[i], ys[i]) < 0) { "window ${xs[i]}/${ys[i]} out of order or twice" }
                val bound = 1L shl level
                require(Integer.toUnsignedLong(xs[i]) < bound && Integer.toUnsignedLong(ys[i]) < bound) {
                    "window ${xs[i]}/${ys[i]} beyond level $level"
                }
                require(lengths[i] >= 0 && offsets[i] >= 0 && offsets[i] + lengths[i] <= tilesBytes) {
                    "window ${xs[i]}/${ys[i]}: bytes ${offsets[i]} to ${offsets[i] + lengths[i]} of a file of $tilesBytes"
                }
            }
        }
    }
}
