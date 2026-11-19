package app.atlas.mapview

/**
 * What the core's theme holds and what is on its way to it, for [MapController]'s moments: a send is [start]ed on the
 * main thread and [finish]ed from its result, and only a send the core took becomes [sent]. So a send that fails (the
 * core closed, a block refused, the moment not made) leaves the core's own look as it was recorded, and the next
 * update sends it again, rather than the map keeping the old look until the next change.
 *
 * [K] is the whole of what a send carries (the moment, the place it lights, the user's Tone and palette).
 */
internal class MomentBook<K : Any> {
    /** What the core last took, or null until it took one (or after [reset]); read on any thread. */
    @Volatile
    var sent: K? = null
        private set

    /** The send on its way, or null. */
    private var sending: K? = null

    /** The last send that failed: tried again at the next update, and at rest once ([finish]). */
    private var failed: K? = null

    /** Whether [key] is neither what the core holds nor what is on its way to it. */
    fun due(key: K): Boolean = key != sent && key != sending

    /** [key] goes to the core. */
    fun start(key: K) {
        sending = key
    }

    /**
     * The send of [key] came back, [ok] where the core took it. Returns whether a failed send is to be tried once
     * more while the map is at rest: true the first time [key] fails, false when it failed before (a block the core
     * refuses is not sent for ever).
     */
    fun finish(key: K, ok: Boolean): Boolean {
        if (sending == key) sending = null
        if (ok) {
            sent = key
            failed = null
            return false
        }
        val first = failed != key
        failed = key
        return first
    }

    /** A new core: it holds nothing of this map's. */
    fun reset() {
        sent = null
        sending = null
        failed = null
    }
}
