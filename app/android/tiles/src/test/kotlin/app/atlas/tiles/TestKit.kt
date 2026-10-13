package app.atlas.tiles

import app.atlas.geo.TileId
import java.io.ByteArrayOutputStream
import java.io.File

/** The real OpenFreeMap tiles around the Westerkerk in app/android/testdata/omt. */
object Fixtures {
    val dir: File = File(System.getProperty("atlas.testdata") ?: "../testdata", "omt")
    val CENTRE = TileId(14, 8414, 5384)

    fun file(id: TileId) = File(dir, "${id.z}/${id.x}/${id.y}.pbf")
    fun bytes(id: TileId): ByteArray = file(id).readBytes()

    val all: List<TileId> by lazy {
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".pbf") }.map {
            val rel = it.relativeTo(dir).path.removeSuffix(".pbf").split(File.separatorChar).map(String::toInt)
            TileId(rel[0], rel[1], rel[2])
        }.sortedWith(compareBy({ it.z }, { it.x }, { it.y })).toList()
    }

    val z14: List<TileId> get() = all.filter { it.z == 14 }
}

/** A protobuf writer for building test data. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(v: Long): ProtoWriter {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
        return this
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun uint(field: Int, v: Long) = tag(field, 0).varint(v)
    fun sint(field: Int, v: Long) = tag(field, 0).varint((v shl 1) xor (v shr 63))
    fun bytes(field: Int, b: ByteArray) = tag(field, 2).varint(b.size.toLong()).raw(b)
    fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    fun message(field: Int, w: ProtoWriter) = bytes(field, w.toByteArray())
    fun fixed32(field: Int, v: Int) = tag(field, 5).raw(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
    fun fixed64(field: Int, v: Long) = tag(field, 1).raw(ByteArray(8) { (v shr (8 * it)).toByte() })
    fun float(field: Int, f: Float) = fixed32(field, java.lang.Float.floatToIntBits(f))
    fun double(field: Int, d: Double) = fixed64(field, java.lang.Double.doubleToLongBits(d))

    fun packed(field: Int, values: IntArray): ProtoWriter {
        val p = ProtoWriter()
        for (v in values) p.varint(v.toLong() and 0xffffffffL)
        return bytes(field, p.toByteArray())
    }

    fun raw(b: ByteArray): ProtoWriter { out.write(b); return this }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Builds Mapbox Vector Tiles for tests. */
class MvtBuilder {
    private val tile = ProtoWriter()

    fun layer(name: String, extent: Int = 4096, version: Int = 2, block: LayerBuilder.() -> Unit): MvtBuilder {
        val l = LayerBuilder().apply(block)
        val w = ProtoWriter().uint(15, version.toLong()).string(1, name)
        for (f in l.features) w.message(2, f)
        for (k in l.keys) w.string(3, k)
        for (v in l.values) w.message(4, encodeValue(v))
        w.uint(5, extent.toLong())
        tile.message(3, w)
        return this
    }

    fun build(): ByteArray = tile.toByteArray()

    private fun encodeValue(v: Any): ProtoWriter = when (v) {
        is String -> ProtoWriter().string(1, v)
        is Float -> ProtoWriter().float(2, v)
        is Double -> ProtoWriter().double(3, v)
        is Int -> ProtoWriter().uint(4, v.toLong())
        is Long -> if (v < 0) ProtoWriter().sint(6, v) else ProtoWriter().uint(5, v)
        is Boolean -> ProtoWriter().uint(7, if (v) 1 else 0)
        else -> error("unsupported $v")
    }

    class LayerBuilder {
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val features = ArrayList<ProtoWriter>()

        /** A feature of [type] (1 point, 2 line, 3 polygon, 0 unknown) with raw command stream [geometry]. */
        fun feature(type: Int, geometry: IntArray, props: Map<String, Any> = emptyMap(), id: Long? = null, extraTags: IntArray = IntArray(0)) {
            val tags = ArrayList<Int>()
            for ((k, v) in props) {
                var ki = keys.indexOf(k)
                if (ki < 0) { keys += k; ki = keys.size - 1 }
                var vi = values.indexOf(v)
                if (vi < 0) { values += v; vi = values.size - 1 }
                tags += ki; tags += vi
            }
            tags.addAll(extraTags.toList())
            val w = ProtoWriter()
            if (id != null) w.uint(1, id)
            if (tags.isNotEmpty()) w.packed(2, tags.toIntArray())
            w.uint(3, type.toLong())
            w.packed(4, geometry)
            features += w
        }
    }
}

/** Encodes absolute coordinates as MVT geometry commands. */
object Geom {
    private fun cmd(id: Int, count: Int) = (count shl 3) or id
    private fun zz(v: Int) = (v shl 1) xor (v shr 31)

    /** Points as one MoveTo. */
    fun points(vararg xy: Int): IntArray {
        val out = ArrayList<Int>()
        out += cmd(1, xy.size / 2)
        var cx = 0; var cy = 0
        for (i in xy.indices step 2) { out += zz(xy[i] - cx); out += zz(xy[i + 1] - cy); cx = xy[i]; cy = xy[i + 1] }
        return out.toIntArray()
    }

