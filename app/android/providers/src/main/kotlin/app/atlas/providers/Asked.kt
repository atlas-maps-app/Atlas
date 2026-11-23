package app.atlas.providers

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What was asked since Atlas started (since this was made: [started]), per source: requests sent, requests refused, when each last was, and the hosts
 * its requests went to beyond the ones it lists. In memory only, and only counts and hosts: never a path nor a query,
 * which hold what was searched. Nothing waits on it: counters and a concurrent set, no lock a download could wait on.
 *
 * [Http] counts a request where it is sent, each hop of a redirect apart, under the source that lists its host (the
 * request's own first, so the chargers asked of Overpass's servers are the chargers'), else under the source stamped on
 * it ([SourceTag]), whose host is then one beyond its list. An answer from the cache sends nothing and counts nothing;
 * a request OkHttp sends again after a broken connection was sent twice and counts twice.
 * A refused request is counted once, under the switched-off source that held it back.
 */
class Asked(private val clock: () -> Long = System::currentTimeMillis) {
    /** One source's count; [lastSent] and [lastRefused] in Unix ms, null if never. */
    data class Count(val sent: Long, val refused: Long, val lastSent: Long?, val lastRefused: Long?, val hosts: Set<String>)

    private class Tally {
        val sent = AtomicLong()
        val refused = AtomicLong()
        val lastSent = AtomicLong()
        val lastRefused = AtomicLong()
        val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun count() = Count(sent.get(), refused.get(), lastSent.get().takeIf { it > 0 }, lastRefused.get().takeIf { it > 0 }, hosts.toSet())
    }

    /** When the count began, in Unix ms by its clock: when Atlas started, as nothing of it is kept. */
    val started: Long = clock()

    private val tallies = ConcurrentHashMap<String, Tally>()

    private fun tally(source: String) = tallies.computeIfAbsent(source) { Tally() }

    /** A request was sent for [source]; [beyond] is its host where [source] does not list it, else null. */
    fun sent(source: String, beyond: String? = null) {
        val t = tally(source)
        t.sent.incrementAndGet()
        t.lastSent.accumulateAndGet(clock(), ::maxOf)
        val host = beyond?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return
        if (t.hosts.size < MAX_HOSTS || host in t.hosts) t.hosts.add(host)
    }

    /** A request was held back because [source] is switched off. */
    fun refused(source: String) {
        val t = tally(source)
        t.refused.incrementAndGet()
        t.lastRefused.accumulateAndGet(clock(), ::maxOf)
    }

    /** [source]'s count: all naught if nothing was asked of it. */
    fun of(source: String): Count = tallies[source]?.count() ?: Count(0, 0, null, null, emptySet())

    /** The count of every source asked or held back at least once. */
    fun all(): Map<String, Count> = tallies.mapValues { it.value.count() }

    companion object {
        /** The hosts beyond its list kept a source: a few instances of Panoramax's, say; a list without end would grow. */
        const val MAX_HOSTS = 16
    }
}
