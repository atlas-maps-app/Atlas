package app.atlas.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration

/** How politely to treat one host: at most [maxConcurrent] requests at once, starting at least [minIntervalMs] apart. */
data class HostPolicy(val minIntervalMs: Long = 0, val maxConcurrent: Int = 6) {
    companion object {
        /** Nominatim allows one request a second; Overpass asks for no parallel queries; the others get a gentle spacing. */
        val DEFAULTS: Map<String, HostPolicy> = mapOf(
            "nominatim.openstreetmap.org" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
            "overpass-api.de" to HostPolicy(maxConcurrent = 1),
            "overpass.kumi.systems" to HostPolicy(maxConcurrent = 1),
            "maps.mail.ru" to HostPolicy(maxConcurrent = 1),
            "overpass.private.coffee" to HostPolicy(maxConcurrent = 1),
            "api.mangrove.reviews" to HostPolicy(minIntervalMs = 250, maxConcurrent = 2),
            "api.openchargemap.io" to HostPolicy(minIntervalMs = 500, maxConcurrent = 1),
            "api.openstreetmap.org" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
            "www.wikidata.org" to HostPolicy(maxConcurrent = 2),
            "commons.wikimedia.org" to HostPolicy(maxConcurrent = 2),
            "photon.komoot.io" to HostPolicy(minIntervalMs = 200, maxConcurrent = 2),
            "api.transitous.org" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1),
            "developer.nlr.gov" to HostPolicy(minIntervalMs = 250, maxConcurrent = 2),
            "opendata.ndw.nu" to HostPolicy(maxConcurrent = 1),
            "data.bundesnetzagentur.de" to HostPolicy(maxConcurrent = 1),
            "terra.tripadvisor.com" to HostPolicy(minIntervalMs = 100, maxConcurrent = 2),
        )
    }
}

/**
 * Per-host request spacing and concurrency. Hosts without a policy run unthrottled.
 * [clock] gives monotonic milliseconds; tests pass virtual time.
 */
class HostLimiter(private val policies: Map<String, HostPolicy>, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Gate(val policy: HostPolicy) {
        val permits = Semaphore(policy.maxConcurrent.coerceAtLeast(1))
        val lock = Mutex()
        var nextStart = Long.MIN_VALUE
    }

    private val gates = ConcurrentHashMap<String, Gate>()

    private fun gate(host: String): Gate? {
        gates[host]?.let { return it }
        val policy = policies[host] ?: policies.entries.firstOrNull { host.endsWith("." + it.key) }?.value ?: return null
        return gates.getOrPut(host) { Gate(policy) }
    }

    /** Runs [block] once [host]'s policy allows another request. */
    suspend fun <T> withPermit(host: String, block: suspend () -> T): T {
        val g = gate(host) ?: return block()
        return g.permits.withPermit {
            val wait = g.lock.withLock {
                val now = clock()
                val start = max(now, g.nextStart)
                g.nextStart = start + g.policy.minIntervalMs
                start - now
            }
            if (wait > 0) delay(wait)
            block()
        }
    }

    /** Holds every request to [host] back for [pause], as Overpass asks after a 429; also for hosts without a policy. */
    suspend fun penalise(host: String, pause: Duration) {
        val g = gate(host) ?: gates.getOrPut(host) { Gate(HostPolicy()) }
        g.lock.withLock { g.nextStart = max(g.nextStart, clock() + pause.inWholeMilliseconds) }
    }
}
