package app.atlas.tiles

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Saves a pack's file to disk: part by part into `<target>.download`, resuming where an interrupted download stopped,
 * checked against the published SHA-256, and a gzipped store unpacked. Pack files sit in dated releases and do not
 * change, so a partial file can be resumed; `<target>.download.id` names the file it belongs to, so the partial
 * download of an older pack is started over rather than finished with another file's bytes, and a checksum mismatch
 * throws the partial file away.
 */
class PackDownloader(client: OkHttpClient = Http.client) {
    // a pack file takes minutes: no limit on the whole call, only on each wait for bytes. Nor through the app's HTTP
    // cache (64 MB): OkHttp copies every whole answer into it as it comes, so a country's 780 MB of tiles took as much
    // again on the phone's disk until the download ended, and then pushed every answer the cache kept out of it; GitHub's
    // signed storage addresses never match again, so nothing it kept of a pack was ever read back.
    private val client = client.newBuilder().cache(null).callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Bytes done of the download's total. */
    data class Progress(val done: Long, val total: Long) {
        val fraction: Double get() = if (total <= 0) 1.0 else done.toDouble() / total
    }

    /** Thrown when the bytes downloaded are not the file published. */
    class ChecksumException(message: String) : IOException(message)

    /** Downloads [file] and returns [target]: the PMTiles or the unpacked store. Cancelling stops it at once. */
    suspend fun download(file: PackFile, target: File, progress: (Progress) -> Unit = {}): File = withContext(Dispatchers.IO) {
        target.absoluteFile.parentFile?.mkdirs()
        val partial = File(target.path + ".download")
        val marker = File(target.path + ".download.id")
        // a partial download of another file (an older pack at the same place) cannot be resumed
        val identity = identityOf(file)
        val known = if (marker.isFile) runCatching { marker.readText() }.getOrNull() else null
        if (partial.length() > file.bytes || (known != null && known != identity)) partial.delete()
        marker.writeText(identity)
        var start = 0L
        for (p in file.parts) {
            val end = start + p.bytes
            if (partial.length() < end) fetch(p, start, partial, file.bytes, progress)
            start = end
        }
        progress(Progress(partial.length(), file.bytes))
        if (partial.length() != file.bytes) throw IOException("${file.name}: ${partial.length()} bytes downloaded, ${file.bytes} published")
        file.sha256?.let { want ->
            val got = sha256(partial)
            if (!got.equals(want, ignoreCase = true)) {
                partial.delete()
                marker.delete()
                throw ChecksumException("${file.name}: SHA-256 $got, published $want")
            }
        }
        if (file.compression == "gzip") {
            val tmp = File(target.path + ".unpacking")
            try {
                GZIPInputStream(partial.inputStream().buffered(BUFFER), BUFFER).use { input ->
                    tmp.outputStream().buffered(BUFFER).use { out -> input.copyTo(out, BUFFER) }
                }
                file.rawBytes?.let { if (tmp.length() != it) throw IOException("${file.name} unpacked to ${tmp.length()} bytes, $it published") }
                replace(tmp, target)
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            partial.delete()
        } else {
            replace(partial, target)
        }
        marker.delete()
        target
    }

    /** Appends part [p], which starts at [start] in the whole file, to [partial] from wherever it stopped. */
    private suspend fun fetch(p: PackPart, start: Long, partial: File, total: Long, progress: (Progress) -> Unit) {
        val have = (partial.length() - start).coerceAtLeast(0)
        val req = Http.request(p.url).header("Accept-Encoding", "identity")
        if (have > 0) req.header("Range", "bytes=$have-")
        client.newCall(req.build()).executeCancellably { r ->
            val from = when (r.code) {
                200 -> 0L
                // a 206 must start where it was asked to; one from the start (a confused proxy) restarts the part
                206 -> (rangeStart(r.header("Content-Range")).takeIf { it >= 0 } ?: have).let { if (it == have || it == 0L) it else -1L }
                else -> throw HttpStatusException(r.code, "HTTP ${r.code} downloading ${p.url}")
            }
            if (from < 0) throw IOException("${p.url}: asked for bytes from $have, got ${r.header("Content-Range")}")
            // a server that ignored the range sends the part from its start
            RandomAccessFile(partial, "rw").use { it.setLength(start + from) }
            val source = r.body.source()
            val buf = ByteArray(BUFFER)
            // never past the part's end, whatever the server sends: the next part starts there
            var left = p.bytes - from
            FileOutputStream(partial, true).use { out ->
                var since = 0L
                while (left > 0) {
                    val n = source.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                    since += n
                    if (since >= PROGRESS_EVERY) {
                        since = 0
                        progress(Progress(partial.length(), total))
                    }
                }
            }
        }
        if (partial.length() != start + p.bytes) throw IOException("${p.url}: part ended at ${partial.length() - start} of ${p.bytes} bytes")
    }

    /**
     * Runs the call on this thread and hands [block] the response; cancelling the coroutine cancels the call, which
     * ends a read blocked on a stalled connection at once rather than at the read timeout.
     */
    private suspend fun <T> Call.executeCancellably(block: (Response) -> T): T = coroutineScope {
        val call = this@executeCancellably
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } catch (e: IOException) {
            // a call that failed because the coroutine was cancelled is a cancellation, not a network failure
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private fun replace(from: File, to: File) {
        if (from.renameTo(to)) return
        to.delete()
        if (!from.renameTo(to)) throw IOException("cannot move $from to $to")
    }

    companion object {
        private const val BUFFER = 1 shl 16
        private const val PROGRESS_EVERY = 1L shl 20

        /** What a partial download belongs to: the file's checksum, else its size and URLs. */
        private fun identityOf(file: PackFile): String =
            file.sha256?.lowercase()?.let { "sha256 $it ${file.bytes}" } ?: "bytes ${file.bytes} ${file.parts.joinToString(" ") { it.url }}"

        /** The first byte of a `Content-Range: bytes a-b/n` header, or -1. */
        internal fun rangeStart(header: String?): Long =
            header?.let { Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(it) }?.groupValues?.get(1)?.toLongOrNull() ?: -1L

        /** The SHA-256 of [file], lower-case hex. */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
