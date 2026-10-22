package app.atlas.mapview.vk

import android.util.Log
import app.atlas.mapcore.MapCore
import app.atlas.mapview.EngineFrames
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The windows the phone builds (ENGINE.md step 3: the core asks, the app fetches): one L14 tile of the map each, built
 * by the core from OpenStreetMap's data of its box. The core says which it wants, nearest first
 * ([MapCore.wantedWindows]); [step] takes the nearest one not under way and not failed lately, fetches its data with
 * [fetch] (the app's Overpass servers, one query at a time), has the core build it into [dir] as `14-<x>-<y>.atlas`, and
 * says so, so the map hands its packs again ([PackList] lists the file; the core takes it into its frame cell's pack).
 * One window at a time, on the caller's background thread.
 */
class Windows(
    private val dir: File,
    /** Overpass's JSON for a box (south, west, north, east): the window's query ([MapCore.Window.box]); throws when no server answers. */
    private val fetch: (DoubleArray) -> ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failed = ConcurrentHashMap<MapCore.Window, Long>()
    private val busy = AtomicBoolean(false)

    /** Builds the nearest window [core] wants; true when one was built (the packs are to be handed again). */
    fun step(core: MapCore): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            val now = clock()
            val w = core.wantedWindows().firstOrNull { w -> failed[w]?.let { now - it > RETRY_MS } ?: true } ?: return false
            val t0 = clock()
            val bytes = try {
                fetch(w.box())
            } catch (e: Exception) {
                failed[w] = now
                Log.w(EngineFrames.TAG, "vk window ${w.id}: no data: ${e.message}")
                return false
            }
            val json = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer
            dir.mkdirs()
            val t1 = clock()
            val why = w.build(json, fileOf(w))
            if (why.isNotEmpty()) {
                failed[w] = now
                Log.w(EngineFrames.TAG, "vk window ${w.id}: $why")
                return false
            }
            failed.remove(w)
            Log.i(EngineFrames.TAG, "vk window ${w.id}: ${bytes.size} bytes fetched in ${t1 - t0} ms, built in ${clock() - t1} ms")
            return true
        } finally {
            busy.set(false)
        }
    }

    /** The file of window [w]. */
    fun fileOf(w: MapCore.Window): File = File(dir, "${w.z}-${w.x}-${w.y}.atlas")

    companion object {
        /** How long a window whose data or build failed waits before it is tried again. */
        const val RETRY_MS = 5 * 60 * 1000L

        private val NAME = Regex("""(\d+)-(\d+)-(\d+)\.atlas""")

        /** The window a file of the windows' folder holds, by its name; null for another file. */
        fun windowOf(file: File): MapCore.Window? {
            val m = NAME.matchEntire(file.name) ?: return null
            val (z, x, y) = m.destructured
            return MapCore.Window(z.toInt(), x.toInt(), y.toInt())
        }
    }
}
