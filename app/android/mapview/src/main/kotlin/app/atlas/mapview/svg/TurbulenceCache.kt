package app.atlas.mapview.svg

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * What a tile of turbulence depends on: the `feTurbulence` primitive's parameters, the filter scale (as the float the
 * noise grid is computed from: [Turbulence.fillWebKit] samples at (x + 1) / [scaleX]) and the channels its consumers
 * read (bit 0 = red … bit 3 = alpha; the others are 0, alpha 255). Nothing else: the engine's noise is anchored at the
 * user-space origin, so the same field covers every camera and every element drawn through the same filter.
 *
 * [steps] names pointwise primitives applied to the noise (a colour matrix, say), so that what a filter computes from
 * noise alone is kept too; empty for the noise itself. It is the name of a [NoiseMap] and must say everything the map
 * depends on.
 */
data class NoiseField(
    val seed: Float,
    val baseX: Float,
    val baseY: Float,
    val octaves: Int,
    val fractal: Boolean,
    val scaleX: Float,
    val scaleY: Float,
    val channels: Int,
    val steps: String = "",
) {
    init {
        require(scaleX > 0f && scaleY > 0f) { "a filter scale must be positive" }
        require(channels in 1..15) { "channels is a mask of 1 … 15" }
    }

    /** The noise the steps start from. */
    val noise: NoiseField get() = if (steps.isEmpty()) this else copy(steps = "")
}

/** A pointwise map of packed pixels: what [NoiseField.steps] names, applied to each pixel of the noise. */
fun interface NoiseMap {
    fun map(p: Int): Int
}

/**
 * WebKit's turbulence ([Turbulence.colorWebKit]) kept in 64 × 64 tiles at absolute filter pixels, so a field is computed
 * once and every later frame, at any camera, copies it. A tile is a pure function of its [NoiseField] and its position,
 * so a kept tile is bit-identical to a fresh one, read back from disk included. Fields with [NoiseField.steps] keep the
 * noise after pointwise primitives (a filter's colour matrix on its noise) the same way.
 *
 * With a [dir], each field lives in a memory-mapped file there (`<hash>.noise`): outside the Java heap (on Android a
 * direct buffer is heap memory; a mapping is not), kept across launches, each tile with a CRC checked on its first read in
 * a process (a damaged tile is made again). Without one, tiles are heap arrays. [maxBytes] bounds what is held mapped or
 * in memory (the least recently used fields are let go); [maxDiskBytes] bounds the files kept in [dir].
 *
 * Thread-safe: bands of a filter fill from it at once, and a tile two threads need is made once.
 */
