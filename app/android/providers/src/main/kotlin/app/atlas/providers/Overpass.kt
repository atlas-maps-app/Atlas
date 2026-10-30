package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Overpass answered, but with an error that another server would repeat: a bad query, or one too heavy for anyone. */
class OverpassException(message: String, val query: String) : ProviderException("overpass", message)

/**
 * An Overpass API client with failover across the public instances.
 *
 * It follows the main instance's policy: one query at a time from this app, an identifying User-Agent, and a 30 s pause
 * on a server that answers 429. A server that is busy (504), down or unreachable is rested and the next one is tried;
 * a query error (400) is ours, so it is thrown at once. The last server that answered is tried first next time.
 *
 * A server that fails again rests twice as long each time in a row ([MAX_REST] at most), and a query asks no server
 * that rests while another may answer; where every one rests, only the one whose rest ends first is asked. On 27 and 30
 * October 2026 every public server failed or gave no answer for an hour, and each query (a search, the places nearby,
 * each piece of a lorry's route) waited for every one of them in turn.
 */
class Overpass(
    private val http: Http,
    val endpoints: List<String> = ENDPOINTS,
    private val defaultTimeoutSeconds: Int = 60,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    private val sequential = Mutex()
    private var preferred = 0
    private val restUntil = LongArray(endpoints.size)

    /** How often each server failed in a row. */
    private val failedInARow = IntArray(endpoints.size)

    /** The endpoint that answered last, for the UI's "via …" line. */
    @Volatile
    var lastEndpoint: String? = null
        private set

    init {
        require(endpoints.isNotEmpty()) { "Overpass needs at least one endpoint" }
    }

    /** Runs [ql] and returns the raw JSON text: for callers with their own parser (the route module's restriction checks). */
    suspend fun raw(ql: String): String = run(ql, retryOnRemark = true) { it.readText() }

    /** Runs [ql] and parses the whole answer. */
    suspend fun query(ql: String): OverpassResult = run(ql, retryOnRemark = true) { OverpassParser.parse(it) }

    /** Runs [ql] and returns its tagged elements as places. */
    suspend fun places(ql: String): List<Place> = query(ql).places()

    /** Runs [ql] and returns its ways with geometry (ask for `out geom`, or `out body; >; out skel;`). */
    suspend fun ways(ql: String): List<OsmWay> = query(ql).ways()

    /**
     * Runs [ql] and streams its elements to [onElement] without holding the answer: for whole-region imports.
     * Once an element has been handed out, a failure is thrown rather than retried elsewhere, so none arrives twice.
     */
    suspend fun stream(ql: String, onElement: (OverpassElement) -> Unit): OverpassMeta {
        var handedOut = false
        return run(ql, retryOnRemark = false, retryable = { !handedOut }) { reader ->
            OverpassParser.stream(reader) { handedOut = true; onElement(it) }
        }
    }

    /**
     * Every way with its line within [metres] of the line through [points], for the route module's checks: height,
     * weight, width and length limits, lorry and hazmat rules, locks and movable bridges. [selectors] narrow or widen it.
     * The line is simplified for the request and the corridor widened by as much, so a few ways just beyond [metres]
     * may come too.
     */
    suspend fun restrictionsAlong(points: List<LatLon>, metres: Double = 25.0, selectors: List<String> = RESTRICTION_SELECTORS): List<OsmWay> {
        if (points.isEmpty()) return emptyList()
        val line = RequestLine.of(points, metres / 4)
        return ways(OverpassQl.waysWithGeometry(selectors, OverpassArea.Along(line.points, metres + line.extraM)))
    }

    /** [retryOnRemark] is false when elements were already handed out, so a failed answer must not be repeated elsewhere. */
    private suspend fun <T> run(ql: String, retryOnRemark: Boolean, retryable: () -> Boolean = { true }, parse: (Reader) -> T): T = sequential.withLock {
        val query = OverpassQl.ensureJson(ql, defaultTimeoutSeconds)
        val timeout: Duration = (OverpassQl.timeoutOf(query, defaultTimeoutSeconds) + 15).seconds
        val failures = ArrayList<String>()
        val tried = HashSet<Int>()
        while (tried.size < endpoints.size) {
            val i = pick(tried) ?: break
            tried += i
            val url = endpoints[i]
            try {
                val result = fetch(url, query, timeout, retryOnRemark, parse)
                preferred = i
                failedInARow[i] = 0
                lastEndpoint = url
                return@withLock result
            } catch (e: OverpassException) {
                throw e
            } catch (e: SwitchedOffException) {
                // the user switched Overpass off: no server failed, so none rests
                throw e
            } catch (e: OverpassBusy) {
                failures += "${host(url)}: ${e.message}"
                rest(i, e.pause)
                if (!retryable()) break
            } catch (e: IOException) {
                // A read cut short because the caller gave up is not the server's fault: stop, and rest nobody.
                currentCoroutineContext().ensureActive()
                failures += "${host(url)}: ${e.message ?: e.javaClass.simpleName}"
                rest(i, 10.seconds)
                if (!retryable()) break
            }
        }
        throw ProviderException("overpass", "No Overpass server could answer (${failures.joinToString("; ")})")
    }

    /**
     * The next endpoint to try: the preferred one first, then the others in order, none that rests; where every one
     * rests before any was asked, the one whose rest ends first. Null where those left all rest.
     */
    private fun pick(tried: Set<Int>): Int? {
        val now = clock()
        val order = endpoints.indices.map { (preferred + it) % endpoints.size }.filter { it !in tried }
        return order.firstOrNull { restUntil[it] <= now } ?: if (tried.isEmpty()) order.minByOrNull { restUntil[it] } else null
    }

    /** Rests endpoint [i] for [pause], twice as long for each failure in a row before this one, [MAX_REST] at most. */
    private fun rest(i: Int, pause: Duration) {
        val n = ++failedInARow[i]
        restUntil[i] = clock() + minOf(pause.inWholeMilliseconds shl (n - 1).coerceAtMost(10), MAX_REST.inWholeMilliseconds)
    }

    private suspend fun <T> fetch(url: String, query: String, timeout: Duration, retryOnRemark: Boolean, parse: (Reader) -> T): T {
        // OkHttp asks for gzip and inflates it itself, as long as we do not set Accept-Encoding by hand.
        val request = http.request(url).post(FormBody.Builder().add("data", query).build()).build()
        return try {
            http.stream(request, timeout) { body ->
                val reader = InputStreamReader(body, Charsets.UTF_8).buffered(64 * 1024)
                reader.mark(256)
                val head = CharArray(256).let { String(it, 0, reader.read(it).coerceAtLeast(0)) }
                reader.reset()
                if (!head.trimStart().startsWith("{")) throw OverpassBusy("answered with HTML instead of JSON: ${htmlError(head)}", 10.seconds)
                parse(reader)
            }.also { checkRemark(it, query, retryOnRemark) }
        } catch (e: HttpException) {
            when (e.code) {
                400 -> throw OverpassException("The query is not valid Overpass QL: ${htmlError(e.body)}", query)
                429 -> {
                    http.limiter.penalise(url.toHttpUrl().host, 30.seconds)
                    throw OverpassBusy("too many requests (429)", 30.seconds)
                }
                else -> throw OverpassBusy("HTTP ${e.code} ${htmlError(e.body)}".trim(), if (e.code == 504) 15.seconds else 30.seconds)
            }
        }
    }

    /** A complete JSON answer can still carry a runtime error in its remark; a timeout there is worth another server. */
    private fun checkRemark(result: Any?, query: String, retry: Boolean) {
        val meta = when (result) {
            is OverpassResult -> result.meta
            is OverpassMeta -> result
            is String -> if (result.contains("\"remark\"")) runCatching { OverpassParser.stream(StringReader(result)) {} }.getOrNull() else null
            else -> null
        } ?: return
        if (!meta.failed) return
        val remark = meta.remark.orEmpty()
        if (retry && (remark.contains("timed out", true) || remark.contains("out of memory", true) || remark.contains("timeout", true))) {
            throw OverpassBusy(remark, 10.seconds)
        }
        throw OverpassException(remark, query)
    }

    private class OverpassBusy(message: String, val pause: Duration) : IOException(message)

    companion object {
        /** The longest a server rests, however often it failed in a row. */
        val MAX_REST: Duration = 10.minutes

        /** Ways that can stop a lorry, a camper or a boat: legal limits, lorry and hazmat rules, locks and movable bridges. */
        val RESTRICTION_SELECTORS = listOf(
            "[\"maxheight\"]", "[\"maxweight\"]", "[\"maxwidth\"]", "[\"maxlength\"]", "[\"maxaxleload\"]",
            "[\"hgv\"~\"^(no|destination|delivery|private|discouraged)$\"]", "[\"hazmat\"]", "[\"maxheight:physical\"]",
            "[\"lock\"=\"yes\"]", "[\"bridge:movable\"]",
        )

        /** The public instances, in the order Atlas prefers them. */
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        val INFO = ProviderInfo(
            id = "overpass",
            name = "OpenStreetMap, through Overpass",
            licence = "ODbL",
            attribution = "© OpenStreetMap contributors",
            url = "https://www.openstreetmap.org/copyright",
            note = "Shared public servers, one query at a time: the map's windows that Atlas's packs do not hold, searches beyond " +
                "your downloads, the places nearby and a lorry's route checks.",
            hosts = ENDPOINTS.map { it.toHttpUrl().host },
        )

        private fun host(url: String) = runCatching { url.toHttpUrl().host }.getOrDefault(url)

        /** The error sentence from Overpass's HTML error page, or the first line of text. */
        internal fun htmlError(body: String): String {
            val m = Regex("""<strong[^>]*>Error</strong>:\s*(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(body)
            val text = (m?.groupValues?.get(1) ?: body).replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            return text.take(240)
        }
    }
}
