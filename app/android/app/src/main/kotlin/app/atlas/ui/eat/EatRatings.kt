package app.atlas.ui.eat

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.providers.AggregateRating
import app.atlas.providers.Mangrove
import app.atlas.providers.Rating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is known about the ratings of an area's places, and whether more may come. */
data class AreaRatings(val byKey: Map<String, Rating>, val state: State) {
    enum class State {
        /** No network (or the app was told to stay offline): no ratings can be known. */
        OFFLINE,
        LOADING,
        READY,
        FAILED,
    }

    operator fun get(key: String): Rating? = byKey[key]

    /** The stars of [key], when a provider gave some. */
    fun stars(key: String): Double? = byKey[key]?.stars

    companion object {
        val OFFLINE = AreaRatings(emptyMap(), State.OFFLINE)
    }
}

/**
 * Ratings for the eat screens. Lists use Mangrove's open reviews, read for a whole area in one call and matched to
 * its places (by OSM id, else by name within the uncertainty). A single card asks every enabled provider through the
 * app's `RatingsAggregator`. Nothing is ever made up: a place nobody reviewed has no rating.
 */
class EatRatings internal constructor(private val services: Services) {
    private val areas = LinkedHashMap<String, MutableStateFlow<AreaRatings>>()
    private val single = MutableStateFlow<Map<String, AggregateRating>>(emptyMap())
    private val asked = HashSet<String>()

    /** Every provider's merged verdict per place key, as far as cards have asked for them. */
    val aggregates: StateFlow<Map<String, AggregateRating>> = single.asStateFlow()

    private val online: Boolean get() = !services.config.offline && services.connectivity.online.value

    /** The ratings of [area]'s places, fetched once per area when online. */
    fun of(area: EatArea): StateFlow<AreaRatings> = synchronized(areas) {
        if (area.places.isEmpty()) return MutableStateFlow(if (online) AreaRatings(emptyMap(), AreaRatings.State.LOADING) else AreaRatings.OFFLINE)
        val key = "${area.centre}:${area.origin}:${area.places.size}"
        val now = online
        areas.remove(key)?.let { known ->
            areas[key] = known
            // The phone was offline, or the call failed, and the network is back: ask again into the same flow.
            val state = known.value.state
            if (now && (state == AreaRatings.State.OFFLINE || state == AreaRatings.State.FAILED)) {
                known.value = AreaRatings(emptyMap(), AreaRatings.State.LOADING)
                services.scope.launch(Dispatchers.IO) { known.value = fetch(area) }
            }
            return known.asStateFlow()
        }
        val flow = MutableStateFlow(if (now) AreaRatings(emptyMap(), AreaRatings.State.LOADING) else AreaRatings.OFFLINE)
        areas[key] = flow
        while (areas.size > MAX_AREAS) areas.remove(areas.keys.first())
        if (now) services.scope.launch(Dispatchers.IO) { flow.value = fetch(area) }
        flow.asStateFlow()
    }

    private suspend fun fetch(area: EatArea): AreaRatings = try {
        val box = BBox.around(area.centre, area.radiusM)
        val reviews = services.mangrove.near(box)
        val places = area.places.map { it.place }
        val now = services.clock.instant()
        val matched = Mangrove.match(reviews, places)
        val byKey = HashMap<String, Rating>()
        for ((key, rs) in matched) {
            val p = area[key]?.place ?: continue
            val r = Mangrove.ratingOf(rs, p, now)
            if (r.stars != null || r.count > 0) byKey[key] = r
        }
        AreaRatings(byKey, AreaRatings.State.READY)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AreaRatings(emptyMap(), AreaRatings.State.FAILED)
    }

    /** Asks every enabled provider about [place] (once per place), for a card; the answer lands in [aggregates]. */
    fun ask(place: EatPlace) {
        if (!online || place.key in single.value) return
        // A card scrolled away and back must not ask the providers twice while the first answer is on its way.
        if (!synchronized(asked) { asked.add(place.key) }) return
        services.scope.launch(Dispatchers.IO) {
            val agg = try {
                services.ratings.rate(place.place)
            } catch (e: CancellationException) {
                synchronized(asked) { asked.remove(place.key) }
                throw e
            } catch (e: Exception) {
                synchronized(asked) { asked.remove(place.key) }
                return@launch
            }
            single.update { it + (place.key to agg) }
        }
    }

    private companion object {
        /** How many areas' ratings are kept at once. */
        const val MAX_AREAS = 6
    }
}
