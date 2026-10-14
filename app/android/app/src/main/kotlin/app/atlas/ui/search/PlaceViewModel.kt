package app.atlas.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Fact
import app.atlas.places.Facts
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.providers.AggregateRating
import app.atlas.providers.Service
import app.atlas.providers.WikidataEntity
import app.atlas.search.PlaceRecord
import app.atlas.services.SettingsKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Where the ratings of a place stand: not asked (offline), being asked, the answer, or why it failed. */
sealed interface RatingsState {
    data object Offline : RatingsState
    data object Loading : RatingsState
    data class Ready(val rating: AggregateRating) : RatingsState
    data class Failed(val why: String) : RatingsState
}

/** Where something sent on the user's tap stands: a note for mappers or a review. */
sealed interface Sending {
    data object Idle : Sending
    data object Busy : Sending
    data class Done(val url: String?) : Sending
    data class Failed(val why: String) : Sending
}

/** Everything the Place sheet shows, as it arrives: the place first, then Wikidata, its photo and its ratings. */
data class PlaceState(
    val place: Place? = null,
    val looking: Boolean = true,
    val facts: List<Fact> = emptyList(),
    val quests: List<Quest> = emptyList(),
    val hours: OpeningHours? = null,
    val hoursContext: OpeningHoursContext = OpeningHoursContext.Default,
    /** The country the hours are read in (its public holidays), when known. */
    val country: String? = null,
    val status: OpenStatus? = null,
    val now: LocalDateTime = LocalDateTime.now(),
    val brand: Brand? = null,
    val wiki: WikidataEntity? = null,
    val brandWiki: WikidataEntity? = null,
    val image: KeptImage? = null,
    val ratings: RatingsState = RatingsState.Offline,
    /** Per quest key, the note sent for its answer. */
    val answered: Map<String, Sending> = emptyMap(),
    val review: Sending = Sending.Idle,
    /** True when the place came with full tags (a downloaded region or Overpass), not just a map label's. */
    val complete: Boolean = false,
)

/**
 * Loads one place for its sheet: from the screens' cache, the downloaded regions (every tag and its outline) or
 * Overpass, then its facts and quests, its hours read with the holidays of its country, its Wikidata facts and photo
 * (kept on the phone once fetched) and, online, its ratings. Notes and reviews go out only through [answer] and
 * [sendReview], which the screen calls after the user confirmed the exact text.
 */
