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
 * Each window read is held as its places alone (what a region's store would keep: [LocalData.keeps]), the last [HELD]
 * of them, so a lookup beside the last reads no file again; a kept window is read again once its file changes. A lookup
 * reads at most [READ_MAX] windows it does not hold yet, nearest its middle first, and asks the packs for at most
 * [FETCH_MAX]: a wide circle is held in part, and its answer says so.
 */
class WindowPlaces(
    private val dir: () -> File?,
    /** Window ([x], [y])'s data in Atlas's packs, gzipped; null where no pack holds it. Blocks; throws where it cannot be read (offline). */
    private val packed: (x: Int, y: Int) -> ByteArray? = { _, _ -> null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The places of the windows meeting a box that the phone holds, and which windows they are. */
    class Held internal constructor(val places: List<Place>, private val windows: Set<Long>) {
        /** Whether these windows hold the whole of [box], with the places [also] says it holds whole (a pack). */
        fun covers(box: BBox, also: (BBox) -> Boolean = { false }): Boolean =
            Mercator.tilesIn(box, LEVEL).all { key(it.x, it.y) in windows || also(it.bbox) }
    }

    private class Window(val stamp: String, val places: List<Place>)

    private val cache = object : LinkedHashMap<Long, SoftReference<Window>>(HELD, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SoftReference<Window>>?): Boolean = size > HELD
    }

    /** The windows no pack holds, and when that was found: not asked again for [NONE_MS]. */
    private val none = HashMap<Long, Long>()
    private val reading = HashMap<Long, Deferred<Unit>>()
    private val permits = Semaphore(PARALLEL)

    /**
     * The places of the windows meeting [box] that the phone holds: their kept data, or a window's data from the packs
     * read lately; nearest [centre] first. Null where it holds none. Reads files: call it off the main thread.
     */
    fun held(box: BBox, centre: LatLon = box.center): Held? {
        val range = Range.of(box)
        val kept = keptIn(range)
        val near = (kept.keys + synchronized(cache) { cache.keys.filter { range.has(it) } }).distinct().sortedBy { distance(it, centre) }
        val windows = HashSet<Long>()
        val places = ArrayList<Place>()
        var read = 0
        for (k in near) {
            val file = kept[k]
            val w = if (file != null) {
                val stamp = "${file.length()}-${file.lastModified()}"
                cached(k, stamp) ?: if (read < READ_MAX) {
                    read++
                    runCatching { file.inputStream().use { placesOf(it) } }.getOrNull()?.let { put(k, Window(stamp, it)) }
                } else null
            } else cached(k, PACKED)
            if (w == null) continue
            windows += k
            places += w.places
        }
        if (windows.isEmpty()) return null
        return Held(places.distinctBy { it.key }, windows)
    }

    /**
     * [held], once the windows meeting [box] the phone keeps no data for have come from Atlas's packs, nearest [centre]
     * first, at most [FETCH_MAX], waited for at most [withinMs] (a read that takes longer goes on alone, and the next
     * lookup holds it).
     */
    suspend fun fetched(box: BBox, centre: LatLon, withinMs: Long): Held? {
        val range = Range.of(box)
        val kept = keptIn(range).keys
        val now = clock()
        val wanted = Mercator.tilesIn(box, LEVEL).asSequence()
            .map { key(it.x, it.y) }
            .filter { k -> k !in kept && cached(k, PACKED) == null && synchronized(none) { none[k]?.let { now - it < NONE_MS } != true } }
            .sortedBy { distance(it, centre) }
            .take(FETCH_MAX)
            .toList()
        if (wanted.isNotEmpty()) {
            val reads = wanted.map(::read)
            withTimeoutOrNull(withinMs) { reads.awaitAll() }
        }
        return held(box, centre)
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

    private fun put(k: Long, w: Window): Window = w.also { synchronized(cache) { cache[k] = SoftReference(it) } }

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

        /** How many windows' places are held at once. */
        const val HELD = 16

        /** How many windows the phone keeps one lookup reads that it does not hold yet. */
        const val READ_MAX = 9

        /** How many windows one lookup asks Atlas's packs for. */
        const val FETCH_MAX = 9

        private const val PARALLEL = 3
        private const val NONE_MS = 10 * 60_000L
        private const val PACKED = "packed"
        private val NAME = Regex("^$LEVEL-(\\d+)-(\\d+)\\.json\\.gz$")

        private fun key(x: Int, y: Int): Long = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)
        private fun xOf(k: Long): Int = (k shr 32).toInt()
        private fun yOf(k: Long): Int = k.toInt()
        private fun distance(k: Long, centre: LatLon): Double = Geo.distance(centre, TileId(LEVEL, xOf(k), yOf(k)).bbox.center)

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
