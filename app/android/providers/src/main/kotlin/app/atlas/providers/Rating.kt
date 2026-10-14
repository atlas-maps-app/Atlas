package app.atlas.providers

import app.atlas.model.Place
import java.time.Instant

/** How the reviewer had their food: Atlas asks it when someone reviews a place to eat or drink. */
enum class Service(val value: String) {
    EATEN_THERE("eaten_there"),
    TAKEAWAY("takeaway"),
    DELIVERED("delivered");

    companion object {
        fun of(value: String?): Service? = entries.firstOrNull { it.value == value }
    }
}

/** One review, from any provider, with what the credit line needs. */
data class Review(
    val provider: String,
    /** 0..5, normalised from the provider's own scale; null for a text-only review. */
    val stars: Double?,
    val text: String?,
    val author: String?,
    val time: Instant?,
    val url: String? = null,
    /** The review's own licence, e.g. "CC-BY-4.0" for Mangrove; null when the provider's terms apply. */
    val licence: String? = null,
    val service: Service? = null,
    /** True for machine-written reviews (Mangrove's `is_generated`); they never count towards a score. */
    val generated: Boolean = false,
    val images: List<String> = emptyList(),
    val language: String? = null,
)

/** Stars and count for one way of having the food. */
data class ServiceRating(val stars: Double, val count: Int)

/** How a place's reviews are moving: counts by age, pace, and whether it is rising. */
data class Trend(
    val last30: Int,
    val last90: Int,
    val last365: Int,
    val total: Int,
    /** Reviews a month over the last 90 days divided by the pace over the 275 days before (with a floor). */
    val velocity: Double,
    /** Average stars of the last 90 days minus those before; 0 when either side has too few reviews. */
    val momentum: Double,
    val rising: Boolean,
    /** True when the first review came within the last 90 days. */
    val newcomer: Boolean,
    val firstReview: Instant?,
    val lastReview: Instant?,
)

/** One provider's verdict on a place. */
data class Rating(
    val provider: String,
    /** 0..5, normalised. */
    val stars: Double?,
    val count: Int,
    /** 1 (cheap) to 4 (very expensive). */
    val priceLevel: Int? = null,
    val url: String? = null,
    val recent: List<Review> = emptyList(),
    val trend: Trend? = null,
    /** Separate scores for eaten there, takeaway and delivered, where reviews say which. */
    val byService: Map<Service, ServiceRating> = emptyMap(),
    /** The credit line to show with it. */
    val attribution: String? = null,
)

/** Terms a provider's data comes with. */
data class RatingPolicy(
    /** False when the terms forbid showing it merged with other reviews (Yelp): it is then shown on its own. */
    val mixable: Boolean = true,
    /** How long its answers may be kept; null for no limit, 0 for not at all. */
    val maxCacheHours: Int? = null,
    /** True for a delivery platform's ratings, which [RatingsAggregator] compares with dine-in ones. */
    val delivery: Boolean = false,
)

/** A source of ratings for places. */
interface RatingProvider : Provider {
    val name: String get() = info.name

    /** The symbol id the UI shows for this source. */
    val symbol: String
    val policy: RatingPolicy get() = RatingPolicy()

    /** The rating for [place], or null when the provider does not know it. Throws [NeedsKeyException] without a key. */
    suspend fun rating(place: Place): Rating?
}
