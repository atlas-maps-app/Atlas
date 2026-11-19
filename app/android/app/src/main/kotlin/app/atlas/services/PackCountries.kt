package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.search.PlaceSource
import app.atlas.search.PlaceStore
import app.atlas.search.covers
import app.atlas.tiles.Pack
import app.atlas.tiles.PackCatalog
import app.atlas.ui.mine.offline.RegionFiles
import java.io.File
import java.util.Locale
import java.util.WeakHashMap

/**
 * The country of each of Atlas's published packs (ISO 3166-1 alpha-2), by the pack's id, as the published list names it
 * (`packs.json`'s `country`, for a pack of one country): what the map learned as it read the list ([learn]), else what
 * the copies of the list the phone keeps say ([copies]: Mine's, the windows'). Near a border the pack that holds a point
 * says its country before the countries' boxes do (the app maintainer, 18 November 2026, batch 4 A5): the smallest box
 * puts Maastricht, Eindhoven, Venlo and Terneuzen in Belgium.
 */
class PackCountries(
    private val regions: RegionFiles,
    private val copies: List<File>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var known: Map<String, String> = emptyMap()

    /**
     * How finely each published country's tiles hold a point, as far as their indexes are in memory; null until the map
     * is wired ([app.atlas.services.MapData]: [app.atlas.tiles.WindowTiles.level]). What the country question reads near
     * a border, never having an index read or fetched for it (the app maintainer, 18 November 2026, batch 5 A3: asking
     * a point's country started the download of its country's index, 1.5 MB for the Netherlands, which only the map's
     * camera asked for before).
     */
    @Volatile
    var tiles: Tiles? = null

    /** How finely a pack's tiles hold a place, from what is in memory alone. */
    fun interface Tiles {
        /**
         * The finest level at which pack [id]'s tiles list the place at [lat], [lon]: 14 its window, 13 or 12 a frame
         * cell's level tile over it, 0 none; null where the pack's index is not in memory.
         */
        fun level(id: String, lat: Double, lon: Double): Int?
    }

    /** When the copies were last read, so a pack the list names no country for does not read them at each ask. */
    private var readAt = 0L

    /** Takes the countries a list read names. */
    fun learn(packs: List<Pack>) {
        val more = packs.mapNotNull { p -> p.country?.trim()?.takeIf { it.length == 2 }?.let { p.id to it.uppercase(Locale.ROOT) } }
        if (more.isNotEmpty()) synchronized(this) { known = known + more }
    }

    /** Pack [id]'s country as far as it is known without reading a file, for a frame that cannot wait; null if not known. */
    fun now(id: String): String? = known[id]

    /** Pack [id]'s country: [now], else from the copies of the list on the phone, read again a minute on at most. Reads files: off the main thread. */
    fun of(id: String): String? {
        known[id]?.let { return it }
        synchronized(this) {
            val t = clock()
            if (readAt != 0L && t - readAt < REREAD_MS) return known[id]
            readAt = t
            for (f in copies) if (f.isFile) runCatching { learn(PackCatalog.parse(f.readText()).packs) }
        }
        return known[id]
    }

    /**
     * The pack each store was taken from ([ofStore]), as its region's file named it, "" for a region taken from none;
     * a store let go is forgotten.
     */
    private val packs = WeakHashMap<PlaceSource, String>()

    /** The time and length of each store's region's file as [packs] read it: written since, it is read again. */
    private val stamps = WeakHashMap<PlaceSource, Stamp>()

    /** A file's last-modified time and its length, which each write of a region's file changes: two stats. */
    private data class Stamp(val time: Long, val length: Long)

    /**
     * How often a store's pack was found changed as its region's file was read again ([ofStore]): what the regions
     * said by the pack it named before is let go by it ([app.atlas.ui.search.HereContext]). Memory: the main thread
     * reads it.
     */
    @Volatile
    var generation = 0
        private set

    /**
     * The country of [store]'s region: the pack it was taken from, as the list names it; null for one of no pack or of
     * several countries. Reads the region's file once a store, whether it names a pack or not (the app maintainer,
     * 18 November 2026, batch 5 A6: a typed search's hits read it for each kilometre cell), and again once it was
     * written since, as its time and length say (19 November 2026, batch 6 A1: a download names its pack at its end,
     * so a region asked while its routing came named none for the run); where there is no file, or it does not read,
     * nothing is kept, and the next ask looks again. The list's copies as [of] reads them. Stats the file: off the main
     * thread.
     */
    fun ofStore(store: PlaceSource): String? {
        val id = (store as? PlaceStore)?.file?.nameWithoutExtension ?: return null
        // the file's time and length before it is read: a write after them is seen at the next ask
        val file = regions.meta(id)
        val stamp = Stamp(file.lastModified(), file.length())
        val was = synchronized(packs) { if (stamps[store] == stamp) null else packs.remove(store) }
        val pack = synchronized(packs) { packs[store] } ?: run {
            val meta = runCatching { regions.get(id) }.getOrNull()
            val found = meta?.packId.orEmpty()
            synchronized(packs) {
                if (meta != null) {
                    packs[store] = found
                    stamps[store] = stamp
                }
                if (was != null && was != found) generation++
            }
            meta ?: return null
            found
        }
        return pack.ifEmpty { null }?.let { of(it) }
    }

    /**
     * Asks [ofStore] again for each of [stores] whose pack is kept, so that a region's file written since is read again
     * and [generation] says whether its pack changed, before what was said by it is read. Stats: off the main thread.
     */
    fun recheck(stores: List<PlaceSource>) {
        for (s in stores) if (synchronized(packs) { packs.containsKey(s) }) ofStore(s)
    }

    /**
     * The country of the downloaded region whose store holds [at] (a place of its own within 2 km, [covers]: not its box
     * alone, as the Netherlands' reaches Antwerp), by its pack; where two do (on their border), the one with more places
     * around it. Null where none does. Asks the stores: off the main thread.
     */
    fun ofRegionAt(stores: List<PlaceSource>, at: LatLon): String? {
        val holding = stores.mapNotNull { s -> ofStore(s)?.let { c -> s to c } }
            .filter { (s, _) -> runCatching { s.covers(at) }.getOrDefault(false) }
        if (holding.size <= 1) return holding.firstOrNull()?.second
        val around = BBox.around(at, AROUND_M)
        return holding.maxByOrNull { (s, _) -> runCatching { s.estimate(around) }.getOrDefault(0) }?.second
    }

    private companion object {
        const val REREAD_MS = 60_000L
        const val AROUND_M = 2_000.0
    }
}
