package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import java.io.File

/** A PMTiles archive uses a compression Atlas cannot read (brotli or zstd). */
class UnsupportedCompressionException(message: String) : TileFormatException(message)

/** The fixed 127-byte header of a PMTiles v3 archive. */
class PmHeader(
    val specVersion: Int,
    val rootOffset: Long,
    val rootLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafOffset: Long,
    val leafLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: Int,
    val tileCompression: Int,
    val tileType: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: BBox,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double,
) {
    companion object {
        const val SIZE = 127

        fun parse(b: ByteArray): PmHeader {
            if (b.size < SIZE) throw TileFormatException("PMTiles header needs $SIZE bytes, got ${b.size}")
            if (String(b, 0, 7, Charsets.US_ASCII) != "PMTiles") throw TileFormatException("not a PMTiles archive")
            val v = b[7].toInt()
            if (v != 3) throw TileFormatException("PMTiles version $v; Atlas reads version 3")
            fun u64(p: Int): Long {
                var x = 0L
                for (i in 7 downTo 0) x = (x shl 8) or (b[p + i].toLong() and 0xff)
                return x
            }
            fun i32(p: Int): Int =
                (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or ((b[p + 2].toInt() and 0xff) shl 16) or (b[p + 3].toInt() shl 24)
            fun u8(p: Int): Int = b[p].toInt() and 0xff
            return PmHeader(
                v, u64(8), u64(16), u64(24), u64(32), u64(40), u64(48), u64(56), u64(64), u64(72), u64(80), u64(88),
                u8(96) == 1, u8(97), u8(98), u8(99), u8(100), u8(101),
                BBox(i32(102) / 1e7, i32(106) / 1e7, i32(110) / 1e7, i32(114) / 1e7),
                u8(118), i32(119) / 1e7, i32(123) / 1e7,
            )
        }
    }
}

/** One PMTiles directory: parallel arrays of entries sorted by tile id. A run length of 0 marks a leaf directory. */
class PmDirectory(val tileIds: LongArray, val runLengths: IntArray, val lengths: IntArray, val offsets: LongArray) {
    val size: Int get() = tileIds.size

    /** The entry holding [tileId] (a tile run or the leaf that may hold it), or -1. */
    fun find(tileId: Long): Int {
        var lo = 0
        var hi = tileIds.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = tileId.compareTo(tileIds[mid])
            if (c > 0) lo = mid + 1 else if (c < 0) hi = mid - 1 else return mid
        }
        if (hi >= 0) {
            if (runLengths[hi] == 0) return hi
            if (tileId - tileIds[hi] < runLengths[hi]) return hi
        }
        return -1
    }

    companion object {
        /** Reads a directory from its (decompressed) bytes. */
        fun parse(b: ByteArray): PmDirectory {
            val r = ProtoReader(b)
            val n = r.varint()
            if (n < 0 || n > b.size) throw TileFormatException("PMTiles directory claims $n entries in ${b.size} bytes")
            val count = n.toInt()
            val ids = LongArray(count)
            val runs = IntArray(count)
            val lens = IntArray(count)
            val offs = LongArray(count)
            var last = 0L
            for (i in 0 until count) { last += r.varint(); ids[i] = last }
            for (i in 0 until count) runs[i] = r.varint().toInt()
            for (i in 0 until count) lens[i] = r.varint().toInt()
            for (i in 0 until count) {
                val v = r.varint()
                offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            }
            return PmDirectory(ids, runs, lens, offs)
        }
    }
}

/**
 * A PMTiles v3 archive (local or remote) as a [TileSource]. Directories (root and leaves) are read on demand,
 * decompressed and kept in a small LRU cache. Gzip and uncompressed archives are read; brotli and zstd fail clearly
 * with [UnsupportedCompressionException] when the archive is opened.
 */
