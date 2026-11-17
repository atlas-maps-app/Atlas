package app.atlas.packer.heights

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * The terrain's reads from its sources' hosts: at most [PER_HOST] requests at a time per host, each retried with a
 * growing wait, each naming the packer ([userAgent]: no personal detail goes into any request), and each counted under
 * the source it was made for: requests, bytes and seconds, which the build reports.
 */
open class Fetcher(private val userAgent: String, private val log: (String) -> Unit = {}, private val retries: Int = 6, private val firstWaitMs: Long = 2000) {
    /** What was read for one source. */
    class Stats {
        val requests = AtomicLong()
        val bytes = AtomicLong()
        val nanos = AtomicLong()
    }

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val hosts = ConcurrentHashMap<String, Semaphore>()
    val stats = ConcurrentHashMap<String, Stats>()

    private fun stat(source: String): Stats = stats.getOrPut(source) { Stats() }

    private fun <T> held(url: String, work: () -> T): T {
        val gate = hosts.getOrPut(URI.create(url).host ?: "") { Semaphore(PER_HOST) }
        gate.acquire()
        try {
            return work()
        } finally {
            gate.release()
        }
    }

    private fun request(url: String, range: LongRange?): HttpRequest {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("User-Agent", userAgent).GET()
        if (range != null) b.header("Range", "bytes=${range.first}-${range.last}")
        return b.build()
    }

    /** Whether a failed request is worth another try: the network, a server's 5xx, or its asking to wait. */
    private fun again(status: Int): Boolean = status == 408 || status == 429 || status >= 500

    private fun <T> retried(source: String, url: String, attempt: () -> T): T {
        var wait = firstWaitMs
        var last: Exception? = null
        for (i in 0 until retries) {
            try {
                return attempt()
            } catch (e: Refused) {
                throw IOException("$url: ${e.message}")
            } catch (e: IOException) {
                last = e
            } catch (e: InterruptedException) {
                throw e
            }
            if (i < retries - 1) {
                log("$source: ${last?.message ?: "failed"}; again in ${wait / 1000} s ($url)")
                Thread.sleep(wait)
                wait = minOf(wait * 2, 120_000)
            }
        }
        throw IOException("$url: ${last?.message ?: "failed"} after $retries tries")
    }

    private class Refused(message: String) : Exception(message)

    /**
     * The bytes at [url] ([range] of them, when given), for [source]. With [short], a range that runs past the file's
     * end gives the bytes there are (a file's head, asked for before its length is known).
     */
    open fun get(source: String, url: String, range: LongRange? = null, short: Boolean = false): ByteArray = retried(source, url) {
        held(url) {
            val t0 = System.nanoTime()
            val r = client.send(request(url, range), HttpResponse.BodyHandlers.ofByteArray())
            val s = stat(source)
            s.requests.incrementAndGet()
            s.nanos.addAndGet(System.nanoTime() - t0)
            val body = r.body()
            s.bytes.addAndGet(body.size.toLong())
            val status = r.statusCode()
            if (again(status)) throw IOException("HTTP $status")
            if (status != 200 && status != 206) throw Refused("HTTP $status")
            val whole = range == null || body.size.toLong() == range.last - range.first + 1
            if (!whole && status == 200 && body.size.toLong() > range!!.last - range.first + 1) throw Refused("the host ignores ranges")
            if (!whole && !short) throw IOException("${body.size} bytes of ${range!!.last - range.first + 1}")
            // a service's refusal comes as a document with status 200 (a rate limit's among them): worth another try
            val type = r.headers().firstValue("Content-Type").orElse("")
            if (range == null && (type.contains("xml") || type.contains("html")) && looksLikeException(body)) throw IOException("the service answers with an exception: ${String(body, 0, minOf(body.size, 300)).replace('\n', ' ')}")
            body
        }
    }

    private fun looksLikeException(body: ByteArray): Boolean {
        val head = String(body, 0, minOf(body.size, 2000))
        return head.contains("ExceptionReport") || head.contains("ServiceException")
    }

    /** [url] into [file] (through a part file beside it), for [source]; a file already there is kept. */
    open fun download(source: String, url: String, file: File): File {
        if (file.isFile && file.length() > 0) return file
        file.absoluteFile.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        retried(source, url) {
            held(url) {
                val t0 = System.nanoTime()
                part.delete()
                val r = client.send(request(url, null), HttpResponse.BodyHandlers.ofFile(part.toPath()))
                val s = stat(source)
                s.requests.incrementAndGet()
                s.nanos.addAndGet(System.nanoTime() - t0)
                s.bytes.addAndGet(part.length())
                val status = r.statusCode()
                if (again(status)) throw IOException("HTTP $status")
                if (status != 200) throw Refused("HTTP $status")
                val length = r.headers().firstValueAsLong("Content-Length")
                if (length.isPresent && length.asLong != part.length()) throw IOException("${part.length()} bytes of ${length.asLong}")
            }
        }
        if (!part.renameTo(file)) throw IOException("cannot move $part to $file")
        return file
    }

    /** Whether [url] is there (a 404 or 403 is not: a degree cell of open sea has no tile), for [source]. */
    open fun exists(source: String, url: String): Boolean = retried(source, url) {
        held(url) {
            val t0 = System.nanoTime()
            val r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", userAgent).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
            val s = stat(source)
            s.requests.incrementAndGet()
            s.nanos.addAndGet(System.nanoTime() - t0)
            val status = r.statusCode()
            if (again(status)) throw IOException("HTTP $status")
            status == 200
        }
    }

    /** One line per source: its requests, bytes and seconds. */
    fun report(): List<String> = stats.entries.sortedBy { it.key }.map { (id, s) ->
        "fetch $id: ${s.requests.get()} requests, ${s.bytes.get()} bytes, ${"%.1f".format(s.nanos.get() / 1e9)} s of requests"
    }

    companion object {
        const val PER_HOST = 4
        const val USER_AGENT = "Atlas-packs/1 (+https://github.com/atlas-maps-app/Atlas)"
    }
}
