package app.atlas.providers

import app.atlas.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Why a place's ratings disagree, when they do — the "eaten there or delivered" idea.
 *
 * A restaurant can be lovely at the table and poor in a box, or the other way round. Atlas asks reviewers how they had
 * the food (Mangrove reviews written by Atlas carry `atlas_how`), and a delivery platform's ratings, if one is ever
 * added, are marked [RatingPolicy.delivery]. When the two sides differ by [MIN_GAP] stars or more, each with at least
 * [MIN_EACH] reviews, the place screen says so instead of hiding it in an average: "4.6 eaten there · 3.1 delivered".
 * Failing that, a plain split between providers (one says 4.5, another 2.8) is reported with [Reason.PROVIDERS].
 */
data class Disagreement(val reason: Reason, val high: Side, val low: Side) {
    enum class Reason { EATEN_THERE_VS_DELIVERED, PROVIDERS }

    /** One side: a way of having the food ("eaten_there", "delivered") or a provider id; its stars and count. */
    data class Side(val label: String, val stars: Double, val count: Int)

    val gap: Double get() = high.stars - low.stars

    companion object {
        const val MIN_GAP = 1.0
        const val MIN_EACH = 2
    }
}

/** The merged view of a place's ratings. */
data class AggregateRating(
    /** 0..5, the count-weighted mean of the mixable sources, pulled towards a prior when reviews are few; null with none. */
    val score: Double?,
    /** Every source that answered, mixable or not; the screens show non-mixable ones (Yelp) apart. */
    val sources: List<Rating>,
    /** Reviews behind [score]. */
    val count: Int,
    /** Highest minus lowest stars among sources with at least three reviews; 0 with fewer than two such sources. */
    val spread: Double,
    val disagreement: Disagreement?,
    /** Providers that did not answer, with why: "needs key", "timed out", or the error. */
    val missing: Map<String, String> = emptyMap(),
) {
    val rising: Boolean get() = sources.any { it.trend?.rising == true }
}

/**
 * Asks every enabled rating provider about a place at once, within a time budget, and merges what comes back.
 * A provider that fails or is slow never holds up the others; it is listed in [AggregateRating.missing].
 */
class RatingsAggregator(private val providers: List<RatingProvider>, private val budget: Duration = 4.seconds) {
    private sealed interface Outcome {
        data class Got(val rating: Rating?) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** The merged rating of [place] from the providers in [enabled] (all READY ones when null). */
    suspend fun rate(place: Place, enabled: Set<String>? = null): AggregateRating {
        val asked = providers.filter { enabled == null || it.id in enabled }
        val outcomes = supervisorScope {
            asked.map { p ->
                async {
                    val state = p.state()
                    if (state != ProviderState.READY) return@async p to Outcome.Failed(if (state == ProviderState.NEEDS_KEY) "needs key" else "unavailable")
                    p to try {
                        withTimeoutOrNull(budget) { Outcome.Got(p.rating(place)) } ?: Outcome.Failed("timed out")
                    } catch (e: CancellationException) {
                        // Our caller gave up: stop. Otherwise the provider's own timeout fired; that is its failure, not ours.
                        currentCoroutineContext().ensureActive()
                        Outcome.Failed("timed out")
                    } catch (e: NeedsKeyException) {
                        Outcome.Failed("needs key")
                    } catch (e: Exception) {
                        Outcome.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
            }.awaitAll()
        }
        val ratings = outcomes.mapNotNull { (_, o) -> (o as? Outcome.Got)?.rating }
        val missing = outcomes.mapNotNull { (p, o) -> (o as? Outcome.Failed)?.let { p.id to it.why } }.toMap()
        val policies = providers.associate { it.id to it.policy }
        return merge(ratings, policies, missing)
    }

    companion object {
        /** The prior a few reviews are pulled towards, and how many reviews it is worth. */
        const val PRIOR_STARS = 3.6
        const val PRIOR_WEIGHT = 2.0

        /** One provider's count is capped so a giant does not silence the others entirely. */
        const val COUNT_CAP = 200

        /** Merges [ratings]; [policies] say which are mixable and which are delivery ratings. */
        fun merge(ratings: List<Rating>, policies: Map<String, RatingPolicy> = emptyMap(), missing: Map<String, String> = emptyMap()): AggregateRating {
            val mixable = ratings.filter { (policies[it.provider]?.mixable ?: true) && it.stars != null && it.count > 0 }
            var weight = PRIOR_WEIGHT
            var sum = PRIOR_STARS * PRIOR_WEIGHT
            var count = 0
            for (r in mixable) {
                val w = min(r.count, COUNT_CAP).toDouble()
                weight += w
                sum += w * r.stars!!
                count += r.count
            }
            val score = if (mixable.isEmpty()) null else sum / weight
            val solid = mixable.filter { it.count >= 3 }
            val spread = if (solid.size >= 2) solid.maxOf { it.stars!! } - solid.minOf { it.stars!! } else 0.0
            return AggregateRating(score, ratings, count, spread, disagreement(mixable, policies), missing)
        }

        /** The disagreement, if any: eaten there against delivered first, then between providers. */
        fun disagreement(ratings: List<Rating>, policies: Map<String, RatingPolicy> = emptyMap()): Disagreement? {
            // Eaten there against delivered, from reviews that say which, plus whole providers marked as delivery platforms.
            val there = ArrayList<Pair<Double, Int>>()
            val delivered = ArrayList<Pair<Double, Int>>()
            for (r in ratings) {
                if (policies[r.provider]?.delivery == true) {
                    r.stars?.let { delivered.add(it to r.count) }
                    continue
                }
                r.byService[Service.EATEN_THERE]?.let { there.add(it.stars to it.count) }
                r.byService[Service.DELIVERED]?.let { delivered.add(it.stars to it.count) }
            }
            val a = pooled("eaten_there", there)
            val b = pooled("delivered", delivered)
            if (a != null && b != null && a.count >= Disagreement.MIN_EACH && b.count >= Disagreement.MIN_EACH && abs(a.stars - b.stars) >= Disagreement.MIN_GAP) {
                return if (a.stars >= b.stars) Disagreement(Disagreement.Reason.EATEN_THERE_VS_DELIVERED, a, b) else Disagreement(Disagreement.Reason.EATEN_THERE_VS_DELIVERED, b, a)
            }
            val solid = ratings.filter { it.stars != null && it.count >= 5 && policies[it.provider]?.delivery != true }
            if (solid.size < 2) return null
            val hi = solid.maxBy { it.stars!! }
            val lo = solid.minBy { it.stars!! }
            if (hi.stars!! - lo.stars!! < Disagreement.MIN_GAP) return null
            return Disagreement(Disagreement.Reason.PROVIDERS, Disagreement.Side(hi.provider, hi.stars, hi.count), Disagreement.Side(lo.provider, lo.stars, lo.count))
        }

        private fun pooled(label: String, parts: List<Pair<Double, Int>>): Disagreement.Side? {
            val n = parts.sumOf { it.second }
            if (n == 0) return null
            return Disagreement.Side(label, parts.sumOf { it.first * it.second } / n, n)
        }
    }
}
