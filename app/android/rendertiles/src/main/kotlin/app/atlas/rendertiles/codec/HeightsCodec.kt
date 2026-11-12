package app.atlas.rendertiles.codec

import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.HeightRaster
import app.atlas.rendertiles.model.Heights
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Reach

/**
 * Writes and reads a terrain tile ([Heights]): a format-3 tile ([Format]'s layout) whose one layer is
 * [Layer.HEIGHTS], and that layer's columns. The reader makes every check [Heights] asks, as the core's does
 * (`CP/tiles/Heights`), and refuses with a [TileFormatException].
 */
object HeightsCodec {
    /** A decoded terrain tile: its key and its raster. */
    class Decoded(val level: Int, val x: Int, val y: Int, val raster: HeightRaster)

    /** The `samples` column's bytes: the width twice, then each sample's residual from its prediction. */
    fun med(samples: IntArray, width: Int): ByteArray {
        require(samples.size == width * width)
        val out = LeSink(samples.size + 8)
        out.varU(width.toLong())
        out.varU(width.toLong())
        for (r in 0 until width) {
            val row = r * width
            for (c in 0 until width) {
                out.varZ(samples[row + c].toLong() - predict(samples, width, r, c))
            }
        }
        return out.toByteArray()
    }

    /** LOCO-I's median edge detector over the samples before ([r], [c]) ([Heights]). */
    private fun predict(v: IntArray, width: Int, r: Int, c: Int): Long {
        val at = r * width + c
        val a = if (c > 0) v[at - 1].toLong() else if (r > 0) v[at - width].toLong() else 0L
        val b = if (r > 0) v[at - width].toLong() else a
        val d = if (r > 0 && c > 0) v[at - width - 1].toLong() else b
        return if (d >= maxOf(a, b)) minOf(a, b) else if (d <= minOf(a, b)) maxOf(a, b) else a + b - d
    }

    /** The samples of a `samples` column of rasters [width] a row whose highest is [max]. */
    fun unmed(src: LeSource, width: Int, max: Long): IntArray {
        val w = src.varCount()
        val h = src.varCount()
        if (w != width || h != width) throw TileFormatException("a raster of $w by $h samples, its side and apron give $width")
        if (src.remaining < width.toLong() * width) throw TileFormatException("${width * width} samples in ${src.remaining} bytes")
        val v = IntArray(width * width)
        var low = Long.MAX_VALUE
        var high = Long.MIN_VALUE
        for (r in 0 until width) {
            for (c in 0 until width) {
                // wrapping, as the core's unsigned sum: a damaged residual lands outside 0..max and is refused
                val s = predict(v, width, r, c) + src.varZ()
                if (s < 0 || s > max) throw TileFormatException("a sample of $s steps, outside 0 to $max")
                v[r * width + c] = s.toInt()
                if (s < low) low = s
                if (s > high) high = s
            }
        }
        if (src.remaining != 0) throw TileFormatException("${src.remaining} bytes after the last sample")
        if (low != 0L) throw TileFormatException("the lowest sample is $low steps above the base, not 0")
        if (high != max) throw TileFormatException("the highest sample is $high steps above the base, not the $max the tile names")
        return v
    }

    /** The highest point of [raster] in centimetres, at least 0: the tile's top in its cell and its reach. */
    fun topCm(raster: HeightRaster): Int {
        val cm = Math.ceil(raster.step * (raster.base + raster.max) * 100.0)
        return if (cm <= 0) 0 else if (cm >= Int.MAX_VALUE) Int.MAX_VALUE else cm.toInt()
    }

    /** The tile ([level], [x], [y]) of [raster], as a pack's writer takes it. */
    fun encoded(level: Int, x: Int, y: Int, raster: HeightRaster): Format3Writer.Encoded {
        val box = Grid.tileBox(level, x, y)
        return Format3Writer.Encoded(level, x, y, encodeTile(level, x, y, raster), 1, Reach(box[0], box[1], box[2], box[3], topCm(raster)), mapOf(Layer.HEIGHTS.label to 1))
    }