class TurbulenceCache(
    val dir: File?,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
) {
    /** What the cache has done since it was made. */
    data class Stats(
        /** Tiles copied out of the cache (each time a tile served a fill). */
        val tilesRead: Long,
        /** Tiles computed. */
        val tilesMade: Long,
        /** Tiles found in files kept from earlier, and checked. */
        val tilesFromDisk: Long,
        /** Kept tiles whose check failed, made again. */
        val tilesRepaired: Long,
        /** Fields held now, and the bytes they hold mapped or in memory. */
        val fields: Int,
        val bytes: Long,
    )

    private val lock = Any()

    /** Open fields. */
    private val fields = HashMap<NoiseField, Field>()

    /** Counts fills: a field remembers the count at its last fill from outside, and the lowest goes first. */
    private var clock = 0L
    private val held = AtomicLong()
    private val read = AtomicLong()
    private val made = AtomicLong()
    private val fromDisk = AtomicLong()
    private val repaired = AtomicLong()

    fun stats(): Stats = synchronized(lock) { Stats(read.get(), made.get(), fromDisk.get(), repaired.get(), fields.size, held.get()) }

    /**
     * Copies [field] over the filter pixels [width] × [height] from ([left], [top]) into [out], row r from [offset] +
     * r · [stride]: exactly what [Turbulence.fillWebKit] computes there, mapped by [map] when the field has steps (the map
     * they name). Missing tiles are made, on the filter workers when several are missing and this is not one of them.
     */
    fun fill(
        field: NoiseField,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        out: IntArray,
        offset: Int = 0,
        stride: Int = width,
        map: NoiseMap? = null,
    ) = fill(field, left, top, width, height, out, offset, stride, map, direct = true)

    /**
     * [fill], [direct] when a filter asks (not when a field with steps makes a tile from its noise): only direct fills
     * count as use, so a noise field kept only to make another one from is let go before the fields frames read.
     */
    private fun fill(
        field: NoiseField,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        out: IntArray,
        offset: Int,
        stride: Int,
        map: NoiseMap?,
        direct: Boolean,
    ) {
        if (width <= 0 || height <= 0) return
        require(stride >= width && offset >= 0 && offset + (height - 1).toLong() * stride + width <= out.size) { "out is too small" }
        require(field.steps.isEmpty() == (map == null)) { "a field has a map exactly when it has steps" }
        val f = acquire(field, direct)
        try {
            val tiles = f.ready(left, top, width, height, map)
            val tx0 = Math.floorDiv(left, T)
            val ty0 = Math.floorDiv(top, T)
            val nx = Math.floorDiv(left + width - 1, T) - tx0 + 1
            for ((k, tile) in tiles.withIndex()) {
                val tx = tx0 + k % nx
                val ty = ty0 + k / nx
                val x0 = maxOf(left, tx * T)
                val x1 = minOf(left + width, tx * T + T)
                val y0 = maxOf(top, ty * T)
                val y1 = minOf(top + height, ty * T + T)
                tile!!.copy(x0 - tx * T, y0 - ty * T, x1 - x0, y1 - y0, out, offset + (y0 - top) * stride + (x0 - left), stride)
            }
            read.addAndGet(tiles.size.toLong())
        } finally {
            release(f)
        }
        trim()
    }

    /**
     * Makes sure [field]'s tiles over the given filter pixels are made and checked, without copying them anywhere. A
     * field with steps needs its [map] to make tiles; without one, only the tiles already kept are checked.
     */
    fun ensure(field: NoiseField, left: Int, top: Int, width: Int, height: Int, map: NoiseMap? = null) {
        if (width <= 0 || height <= 0) return
        val f = acquire(field)
        try {
            f.ready(left, top, width, height, map, make = field.steps.isEmpty() || map != null)
        } finally {
            release(f)
        }
        trim()
    }

    /** The fields kept in [dir], read from the files' headers, most recently used first (none without a directory). */
    fun kept(): List<NoiseField> {
        val d = dir ?: return emptyList()
        val files = d.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { f ->
            runCatching { RandomAccessFile(f, "r").use { raf -> header(raf.channel)?.first } }.getOrNull()
        }
    }

    /**
     * Readies the cache for frames of [width] × [height] filter pixels at [scaleX] × [scaleY]: every kept field at that
     * scale whose kept tiles cover at least a quarter of the frame (the screen-wide fields: paper grain, material grain,
     * cloud shadows, water) is opened, and those tiles are read and checked, so the first frame finds them ready. Nothing
     * is made: what the last frames used is what is kept. Runs on the calling thread, which should be a background one;
     * returns the fields readied.
     */
    fun warmUp(width: Int, height: Int, scaleX: Float, scaleY: Float): List<NoiseField> {
        if (width <= 0 || height <= 0) return emptyList()
        val readied = ArrayList<NoiseField>()
        val all = (Math.floorDiv(width - 1, T) + 1).toLong() * (Math.floorDiv(height - 1, T) + 1)
        for (key in kept()) {
            if (key.scaleX != scaleX || key.scaleY != scaleY) continue
            val f = acquire(key)
            try {
                if (f.count(0, 0, width, height) * 4 < all) continue
                f.ready(0, 0, width, height, null, make = false)
                readied.add(key)
            } finally {
                release(f)
            }
            trim()
        }
        return readied
    }

    /** Lets go of every field (files stay on disk). Only when nothing is filling from the cache. */
    fun close() = synchronized(lock) {
        for (f in fields.values) f.close()
        fields.clear()
        held.set(0)
    }

    /** Lets go of every field and deletes the kept files. Only when nothing is filling from the cache. */
    fun clear() {
        close()
        dir?.listFiles { f -> f.name.endsWith(SUFFIX) }?.forEach { it.delete() }
    }

    private fun acquire(key: NoiseField, direct: Boolean = true): Field = synchronized(lock) {
        val f = fields.getOrPut(key) { open(key) }
        f.users++
        if (direct) f.used = ++clock
        f
    }

    private fun release(f: Field) = synchronized(lock) {
        f.users--
    }

    private fun open(key: NoiseField): Field {
        val f = Field(key)
        val d = dir
        if (d != null) {
            try {
                f.openFile(File(d, name(key)))
            } catch (e: IOException) {
                // an unwritable or full disk: this field lives on the heap instead
                f.close()
            }
            trimDisk()
        }
        return f
    }

    /** Lets go of the least recently used fields (by fills from outside) while more than [maxBytes] are held. */
    private fun trim() = synchronized(lock) {
        while (held.get() > maxBytes) {
            val f = fields.values.filter { it.users == 0 }.minByOrNull { it.used } ?: return
            fields.remove(f.key)
            f.close()
        }
    }

    /** Deletes the least recently used files while [dir] holds more than [maxDiskBytes]; open fields' files stay. */
    private fun trimDisk() {
        val d = dir ?: return
        val files = d.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        val open = fields.keys.map { name(it) }.toSet()
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxDiskBytes) break
            if (f.name in open) continue
            val n = f.length()
            if (f.delete()) total -= n
        }
    }

    /** One tile: a view into a mapped file, or a heap array. */
    private class Tile(val slot: Int, val mapped: IntBuffer?, val heap: IntArray?) {
        @Volatile
        var checked = false

        fun copy(x: Int, y: Int, w: Int, h: Int, out: IntArray, at: Int, stride: Int) {
            if (heap != null) {
                for (r in 0 until h) System.arraycopy(heap, (y + r) * T + x, out, at + r * stride, w)
                return
            }
            val v = mapped!!.duplicate()
            for (r in 0 until h) {
                v.position((y + r) * T + x)
                v.get(out, at + r * stride, w)
            }
        }
    }

    /** The tiles of one field, and its file. */
    private inner class Field(val key: NoiseField) {
        private val noise = Turbulence.of(key.seed)
        private val invX = 1f / key.scaleX
        private val invY = 1f / key.scaleY
        private val tiles = ConcurrentHashMap<Long, Tile>()
        private val pending = ConcurrentHashMap<Long, FutureTask<Tile>>()
        var users = 0

        /** The fill count at this field's last fill from outside (0: never, only made from). */
        var used = 0L
        private var channel: FileChannel? = null
        private var data = 0L

        /** The mapped chunks, replaced whole when one is added, so readers on other threads never see a half-grown list. */
        @Volatile
        private var chunks = arrayOfNulls<MappedByteBuffer>(0)
        private val free = ArrayDeque<Int>()
        private var slots = 0
        private var bytes = 0L

        /** Opens (or starts) the field's file: maps its chunks and indexes the tiles in them. */
        fun openFile(f: File) {
            f.parentFile?.mkdirs()
            val ch = RandomAccessFile(f, "rw").channel
            channel = ch
            val found = header(ch)
            if (found == null || found.first != key) {
                ch.truncate(0)
                val h = headerBytes(key)
                while (h.hasRemaining()) ch.write(h, h.position().toLong())
            }
            data = dataStart(key)
            val whole = ((ch.size() - data).coerceAtLeast(0) / CHUNK_BYTES).toInt()
            if (ch.size() != data + whole.toLong() * CHUNK_BYTES) ch.truncate(data + whole.toLong() * CHUNK_BYTES)
            repeat(whole) { mapChunk() }
            for (s in 0 until slots) {
                val c = chunks[s / SLOTS]!!
                val base = (s % SLOTS) * SLOT_BYTES
                if (c.getInt(base + 12) != VALID) {
                    free.addLast(s)
                    continue
                }
                val k = tileKey(c.getInt(base), c.getInt(base + 4))
                if (tiles.containsKey(k)) {
                    c.putInt(base + 12, 0)
                    free.addLast(s)
                } else {
                    tiles[k] = Tile(s, pixels(s), null)
                }
            }
            f.setLastModified(System.currentTimeMillis())
        }

        private fun mapChunk() {
            val ch = channel ?: throw IOException("no file")
            val n = chunks.size
            val at = data + n.toLong() * CHUNK_BYTES
            // A mapping past the end of the file would make it sparse, and writing a page the disk has no room for kills
            // the process (SIGBUS); zeros written first take the room now, or fail here with an IOException.
            var p = at
            while (p < at + CHUNK_BYTES && p >= ch.size()) {
                val z = ZEROS.duplicate()
                z.limit(minOf(z.capacity().toLong(), at + CHUNK_BYTES - p).toInt())
                while (z.hasRemaining()) p += ch.write(z, p)
            }
            val m = ch.map(FileChannel.MapMode.READ_WRITE, at, CHUNK_BYTES.toLong())
            m.order(ByteOrder.LITTLE_ENDIAN)
            chunks = chunks.copyOf(n + 1).also { it[n] = m }
            slots += SLOTS
            bytes += CHUNK_BYTES
            held.addAndGet(CHUNK_BYTES.toLong())
        }

        private fun slice(slot: Int): ByteBuffer {
            val b = chunks[slot / SLOTS]!!.duplicate()
            val base = (slot % SLOTS) * SLOT_BYTES + SLOT_HEADER
            b.limit(base + TILE_BYTES)
            b.position(base)
            return b.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun pixels(slot: Int): IntBuffer = slice(slot).asIntBuffer()

        private fun crc(slot: Int): Int = CRC32().apply { update(slice(slot)) }.value.toInt()

        /** The number of tiles held over the filter pixels given. */
        fun count(left: Int, top: Int, width: Int, height: Int): Int {
            var n = 0
            for (ty in Math.floorDiv(top, T)..Math.floorDiv(top + height - 1, T)) {
                for (tx in Math.floorDiv(left, T)..Math.floorDiv(left + width - 1, T)) if (tiles.containsKey(tileKey(tx, ty))) n++
            }
            return n
        }

        /**
         * Every tile over the filter pixels given, row by row: checked, or made with [map] when [make] (null where a tile
         * is missing and is not made).
         */
        fun ready(left: Int, top: Int, width: Int, height: Int, map: NoiseMap?, make: Boolean = true): Array<Tile?> {
            val tx0 = Math.floorDiv(left, T)
            val ty0 = Math.floorDiv(top, T)
            val nx = Math.floorDiv(left + width - 1, T) - tx0 + 1
            val ny = Math.floorDiv(top + height - 1, T) - ty0 + 1
            val out = arrayOfNulls<Tile>(nx * ny)
            var missing = 0
            for (k in out.indices) {
                out[k] = checked(tileKey(tx0 + k % nx, ty0 + k / nx))
                if (out[k] == null) missing++
            }
            if (missing > 0 && make) {
                val todo = IntArray(missing)
                var m = 0
                for (k in out.indices) if (out[k] == null) todo[m++] = k
                SvgFilterRaster.parallel(missing) { i ->
                    val k = todo[i]
                    out[k] = make(tx0 + k % nx, ty0 + k / nx, map)
                }
            }
            return out
        }

        /** The tile at [k] if it is held and sound: a kept tile's CRC is checked on its first read. */
        private fun checked(k: Long): Tile? {
            val t = tiles[k] ?: return null
            if (t.checked) return t
            val c = chunks[t.slot / SLOTS]!!
            val base = (t.slot % SLOTS) * SLOT_BYTES
            if (crc(t.slot) == c.getInt(base + 8)) {
                t.checked = true
                fromDisk.incrementAndGet()
                return t
            }
            // damaged on disk: dropped (its slot freed) and made again
            if (tiles.remove(k, t)) {
                synchronized(this) {
                    c.putInt(base + 12, 0)
                    free.addLast(t.slot)
                }
                repaired.incrementAndGet()
            }
            return null
        }

        /** The tile at [tx], [ty], made once even when several threads ask at once. */
        private fun make(tx: Int, ty: Int, map: NoiseMap?): Tile {
            val k = tileKey(tx, ty)
            checked(k)?.let { return it }
            val task = FutureTask { checked(k) ?: compute(tx, ty, k, map) }
            val other = pending.putIfAbsent(k, task)
            try {
                if (other != null) return other.get()
                task.run()
                return task.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                if (other == null) pending.remove(k, task)
            }
        }

        private fun compute(tx: Int, ty: Int, k: Long, map: NoiseMap?): Tile {
            val px = if (key.steps.isEmpty()) scratch.get() else stepScratch.get()
            if (key.steps.isEmpty()) {
                noise.fillWebKit(tx * T, ty * T, T, T, invX, invY, key.baseX, key.baseY, key.octaves, key.fractal, key.channels, px)
            } else {
                requireNotNull(map) { "a field with steps is made with its map" }
                // the noise itself, kept as well (another moment's colours start from it), then the steps
                fill(key.noise, tx * T, ty * T, T, T, px, 0, T, null, direct = false)
                for (i in 0 until TILE_PIXELS) px[i] = map.map(px[i])
            }
            val t = store(tx, ty, px)
            tiles[k] = t
            made.incrementAndGet()
            return t
        }

        /** Keeps a made tile: in a free slot of the file when there is one, else on the heap. */
        private fun store(tx: Int, ty: Int, px: IntArray): Tile {
            if (channel != null) {
                try {
                    val slot = synchronized(this) {
                        if (free.isEmpty()) {
                            mapChunk()
                            for (s in slots - SLOTS until slots) free.addLast(s)
                        }
                        free.removeFirst()
                    }
                    val view = pixels(slot)
                    view.put(px, 0, TILE_PIXELS)
                    val c = chunks[slot / SLOTS]!!
                    val base = (slot % SLOTS) * SLOT_BYTES
                    c.putInt(base, tx)
                    c.putInt(base + 4, ty)
                    c.putInt(base + 8, crc(slot))
                    // written last: a tile is kept only once all of it is
                    c.putInt(base + 12, VALID)
                    return Tile(slot, pixels(slot), null).also { it.checked = true }
                } catch (e: IOException) {
                    // the disk is full: the rest of this field is kept on the heap (its tiles kept so far stay mapped)
                    synchronized(this) {
                        runCatching { channel?.close() }
                        channel = null
                    }
                }
            }
            synchronized(this) { bytes += TILE_BYTES }
            held.addAndGet(TILE_BYTES.toLong())
            return Tile(-1, null, px.copyOf()).also { it.checked = true }
        }

        fun close() = synchronized(this) {
            runCatching { channel?.close() }
            channel = null
            chunks = arrayOfNulls(0)
            tiles.clear()
            free.clear()
            slots = 0
            held.addAndGet(-bytes)
            bytes = 0
        }
    }

    companion object {
        /** The tile size, in filter pixels. */
        const val T = 64

        private const val TILE_PIXELS = T * T
        private const val TILE_BYTES = TILE_PIXELS * 4
        private const val SLOT_HEADER = 16
        private const val SLOT_BYTES = SLOT_HEADER + TILE_BYTES
        private const val SLOTS = 64
        private const val CHUNK_BYTES = SLOTS * SLOT_BYTES
        private const val HEADER = 64
        private const val MAGIC = 0x5A4E5441 // "ATNZ"
        private const val FORMAT = 1

        /**
         * The version of the noise arithmetic: bump it whenever [Turbulence.colorWebKit] (or a pointwise step the filters
         * keep, [SvgFilterRaster]'s colour matrix) could give another byte, so files made by the old code are never read
         * as the new one's.
         */
        const val ALGORITHM = 1
        private const val VALID = 0x494C4156 // "VALI"
        private const val SUFFIX = ".noise"

        /** About 60 MB mapped: five or six screen-wide fields at a phone's resolution. */
        const val DEFAULT_MAX_BYTES = 64L shl 20
        const val DEFAULT_MAX_DISK_BYTES = 160L shl 20

        /** The cache the map's filters use, set by [app.atlas.mapview.MapEngine.useCacheDir]; null computes every frame's noise. */
        @Volatile
        var shared: TurbulenceCache? = null

        private val scratch = ThreadLocal.withInitial { IntArray(TILE_PIXELS) }

        /** Zeros that take a new chunk's room on disk before it is mapped. */
        private val ZEROS: ByteBuffer = ByteBuffer.allocate(64 shl 10)

        /** A field with steps makes its noise tile (in [scratch]) while its own tile is being made. */
        private val stepScratch = ThreadLocal.withInitial { IntArray(TILE_PIXELS) }

        private fun tileKey(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

        private fun bits(f: Float) = java.lang.Float.floatToRawIntBits(f)

        private fun ints(key: NoiseField) = intArrayOf(
            bits(key.seed), bits(key.baseX), bits(key.baseY), key.octaves, if (key.fractal) 1 else 0, bits(key.scaleX), bits(key.scaleY), key.channels,
        )

        /** The file of [key]: a 64-bit FNV-1a hash of the key and the versions. */
        fun name(key: NoiseField): String {
            var h = -0x340d631b7bdddcdbL
            fun add(b: Int) {
                h = h xor (b and 0xFF).toLong()
                h *= 0x100000001b3L
            }
            for (v in intArrayOf(FORMAT, ALGORITHM, T) + ints(key)) for (b in 0 until 4) add(v ushr (8 * b))
            for (b in key.steps.toByteArray()) add(b.toInt())
            return "%016x%s".format(h, SUFFIX)
        }

        /** Where a field's chunks start: after the header and its steps, on a 64-byte boundary. */
        private fun dataStart(key: NoiseField): Long = (HEADER + key.steps.toByteArray().size + 63L) / 64 * 64

        private fun headerBytes(key: NoiseField): ByteBuffer {
            val steps = key.steps.toByteArray()
            val b = ByteBuffer.allocate(dataStart(key).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(MAGIC).putInt(FORMAT).putInt(ALGORITHM).putInt(T).putInt(SLOTS)
            for (v in ints(key)) b.putInt(v)
            b.putInt(steps.size)
            b.position(HEADER)
            b.put(steps)
            b.position(0)
            return b
        }

        /** The key a file was made for and where its chunks start, or null when it is not a noise file of this version. */
        private fun header(ch: FileChannel): Pair<NoiseField, Long>? {
            if (ch.size() < HEADER) return null
            val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            while (b.hasRemaining()) if (ch.read(b, b.position().toLong()) < 0) return null
            b.flip()
            if (b.getInt() != MAGIC || b.getInt() != FORMAT || b.getInt() != ALGORITHM || b.getInt() != T || b.getInt() != SLOTS) return null
            val v = IntArray(8) { b.getInt() }
            val n = b.getInt()
            if (n < 0 || n > 1 shl 16 || ch.size() < HEADER + n) return null
            val s = ByteBuffer.allocate(n)
            while (s.hasRemaining()) if (ch.read(s, HEADER.toLong() + s.position()) < 0) return null
            val key = runCatching {
                NoiseField(
                    java.lang.Float.intBitsToFloat(v[0]), java.lang.Float.intBitsToFloat(v[1]), java.lang.Float.intBitsToFloat(v[2]), v[3], v[4] == 1,
                    java.lang.Float.intBitsToFloat(v[5]), java.lang.Float.intBitsToFloat(v[6]), v[7], String(s.array()),
                )
            }.getOrNull() ?: return null
            return key to dataStart(key)
        }
    }
}