class PmTiles private constructor(
    private val source: ByteRangeSource,
    val header: PmHeader,
    private val root: PmDirectory,
    /** The archive's JSON metadata. */
    val metadata: Map<String, Any?>,
    directoryCacheSize: Int,
) : TileSource {
    private val directories = object : LinkedHashMap<Long, PmDirectory>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PmDirectory>?) = size > directoryCacheSize
    }

    override val minZoom: Int get() = header.minZoom
    override val maxZoom: Int get() = header.maxZoom
    override val bounds: BBox get() = header.bounds
    override val attribution: String get() = metadata["attribution"] as? String ?: ""

    /** True when tiles are Mapbox Vector Tiles. */
    val isVector: Boolean get() = header.tileType == TYPE_MVT

    /** The raw tile bytes (decompressed) of [id], or null. */
    override suspend fun tile(id: TileId): ByteArray? {
        if (!id.isValid() || id.z < header.minZoom || id.z > header.maxZoom) return null
        val tid = tileId(id.z, id.x, id.y)
        var dir = root
        for (depth in 0 until 4) {
            val i = dir.find(tid)
            if (i < 0) return null
            if (dir.runLengths[i] > 0) {
                val data = source.read(header.tileDataOffset + dir.offsets[i], dir.lengths[i])
                if (data.size != dir.lengths[i]) throw TileFormatException("PMTiles tile $id truncated")
                return decompress(data, header.tileCompression)
            }
            dir = leaf(dir.offsets[i], dir.lengths[i])
        }
        throw TileFormatException("PMTiles directories nest deeper than the spec allows")
    }

    private suspend fun leaf(offset: Long, length: Int): PmDirectory {
        synchronized(directories) { directories[offset] }?.let { return it }
        val raw = source.read(header.leafOffset + offset, length)
        if (raw.size != length) throw TileFormatException("PMTiles leaf directory truncated")
        val dir = PmDirectory.parse(decompress(raw, header.internalCompression))
        synchronized(directories) { directories[offset] = dir }
        return dir
    }

    /** Leaf directories now held in memory. */
    val cachedDirectories: Int get() = synchronized(directories) { directories.size }

    override fun close() = source.close()

    companion object {
        const val COMPRESSION_UNKNOWN = 0
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val COMPRESSION_BROTLI = 3
        const val COMPRESSION_ZSTD = 4
        const val TYPE_MVT = 1

        /** The first read: the header and, by the spec, the whole root directory. */
        private const val FIRST_READ = 16384

        /** Opens an archive: reads its header, root directory and metadata. */
        suspend fun open(source: ByteRangeSource, directoryCacheSize: Int = 64): PmTiles {
            val first = source.read(0, FIRST_READ)
            val h = PmHeader.parse(first)
            checkCompression(h.internalCompression, "directories")
            checkCompression(h.tileCompression, "tiles")
            val rootBytes = if (h.rootOffset + h.rootLength <= first.size) {
                first.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
            } else {
                source.read(h.rootOffset, h.rootLength.toInt())
            }
            val root = PmDirectory.parse(decompress(rootBytes, h.internalCompression))
            val metadata = if (h.metadataLength > 0) {
                val raw = source.read(h.metadataOffset, h.metadataLength.toInt())
                runCatching { Json.parseObject(String(decompress(raw, h.internalCompression), Charsets.UTF_8)) }.getOrElse { emptyMap() }
            } else {
                emptyMap()
            }
            return PmTiles(source, h, root, metadata, directoryCacheSize)
        }

        suspend fun open(file: File): PmTiles {
            val src = FileRangeSource(file)
            return try { open(src) } catch (e: Throwable) { src.close(); throw e }
        }

        private fun checkCompression(c: Int, what: String) {
            when (c) {
                COMPRESSION_BROTLI -> throw UnsupportedCompressionException("PMTiles $what are brotli-compressed; Atlas reads gzip or none")
                COMPRESSION_ZSTD -> throw UnsupportedCompressionException("PMTiles $what are zstd-compressed; Atlas reads gzip or none")
                COMPRESSION_UNKNOWN, COMPRESSION_NONE, COMPRESSION_GZIP -> Unit
                else -> throw UnsupportedCompressionException("PMTiles $what use unknown compression $c")
            }
        }

        internal fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
            COMPRESSION_GZIP -> Gzip.decompress(b)
            COMPRESSION_UNKNOWN -> Gzip.unwrap(b)
            else -> b
        }

        /** The Hilbert tile id of z/x/y: tiles of lower zooms first, then along the Hilbert curve. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..31) { "zoom $z" }
            val n = 1L shl z
            require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y outside its zoom" }
            val acc = ((1L shl (2 * z)) - 1) / 3
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = n shr 1
            while (s > 0) {
                val rx = if (tx and s != 0L) 1L else 0L
                val ry = if (ty and s != 0L) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx; tx = ty; ty = t
                }
                s = s shr 1
            }
            return acc + d
        }

        fun tileId(id: TileId): Long = tileId(id.z, id.x, id.y)

        /** The tile of a Hilbert tile id. */
        fun tileOf(tileId: Long): TileId {
            require(tileId >= 0) { "tile id $tileId" }
            var acc = 0L
            for (z in 0..31) {
                val count = 1L shl (2 * z)
                if (acc + count > tileId) {
                    var t = tileId - acc
                    var x = 0L
                    var y = 0L
                    var s = 1L
                    val n = 1L shl z
                    while (s < n) {
                        val rx = 1L and (t shr 1)
                        val ry = 1L and (t xor rx)
                        if (ry == 0L) {
                            if (rx == 1L) {
                                x = s - 1 - x
                                y = s - 1 - y
                            }
                            val tmp = x; x = y; y = tmp
                        }
                        x += s * rx
                        y += s * ry
                        t = t shr 2
                        s = s shl 1
                    }
                    return TileId(z, x.toInt(), y.toInt())
                }
                acc += count
            }
            throw IllegalArgumentException("tile id $tileId beyond zoom 31")
        }
    }
}
