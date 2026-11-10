package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.model.Place
import app.atlas.providers.Mangrove
import app.atlas.providers.MangroveReview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Open ratings for the places a search finds (the user, 3 November: "Every source and everything thats possible goes
 * into search results"): Mangrove's reviews in the places' box, asked once an hour for a stretch of map, matched to
 * the places and written into each one's extras ("rating" 0..5, "rating_count", "rating_source"), which the rankings
 * and the rows read. One request for the whole list, not one a place.
 */
class AreaRatings(private val mangrove: Mangrove, private val clock: Clock, private val allowed: () -> Boolean) {
    private val kept = ArrayList<Triple<BBox, Instant, List<MangroveReview>>>()
    private val lock = Mutex()

    /** [places] with their ratings where reviews say one; as they are when none can be had (offline, switched off, down). */
    suspend fun rate(places: List<Place>): List<Place> {
        if (places.isEmpty() || !allowed()) return places
        val box = BBox.of(places.map { it.location })?.buffer(MARGIN_M) ?: return places
        if (box.width > MAX_DEGREES || box.height > MAX_DEGREES) return places
        val reviews = reviewsIn(box) ?: return places
        if (reviews.isEmpty()) return places
        val matched = Mangrove.match(reviews, places)
        val now = clock.instant()
        return places.map { p ->
            val r = matched[p.key]?.let { Mangrove.ratingOf(it, p, now) }
            val stars = r?.stars ?: return@map p
            p.copy(extra = p.extra + mapOf("rating" to stars.toString(), "rating_count" to r.count.toString(), "rating_source" to Mangrove.ID))
        }
    }

    /** The reviews in [box]: from a box asked within the hour that holds it, else asked now; null when they cannot be had. */
    private suspend fun reviewsIn(box: BBox): List<MangroveReview>? = lock.withLock {
        val now = clock.instant()
        kept.removeAll { Duration.between(it.second, now) > KEEP }
        kept.firstOrNull { (b, _, _) -> b.west <= box.west && b.south <= box.south && b.east >= box.east && b.north >= box.north }?.let { return it.third }
        val got = runCatching { mangrove.near(box) }.getOrNull() ?: return null
        kept += Triple(box, now, got)
        if (kept.size > KEPT_BOXES) kept.removeAt(0)
        got
    }

    private companion object {
        val KEEP: Duration = Duration.ofHours(1)
        const val KEPT_BOXES = 8
        const val MARGIN_M = 60.0

        /** A wider box is a country, not a stretch of map: its reviews would be too many to ask at once. */
        const val MAX_DEGREES = 0.4
    }
}
