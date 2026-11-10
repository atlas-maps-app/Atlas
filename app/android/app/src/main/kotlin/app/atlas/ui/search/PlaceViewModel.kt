package app.atlas.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.BrandHours
import app.atlas.places.BrandSite
import app.atlas.places.Brands
import app.atlas.places.Fact
import app.atlas.places.Facts
import app.atlas.places.InferredHours
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.providers.AggregateRating
import app.atlas.providers.Service
import app.atlas.providers.WikidataEntity
import app.atlas.search.HoursBook
import app.atlas.search.HoursFrom
import app.atlas.search.PlaceRecord
import app.atlas.search.PlaceSource
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
import kotlin.math.roundToInt

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
    /** For a branch that maps no website, its brand's own site as its branches in the regions link it ([BrandSite]). */
    val brandSite: String? = null,
    /** Where [hours] are borrowed, not the place's own: whose they are ([BorrowedHours]); null for its own or none. */
    val guess: HoursGuess? = null,
) {
    /** The first question still to ask: one whose answer is on its way or sent is done, one that failed to go out is open again. */
    val openQuest: Quest? get() = quests.firstOrNull { answered[it.key].let { s -> s == null || s is Sending.Failed } }
}

/**
 * Hours a place that maps none borrows ([BorrowedHours]): its brand's branches' near ([from] BRAND) or its kind's around
 * it (KIND), never its own, so never confirmed. [places] is how many places' weeks they are worked out from, [keep] how
 * many of those keep exactly them, and [branch] the one branch they come from as a brand's page names it
 * ("Raadhuisstraat 43-H"), where it is one and named.
 */
