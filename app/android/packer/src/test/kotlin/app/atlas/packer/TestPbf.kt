package app.atlas.packer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater

/** A small protobuf writer for building PBF files in tests. */
class Proto {
    private val out = ByteArrayOutputStream()

    fun varint(v: Long): Proto {
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
    fun sint(field: Int, v: Long) = tag(field, 0).varint(zz(v))
    fun bytes(field: Int, b: ByteArray) = tag(field, 2).varint(b.size.toLong()).raw(b)
    fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    fun message(field: Int, p: Proto) = bytes(field, p.toByteArray())
    fun packed(field: Int, values: LongArray, signed: Boolean = false): Proto {
        val p = Proto()
        for (v in values) p.varint(if (signed) zz(v) else v)
        return bytes(field, p.toByteArray())
    }

    fun raw(b: ByteArray): Proto { out.write(b); return this }
    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun zz(v: Long) = (v shl 1) xor (v shr 63)
        fun deltas(v: LongArray): LongArray = LongArray(v.size) { if (it == 0) v[0] else v[it] - v[it - 1] }
    }
}

/** Elements to write, in degrees and epoch seconds. */
data class TNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String> = emptyMap(), val time: Long = 0, val version: Int = 1)
data class TWay(val id: Long, val refs: List<Long>, val tags: Map<String, String> = emptyMap(), val time: Long = 0, val version: Int = 1)
data class TMember(val type: Int, val id: Long, val role: String = "")
data class TRelation(val id: Long, val members: List<TMember>, val tags: Map<String, String> = emptyMap(), val time: Long = 0, val version: Int = 1)

