package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Raw DEFLATE, as the sections of a pack use it. */
internal object Zip {
    fun deflate(src: ByteArray, len: Int = src.size, level: Int = Deflater.BEST_COMPRESSION): ByteArray {
        val d = Deflater(level, true)
        try {
            d.setInput(src, 0, len)
            d.finish()
            val out = ByteSink(len / 3 + 64)
            val tmp = ByteArray(1 shl 14)
            while (!d.finished()) {
                val n = d.deflate(tmp)
                out.bytes(tmp, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    fun inflate(src: ByteArray, rawLen: Int): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(src)
            val out = ByteArray(rawLen)
            var n = 0
            while (n < rawLen) {
                val k = inf.inflate(out, n, rawLen - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n != rawLen) throw PackFormatException("a section inflates to $n bytes, not $rawLen")
            return out
        } finally {
            inf.end()
        }
    }
}

/** What goes into a region pack. */
class PackSpec(
    val id: String,
    val name: String,
    val bbox: BBox,
    val origin: LatLon,
    val tz: String?,
    val tradition: String?,
    /** The region's entry of `places.py` as JSON, views included. */
    val config: JsonObject,
    val elements: ElementSource,
    val sources: List<String> = emptyList(),
    /** Height grids by name (`dem`, `dem5`). */
    val dems: Map<String, Dem> = emptyMap(),
    /** Small JSON documents by name (`wx`, `sea`, a park's ride profiles). */
    val aux: Map<String, JsonElement> = emptyMap(),
    /** Overrides the data date taken from the extract's `osm3s.timestamp_osm_base`. */
    val dataTime: String? = null,
)

/** What [PackWriter] made. */
class PackStats(
    val bytes: Long,
    val elements: Int,
    val chunks: Int,
    val nodes: Int,
    val ways: Int,
    val relations: Int,
    val digits: Int,
    val sections: Map<String, Pair<Int, Int>>,
    val millis: Long,
)

/**
 * Writes a `.atlas` region pack.
 *
 * Layout: magic, version, manifest (JSON, readable), a directory of sections (name, offset, stored and raw lengths,
 * CRC-32, method), then the sections: `strings` and `pairs` (the shared tag tables), `index` (every element's box and
 * its place in the extract's order), `chtab` (chunk sizes), `chunks` (elements grouped along a Hilbert curve, 256 to a
 * chunk, each chunk deflated on its own), `dem:<name>` and `aux:<name>`.
 *
 * It is lossless: the elements read back equal the ones written, in the extract's order. Coordinates are stored as
 * integers at 1e-7 degrees (finer when an extract needs it) and an extract whose coordinates fit no such grid is refused.
 */
object PackWriter {
    const val MAGIC = "ATLASPCK"
    const val CHUNK_SIZE = 256

    fun write(spec: PackSpec, file: File, chunkSize: Int = CHUNK_SIZE): PackStats {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        val stats = tmp.outputStream().buffered(1 shl 16).use { write(spec, it, chunkSize) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot move $tmp to $file")
        }
        return stats
    }

    fun toBytes(spec: PackSpec, chunkSize: Int = CHUNK_SIZE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        write(spec, out, chunkSize)
        return out.toByteArray()
    }

    private class Section(val name: String, val stored: ByteArray, val raw: Int, val method: Int)

    fun write(spec: PackSpec, out: OutputStream, chunkSize: Int = CHUNK_SIZE): PackStats {
        val started = System.nanoTime()
        require(chunkSize in 1..65535) { "a chunk holds 1 to 65535 elements" }

        // pass 1: the strings, the coordinate grid, every element's box
        val db = DictionaryBuilder()
        var n = 0
        var nodes = 0
        var ways = 0
        var relations = 0
        var minLat = DoubleArray(1024)
        var minLon = DoubleArray(1024)
        var maxLat = DoubleArray(1024)
        var maxLon = DoubleArray(1024)
        val scales = Array(Scale.MAX_DIGITS + 1) { Scale(it) }
        var digits = Scale.MIN_DIGITS
        fun check(v: Double) {
            if (v.isNaN()) return
            while (digits <= Scale.MAX_DIGITS && !scales[digits].exact(v)) digits++
            if (digits > Scale.MAX_DIGITS) throw PackFormatException("a coordinate ($v) does not fit a 1e-9 degree grid")
        }
        for (e in spec.elements.elements()) {
            if (n == minLat.size) {
                minLat = minLat.copyOf(n * 2)
                minLon = minLon.copyOf(n * 2)
                maxLat = maxLat.copyOf(n * 2)
                maxLon = maxLon.copyOf(n * 2)
            }
            when (e.type) {
                ElementType.NODE -> nodes++
                ElementType.WAY -> ways++
                ElementType.RELATION -> relations++
            }
            e.tags?.forEach { (k, v) -> db.tag(k, v) }
            var a = Double.POSITIVE_INFINITY
            var b = Double.POSITIVE_INFINITY
            var c = Double.NEGATIVE_INFINITY
            var d = Double.NEGATIVE_INFINITY
            fun add(la: Double, lo: Double) {
                check(la)
                check(lo)
                if (la < a) a = la
                if (la > c) c = la
                if (lo < b) b = lo
                if (lo > d) d = lo
            }
            if (!e.lat.isNaN()) add(e.lat, e.lon)
            e.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
            e.members?.forEach { m ->
                db.string(m.role)
                if (m.hasPosition) add(m.lat, m.lon)
                m.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
            }
            e.bounds?.let { bo ->
                check(bo.minLat); check(bo.minLon); check(bo.maxLat); check(bo.maxLon)
                a = bo.minLat; b = bo.minLon; c = bo.maxLat; d = bo.maxLon
            }
            minLat[n] = a; minLon[n] = b; maxLat[n] = c; maxLon[n] = d
            n++
        }
        val scale = scales[digits]
        val dict = db.build()

        // the order of the chunks: along a Hilbert curve through the boxes' centres
        var w0 = Double.POSITIVE_INFINITY
        var w1 = Double.NEGATIVE_INFINITY
        var s0 = Double.POSITIVE_INFINITY
        var s1 = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            if (minLat[i] > maxLat[i]) continue
            val cx = (minLon[i] + maxLon[i]) / 2
            val cy = (minLat[i] + maxLat[i]) / 2
            if (cx < w0) w0 = cx
            if (cx > w1) w1 = cx
            if (cy < s0) s0 = cy
            if (cy > s1) s1 = cy
        }
        val sx = if (w1 > w0) 65535.0 / (w1 - w0) else 0.0
        val sy = if (s1 > s0) 65535.0 / (s1 - s0) else 0.0
        val keys = LongArray(n)
        for (i in 0 until n) {
            val h = if (minLat[i] > maxLat[i]) 0xFFFFFFFFL
            else hilbert(((((minLon[i] + maxLon[i]) / 2) - w0) * sx).toInt(), ((((minLat[i] + maxLat[i]) / 2) - s0) * sy).toInt())
            keys[i] = ((h ushr 1) shl 32) or i.toLong()
        }
        keys.sort()
        val chunkOf = IntArray(n)
        for (rank in 0 until n) chunkOf[(keys[rank] and 0xFFFFFFFFL).toInt()] = rank / chunkSize
        val chunks = (n + chunkSize - 1) / chunkSize
        val chunkCount = IntArray(chunks) { minOf(chunkSize, n - it * chunkSize) }

        // pass 2: each element into its chunk, in the extract's order
        val sinks = Array(chunks) { ByteSink(1024) }
        val encoders = Array(chunks) { ElementEncoder(dict, scale) }
        val seen = IntArray(chunks)
        val ordinalAt = IntArray(n)
        var ordinal = 0
        for (e in spec.elements.elements()) {
            val c = chunkOf[ordinal]
            encoders[c].encode(e, sinks[c])
            ordinalAt[c * chunkSize + seen[c]++] = ordinal
            ordinal++
        }
        if (ordinal != n) throw PackFormatException("the element source changed between passes ($n then $ordinal)")

        val chunkBytes = ByteSink(1 shl 20)
        val chtab = ByteSink(chunks * 6 + 8)
        chtab.varInt(chunks)
        for (c in 0 until chunks) {
            val body = ByteSink(sinks[c].size + 4)
            body.varInt(chunkCount[c])
            body.bytes(sinks[c].unsafeArray(), 0, sinks[c].size)
            val packed = Zip.deflate(body.unsafeArray(), body.size)
            chtab.varInt(packed.size)
            chtab.varInt(body.size)
            chunkBytes.bytes(packed)
            sinks[c] = ByteSink(16)
        }

        // the index: ordinals and boxes in chunk order
        val index = ByteSink(n * 8 + 64)
        index.varInt(n)
        index.varInt(chunkSize)
        index.byte(digits)
        val noBox = ArrayList<Int>()
        for (s in 0 until n) if (minLat[ordinalAt[s]] > maxLat[ordinalAt[s]]) noBox += s
        index.varInt(noBox.size)
        var prev = -1
        for (s in noBox) {
            index.varInt(s - prev - 1)
            prev = s
        }
        var lastOrdinal = 0L
        var lastLat = 0L
        var lastLon = 0L
        for (s in 0 until n) {
            val o = ordinalAt[s]
            index.zigzag(o - lastOrdinal)
            lastOrdinal = o.toLong()
            if (minLat[o] > maxLat[o]) continue
            val la = scale.fixed(minLat[o])
            val lo = scale.fixed(minLon[o])
            index.zigzag(la - lastLat)
            index.zigzag(lo - lastLon)
            index.varLong(scale.fixed(maxLat[o]) - la)
            index.varLong(scale.fixed(maxLon[o]) - lo)
            lastLat = la
            lastLon = lo
        }

        val sections = ArrayList<Section>()
        fun packed(name: String, sink: ByteSink) {
            sections.add(Section(name, Zip.deflate(sink.unsafeArray(), sink.size), sink.size, 1))
        }
        fun stored(name: String, sink: ByteSink) {
            sections.add(Section(name, sink.toByteArray(), sink.size, 0))
        }
        val strings = ByteSink(1 shl 16).also { dict.writeStrings(it) }
        val pairs = ByteSink(1 shl 12).also { dict.writePairs(it) }
        packed("strings", strings)
        packed("pairs", pairs)
        packed("index", index)
        stored("chtab", chtab)
        sections.add(Section("chunks", chunkBytes.toByteArray(), chunkBytes.size, 0))
        for ((name, dem) in spec.dems) packed("dem:$name", ByteSink(1 shl 16).also { DemCodec.encode(dem, it) })
        for ((name, doc) in spec.aux) packed("aux:$name", ByteSink(1024).also { it.string(doc.toString()) })

        val header = spec.elements.header
        val sizes = buildJsonObject {
            for (s in sections) put(s.name, buildJsonObject {
                put("stored", s.stored.size)
                put("raw", s.raw)
            })
        }
        val manifest = PackManifest.build(
            spec.id, spec.name, spec.bbox, spec.origin, spec.tz, spec.tradition,
            spec.dataTime ?: spec.elements.dataTime, spec.config, header, spec.sources,
            extra = buildJsonObject {
                put("counts", buildJsonObject {
                    put("nodes", nodes)
                    put("ways", ways)
                    put("relations", relations)
                    put("elements", n)
                })
                put("coord_digits", digits)
                put("chunk_size", chunkSize)
                put("dems", buildJsonArray { for (k in spec.dems.keys) add(JsonPrimitive(k)) })
                put("aux", buildJsonArray { for (k in spec.aux.keys) add(JsonPrimitive(k)) })
                put("sections", sizes)
                put("builder", "atlas-packer 1")
            },
        )
        val manifestBytes = manifest.json.toString().toByteArray(Charsets.UTF_8)

        // directory: offsets are known once its own size is
        fun directory(base: Long): ByteSink {
            val d = ByteSink(256)
            var offset = base
            for (s in sections) {
                d.string(s.name)
                d.u64(offset)
                d.u32(s.stored.size.toLong())
                d.u32(s.raw.toLong())
                val crc = CRC32().also { it.update(s.stored) }
                d.u32(crc.value)
                d.byte(s.method)
                offset += s.stored.size
            }
            return d
        }
        val dirLen = directory(0).size
        val dir = directory((HEADER + manifestBytes.size + dirLen).toLong())
        val head = ByteSink(HEADER)
        head.bytes(MAGIC.toByteArray(Charsets.US_ASCII))
        head.byte(0); head.byte(PackManifest.VERSION)
        head.byte(0); head.byte(0)
        head.u32(manifestBytes.size.toLong())
        head.u32(dir.size.toLong())
        out.write(head.unsafeArray(), 0, head.size)
        out.write(manifestBytes)
        out.write(dir.unsafeArray(), 0, dir.size)
        var total = (head.size + manifestBytes.size + dir.size).toLong()
        for (s in sections) {
            out.write(s.stored)
            total += s.stored.size
        }
        out.flush()
        return PackStats(
            total, n, chunks, nodes, ways, relations, digits,
            sections.associate { it.name to (it.stored.size to it.raw) }, (System.nanoTime() - started) / 1_000_000,
        )
    }

    /** The bytes before the manifest: magic (8), version (2), flags (2), manifest length (4), directory length (4). */
    internal const val HEADER = 20

    /** Position along a Hilbert curve of the 65536 x 65536 grid cell (x, y). */
    internal fun hilbert(x0: Int, y0: Int): Long {
        val n = 65536
        var x = x0.coerceIn(0, n - 1)
        var y = y0.coerceIn(0, n - 1)
        var d = 0L
        var s = n shr 1
        while (s > 0) {
            val rx = if (x and s != 0) 1 else 0
            val ry = if (y and s != 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            if (ry == 0) {
                if (rx == 1) {
                    x = n - 1 - x
                    y = n - 1 - y
                }
                val t = x
                x = y
                y = t
            }
            s = s shr 1
        }
        return d
    }
}