    /** Lines, each given as flat x, y pairs. */
    fun lines(vararg lines: IntArray): IntArray = paths(false, *lines)

    /** Polygon rings (not repeating their first point), each as flat x, y pairs, in the winding given. */
    fun rings(vararg rings: IntArray): IntArray = paths(true, *rings)

    private fun paths(close: Boolean, vararg paths: IntArray): IntArray {
        val out = ArrayList<Int>()
        var cx = 0; var cy = 0
        for (p in paths) {
            out += cmd(1, 1)
            out += zz(p[0] - cx); out += zz(p[1] - cy); cx = p[0]; cy = p[1]
            out += cmd(2, p.size / 2 - 1)
            for (i in 2 until p.size step 2) { out += zz(p[i] - cx); out += zz(p[i + 1] - cy); cx = p[i]; cy = p[i + 1] }
            if (close) out += cmd(7, 1)
        }
        return out.toIntArray()
    }

    /** A square ring from (x, y) with side [s]; clockwise on screen (positive area, an exterior) unless [reverse]. */
    fun square(x: Int, y: Int, s: Int, reverse: Boolean = false): IntArray =
        if (!reverse) intArrayOf(x, y, x + s, y, x + s, y + s, x, y + s) else intArrayOf(x, y, x, y + s, x + s, y + s, x + s, y)
}

/** Builds PMTiles v3 archives for tests: gzip directories, deduplicated and run-length encoded tiles, optional leaves. */
object PmTilesWriter {
    class Entry(val tileId: Long, val offset: Long, val length: Int, var runLength: Int)

    fun write(
        file: File,
        tiles: Map<TileId, ByteArray>,
        leafSize: Int = Int.MAX_VALUE,
        tileCompression: Int = PmTiles.COMPRESSION_GZIP,
        internalCompression: Int = PmTiles.COMPRESSION_GZIP,
        metadata: String = """{"attribution":"test attribution","name":"test"}""",
    ) {
        val sorted = tiles.entries.map { PmTiles.tileId(it.key) to it.value }.sortedBy { it.first }
        val data = ByteArrayOutputStream()
        val byContent = HashMap<String, Pair<Long, Int>>()
        val entries = ArrayList<Entry>()
        for ((tid, raw) in sorted) {
            val body = if (tileCompression == PmTiles.COMPRESSION_GZIP) Gzip.compress(raw) else raw
            val key = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(raw))
            val last = entries.lastOrNull()
            val known = byContent[key]
            if (known != null && last != null && last.offset == known.first && last.tileId + last.runLength == tid) {
                last.runLength++
                continue
            }
            val (off, len) = known ?: (data.size().toLong() to body.size).also { data.write(body); byContent[key] = it }
            entries += Entry(tid, off, len, 1)
        }
        fun compress(b: ByteArray) = if (internalCompression == PmTiles.COMPRESSION_GZIP) Gzip.compress(b) else b
        val rootEntries: List<Entry>
        val leaves = ByteArrayOutputStream()
        if (entries.size <= leafSize) {
            rootEntries = entries
        } else {
            rootEntries = entries.chunked(leafSize).map { chunk ->
                val bytes = compress(directory(chunk))
                val e = Entry(chunk.first().tileId, leaves.size().toLong(), bytes.size, 0)
                leaves.write(bytes)
                e
            }
        }
        val root = compress(directory(rootEntries))
        val meta = compress(metadata.toByteArray())
        val rootOffset = 127L
        val metaOffset = rootOffset + root.size
        val leafOffset = metaOffset + meta.size
        val dataOffset = leafOffset + leaves.size()
        val h = java.nio.ByteBuffer.allocate(127).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()).put(3)
        h.putLong(rootOffset).putLong(root.size.toLong())
        h.putLong(metaOffset).putLong(meta.size.toLong())
        h.putLong(leafOffset).putLong(leaves.size().toLong())
        h.putLong(dataOffset).putLong(data.size().toLong())
        h.putLong(tiles.size.toLong()).putLong(entries.size.toLong()).putLong(byContent.size.toLong())
        h.put(1).put(internalCompression.toByte()).put(tileCompression.toByte()).put(1)
        val zs = tiles.keys.map { it.z }
        h.put(zs.min().toByte()).put(zs.max().toByte())
        h.putInt(48_000_000).putInt(520_000_000).putInt(50_000_000).putInt(530_000_000)
        h.put(14).putInt(48_838_000).putInt(523_745_000)
        file.outputStream().use {
            it.write(h.array())
            it.write(root)
            it.write(meta)
            leaves.writeTo(it)
            data.writeTo(it)
        }
    }

    fun directory(entries: List<Entry>): ByteArray {
        val w = ProtoWriter()
        w.varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { w.varint(e.tileId - last); last = e.tileId }
        for (e in entries) w.varint(e.runLength.toLong())
        for (e in entries) w.varint(e.length.toLong())
        for ((i, e) in entries.withIndex()) {
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) w.varint(0) else w.varint(e.offset + 1)
        }
        return w.toByteArray()
    }
}

/** A directory of its own under the build's temporary folder for one test. */
fun tempDir(name: String): File =
    kotlin.io.path.createTempDirectory("atlas-tiles-$name").toFile().also { it.deleteOnExit() }