/** Writes PBF files the way osmium does (or deliberately not, for the error cases). */
class TestPbf(
    val nodes: List<TNode> = emptyList(),
    val ways: List<TWay> = emptyList(),
    val relations: List<TRelation> = emptyList(),
) {
    var dense = true
    var zlib = true
    var granularity = 100
    var latOffset = 0L
    var lonOffset = 0L
    var dateGranularity = 1000
    var blockSize = 8000
    var packedRefs = true
    var required = listOf("OsmSchema-V0.6", "DenseNodes")
    var optional = listOf("Sort.Type_then_ID")
    var replicationTime = 1_760_000_000L
    var replicationSequence = 4936L
    var bbox: DoubleArray? = doubleArrayOf(4.0, 52.0, 5.0, 53.0)
    var compressionOverride: Int? = null

    /** When set, ways carry their nodes' positions from here (LocationsOnWays); a node missing gets osmium's "undefined". */
    var wayLocations: Map<Long, TNode>? = null

    /** Each element kind in its own blocks, in this order (the caller can interleave by writing several). */
    var order = listOf("nodes", "ways", "relations")

    fun write(file: File): File {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            blob(out, "OSMHeader", header())
            for (kind in order) when (kind) {
                "nodes" -> for (chunk in nodes.chunked(blockSize)) blob(out, "OSMData", if (dense) denseBlock(chunk) else plainNodeBlock(chunk))
                "ways" -> for (chunk in ways.chunked(blockSize)) blob(out, "OSMData", wayBlock(chunk))
                "relations" -> for (chunk in relations.chunked(blockSize)) blob(out, "OSMData", relationBlock(chunk))
            }
        }
        return file
    }

    private fun header(): ByteArray {
        val p = Proto()
        bbox?.let { b ->
            p.message(1, Proto().sint(1, (b[0] * 1e9).toLong()).sint(2, (b[2] * 1e9).toLong()).sint(3, (b[3] * 1e9).toLong()).sint(4, (b[1] * 1e9).toLong()))
        }
        for (f in required) p.string(4, f)
        for (f in optional) p.string(5, f)
        p.string(16, "atlas-test")
        if (replicationTime > 0) p.uint(32, replicationTime)
        p.uint(33, replicationSequence)
        p.string(34, "https://example.org/replication")
        return p.toByteArray()
    }

    private fun blob(out: DataOutputStream, type: String, content: ByteArray) {
        val blob = Proto()
        when {
            compressionOverride != null -> blob.uint(2, content.size.toLong()).bytes(compressionOverride!!, content)
            zlib -> {
                val d = Deflater()
                d.setInput(content)
                d.finish()
                val buf = ByteArray(content.size + 1024)
                val n = d.deflate(buf)
                d.end()
                blob.uint(2, content.size.toLong()).bytes(3, buf.copyOf(n))
            }
            else -> blob.bytes(1, content)
        }
        val body = blob.toByteArray()
        val header = Proto().string(1, type).uint(3, body.size.toLong()).toByteArray()
        out.writeInt(header.size)
        out.write(header)
        out.write(body)
    }

    // ------------------------------------------------------------------------------------------------- blocks

    private class Strings {
        val list = arrayListOf("")
        val index = hashMapOf("" to 0)
        fun of(s: String): Long = index.getOrPut(s) { list += s; list.size - 1 }.toLong()
        fun table(): Proto = Proto().also { p -> for (s in list) p.bytes(1, s.toByteArray()) }
    }

    private fun block(strings: Strings, group: Proto): ByteArray {
        val p = Proto().message(1, strings.table()).message(2, group)
        if (granularity != 100) p.uint(17, granularity.toLong())
        if (dateGranularity != 1000) p.uint(18, dateGranularity.toLong())
        if (latOffset != 0L) p.uint(19, latOffset)
        if (lonOffset != 0L) p.uint(20, lonOffset)
        return p.toByteArray()
    }

    private fun lat(v: Double) = (Math.round(v * 1e9) - latOffset) / granularity
    private fun lon(v: Double) = (Math.round(v * 1e9) - lonOffset) / granularity
    private fun time(seconds: Long) = seconds * 1000 / dateGranularity

    private fun denseBlock(nodes: List<TNode>): ByteArray {
        val s = Strings()
        val kv = ArrayList<Long>()
        for (n in nodes) {
            for ((k, v) in n.tags) { kv += s.of(k); kv += s.of(v) }
            kv += 0
        }
        val info = Proto()
            .packed(1, LongArray(nodes.size) { nodes[it].version.toLong() })
            .packed(2, Proto.deltas(LongArray(nodes.size) { time(nodes[it].time) }), signed = true)
        val dense = Proto()
            .packed(1, Proto.deltas(LongArray(nodes.size) { nodes[it].id }), signed = true)
            .message(5, info)
            .packed(8, Proto.deltas(LongArray(nodes.size) { lat(nodes[it].lat) }), signed = true)
            .packed(9, Proto.deltas(LongArray(nodes.size) { lon(nodes[it].lon) }), signed = true)
        if (nodes.any { it.tags.isNotEmpty() }) dense.packed(10, kv.toLongArray())
        return block(s, Proto().message(2, dense))
    }

    private fun plainNodeBlock(nodes: List<TNode>): ByteArray {
        val s = Strings()
        val g = Proto()
        for (n in nodes) {
            val p = Proto().sint(1, n.id)
            if (n.tags.isNotEmpty()) {
                p.packed(2, n.tags.keys.map { s.of(it) }.toLongArray())
                p.packed(3, n.tags.values.map { s.of(it) }.toLongArray())
            }
            p.message(4, Proto().uint(1, n.version.toLong()).uint(2, time(n.time)))
            p.sint(8, lat(n.lat)).sint(9, lon(n.lon))
            g.message(1, p)
        }
        return block(s, g)
    }

    private fun wayBlock(ways: List<TWay>): ByteArray {
        val s = Strings()
        val g = Proto()
        for (w in ways) {
            val p = Proto().uint(1, w.id)
            if (w.tags.isNotEmpty()) {
                p.packed(2, w.tags.keys.map { s.of(it) }.toLongArray())
                p.packed(3, w.tags.values.map { s.of(it) }.toLongArray())
            }
            p.message(4, Proto().uint(1, w.version.toLong()).uint(2, time(w.time)))
            val d = Proto.deltas(w.refs.toLongArray())
            if (packedRefs) p.packed(8, d, signed = true) else for (v in d) p.sint(8, v)
            wayLocations?.let { at ->
                // osmium writes Location::undefined (2^31 - 1 in 1e-7 degrees) for a node it did not have
                val undefined = Int.MAX_VALUE.toLong() * 100
                fun raw(nano: Long, offset: Long) = (nano - offset) / granularity
                val lats = LongArray(w.refs.size) { i -> at[w.refs[i]]?.let { lat(it.lat) } ?: raw(undefined, latOffset) }
                val lons = LongArray(w.refs.size) { i -> at[w.refs[i]]?.let { lon(it.lon) } ?: raw(undefined, lonOffset) }
                p.packed(9, Proto.deltas(lats), signed = true)
                p.packed(10, Proto.deltas(lons), signed = true)
            }
            g.message(3, p)
        }
        return block(s, g)
    }

    private fun relationBlock(relations: List<TRelation>): ByteArray {
        val s = Strings()
        val g = Proto()
        for (r in relations) {
            val p = Proto().uint(1, r.id)
            if (r.tags.isNotEmpty()) {
                p.packed(2, r.tags.keys.map { s.of(it) }.toLongArray())
                p.packed(3, r.tags.values.map { s.of(it) }.toLongArray())
            }
            p.message(4, Proto().uint(1, r.version.toLong()).uint(2, time(r.time)))
            p.packed(8, r.members.map { s.of(it.role) }.toLongArray())
            p.packed(9, Proto.deltas(r.members.map { it.id }.toLongArray()), signed = true)
            p.packed(10, r.members.map { it.type.toLong() }.toLongArray())
            g.message(4, p)
        }
        return block(s, g)
    }
}
