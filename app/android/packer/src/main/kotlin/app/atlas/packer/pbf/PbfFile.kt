package app.atlas.packer.pbf

import app.atlas.tiles.ProtoReader
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/** A malformed or unsupported OSM PBF file. */
class PbfFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One blob of a PBF file: its type ("OSMHeader", "OSMData" or another a reader skips) and its `Blob` message, still compressed. */
class PbfBlob(val type: String, val data: ByteArray, val offset: Long)

/**
 * Reads the blobs of an OSM PBF file in order, each a big-endian length, a `BlobHeader` and a `Blob`
 * (https://wiki.openstreetmap.org/wiki/PBF_Format). It does not decompress them: [PbfReader] does that, in parallel.
 */
class PbfFile(val file: File) : Closeable {
    private val input = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20))
    private var position = 0L

    /** The next blob, or null at the end of the file. */
    fun next(): PbfBlob? {
        val start = position
        val headerSize = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (headerSize !in 1..MAX_HEADER) throw PbfFormatException("${file.name}: blob header of $headerSize bytes at $start")
        val header = ByteArray(headerSize)
        readFully(header, start)
        var type: String? = null
        var dataSize = -1
        val r = ProtoReader(header)
        while (r.next()) when (r.field) {
            1 -> type = r.string()
            3 -> dataSize = r.int32()
            else -> r.skip()
        }
        if (type == null || dataSize !in 0..MAX_BLOB) throw PbfFormatException("${file.name}: bad blob header at $start (type $type, $dataSize bytes)")
        val data = ByteArray(dataSize)
        readFully(data, start)
        position += 4L + headerSize + dataSize
        return PbfBlob(type, data, start)
    }

    private fun readFully(b: ByteArray, blobStart: Long) {
        try {
            input.readFully(b)
        } catch (e: EOFException) {
            throw PbfFormatException("${file.name}: truncated blob at $blobStart", e)
        }
    }

    override fun close() = input.close()

    companion object {
        /** The format's limits: a blob header under 64 KiB, a blob under 32 MiB. */
        const val MAX_HEADER = 64 * 1024
        const val MAX_BLOB = 32 * 1024 * 1024
    }
}
