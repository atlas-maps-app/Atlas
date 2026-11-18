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

    /** The country of [store]'s region: the pack it was taken from, as the list names it; null for one of no pack or of several countries. Reads files. */
    fun ofStore(store: PlaceSource): String? {
        val id = (store as? PlaceStore)?.file?.nameWithoutExtension ?: return null
        val pack = runCatching { regions.get(id)?.packId }.getOrNull() ?: return null
        return of(pack)
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
