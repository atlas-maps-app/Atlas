package app.atlas.render.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes a tiled (format 2) `.atlas` pack, for anything from a city to a country: [add] every element, in any order,
 * then [finish] once.
 *
 * Layout: magic, version 2, manifest (JSON), a directory of sections (name, offset, stored and raw lengths as 64-bit,
 * CRC-32, method), then the sections: `tiles` (the chunk table: per chunk its zoom, column and row, stored and raw
 * lengths, element count and the SHA-256 of its inflated bytes; offsets follow from the lengths), `chunks` (one
 * deflated chunk per tile, in level order and along a Hilbert curve within a level, so an area's chunks sit together),
 * `dem:<name>` and `aux:<name>`. [PackTiles] says which tile an element goes in.
 *
 * Each chunk stands alone: its own string and tag-pair tables, then its elements in canonical order (nodes, ways,
 * relations, each by ascending id), coded as format 1 codes them. A chunk's bytes, and so its hash, depend on its
 * elements only, which is what lets a monthly rebuild change only the chunks whose data changed.
 *
 * Memory stays bounded whatever the pack's size: added elements are coded straight away into range partitions of the
 * final chunk order, held in memory up to [memory] bytes and spilled, deflated, to [work] beyond that; [finish] then
 * reads one partition at a time. Lossless, like format 1: the elements read back equal the ones added, in canonical
 * order; a coordinate off the pack's 10^-[digits] degree grid is refused.
 */
class TiledPackWriter(
    private val work: File,
    val digits: Int = Scale.MIN_DIGITS,
    levels: IntArray = PackTiles.LEVELS,
    private val memory: Long = DEFAULT_MEMORY,
) : Closeable {
    private val levels: IntArray = levels.copyOf().also { PackTiles.checkLevels(it) }
    private val scale = Scale(digits.also { require(it in Scale.MIN_DIGITS..Scale.MAX_DIGITS) { "digits must be ${Scale.MIN_DIGITS} to ${Scale.MAX_DIGITS}" } })
    private val encoder = ElementEncoder(Dictionary.EMPTY, scale)
    private val record = ByteSink(1 shl 12)
    private val parts = HashMap<Long, Part>()
    private var buffered = 0L
    private var finished = false
    private var madeWork = false

    var nodes = 0; private set
    var ways = 0; private set
    var relations = 0; private set
    val elements: Int get() = nodes + ways + relations

    /** Bytes of coded elements written to [work] so far (deflated). */
    var spilledBytes = 0L; private set

    /** Partitions so far. */
    val partitions: Int get() = parts.size

    private class Part(val key: Long) {
        var mem = ByteSink(256)
        var file: File? = null
        var rawOnDisk = 0L
    }

    /** Adds one element. */
    fun add(e: OsmElement) {
        check(!finished) { "the pack is finished" }
        var a = Double.POSITIVE_INFINITY
        var b = Double.POSITIVE_INFINITY
        var c = Double.NEGATIVE_INFINITY
        var d = Double.NEGATIVE_INFINITY
        fun on(v: Double) {
            if (!scale.exact(v)) throw PackFormatException("${e.type.json} ${e.id}: the coordinate $v is not on the pack's 1e-$digits degree grid")
        }
        fun add(la: Double, lo: Double) {
            on(la)
            on(lo)
            if (la < a) a = la
            if (la > c) c = la
            if (lo < b) b = lo
            if (lo > d) d = lo
        }
        if (!e.lat.isNaN()) add(e.lat, e.lon)
        e.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
        e.members?.forEach { m ->
            if (m.hasPosition) add(m.lat, m.lon)
            m.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
        }
        e.bounds?.let { bo ->
            on(bo.minLat); on(bo.minLon); on(bo.maxLat); on(bo.maxLon)
            a = bo.minLat; b = bo.minLon; c = bo.maxLat; d = bo.maxLon
        }
        val tile = if (a > c || b > d) PackTiles.world(levels) else PackTiles.place(a, b, c, d, levels)
        record.reset()
        encoder.encodeOne(e, record)
        val li = PackTiles.levelOf(tile)
        val x = PackTiles.xOf(tile)
        val y = PackTiles.yOf(tile)
        val z = levels[li]
        val key = partitionKey(li, z, PackTiles.hilbert(z, x, y))
        val part = parts.getOrPut(key) { Part(key) }
        val before = part.mem.size
        part.mem.varInt(x)
        part.mem.varInt(y)
        part.mem.varInt(record.size)
        part.mem.bytes(record.unsafeArray(), 0, record.size)
        buffered += part.mem.size - before
        when (e.type) {
            ElementType.NODE -> nodes++
            ElementType.WAY -> ways++
            ElementType.RELATION -> relations++
        }
        if (buffered > memory) spill()
    }

    private fun partitionKey(li: Int, z: Int, h: Long): Long {
        val c = minOf(z, PARTITION_ZOOM)
        return (li.toLong() shl 56) or (h ushr (2 * (z - c)))
    }

    private fun workDir(): File {
        if (!work.isDirectory) {
            if (!work.mkdirs() && !work.isDirectory) throw IOException("cannot make $work")
            madeWork = true
        }
        return work
    }

    /** Moves every partition's buffered records to its file in [work], deflated. */
    private fun spill() {
        val dir = workDir()
        val head = ByteSink(16)
        for (p in parts.values) {
            if (p.mem.size == 0) continue
            val f = p.file ?: File(dir, "part-%016x".format(p.key)).also { p.file = it }
            val packed = Zip.deflate(p.mem.unsafeArray(), p.mem.size, Deflater.BEST_SPEED)
            head.reset()
            head.varInt(p.mem.size)
            head.varInt(packed.size)
            FileOutputStream(f, true).use { o ->
                o.write(head.unsafeArray(), 0, head.size)
                o.write(packed)
            }
            spilledBytes += head.size + packed.size
            p.rawOnDisk += p.mem.size
            p.mem = ByteSink(256)
        }
        buffered = 0
    }

    /** A partition's records: what went to disk first, then what is still in memory. */
    private fun load(p: Part): ByteArray {
        val total = p.rawOnDisk + p.mem.size
        if (total > Int.MAX_VALUE - 64) throw PackFormatException("a partition of ${total shr 20} MiB is too big")
        val out = ByteArray(total.toInt())
        var at = 0
        p.file?.let { f ->
            java.io.DataInputStream(FileInputStream(f).buffered(1 shl 16)).use { s ->
                while (true) {
                    val raw = readVarInt(s)
                    if (raw < 0) break
                    val stored = readVarInt(s)
                    // readFully, not readNBytes: the phone's minimum Android has no readNBytes
                    val packed = ByteArray(stored)
                    s.readFully(packed)
                    val inflated = Zip.inflate(packed, raw)
                    System.arraycopy(inflated, 0, out, at, raw)
                    at += raw
                }
            }
        }
        System.arraycopy(p.mem.unsafeArray(), 0, out, at, p.mem.size)
        at += p.mem.size
        if (at != out.size) throw IOException("partition ${p.key}: read $at bytes, not ${out.size}")
        return out
    }

    private fun readVarInt(s: java.io.InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = s.read()
            if (b < 0) {
                if (shift == 0) return -1
                throw IOException("a spill file ends inside a number")
            }
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    /**
     * Writes the pack to [file]: the chunks from what was added, the name, box, configuration, sources, height grids and
     * documents from [spec] (whose element source gives only the extract's header and date here). [log] hears progress.
     */
    fun finish(spec: PackSpec, file: File, log: (String) -> Unit = {}): PackStats {
        check(!finished) { "the pack is finished" }
        finished = true
        val started = System.nanoTime()
        val dir = workDir()
        val chunkFile = File(dir, "chunks.tmp")
        val chunks = ArrayList<TileChunk>()
        val crc = CRC32()
        var chunkBytes = 0L
        val body = ByteSink(1 shl 16)
        val sha = MessageDigest.getInstance("SHA-256")
        val keys = parts.keys.toLongArray().also { it.sort() }
        FileOutputStream(chunkFile).buffered(1 shl 16).use { out ->
            var done = 0
            for (key in keys) {
                val p = parts.remove(key)!!
                val bytes = load(p)
                p.file?.delete()
                p.mem = ByteSink(16)
                encodePartition(key, bytes, body, sha) { z, x, y, count, hash ->
                    val stored = Zip.deflate(body.unsafeArray(), body.size)
                    out.write(stored)
                    crc.update(stored)
                    chunks += TileChunk(z, x, y, chunkBytes, stored.size, body.size, count, hash)
                    chunkBytes += stored.size
                }
                done++
                if (done % 64 == 0) log("chunks: $done of ${keys.size} partitions, ${chunks.size} tiles, ${chunkBytes shr 20} MiB")
            }
        }

        val table = ByteSink(chunks.size * 48 + 16)
        table.varInt(chunks.size)
        val content = MessageDigest.getInstance("SHA-256")
        val c0 = ByteSink(64)
        c0.varInt(digits)
        c0.varInt(levels.size)
        for (z in levels) c0.varInt(z)
        content.update(c0.unsafeArray(), 0, c0.size)
        for (ch in chunks) {
            table.byte(ch.z)
            table.varInt(ch.x)
            table.varInt(ch.y)
            table.varInt(ch.stored)
            table.varInt(ch.raw)
            table.varInt(ch.count)
            val h = ch.sha256
            table.bytes(h)
            c0.reset()
            c0.byte(ch.z)
            c0.varInt(ch.x)
            c0.varInt(ch.y)
            c0.varInt(ch.count)
            c0.bytes(h)
            content.update(c0.unsafeArray(), 0, c0.size)
        }
        val contentHex = content.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        val small = ArrayList<SmallSection>()
        small += SmallSection("tiles", table.toByteArray(), table.size, 0)
        for ((name, dem) in spec.dems) small += packed("dem:$name", ByteSink(1 shl 16).also { DemCodec.encode(dem, it) })
        for ((name, doc) in spec.aux) small += packed("aux:$name", ByteSink(1024).also { it.string(doc.toString()) })

        class Dir(val name: String, val stored: Long, val raw: Long, val crc: Long, val method: Int)
        val order = ArrayList<Dir>()
        order += small[0].let { Dir(it.name, it.stored.size.toLong(), it.raw.toLong(), crcOf(it.stored), it.method) }
        order += Dir("chunks", chunkBytes, chunkBytes, crc.value, 0)
        for (s in small.drop(1)) order += Dir(s.name, s.stored.size.toLong(), s.raw.toLong(), crcOf(s.stored), s.method)

        val manifest = PackManifest.build(
            spec.id, spec.name, spec.bbox, spec.origin, spec.tz, spec.tradition,
            spec.dataTime ?: spec.elements.dataTime, spec.config, spec.elements.header, spec.sources,
            version = PackManifest.TILED,
            extra = buildJsonObject {
                put("counts", buildJsonObject {
                    put("nodes", nodes)
                    put("ways", ways)
                    put("relations", relations)
                    put("elements", elements)
                })
                put("coord_digits", digits)
                put("layout", PackManifest.LAYOUT_TILES)
                put("levels", buildJsonArray { for (z in levels) add(JsonPrimitive(z)) })
                put("chunks", chunks.size)
                put("content_sha256", contentHex)
                put("dems", buildJsonArray { for (k in spec.dems.keys) add(JsonPrimitive(k)) })
                put("aux", buildJsonArray { for (k in spec.aux.keys) add(JsonPrimitive(k)) })
                put("sections", buildJsonObject {
                    for (s in order) put(s.name, buildJsonObject {
                        put("stored", s.stored)
                        put("raw", s.raw)
                    })
                })
                put("builder", "atlas-packer 2")
            },
        )
        val manifestBytes = manifest.json.toString().toByteArray(Charsets.UTF_8)

        fun directory(base: Long): ByteSink {
            val d = ByteSink(256)
            var offset = base
            for (s in order) {
                d.string(s.name)
                d.u64(offset)
                d.u64(s.stored)
                d.u64(s.raw)
                d.u32(s.crc)
                d.byte(s.method)
                offset += s.stored
            }
            return d
        }
        val dirLen = directory(0).size
        val directory = directory((PackWriter.HEADER + manifestBytes.size + dirLen).toLong())
        val head = ByteSink(PackWriter.HEADER)
        head.bytes(PackWriter.MAGIC.toByteArray(Charsets.US_ASCII))
        head.byte(0); head.byte(PackManifest.TILED)
        head.byte(0); head.byte(0)
        head.u32(manifestBytes.size.toLong())
        head.u32(directory.size.toLong())

        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        var total = 0L
        FileOutputStream(tmp).use { fos ->
            val out = BufferedOutputStream(fos, 1 shl 16)
            fun put(b: ByteArray, len: Int = b.size) {
                out.write(b, 0, len)
                total += len
            }
            put(head.unsafeArray(), head.size)
            put(manifestBytes)
            put(directory.unsafeArray(), directory.size)
            put(small[0].stored)
            FileInputStream(chunkFile).use { s -> total += copy(s, out) }
            for (s in small.drop(1)) put(s.stored)
            out.flush()
        }
        chunkFile.delete()
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot move $tmp to $file")
        }
        close()
        log("${chunks.size} tiles, ${total shr 10} KiB, ${spilledBytes shr 20} MiB spilled on the way")
        return PackStats(
            total, elements, chunks.size, nodes, ways, relations, digits,
            order.associate { it.name to (it.stored to it.raw) }, (System.nanoTime() - started) / 1_000_000,
        )
    }

    private class SmallSection(val name: String, val stored: ByteArray, val raw: Int, val method: Int)

    private fun packed(name: String, sink: ByteSink) = SmallSection(name, Zip.deflate(sink.unsafeArray(), sink.size), sink.size, 1)

    private fun crcOf(b: ByteArray): Long = CRC32().also { it.update(b) }.value

    private fun copy(from: java.io.InputStream, to: OutputStream): Long {
        val buf = ByteArray(1 shl 16)
        var n = 0L
        while (true) {
            val k = from.read(buf)
            if (k < 0) return n
            to.write(buf, 0, k)
            n += k
        }
    }

    /**
     * Codes the tiles of one partition: its records sorted by tile, then type, then id (arrival breaking ties), each tile
     * coded into [body] and handed to [emit] with its zoom, column, row, element count and hash.
     */
    private fun encodePartition(key: Long, bytes: ByteArray, body: ByteSink, sha: MessageDigest, emit: (Int, Int, Int, Int, ByteArray) -> Unit) {
        val li = (key ushr 56).toInt()
        val z = levels[li]
        var n = 0
        var xs = IntArray(1024)
        var ys = IntArray(1024)
        var hs = LongArray(1024)
        var ranks = LongArray(1024)
        var at = IntArray(1024)
        val src = ByteSource(bytes)
        while (src.remaining > 0) {
            if (n == xs.size) {
                val m = n * 2
                xs = xs.copyOf(m); ys = ys.copyOf(m); hs = hs.copyOf(m); ranks = ranks.copyOf(m); at = at.copyOf(m)
            }
            val x = src.varInt()
            val y = src.varInt()
            val len = src.varInt()
            val pos = src.pos
            // the record starts with its head byte (type in the low bits) and its id
            val peek = ByteSource(bytes, pos, pos + len)
            val type = peek.byte() and 3
            val id = peek.zigzag()
            xs[n] = x
            ys[n] = y
            hs[n] = PackTiles.hilbert(z, x, y)
            ranks[n] = (type.toLong() shl 61) or (id and 0x1FFFFFFFFFFFFFFFL)
            at[n] = pos
            n++
            src.pos = pos + len
        }
        val order = sortIndices(n) { i, j ->
            val c = hs[i].compareTo(hs[j])
            if (c != 0) c else {
                val r = compareRank(ranks[i], ranks[j])
                if (r != 0) r else at[i].compareTo(at[j])
            }
        }
        val decoder = ElementDecoder(Dictionary.EMPTY, scale)
        val els = ArrayList<OsmElement>()
        var i = 0
        while (i < n) {
            val h = hs[order[i]]
            var j = i
            els.clear()
            while (j < n && hs[order[j]] == h) {
                els += decoder.decodeOne(ByteSource(bytes, at[order[j]]))
                j++
            }
            encodeChunk(els, body)
            sha.reset()
            sha.update(body.unsafeArray(), 0, body.size)
            emit(z, xs[order[i]], ys[order[i]], els.size, sha.digest())
            i = j
        }
    }

    /** Type rank then id, ids compared as signed (negative ids of an editor's extract sort first). */
    private fun compareRank(a: Long, b: Long): Int {
        val ta = (a ushr 61).toInt()
        val tb = (b ushr 61).toInt()
        if (ta != tb) return ta - tb
        // sign-extend the 61-bit id
        val ia = (a shl 3) shr 3
        val ib = (b shl 3) shr 3
        return ia.compareTo(ib)
    }

    /** One chunk: its string table, its tag-pair table, the count, the elements. */
    private fun encodeChunk(els: List<OsmElement>, body: ByteSink) {
        val db = DictionaryBuilder((els.size * 4).coerceIn(64, 1 shl 16))
        for (e in els) {
            e.tags?.forEach { (k, v) -> db.tag(k, v) }
            e.members?.forEach { m -> db.string(m.role) }
        }
        val dict = db.build()
        body.reset()
        dict.writeStrings(body)
        dict.writePairs(body)
        body.varInt(els.size)
        val enc = ElementEncoder(dict, scale)
        for (e in els) enc.encode(e, body)
    }

    /** Deletes the work files; the pack itself stays. */
    override fun close() {
        for (p in parts.values) p.file?.delete()
        parts.clear()
        File(work, "chunks.tmp").delete()
        if (madeWork) work.delete()
    }

    companion object {
        /** Coded elements kept in memory before they go to disk. */
        const val DEFAULT_MEMORY = 96L shl 20

        /** Partitions are the tiles under one tile of this zoom (or the tile itself at coarser levels). */
        const val PARTITION_ZOOM = 11

        /** A stable merge sort of 0 until [n] by [cmp]: the order, without boxing. */
        internal inline fun sortIndices(n: Int, cmp: (Int, Int) -> Int): IntArray {
            var a = IntArray(n) { it }
            var b = IntArray(n)
            var width = 1
            while (width < n) {
                var lo = 0
                while (lo < n) {
                    val mid = minOf(lo + width, n)
                    val hi = minOf(lo + 2 * width, n)
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) b[k++] = if (cmp(a[j], a[i]) < 0) a[j++] else a[i++]
                    while (i < mid) b[k++] = a[i++]
                    while (j < hi) b[k++] = a[j++]
                    lo = hi
                }
                val t = a
                a = b
                b = t
                width *= 2
            }
            return a
        }
    }
}
