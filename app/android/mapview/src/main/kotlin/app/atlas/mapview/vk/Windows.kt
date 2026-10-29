package app.atlas.mapview.vk

import android.util.Log
import app.atlas.mapcore.MapCore
import app.atlas.mapcore.UserEdit
import app.atlas.mapview.EngineFrames
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The windows the phone builds (ENGINE.md step 3: the core asks, the app fetches): one L14 tile of the map each, built
 * by the core from OpenStreetMap's data of its box. The core says which it wants, nearest first
 * ([MapCore.wantedWindows]); [step] takes the nearest one not under way and not failed lately, fetches its data with
 * [fetch] (the app's Overpass servers, one query at a time) into a file beside it, has the core build it from that
 * file, which it maps (a dense window's data is tens of MB, which never pass through the app's heap), into [dir] as
 * `14-<x>-<y>.atlas`, keeps the data beside it (`14-<x>-<y>.json.gz`), and says so, so the map hands its packs again
 * ([PackList] lists the file; the core takes it into its frame cell's pack). One window at a time, on the caller's
 * background thread.
 *
 * A window built again after a week: where the core wants none, [step] takes the nearest window drawn in view
 * ([MapCore.windowsInView]) whose data was fetched or checked more than [REFRESH_MS] ago, asks Overpass with [fetchDiff]
 * only for what changed since the kept data's date (less [MARGIN_MS], for edits Overpass took in after that date), and
 * has the core merge the changes into the kept data and build the window from it: the window a fresh answer of that
 * date builds. Nothing changed: the data is only marked checked. The window is fetched whole instead where it keeps no
 * data, where its data was last fetched whole more than [WHOLE_MS] ago (so a change a diff could miss lives a month at
 * most), and where no server gives the changes or the core cannot merge them exactly. An answer older than the kept
 * data is never built: the window stays as it is until a server catches up.
 *
 * A window that failed waits before it is tried again, so the public servers are never asked for the same data over
 * and over: one whose data did not come (no server answered, or only with older data) [RETRY_MS], then twice as long
 * after each failure in a row, [RETRY_CAP_MS] at most; one whose data the core refused, [REFUSED_MS] (the same data and
 * the same core refuse it again; the app's next start, perhaps with a newer core, tries it at once).
 *
 * Nor is a window built lately fetched again where the core still wants its place: one that holds nothing to draw (the
 * open sea far from a coast, the middle of a field: [PackList] does not list it, so the core holds no tile there), one
 * the core did not take into its frame cell, or one built while the map has not handed the core its packs again. It is
 * fetched whole again once its data is [REFRESH_MS] old, and never built from data older than what it keeps. Until 22
 * October such a window was fetched again every half second while its place was in view.
 *
 * A window of another version than a window built now (another stage P or style: an earlier APK built it) is not
 * drawn: [PackList] does not list it, so a frame cell's windows are of one version (the core refuses a window of another
 * version than its cell's). A new version of Atlas is installed fresh, without such windows.
 *
 * A window the core wants that has none on the phone comes from its country's tiles first ([tiles]: every window of a
 * country as the phone's own core builds it from the windows' data of a date, published on GitHub; ENGINE.md step 3,
 * one pack per country): its pack, read by an HTTP range, is written in its place, with what it was built from beside
 * it (`14-<x>-<y>.tiles`: the data's date, the file's time when it came); a window the tiles hold with nothing to draw
 * is marked by that file alone, and like a built window that holds nothing it is not listed and not asked for again
 * until it is due. A window taken from the tiles keeps no data. Once it is due, or the user's edits reach it, the
 * windows' data of its release (the data its pack was built from, [packs]) becomes its data, without a build, and it
 * goes on as a window built from data: Overpass's changes since that date, then the edits. Where no pack gives that
 * release's data, its data comes as any window's, never older than its tiles'. Only where no country's tiles hold a
 * window (another core built them, or none reaches its place) or its bytes did not come is it built from data at once.
 *
 * A window's data comes from Atlas's own packs first ([packs]: built on GitHub from Geofabrik's extracts, the answer
 * Overpass gives the window's query as of the extract's date, gzipped), where one holds the window, and from Overpass
 * only where none does or its data did not come (ENGINE.md: the sources for many users; on 27 October 2026 every public
 * Overpass server failed, and the map had nothing to draw beyond the bundled packs). A window fetched whole again takes
 * a pack's data only when it is newer than the data it keeps. Data from a pack counts as fetched when it came, so the
 * window is asked for Overpass's changes since the pack's date a week later, as any other.
 *
 * The user's own edits to OpenStreetMap waiting on the phone ([edits]: FillIn's, until the map's data carries them;
 * ENGINE.md step 6) are drawn as mapped: every window is built from its data with them ([Core.edit]), and the data kept
 * is the data as it came, so a window's next diff is asked from its own date. Where the user edits what stands at a
 * place ([edited]), the windows whose data reaches it are built again from their kept data with the edits, before
 * anything else and with no server asked.
 */
class Windows(
    private val dir: File,
    /**
     * Overpass's JSON for a box (south, west, north, east), the window's query ([MapCore.Window.box]), streamed into the
     * file given; throws when no server answers.
     */
    private val fetch: (DoubleArray, File) -> Unit,
    /**
     * Overpass's changes to a box's data since a date (its XML; the dates as Overpass writes them), from a server whose
     * data is not older than the second date; throws when none answers. Null: windows are fetched whole again.
     */
    private val fetchDiff: ((DoubleArray, String, String) -> ByteArray)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The user's edits to draw: pending or sent, oldest first; the app's EditQueue. */
    private val edits: () -> List<UserEdit> = { emptyList() },
    /**
     * A window's data from Atlas's own packs, newer than the date given where one is ([Packed]); null where no pack
     * holds it (or none newer); throws where a pack holds it but its data did not come whole. Null: Overpass alone.
     */
    private val packs: ((MapCore.Window, Instant?) -> Packed?)? = null,
    /**
     * A window's pack from its country's tiles, as the phone's own core builds it ([Tiled]); null where no country's
     * tiles hold it (or another core built them); throws where they hold it but its bytes did not come whole. Null: none.
     */
    private val tiles: ((MapCore.Window) -> Tiled?)? = null,
) {
    /** A window's data from one of Atlas's packs: Overpass's answer for its box as of [date], gzipped, from pack [from]. */
    class Packed(val gz: ByteArray, val date: Instant, val from: String)

    /**
     * A window's pack from its country's tiles: the [pack]'s bytes, none where the window holds nothing to draw, built
     * from the windows' data of [date], from the tiles of pack [from].
     */
    class Tiled(val pack: ByteArray, val date: Instant, val from: String)

    /** What [step] asks of the core: [MapCore]'s, or a test's. */
    interface Core {
        fun wanted(): List<MapCore.Window>
        fun inView(): List<MapCore.Window>
        fun box(w: MapCore.Window): DoubleArray
        /** Builds window [w] from the JSON in [json] into the pack [file]: "" or why not. */
        fun build(w: MapCore.Window, json: File, file: File): String
        /**
         * The kept data [kept] merged with [diff] into [out]: true; false where nothing changed (nothing written); throws
         * IllegalStateException where it cannot merge exactly.
         */
        fun merge(w: MapCore.Window, kept: File, diff: ByteBuffer, out: File): Boolean

        /**
         * The data [kept] with the user's [edits] ([UserEdit.json]) into [out]: true; false where none applies to it
         * (nothing written); throws IllegalStateException where they do not read.
         */
        fun edit(w: MapCore.Window, kept: File, edits: ByteBuffer, out: File): Boolean = false
    }

    private class Of(private val core: MapCore) : Core {
        override fun wanted() = core.wantedWindows()
        override fun inView() = core.windowsInView()
        override fun box(w: MapCore.Window) = w.box()
        override fun build(w: MapCore.Window, json: File, file: File) = w.build(json, file)
        override fun merge(w: MapCore.Window, kept: File, diff: ByteBuffer, out: File) = w.merge(kept, diff, out)
        override fun edit(w: MapCore.Window, kept: File, edits: ByteBuffer, out: File) = w.edit(kept, edits, out)
    }

    /** A window that failed lately: when it last did, whether the core refused its data, and how often in a row. */
    private class Failure(val at: Long, val refused: Boolean, val times: Int)

    /**
     * What the windows do now, for the map's status where the core has nothing to draw yet: fetching a window's pack
     * from its country's tiles or its data from OpenStreetMap's servers, building it, or waiting after its data did not
     * come ([Waiting.until], on [clock]'s time) or the core refused it.
     */
    sealed interface Status {
        data object Idle : Status

        data class Fetching(val since: Long) : Status

        data class Building(val since: Long) : Status

        data class Waiting(val until: Long, val refused: Boolean) : Status
    }

    /** What the windows do now ([Status]); written on the windows' thread, read by the map's status. */
    @Volatile
    var status: Status = Status.Idle
        private set

    /** Milliseconds until [s]'s next try, 0 when it is due. */
    fun remaining(s: Status.Waiting): Long = (s.until - clock()).coerceAtLeast(0L)

    private val failed = ConcurrentHashMap<MapCore.Window, Failure>()
    private val busy = AtomicBoolean(false)

    /** The places the user edited lately ([edited]): latitude and longitude, their windows not yet looked up. */
    private val touched = ConcurrentLinkedQueue<DoubleArray>()

    /** The windows to build again with the user's edits, before anything else; found on a step from [touched]. */
    private val toEdit = ArrayDeque<MapCore.Window>()

    /**
     * The user edited what stands at ([lat], [lon]), or took an edit back: the windows built here whose data reaches that
     * place are built again from their kept data with [edits] on the next steps, one a step, before anything else.
     */
    fun edited(lat: Double, lon: Double) {
        touched.add(doubleArrayOf(lat, lon))
    }

    /** Whether window [w] may be tried at [now]: it has not failed lately, or it has waited long enough since. */
    private fun ready(w: MapCore.Window, now: Long): Boolean {
        val f = failed[w] ?: return true
        return now - f.at > waitAfter(f.refused, f.times)
    }

    /** Window [w] failed at [now]: [refused], the core refused its data; else its data did not come. */
    private fun fail(w: MapCore.Window, now: Long, refused: Boolean) {
        val times = (failed[w]?.times ?: 0) + 1
        failed[w] = Failure(now, refused, times)
        status = Status.Waiting(now + waitAfter(refused, times), refused)
    }

    /**
     * Takes or builds the nearest window [core] wants, else refreshes the nearest aged one in view; true when one was
     * written (the packs are to be handed again).
     */
    fun step(core: MapCore): Boolean = step(Of(core))

    fun step(core: Core): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        // built behind the screen: at background priority while it is (a shared I/O thread: its own given back after),
        // so a window's seconds of work never take a core from the frames
        val priority = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            val now = clock()
            nextEdited(core)?.let { return rebuild(core, it, now) }
            val wanted = core.wanted()
            wanted.firstOrNull { ready(it, now) && !builtLately(it, now) }?.let { w ->
                // none on the phone: its country's pack, before any data
                if (!fileOf(w).exists() && !tiledOf(w).exists()) tiled(w, now)?.let { return it }
                return whole(core, w, now, keptBase(w))
            }
            // nothing to fetch now: the windows wait on a failure where the core still wants one, else rest
            if (wanted.none { !builtLately(it, now) }) status = Status.Idle
            val aged = core.inView().firstOrNull { ready(it, now) && aged(it, now) } ?: return false
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

    /**
     * What window [w] keeps while its pack is its country's tiles' and it keeps no data: the date of the data the pack
     * was built from and the pack it came from; the file's time is when it came. Without a pack beside it, the window
     * holds nothing to draw.
     */
    fun tiledOf(w: MapCore.Window): File = File(dir, "${w.z}-${w.x}-${w.y}.tiles")

    /**
     * Whether window [w] was built or taken from the tiles, or its data checked, within [REFRESH_MS]: its pack (or the
     * mark that it holds nothing) is there though the core may still want its place (it holds nothing to draw, the core
     * did not take it, or the map has not handed it over yet).
     */
    private fun builtLately(w: MapCore.Window, now: Long): Boolean = (fileOf(w).exists() || tiledOf(w).exists()) && !aged(w, now)

    /**
     * The date of what window [w] holds: of the data it keeps, else of its pack from the tiles; null where neither
     * reads.
     */
    private fun keptBase(w: MapCore.Window): Instant? =
        dataOf(w).takeIf { it.exists() }?.let { runCatching { headOf(it, true) }.getOrNull()?.base } ?: tiledBase(w)

    /** The date of the data window [w]'s pack from the tiles was built from; null where it keeps none from the tiles. */
    private fun tiledBase(w: MapCore.Window): Instant? = tiledOf(w).takeIf { it.exists() }?.let { f ->
        runCatching { Instant.parse(f.readText().trim().substringBefore(' ')) }.getOrNull()
    }

    /**
     * Whether window [w]'s data was fetched or checked more than [REFRESH_MS] ago (where it keeps none, when its pack came
     * from the tiles, else its pack's age).
     */
    private fun aged(w: MapCore.Window, now: Long): Boolean {
        val data = dataOf(w)
        val tiled = tiledOf(w)
        val at = when {
            data.exists() -> data.lastModified()
            tiled.exists() -> tiled.lastModified()
            else -> fileOf(w).lastModified()
        }
        return at > 0 && now - at > REFRESH_MS
    }

    /** Window [w]'s working files beside its pack: its answer as it comes, its kept data unzipped, a merge's result. */
    private fun workOf(w: MapCore.Window, what: String): File = File(dir, "${w.z}-${w.x}-${w.y}.$what.json")

    /**
     * The next window built here, or taken from the tiles, whose data reaches a place the user edited ([edited]), by its
     * name; null when none is left. Each place's windows are looked up once, by the boxes [core] gives them.
     */
    private fun nextEdited(core: Core): MapCore.Window? {
        while (true) {
            val at = touched.poll() ?: break
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                val w = windowOf(f) ?: continue
                if (w in toEdit || (!dataOf(w).exists() && !tiledOf(w).exists())) continue
                val b = runCatching { core.box(w) }.getOrNull() ?: continue
                if (at[0] >= b[0] && at[0] <= b[2] && at[1] >= b[1] && at[1] <= b[3]) toEdit.addLast(w)
            }
        }
        return toEdit.removeFirstOrNull()
    }

    /**
     * Builds window [w] again from the data it keeps, on the phone, with the user's edits: no server is asked, and its
     * data keeps its date; one that does not build keeps the pack it has. A window taken from the tiles keeps no data:
     * the windows' data of its release becomes its data first, else data no older comes as for any window, and it is
     * built from that with the edits. True when it was built (the packs are to be handed again).
     */
    private fun rebuild(core: Core, w: MapCore.Window, now: Long): Boolean {
        if (!dataOf(w).exists()) {
            val date = tiledBase(w) ?: return false
            val got = ask(w, null)
            if (got == null || !adopt(w, got, date)) return whole(core, w, now, date, asked = true, got = got)
        }
        val kept = workOf(w, "kept")
        val edited = workOf(w, "edited")
        try {
            val data = dataOf(w)
            val read = data.exists() && runCatching { unzip(data, kept) }.isSuccess
            val t0 = clock()
            val why = if (read) core.build(w, withEdits(core, w, kept, edited), fileOf(w)) else "it keeps no data that reads"
            if (why.isEmpty()) {
                Log.i(EngineFrames.TAG, "vk window ${w.id}: for the user's edits, built again from its kept data in ${clock() - t0} ms")
                return true
            }
            Log.w(EngineFrames.TAG, "vk window ${w.id}: for the user's edits, not built again ($why)")
            return false
        } finally {
            kept.delete()
            edited.delete()
        }
    }

    /**
     * What window [w] is built from: the data in [json] with the user's [edits] where any applies to it, written to
     * [edited], else [json] itself. Edits that do not read leave the window as its data has it.
     */
    private fun withEdits(core: Core, w: MapCore.Window, json: File, edited: File): File {
        val list = runCatching { edits() }.getOrDefault(emptyList())
        if (list.isEmpty()) return json
        return try {
            if (core.edit(w, json, UserEdit.json(list), edited)) edited else json
        } catch (e: IllegalStateException) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: the user's edits are left out: ${e.message}")
            json
        }
    }

    /**
     * Window [w]'s pack from its country's tiles, written in its place with what it was built from beside it: true;
     * false where the window holds nothing to draw (marked so: it is not asked for again until it is due); null where
     * no country's tiles give it, or its bytes did not come whole, so its data is fetched as where none holds it.
     */
    private fun tiled(w: MapCore.Window, now: Long): Boolean? {
        val source = tiles ?: return null
        val t0 = clock()
        status = Status.Fetching(t0)
        val got = try {
            source(w) ?: return null
        } catch (e: Exception) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: no pack from the tiles: ${e.message}; fetching its data")
            return null
        }
        try {
            dir.mkdirs()
            // the pack first: a mark without a pack beside it says the window holds nothing to draw
            if (got.pack.isNotEmpty()) write(fileOf(w), got.pack)
            write(tiledOf(w), "${got.date} ${got.from}\n".toByteArray())
            tiledOf(w).setLastModified(now)
        } catch (e: java.io.IOException) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: the pack from the tiles of ${got.from} was not written: ${e.message}")
            return null
        }
        failed.remove(w)
        status = Status.Idle
        val what = if (got.pack.isNotEmpty()) "${got.pack.size} bytes" else "nothing to draw"
        Log.i(EngineFrames.TAG, "vk window ${w.id}: $what from the tiles of ${got.from} (their data of ${got.date}) in ${clock() - t0} ms")
        return got.pack.isNotEmpty()
    }

    /**
     * Window [w]'s data from the packs, newer than [after] where one is given; null where no pack gives it, or its data
     * did not come whole.
     */
    private fun ask(w: MapCore.Window, after: Instant?): Packed? {
        val source = packs ?: return null
        status = Status.Fetching(clock())
        return try {
            source(w, after)
        } catch (e: Exception) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: no data from the packs: ${e.message}")
            null
        }
    }

    /**
     * Keeps [got], the windows' data of window [w]'s release where its [date] is that of its pack from the tiles, as its
     * data, without building it: its pack is what that data builds. True when kept. The data counts as fetched when
     * the pack came, so a window due stays due until its changes since are asked for.
     */
    private fun adopt(w: MapCore.Window, got: Packed, date: Instant): Boolean {
        if (got.date != date) return false
        val data = dataOf(w)
        val came = tiledOf(w).lastModified()
        try {
            write(data, got.gz)
            if (headOf(data)?.base != date) throw java.io.IOException("it says another date than its pack's")
        } catch (e: java.io.IOException) {
            data.delete()
            Log.w(EngineFrames.TAG, "vk window ${w.id}: the data of pack ${got.from} does not read: ${e.message}")
            return false
        }
        data.setLastModified(came)
        tiledOf(w).delete()
        Log.i(EngineFrames.TAG, "vk window ${w.id}: the data its pack from the tiles was built from kept (${got.gz.size} bytes of the pack ${got.from})")
        return true
    }

    /**
     * Fetches window [w]'s data whole, from a pack where one holds it, else from Overpass, and builds it; with [kept] (the
     * date of what it holds), never from older data. [asked]: the packs were asked already, and gave [got].
     */
    private fun whole(core: Core, w: MapCore.Window, now: Long, kept: Instant?, asked: Boolean = false, got: Packed? = null): Boolean {
        dir.mkdirs()
        val answer = workOf(w, "answer")
        try {
            val t0 = clock()
            status = Status.Fetching(t0)
            packed(w, kept, answer, if (asked) got else ask(w, if (dataOf(w).exists()) kept else null), t0)?.let { how ->
                return build(core, w, answer, now, how)
            }
            try {
                fetch(core.box(w), answer)
            } catch (e: Exception) {
                fail(w, now, refused = false)
                Log.w(EngineFrames.TAG, "vk window ${w.id}: no data: ${e.message}")
                return false
            }
            val date = runCatching { headOf(answer, false) }.getOrNull()?.base
            if (kept != null && (date == null || date.isBefore(kept))) {
                fail(w, now, refused = false)
                Log.w(EngineFrames.TAG, "vk window ${w.id}: the data that came is from $date, older than the window's $kept")
                return false
            }
            return build(core, w, answer, now, "${answer.length()} bytes fetched in ${clock() - t0} ms")
        } finally {
            answer.delete()
        }
    }

    /**
     * Window [w]'s data from a pack ([data], what the packs gave since [t0]) into [answer]: how it came; null where they
     * gave none to build from (Overpass is asked then). A window that keeps data takes only data newer than its own
     * ([kept]); one taken from the tiles keeps none, and takes data no older than its tiles', its release's among them
     * (its pack is what that data builds, and the window is built from data from then on).
     */
    private fun packed(w: MapCore.Window, kept: Instant?, answer: File, data: Packed?, t0: Long): String? {
        if (data == null) return null
        if (kept != null && (if (dataOf(w).exists()) !data.date.isAfter(kept) else data.date.isBefore(kept))) return null
        try {
            GZIPInputStream(data.gz.inputStream(), 1 shl 16).use { input ->
                answer.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
            }
        } catch (e: java.io.IOException) {
            Log.w(EngineFrames.TAG, "vk window ${w.id}: the data of pack ${data.from} does not read: ${e.message}; asking Overpass")
            return null
        }
        return "${data.gz.size} bytes of the pack ${data.from} (its data of ${data.date}) in ${clock() - t0} ms"
    }

    /** Builds window [w] from the JSON in [json], with the user's edits, and keeps the data beside it, without them. */
    private fun build(core: Core, w: MapCore.Window, json: File, now: Long, how: String): Boolean {
        val t1 = clock()
        status = Status.Building(t1)
        val edited = workOf(w, "edited")
        val why = try {
            core.build(w, withEdits(core, w, json, edited), fileOf(w))
        } finally {
            edited.delete()
        }
        if (why.isNotEmpty()) {
            fail(w, now, refused = true)
            Log.w(EngineFrames.TAG, "vk window ${w.id}: $why; tried again in a day")
            return false
        }
        val built = clock() - t1
        val kept = runCatching {
            keep(json, dataOf(w))
            dataOf(w).setLastModified(now)
        }
        // built from data: no longer the tiles' pack
        tiledOf(w).delete()
        failed.remove(w)
        status = Status.Idle
        Log.i(
            EngineFrames.TAG,
            "vk window ${w.id}: $how, built in $built ms" +
                (kept.exceptionOrNull()?.let { ", its data not kept: ${it.message}" } ?: ", its data kept"),
        )
        return true
    }

    /**
     * Builds window [w] again from its kept data and Overpass's changes since, else whole. A window taken from the tiles
     * keeps no data: the windows' data of its release becomes its data first, and the changes since its date are asked
     * for; where no pack gives that data, it is fetched whole, never from data older than its tiles'.
     */
    private fun refresh(core: Core, w: MapCore.Window, now: Long): Boolean {
        val diffs = fetchDiff
        val data = dataOf(w)
        if (!data.exists()) {
            tiledBase(w)?.let { date ->
                // without the changes to ask for, it is fetched whole, as any window then
                if (diffs == null) return whole(core, w, now, date)
                val got = ask(w, null)
                if (got == null || !adopt(w, got, date)) return whole(core, w, now, date, asked = true, got = got)
            }
        }
        val head = if (data.exists()) runCatching { headOf(data, true) }.getOrNull() else null
        if (diffs == null || head == null) return whole(core, w, now, head?.base)
        if (now - head.whole.toEpochMilli() > WHOLE_MS) {
            Log.i(EngineFrames.TAG, "vk window ${w.id}: its data was fetched whole on ${head.whole}: fetched whole again")
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
        val kept = workOf(w, "kept")
        val merged = workOf(w, "merged")
        try {
            runCatching { unzip(data, kept) }.onFailure {
                Log.w(EngineFrames.TAG, "vk window ${w.id}: its data does not read: ${it.message}; fetched whole")
                return whole(core, w, now, head.base)
            }
            val changed = try {
                core.merge(w, kept, direct(diff), merged)
            } catch (e: IllegalStateException) {
                Log.w(EngineFrames.TAG, "vk window ${w.id}: ${e.message}; fetched whole")
                return whole(core, w, now, head.base)
            }
            if (!changed) {
                data.setLastModified(now)
                failed.remove(w)
                Log.i(EngineFrames.TAG, "vk window ${w.id}: nothing changed since $since (${diff.size} bytes in $fetched ms)")
                return false
            }
            return build(core, w, merged, now, "the changes since $since fetched (${diff.size} bytes) in $fetched ms, merged")
        } finally {
            kept.delete()
            merged.delete()
        }
    }

    /** What a window's data says of itself: its date, and when it was last fetched whole (the core's merges say). */
    data class Head(val base: Instant, val whole: Instant) {
        companion object {
            private val BASE = Regex(""""timestamp_osm_base"\s*:\s*"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"""")
            private val WHOLE = Regex(""""whole"\s*:\s*"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ)"""")

            /** The head of Overpass's JSON (or a merge's); null where it says no date. */
            fun of(json: ByteArray): Head? = of(String(json, 0, minOf(json.size, HEAD_BYTES), Charsets.UTF_8))

            private fun of(text: String): Head? {
                val base = BASE.find(text)?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() } ?: return null
                val whole = WHOLE.find(text)?.let { runCatching { Instant.parse(it.groupValues[1]) }.getOrNull() } ?: base
                return Head(base, whole)
            }

            private const val HEAD_BYTES = 2048
        }
    }

    companion object {
        /** How long a window whose data did not come waits before it is asked for again, after its first failure. */
        const val RETRY_MS = 5 * 60 * 1000L

        /** The longest a window whose data did not come waits, however often it failed in a row: two hours. */
        const val RETRY_CAP_MS = 2 * 3600 * 1000L

        /** How long a window whose data the core refused waits before it is fetched and built again: a day. */
        const val REFUSED_MS = 24 * 3600 * 1000L

        /**
         * How long a window waits after its [times]th failure in a row: [REFUSED_MS] where the core [refused] its data,
         * else [RETRY_MS], twice as long after each failure in a row, [RETRY_CAP_MS] at most.
         */
        fun waitAfter(refused: Boolean, times: Int): Long =
            if (refused) REFUSED_MS else minOf(RETRY_MS shl (times - 1).coerceIn(0, 10), RETRY_CAP_MS)

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

        /** Writes [bytes] into [file], by a file beside it renamed once whole. */
        internal fun write(file: File, bytes: ByteArray) {
            val part = File(file.path + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) {
                part.delete()
                throw java.io.IOException("$part could not be renamed")
            }
        }

        /** Gzips the data in [json] into [file], by a file beside it renamed once whole. */
        internal fun keep(json: File, file: File) {
            val part = File(file.path + ".part")
            json.inputStream().buffered(1 shl 16).use { input ->
                GZIPOutputStream(part.outputStream().buffered(1 shl 16), 1 shl 16).use { input.copyTo(it, 1 shl 16) }
            }
            if (!part.renameTo(file)) {
                part.delete()
                throw java.io.IOException("$part could not be renamed")
            }
        }

        /** What a window's data file says of itself, from its first bytes ([zipped]: a kept one, gzipped). */
        internal fun headOf(file: File, zipped: Boolean = true): Head? =
            (if (zipped) GZIPInputStream(file.inputStream(), 1 shl 12) else file.inputStream()).use { input ->
                val head = ByteArray(2048)
                var n = 0
                while (n < head.size) {
                    val r = input.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                Head.of(head.copyOf(n))
            }

        /** A kept data file unzipped into [into], for the core to map. */
        internal fun unzip(file: File, into: File) {
            GZIPInputStream(file.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
                into.outputStream().buffered(1 shl 16).use { input.copyTo(it, 1 shl 16) }
            }
        }
    }
}
