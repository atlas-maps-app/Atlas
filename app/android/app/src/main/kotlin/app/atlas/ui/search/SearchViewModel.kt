package app.atlas.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.search.EvalContext
import app.atlas.search.OfflineEvaluator
import app.atlas.search.Query
import app.atlas.search.QueryResult
import app.atlas.search.SearchContext
import app.atlas.search.SearchItem
import app.atlas.search.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** What the Search screen shows: the text, whether it is still looking, the answer and the narrowing chips that are on. */
data class SearchState(
    val text: String = "",
    /** True from a keystroke until its answer is in. */
    val searching: Boolean = false,
    /** The answer for [text], or null before the first one (and for an empty field). */
    val results: SearchResults? = null,
    /** The places left once the chips in [selected] narrow the answer; null when no chip is on. */
    val refined: QueryResult? = null,
    /** The values of the refinement chips that are on ("now", "wheelchair~…"). */
    val selected: Set<String> = emptySet(),
    /** Why the search failed, when it did. */
    val error: String? = null,
)

/**
 * The search box's brain: each keystroke (debounced) runs `SearchEngine` over the downloaded regions, a newer
 * keystroke cancelling the older search; online it adds Photon and Overpass. Chips narrow the answer offline with the
 * same evaluator. [category] keeps only places of one category (and its leaves).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val services: Services,
    initial: String,
    private val category: String? = null,
    private val here: () -> LatLon,
    private val lang: String,
) : ViewModel() {
    private val text = MutableStateFlow(initial)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val mutable = MutableStateFlow(SearchState(text = initial, searching = initial.isNotBlank()))
    val state: StateFlow<SearchState> = mutable.asStateFlow()

    private val leaves: Set<String>? = category?.let { id ->
        val c = Categories.byId(id) ?: return@let null
        if (c.isGroup) Categories.children(id).mapTo(HashSet()) { it.id } else setOf(id)
    }

    init {
        viewModelScope.launch {
            // The text the screen opened with runs at once; what is typed after waits for a pause.
            merge(flowOf(initial), text.drop(1).debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }).distinctUntilChanged().collectLatest { t -> run(t) }
        }
        viewModelScope.launch {
            val answers = mutable.map { it.results }.distinctUntilChanged { a, b -> a === b }
            combine(answers, selected) { r, sel -> r to sel }.collectLatest { (results, sel) -> refine(results, sel) }
        }
    }

    /** The text typed: a new search starts after a short pause. */
    fun onText(t: String) {
        if (t == text.value) return
        text.value = t
        selected.value = emptySet()
        mutable.value = mutable.value.copy(text = t, searching = t.isNotBlank(), selected = emptySet(), refined = null)
    }

    /** Turns the refinement chip with [value] on or off. */
    fun toggle(value: String) {
        selected.value = selected.value.let { if (value in it) it - value else it + value }
        mutable.value = mutable.value.copy(selected = selected.value)
    }

    /** Remembers that the search [text] led to [place] (or was simply made), for the recent list. */
    fun remember(text: String, place: Place?) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { services.saved.addRecent(text, place) } }
    }

    private suspend fun run(t: String) {
        if (t.isBlank()) {
            mutable.value = mutable.value.copy(text = t, searching = false, results = null, refined = null, error = null)
            return
        }
        try {
            val r = services.search.search(t, context())
            mutable.value = mutable.value.copy(text = t, searching = false, results = narrow(r), error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, a store closed under the search (a region deleted meanwhile), a broken answer: say so, never crash.
            mutable.value = mutable.value.copy(searching = false, error = e.message ?: e.toString())
        }
    }

    private suspend fun refine(results: SearchResults?, sel: Set<String>) {
        if (results == null || sel.isEmpty()) {
            if (mutable.value.refined != null) mutable.value = mutable.value.copy(refined = null)
            return
        }
        val chosen = results.refinements.filter { it.chip.value in sel }
        if (chosen.isEmpty()) return
        val q = Query.and(chosen.map { it.query })
        val ctx = context()
        val r = try {
            withContext(Dispatchers.Default) {
                OfflineEvaluator(services.placeStores).run(q, EvalContext(ctx.location, ctx.viewport, ctx.now, ctx.lang, ctx.limit, ctx.hours))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (mutable.value.results === results) mutable.value = mutable.value.copy(refined = r)
    }

    /** Keeps only places of [category] when the search was made for one. */
    private fun narrow(r: SearchResults): SearchResults {
        val keep = leaves ?: return r
        return r.copy(items = r.items.filter { it !is SearchItem.PlaceResult || it.hit.record.category in keep })
    }

    private fun context(): SearchContext {
        val at = here()
        val now = ZonedDateTime.now(services.clock)
        return SearchContext(
            location = at,
            lang = lang,
            now = now,
            online = !services.config.offline && services.connectivity.online.value,
            country = null,
            limit = LIMIT,
            hours = HereContext.hours(services, at, now.zone),
        )
    }

    companion object {
        const val DEBOUNCE_MS = 140L
        const val LIMIT = 30
    }
}
