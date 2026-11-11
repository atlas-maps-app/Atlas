package app.atlas.ui.search

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Vehicle
import app.atlas.route.Green
import app.atlas.route.RoundWalk
import app.atlas.route.RoundWalks
import app.atlas.route.RouteResult
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.places.PublicHolidays
import app.atlas.places.SunClock
import app.atlas.places.TagFilter
import app.atlas.providers.BathingSite
import app.atlas.providers.Forecast
import app.atlas.providers.Sky
import app.atlas.search.Browse
import app.atlas.search.CategoryPulse
import app.atlas.search.CategoryResults
import app.atlas.search.CountAnswer
import app.atlas.search.EvalContext
import app.atlas.search.EveryKind
import app.atlas.search.FacetSelection
import app.atlas.search.Facets
import app.atlas.search.FitReason
import app.atlas.search.Goods
import app.atlas.search.GoodsCatalogue
import app.atlas.search.Hit
import app.atlas.search.HoursAnswer
import app.atlas.search.HoursBook
import app.atlas.search.Intent
import app.atlas.search.Intents
import app.atlas.search.Laps
import app.atlas.search.KindView
import app.atlas.search.Largest
import app.atlas.search.Loosened
import app.atlas.search.Moment
import app.atlas.search.OfflineEvaluator
import app.atlas.search.Order
import app.atlas.search.Outlook
import app.atlas.search.ParsedQuery
import app.atlas.search.PlaceRecord
import app.atlas.search.Purposes
import app.atlas.search.Query
import app.atlas.search.Relevance
import app.atlas.search.SearchContext
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import app.atlas.search.Sure
import app.atlas.search.Travel
import app.atlas.search.TravelMode
import app.atlas.search.WeatherNow
import app.atlas.search.covers
import app.atlas.search.within
import app.atlas.services.PlaceOrigin
import app.atlas.services.SettingsKeys
import app.atlas.ui.go.SegmentFetch
import app.atlas.ui.go.SegmentNeed
import app.atlas.ui.go.SegmentOffer
import app.atlas.ui.home.HOME_CATEGORIES
import app.atlas.ui.home.HomeRow
import app.atlas.ui.kit.CurrentWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.max

/** The searcher's own choices for the search: kept filters, how the grid looks, what they chose before, how they go. */
@Immutable
data class SearchPrefs(
    val sticky: Set<String> = emptySet(),
    val smart: Boolean = true,
    val nearbyOnly: Boolean = false,
    val fixed: Boolean = false,
    val picks: Map<String, Int> = emptyMap(),
    val mode: TravelMode = TravelMode.WALK,
    /** Node ids chosen lately, newest first. */
    val recent: List<String> = emptyList(),
    /** The places found shown as large cards to slide through ([ResultCards]), not as rows. */
    val cards: Boolean = false,
    /**
     * The kinds, by node id, whose view shows the places of their kin too ([SearchSheetModel.kin]; the owner, 6 November:
     * "Fill the view with them and save that as a combined view if desired?"): the banks in the cash machines' view.
     */
    val together: Set<String> = emptySet(),
    /** The home's own row ([HomeRow]), by its ids in order: a kind's or a chain's star says whether it is in it. */
    val row: List<String> = HOME_CATEGORIES,
)

/** One entry of the Now shelf: a kind of place, or something to do, that fits the moment, and why. */
@Immutable
data class NowItem(val node: CategoryNode, val intent: Intent?, val reason: FitReason, val pulse: CategoryPulse?)

/**
 * What typed words opened in the sheet over the map when they are more than a kind's own view (decision 12: none has
 * a screen of its own): a query's places, every one of a kind the tree holds no node for, a brand's branches, the
 * places that sell something, an outing's plan. Its places are the sheet's results as a kind's are: ranked as the
 * kind they share is, every tag of them a filter with its count, the kinds among them a tap narrower.
 */
@Immutable
sealed interface Asked {
    /** The words as typed: the sheet's title, and the field's again on the way back. */
    val text: String

    /**
     * A query ("playground in the woods with a lake"): how it read; what was let go of to find any ([loosened]); its
     * answer to "how many" or to "which hours" where it asked; whether the answer came online; an area it named that
     * no store or geocoder knew (its places are then around the searcher).
     */
    data class Words(
        override val text: String,
        val parsed: ParsedQuery,
        val loosened: List<Loosened> = emptyList(),
        val count: CountAnswer? = null,
        val hours: HoursAnswer? = null,
        val online: Boolean = false,
        val unresolvedArea: String? = null,
    ) : Asked

    /** Every place of a kind the tree holds no node for: the tags it asks for, drawn with [symbol]. */
    data class Tags(override val text: String, val filter: TagFilter, val symbol: String) : Asked

    /**
     * A brand's branches around, one by one, under all that is known of it: its page ([BrandBand]). [typed] where typed
     * words asked for it, which back gives back; [from] the chain whose page it was reached from (a chain of the same
     * kind around), which back opens again.
     */
    data class Chain(override val text: String, val brand: Brand, val typed: Boolean = false, val from: Chain? = null) : Asked

    /** The places that sell [goods], parted by how surely they do. */
    data class Sells(override val text: String, val goods: Goods) : Asked

    /**
     * An outing in one sentence ("a café, then dinner"): its stops timed from now; null while it is planned. [picks]: the
     * place (its key) chosen by hand for a step instead of the planner's.
     */
    data class Outing(
        override val text: String,
        val parsed: ParsedQuery,
        val plan: AskPlan? = null,
        val refine: Set<Refine> = emptySet(),
        val picks: Map<Int, String> = emptyMap(),
    ) : Asked
}

/** What the one search shows: the grid around [centre], or inside a [node] (or an [intent]) its results and facets. */
@Immutable
data class SearchSheetState(
    val centre: LatLon,
    val moment: Moment,
    val prefs: SearchPrefs = SearchPrefs(),
    /** The grid's tiles, by node id: every category and shelf. */
    val pulses: Map<String, CategoryPulse> = emptyMap(),
    /** What each intent lists around, by its id: an intent's chip says of these, not of its whole kind ([Browse.pulse]). */
    val served: Map<String, CategoryPulse> = emptyMap(),
    /** The round walk asked for; it counts while the view of a walk is open ([walking]). */
    val roundWalk: RoundWalkState? = null,
    /** Where the grid's places came from; null before they are read or where the phone has none. */
    val gridOrigin: PlaceOrigin? = null,
    /**
     * The chains around with their branches near, stacked by kind ([BrandsAround.stacks]), the stack with the most
     * branches first, those hidden on the Brands board left out.
     */
    val brands: List<BrandStack> = emptyList(),
    /**
     * How many small questions about the grid's places near the map cannot answer yet ([QuestFinder]), up to its most;
     * null until the grid's last line comes into view and asks ([SearchSheetModel.countQuests]).
     */
    val quests: Int? = null,
    val now: List<NowItem> = emptyList(),
    val node: CategoryNode? = null,
    val intent: Intent? = null,
    val results: CategoryResults? = null,
    val loading: Boolean = false,
    val origin: PlaceOrigin? = null,
    val error: String? = null,
    /**
     * How far around the results were looked for (widened where few were near): the circle actually used, out to the
     * farthest result where none of the kind was in the widest circle ([noneWithin]).
     */
    val radius: Double = 0.0,
    /**
     * The circle, in metres, that held none of the kind when the results are the nearest wherever they are (the owner,
     * 4 November: "Default should be no maximum distance pre set if there are no results"); null when they are within
     * the circle the search widens to on its own.
     */
    val noneWithin: Double? = null,
    val selection: FacetSelection = FacetSelection.NONE,
    /** The time asked about ("open Saturday 14:00"). */
    val at: LocalDateTime? = null,
    /** The panel of every filter is open over the results. */
    val filtersOpen: Boolean = false,
    /** The order the searcher chose for the kind's results; null for the kind's own. */
    val order: Order? = null,
    /** Something to do chosen on the kind (swimming, on lakes): the results part by how surely each serves it. */
    val aim: Intent? = null,
    /** What typed words opened, when they are more than a kind's view; [node] then stands for its places' kind. */
    val asked: Asked? = null,
    /** The kind among an asked subject's places it is narrowed to. */
    val within: CategoryNode? = null,
    /** The wishes a place did not say that were answered from its row ("toilets?"), as sending, sent or failed, by [answerKey]. */
    val answered: Map<String, Sending> = emptyMap(),
    /** The place whose card is in view while the places show as cards ([SearchPrefs.cards]): its pin is chosen on the map. */
    val focus: String? = null,
    /**
     * Every kind of the families among the grid's places, as its tile would have it ([EveryKind.around]): the plain
     * list's, from the grid.
     */
    val kinds: List<CategoryPulse> = emptyList(),
    /** Every kind of everything else on the map among them (a road, a canal: the grid's last shelf), behind its own row. */
    val everything: List<CategoryPulse> = emptyList(),
    /** The chains behind each of those kinds, by node id ([EveryKind.chains]). */
    val chains: Map<String, List<String>> = emptyMap(),
    /** The plain list of every kind, while it is open; under a kind opened from it, the way back to it. */
    val every: EveryKindView? = null,
    /**
     * The kinds whose row of narrower ones is pulled open to show them all ([unfold]), by node id: open still when one
     * comes back up to it from a narrower one, till the sheet goes back to the grid.
     */
    val unfolded: Set<String> = emptySet(),
    /**
     * The kind each narrower one was entered from, by node id: the way back up goes there. Where a kind's chips are
     * those of the one kind under it that holds all its places (Groceries' one Groceries), the way back skips that one
     * as the chips did, instead of stopping on a view that looks the same.
     */
    val cameFrom: Map<String, String> = emptyMap(),
    /**
     * The kinds around that also sell what the kind in view is named for (the owner, 6 November: "A bakery might sell
     * pastries"), with how many of each the grid's places hold, the likeliest first ([GoodsCatalogue.alsoSold]): a
     * pastry shop's bakeries and supermarkets. None for most kinds, and none for what words asked.
     */
    val alsoSold: List<Pair<CategoryNode, Int>> = emptyList(),
    /**
     * The kinds that belong with the kind in view though each stands where it is (the owner, 6 November: "Miniature golf
     * is in fun. Where is golf? Link kinds that belong to each other."), with how many of each the grid's places hold,
     * those near first ([CategoryNode.links]): a minigolf's golf courses. None that [alsoSold] names, and none for what
     * words asked.
     */
    val seeAlso: List<Pair<CategoryNode, Int>> = emptyList(),
) {
    val inside: Boolean get() = node != null || asked != null
}

