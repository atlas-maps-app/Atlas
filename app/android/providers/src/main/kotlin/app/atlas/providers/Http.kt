package app.atlas.providers

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The User-Agent every Atlas request carries, as the Overpass, Nominatim, Wikimedia and MET Norway policies ask. */
const val USER_AGENT = "Atlas/0.1 (+https://github.com/atlas-maps-app/Atlas)"

/**
 * The one HTTP client all providers share: a single OkHttp connection pool and disk cache, polite per-host rate limits,
 * and `suspend` calls that cancel the network request when the coroutine is cancelled.
 */
class Http(
    cacheDir: File? = null,
    cacheBytes: Long = 64L * 1024 * 1024,
    val userAgent: String = USER_AGENT,
    connectTimeout: Duration = 10.seconds,
    readTimeout: Duration = 30.seconds,
    callTimeout: Duration = 60.seconds,
    policies: Map<String, HostPolicy> = HostPolicy.DEFAULTS,
    base: OkHttpClient? = null,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** The shared OkHttp client; other modules may build on it with [OkHttpClient.newBuilder] to share its pool. */
    val client: OkHttpClient = (base?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .apply { if (cacheDir != null) cache(Cache(cacheDir, cacheBytes)) }
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", userAgent).build() else req)
        }
        .build()

    /** The per-host limiter; providers call [HostLimiter.penalise] when a server asks them to back off. */
    val limiter = HostLimiter(policies, clock)

    /** A request builder for [url] with the Atlas User-Agent and JSON accepted. */
    fun request(url: String): Request.Builder = request(url.toHttpUrl())

    fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json")

    /** Sends [request] and reads the whole body, whatever the status code. */
    suspend fun execute(request: Request, timeout: Duration? = null): HttpResponse =
        limiter.withPermit(request.url.host) {
            val call = clientFor(timeout).newCall(request)
            call.awaitBody()
        }

    /** Sends [request] and hands the open body stream to [read]; non-2xx answers throw [HttpException] before [read] runs. */
    suspend fun <T> stream(request: Request, timeout: Duration? = null, read: (InputStream) -> T): T =
        limiter.withPermit(request.url.host) {
            val call = clientFor(timeout).newCall(request)
            call.awaitResponse().use { response ->
                call.readCancellably {
                    if (!response.isSuccessful) throw HttpException(response.code, request.url.toString(), response.body.string().take(500))
                    read(response.body.byteStream())
                }
            }
        }

    /**
     * Sends [request] and hands the whole response to [read] on an IO thread, whatever its status: for big downloads
     * that must be streamed and for conditional requests that may answer 304. The response is closed afterwards.
     */
    suspend fun <T> exchange(request: Request, timeout: Duration? = null, read: (Response) -> T): T =
        limiter.withPermit(request.url.host) {
            val call = clientFor(timeout).newCall(request)
            call.awaitResponse().use { response -> call.readCancellably { read(response) } }
        }

    /** GET [url] and return the body as text, throwing [HttpException] unless the answer is 2xx. */
    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        execute(request(url).headers(headers).build()).requireOk().text

    /** GET [url] as a JSON tree. */
    suspend fun json(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        ProviderJson.parseToJsonElement(text(url, headers))

    /** GET [url] and decode it with [deserializer]. */
    suspend fun <T> json(url: String, deserializer: DeserializationStrategy<T>, headers: Map<String, String> = emptyMap()): T =
        ProviderJson.decodeFromString(deserializer, text(url, headers))

    /** POST a form and return the response, whatever its status. */
    suspend fun postForm(url: String, fields: Map<String, String>, timeout: Duration? = null): HttpResponse {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute(request(url).post(form).build(), timeout)
    }

    private fun clientFor(timeout: Duration?): OkHttpClient =
        if (timeout == null) client
        else client.newBuilder()
            .readTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(timeout.inWholeMilliseconds + 15_000, TimeUnit.MILLISECONDS)
            .build()

    private fun Request.Builder.headers(map: Map<String, String>): Request.Builder = apply { map.forEach { (k, v) -> header(k, v) } }
}

/** A finished HTTP exchange with its whole body. */
class HttpResponse(val code: Int, val headers: Headers, val body: ByteArray, val url: String, val fromCache: Boolean = false) {
    val ok: Boolean get() = code in 200..299
    val text: String by lazy { body.toString(Charsets.UTF_8) }

    fun json(): JsonElement = ProviderJson.parseToJsonElement(text)

    /** Throws [HttpException] unless the status is 2xx; returns this for chaining. */
    fun requireOk(): HttpResponse = if (ok) this else throw HttpException(code, url, text.take(500))
}

/** A server answered with a status other than 2xx. */
class HttpException(val code: Int, val url: String, val body: String) : IOException("HTTP $code from ${url.substringBefore('?')}: ${body.take(200)}")

/** Enqueues the call, resumes with the response, and cancels the call if the coroutine is cancelled. */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }
    })
}

/**
 * Runs the blocking [block] (reading a response body) on an IO thread. A blocked socket read ignores coroutine
 * cancellation, so cancelling the coroutine cancels the call instead, which closes the socket and ends the read at once:
 * a 15-minute national download or a city-sized Overpass stream stops when its screen goes away.
 */
private suspend fun <T> Call.readCancellably(block: () -> T): T = coroutineScope {
    val reading = AtomicBoolean(true)
    val call = this@readCancellably
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (reading.get()) call.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: IOException) {
        // If the read failed because the call was cancelled under it, report the cancellation, not a network error.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        reading.set(false)
        watcher.cancel()
    }
}

/** Like [awaitResponse] but reads the body on OkHttp's own thread, so the caller never blocks. */
private suspend fun Call.awaitBody(): HttpResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { HttpResponse(it.code, it.headers, it.body.bytes(), it.request.url.toString(), it.cacheResponse != null && it.networkResponse == null) }
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
            }
            if (cont.isActive) cont.resume(result)
        }
    })
}
