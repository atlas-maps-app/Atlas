package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.model.Place
import app.atlas.render.data.OverpassJson
import app.atlas.render.data.PackFeatures
import app.atlas.tiles.WindowPacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.lang.ref.SoftReference
import java.util.zip.GZIPInputStream

/**
 * The places in the map's windows (ENGINE.md step 3: Overpass's answer for an L14 tile and 200 m around, every tagged
 * node, way and multipolygon), for the lookups where no region's store or pack on the phone holds a place: the data
 * "What's here" reads ([HereWindows]), which the user found the only lookup that works reliably (11 November: "The
 * long press whats nearby is the only lookup that works reliably"). The windows whose data the phone keeps ([dir]:
 * `14-<x>-<y>.json.gz`, beside each window the map built from data) answer offline and at once ([held]); online, those
 * it keeps none for (a window taken from its country's tiles) come from the window's data in Atlas's own packs
 * ([fetched], [packed]: one range of the country's windows file each), not from Overpass, whose one query at a time
 * the map's windows share.
 *
 * Each window read is held as its places alone (what a region's store would keep: [LocalData.keeps]), at most [HELD]
 * of them: those nearest the last lookup's middle, never one that lookup used or waits for. So a lookup beside the last
 * reads no file again, and a kept window is read again only once its file changes. A lookup reads at most [READ_MAX]
 * windows it does not hold yet, nearest its middle first, and none past the [HELD] nearest, which it could not hold
 * beside them; it asks the packs for at most [FETCH_MAX], as near, and waits for them where they make its box whole
 * ([fetched]); a lookup that asks Overpass beside them decides itself what it waits for ([start]). A wide circle is
 * held in part, and its answer says so. (The reviewer, 17 November 2026: held by
 * when they were last read, the windows of a wide circle pushed out those nearest the user, and a wide circle kept on
 * the phone was read again at every lookup.)
 */