    /** One terrain tile's bytes. */
    fun encodeTile(level: Int, x: Int, y: Int, raster: HeightRaster): ByteArray {
        require(level in 0..Format.MAX_LEVEL && x in 0 until (1 shl level) && y in 0 until (1 shl level)) { "no tile $level/$x/$y" }
        val strings = StringTable()
        val layer = LeSink(raster.samples.size + 256)
        var columns = 0
        fun column(name: String, codec: Int, bytes: ByteArray) {
            layer.varU(strings.of(name).toLong())
            layer.u8(codec)
            layer.varU(bytes.size.toLong())
            layer.bytes(bytes)
            columns++
        }
        fun one(write: LeSink.() -> Unit): ByteArray = LeSink(16).apply(write).toByteArray()
        column(Heights.SIDE, Codec.VARU.id, one { varU(raster.side.toLong()) })
        column(Heights.APRON_COLUMN, Codec.VARU.id, one { varU(raster.apron.toLong()) })
        column(Heights.STEP, Codec.F64.id, one { f64(raster.step) })
        column(Heights.BASE, Codec.VARZ.id, one { varZ(raster.base) })
        column(Heights.MAX, Codec.VARU.id, one { varU(raster.max.toLong()) })
        column(Heights.FLAGS, Codec.VARU.id, one { varU(raster.flags.toLong()) })
        column(Heights.SOURCES, Codec.STRS.id, one {
            varU(raster.sources.size + 1L)
            for (s in raster.sources) varU(strings.of(s).toLong())
        })
        column(Heights.SHARES, Codec.IDS.id, one {
            varU(raster.shares.size.toLong())
            var prev = 0L
            for (s in raster.shares) {
                varZ(s - prev)
                prev = s
            }
        })
        column(Heights.SAMPLES, Heights.CODEC_MED, med(raster.samples, raster.width))

        val stringBlock = LeSink(256).also { strings.write(it) }
        val box = Grid.tileBox(level, x, y)
        val n = Format.cellsPerSide(level)
        val cells = LeSink(n * n + 32)
        // the raster is the tile's one feature, in its first cell: the tile's box, the raster's top
        cells.varU(1)
        cells.varZ(0); cells.varZ(0)
        cells.varZ(box[2].toLong() - box[0]); cells.varZ(box[3].toLong() - box[1])
        cells.varZ(topCm(raster).toLong())
        cells.varU(1)
        repeat(n * n - 1) { cells.varU(0) }

        val stringAt = Format.HEADER_BYTES + Format.LAYER_ENTRY_BYTES
        val cellAt = stringAt + stringBlock.size
        val layerAt = cellAt + cells.size
        val out = LeSink(layerAt + layer.size)
        out.u32(Format.TILE_MAGIC.toLong())
        out.u16(Format.PAYLOAD)
        out.u8(level)
        out.u8(n)
        out.u32(x.toLong()); out.u32(y.toLong())
        for (v in box) out.i32(v)
        out.u32(stringAt.toLong()); out.u32(stringBlock.size.toLong())
        out.u32(cellAt.toLong()); out.u32(cells.size.toLong())
        out.u16(1); out.u16(0)
        out.u8(Layer.HEIGHTS.id); out.u8(0); out.u16(columns)
        out.u32(1); out.u32(layerAt.toLong()); out.u32(layer.size.toLong())
        out.bytes(stringBlock.unsafeArray(), 0, stringBlock.size)
        out.bytes(cells.unsafeArray(), 0, cells.size)
        out.bytes(layer.unsafeArray(), 0, layer.size)
        return out.toByteArray()
    }

    /** The terrain tile in [raw], every check made; a tile without a heights layer is refused. */
    fun decodeTile(raw: ByteArray): Decoded {
        if (raw.size < Format.HEADER_BYTES) throw TileFormatException("a tile of ${raw.size} bytes")
        if (raw.size > Format.MAX_TILE_BYTES) throw TileFormatException("a tile of ${raw.size} bytes, more than ${Format.MAX_TILE_BYTES}")
        val h = LeSource(raw)
        if (h.u32() != Format.TILE_MAGIC.toLong()) throw TileFormatException("not a tile")
        val payload = h.u16()
        if (payload != Format.PAYLOAD) throw TileFormatException("a tile of payload $payload; this reader knows ${Format.PAYLOAD}")
        val level = h.u8()
        val n = h.u8()
        val x = h.u32()
        val y = h.u32()
        if (level > Format.MAX_LEVEL || x >= (1L shl level) || y >= (1L shl level)) throw TileFormatException("no tile $level/$x/$y")
        if (n != Format.cellsPerSide(level)) throw TileFormatException("$n cells a side at level $level")
        val box = IntArray(4) { h.i32() }
        if (!box.contentEquals(Grid.tileBox(level, x.toInt(), y.toInt()))) throw TileFormatException("tile $level/$x/$y's bounds are not the grid's")
        fun block(at: Long, length: Long): LeSource {
            if (at < 0 || length < 0 || at + length > raw.size) throw TileFormatException("a block lies outside the tile")
            return LeSource(raw, at.toInt(), (at + length).toInt())
        }
        val stringSrc = block(h.u32(), h.u32())
        val cellSrc = block(h.u32(), h.u32())
        val layers = h.u16()
        h.u16()
        var found: LeSource? = null
        var columns = 0
        val counts = IntArray(layers)
        val ids = IntArray(layers)
        for (i in 0 until layers) {
            ids[i] = h.u8()
            h.u8()
            val cols = h.u16()
            val count = h.u32()
            if (count > Format.MAX_FEATURES) throw TileFormatException("$count features in a layer")
            counts[i] = count.toInt()
            val src = block(h.u32(), h.u32())
            for (j in 0 until i) if (ids[j] == ids[i]) throw TileFormatException("layer ${ids[i]} twice")
            if (ids[i] == Layer.HEIGHTS.id) {
                if (count != 1L) throw TileFormatException("$count rasters in a tile, not one")
                found = src
                columns = cols
            }
        }
        val strings = StringTable.read(stringSrc)
        // the cell table: every layer's features add up across the cells
        val sums = IntArray(layers)
        for (c in 0 until n * n) {
            val total = cellSrc.varCount()
            if (total == 0) continue
            repeat(5) { cellSrc.varZ() }
            var sum = 0L
            for (j in 0 until layers) {
                val k = cellSrc.varCount()
                sums[j] += k
                sum += k
            }
            if (sum != total.toLong()) throw TileFormatException("cell $c counts $total features, its layers $sum")
        }
        if (cellSrc.remaining != 0) throw TileFormatException("the cell table has ${cellSrc.remaining} bytes left over")
        for (j in 0 until layers) if (sums[j] != counts[j]) throw TileFormatException("layer ${ids[j]}: the cells count ${sums[j]} features, the layer ${counts[j]}")
        val layer = found ?: throw TileFormatException("tile $level/$x/$y has no heights layer")
        return Decoded(level, x.toInt(), y.toInt(), decodeLayer(layer, columns, strings))
    }

