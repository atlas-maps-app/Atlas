package app.atlas.tiles

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP for tiles: one shared client and a coroutine-friendly call. */
object Http {
    /** Every request says who is asking, as the servers it asks (GitHub's among them) want. */
    const val USER_AGENT = "Atlas/0.1 (+https://github.com/atlas-maps-app/Atlas)"

    /** A client with polite timeouts, shared so connections are reused. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun request(url: String): Request.Builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
}

/** A response read in full: status, headers and body. */
class HttpResult(val code: Int, val headers: Headers, val body: ByteArray) {
    fun header(name: String): String? = headers[name]
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Runs the call without blocking the coroutine and reads the whole body on OkHttp's own threads.
 * Cancelling the coroutine cancels the call. A body larger than [maxBytes] fails instead of filling memory.
 */
internal suspend fun Call.read(maxBytes: Long = 64L shl 20): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use {
                    val declared = it.body.contentLength()
                    if (declared > maxBytes) throw IOException("response of $declared bytes exceeds $maxBytes")
                    val source = it.body.source()
                    if (!source.request(maxBytes + 1)) HttpResult(it.code, it.headers, source.buffer.readByteArray())
                    else throw IOException("response exceeds $maxBytes bytes")
                }
            } catch (e: Exception) {
                // Whatever goes wrong here must reach the caller: OkHttp does not report a throwing onResponse, and the
                // coroutine would wait for ever.
                if (cont.isActive) cont.resumeWithException(e as? IOException ?: IOException("reading the response failed: $e", e))
                return
            }
            if (cont.isActive) cont.resume(result)
        }
    })
}

/** Thrown for an HTTP status that is neither success nor a known "no tile". */
class HttpStatusException(val code: Int, message: String) : IOException(message)
