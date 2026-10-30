package app.atlas.net

import java.net.URI

/**
 * What the phone knows of the public Overpass servers lately, shared by every client that asks them: the map's windows
 * (render's `OverpassClient`) and the app's queries (providers' `Overpass`). A server that failed rests until a time,
 * longer after each failure in a row; the one that answered last is asked first. Until 30 October each client kept its
 * own, so a server one had found dead the other still asked, each waiting minutes on it (the user, 30 October: "are all
 * sources reliable and respond fast?").
 *
 * A server is known by its host, with its port where the address names one ([keyOf]). [clock] gives monotonic
 * milliseconds; tests pass their own. [inUse] says whether a server's one query at a time is under way now (the app's
 * limiter, which both clients pass): a client asks a free server before one it would wait for.
 */
class OverpassServers(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val inUse: (url: String) -> Boolean = { false },
) {
    private class Rest(val until: Long, val times: Int)

    private val rests = HashMap<String, Rest>()
    private var first: String? = null

    /** The clock's time now. */
    fun now(): Long = clock()

    /** Until when the server at [url] rests, in the clock's time; [Long.MIN_VALUE] where it does not. */
    fun restUntil(url: String): Long = synchronized(this) { rests[keyOf(url)]?.until ?: Long.MIN_VALUE }

    /**
     * The server at [url] failed: it rests [pauseMs], twice as long for each failure in a row before this one (whichever
     * client saw them), [capMs] at most. A longer rest it already had stands, and it is no longer asked first.
     */
    fun failed(url: String, pauseMs: Long, capMs: Long) = synchronized(this) {
        val key = keyOf(url)
        val before = rests[key]
        val times = (before?.times ?: 0) + 1
        val until = clock() + minOf(pauseMs shl (times - 1).coerceAtMost(10), capMs)
        rests[key] = Rest(maxOf(until, before?.until ?: Long.MIN_VALUE), times)
        if (first == key) first = null
    }

    /** The server at [url] answered: it rests no more, and is asked first by every client where [first], else no longer so. */
    fun answered(url: String, first: Boolean = true) = synchronized(this) {
        val key = keyOf(url)
        rests.remove(key)
        if (first) this.first = key else if (this.first == key) this.first = null
    }

    /** Whether the server at [url] answered last, so every client asks it first. */
    fun isFirst(url: String): Boolean = synchronized(this) { first == keyOf(url) }

    /** Whether a query is under way on the server at [url] now, so another would wait for it. */
    fun busy(url: String): Boolean = inUse(url)

    companion object {
        /** A server's key: the host of [url], with the port where the address names one; [url] itself where it is no address. */
        fun keyOf(url: String): String = runCatching {
            val u = URI(url)
            val host = u.host ?: return@runCatching url
            if (u.port == -1) host else "$host:${u.port}"
        }.getOrDefault(url)
    }
}