    /** The raster of a heights layer's [columns] columns in [src]. */
    fun decodeLayer(src: LeSource, columns: Int, strings: List<String>): HeightRaster {
        val blocks = HashMap<String, LeSource>()
        repeat(columns) {
            val name = src.varCount().let { i -> if (i in strings.indices) strings[i] else throw TileFormatException("string $i of ${strings.size}") }
            val codec = src.u8()
            val block = src.block()
            val want = CODECS[name] ?: return@repeat // a column this reader does not know
            if (codec != want) throw TileFormatException("heights.$name in codec $codec, not $want")
            if (blocks.put(name, block) != null) throw TileFormatException("heights.$name twice")
        }
        if (src.remaining != 0) throw TileFormatException("the heights layer has ${src.remaining} bytes left over")
        fun <T> read(name: String, absent: T?, take: LeSource.() -> T): T {
            val b = blocks[name] ?: return absent ?: throw TileFormatException("heights.$name is missing")
            val v = b.take()
            if (b.remaining != 0) throw TileFormatException("heights.$name has ${b.remaining} bytes left over")
            return v
        }
        val side = read<Int>(Heights.SIDE, null) { varCount() }
        if (!Heights.sideAllowed(side)) throw TileFormatException("a raster of side $side")
        val apron = read(Heights.APRON_COLUMN, 0) { varCount() }
        if (apron > Heights.MAX_APRON) throw TileFormatException("an apron of $apron samples")
        val step = read<Double>(Heights.STEP, null) { f64() }
        if (!(step > 0) || !step.isFinite()) throw TileFormatException("a step of $step")
        val base = read(Heights.BASE, 0L) { varZ() }
        val max = read<Long>(Heights.MAX, null) { varU() }
        if (max < 0 || max > Heights.MAX_STEPS) throw TileFormatException("a highest sample of $max steps")
        val flags = read(Heights.FLAGS, 0L) { varU() }
        val sources = read(Heights.SOURCES, emptyList()) {
            val k = varCount()
            if (k == 0) emptyList() else List(k - 1) { varCount().let { i -> if (i in strings.indices) strings[i] else throw TileFormatException("string $i of ${strings.size}") } }
        }
        val shares = read(Heights.SHARES, emptyList()) {
            var prev = 0L
            List(varCount().also { if (it > remaining) throw TileFormatException("$it shares in $remaining bytes") }) { prev += varZ(); prev }
        }
        val width = side + 2 * apron
        if (shares.size != sources.size || shares.any { it < 0 || it > width.toLong() * width } || shares.sum() > width.toLong() * width) {
            throw TileFormatException("shares $shares for ${sources.size} sources of ${width * width} samples")
        }
        val samples = (blocks[Heights.SAMPLES] ?: throw TileFormatException("heights.samples is missing")).let { unmed(it, width, max) }
        return HeightRaster(side, apron, step, base, samples, (flags and 0x7FFFFFFF).toInt(), sources, shares)
    }

    /** The codec of each column this reader knows. */
    private val CODECS = mapOf(
        Heights.SIDE to Codec.VARU.id, Heights.APRON_COLUMN to Codec.VARU.id, Heights.STEP to Codec.F64.id, Heights.BASE to Codec.VARZ.id,
        Heights.MAX to Codec.VARU.id, Heights.FLAGS to Codec.VARU.id, Heights.SOURCES to Codec.STRS.id, Heights.SHARES to Codec.IDS.id,
        Heights.SAMPLES to Heights.CODEC_MED,
    )
}
