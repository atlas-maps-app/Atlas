package app.atlas.tiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Random access to the bytes of an archive, local or remote. */
interface ByteRangeSource : AutoCloseable {
    /** A short name for messages. */
    val name: String

    /** Up to [length] bytes from [offset]; fewer only where the data ends. */
    suspend fun read(offset: Long, length: Int): ByteArray

    override fun close() {}
}

/** A local file read with positional reads, safe from many coroutines at once. */
class FileRangeSource(val file: File) : ByteRangeSource {
    private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    override val name: String get() = file.name
    val size: Long get() = channel.size()

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) { readBlocking(offset, length) }

    fun readBlocking(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "bad range $offset+$length" }
        val n = minOf(length.toLong(), (channel.size() - offset).coerceAtLeast(0)).toInt()
        val out = ByteArray(n)
        val bb = ByteBuffer.wrap(out)
        var p = offset
        while (bb.hasRemaining()) {
            val r = channel.read(bb, p)
            if (r < 0) break
            p += r
        }
        return if (bb.position() == n) out else out.copyOf(bb.position())
    }

    override fun close() = channel.close()
}

/**
 * A remote file read with HTTP range requests. It notes the first ETag it sees and fails when the file changes under it,
 * so a reader never mixes two versions of an archive.
 */
class HttpRangeSource(
    val url: String,
    private val client: OkHttpClient = Http.client,
) : ByteRangeSource {
    override val name: String get() = url.substringAfterLast('/')

    @Volatile
    private var etag: String? = null

    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "bad range $offset+$length" }
        if (length == 0) return ByteArray(0)
        val request = Http.request(url)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("Accept-Encoding", "identity")
            .build()
        val r = try {
            client.newCall(request).read(maxBytes = length.toLong() + 4096)
        } catch (e: IOException) {
            if (e is HttpStatusException) throw e
            throw IOException("reading $name at $offset+$length: ${e.message}", e)
        }
        val tag = r.header("ETag")
        if (tag != null) {
            val known = etag
            if (known == null) etag = tag
            else if (known != tag) throw IOException("$name changed while reading (ETag $known, now $tag)")
        }
        return when (r.code) {
            206 -> r.body
            // The server ignored the range and sent the whole (small) file: take the part asked for.
            200 -> if (offset < r.body.size) r.body.copyOfRange(offset.toInt(), minOf(r.body.size.toLong(), offset + length).toInt()) else ByteArray(0)
            416 -> ByteArray(0)
            else -> throw HttpStatusException(r.code, "HTTP ${r.code} reading $name at $offset")
        }
    }
}
