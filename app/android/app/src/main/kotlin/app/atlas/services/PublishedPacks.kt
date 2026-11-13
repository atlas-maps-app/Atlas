package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.live.PackLibrary
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The published element packs the map's places ask for (ENGINE.md step 3, the windows' local lane): where no pack on
 * the phone meets a window's box, the published pack that holds its middle is fetched and kept, as today's map fetched
 * it until 5 November (the renders' places outside the Netherlands). Until 13 November the windows' own step fetched
 * it, so every window waited behind a download of megabytes that the map called "getting this area", on whatever
 * network the phone had. Now:
 * - **A job of its own.** [want] only notes the place and returns: the list of packs and the pack itself come on
 *   [run]'s thread, one pack at a time. The window that asked goes on to its own data meanwhile, and the windows the
 *   phone still has nothing of when the pack lands come from it.
 * - **Never on mobile data unasked.** Nothing is asked of the network while [allowed] says no: the phone is offline, or
 *   on a metered network while the Maps board's "Wi-Fi only" is on (its default), which is the consent a region's
 *   download waits for too. What is wanted stays wanted; [wake] takes it up as the network or the setting changes. A
 *   download under way stops where the network turns metered.
 * - **Said as it is.** [coming] is the pack on its way and how far it is, for the map's line ("Downloading the map
 *   of Paris… 42 %") in place of the windows' own.
 *
 * A pack that did not come, or the list, is asked again [RETRY_MS] on at the earliest, when a place wants it or the
 * network changes. Without Android's types: the app's tests run it with their own library and thread.
 */
class PublishedPacks(
    private val packs: PackLibrary,
    private val allowed: () -> Boolean,
    private val run: (Runnable) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** A pack landed, or what [coming] says changed: the map draws its line again. */
    private val changed: () -> Unit = {},
    private val said: (String) -> Unit = {},
) {
    /** A pack on its way: which, the box it holds, and [done] of its [bytes] (−1: not known). */
    class Coming(val id: String, val name: String, val bbox: BBox, val done: Long, val bytes: Long)

    /** The pack being fetched now; null while none is. */
    @Volatile
    var coming: Coming? = null
        private set

    /** The packs wanted and not on the phone yet, by id, in the order they were asked for. */
    private val wanted = java.util.Collections.synchronizedMap(LinkedHashMap<String, PackLibrary.Entry>())

    /** When each pack last failed to come (ms). */
    private val failed = ConcurrentHashMap<String, Long>()

    /** The last place asked for while the list of packs was not read yet: looked up once it is. */
    @Volatile
    private var pending: LatLon? = null

    @Volatile
    private var listAskedAt = Long.MIN_VALUE

    private val working = AtomicBoolean(false)

    /**
     * A window whose middle is [at] found no pack on the phone: the published pack that holds it, where one does and
     * it is not on the phone, is wanted from now on. Never blocks and asks nothing of the network itself.
     */
    fun want(at: LatLon) {
        val known = packs.published
        if (known.isEmpty()) pending = at else note(known, at)
        wake()
    }

    /** Takes up what is wanted where it may come now ([allowed]); called as the network or the user's consent changes. */
    fun wake() {
        if (!due() || !allowed()) return
        if (working.compareAndSet(false, true)) run(Runnable { work() })
    }

    private fun note(known: List<PackLibrary.Entry>, at: LatLon) {
        val entry = known.firstOrNull { at in it.bbox && packs.local(it.id) == null } ?: return
        wanted.putIfAbsent(entry.id, entry)
    }

    /** Whether something is to be fetched now: the list for a place that waits for it, or a wanted pack not lately failed. */
    private fun due(): Boolean = listDue() || next() != null

    private fun listDue(): Boolean = pending != null && packs.published.isEmpty() && (listAskedAt == Long.MIN_VALUE || now() - listAskedAt >= RETRY_MS)

    private fun next(): PackLibrary.Entry? {
        val t = now()
        return synchronized(wanted) { wanted.values.firstOrNull { e -> failed[e.id]?.let { t - it < RETRY_MS } != true } }
    }

    private fun work() {
        try {
            while (allowed()) {
                if (listDue()) {
                    listAskedAt = now()
                    try {
                        packs.refresh()
                    } catch (e: IOException) {
                        said("the list of published packs did not come: ${e.message}")
                    }
                }
                packs.published.takeIf { it.isNotEmpty() }?.let { known ->
                    pending?.let { note(known, it) }
                    pending = null
                }
                val entry = next() ?: break
                if (packs.local(entry.id) == null) fetch(entry)
                if (packs.local(entry.id) != null) {
                    wanted.remove(entry.id)
                    failed.remove(entry.id)
                } else {
                    // not on the phone after all: it waits as one that did not come, so the loop goes on to the next
                    failed.putIfAbsent(entry.id, now())
                }
            }
        } finally {
            working.set(false)
        }
        // what was asked for as the loop ended
        wake()
    }

    private fun fetch(entry: PackLibrary.Entry) {
        coming = Coming(entry.id, entry.name, entry.bbox, 0L, entry.bytes)
        changed()
        try {
            var told = 0L
            packs.download(entry) { done, total ->
                if (!allowed()) throw IOException("the network is no longer one a pack may come over")
                coming = Coming(entry.id, entry.name, entry.bbox, done, if (total > 0) total else entry.bytes)
                if (done - told >= TELL_EVERY) {
                    told = done
                    changed()
                }
            }
            said("the pack ${entry.id} fetched (${entry.bytes} bytes)")
        } catch (e: IOException) {
            failed[entry.id] = now()
            said("the pack ${entry.id} did not come: ${e.message}")
        } catch (e: RuntimeException) {
            // a file that is no pack (PackFormatException): not placed, asked again later as one that did not come
            failed[entry.id] = now()
            said("the pack ${entry.id} is not one this app reads: ${e.message}")
        } finally {
            coming = null
            changed()
        }
    }

    companion object {
        /** How long a pack that did not come, or the list, waits before it is asked for again. */
        const val RETRY_MS = 60_000L

        /** The map hears of a download's progress every so many bytes. */
        private const val TELL_EVERY = 256L shl 10
    }
}