/**
 * The plain list of every kind (the owner, 6 November: "a plain all kinds list row sortable with every tag/kind that
 * exists"): those around from the grid, or those among a kind's places ([scope], a family) from inside it, as [kinds];
 * in [order], only those with one open where [openOnly]. [entered] is the kind opened from it, which the way back
 * leaves for the list again; [scope]'s view is where the way back from the list goes, the grid without one.
 */
@Immutable
data class EveryKindView(
    val kinds: List<CategoryPulse>,
    val scope: CategoryNode? = null,
    val order: EveryKind.Order = EveryKind.Order.MOST,
    val openOnly: Boolean = false,
    val entered: String? = null,
    /** From the grid, the kinds of everything else on the map (a road, a canal), as the grid's last shelf, behind a row of their own. */
    val everything: List<CategoryPulse> = emptyList(),
    /** The chains behind each kind, by node id, peeking out behind its symbol ([EveryKind.chains]). */
    val chains: Map<String, List<String>> = emptyMap(),
    /** Looking further for a kind's kinds none of which is near ([SearchSheetModel.lookFurther]): not yet, under way, done. */
    val further: Further = Further.NOT_YET,
    /** The kinds looked for further and found nowhere, by node id. */
    val nowhere: Set<String> = emptySet(),
    /** The network could not be asked while looking further (offline, or it failed): those found nowhere are only not on the phone. */
    val unasked: Boolean = false,
    /**
     * Opened from the home's own row to add to it ([SearchSheetModel.openEvery]): the families first, each with its kinds
     * around under it; a kind pressed, or a kind or chain typed, goes into the row ([SearchSheetModel.pickForRow]).
     */
    val picking: Boolean = false,
)

/** How far looking further for the kinds none of which is near has got. */
enum class Further { NOT_YET, LOOKING, DONE }

/**
 * The one search (decision 12): the grid of every kind of place around, with what each tile says now; inside a kind,
 * its places ranked its own way, a brand's branches together, every tag of them a filter with its count, narrower
 * kinds a tap away. Places come from the phone first (a region's store, else the map's packs) and online where the
 * phone has too few: the circle widens until enough are found. The searcher's kept filters ([SearchPrefs.sticky]) are
 * on in every kind that maps them.
 */
/**
 * A round walk asked for in a walk's view: how many minutes; the [walk] once found; else why there is none ([failed]),
 * with the routing data it would take ([needs]) and how far fetching that has come ([fetch]).
 */
@Immutable
data class RoundWalkState(
    val minutes: Int,
    val walk: RoundWalk? = null,
    val failed: RouteResult.Failure? = null,
    val needs: List<SegmentNeed> = emptyList(),
    val fetch: SegmentFetch? = null,
)

/** The round walk asked for, while the view of a walk it was asked for in is open. */
val SearchSheetState.walking: RoundWalkState? get() = roundWalk?.takeIf { intent?.id == Intents.WALK }

