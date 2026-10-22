package app.atlas.mapview.vk

import android.util.Log
import app.atlas.mapcore.MapCore
import app.atlas.mapview.EngineFrames
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The windows the phone builds (ENGINE.md step 3: the core asks, the app fetches): one L14 tile of the map each, built
 * by the core from OpenStreetMap's data of its box. The core says which it wants, nearest first
 * ([MapCore.wantedWindows]); [step] takes the nearest one not under way and not failed lately, fetches its data with
 * [fetch] (the app's Overpass servers, one query at a time), has the core build it into [dir] as `14-<x>-<y>.atlas`,
 * keeps the data beside it (`14-<x>-<y>.json.gz`), and says so, so the map hands its packs again ([PackList] lists the
 * file; the core takes it into its frame cell's pack). One window at a time, on the caller's background thread.
 *
 * A window built again after a week: where the core wants none, [step] takes the nearest window drawn in view
 * ([MapCore.windowsInView]) whose data was fetched or checked more than [REFRESH_MS] ago, asks Overpass with [fetchDiff]
 * only for what changed since the kept data's date (less [MARGIN_MS], for edits Overpass took in after that date), and
 * has the core merge the changes into the kept data and build the window from it: the window a fresh answer of that
 * date builds. Nothing changed: the data is only marked checked. The window is fetched whole instead where it keeps no
 * data, where its data was last fetched whole more than [WHOLE_MS] ago (so a change a diff could miss lives a month at
 * most), and where no server gives the changes or the core cannot merge them exactly. An answer older than the kept
 * data is never built: the window stays as it is until a server catches up.
 */
class Windows(
    private val dir: File,
    /** Overpass's JSON for a box (south, west, north, east): the window's query ([MapCore.Window.box]); throws when no server answers. */
    private val fetch: (DoubleArray) -> ByteArray,
    /**
     * Overpass's changes to a box's data since a date (its XML; the dates as Overpass writes them), from a server whose
     * data is not older than the second date; throws when none answers. Null: windows are fetched whole again.
     */
    private val fetchDiff: ((DoubleArray, String, String) -> ByteArray)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What [step] asks of the core: [MapCore]'s, or a test's. */
    interface Core {
        fun wanted(): List<MapCore.Window>
        fun inView(): List<MapCore.Window>
        fun box(w: MapCore.Window): DoubleArray
        /** "" or why not. */
        fun build(w: MapCore.Window, json: ByteBuffer, file: File): String
        /** The merged JSON, or null where nothing changed; throws IllegalStateException where it cannot merge exactly. */
        fun merge(w: MapCore.Window, kept: ByteBuffer, diff: ByteBuffer): ByteArray?
    }

    private class Of(private val core: MapCore) : Core {
        override fun wanted() = core.wantedWindows()
        override fun inView() = core.windowsInView()
        override fun box(w: MapCore.Window) = w.box()
        override fun build(w: MapCore.Window, json: ByteBuffer, file: File) = w.build(json, file)
        override fun merge(w: MapCore.Window, kept: ByteBuffer, diff: ByteBuffer) = w.merge(kept, diff)
    }

    private val failed = ConcurrentHashMap<MapCore.Window, Long>()
    private val busy = AtomicBoolean(false)

    /** Builds the nearest window [core] wants, else refreshes the nearest aged one in view; true when one was built (the packs are to be handed again). */
    fun step(core: MapCore): Boolean = step(Of(core))

    fun step(core: Core): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        // built behind the screen: at background priority while it is (a shared I/O thread: its own given back after),
        // so a window's seconds of work never take a core from the frames
        val priority = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            val now = clock()
            val ready = { w: MapCore.Window -> failed[w]?.let { now - it > RETRY_MS } ?: true }
            core.wanted().firstOrNull(ready)?.let { return whole(core, it, now, null) }
            val aged = core.inView().firstOrNull { ready(it) && aged(it, now) } ?: return false
            return refresh(core, aged, now)
        } finally {
            priority?.let { p -> runCatching { android.os.Process.setThreadPriority(p) } }
            busy.set(false)
        }
    }

    /** The file of window [w]. */
    fun fileOf(w: MapCore.Window): File = File(dir, "${w.z}-${w.x}-${w.y}.atlas")

    /** The data window [w] was last built from: Overpass's JSON of its query, as fetched or merged, gzipped. */
    fun dataOf(w: MapCore.Window): File = File(dir, "${w.z}-${w.x}-${w.y}.json.gz")

    /** Whether window [w]'s data was fetched or checked more than [REFRESH_MS] ago (its pack's age where it keeps none). */
    private fun aged(w: MapCore.Window, now: Long): Boolean {
        val data = dataOf(w)
        val at = if (data.exists()) data.lastModified() else fileOf(w).lastModified()
        return at > 0 && now - at > REFRESH_MS
    }

    /** Fetches window [w]'s data whole and builds it; with [kept] (the date of the data it holds), never from older data. */
    private fun whole(core: Core, w: MapCore.Window, now: Long, kept: Instant?): Boolean {
        val t0 = clock()
        val bytes = try {
            fetch(core.box(w))
        } catch (e: Exception) {
            failed[w] = now
            Log.w(EngineFrames.TAG, "vk window ${w.id}: no data: ${e.message}")
            return false
        }
        val date = Head.of(bytes)?.base
        if (kept != null && (date == null || date.isBefore(kept))) {
            failed[w] = now
            Log.w(EngineFrames.TAG, "vk window ${w.id}: the data that came is from $date, older than the window's $kept")
            return false
        }
        return build(core, w, bytes, now, "${bytes.size} bytes fetched in ${clock() - t0} ms")
    }

    /** Builds window [w] from [json] and keeps the data beside it. */
    private fun build(core: Core, w: MapCore.Window, json: ByteArray, now: Long, how: String): Boolean {
        dir.mkdirs()
        val t1 = clock()
        val why = core.build(w, direct(json), fileOf(w))
        if (why.isNotEmpty()) {
            failed[w] = now
            Log.w(EngineFrames.TAG, "vk window ${w.id}: $why")
            return false
        }
        val built = clock() - t1
        val kept = runCatching {
            keep(json, dataOf(w))
            dataOf(w).setLastModified(now)
        }
        failed.remove(w)
        Log.i(
            EngineFrames.TAG,
            "vk window ${w.id}: $how, built in $built ms" +
                (kept.exceptionOrNull()?.let { ", its data not kept: ${it.message}" } ?: ", its data kept"),
        )
        return true
    }

    /** Builds window [w] again from its kept data and Overpass's changes since, else whole. */
    private fun refresh(core: Core, w: MapCore.Window, now: Long): Boolean {
        val diffs = fetchDiff
        val data = dataOf(w)
        val head = if (data.exists()) runCatching { headOf(data) }.getOrNull() else null
        if (diffs == null || head == null) return whole(core, w, now, head?.base)
        if (now - head.whole.toEpochMilli() > WHOLE_MS) {
            Log.i(EngineFrames.TAG, "vk window ${w.id}: its data was fetched whole on ${head.whole}: fetched whole again")
            return whole(core, w, now, head.base)
        }
        val kept = runCatching { unzip(data) }.getOrElse {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: its data does not read: ${it.message}; fetched whole")
            return whole(core, w, now, head.base)
        }
        val since = head.base.minusMillis(MARGIN_MS).toString()
        val t0 = clock()
        val diff = try {
            diffs(core.box(w), since, head.base.toString())
        } catch (e: Exception) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: no changes since $since: ${e.message}; fetched whole")
            return whole(core, w, now, head.base)
        }
        val fetched = clock() - t0
        val merged = try {
            core.merge(w, kept, direct(diff))
        } catch (e: IllegalStateException) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: ${e.message}; fetched whole")
            return whole(core, w, now, head.base)
        }
        if (merged == null) {
            data.setLastModified(now)
            failed.remove(w)
            Log.i(EngineFrames.TAG, "vk window ${w.id}: nothing changed since $since (${diff.size} bytes in $fetched ms)")
            return false
        }
        return build(core, w, merged, now, "the changes since $since fetched (${diff.size} bytes) in $fetched ms, merged")
    }

    /** What a window's data says of itself: its date, and when it was last fetched whole (the core's merges say). */
    data class Head(val base: Instant, val whole: Instant) {
        companion object {
            private val BASE = Regex(""""timestamp_osm_base"\s*:\s*"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"""")
            private val WHOLE = Regex(""""whole"\s*:\s*"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"""")

            /** The head of Overpass's JSON (or a merge's); null where it says no date. */
            fun of(json: ByteArray): Head? = of(String(json, 0, minOf(json.size, HEAD_BYTES), Charsets.UTF_8))

            fun of(json: ByteBuffer): Head? {
                val b = json.duplicate()
                val head = ByteArray(minOf(b.remaining(), HEAD_BYTES))
                b.get(head)
                return of(head)
            }

            private fun of(text: String): Head? {
                val base = BASE.find(text)?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() } ?: return null
                val whole = WHOLE.find(text)?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() } ?: base
                return Head(base, whole)
            }

            private const val HEAD_BYTES = 2048
        }
    }

    companion object {
        /** How long a window whose data or build failed waits before it is tried again. */
        const val RETRY_MS = 5 * 60 * 1000L

        /** How old a window's data may grow before it is built again: a week. */
        const val REFRESH_MS = 7 * 24 * 3600 * 1000L

        /** How long ago a window's data may have been fetched whole before it is fetched whole again: four weeks. */
        const val WHOLE_MS = 28 * 24 * 3600 * 1000L

        /**
         * How far before the kept data's date the changes are asked from: an edit whose time lies before that date can
         * reach a server's data after it (an upload committed after the minute it began), and is then among the changes.
         */
        const val MARGIN_MS = 30 * 60 * 1000L

        private val NAME = Regex("""(\d+)-(\d+)-(\d+)\.atlas""")

        /** The window a file of the windows' folder holds, by its name; null for another file. */
        fun windowOf(file: File): MapCore.Window? {
            val m = NAME.matchEntire(file.name) ?: return null
            val (z, x, y) = m.destructured
            return MapCore.Window(z.toInt(), x.toInt(), y.toInt())
        }

        private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() as ByteBuffer

        /** Writes [json] gzipped to [file], by a file beside it renamed once whole. */
        internal fun keep(json: ByteArray, file: File) {
            val part = File(file.path + ".part")
            GZIPOutputStream(part.outputStream().buffered(1 shl 16), 1 shl 16).use { it.write(json) }
            if (!part.renameTo(file)) {
                part.delete()
                throw java.io.IOException("$part could not be renamed")
            }
        }

        /** What a kept data file says of itself, from its first bytes. */
        internal fun headOf(file: File): Head? = GZIPInputStream(file.inputStream(), 1 shl 12).use { input ->
            val head = ByteArray(2048)
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            Head.of(head.copyOf(n))
        }

        /** A kept data file, unzipped into a direct buffer (its size from the gzip trailer). */
        internal fun unzip(file: File): ByteBuffer {
            val size = RandomAccessFile(file, "r").use { f ->
                f.seek(f.length() - 4)
                val b = ByteArray(4)
                f.readFully(b)
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            }
            val out = ByteBuffer.allocateDirect(size.toInt())
            GZIPInputStream(file.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
                val chunk = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n > out.remaining()) throw java.io.IOException("$file holds more than its trailer says")
                    out.put(chunk, 0, n)
                }
            }
            if (out.hasRemaining()) throw java.io.IOException("$file holds less than its trailer says")
            return out.flip() as ByteBuffer
        }
    }
}
