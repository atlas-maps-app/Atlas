package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.providers.PmTiles
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Writes what OpenAddresses serves, for the tests: vector tiles of address points (one layer, "data", as tippecanoe
 * names it) and a PMTiles archive of them, its tile entries in a leaf directory under the root.
 */
object TestArchive {
    /** A point at ([fx], [fy]) of its tile (0 to 1 from the north-west corner; outside it is the tile's buffer). */
    class Point(val fx: Double, val fy: Double, val attributes: Map<String, String>)

    const val EXTENT = 4096

    /** Where ([fx], [fy]) of tile [t] lies. */
    fun at(t: TileId, fx: Double, fy: Double): LatLon {
        val n = (1 shl t.z).toDouble()
        return LatLon(Mercator.lat((t.y + fy) / n), Mercator.lon((t.x + fx) / n))
    }

    fun tile(points: List<Point>): ByteArray {
        val keys = LinkedHashMap<String, Int>()
        val values = LinkedHashMap<String, Int>()
        val layer = ByteArrayOutputStream()
        tag(layer, 15, 0); varint(layer, 2)
        bytes(layer, 1, "data".toByteArray())
        for (p in points) {
            val feature = ByteArrayOutputStream()
            val tags = ByteArrayOutputStream()
            for ((k, v) in p.attributes) {
                varint(tags, keys.getOrPut(k) { keys.size }.toLong())
                varint(tags, values.getOrPut(v) { values.size }.toLong())
            }
            bytes(feature, 2, tags.toByteArray())
            tag(feature, 3, 0); varint(feature, 1)
            val geometry = ByteArrayOutputStream()
            varint(geometry, ((1 shl 3) or 1).toLong())
            varint(geometry, zigzag((p.fx * EXTENT).toInt()))
            varint(geometry, zigzag((p.fy * EXTENT).toInt()))
            bytes(feature, 4, geometry.toByteArray())
            bytes(layer, 2, feature.toByteArray())
        }
        for (k in keys.keys) bytes(layer, 3, k.toByteArray())
        for (v in values.keys) {
            val value = ByteArrayOutputStream()
            bytes(value, 1, v.toByteArray())
            bytes(layer, 4, value.toByteArray())
        }
        tag(layer, 5, 0); varint(layer, EXTENT.toLong())
        val out = ByteArrayOutputStream()
        bytes(out, 3, layer.toByteArray())
        return out.toByteArray()
    }

    /** An archive of [tiles] at zoom 14, gzipped as OpenAddresses' are. */
    fun archive(tiles: Map<TileId, ByteArray>): ByteArray {
        val packed = tiles.entries.map { (t, b) -> PmTiles.tileId(t.z, t.x, t.y) to gzip(b) }.sortedBy { it.first }
        val data = ByteArrayOutputStream()
        val entries = ArrayList<LongArray>()
        for ((id, b) in packed) {
            entries += longArrayOf(id, data.size().toLong(), b.size.toLong(), 1)
            data.write(b)
        }
        val leaf = gzip(directory(entries))
        val root = gzip(directory(listOf(longArrayOf(entries.first()[0], 0, leaf.size.toLong(), 0))))
        val meta = gzip("{}".toByteArray())
        val rootAt = PmTiles.HEADER_BYTES.toLong()
        val metaAt = rootAt + root.size
        val leafAt = metaAt + meta.size
        val dataAt = leafAt + leaf.size
        val h = ByteBuffer.allocate(PmTiles.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()).put(3)
        for (v in longArrayOf(rootAt, root.size.toLong(), metaAt, meta.size.toLong(), leafAt, leaf.size.toLong(), dataAt, data.size().toLong(), packed.size.toLong(), packed.size.toLong(), packed.size.toLong())) h.putLong(v)
        h.put(1).put(PmTiles.GZIP.toByte()).put(PmTiles.GZIP.toByte()).put(PmTiles.MVT.toByte()).put(0).put(14)
        val boxes = tiles.keys.map { it.bbox }
        for (v in listOf(boxes.minOf { it.west }, boxes.minOf { it.south }, boxes.maxOf { it.east }, boxes.maxOf { it.north })) h.putInt(Math.round(v * 1e7).toInt())
        h.put(14).putInt(0).putInt(0)
        return h.array() + root + meta + leaf + data.toByteArray()
    }

    private fun directory(entries: List<LongArray>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(out, e[0] - last); last = e[0] }
        for (e in entries) varint(out, e[3])
        for (e in entries) varint(out, e[2])
        for ((i, e) in entries.withIndex()) {
            val running = i > 0 && e[1] == entries[i - 1][1] + entries[i - 1][2]
            varint(out, if (running) 0 else e[1] + 1)
        }
        return out.toByteArray()
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun zigzag(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong() and 0xffffffffL

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())

    private fun bytes(out: ByteArrayOutputStream, field: Int, b: ByteArray) {
        tag(out, field, 2)
        varint(out, b.size.toLong())
        out.write(b)
    }

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
