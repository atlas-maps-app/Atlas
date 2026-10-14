package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.places.TagFilter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where places are looked up: one region's [PlaceStore], all attached regions ([Regions]), or places held in memory
 * ([MemoryPlaces], for online results). Every lookup can be limited to a box; results are unordered unless stated.
 */
interface PlaceSource {
    /** How many places it holds. */
    val size: Int

    /** The box around all its places, or null when empty. */
    val bounds: BBox?

    fun get(id: OsmId): PlaceRecord?

    /** Places whose point or outline box meets [box]. */
    fun inBox(box: BBox, limit: Int = UNLIMITED): List<PlaceRecord>

    /** Places with tag [key] (any value when [value] is null; a `;` list matches by its parts). */
    fun byTag(key: String, value: String?, box: BBox? = null, limit: Int = UNLIMITED): List<PlaceRecord>

    /** Places in any of the category leaves [ids]. */
    fun byCategory(ids: Collection<String>, box: BBox? = null, limit: Int = UNLIMITED): List<PlaceRecord>

    /** Branches of any of the brands [ids] (name-suggestion-index ids). */
    fun byBrand(ids: Collection<String>, box: BBox? = null, limit: Int = UNLIMITED): List<PlaceRecord>

    /**
     * Places whose names, kinds or addresses contain the words of [text] (the last one may be unfinished), typos
     * forgiven when exact words find little. Those near [near] are always considered, so a common name still finds
     * the branch round the corner. Unranked: score them with [NameMatch].
     */
    fun text(text: String, near: LatLon? = null, box: BBox? = null, limit: Int = 200): List<PlaceRecord>

    /** About how many places carry [key] (with [value] when given). */
    fun estimate(key: String, value: String?): Int

    /** About how many places lie in [box]. */
    fun estimate(box: BBox): Int

    companion object {
        const val UNLIMITED = Int.MAX_VALUE
    }
}

/** Places whose tags pass [filter], inside [box] (anywhere when null): looked up through the narrowest index, then checked. */
fun PlaceSource.byFilter(filter: TagFilter, box: BBox? = null): List<PlaceRecord> =
    OfflineEvaluator(this).matches(Query.Tags(filter), EvalContext(), box ?: bounds)

/** The [k] places nearest [p] that [accept] keeps, within [maxDistance] metres, nearest first: by growing boxes. */
fun PlaceSource.nearest(p: LatLon, k: Int, maxDistance: Double = 50_000.0, accept: (PlaceRecord) -> Boolean = { true }): List<PlaceRecord> {
    if (k <= 0 || size == 0) return emptyList()
    var radius = 250.0
    while (true) {
        val r = minOf(radius, maxDistance)
        val found = inBox(BBox.around(p, r)).filter(accept)
            .map { it to it.distanceTo(p) }
            .filter { it.second <= r }
            .sortedBy { it.second }
        if (found.size >= k || r >= maxDistance) return found.take(k).map { it.first }
        radius *= 3
    }
}

/** The places within [metres] of [center] that [accept] keeps, nearest first (to their outline when they have one). */
fun PlaceSource.within(center: LatLon, metres: Double, accept: (PlaceRecord) -> Boolean = { true }): List<PlaceRecord> {
    if (metres < 0 || size == 0) return emptyList()
    return inBox(BBox.around(center, metres)).asSequence()
        .filter(accept)
        .map { it to it.distanceTo(center) }
        .filter { it.second <= metres }
        .sortedBy { it.second }
        .map { it.first }
        .toList()
}

/**
 * The regions attached at once, each its own store file: lookups span all of them, and a place that two regions
 * share (on their border) comes back once.
 */
class Regions(stores: Collection<PlaceSource> = emptyList()) : PlaceSource {
    private val list = CopyOnWriteArrayList(stores)

    val stores: List<PlaceSource> get() = list.toList()

    fun attach(store: PlaceSource) {
        if (store !in list) list.add(store)
    }

    fun detach(store: PlaceSource) {
        list.remove(store)
    }

    override val size: Int get() = list.sumOf { it.size }

    override val bounds: BBox? get() = list.mapNotNull { it.bounds }.reduceOrNull { a, b -> a.union(b) }

    override fun get(id: OsmId): PlaceRecord? {
        for (s in list) s.get(id)?.let { return it }
        return null
    }

    override fun inBox(box: BBox, limit: Int) = merge(limit) { s -> if (meets(s, box)) s.inBox(box, limit) else emptyList() }

    override fun byTag(key: String, value: String?, box: BBox?, limit: Int) =
        merge(limit) { s -> if (box == null || meets(s, box)) s.byTag(key, value, box, limit) else emptyList() }

    override fun byCategory(ids: Collection<String>, box: BBox?, limit: Int) =
        merge(limit) { s -> if (box == null || meets(s, box)) s.byCategory(ids, box, limit) else emptyList() }

    override fun byBrand(ids: Collection<String>, box: BBox?, limit: Int) =
        merge(limit) { s -> if (box == null || meets(s, box)) s.byBrand(ids, box, limit) else emptyList() }

    override fun text(text: String, near: LatLon?, box: BBox?, limit: Int) =
        merge(limit * list.size.coerceAtLeast(1)) { s -> if (box == null || meets(s, box)) s.text(text, near, box, limit) else emptyList() }

    override fun estimate(key: String, value: String?): Int = list.sumOf { it.estimate(key, value) }

    override fun estimate(box: BBox): Int = list.sumOf { if (meets(it, box)) it.estimate(box) else 0 }

    private fun meets(s: PlaceSource, box: BBox): Boolean = s.bounds?.intersects(box) ?: false

    private inline fun merge(limit: Int, lookup: (PlaceSource) -> List<PlaceRecord>): List<PlaceRecord> {
        // one snapshot: a region detached meanwhile is not read half-way
        val stores = list.toTypedArray()
        if (stores.size == 1) return lookup(stores[0])
        val seen = HashSet<String>()
        val out = ArrayList<PlaceRecord>()
        for (s in stores) {
            for (r in lookup(s)) {
                if (seen.add(r.place.key)) out += r
                if (out.size >= limit) return out
            }
        }
        return out
    }
}
