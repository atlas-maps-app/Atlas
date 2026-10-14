package app.atlas.packer.pbf

import app.atlas.geo.BBox
import app.atlas.tiles.ProtoReader
import java.time.Instant
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * What a PBF file says about itself in its `HeaderBlock`: the area it covers, the features a reader must support, the
 * program that wrote it and, for Geofabrik's extracts, the replication time the data is current to.
 */
data class PbfHeader(
    val bbox: BBox?,
    val requiredFeatures: List<String>,
    val optionalFeatures: List<String>,
    val writingProgram: String?,
    val source: String?,
    /** When the data was current (osmosis_replication_timestamp). */
    val replicationTime: Instant?,
    val replicationSequence: Long?,
    val replicationUrl: String?,
) {
    /** True when the writer promises nodes, then ways, then relations, each by ascending id. */
    val sortedByTypeThenId: Boolean get() = "Sort.Type_then_ID" in optionalFeatures

    /** Throws for a file this reader cannot read correctly: history files and unknown required features. */
    fun checkSupported() {
        val unsupported = requiredFeatures.filter { it !in SUPPORTED }
        if (unsupported.isNotEmpty()) throw PbfFormatException("unsupported PBF features: ${unsupported.joinToString()}")
    }

    companion object {
        /** The required features this reader handles; "HistoricalInformation" (history files) is not among them. */
        val SUPPORTED = setOf("OsmSchema-V0.6", "DenseNodes", "LocationsOnWays")

        /** Parses an inflated `HeaderBlock`. */
        fun parse(b: ByteArray): PbfHeader {
            var bbox: BBox? = null
            val required = ArrayList<String>()
            val optional = ArrayList<String>()
            var program: String? = null
            var source: String? = null
            var time: Instant? = null
            var sequence: Long? = null
            var url: String? = null
            val r = ProtoReader(b)
            while (r.next()) when (r.field) {
                1 -> {
                    val old = r.pushLimit(r.length())
                    var left = 0L
                    var right = 0L
                    var top = 0L
                    var bottom = 0L
                    while (r.next()) when (r.field) {
                        1 -> left = r.sint64()
                        2 -> right = r.sint64()
                        3 -> top = r.sint64()
                        4 -> bottom = r.sint64()
                        else -> r.skip()
                    }
                    r.popLimit(old)
                    bbox = BBox(left / GIGA, bottom / GIGA, right / GIGA, top / GIGA)
                }
                4 -> required += r.string()
                5 -> optional += r.string()
                16 -> program = r.string()
                17 -> source = r.string()
                32 -> time = r.int64().takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
                33 -> sequence = r.int64()
                34 -> url = r.string()
                else -> r.skip()
            }
            return PbfHeader(bbox, required, optional, program, source, time, sequence, url)
        }

        // nanodegrees to degrees by division: the double nearest the decimal value
        private const val GIGA = 1e9
    }
}

/** Opens `Blob` messages: raw or zlib; the other compressions a PBF may use fail clearly. */
object BlobCodec {
    /** The uncompressed content of [blob], inflated with [inflater] (reset first, so one per thread can be reused). */
    fun decode(blob: ByteArray, inflater: Inflater): ByteArray {
        var rawSize = -1
        var rawStart = -1
        var rawEnd = -1
        var zStart = -1
        var zEnd = -1
        var other: String? = null
        val r = ProtoReader(blob)
        while (r.next()) when (r.field) {
            1 -> { val n = r.length(); rawStart = r.pos; rawEnd = rawStart + n; r.pos = rawEnd }
            2 -> rawSize = r.int32()
            3 -> { val n = r.length(); zStart = r.pos; zEnd = zStart + n; r.pos = zEnd }
            4 -> { other = "LZMA"; r.skip() }
            5 -> { other = "bzip2"; r.skip() }
            6 -> { other = "LZ4"; r.skip() }
            7 -> { other = "Zstandard"; r.skip() }
            else -> r.skip()
        }
        if (rawStart >= 0) return blob.copyOfRange(rawStart, rawEnd)
        if (zStart < 0) {
            throw PbfFormatException(
                if (other != null) "$other-compressed PBF blobs are not supported; convert with `osmium cat -o out.osm.pbf -f pbf,pbf_compression=zlib`"
                else "a PBF blob without data",
            )
        }
        if (rawSize !in 0..PbfFile.MAX_BLOB) throw PbfFormatException("a zlib blob declares $rawSize bytes")
        val out = ByteArray(rawSize)
        inflater.reset()
        inflater.setInput(blob, zStart, zEnd - zStart)
        var n = 0
        try {
            while (n < rawSize) {
                val k = inflater.inflate(out, n, rawSize - n)
                if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
        } catch (e: DataFormatException) {
            throw PbfFormatException("a zlib blob is corrupt: ${e.message}", e)
        }
        if (n != rawSize) throw PbfFormatException("a zlib blob inflated to $n bytes, not the $rawSize it declares")
        return out
    }
}