class WindowPlaces(
    private val dir: () -> File?,
    /** Window ([x], [y])'s data in Atlas's packs, gzipped; null where no pack holds it. Blocks; throws where it cannot be read (offline). */
    private val packed: (x: Int, y: Int) -> ByteArray? = { _, _ -> null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Opens a kept window's file (a test counts what is read). */
    private val open: (File) -> InputStream = { it.inputStream() },
) {
    /** The places of the windows meeting a box that the phone holds, and which windows they are. */
    class Held internal constructor(val places: List<Place>, private val windows: Set<Long>) {
        /**
         * Whether these windows hold the whole of [box]: each of its windows, a pack's box beside them being no proof of
         * what the pack holds (the app maintainer, 18 November 2026, batch 5 A2: the Netherlands' reaches Antwerp).
         */
        fun covers(box: BBox): Boolean = Mercator.tilesIn(box, LEVEL).all { key(it.x, it.y) in windows }
    }

    private class Window(val stamp: String, val places: List<Place>)

    /**
     * The windows held, by window: at most [HELD], those nearest [middle], never one of [mine] nor one a lookup in flight
     * waits for ([waited]), so more while lookups wait; the heap may take any back.
     */
    private val cache = HashMap<Long, SoftReference<Window>>()

    /** The last lookup's middle, and the windows it used or waits for from the packs. Guarded by [cache]. */
    private var middle: LatLon? = null
    private var mine: Set<Long> = emptySet()

    /**
     * The windows each lookup in flight holds or waits for, with how many such lookups: none goes while one waits (the
     * app maintainer, 18 November 2026, batch 4 B13: a second lookup made its middle and its own the ones held, and
     * the first's windows went as they came). Guarded by [cache].
     */
    private val waited = HashMap<Long, Int>()

    /** The windows no pack holds, and when that was found: not asked again for [NONE_MS]. */
    private val none = HashMap<Long, Long>()
    private val reading = HashMap<Long, Deferred<Unit>>()
    private val permits = Semaphore(PARALLEL)

    /**
     * The places of the windows meeting [box] that the phone holds: their kept data, or a window's data from the packs
     * read lately; nearest [centre] first, from at most [HELD] windows. Null where it holds none. Reads files: call it
     * off the main thread.
     */
    fun held(box: BBox, centre: LatLon = box.center): Held? = look(box, centre, waiting = emptySet())

    /**
     * [held], once the windows meeting [box] the phone keeps no data for have come from Atlas's packs, where what they
     * send makes the box whole: a box of at most [HELD] windows, of which at most [FETCH_MAX] are missing, none of them
     * known to be in no pack, and at most [READ_MAX] kept unread. Waited for at most [withinMs] (a read that takes longer
     * goes on alone, and the next lookup holds it). Where they cannot make it whole the packs are asked all the same for
     * the nearest they can give, at most [FETCH_MAX], nearest [centre] first and none past the [HELD] nearest, but not
     * waited for, unless [waitForPart] (a caller that asks nothing after them): this is [held] at once. A caller that asks
     * Overpass for the whole beside them takes [start], and waits for them itself. A widening search around one middle
     * asks for the same nearest windows once. (The reviewer, 17 November 2026: nine windows were waited for, up to 10 s,
     * for a circle of any size; the app maintainer, 18 November 2026: and then, where the packs could not make a box
     * whole, none was read, so where Overpass failed the answer was empty where it had held the nine nearest.) A window
     * inside the box of a pack read beside them is read and counted as any other, the pack's box being no proof of what
     * it holds (the app maintainer, 18 November 2026, batch 5 A2: batch 4 B12 took those for the pack's, never asked or
     * read, so at a border inside a pack's box the places past it were lost).
     */
    suspend fun fetched(box: BBox, centre: LatLon, withinMs: Long, waitForPart: Boolean = false): Held? =
        start(box, centre, withinMs).use { f -> if (f.makesWhole || waitForPart) f.landed() else f.held() }

    /**
     * Asks Atlas's packs for the windows meeting [box] the phone keeps no data for, as [fetched] asks them, for a lookup
     * that asks Overpass for the whole beside them and decides itself what it waits for (the app maintainer, 18 November
     * 2026, batch 5 A1: where they could not make the box whole they were not waited for, so where Overpass failed at
     * once the answer held none of them, and where it was slow they showed only once it answered). [Fetch.landed] waits
     * for them at most [withinMs] from now. The lookup's windows are held as its own until the [Fetch] is closed.
     */
    fun start(box: BBox, centre: LatLon, withinMs: Long): Fetch {
        val until = System.nanoTime() + withinMs * 1_000_000
        val range = Range.of(box)
        val kept = keptIn(range)
        val now = clock()
        // the windows this lookup can hold, nearest its middle first: none past them is read or asked for
        val windows = range.closest(centre, HELD)
        val unread = windows.count { k -> kept[k]?.let { cached(k, stampOf(it)) == null } == true }
        val missing = windows.filter { k -> k !in kept && cached(k, PACKED) == null }
        val inNoPack = synchronized(none) { missing.filter { k -> none[k]?.let { now - it < NONE_MS } == true }.toSet() }
        val asked = missing.filter { it !in inNoPack }.take(FETCH_MAX)
        if (asked.isEmpty()) return Fetch(box, centre, emptyList(), emptyList(), makesWhole = false, until)
        val whole = range.size <= HELD && inNoPack.isEmpty() && missing.size <= FETCH_MAX && unread <= READ_MAX
        // this lookup's windows, those still to come among them, are held when they come, whatever lookup comes between
        synchronized(cache) {
            middle = centre
            mine = windows.toSet()
            for (k in windows) waited.merge(k, 1, Int::plus)
        }
        return Fetch(box, centre, windows, asked, whole, until)
    }

    /**
     * A lookup's windows asked of Atlas's packs ([start]): whether what they send can make its box whole, what the phone
     * holds of the box now, and what it holds once they came. Its windows are held as its own until it is closed.
     */
    inner class Fetch internal constructor(
        private val box: BBox,
        private val centre: LatLon,
        private val windows: List<Long>,
        private val asked: List<Long>,
        /** Whether the windows asked for make the box whole beside those held: worth their wait before Overpass is asked. */
        val makesWhole: Boolean,
        private val until: Long,
    ) : Closeable {
        private val reads = asked.map(::read)
        private var closed = false

        /** Whether any window was asked of the packs. */
        val asks: Boolean get() = asked.isNotEmpty()

        /** [held] now, the windows asked for held as this lookup's own when they come. */
        fun held(): Held? = look(box, centre, waiting = asked.toSet())

        /** [held] once the windows asked for came, waited for until the wait [start] was given is over. */
        suspend fun landed(): Held? {
            val left = (until - System.nanoTime()) / 1_000_000
            if (reads.isNotEmpty() && left > 0) withTimeoutOrNull(left) { reads.awaitAll() }
            return held()
        }

        override fun close() {
            synchronized(cache) {
                if (closed) return
                closed = true
                for (k in windows) waited.computeIfPresent(k) { _, n -> (n - 1).takeIf { it > 0 } }
                trim()
            }
        }
    }

    /** [held], [waiting] (windows of [box] asked of the packs) held as this lookup's own when they come. */
    private fun look(box: BBox, centre: LatLon, waiting: Set<Long>): Held? {
        val range = Range.of(box)
        val kept = keptIn(range)
        val near = (kept.keys + synchronized(cache) { cache.keys.filter { range.has(it) } }).distinct().sortedWith(nearest(centre))
        val used = LinkedHashSet<Long>()
        val read = HashMap<Long, Window>()
        val places = ArrayList<Place>()
        var reads = 0
        for (k in near) {
            // past the HELD nearest, a window could not be held beside them: it is not read, so not read at every lookup
            if (used.size == HELD) break
            val file = kept[k]
            val w = if (file != null) {
                val stamp = stampOf(file)
                cached(k, stamp) ?: if (reads < READ_MAX) {
                    reads++
                    runCatching { open(file).use { placesOf(it) } }.getOrNull()?.let { Window(stamp, it) }?.also { read[k] = it }
                } else null
            } else cached(k, PACKED)
            if (w == null) continue
            used += k
            places += w.places
        }
        keep(centre, used + waiting, read)
        if (used.isEmpty()) return null
        return Held(places.distinctBy { it.key }, used)
    }

    /** Reads window [k] from the packs, once however many lookups want it. */
    private fun read(k: Long): Deferred<Unit> = synchronized(reading) {
        reading[k] ?: scope.async<Unit>(start = CoroutineStart.LAZY) {
            try {
                permits.withPermit {
                    val bytes = packed(xOf(k), yOf(k))
                    if (bytes == null) synchronized(none) { none[k] = clock() }
                    else put(k, Window(PACKED, ByteArrayInputStream(bytes).use { placesOf(it) }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // offline, or the bytes did not come whole: asked again by the next lookup
            } finally {
                synchronized(reading) { reading.remove(k) }
            }
        }.also { reading[k] = it; it.start() }
    }

    private fun cached(k: Long, stamp: String): Window? = synchronized(cache) { cache[k]?.get()?.takeIf { it.stamp == stamp } }

    /** A window read from the packs, held beside the last lookup's. */
    private fun put(k: Long, w: Window) = synchronized(cache) {
        cache[k] = SoftReference(w)
        trim()
    }

    /** The windows a lookup at [centre] read ([read]) held beside the rest, its own ([mine]) never going. */
    private fun keep(centre: LatLon, mine: Set<Long>, read: Map<Long, Window>) = synchronized(cache) {
        middle = centre
        this.mine = mine
        for ((k, w) in read) cache[k] = SoftReference(w)
        trim()
    }

    /**
     * Over [HELD], the windows farthest from the last lookup's middle go, never one of its own nor one a lookup in flight
     * waits for; and those the heap took back. Under [cache]'s lock.
     */
    private fun trim() {
        cache.values.removeAll { it.get() == null }
        val over = cache.size - HELD
        val c = middle ?: return
        if (over <= 0) return
        for (k in cache.keys.filter { it !in mine && it !in waited }.sortedWith(nearest(c)).takeLast(over)) cache.remove(k)
    }

    /** The kept data files of the windows in [range], by window. */
    private fun keptIn(range: Range): Map<Long, File> {
        val d = dir() ?: return emptyMap()
        val out = HashMap<Long, File>()
        for (name in d.list().orEmpty()) {
            val m = NAME.matchEntire(name) ?: continue
            val k = key(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            if (!range.has(k)) continue
            val f = File(d, name)
            if (f.length() > 0) out[k] = f
        }
        return out
    }

    /** The tiles of a box at [LEVEL]: columns [x0]..[x1], rows [y0]..[y1]. */
    private class Range(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        fun has(k: Long): Boolean = xOf(k) in x0..x1 && yOf(k) in y0..y1

        /** How many windows the range holds. */
        val size: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)

        /**
         * Its [n] windows nearest [centre], nearest first: of those within [NEAR] windows of the one holding [centre] (or
         * of its window nearest it), never every window of a wide range.
         */
        fun closest(centre: LatLon, n: Int): List<Long> {
            val t = Mercator.tileAt(centre, LEVEL)
            val cx = t.x.coerceIn(x0, x1)
            val cy = t.y.coerceIn(y0, y1)
            val near = (maxOf(x0, cx - NEAR)..minOf(x1, cx + NEAR)).flatMap { x -> (maxOf(y0, cy - NEAR)..minOf(y1, cy + NEAR)).map { y -> key(x, y) } }
            return near.sortedWith(nearest(centre)).take(n)
        }

        companion object {
            fun of(box: BBox): Range {
                val a = Mercator.tileAt(LatLon(box.north, box.west), LEVEL)
                val b = Mercator.tileAt(LatLon(box.south, box.east), LEVEL)
                return Range(a.x, b.x, a.y, b.y)
            }
        }
    }

    companion object {
        /** The level of the map's windows. */
        const val LEVEL = WindowPacks.LEVEL

        /** How many windows the phone keeps one lookup reads that it does not hold yet. */
        const val READ_MAX = 9

        /** How many windows one lookup asks Atlas's packs for, where they make its box whole. */
        const val FETCH_MAX = 9

        /**
         * How many windows' places are held at once: all that one lookup can read and be sent ([READ_MAX] and
         * [FETCH_MAX]), so the largest box one lookup can make whole is held whole, and no larger box is asked of the
         * packs (the Where wheel's 30 minutes meet 9 to 16 windows at Amsterdam's latitude). Around Amsterdam's middle a
         * window's places take 0.2 to 12.6 MB of the heap, 3.2 on average (measured 17 November 2026): eighteen of
         * them, about 60 MB at most there, which the heap may take back.
         */
        const val HELD = READ_MAX + FETCH_MAX

        private const val PARALLEL = 3

        /** How far from a lookup's middle, in windows, its [HELD] nearest can lie: 13 by 13 hold them from any corner. */
        private const val NEAR = 6
        private const val NONE_MS = 10 * 60_000L
        private const val PACKED = "packed"
        private val NAME = Regex("^$LEVEL-(\\d+)-(\\d+)\\.json\\.gz$")

        private fun key(x: Int, y: Int): Long = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)
        private fun xOf(k: Long): Int = (k shr 32).toInt()
        private fun yOf(k: Long): Int = k.toInt()
        private fun boxOf(k: Long): BBox = TileId(LEVEL, xOf(k), yOf(k)).bbox
        private fun distance(k: Long, centre: LatLon): Double = Geo.distance(centre, boxOf(k).center)

        /** Windows nearest [centre] first; of two as near, the one of the lower key. */
        private fun nearest(centre: LatLon): Comparator<Long> = compareBy<Long> { distance(it, centre) }.thenBy { it }

        private fun stampOf(file: File): String = "${file.length()}-${file.lastModified()}"

        /** The places in a window's gzipped Overpass answer: what a region's store would keep ([LocalData.keeps]). */
        private fun placesOf(input: InputStream): List<Place> {
            val out = ArrayList<Place>()
            val p = OverpassJson.pull(GZIPInputStream(input, 1 shl 16).buffered(1 shl 16))
            p.beginObject()
            while (p.hasNext()) {
                if (p.nextName() != "elements") {
                    p.skipValue()
                    continue
                }
                p.beginArray()
                while (p.hasNext()) {
                    val e = OverpassJson.element(p)
                    val tags = e.tags ?: continue
                    if (LocalData.keeps(e, tags)) PackFeatures.place(e)?.let { out += it }
                }
                p.endArray()
            }
            return out
        }
    }
}
