package app.atlas.search

import app.atlas.places.TagFilter

/**
 * A kind's largest places across whole stores (a region, a country), not only those around (the user, 9 November: "Is
 * it somewhere possible to see the largest green areas in your country or around you?"). Each store gives the largest
 * it holds through the index of each tag the kind asks for (leisure=park; a wood's natural=wood or landuse=forest),
 * more of them than wanted, so that those the rest of the filter leaves out (an unnamed wood) leave enough; then the
 * whole filter is checked and the largest by their ground ([Laps.ground]) are kept.
 */
object Largest {
    /** How many times as many as wanted each tag's lookup reads. */
    private const val SPARE = 4

    /** The [limit] places of [sources] that [filter] takes covering the most ground, the largest first. */
    fun of(sources: List<PlaceSource>, filter: TagFilter, limit: Int): List<PlaceRecord> {
        val found = LinkedHashMap<String, PlaceRecord>()
        for (s in sources) for (r in read(s, filter, limit * SPARE).orEmpty()) if (filter.matches(r.tags)) found.putIfAbsent(r.place.key, r)
        return found.values.mapNotNull { r -> Laps.ground(r.place.bbox)?.let { r to it } }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun read(s: PlaceSource, f: TagFilter, n: Int): List<PlaceRecord>? = when (s) {
        is Regions -> s.stores.flatMap { read(it, f, n).orEmpty() }
        is PlaceStore -> seed(s, f, n)
        else -> s.byFilter(f)
    }

    private fun seed(s: PlaceStore, f: TagFilter, n: Int): List<PlaceRecord>? = when (f) {
        is TagFilter.Has -> s.largest(f.key, null, n)
        is TagFilter.Equals -> s.largest(f.key, f.value, n)
        is TagFilter.Matches -> if (f.negate) null else OfflineEvaluator.literals(f.pattern)?.flatMap { s.largest(f.key, it, n) } ?: s.largest(f.key, null, n)
        // the kind's own part ("woods and named": the woods), not one any place may have
        is TagFilter.And -> f.parts.sortedBy { it is TagFilter.Has }.firstNotNullOfOrNull { seed(s, it, n) }
        is TagFilter.Or -> f.parts.flatMap { seed(s, it, n) ?: return null }
        else -> null
    }
}