class SearchSheetModel(
    private val services: Services,
    centre: LatLon,
    private val lang: String,
    /** The weather where the searcher is, when the host knows it. */
    private val weather: () -> CurrentWeather? = { null },
    /** The forecast there, for the hours ahead out of doors. */
    private val forecast: () -> Forecast? = { null },
) : ViewModel() {
    private val mutable = MutableStateFlow(SearchSheetState(centre, moment(centre, SearchPrefs())))
    val state: StateFlow<SearchSheetState> = mutable.asStateFlow()

    private var pool: List<PlaceRecord> = emptyList()
    private var poolAt: LatLon? = null
    private var poolJob: Job? = null
    private var questsJob: Job? = null
    private var nodeJob: Job? = null
    private var rankJob: Job? = null
    private var alsoJob: Job? = null
    private var furtherJob: Job? = null
    private var walkJob: Job? = null

    /** The node's places around, the same kind further away, and their hours. */
    private var places: List<PlaceRecord> = emptyList()
    private var elsewhere: List<PlaceRecord> = emptyList()

    /** With Largest chosen, the kind's largest across the region that holds the centre ([largestOf]). */
    private var largest: List<PlaceRecord> = emptyList()
    private var hours: HoursBook = HoursBook.EMPTY

    /** The official bathing waters around, for kinds of water (lakes, beaches). */
    private var sites: List<BathingSite> = emptyList()
    private var sitesJob: Job? = null

    /** The places typed words found, as the engine found them (what each is near, what it leaves unsaid). */
    private var askedHits: List<Hit> = emptyList()
    private var askJob: Job? = null

    /** The circle typed words asked for ("within 500 m"): it does not widen. */
    private var fixedRadius: Double? = null

    /** Today is a public holiday where the grid's places are (read with them, off the main thread). */
    @Volatile private var holiday = false

    init {
        viewModelScope.launch {
            services.settings.data.map(::prefsOf).distinctUntilChanged().collect { p ->
                val s = mutable.value
                // rows or cards is how the same places are shown, and the home's row is the home's: nothing is looked
                // for or ranked again
                if (p != s.prefs && p.copy(cards = s.prefs.cards, row = s.prefs.row) == s.prefs) {
                    mutable.value = s.copy(prefs = p)
                    return@collect
                }
                // a filter kept or let go applies at once to the kind open
                val selection = if (s.inside) s.selection.minus(s.prefs.sticky - p.sticky) + FacetSelection.of(p.sticky - s.prefs.sticky) else s.selection
                mutable.value = s.copy(prefs = p, moment = moment(s.centre, p), selection = selection)
                refreshNow()
                // another way of getting about looks again, a quarter of an hour of it around; the kind in view shown with
                // its kin, or no longer, reads its places again
                val rejoined = s.node?.let { (it.id in p.together) != (it.id in s.prefs.together) } == true
                if (s.inside && (p.mode != s.prefs.mode || rejoined)) reload() else if (s.inside) rerank()
            }
        }
    }

    /** The search opened here, or the searcher moved: the grid's places are read, again once it is [POOL_MOVE_M] away. */
    fun moveTo(c: LatLon) {
        val s = mutable.value
        mutable.value = s.copy(centre = c, moment = moment(c, s.prefs))
        val last = poolAt
        if (last == null || Geo.distance(last, c) > POOL_MOVE_M) loadPool(c)
    }

    /** The weather or its forecast came, or changed: the moment weighs it from now on (a lake on a hot day). */
    fun skyChanged() {
        val s = mutable.value
        mutable.value = s.copy(moment = moment(s.centre, s.prefs))
        refreshNow()
        if (s.inside) rerank()
    }

    /**
     * Opens the node [id] ("cat:restaurants", "preset:amenity/cafe", a category or group id "drinks"); with [door], as one
     * more place to look from the kind in view (the bakeries from the pastry shops), which back returns to.
     */
    fun enter(id: String, door: Boolean = false) {
        val node = CategoryTree.byId(id) ?: CategoryTree.category(id) ?: return
        val s = mutable.value
        val from = s.node
        // down into a narrower kind, or through a door of this one (a kind it holds too, a shelf it leads into, a kind
        // that sells its goods too), keeps what was chosen; another kind starts from the kept filters
        val narrowing = from != null && (door || CategoryTree.isUnder(node, from) || CategoryTree.leadsTo(from, node))
        val selection = if (narrowing) s.selection else FacetSelection.of(s.prefs.sticky)
        // that way the way back is to this one; up, or back the way it came, the way stays; to a kind elsewhere, it starts afresh
        val cameFrom = when {
            from != null && narrowing && from.id != node.id -> s.cameFrom + (node.id to from.id)
            from != null && (CategoryTree.isUnder(from, node) || s.cameFrom[from.id] == node.id) -> s.cameFrom
            else -> emptyMap()
        }
        mutable.value = s.copy(
            node = node, intent = null, results = null, loading = true, error = null, selection = selection, filtersOpen = false, order = null, aim = null,
            asked = null, within = null, cameFrom = cameFrom, alsoSold = emptyList(), seeAlso = emptyList(),
        )
        pick(node.id)
        fixedRadius = null
        load(node, null, null)
        findAlso(node)
    }

    /** Opens a kind as typed words asked for it ("vegan restaurant open now"): its own view, their filters chosen. */
    fun enter(view: KindView) {
        val s = mutable.value
        mutable.value = s.copy(
            node = view.node, intent = null, results = null, loading = true, error = null, selection = view.selection + FacetSelection.of(s.prefs.sticky),
            at = view.at, filtersOpen = false, order = view.order, aim = view.aim, asked = null, within = null, cameFrom = emptyMap(), alsoSold = emptyList(), seeAlso = emptyList(),
        )
        pick(view.node.id)
        fixedRadius = view.radius
        load(view.node, null, view.aim)
        findAlso(view.node)
    }

    /**
     * Opens what typed words found ([item], one of [found]'s) here over the map (decision 12): a kind where they name
     * one, its own view where all they ask is a kind's filters, else what they asked as a subject of its own
     * ([Asked]). False for what keeps a page of its own (a place, a point, a way).
     */
    fun open(item: SearchItem, found: SearchResults): Boolean {
        // the list opened to add to the home's row takes the kind or the chain the words name
        if (mutable.value.every?.picking == true) pickedFrom(item)?.let { pickForRow(it); return true }
        val text = found.text.trim()
        when (item) {
            is SearchItem.Structured -> {
                val view = item.view
                when {
                    view != null -> enter(view)
                    item.parsed.isPlan -> plan(Asked.Outing(text, item.parsed))
                    else -> ask(Asked.Words(text, item.parsed, item.loosened, online = item.online, unresolvedArea = found.unresolvedArea), item.results.firstOrNull()?.hits.orEmpty())
                }
            }
            // "how many", "which hours": the places they speak of, the answer over them
            is SearchItem.Count, is SearchItem.Hours -> {
                val q = found.items.filterIsInstance<SearchItem.Structured>().firstOrNull() ?: return false
                val count = (item as? SearchItem.Count)?.answer
                val hours = (item as? SearchItem.Hours)?.answer
                ask(Asked.Words(text, q.parsed, q.loosened, count, hours, q.online, found.unresolvedArea), q.results.firstOrNull()?.hits.orEmpty())
            }
            is SearchItem.Kind -> {
                val node = kindNode(item.query)
                val tags = (item.query as? Query.Tags)?.filter
                when {
                    node != null -> enter(node.id)
                    tags != null -> ask(Asked.Tags(item.label, tags, item.symbol))
                    else -> return false
                }
            }
            // back gives the words typed back ("appie"), not the brand's index name
            is SearchItem.BrandResult -> ask(Asked.Chain(text.ifEmpty { item.brand.name }, item.brand, typed = true))
            is SearchItem.GoodsResult -> ask(Asked.Sells(item.label, item.goods))
            else -> return false
        }
        return true
    }

    /**
     * Counts the questions about the grid's places near ([SearchSheetState.quests]), once for the places read: the grid's
     * last line asks as it comes into view, so the facts of every place near are read only for those who scroll there.
     */
    fun countQuests() {
        val c = poolAt ?: return
        if (mutable.value.quests != null || questsJob?.isActive == true) return
        val places = pool
        questsJob = viewModelScope.launch {
            val n = withContext(Dispatchers.Default) { runCatching { QuestFinder.countAround(services, places.map { it.place }, c, lang) }.getOrDefault(0) }
            if (pool === places) mutable.value = mutable.value.copy(quests = n)
        }
    }

    /** A chain's branches around, one by one: a sign on the grid pressed, or a brand's page opened. */
    fun chain(brand: Brand) = ask(Asked.Chain(brand.name, brand))

    /** Another chain of the kind, pressed on a chain's page: back comes back to this one. */
    fun sibling(brand: Brand) = ask(Asked.Chain(brand.name, brand, from = mutable.value.asked as? Asked.Chain))

    /**
     * Back out of what was asked: to the chain whose page this one was reached from, else to the grid where a sign
     * opened it; what typed words asked closes and gives the words back ([Asked.text]), for the field to hold again.
     */
    fun backFromAsked(): String? {
        val a = mutable.value.asked ?: return null
        if (a is Asked.Chain && !a.typed) {
            a.from?.let { ask(it) } ?: close()
            return null
        }
        close()
        return a.text
    }

    /** Whether a note can leave the phone now. */
    fun canSend(): Boolean = !services.config.offline && services.connectivity.online.value

    /** Whether [key] of [place] was answered from this phone before, on its row or its page: it is asked no more. */
    fun answeredBefore(place: Place, key: String): Boolean = services.saved.answered(place.key, key)

    /**
     * Leaves the note answering [key] of [place] with [yes] ([Quests.keyNote]), the wish of a search its tags did not
     * say; the sheet has shown the user the note's exact text first.
     */
    fun answer(place: Place, key: String, yes: Boolean) {
        val at = answerKey(place, key)
        mutable.value = mutable.value.let { it.copy(answered = it.answered + (at to Sending.Busy)) }
        viewModelScope.launch {
            val r = try {
                val note = withContext(Dispatchers.IO) { services.osmNotes.create(place.location, Quests.keyNote(place, key, yes)) }
                withContext(Dispatchers.IO) { runCatching { services.saved.recordAnswer(place.key, key, yes, note.url) } }
                Sending.Done(note.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Sending.Failed(e.message ?: e.toString())
            }
            mutable.value = mutable.value.let { it.copy(answered = it.answered + (at to r)) }
        }
    }

    /** Asks [text] again as typed words (a chip taken out, a distance changed): the subject opens anew. */
    fun reword(text: String) {
        val s = mutable.value
        val now = ZonedDateTime.now(services.clock)
        val parsed = app.atlas.search.QueryParser.parse(text, lang, now)
        if (parsed.steps.isEmpty()) return close()
        if (parsed.isPlan) return plan(Asked.Outing(text, parsed, refine = (s.asked as? Asked.Outing)?.refine.orEmpty()))
        ask(Asked.Words(text, parsed))
    }

    /** Narrows an asked subject's places to [node], one of the kinds among them (null: all of them again). */
    fun narrow(node: CategoryNode?) {
        val s = mutable.value
        if (s.asked == null || s.within?.id == node?.id) return
        mutable.value = s.copy(within = node, node = node ?: s.node)
        rerank()
    }

    /** Turns a way of changing the outing on or off ([Refine]): it is planned again. */
    fun refine(r: Refine) {
        val o = mutable.value.asked as? Asked.Outing ?: return
        plan(o.copy(plan = null, refine = if (r in o.refine) o.refine - r else o.refine + r))
    }

    /** Takes [hit] for the outing's stop [step] instead: the stops after it are planned again from there. */
    fun pick(step: Int, hit: Hit) {
        val o = mutable.value.asked as? Asked.Outing ?: return
        plan(o.copy(plan = null, picks = o.picks + (step to hit.place.key)))
    }

    /** Opens [asked], with [hits] its words found so far (the field's few): the rest are read. */
    private fun ask(asked: Asked, hits: List<Hit> = emptyList()) {
        cancelReading()
        askedHits = hits
        places = hits.map { it.record }
        elsewhere = emptyList()
        hours = HoursBook(places)
        sites = emptyList()
        fixedRadius = null
        val s = mutable.value
        val node = (asked as? Asked.Chain)?.let { CategoryTree.of(it.brand.keyTags) } ?: Browse.kindFor(places)
        mutable.value = s.copy(
            node = node, intent = null, results = null, loading = true, error = null, selection = FacetSelection.of(s.prefs.sticky), at = null,
            filtersOpen = false, order = orderOf(asked), aim = null, asked = asked, within = null, radius = 0.0, noneWithin = null, alsoSold = emptyList(), seeAlso = emptyList(),
        )
        reload()
    }

    /** Plans [o]'s outing from the sheet's centre, now, each stop near the one before. */
    private fun plan(o: Asked.Outing) {
        cancelReading()
        places = emptyList()
        largest = emptyList()
        askedHits = emptyList()
        val s = mutable.value
        mutable.value = s.copy(node = null, intent = null, results = null, loading = true, error = null, filtersOpen = false, order = null, aim = null, asked = o, within = null)
        askJob = viewModelScope.launch {
            val planned = withContext(Dispatchers.Default) {
                runCatching {
                    val now = ZonedDateTime.now(services.clock)
                    val from = s.centre
                    val hours = HereContext.hours(services, from, now.zone)
                    val evaluator = OfflineEvaluator(services.placeStores)
                    AskPlanner.plan(o.parsed, from, now.toLocalDateTime(), hours, o.refine, o.picks) { q, at ->
                        evaluator.run(q, EvalContext(location = at, now = now, lang = lang, limit = PLAN_LIMIT, hours = hours))
                    }
                }
            }
            val now = mutable.value
            if (now.asked !== o) return@launch
            mutable.value = planned.fold(
                { p -> now.copy(asked = o.copy(plan = p), loading = false) },
                { e -> now.copy(loading = false, error = e.message ?: e.toString()) },
            )
        }
    }

    /** Opens what serves [intent]: swimming's beaches and bathing places, a dry place's cafés and libraries. */
    fun enter(intent: Intent) {
        val node = intent.categories.firstNotNullOfOrNull { CategoryTree.category(it) } ?: return
        val s = mutable.value
        walkJob?.cancel()
        mutable.value = s.copy(node = node, intent = intent, results = null, loading = true, error = null, selection = FacetSelection.of(s.prefs.sticky), filtersOpen = false, order = null, aim = null, asked = null, within = null, roundWalk = null)
        fixedRadius = null
        load(node, intent.filter, null)
    }

    /**
     * A round walk of [minutes] from the search's middle, where the searcher is when they are near it, through the
     * greens around that a walk lists; asked again, let go.
     */
    fun roundWalk(minutes: Int) {
        val s = mutable.value
        walkJob?.cancel()
        mutable.value = s.copy(roundWalk = if (s.walking?.minutes == minutes) null else RoundWalkState(minutes))
        if (s.walking?.minutes != minutes) findWalk(minutes)
    }

    private fun findWalk(minutes: Int) {
        val s = mutable.value
        val serving = s.intent ?: return
        walkJob = viewModelScope.launch {
            val greens = withContext(Dispatchers.Default) {
                (places + pool).filter { serving.takes(it) && serving.goesRound(it) }.distinctBy { it.place.key }.mapNotNull { r ->
                    r.place.bbox?.takeIf { Laps.metres(it) != null }?.let { Green(it, r.name(lang) ?: r.place.name()) }
                }
            }
            val found = try {
                RoundWalks.find(services.router, s.centre, minutes, greens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RoundWalks.Failed(RouteResult.Error(e.message ?: e.toString(), e))
            }
            val answer = when (found) {
                is RoundWalks.Found -> RoundWalkState(minutes, found.walk)
                is RoundWalks.Failed -> RoundWalkState(minutes, failed = found.why, needs = (found.why as? RouteResult.NoData)?.missingTiles.orEmpty().map { t ->
                    val name = t.removeSuffix(".rd5")
                    SegmentNeed(name, SegmentOffer.size(services.context, services.segments, name, services.connectivity.online.value))
                })
            }
            if (mutable.value.walking?.minutes == minutes) mutable.value = mutable.value.copy(roundWalk = answer)
        }
    }

    /** Fetches the routing data the round walk asked for lacks, then looks for the walk again. */
    fun fetchWalkData() {
        val asked = mutable.value.walking ?: return
        walkJob?.cancel()
        walkJob = viewModelScope.launch {
            try {
                for (n in asked.needs) SegmentOffer.download(services.regions, services.segments, n.tile).collect { f ->
                    mutable.value = mutable.value.copy(roundWalk = mutable.value.roundWalk?.copy(fetch = f))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(roundWalk = asked.copy(fetch = null))
                return@launch
            }
            mutable.value = mutable.value.copy(roundWalk = RoundWalkState(asked.minutes))
            findWalk(asked.minutes)
        }
    }

    /** Starts guiding the round walk found, as [name]: true when it has begun. A way round is no destination to remember. */
    suspend fun startWalk(context: Context, name: String): Boolean {
        val walk = mutable.value.walking?.walk ?: return false
        val hub = services.go
        hub.startNav(context, walk.request, walk.route, name, hub.guideSettings(Mode.WALK, Vehicle()), demo = false, remembered = false)
        return true
    }

    /** Orders the kind's results by [order] (the kind's own: back to it). */
    fun setOrder(order: Order) {
        val s = mutable.value
        val node = s.node ?: return
        mutable.value = s.copy(order = order.takeIf { it != Purposes.of(node).order })
        // the region's largest join those around, read once (a reading under way reads them itself as it ends)
        if (order != Order.LARGEST || largest.isNotEmpty() || s.asked != null || nodeJob?.isActive == true) return rerank()
        nodeJob = viewModelScope.launch {
            largest = withContext(Dispatchers.IO) { largestOf(node, mutable.value) }
            hours = HoursBook(places + elsewhere + largest)
            rerank(read = true)
            settle()
        }
    }

    /**
     * The largest of [node] (of the intent chosen on it) across the regions on the phone that hold the centre, read by
     * their size ([Largest]) once Largest is chosen: the view lists them with those around, a country's largest parks
     * however far (the user, 9 November: "Is it somewhere possible to see the largest green areas in your country or
     * around you?"). None for an intent that reads only as far as one goes there (a walk: the largest within reach).
     */
    private fun largestOf(node: CategoryNode, s: SearchSheetState): List<PlaceRecord> {
        if (s.order != Order.LARGEST || s.intent?.reach != null) return emptyList()
        val holding = services.placeStores.stores.filter { it.covers(s.centre) }
        if (holding.isEmpty()) return emptyList()
        return runCatching { Largest.of(holding, s.intent?.filter ?: node.filter, LARGEST_LIMIT) }.getOrDefault(emptyList())
    }

    /** The places a kind's results are made of: those read around, with Largest chosen the region's largest too. */
    private fun pool(s: SearchSheetState): List<PlaceRecord> {
        if (s.order != Order.LARGEST || largest.isEmpty()) return places
        val near = places.mapTo(HashSet()) { it.place.key }
        return places + largest.filter { it.place.key !in near }
    }

    /**
     * Chooses [intent] on the kind (swimming, on lakes), or lets it go: its places are looked for too, as far as the
     * nearest that surely serves it, and the results part by how surely each does.
     */
    fun aimAt(intent: Intent?) {
        val s = mutable.value
        val node = s.node ?: return
        if (s.aim?.id == intent?.id || s.asked != null) return
        mutable.value = s.copy(aim = intent, loading = true)
        load(node, s.intent?.filter, intent)
    }

    /**
     * One step up: to the kind the node was entered from, else its parent; out of a kind narrowed to among asked
     * places; from a kind opened on the list of every kind back to the list, from the list to where it was opened; or
     * back to the grid.
     */
    fun up() {
        val s = mutable.value
        // a shelf entered through another's door (Sport from Fun) has no parent but the way it came
        val back = s.node?.let { s.cameFrom[it.id] } ?: s.node?.parent?.id
        val every = s.every
        when {
            s.asked != null && s.within != null -> narrow(null)
            every != null && s.node == null && s.asked == null -> leaveEvery(every)
            every?.entered != null && s.node?.id == every.entered -> {
                leave()
                mutable.value = mutable.value.copy(every = every.copy(entered = null))
            }
            s.asked == null && s.intent == null && back != null -> enter(back)
            else -> close()
        }
    }

    /** Back to the grid. */
    fun close() {
        leave()
        furtherJob?.cancel()
        mutable.value = mutable.value.copy(every = null, unfolded = emptySet(), cameFrom = emptyMap())
    }

    /**
     * Pulls the row of [id]'s narrower kinds open, into lines that show every one, or folds it back into the one row
     * (the owner, 6 November: "Pull down on the subcategory row to expand?").
     */
    fun unfold(id: String, open: Boolean) {
        val s = mutable.value
        mutable.value = s.copy(unfolded = if (open) s.unfolded + id else s.unfolded - id)
    }

    /** Out of the kind in view (or what words asked), to what is under it: the grid, or the list of every kind. */
    private fun leave() {
        cancelReading()
        places = emptyList()
        largest = emptyList()
        elsewhere = emptyList()
        askedHits = emptyList()
        sites = emptyList()
        fixedRadius = null
        mutable.value = mutable.value.copy(
            node = null, intent = null, results = null, loading = false, error = null, filtersOpen = false, at = null, order = null, aim = null, asked = null,
            within = null, noneWithin = null, alsoSold = emptyList(), seeAlso = emptyList(), roundWalk = null,
        )
    }

    /**
     * Opens the plain list of every kind: from the grid every kind around; from inside a kind every kind it holds, those
     * among its places as its chips count them, then the rest, none of which is near ([EveryKind.all]); the way back from
     * it returns to that kind. [picking], from the home's own row: the families, each with its kinds around under it,
     * to add to the row ([pickForRow]), wherever the sheet was.
     */
    fun openEvery(picking: Boolean = false) {
        if (picking) {
            furtherJob?.cancel()
            if (mutable.value.inside) leave()
            val s = mutable.value
            mutable.value = s.copy(every = EveryKindView(pickable(s), chains = s.chains, picking = true))
            return
        }
        val s = mutable.value
        furtherJob?.cancel()
        val scope = s.node?.takeIf { s.asked == null && s.intent == null }
        if (scope == null) {
            mutable.value = s.copy(every = EveryKindView(s.kinds, everything = s.everything, chains = s.chains))
            return
        }
        val among = places
        val hours = HoursBook(among)
        leave()
        mutable.value = mutable.value.copy(every = EveryKindView(emptyList(), scope))
        viewModelScope.launch {
            val (kinds, chains) = withContext(Dispatchers.Default) {
                EveryKind.around(among, s.centre, s.moment, hours, scope).let { near -> EveryKind.all(scope, near) to EveryKind.chains(near, among) }
            }
            mutable.value.every?.takeIf { it.scope?.id == scope.id }?.let { mutable.value = mutable.value.copy(every = it.copy(kinds = kinds, chains = chains)) }
        }
    }

    /**
     * Looks further for the kinds on a kind's list none of which is near (the owner, 6 November: "no way to then extend
     * the search until results for every kind turn up"): the nearest of each wherever it is, on the phone first, then
     * online in as few questions as can hold them ([LocalData.nearestEach]), each row taking its nearest as it turns up;
     * at the end, those found nowhere. It goes on while a kind opened from the list is in view, and stops when the list
     * is left.
     */
    fun lookFurther() {
        val s = mutable.value
        val every = s.every?.takeIf { it.further != Further.LOOKING } ?: return
        val scope = every.scope ?: return
        val wanted = every.kinds.filter { it.count == 0 && it.nearest == null }.associate { it.node.id to it.node.filter }
        if (wanted.isEmpty()) return
        mutable.value = s.copy(every = every.copy(further = Further.LOOKING, nowhere = emptySet(), unasked = false))
        furtherJob?.cancel()
        furtherJob = viewModelScope.launch {
            val found = HashSet<String>()
            var unasked = false
            fun ours() = mutable.value.every?.takeIf { it.scope?.id == scope.id }
            services.localData.nearestEach(s.centre, wanted).collect { (id, answer) ->
                val place = answer.value.firstOrNull()
                if (place == null) {
                    if (answer.origin == null || answer.error != null) unasked = true
                    return@collect
                }
                found += id
                val r = PlaceRecord.of(place)
                val metres = r.distanceTo(s.centre)
                val e = ours() ?: return@collect
                val kinds = e.kinds.map { p -> if (p.node.id == id) p.copy(nearest = r, metres = metres, minutes = Travel.minutes(metres, s.moment.mode)) else p }
                mutable.value = mutable.value.copy(every = e.copy(kinds = kinds))
            }
            ours()?.let { e -> mutable.value = mutable.value.copy(every = e.copy(further = Further.DONE, nowhere = wanted.keys - found, unasked = unasked)) }
        }
    }

    /** Sorts the list of every kind by [order]. */
    fun everyOrder(order: EveryKind.Order) {
        mutable.value.every?.let { mutable.value = mutable.value.copy(every = it.copy(order = order)) }
    }

    /** Keeps on the list of every kind only those with one open now, or every one again. */
    fun everyOpen(on: Boolean) {
        mutable.value.every?.let { mutable.value = mutable.value.copy(every = it.copy(openOnly = on)) }
    }

    /** What the home's own row can take from the list: every family's shelf (but everything else on the map), and the kinds around. */
    private fun pickable(s: SearchSheetState): List<CategoryPulse> =
        CategoryTree.shelves().filter { it.category.family != CategoryTree.OSM }.map { s.pulses[it.id] ?: CategoryPulse.none(it) } + s.kinds

    /** The row's id for what typed words found, where it is a kind or a chain. */
    private fun pickedFrom(item: SearchItem): String? = when (item) {
        is SearchItem.Kind -> kindNode(item.query)?.let(HomeRow::idOf)
        is SearchItem.BrandResult -> HomeRow.idOf(item.brand)
        is SearchItem.Structured -> item.view?.node?.let(HomeRow::idOf)
        else -> null
    }

    /**
     * Puts [id] ("eat", "preset:leisure/playground", a chain's "brand:…") at the end of the home's own row, or takes it
     * out ([HomeRow]): a kind's or a chain's star.
     */
    fun star(id: String, on: Boolean) {
        viewModelScope.launch { HomeRow.edit(services.settings) { if (on) HomeRow.with(it, id) else it - id } }
    }

    /** Adds [id] to the home's own row from the list opened to add to it, which closes. */
    fun pickForRow(id: String) {
        star(id, true)
        furtherJob?.cancel()
        mutable.value = mutable.value.copy(every = null)
    }

    /** Opens kind [id] from the list of every kind: the way back from it returns to the list. */
    fun enterFromEvery(id: String) {
        val every = mutable.value.every ?: return
        enter(id)
        mutable.value = mutable.value.copy(every = every.copy(entered = id))
    }

    /** Out of the list of every kind, to the kind it was opened from, else to the grid. */
    private fun leaveEvery(every: EveryKindView) {
        furtherJob?.cancel()
        mutable.value = mutable.value.copy(every = null)
        every.scope?.let { enter(it.id) }
    }

    private fun cancelReading() {
        walkJob?.cancel()
        nodeJob?.cancel()
        rankJob?.cancel()
        askJob?.cancel()
        sitesJob?.cancel()
    }

    fun toggle(facet: String, value: String) = select(mutable.value.selection.toggle(facet, value))

    /** Clears every chosen value but the kept ones. */
    fun clearFilters() = select(FacetSelection.of(mutable.value.prefs.sticky))

    /** Asks what is open at [at] (null: back to now). */
    fun openAt(at: LocalDateTime?) {
        val s = mutable.value
        val without = s.selection.chosen[Facets.OPEN].orEmpty().filter { it.startsWith("at:") }.fold(s.selection) { sel, v -> sel.toggle(Facets.OPEN, v) }
        mutable.value = s.copy(at = at, selection = if (at == null) without else without.toggle(Facets.OPEN, "at:$at"))
        rerank()
    }

    fun showFilters(open: Boolean) {
        mutable.value = mutable.value.copy(filtersOpen = open)
    }

    /** The card of place [key] came into view, or its pin was tapped while the places show as cards. */
    fun focus(key: String?) {
        if (mutable.value.focus != key) mutable.value = mutable.value.copy(focus = key)
    }

    /** Shows the places found as large cards to slide through, or as rows; kept for every search after. */
    fun showCards(on: Boolean) = setPref(SettingsKeys.SEARCH_CARDS, on)

    /**
     * Shows [node]'s kin in its view too, with its own places, or no longer ([SearchPrefs.together]): kept for the kind,
     * so that its view opens so from then on (the owner, 6 November: "save that as a combined view if desired").
     */
    fun together(node: CategoryNode, on: Boolean) {
        viewModelScope.launch {
            services.settings.edit { p ->
                val now = p[SettingsKeys.SEARCH_TOGETHER].orEmpty()
                p[SettingsKeys.SEARCH_TOGETHER] = if (on) now + node.id else now - node.id
            }
        }
    }

    /** Keeps the filter [facet]=[value] on for every search, or lets it go. */
    fun keep(facet: String, value: String) {
        val id = "$facet=$value"
        viewModelScope.launch {
            services.settings.edit { p ->
                val now = p[SettingsKeys.SEARCH_STICKY].orEmpty()
                p[SettingsKeys.SEARCH_STICKY] = if (id in now) now - id else now + id
            }
        }
    }

    fun setPref(key: Preferences.Key<Boolean>, on: Boolean) {
        viewModelScope.launch { services.settings.edit { it[key] = on } }
    }

    fun setMode(mode: TravelMode) {
        viewModelScope.launch { services.settings.edit { it[SettingsKeys.SEARCH_MODE] = mode.name } }
    }

    private fun select(selection: FacetSelection) {
        mutable.value = mutable.value.copy(selection = selection)
        rerank()
    }

    // ------------------------------------------------------------------------------------------------- reading places

    /** Every place around [c] the phone holds, for the grid's tiles. */
    private fun loadPool(c: LatLon) {
        poolJob?.cancel()
        questsJob?.cancel()
        poolAt = c
        poolJob = viewModelScope.launch {
            val (found, origin) = try {
                withContext(Dispatchers.IO) { aroundOnPhone(c, POOL_RADIUS_M) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<PlaceRecord>() to null
            }
            pool = found
            mutable.value.node?.let { findAlso(it) }
            holiday = withContext(Dispatchers.IO) { runCatching { holidayAt(c) }.getOrDefault(false) }
            mutable.value = mutable.value.let { it.copy(moment = moment(it.centre, it.prefs)) }
            val s = mutable.value
            val (pulses, served) = withContext(Dispatchers.Default) {
                val book = HoursBook(found)
                Browse.pulses(GRID, found, c, s.moment, book) to Intents.all.mapNotNull { i -> nodeOf(i)?.let { n -> i.id to Browse.pulse(i, n, found, c, s.moment, book) } }.toMap()
            }
            mutable.value = mutable.value.copy(pulses = pulses.associateBy { it.node.id }, served = served, gridOrigin = origin)
            refreshNow()
            // the families' kinds on the list, everything else on the map (a road, a canal) behind its row, as on the grid
            val (all, chains) = withContext(Dispatchers.Default) {
                EveryKind.around(found, c, s.moment, HoursBook(found)).let { it to EveryKind.chains(it, found) }
            }
            val (everything, kinds) = all.partition { it.node.category.family == CategoryTree.OSM }
            mutable.value = mutable.value.copy(kinds = kinds, everything = everything, chains = chains)
            // the list opened to add to the home's row takes the kinds around as they come
            mutable.value.every?.takeIf { it.picking }?.let { e -> mutable.value = mutable.value.copy(every = e.copy(kinds = pickable(mutable.value), chains = chains)) }
            // the chains around come from the same places, after the tiles, which matter first; the questions are
            // counted again for these places when the grid asks
            val brands = withContext(Dispatchers.Default) { BrandsAround.stacks(chainsAround(found, c)).take(BRANDS) }
            mutable.value = mutable.value.copy(brands = brands, quests = null)
        }
    }

    /** The places within [r] of [c] on the phone: a region's store covering it, else the map's packs. */
    private fun aroundOnPhone(c: LatLon, r: Double): Pair<List<PlaceRecord>, PlaceOrigin?> {
        val stores = services.placeStores
        if (stores.size > 0 && stores.covers(c)) return stores.within(c, r) to PlaceOrigin.STORE
        val packed = services.localData.inPacks(c, r, ANY_PLACE, POOL_LIMIT) ?: return emptyList<PlaceRecord>() to null
        return packed.value.map { PlaceRecord.of(it) } to PlaceOrigin.PACK
    }

    /**
     * The chains among [found] within [BRANDS_RADIUS_M] of [c], most branches first, those hidden on the Brands board
     * left out: all of them, so that each kind's stack holds every one of its chains.
     */
    private fun chainsAround(found: List<PlaceRecord>, c: LatLon): List<BrandBranches> {
        val hidden = services.saved.hiddenBrands.value
        return found.filter { r -> r.brand.let { it != null && it !in hidden } && BrandsAround.isTrade(r.tags) && Geo.distance(c, r.location) <= BRANDS_RADIUS_M }
            .groupBy { it.brand!! }
            .mapNotNull { (id, rs) -> Brands.byId(id)?.let { b -> BrandBranches(b, rs.map { it.place }.distinctBy { it.key }.sortedBy { Geo.distance(c, it.location) }) } }
            .sortedWith(compareByDescending<BrandBranches> { it.branches.size }.thenBy { Geo.distance(c, it.branches.first().location) })
    }

    /** True when today is a public holiday in the country at [c]. */
    private fun holidayAt(c: LatLon): Boolean {
        val country = HereContext.country(services, c) ?: return false
        return PublicHolidays.forCountry(country)?.holiday(ZonedDateTime.now(services.clock).toLocalDate()) != null
    }

    /**
     * The places of [node] (or of [filter], an intent's) around the centre: from the phone, else online; a circle
     * with too few in it widens, up to [MAX_RADIUS_M], unless the answer came online (one question is enough). With
     * [aim] chosen on the kind, the places that serve it are read too, and the circle widens until one is in it. A
     * circle asked for ([fixedRadius]) does not widen while it holds any of the kind; one that holds none widens as
     * any does, its places beyond it the closest matches ([Browse.results]'s circle). None in the widest circle: the
     * nearest wherever they are ([farther]).
     */
    private fun load(node: CategoryNode, filter: TagFilter?, aim: Intent?, brand: String? = null) {
        nodeJob?.cancel()
        rankJob?.cancel()
        largest = emptyList()
        val c = mutable.value.centre
        // shown with its kin, their places are read with its own and count with them
        val with = if (filter == null && brand == null) joined(mutable.value, node) else emptyList()
        val takes = (filter ?: node.filter).let { if (with.isEmpty()) it else TagFilter.Or(listOf(it) + with.map { k -> k.filter }) }
            .let { if (aim != null) TagFilter.Or(listOf(it, aim.filter)) else it }
        if (Purposes.of(node).parts != null && brand == null) loadSites(c) else sites = emptyList()
        val fixed = fixedRadius
        nodeJob = viewModelScope.launch {
            // an intent may read farther from the start than the mode would (a walk: as far as one walks)
            var radius = fixed ?: max(startRadius(mutable.value.prefs.mode), mutable.value.intent?.reach ?: 0.0)
            var held: Int
            while (true) {
                val answer = try {
                    if (brand != null) services.localData.branches(brand, c, radius) else services.localData.places(c, radius, takes, LIMIT)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutable.value = mutable.value.copy(loading = false, error = e.message ?: e.toString(), results = null)
                    return@launch
                }
                val records = withContext(Dispatchers.Default) { answer.value.map { PlaceRecord.of(it) } }
                places = records
                mutable.value = mutable.value.copy(radius = radius, origin = answer.origin, error = answer.error)
                val serving = mutable.value.intent
                held = if (brand != null) records.size else records.count { r -> serving?.takes(r) ?: filter?.matches(r.tags) ?: (Browse.holds(node, r) || with.any { Browse.holds(it, r) }) }
                val enough = held >= ENOUGH &&
                    (aim == null || records.any { aim.filter.matches(it.tags) } || sites.any { it.swimmable && Geo.distance(c, it.location) <= radius })
                if (enough || fixed != null && held > 0 || radius >= MAX_RADIUS_M || answer.origin == PlaceOrigin.ONLINE) break
                // the last widening stops at the widest circle, not two and a half times past it
                radius = minOf(radius * WIDEN, MAX_RADIUS_M)
            }
            if (held == 0) farther(c, takes, brand, radius) else mutable.value = mutable.value.copy(noneWithin = null)
            elsewhere = if (brand != null) emptyList() else withContext(Dispatchers.IO) { runCatching { elsewhereOf(node, filter) }.getOrDefault(emptyList()) }
            largest = if (brand != null) emptyList() else withContext(Dispatchers.IO) { largestOf(node, mutable.value) }
            hours = HoursBook(places + elsewhere + largest)
            rerank(read = true)
            rate()
            settle()
        }
    }

    /**
     * None of the kind (or of [brand]) within [within] of [c]: the nearest wherever they are, the phone's region first,
     * then online where the phone holds none ([LocalData.farther]). The reach is then out to the farthest of them.
     */
    private suspend fun farther(c: LatLon, takes: TagFilter, brand: String?, within: Double) {
        val filter = brand?.let { id -> Brands.byId(id)?.let { b -> b.wikidata?.let { TagFilter.Equals("brand:wikidata", it) } ?: TagFilter.Equals("brand", b.brand) } } ?: takes
        val answer = try {
            services.localData.farther(c, filter, LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (answer.value.isEmpty()) return
        val records = withContext(Dispatchers.Default) { answer.value.map { PlaceRecord.of(it) } }
        places = records
        val reach = ceil(records.maxOf { it.distanceTo(c) } / FARTHER_STEP_M) * FARTHER_STEP_M
        mutable.value = mutable.value.copy(radius = max(reach, within), origin = answer.origin, error = answer.error, noneWithin = within)
    }

    /** Reading is done: the sheet stops looking, unless the results are still being ranked (which then says so). */
    private fun settle() {
        mutable.value = mutable.value.copy(loading = rankJob?.isActive == true)
    }

    /** The open ratings of the area, one request for all of the places: the order takes them in once they are here. */
    private suspend fun rate() {
        val found = places
        val rated = withContext(Dispatchers.IO) { services.areaRatings.rate(found.map { it.place }) }
        if (rated.indices.none { rated[it] !== found[it].place }) return
        places = withContext(Dispatchers.Default) { rated.map { PlaceRecord.of(it) } }
        // a query's places keep what the engine found of them, on their rated records
        if (askedHits.size == places.size) askedHits = askedHits.mapIndexed { i, h -> h.copy(record = places[i]) }
        hours = HoursBook(places + elsewhere)
        rerank()
    }

    /** Reads the places of what is open again: the kind's, or what was asked. */
    private fun reload() {
        val s = mutable.value
        when (val a = s.asked) {
            null -> s.node?.let { load(it, s.intent?.filter, s.aim) }
            is Asked.Words -> answer(a)
            is Asked.Tags -> s.node?.let { load(it, a.filter, null) }
            is Asked.Chain -> s.node?.let { load(it, null, null, brand = a.brand.id) }
            is Asked.Sells -> s.node?.let { load(it, sellersOf(a.goods), null) }
            is Asked.Outing -> plan(a)
        }
    }

    /**
     * Every place [w]'s words find, from the engine (the field showed only the first few, which rank at once): areas
     * resolved, online where the regions have none, loosened where nothing is as asked.
     */
    private fun answer(w: Asked.Words) {
        rerank()
        askJob?.cancel()
        askJob = viewModelScope.launch {
            val found = try {
                withContext(Dispatchers.IO) { services.search.answer(w.text, searchContext(LIMIT)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = e.message ?: e.toString())
                return@launch
            }
            val q = found?.items?.filterIsInstance<SearchItem.Structured>()?.firstOrNull()
            if (q == null || mutable.value.asked !== w) return@launch settle()
            askedHits = q.results.firstOrNull()?.hits.orEmpty()
            places = askedHits.map { it.record }
            hours = HoursBook(places)
            val count = found.items.filterIsInstance<SearchItem.Count>().firstOrNull()?.answer ?: w.count
            val week = found.items.filterIsInstance<SearchItem.Hours>().firstOrNull()?.answer ?: w.hours
            val now = w.copy(loosened = q.loosened, count = count, hours = week, online = q.online, unresolvedArea = found.unresolvedArea)
            // the reach the words' places were found within, out to the farthest of them
            val reach = askedHits.maxOfOrNull { it.distance ?: 0.0 }?.let { ceil(it / REACH_STEP_M) * REACH_STEP_M } ?: 0.0
            mutable.value = mutable.value.copy(asked = now, origin = if (q.online) PlaceOrigin.ONLINE else PlaceOrigin.STORE, radius = reach)
            rerank(read = true)
            rate()
            settle()
        }
    }

    /** Who is searching for the engine: here, now, in the sheet's language, online where the phone may go. */
    private fun searchContext(limit: Int): SearchContext {
        val c = mutable.value.centre
        val now = ZonedDateTime.now(services.clock)
        val online = !services.config.offline && services.connectivity.online.value
        return SearchContext(location = c, lang = lang, now = now, online = online, limit = limit, hours = HereContext.hours(services, c, now.zone))
    }

    /** The official bathing waters around [c], asked online once per kind of water entered; the results rank again with them. */
    private fun loadSites(c: LatLon) {
        sitesJob?.cancel()
        sitesJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    services.bathing.near(c, SITES_RADIUS_M)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // offline, switched off or down: the map's own tags still say what they say
                    emptyList()
                }
            }
            sites = found
            if (found.isNotEmpty()) rerank()
        }
    }

    /** The same kind of place across the regions on the phone, beyond the circle: their tags are filters too. */
    private fun elsewhereOf(node: CategoryNode, filter: TagFilter?): List<PlaceRecord> {
        val stores = services.placeStores
        if (stores.size == 0 || filter != null) return emptyList()
        val near = places.mapNotNullTo(HashSet()) { it.id }
        val ids = when (node.kind) {
            NodeKind.CATEGORY -> listOf(node.category.id)
            // a shelf's categories, and those of the kinds it holds too
            NodeKind.SHELF -> (CategoryTree.children(node.id).filter { it.kind == NodeKind.CATEGORY } + node.guests).map { it.category.id }.distinct()
            else -> listOfNotNull(node.category.id.takeIf { node.category.family != CategoryTree.OSM && Categories.byId(it) != null })
        }
        if (ids.isEmpty()) return emptyList()
        return stores.byCategory(ids, null, ELSEWHERE_LIMIT).filter { it.id !in near && Browse.holds(node, it) }
    }

    /**
     * Finds the kinds around that also sell what [node]'s kind is named for, and the kinds that belong with it, from the
     * grid's places ([alsoSold], [seeAlso]), for the kind in view: none where words asked.
     */
    private fun findAlso(node: CategoryNode) {
        alsoJob?.cancel()
        if (node.preset == null && node.links.isEmpty()) return
        alsoJob = viewModelScope.launch {
            val (also, see) = withContext(Dispatchers.Default) { alsoSold(node, pool).let { it to seeAlso(node, pool, it) } }
            val s = mutable.value
            if (s.node?.id == node.id && s.asked == null) mutable.value = s.copy(alsoSold = also, seeAlso = see)
        }
    }

    /**
     * Ranks the places read for what is open. While they are still being read and none are yet, it waits, so that no
     * "none around" shows before the reading is done; [read] comes from the reading itself once it is done, where none
     * may be the answer (a kind none of which is anywhere on a phone offline).
     */
    private fun rerank(read: Boolean = false) {
        val s = mutable.value
        val node = s.node ?: return
        if (!read && places.isEmpty() && s.loading && reading()) return
        rankJob?.cancel()
        rankJob = viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { rank(s, node) }
            val now = mutable.value
            // a kind that asked places stand for follows them (a café's for "cafe with toilets")
            mutable.value = now.copy(results = r, node = if (now.asked != null && r != null) r.node else now.node, loading = reading())
        }
    }

    /** The results of what [s] has open, over the places read: a kind's, or an asked subject's. */
    private fun rank(s: SearchSheetState, node: CategoryNode): CategoryResults? {
        val selection = applicable(s)
        // narrowed to one of the kinds among asked places: those it holds
        fun within(f: TagFilter) = s.within?.let { TagFilter.And(listOf(f, it.filter)) } ?: f
        fun kind(f: TagFilter) = s.within ?: Browse.kindFor(places.filter { f.matches(it.tags) })
        return when (val a = s.asked) {
            null -> Browse.results(
                // an intent's kinds are those among its places (a walk's parks, views and monuments), not its shelf's alone
                node, pool(s), s.centre, s.moment, selection, elsewhere, hours, at = s.at, filter = s.intent?.filter, intent = s.intent, order = s.order, aim = s.aim, sites = sites,
                circle = fixedRadius, with = joined(s, node),
            )
            is Asked.Words -> Browse.asked(askedHits, s.centre, s.moment, selection, hours, at = s.at, order = s.order, within = s.within)
            is Asked.Tags -> within(a.filter).let { f -> Browse.results(kind(f), places, s.centre, s.moment, selection, hours = hours, at = s.at, filter = f, order = s.order) }
            is Asked.Chain -> within(TagFilter.All).let { f -> Browse.results(kind(f), places, s.centre, s.moment, selection, hours = hours, brandSearched = a.brand.id, at = s.at, filter = f, order = s.order) }
            is Asked.Sells -> within(sellersOf(a.goods)).let { f ->
                Browse.results(kind(f), places, s.centre, s.moment, selection, hours = hours, at = s.at, filter = f, order = s.order, sureOf = { r -> sells(a.goods, r) })
            }
            is Asked.Outing -> null
        }
    }

    private fun reading(): Boolean = nodeJob?.isActive == true || askJob?.isActive == true

    /** [node]'s kin where its view shows them with it ([SearchPrefs.together]): a kind's own view, not words' nor an intent's. */
    private fun joined(s: SearchSheetState, node: CategoryNode): List<CategoryNode> =
        if (s.asked == null && s.intent == null && node.id in s.prefs.together) kin(node) else emptyList()

    /** The chosen values, the kept ones left out where no place of the kind maps their tag (vegan at a fuel station). */
    private fun applicable(s: SearchSheetState): FacetSelection {
        val kept = FacetSelection.of(s.prefs.sticky)
        val mapped = places.flatMapTo(HashSet()) { it.tags.keys } + setOf(Facets.OPEN, Facets.PRICE, Facets.CHECKED)
        return FacetSelection(s.selection.chosen.filter { (facet, values) -> facet in mapped || values.any { !kept.has(facet, it) } })
    }

    /** The kind standing for what [i] lists, in the ranking and the view. */
    private fun nodeOf(i: Intent): CategoryNode? = i.categories.firstNotNullOfOrNull { CategoryTree.category(it) }

    /** The Now shelf: what fits the moment (a lake on a hot day, somewhere dry in the rain), with places near first. */
    private fun refreshNow() {
        val s = mutable.value
        val m = s.moment
        val intents = Intents.now(m, 3).mapNotNull { (i, why) ->
            val node = nodeOf(i) ?: return@mapNotNull null
            NowItem(node, i, why, s.served[i.id])
        }
        val leaves = Categories.leaves().map { it.id }
        val categories = Relevance.order(leaves, m).asSequence()
            .mapNotNull { CategoryTree.category(it) }
            .filter { n -> (s.pulses[n.id]?.count ?: 0) > 0 && intents.none { it.node.id == n.id } }
            .take(NOW_SIZE - intents.size)
            .map { n -> NowItem(n, null, Relevance.score(n.category.id, m).second, s.pulses[n.id]) }
        mutable.value = mutable.value.copy(now = intents + categories)
    }

    private fun pick(id: String) {
        viewModelScope.launch {
            services.settings.edit { p ->
                val picks = picksOf(p[SettingsKeys.SEARCH_PICKS].orEmpty()).toMutableMap()
                picks[id] = (picks[id] ?: 0) + 1
                p[SettingsKeys.SEARCH_PICKS] = picks.entries.sortedByDescending { it.value }.take(MAX_PICKS).map { "${it.key}=${it.value}" }.toSet()
                val recent = listOf(id) + recentOf(p[SettingsKeys.SEARCH_RECENT]).filter { it != id }
                p[SettingsKeys.SEARCH_RECENT] = recent.take(MAX_RECENT).joinToString(RECENT_SEPARATOR)
            }
        }
    }

    private fun moment(c: LatLon, p: SearchPrefs): Moment {
        val now = ZonedDateTime.now(services.clock)
        val sunset = runCatching { SunClock.at(c.lat, c.lon, now.zone).times(now.toLocalDate())?.sunset }.getOrNull()
        val outlook = forecast()?.let { f -> runCatching { Outlook.of(f, now) }.getOrNull() }
        return Moment(now, weatherNow(weather()), p.mode, p.picks, p.recent, sunset, lang, outlook, holiday)
    }

    companion object {
        /** How far around the grid's tiles count places. */
        const val POOL_RADIUS_M = 1_500.0
        private const val POOL_MOVE_M = 300.0
        private const val POOL_LIMIT = 6_000

        /** The most stacks of chains the grid shows around. */
        private const val BRANDS = 16

        /** The most places of a kind read, nearest first. */
        const val LIMIT = 400

        /** The most of a region's largest of a kind a view lists beyond those around ([largestOf]). */
        private const val LARGEST_LIMIT = 40
        private const val ELSEWHERE_LIMIT = 1_500

        /** Fewer of a kind than this in the circle widen it, by [WIDEN] each time, to [MAX_RADIUS_M]. */
        private const val ENOUGH = 8
        private const val WIDEN = 2.5
        const val MAX_RADIUS_M = 25_000.0

        /** The reach of results found farther than the widest circle, rounded up to this, in metres. */
        private const val FARTHER_STEP_M = 1_000.0

        /** The reach of typed words' places, rounded up to this, in metres. */
        private const val REACH_STEP_M = 100.0
        private const val NOW_SIZE = 6
        private const val MAX_PICKS = 40
        private const val MAX_RECENT = 12
        private const val RECENT_SEPARATOR = "|"

        /** How many places each step of an outing weighs. */
        private const val PLAN_LIMIT = 20

        /** How far around the official bathing waters are asked for: as far as a swim is worth going. */
        private const val SITES_RADIUS_M = 25_000.0

        /** The circle first looked in: about a quarter of an hour of the searcher's way of getting about. */
        fun startRadius(mode: TravelMode): Double = when (mode) {
            TravelMode.WALK -> 1_200.0
            TravelMode.BIKE -> 3_000.0
            TravelMode.TRANSIT -> 4_000.0
            TravelMode.CAR -> 8_000.0
        }

        /** The grid's nodes whose tiles say something: every shelf, every category and every key of the last shelf. */
        val GRID: List<CategoryNode> by lazy { CategoryTree.all().filter { it.kind == NodeKind.SHELF || it.kind == NodeKind.CATEGORY || it.kind == NodeKind.KEY } }

        /** What makes a place for the grid: any category's rule. */
        private val ANY_PLACE: TagFilter by lazy { TagFilter.Or(Categories.leaves().mapNotNull { it.rule }) }

        fun prefsOf(p: Preferences) = SearchPrefs(
            sticky = p[SettingsKeys.SEARCH_STICKY].orEmpty(),
            smart = p[SettingsKeys.SEARCH_SMART] ?: true,
            nearbyOnly = p[SettingsKeys.SEARCH_NEARBY] ?: false,
            fixed = p[SettingsKeys.SEARCH_FIXED] ?: false,
            picks = picksOf(p[SettingsKeys.SEARCH_PICKS].orEmpty()),
            mode = p[SettingsKeys.SEARCH_MODE]?.let { runCatching { TravelMode.valueOf(it) }.getOrNull() } ?: TravelMode.WALK,
            recent = recentOf(p[SettingsKeys.SEARCH_RECENT]),
            cards = p[SettingsKeys.SEARCH_CARDS] ?: false,
            together = p[SettingsKeys.SEARCH_TOGETHER].orEmpty(),
            row = HomeRow.ids(p[SettingsKeys.HOME_ROW]),
        )

        private fun recentOf(s: String?): List<String> = s?.split(RECENT_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

        private fun picksOf(set: Set<String>): Map<String, Int> =
            set.mapNotNull { e -> e.substringBeforeLast('=').takeIf { it.isNotEmpty() }?.let { it to (e.substringAfterLast('=').toIntOrNull() ?: 1) } }.toMap()

        /** The order an asked subject opens in: a query's own where it asked for one ("oldest first"), else its kind's. */
        fun orderOf(a: Asked): Order? {
            val q = (a as? Asked.Words)?.parsed?.query ?: return null
            val top = if (q is Query.Limit) q.query else q
            return Order.ASKED.takeIf { top is Query.Sort }
        }

        /** Every kind that sells [g]. */
        fun sellersOf(g: Goods): TagFilter = TagFilter.Or(g.sellers.map { it.filter })

        /**
         * The kinds among [around] that also sell what [node]'s kind is named for ([GoodsCatalogue.alsoSold]), with how
         * many of each there are, the likeliest first and as many as [ALSO_SOLD]; its own and narrower kinds left out.
         */
        fun alsoSold(node: CategoryNode, around: List<PlaceRecord>): List<Pair<CategoryNode, Int>> =
            soldAt(node).map { k -> k to around.count { Browse.holds(k, it) } }.filter { it.second > 0 }.take(ALSO_SOLD)

        /** The kinds that also sell what [node]'s kind is named for ([GoodsCatalogue.alsoSold]), its own and narrower ones left out. */
        private fun soldAt(node: CategoryNode): List<CategoryNode> {
            val kind = node.preset ?: return emptyList()
            return GoodsCatalogue.alsoSold(kind).mapNotNull { s -> s.preset?.let { CategoryTree.preset(it.id) } }.distinctBy { it.id }
                .filter { !CategoryTree.isUnder(it, node) && !CategoryTree.isUnder(node, it) }
        }

        /**
         * The kinds [node]'s view shows with its own places when they are kept together ([SearchPrefs.together]; the owner,
         * 6 November: "Fill the view with them and save that as a combined view if desired?", and of the cash machines' "a
         * bank is almost always an atm too"): those that also sell what it is named for, then those that belong with it,
         * as its rows name them. The frozen food shops' supermarkets, the cash machines' banks and change offices.
         */
        fun kin(node: CategoryNode): List<CategoryNode> = (soldAt(node) + node.links).distinctBy { it.id }

        /** How many kinds that also sell a kind's goods its view names at most. */
        private const val ALSO_SOLD = 3

        /**
         * The kinds that belong with [node] ([CategoryNode.links]), with how many of each there are among [around], those
         * near first and each in its links' order; none that [also] names already.
         */
        fun seeAlso(node: CategoryNode, around: List<PlaceRecord>, also: List<Pair<CategoryNode, Int>> = emptyList()): List<Pair<CategoryNode, Int>> {
            val sold = also.mapTo(HashSet()) { it.first.id }
            return node.links.filter { it.id !in sold }.map { k -> k to around.count { Browse.holds(k, it) } }.sortedByDescending { it.second > 0 }
        }

        /** How surely [r] sells [g]: by the likeliest of its sellers that it is (a hardware store surely has picture hooks). */
        fun sells(g: Goods, r: PlaceRecord): Sure {
            val likelihood = g.sellers.filter { it.filter.matches(r.tags) }.maxOfOrNull { it.likelihood } ?: return Sure.MAYBE
            return when {
                likelihood >= SURELY -> Sure.YES
                likelihood >= LIKELY -> Sure.LIKELY
                else -> Sure.MAYBE
            }
        }

        /** The likelihoods (of 5) from which a seller surely, or likely, has the goods. */
        private const val SURELY = 4
        private const val LIKELY = 2

        /** The weather as the search weighs it: rain and storms from the sky, sun by day. */
        fun weatherNow(w: CurrentWeather?): WeatherNow? = w?.let {
            WeatherNow(
                tempC = it.temperature,
                rain = it.sky.wet,
                storm = it.sky == Sky.THUNDERSTORM || it.sky == Sky.THUNDERSTORM_HAIL,
                sunny = it.isDay && (it.sky == Sky.CLEAR || it.sky == Sky.MAINLY_CLEAR),
            )
        }
    }
}

/** [this] without the values of the ids in [ids] ("wheelchair=~yes"). */
private fun FacetSelection.minus(ids: Set<String>): FacetSelection =
    FacetSelection.of(ids).chosen.entries.fold(this) { sel, (f, vs) -> vs.fold(sel) { s, v -> if (s.has(f, v)) s.toggle(f, v) else s } }

/** The kind in the tree that typed words name: a category by its id, a preset by the tags it asks for. */
internal fun kindNode(q: Query): CategoryNode? = when (q) {
    is Query.Category -> CategoryTree.category(q.id)
    is Query.Tags -> CategoryTree.all().firstOrNull { it.kind == NodeKind.PRESET && it.filter == q.filter }
    else -> null
}

/** Where an answer to [key] of [place] stands in [SearchSheetState.answered]. */
internal fun answerKey(place: Place, key: String): String = place.key + "|" + key
