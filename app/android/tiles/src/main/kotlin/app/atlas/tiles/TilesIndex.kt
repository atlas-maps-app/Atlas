package app.atlas.tiles

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * The index of a country's tiles, Atlas's own pack of the map (ENGINE.md step 3, one pack per country): which L14
 * windows the country's `tiles` file holds, where each one's pack lies in that file, and its SHA-256. A window's pack is
 * the format-3 pack the core builds for the window from its data in the country's windows' data (`packer windows`,
 * [WindowsIndex]), as the phone would build it (`load::build_window`): the same tiles, with its chunks compressed at
 * zstd's level 19 where the phone writes them as stored blocks, and its style as what differs from the country's
 * ([CountryStyle]). `packer country` writes both files on GitHub; the phone downloads the index whole once (packs.json
 * gives its SHA-256) and a window's pack by an HTTP range, which it holds to the index's SHA-256, writes the window's
 * style back whole, and keeps the pack where it keeps the windows it builds.
 *
 * A window whose box holds nothing to draw (the open sea, a lake's middle) is listed with no bytes, so the phone asks no
 * one for it. Only a phone whose core builds windows of [version] takes the packs: another core's would be other tiles.
 * Every window's style is the country's but for its region record, so the index holds the country's style once
 * ([style]).
 *
 * Beside the windows, each frame cell's levels (ENGINE.md one pack per country, step 7): the L13 and L12 tiles the core
 * derives from the cell's windows (`atlas_stagep levels`, load::build_levels), each a pack of its own of one tile,
 * stored as a window's is, which the phone takes when its core asks for them ([find] by level).
 *
 * Little-endian: "ATTI", the format's version (u16), the windows' level (u8), 0 (u8), the count (u32), the windows'
 * data's date (i64, seconds since the epoch), the tiles file's length (u64), the windows' version's length (u16) and its
 * UTF-8 bytes (`load::window_version`: the core's stage P version and its style's), the country's style's length (u32)
 * and its bytes; then the tiles sorted by level, finest first, then x, then y, each its level (u8), x (u32), y (u32),
 * offset (u64), length (u32, 0 where it holds nothing to draw) and SHA-256 (32 bytes; zeros where it holds nothing).
 */
class TilesIndex private constructor(
    /** The windows' level: 14, the phone's windows' (load::window_level); the levels' tiles are coarser. */
    val level: Int,
    /** The date of the data the windows were built from: the extract's, its windows' data's. */
    val date: Instant,
    /** The tiles file's length in bytes, all its parts together. */
    val tilesBytes: Long,
    /** The core's version the packs were built with (MapCore.Window.version): its stage P's and its style's. */
    val version: String,
    /**
     * The country's style: a window's `style` section, raw, which every window's differs from in its region record
     * alone ([CountryStyle]); empty where no window holds anything to draw.
     */
    val style: ByteArray,
    private val zs: ByteArray,
    private val xs: IntArray,
    private val ys: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val hashes: ByteArray,
) {
    /**
     * One tile's pack, a window's (level [z] the index's [level]) or a level's: bytes [offset] to [offset] + [length] of
     * the tiles file, whose SHA-256 is [sha256]; none where [length] is 0.
     */
    class Entry(val x: Int, val y: Int, val offset: Long, val length: Int, val sha256: ByteArray, val z: Int = WINDOW_LEVEL) {
        /** Whether the tile holds anything to draw. */
        val drawn: Boolean get() = length > 0
    }

    /** How many tiles the country holds: its windows and its levels' tiles. */
    val size: Int get() = xs.size

    operator fun get(i: Int): Entry =
        Entry(xs[i], ys[i], offsets[i], lengths[i], hashes.copyOfRange(i * HASH_BYTES, (i + 1) * HASH_BYTES), zs[i].toInt() and 0xFF)

    /** Window ([x], [y])'s pack; null where the country holds none for it. */
    fun find(x: Int, y: Int): Entry? = find(level, x, y)

    /** Tile ([z], [x], [y])'s pack, a window's or a level's; null where the country holds none for it. */
    fun find(z: Int, x: Int, y: Int): Entry? {
        var lo = 0
        var hi = xs.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(zs[mid].toInt() and 0xFF, xs[mid], ys[mid], z, x, y)
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
        val b = ByteBuffer.allocate(HEAD_BYTES + v.size + STYLE_BYTES + style.size + size * ENTRY_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC.toByteArray(Charsets.US_ASCII)).putShort(VERSION.toShort()).put(level.toByte()).put(0)
        b.putInt(size).putLong(date.epochSecond).putLong(tilesBytes).putShort(v.size.toShort()).put(v)
        b.putInt(style.size).put(style)
        for (i in 0 until size) {
            b.put(zs[i]).putInt(xs[i]).putInt(ys[i]).putLong(offsets[i]).putInt(lengths[i]).put(hashes, i * HASH_BYTES, HASH_BYTES)
        }
        out.write(b.array())
    }

    companion object {
        const val MAGIC = "ATTI"

        /** The format's version this reader knows; an index of another is refused. 3: the levels' tiles beside the windows. */
        const val VERSION = 3

        /** The windows' level (load::window_level), and the levels' a frame cell derives from them (load::coarse_level, far_level). */
        const val WINDOW_LEVEL = 14
        val LEVELS = listOf(13, 12)

        /** The head's fixed bytes, before the version's text. */
        const val HEAD_BYTES = 30

        /** The style's length, after the version's text. */
        const val STYLE_BYTES = 4
        const val ENTRY_BYTES = 53
        private const val HASH_BYTES = 32

        /** The tiles' order: by level, finest first, then x, then y. */
        private fun compare(z0: Int, x0: Int, y0: Int, z1: Int, x1: Int, y1: Int): Int = when {
            z0 != z1 -> z1.compareTo(z0)
            x0 != x1 -> Integer.compareUnsigned(x0, x1)
            else -> Integer.compareUnsigned(y0, y1)
        }

        /**
         * An index of [entries] (any order; each tile once) in a tiles file of [tilesBytes], built by core [version],
         * whose tiles' styles differ from the country's [style].
         */
        fun of(level: Int, date: Instant, tilesBytes: Long, version: String, style: ByteArray, entries: List<Entry>): TilesIndex {
            val sorted = entries.sortedWith { a, b -> compare(a.z, a.x, a.y, b.z, b.x, b.y) }
            val hashes = ByteArray(sorted.size * HASH_BYTES)
            sorted.forEachIndexed { i, e ->
                require(e.sha256.size == HASH_BYTES) { "tile ${e.z}/${e.x}/${e.y}: a SHA-256 of ${e.sha256.size} bytes" }
                e.sha256.copyInto(hashes, i * HASH_BYTES)
            }
            return checked(
                TilesIndex(
                    level, date, tilesBytes, version, style.copyOf(), ByteArray(sorted.size) { sorted[it].z.toByte() },
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
            require(bytes.size.toLong() >= HEAD_BYTES + textLength + STYLE_BYTES) { "a tiles index cut short" }
            val text = String(bytes, HEAD_BYTES, textLength, Charsets.UTF_8)
            b.position(HEAD_BYTES + textLength)
            val styleLength = b.int.toLong() and 0xFFFFFFFFL
            require(count >= 0 && bytes.size.toLong() == HEAD_BYTES + textLength + STYLE_BYTES + styleLength + count.toLong() * ENTRY_BYTES) {
                "a tiles index of ${bytes.size} bytes for a style of $styleLength and $count tiles"
            }
            val style = ByteArray(styleLength.toInt())
            b.get(style)
            val zs = ByteArray(count)
            val xs = IntArray(count)
            val ys = IntArray(count)
            val offsets = LongArray(count)
            val lengths = IntArray(count)
            val hashes = ByteArray(count * HASH_BYTES)
            for (i in 0 until count) {
                zs[i] = b.get()
                xs[i] = b.int
                ys[i] = b.int
                offsets[i] = b.long
                lengths[i] = b.int
                b.get(hashes, i * HASH_BYTES, HASH_BYTES)
            }
            return checked(TilesIndex(level, date, tilesBytes, text, style, zs, xs, ys, offsets, lengths, hashes))
        }

        /**
         * [index], once its tiles are in order, each once, each a window's or a coarser level's, and each one's pack lies
         * within the tiles file.
         */
        private fun checked(index: TilesIndex): TilesIndex = index.apply {
            require(level in 0..30) { "windows of level $level" }
            require(tilesBytes >= 0) { "a tiles file of $tilesBytes bytes" }
            require(version.isNotEmpty()) { "a tiles index without the core's version" }
            require(style.isNotEmpty() || lengths.all { it == 0 }) { "a tiles index without the country's style" }
            for (i in 0 until size) {
                val z = zs[i].toInt() and 0xFF
                require(z <= level) { "tile $z/${xs[i]}/${ys[i]} finer than the windows' level $level" }
                require(i == 0 || compare(zs[i - 1].toInt() and 0xFF, xs[i - 1], ys[i - 1], z, xs[i], ys[i]) < 0) {
                    "tile $z/${xs[i]}/${ys[i]} out of order or twice"
                }
                val bound = 1L shl z
                require(Integer.toUnsignedLong(xs[i]) < bound && Integer.toUnsignedLong(ys[i]) < bound) {
                    "tile $z/${xs[i]}/${ys[i]} beyond its level"
                }
                require(lengths[i] >= 0 && offsets[i] >= 0 && offsets[i] + lengths[i] <= tilesBytes) {
                    "tile $z/${xs[i]}/${ys[i]}: bytes ${offsets[i]} to ${offsets[i] + lengths[i]} of a file of $tilesBytes"
                }
            }
        }
    }
}
