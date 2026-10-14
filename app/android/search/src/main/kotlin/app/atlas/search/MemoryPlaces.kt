package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.PackedRTree
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.Categories

/**
 * Places held in memory as a [PlaceSource]: an online answer, or a provider's list, searched with the same evaluator
 * as the offline stores. Boxes go in an R-tree; tags, categories and brands in hash indexes.
 */
class MemoryPlaces(places: Collection<Place>) : PlaceSource {
    private val records: List<PlaceRecord>
    private val byKey = HashMap<String, Int>()
    private val tagIndex = HashMap<String, MutableList<Int>>()
    private val keyIndex = HashMap<String, MutableList<Int>>()
    private val tree: PackedRTree?

    init {
        val seen = LinkedHashMap<String, Place>()
        for (p in places) if (p.tags.isNotEmpty()) seen[p.key] = p
        records = seen.values.map { PlaceRecord.of(it) }
        for ((i, r) in records.withIndex()) {
            byKey[r.place.key] = i
            for ((k, v) in r.tags) {
                keyIndex.getOrPut(k) { ArrayList(4) }.add(i)
                tagIndex.getOrPut("$k=$v") { ArrayList(4) }.add(i)
                if (';' in v) for (part in v.split(';')) {
                    val t = part.trim()
                    if (t.isNotEmpty() && t != v) tagIndex.getOrPut("$k=$t") { ArrayList(4) }.add(i)
                }
            }
        }
        tree = if (records.isEmpty()) null else PackedRTree(records.size).also { t ->
            for (r in records) {
                val b = PlaceRecord.boxOf(r.place)
                t.add(b.west, b.south, b.east, b.north)
            }
            t.finish()
        }
    }

    /** Every place, with what Atlas worked out from its tags. */
    val all: List<PlaceRecord> get() = records

    override val size: Int get() = records.size

    override val bounds: BBox? by lazy { records.fold(null as BBox?) { acc, r -> PlaceRecord.boxOf(r.place).let { b -> acc?.union(b) ?: b } } }

    override fun get(id: OsmId): PlaceRecord? = byKey[id.toString()]?.let { records[it] }

    override fun inBox(box: BBox, limit: Int): List<PlaceRecord> {
        val t = tree ?: return emptyList()
        val ids = t.search(box.west, box.south, box.east, box.north)
        return ids.asSequence().take(limit).map { records[it] }.toList()
    }

    override fun byTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> {
        val ids = (if (value == null) keyIndex[key] else tagIndex["$key=$value"]) ?: return emptyList()
        return ids.distinct().asSequence().map { records[it] }.filter { box == null || meets(it, box) }.take(limit).toList()
    }

    override fun byCategory(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> =
        records.asSequence().filter { it.category != null && it.category in ids && (box == null || meets(it, box)) }.take(limit).toList()

    override fun byBrand(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> =
        records.asSequence().filter { it.brand != null && it.brand in ids && (box == null || meets(it, box)) }.take(limit).toList()

    override fun text(text: String, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> {
        val words = SearchText.words(text)
        if (words.isEmpty()) return emptyList()
        return records.asSequence()
            .filter { box == null || meets(it, box) }
            .filter { r ->
                val others = listOfNotNull(r.tags["operator"], r.tags["ref"], r.tags["addr:street"], r.category?.let { Categories.byId(it)?.name() })
                NameMatch.best(words, SearchText.names(r.tags), others) >= 0.45
            }
            .take(limit).toList()
    }

    override fun estimate(key: String, value: String?): Int = ((if (value == null) keyIndex[key] else tagIndex["$key=$value"])?.size ?: 0)

    override fun estimate(box: BBox): Int = tree?.search(box.west, box.south, box.east, box.north)?.size ?: 0

    private fun meets(r: PlaceRecord, box: BBox): Boolean = PlaceRecord.boxOf(r.place).intersects(box)
}