class PlaceViewModel(
    private val services: Services,
    private val key: String?,
    private val osmId: String?,
    private val at: LatLon?,
    private val name: String?,
    private val lang: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(PlaceState(now = LocalDateTime.now(services.clock)))
    val state: StateFlow<PlaceState> = mutable.asStateFlow()

    private val online: Boolean get() = !services.config.offline && services.connectivity.online.value
    private val langs = listOf(lang, "en").distinct()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val first = withContext(Dispatchers.IO) { fromPhone() }
        // A map label's or a link's place has a name at most: the rest comes from a region or Overpass.
        val partial = first == null || first.source == "tiles" || first.source == "link"
        show(first, complete = !partial)
        var place = first
        if (partial && online) {
            val id = place?.id ?: osmId?.let(OsmId::parse)
            if (id != null) fromOverpass(id)?.let { full -> place = full.copy(id = full.id ?: id); show(place, complete = true) }
        }
        mutable.update { it.copy(looking = false) }
        val p = place ?: return
        viewModelScope.launch { wiki(p) }
        viewModelScope.launch { ratings(p) }
    }

    /** The place from the screens' cache, merged with the downloaded regions' full record when they have it. */
    private fun fromPhone(): Place? {
        val cached = key?.let { services.placeCache[it] }
        val id = cached?.id ?: osmId?.let(OsmId::parse)
        val stored = id?.let { runCatching { services.placeStores.get(it)?.place }.getOrNull() }
        return when {
            stored != null && cached != null -> stored.copy(extra = cached.extra + stored.extra)
            stored != null -> stored
            cached != null -> cached
            at != null -> Place(id, at, name?.let { mapOf("name" to it) } ?: emptyMap(), source = "link")
            else -> null
        }
    }

    private suspend fun fromOverpass(id: OsmId): Place? = withContext(Dispatchers.IO) {
        val type = when (id.type) {
            OsmType.NODE -> "node"
            OsmType.WAY -> "way"
            OsmType.RELATION -> "relation"
        }
        try {
            withTimeoutOrNull(30_000) { services.overpass.places("[out:json][timeout:25];$type(${id.id});out tags bb center;").firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, refused, or an answer that does not parse (a captive portal): the place stays as it was.
            null
        }
    }

    private fun show(place: Place?, complete: Boolean) {
        if (place == null) return
        val now = LocalDateTime.now(services.clock)
        val zone = ZonedDateTime.now(services.clock).zone
        val country = place.tags["addr:country"]?.uppercase() ?: HereContext.country(services, place.location)
        val ctx = OpeningHoursContext.forPlace(country, zone, place.location.lat, place.location.lon)
        val hours = OpeningHours.parseOrNull(place.tags["opening_hours"])
        val facts = Facts.of(place, lang, now, ctx, geometry = PlaceRecord.geometryOf(place))
        mutable.update {
            it.copy(
                place = place,
                facts = facts,
                quests = if (place.id != null) Quests.of(facts).filter { q -> !services.saved.answered(place.key, q.key) } else emptyList(),
                hours = hours,
                hoursContext = ctx,
                country = country,
                status = hours?.status(now, ctx),
                now = now,
                brand = Brands.forTags(place.tags),
                complete = complete || it.complete,
            )
        }
    }

    private suspend fun wiki(p: Place) {
        val kept = KeptFacts(File(services.dirs.files, "search/wikidata"))
        val images = KeptImages(File(services.dirs.cache, "search/images"))
        val qid = p.tags["wikidata"]?.trim()
        val brandQid = (p.tags["brand:wikidata"] ?: Brands.forTags(p.tags)?.wikidata)?.trim()
        suspend fun entity(q: String?): WikidataEntity? {
            if (q.isNullOrEmpty()) return null
            withContext(Dispatchers.IO) { kept.read(q) }?.let { return it }
            if (!online) return null
            return try {
                services.wikidata.entity(q, langs)?.also { e -> withContext(Dispatchers.IO) { runCatching { kept.keep(e) } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val e = entity(qid)
        if (e != null) mutable.update { it.copy(wiki = e) }
        if (brandQid != null && brandQid != qid) entity(brandQid)?.let { b -> mutable.update { it.copy(brandWiki = b) } }
        e?.image?.let { name ->
            val img = try {
                images.get(services, name)
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                null
            }
            img?.let { i -> mutable.update { it.copy(image = i) } }
        }
    }

    private suspend fun ratings(p: Place) {
        if (!online || p.name() == null) {
            mutable.update { it.copy(ratings = RatingsState.Offline) }
            return
        }
        mutable.update { it.copy(ratings = RatingsState.Loading) }
        val off = runCatching { services.settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).first() }.getOrDefault(emptySet())
        val r = try {
            val all = services.ratings.rate(p)
            RatingsState.Ready(all.copy(sources = all.sources.filter { it.provider !in off }))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RatingsState.Failed(e.message ?: e.toString())
        }
        mutable.update { it.copy(ratings = r) }
    }

    /** True when notes and reviews can be sent now. */
    fun canSend(): Boolean = online

    /** Leaves the note for [quest] answered [yes]; the screen has shown the user its exact text first. */
    fun answer(quest: Quest, yes: Boolean) {
        val p = state.value.place ?: return
        mutable.update { it.copy(answered = it.answered + (quest.key to Sending.Busy)) }
        viewModelScope.launch {
            val r = try {
                val note = withContext(Dispatchers.IO) { services.osmNotes.create(p.location, Quests.noteText(p, quest, yes)) }
                withContext(Dispatchers.IO) { runCatching { services.saved.recordAnswer(p.key, quest.key, yes, note.url) } }
                Sending.Done(note.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sending.Failed(e.message ?: e.toString())
            }
            mutable.update { it.copy(answered = it.answered + (quest.key to r)) }
        }
    }

    /** Publishes the review the user confirmed: [stars] 1–5, one line, and how the food was had for eateries. */
    fun sendReview(stars: Int, opinion: String, how: Service?) {
        val p = state.value.place ?: return
        mutable.update { it.copy(review = Sending.Busy) }
        viewModelScope.launch {
            val r = try {
                withContext(Dispatchers.IO) {
                    val review = Reviewer.draft(services, p, stars, opinion, how)
                    Reviewer.send(services, review)
                }
                Sending.Done(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sending.Failed(e.message ?: e.toString())
            }
            mutable.update { it.copy(review = r) }
            if (r is Sending.Done) ratings(p)
        }
    }
}
