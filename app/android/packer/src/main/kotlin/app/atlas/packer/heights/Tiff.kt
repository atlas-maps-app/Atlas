package app.atlas.packer.heights

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * The packer's GeoTIFF reader, for the terrain's cloud-optimised sources (ENGINE.md part 6): the images of a file (the
 * raster and its overviews) from its directories, read through any reader of byte ranges (a file, or a host by HTTP
 * ranges), a tile's samples (32-bit floats, DEFLATE or stored, with the floating-point predictor or none: what
 * Copernicus's and AHN's files hold), and one image written out as a GeoTIFF of its own, its compressed tiles as they
 * are, for GDAL to warp. Little-endian files, classic or BigTIFF.
 */
object Tiff {
    /** One image of a file: the raster, or one of its overviews. */
    class Image(
        val width: Int,
        val height: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val compression: Int,
        val predictor: Int,
        val offsets: LongArray,
        val counts: LongArray,
        /** Pixel scale (x, y), and the tie point's raster (i, j) and model (x, y): the file's georeferencing, or null (an overview carries none). */
        val scale: DoubleArray?,
        val tie: DoubleArray?,
        val geoKeys: IntArray?,
        val geoDoubles: DoubleArray?,
        val geoAscii: String?,
        val nodata: String?,
        /** Whether a sample stands at a pixel's centre (PixelIsPoint: the tie point is then a centre), not over its area. */
        val pixelIsPoint: Boolean,
    ) {
        val tilesAcross: Int get() = (width + tileWidth - 1) / tileWidth
        val tilesDown: Int get() = (height + tileHeight - 1) / tileHeight

        /** The first and last byte of the image's tiles in the file. */
        val dataRange: LongRange
            get() {
                var lo = Long.MAX_VALUE
                var hi = 0L
                for (i in offsets.indices) {
                    if (counts[i] == 0L) continue
                    if (offsets[i] < lo) lo = offsets[i]
                    if (offsets[i] + counts[i] - 1 > hi) hi = offsets[i] + counts[i] - 1
                }
                return if (lo == Long.MAX_VALUE) 0L..-1L else lo..hi
            }
    }

    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_SAMPLES = 277
    private const val TAG_PREDICTOR = 317
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_HEIGHT = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_COUNTS = 325
    private const val TAG_FORMAT = 339
    private const val TAG_SCALE = 33550
    private const val TAG_TIE = 33922
    private const val TAG_GEO_KEYS = 34735
    private const val TAG_GEO_DOUBLES = 34736
    private const val TAG_GEO_ASCII = 34737
    private const val TAG_NODATA = 42113

