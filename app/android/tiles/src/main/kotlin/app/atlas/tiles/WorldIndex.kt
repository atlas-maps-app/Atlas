package app.atlas.tiles

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * The world's index (ENGINE.md step 5, the world's mid-zoom base: `world.idx`): every world pack, the block of tiles it
 * holds and where its bytes lie. A fine pack holds an L6 tile's L9 to L11 tiles, a coarse pack an L3 tile's L6 to L8;
 * they lie end to end in parts (`world-mid.000`, `world-coarse.000`, …, each under 1.9 GB), and a pack's bytes are
 * [Entry.length] bytes from [Entry.offset] of its part, whose SHA-256 is [Entry.sha256]. A pack was built for the tiles
 * of its block inside its [Entry.box]: there, a tile it does not hold is open sea; beyond it, not built yet.
 *
 * Little-endian: "ATWX", the format's version (u16), 0 (u16), the data's date (i64, seconds since the epoch), the parts'
 * count (u16), then each part's name (u8 length, its bytes) and length (u64); the packs' count (u32), then the packs
 * sorted by block level, x and y: the block's level (u8), its first and last tile levels (u8 each), its part (u8), x
 * (u32), y (u32), offset (u64), length (u64), tiles (u32), the box built (south, west, north, east on the 1e-7° grid,
 * i32 each) and the SHA-256 (32 bytes).
 */
class WorldIndex(val date: Instant, val parts: List<Part>, entries: List<Entry>) {
    /** A part: its asset's name and its length in bytes. */
    class Part(val name: String, val bytes: Long)

    /** One pack: its block, its levels, its part, its range in the part, its tiles, the box it was built for and its SHA-256. */
    class Entry(
        val z: Int,
        val x: Int,
        val y: Int,
        val firstLevel: Int,
        val lastLevel: Int,
        val part: Int,
        val offset: Long,
        val length: Long,
        val tiles: Int,
        val box: IntArray,
        val sha256: ByteArray,
    ) {
        init {
            require(box.size == 4 && sha256.size == HASH_BYTES)
        }
    }

    val entries: List<Entry> = entries.sortedWith(compareBy({ it.z }, { it.x }, { it.y }))

    init {
        require(parts.size in 0..255)
        for (e in this.entries) require(e.part in parts.indices) { "pack ${e.z}/${e.x}/${e.y} lies in part ${e.part} of ${parts.size}" }
        for (i in 1 until this.entries.size) {
            val a = this.entries[i - 1]
            val b = this.entries[i]
            require(a.z != b.z || a.x != b.x || a.y != b.y) { "pack ${a.z}/${a.x}/${a.y} twice" }
        }
    }

    /** The pack of block ([z], [x], [y]); null where the index has none. */
    fun find(z: Int, x: Int, y: Int): Entry? = entries.firstOrNull { it.z == z && it.x == x && it.y == y }

    /** The pack holding tile ([level], [x], [y]): the fine pack of its L6 tile, or the coarse pack of its L3 tile. */
    fun holding(level: Int, x: Int, y: Int): Entry? {
        val block = when (level) {
            in FINE_LEVELS -> FINE_BLOCK
            in COARSE_LEVELS -> COARSE_BLOCK
            else -> return null
        }
        val shift = level - block
        return find(block, x shr shift, y shr shift)
    }

    fun write(out: OutputStream) {
        val names = parts.map { it.name.toByteArray(Charsets.UTF_8) }
        val size = 4 + 2 + 2 + 8 + 2 + names.sumOf { 1 + it.size + 8 } + 4 + entries.size * ENTRY_BYTES
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIC.toByteArray(Charsets.US_ASCII)).putShort(VERSION.toShort()).putShort(0).putLong(date.epochSecond)
        b.putShort(parts.size.toShort())
        for ((i, p) in parts.withIndex()) {
            require(names[i].size in 1..255)
            b.put(names[i].size.toByte()).put(names[i]).putLong(p.bytes)
        }
        b.putInt(entries.size)
        for (e in entries) {
            b.put(e.z.toByte()).put(e.firstLevel.toByte()).put(e.lastLevel.toByte()).put(e.part.toByte())
            b.putInt(e.x).putInt(e.y).putLong(e.offset).putLong(e.length).putInt(e.tiles)
            for (v in e.box) b.putInt(v)
            b.put(e.sha256)
        }
        out.write(b.array())
    }

    companion object {
        const val MAGIC = "ATWX"

        /** The format's version this reader knows; an index of a later one is refused. */
        const val VERSION = 1

        const val HASH_BYTES = 32

        /** A pack's entry: 4 + 4 + 4 + 8 + 8 + 4 + 16 + 32 bytes. */
        const val ENTRY_BYTES = 80

        /** A fine pack's block level and its tiles' levels; a coarse pack's. */
        const val FINE_BLOCK = 6
        val FINE_LEVELS = 9..11
        const val COARSE_BLOCK = 3
        val COARSE_LEVELS = 6..8

        fun read(bytes: ByteArray): WorldIndex {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun need(n: Int) = require(b.remaining() >= n) { "a world index cut short" }
            need(18)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "not a world index" }
            val version = b.short.toInt() and 0xFFFF
            require(version == VERSION) { "a world index of version $version, which this reader does not know" }
            b.short
            val date = Instant.ofEpochSecond(b.long)
            val partCount = b.short.toInt() and 0xFFFF
            val parts = List(partCount) {
                need(1)
                val n = b.get().toInt() and 0xFF
                need(n + 8)
                val name = ByteArray(n).also { b.get(it) }
                Part(String(name, Charsets.UTF_8), b.long)
            }
            need(4)
            val count = b.int
            require(count >= 0 && b.remaining() == count.toLong().times(ENTRY_BYTES).toInt()) { "a world index of $count packs in ${b.remaining()} bytes" }
            val entries = List(count) {
                val z = b.get().toInt() and 0xFF
                val first = b.get().toInt() and 0xFF
                val last = b.get().toInt() and 0xFF
                val part = b.get().toInt() and 0xFF
                val x = b.int
                val y = b.int
                val offset = b.long
                val length = b.long
                val tiles = b.int
                val box = IntArray(4) { b.int }
                val sha = ByteArray(HASH_BYTES).also { b.get(it) }
                Entry(z, x, y, first, last, part, offset, length, tiles, box, sha)
            }
            return WorldIndex(date, parts, entries)
        }
    }
}