data class HoursGuess(val hours: OpeningHours, val from: HoursFrom, val places: Int, val keep: Int, val branch: String? = null)

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
        viewModelScope.launch { brandSite(p) }
        viewModelScope.launch { borrow(p) }
    }

    /** A place that maps no hours takes its brand's branches' near, else its kind's around it ([BorrowedHours]): a guess, shown as one. */
    private suspend fun borrow(p: Place) {
        if ("opening_hours" in p.tags) return
        val guess = withContext(Dispatchers.IO) { runCatching { BorrowedHours.of(services.placeStores, p, lang) }.getOrNull() } ?: return
        mutable.update { s ->
            if (s.hours != null || s.place?.key != p.key) s else s.copy(hours = guess.hours, status = guess.hours.status(s.now, s.hoursContext), guess = guess)
        }
    }

    /** A branch without a website of its own gets its brand's: the site its brand's branches within 50 km share. */
    private suspend fun brandSite(p: Place) {
        if (BrandSite.mapped(p.tags) != null) return
        val brand = Brands.forTags(p.tags) ?: return
        val site = withContext(Dispatchers.IO) {
            val branches = runCatching { services.placeStores.byBrand(listOf(brand.id), BBox.around(p.location, 50_000.0), 300) }.getOrDefault(emptyList())
            BrandSite.of(branches.filter { it.place.key != p.key }.map { it.tags }, mutable.value.country)
        }
        if (site != null) mutable.update { it.copy(brandSite = site) }
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

    /** Reads the place's facts and hours on its own clock and holidays (off the main thread: the regions are asked). */
    private suspend fun show(place: Place?, complete: Boolean) {
        if (place == null) return
        val next = withContext(Dispatchers.Default) {
            val local = HereContext.localeOf(services, place, HereContext.phoneAt(services), ZonedDateTime.now(services.clock).zone)
            val now = local.now(services.clock)
            val ctx = OpeningHoursContext.forPlace(local.country, local.zone, place.location.lat, place.location.lon)
            val hours = OpeningHours.parseOrNull(place.tags["opening_hours"])
            val facts = Facts.of(place, lang, now, ctx, geometry = PlaceRecord.geometryOf(place))
            PlaceState(
                place = place,
                facts = facts,
                quests = if (place.id != null) Quests.of(facts).filter { q -> !services.saved.answered(place.key, q.key) } else emptyList(),
                hours = hours,
                hoursContext = ctx,
                country = local.country,
                status = hours?.status(now, ctx),
                now = now,
                brand = Brands.forTags(place.tags),
            )
        }
        mutable.update {
            it.copy(
                place = next.place, facts = next.facts, quests = next.quests, hours = next.hours, hoursContext = next.hoursContext,
                country = next.country, status = next.status, now = next.now, brand = next.brand, complete = complete || it.complete,
            )
        }
    }

    private suspend fun wiki(p: Place) {
        val kept = KeptFacts(File(services.dirs.files, "search/wikidata"))
        val images = KeptImages(services.dirs.images)
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

/**
 * The hours a place's page shows for a place that maps none, as the search's rows, pins and counts give it them
 * ([HoursBook]; the owner, 6 November, of two Birkenstock stores, one mapped closed and one with no hours: "if the other
 * branch is closed the other one is probably closed too"): its brand's, worked out from the weeks its nearest
 * [BRANCH_SAMPLE] other branches within [BRANCH_REACH_M] keep, one of them enough ([BrandHours.MIN_SIBLINGS]); else its
 * kind's around it, as [HoursBook.ofKind] works them out from the places of its category within reach of its cell. A place
 * whose own hours do not read, and one usually open (a park, a bench), borrows none.
 */
internal object BorrowedHours {
    /** How far a branch may lie to lend its hours: the search's widest circle. */
    const val BRANCH_REACH_M = SearchSheetModel.MAX_RADIUS_M

    /** How many of the nearest branches with readable hours a brand's hours are worked out from: as many as a kind's. */
    const val BRANCH_SAMPLE = HoursBook.KIND_SAMPLE

    /** [p]'s borrowed hours from the places of [source]; [lang] names the branch they come from. Reads the regions. */
    fun of(source: PlaceSource, p: Place, lang: String): HoursGuess? {
        if ("opening_hours" in p.tags) return null
        val r = PlaceRecord.of(p)
        return ofBrand(source, r, lang) ?: ofKind(source, r)
    }

    private fun ofBrand(source: PlaceSource, r: PlaceRecord, lang: String): HoursGuess? {
        val brand = r.brand ?: return null
        val near = source.byBrand(listOf(brand), BBox.around(r.location, BRANCH_REACH_M)).asSequence()
            .filter { it.place.key != r.place.key }
            .map { it to it.distanceTo(r.location) }
            .filter { it.second <= BRANCH_REACH_M }
            .sortedBy { it.second }
            .mapNotNull { (b, _) -> b.tags["opening_hours"]?.let(OpeningHours::parseOrNull)?.let(BrandHours::weekOf)?.let { b to it } }
            .take(BRANCH_SAMPLE)
            .toList()
        val inferred = BrandHours.fromWeeks(near.map { it.second }, BrandHours.MIN_SIBLINGS) ?: return null
        val one = near.singleOrNull()?.first?.let { b -> Brands.byId(brand)?.let { branchName(b.place, brandName(it), lang) } }
        return HoursGuess(inferred.hours, HoursFrom.BRAND, inferred.from, kept(inferred), one)
    }

    private fun ofKind(source: PlaceSource, r: PlaceRecord): HoursGuess? {
        val category = r.category ?: return null
        // a kind's guess is made for the middle of the place's cell: its places within reach of anywhere in the cell
        val around = source.byCategory(listOf(category), BBox.around(r.location, HoursBook.KIND_REACH_M + HoursBook.CELL_DEG * 111_320.0))
        val inferred = HoursBook((around + r).distinctBy { it.place.key }).ofKind(r) ?: return null
        return HoursGuess(inferred.hours, HoursFrom.KIND, inferred.from, kept(inferred))
    }

    private fun kept(h: InferredHours): Int = (h.agreement * h.from).roundToInt()
}