    private val SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 16 to 8, 17 to 8, 18 to 8)

    /** A reader of byte ranges that asks for the file's head once ([HEAD] bytes: every directory of a cloud-optimised file lies there) and for more only where a directory or its arrays lie beyond it. */
    class Ranges(private val read: (offset: Long, length: Int) -> ByteArray) {
        private var head: ByteArray? = null

        fun at(offset: Long, length: Int): ByteBuffer {
            val h = head ?: read(0, HEAD).also { head = it }
            val bytes = if (offset + length <= h.size) h.copyOfRange(offset.toInt(), offset.toInt() + length) else read(offset, length)
            if (bytes.size < length) throw IllegalArgumentException("a TIFF cut short: $length bytes at $offset")
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        companion object {
            const val HEAD = 1 shl 16
        }
    }

    /** The images of the file [read] reads, the raster first, then its overviews in the file's order. */
    fun images(read: (offset: Long, length: Int) -> ByteArray): List<Image> {
        val src = Ranges { o, n -> read(o, n) }
        val h = src.at(0, 16)
        require(h.get(0) == 'I'.code.toByte() && h.get(1) == 'I'.code.toByte()) { "not a little-endian TIFF" }
        val magic = h.getShort(2).toInt()
        require(magic == 42 || magic == 43) { "not a TIFF" }
        val big = magic == 43
        var at = if (big) h.getLong(8) else h.getInt(4).toLong() and 0xFFFFFFFFL
        val out = ArrayList<Image>()
        while (at != 0L && out.size < 32) {
            val n = if (big) src.at(at, 8).getLong(0).toInt() else src.at(at, 2).getShort(0).toInt() and 0xFFFF
            require(n in 1..4096) { "a TIFF directory of $n entries" }
            val entry = if (big) 20 else 12
            val base = if (big) 8 else 2
            val dir = src.at(at, base + n * entry + (if (big) 8 else 4))
            val tags = HashMap<Int, Triple<Int, Long, Int>>() // type, count, where its value field lies in dir
            for (i in 0 until n) {
                val e = base + i * entry
                val tag = dir.getShort(e).toInt() and 0xFFFF
                val type = dir.getShort(e + 2).toInt() and 0xFFFF
                val count = if (big) dir.getLong(e + 4) else dir.getInt(e + 4).toLong() and 0xFFFFFFFFL
                tags[tag] = Triple(type, count, e + (if (big) 12 else 8))
            }
            fun raw(tag: Int): ByteBuffer? {
                val (type, count, field) = tags[tag] ?: return null
                val size = (SIZES[type] ?: throw IllegalArgumentException("TIFF tag $tag of type $type")) * count
                require(size in 0..(64 shl 20)) { "TIFF tag $tag of $size bytes" }
                val inline = if (big) 8 else 4
                if (size <= inline) return dir.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(field).slice().order(ByteOrder.LITTLE_ENDIAN)
                val where = if (big) dir.getLong(field) else dir.getInt(field).toLong() and 0xFFFFFFFFL
                return src.at(where, size.toInt())
            }
            fun longs(tag: Int): LongArray? {
                val (type, count, _) = tags[tag] ?: return null
                val b = raw(tag)!!
                return LongArray(count.toInt()) { i ->
                    when (type) {
                        1 -> b.get(i).toLong() and 0xFF
                        3 -> b.getShort(i * 2).toLong() and 0xFFFF
                        4 -> b.getInt(i * 4).toLong() and 0xFFFFFFFFL
                        16, 17, 18 -> b.getLong(i * 8)
                        else -> throw IllegalArgumentException("TIFF tag $tag of type $type is no integer")
                    }
                }
            }
            fun doubles(tag: Int): DoubleArray? {
                val (type, count, _) = tags[tag] ?: return null
                require(type == 12) { "TIFF tag $tag of type $type is no double" }
                val b = raw(tag)!!
                return DoubleArray(count.toInt()) { b.getDouble(it * 8) }
            }
            fun ascii(tag: Int): String? {
                val (_, count, _) = tags[tag] ?: return null
                val b = raw(tag)!!
                val bytes = ByteArray(count.toInt()) { b.get(it) }
                return String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000')
            }
            fun one(tag: Int, absent: Long? = null): Long = longs(tag)?.firstOrNull() ?: absent ?: throw IllegalArgumentException("a TIFF image without tag $tag")
            require(one(TAG_BITS) == 32L && one(TAG_FORMAT, 1) == 3L && one(TAG_SAMPLES, 1) == 1L) { "not one band of 32-bit floats" }
            val offsets = longs(TAG_TILE_OFFSETS) ?: throw IllegalArgumentException("a TIFF image that is not tiled")
            val counts = longs(TAG_TILE_COUNTS) ?: throw IllegalArgumentException("a TIFF image without its tiles' lengths")
            val keys = longs(TAG_GEO_KEYS)?.let { k -> IntArray(k.size) { k[it].toInt() } }
            // GTRasterTypeGeoKey (1025): 2 is PixelIsPoint
            var point = false
            if (keys != null) for (i in 4 until keys.size - 3 step 4) if (keys[i] == 1025 && keys[i + 1] == 0 && keys[i + 3] == 2) point = true
            val image = Image(
                one(TAG_WIDTH).toInt(), one(TAG_HEIGHT).toInt(), one(TAG_TILE_WIDTH).toInt(), one(TAG_TILE_HEIGHT).toInt(), one(TAG_COMPRESSION, 1).toInt(),
                one(TAG_PREDICTOR, 1).toInt(), offsets, counts, doubles(TAG_SCALE), doubles(TAG_TIE), keys, doubles(TAG_GEO_DOUBLES), ascii(TAG_GEO_ASCII), ascii(TAG_NODATA), point,
            )
            require(image.width > 0 && image.height > 0 && image.tileWidth > 0 && image.tileHeight > 0 && offsets.size == counts.size && offsets.size == image.tilesAcross * image.tilesDown) {
                "a TIFF image of ${image.width} by ${image.height} in ${offsets.size} tiles"
            }
            out += image
            at = if (big) dir.getLong(base + n * entry) else dir.getInt(base + n * entry).toLong() and 0xFFFFFFFFL
        }
        require(out.isNotEmpty()) { "a TIFF without an image" }
        return out
    }

    /** The images of [file]. */
    fun images(file: File): List<Image> = RandomAccessFile(file, "r").use { f -> images { o, n -> at(f, o, n) } }

    /** [length] bytes of [f] from [offset] (fewer at its end). */
    fun at(f: RandomAccessFile, offset: Long, length: Int): ByteArray {
        val n = maxOf(0L, minOf(length.toLong(), f.length() - offset)).toInt()
        val b = ByteArray(n)
        f.seek(offset)
        f.readFully(b)
        return b
    }

    /** One tile's samples, row by row ([Image.tileWidth] × [Image.tileHeight]), from its bytes in the file. */
    fun samples(image: Image, stored: ByteArray): FloatArray {
        val w = image.tileWidth
        val h = image.tileHeight
        val raw = when (image.compression) {
            1 -> stored
            8, 32946 -> {
                val out = ByteArray(w * h * 4)
                val inflater = Inflater()
                try {
                    inflater.setInput(stored)
                    var n = 0
                    while (n < out.size) {
                        val k = inflater.inflate(out, n, out.size - n)
                        if (k == 0 && (inflater.finished() || inflater.needsInput())) break
                        n += k
                    }
                    require(n == out.size) { "a TIFF tile of $n bytes, not ${out.size}" }
                } finally {
                    inflater.end()
                }
                out
            }
            else -> throw IllegalArgumentException("TIFF compression ${image.compression}: this reader knows none and DEFLATE")
        }
        require(raw.size >= w * h * 4) { "a TIFF tile of ${raw.size} bytes, not ${w * h * 4}" }
        val out = FloatArray(w * h)
        when (image.predictor) {
            1 -> ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            3 -> {
                // the floating-point predictor: each row's bytes as four planes, most significant first, differenced
                val row = ByteArray(w * 4)
                for (r in 0 until h) {
                    System.arraycopy(raw, r * w * 4, row, 0, w * 4)
                    for (i in 1 until row.size) row[i] = (row[i] + row[i - 1]).toByte()
                    for (c in 0 until w) {
                        val bits = ((row[c].toInt() and 0xFF) shl 24) or ((row[w + c].toInt() and 0xFF) shl 16) or ((row[2 * w + c].toInt() and 0xFF) shl 8) or (row[3 * w + c].toInt() and 0xFF)
                        out[r * w + c] = Float.fromBits(bits)
                    }
                }
            }
            else -> throw IllegalArgumentException("TIFF predictor ${image.predictor}: this reader knows none and the floating-point one")
        }
        return out
    }

    /** The whole [image], row by row, its tiles read by [read]. */
    fun raster(image: Image, read: (offset: Long, length: Int) -> ByteArray): FloatArray {
        val out = FloatArray(image.width * image.height)
        for (ty in 0 until image.tilesDown) for (tx in 0 until image.tilesAcross) {
            val i = ty * image.tilesAcross + tx
            if (image.counts[i] == 0L) {
                continue
            }
            val tile = samples(image, read(image.offsets[i], image.counts[i].toInt()))
            val x0 = tx * image.tileWidth
            val y0 = ty * image.tileHeight
            val cols = minOf(image.tileWidth, image.width - x0)
            for (r in 0 until minOf(image.tileHeight, image.height - y0)) System.arraycopy(tile, r * image.tileWidth, out, (y0 + r) * image.width + x0, cols)
        }
        return out
    }

    /**
     * [image] of a file whose raster is [first] as a GeoTIFF of its own in [out]: its tiles' bytes as they lie in the
     * source ([data], the image's [Image.dataRange]), the raster's georeferencing scaled to the image's size (as GDAL
     * places an overview) and its nodata value.
     */
    fun writeImage(first: Image, image: Image, data: ByteArray, out: File) {
        val range = image.dataRange
        require(data.size.toLong() == range.last - range.first + 1) { "${data.size} bytes for the image's ${range.last - range.first + 1}" }
        val scale0 = first.scale ?: throw IllegalArgumentException("a TIFF without a pixel scale")
        val tie = first.tie ?: throw IllegalArgumentException("a TIFF without a tie point")
        val scale = doubleArrayOf(scale0[0] * first.width / image.width, scale0[1] * first.height / image.height, 0.0)
        class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
        fun ints(v: LongArray) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()
        fun doubles(v: DoubleArray) = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putDouble(it) } }.array()
        fun ascii(s: String) = (s + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val entries = ArrayList<Entry>()
        entries += Entry(TAG_WIDTH, 4, 1, ints(longArrayOf(image.width.toLong())))
        entries += Entry(TAG_HEIGHT, 4, 1, ints(longArrayOf(image.height.toLong())))
        entries += Entry(TAG_BITS, 3, 1, shorts(32))
        entries += Entry(TAG_COMPRESSION, 3, 1, shorts(image.compression))
        entries += Entry(262, 3, 1, shorts(1)) // photometric: black is zero
        entries += Entry(TAG_SAMPLES, 3, 1, shorts(1))
        entries += Entry(284, 3, 1, shorts(1)) // planar configuration
        entries += Entry(TAG_PREDICTOR, 3, 1, shorts(image.predictor))
        entries += Entry(TAG_TILE_WIDTH, 3, 1, shorts(image.tileWidth))
        entries += Entry(TAG_TILE_HEIGHT, 3, 1, shorts(image.tileHeight))
        val offsetsAt = entries.size
        entries += Entry(TAG_TILE_OFFSETS, 4, image.offsets.size, ByteArray(image.offsets.size * 4))
        entries += Entry(TAG_TILE_COUNTS, 4, image.counts.size, ints(image.counts))
        entries += Entry(TAG_FORMAT, 3, 1, shorts(3))
        entries += Entry(TAG_SCALE, 12, 3, doubles(scale))
        entries += Entry(TAG_TIE, 12, tie.size, doubles(tie))
        first.geoKeys?.let { k -> entries += Entry(TAG_GEO_KEYS, 3, k.size, shorts(*k)) }
        first.geoDoubles?.let { d -> entries += Entry(TAG_GEO_DOUBLES, 12, d.size, doubles(d)) }
        first.geoAscii?.let { a -> ascii(a).let { entries += Entry(TAG_GEO_ASCII, 2, it.size, it) } }
        (image.nodata ?: first.nodata)?.let { v -> ascii(v).let { entries += Entry(TAG_NODATA, 2, it.size, it) } }
        entries.sortBy { it.tag }
        val offsetsIndex = entries.indexOfFirst { it.tag == TAG_TILE_OFFSETS }
        check(offsetsAt >= 0 && offsetsIndex >= 0)
        // header (8), the directory, the values that do not fit an entry, then the tiles
        val dirBytes = 2 + entries.size * 12 + 4
        var extra = 8 + dirBytes
        val where = IntArray(entries.size)
        for ((i, e) in entries.withIndex()) {
            if (e.bytes.size > 4) {
                where[i] = extra
                extra += e.bytes.size + (e.bytes.size and 1)
            }
        }
        val dataAt = extra.toLong()
        require(dataAt + data.size < 0xFFFFFFFFL) { "an image of ${data.size} bytes is too big for a classic TIFF" }
        val rebased = LongArray(image.offsets.size) { if (image.counts[it] == 0L) 0L else image.offsets[it] - range.first + dataAt }
        val head = ByteBuffer.allocate(extra).order(ByteOrder.LITTLE_ENDIAN)
        head.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        head.putShort(entries.size.toShort())
        for ((i, e) in entries.withIndex()) {
            val bytes = if (i == offsetsIndex) ints(rebased) else e.bytes
            head.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (bytes.size <= 4) {
                head.put(bytes)
                repeat(4 - bytes.size) { head.put(0) }
            } else {
                head.putInt(where[i])
                val back = head.position()
                head.position(where[i])
                head.put(bytes)
                head.position(back)
            }
        }
        head.putInt(0)
        out.absoluteFile.parentFile?.mkdirs()
        val part = File(out.path + ".part")
        part.outputStream().buffered(1 shl 16).use { o ->
            o.write(head.array())
            o.write(data)
        }
        if (out.exists()) out.delete()
        check(part.renameTo(out)) { "cannot move $part to $out" }
    }
}
