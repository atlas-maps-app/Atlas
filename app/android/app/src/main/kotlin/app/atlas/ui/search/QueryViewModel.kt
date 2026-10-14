package app.atlas.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.providers.OverpassArea
import app.atlas.search.EvalContext
import app.atlas.search.Hit
import app.atlas.search.OfflineEvaluator
import app.atlas.search.OnlineSearch
import app.atlas.search.OverpassCompiler
import app.atlas.search.ParsedQuery
import app.atlas.search.Query
import app.atlas.search.QueryParser
import app.atlas.search.QueryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** One far-side feature to draw: a "B" place, its clause, and the circle of the distance around it. */
data class NearCircle(val clause: Int, val at: LatLon, val radius: Double, val key: String, val symbol: String)

/** What the query builder shows: the text, how it read, the answer, the circles and how long it took. */
data class QueryState(
    val text: String = "",
    val parsed: ParsedQuery? = null,
    /** The distance of each ↳ row the user set by hand, by row. */
    val distances: Map<Int, Double> = emptyMap(),
    val running: Boolean = false,
    val result: QueryResult? = null,
    /** True when the answer came from Overpass rather than the regions on the phone. */
    val online: Boolean = false,
    val circles: List<NearCircle> = emptyList(),
    val error: String? = null,
)

/**
 * The query builder's brain: the text read into a query (natural or power syntax), each ↳ row's distance editable,
 * run offline over the regions with the spatial join, and online through Overpass when asked (or when the regions
 * have nothing). [ql] is the Overpass QL of the same query, for `{} QL`.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class QueryViewModel(
    private val services: Services,
    initial: String,
    private val here: () -> LatLon,
    private val lang: String,
) : ViewModel() {
    private val input = MutableStateFlow(initial to emptyMap<Int, Double>())
    private val mutable = MutableStateFlow(QueryState(text = initial, running = initial.isNotBlank()))
    val state: StateFlow<QueryState> = mutable.asStateFlow()
    private var onlineJob: Job? = null

    init {
        viewModelScope.launch {
            merge(flowOf(input.value), input.drop(1).debounce(DEBOUNCE_MS)).collectLatest { (t, d) -> run(t, d) }
        }
    }

    fun onText(t: String) {
        if (t == mutable.value.text) return
        mutable.update { it.copy(text = t, distances = emptyMap(), running = t.isNotBlank()) }
        input.value = t to emptyMap()
    }

    /** Sets the distance of ↳ row [row]. */
    fun setDistance(row: Int, metres: Double) {
        val d = mutable.value.distances + (row to metres)
        mutable.update { it.copy(distances = d, running = true) }
        input.value = mutable.value.text to d
    }

    /** The query as it runs now: the text's, with the distances set by hand. */
    fun query(): Query? {
        val p = mutable.value.parsed ?: return null
        if (p.steps.isEmpty()) return null
        return QueryEdit.withDistances(p, mutable.value.distances)
    }

    /** The Overpass QL of the query over the area it searched. */
    fun ql(): String? = query()?.let { OverpassCompiler.compile(it, area(), here()) }

    /** Runs the query on Overpass (the whole planet); the answer replaces the offline one. */
    fun runOnline() {
        val q = query() ?: return
        if (!online()) return
        onlineJob?.cancel()
        mutable.update { it.copy(running = true, error = null) }
        onlineJob = viewModelScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { OnlineSearch(services.overpass).run(q, area(), context()) }
                mutable.update { it.copy(running = false, result = answer.result, online = true, circles = circlesOf(answer.result.hits)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun online(): Boolean = !services.config.offline && services.connectivity.online.value

    private suspend fun run(text: String, distances: Map<Int, Double>) {
        onlineJob?.cancel()
        if (text.isBlank()) {
            mutable.update { it.copy(parsed = null, result = null, circles = emptyList(), running = false, error = null, online = false) }
            return
        }
        val now = ZonedDateTime.now(services.clock)
        val parsed = withContext(Dispatchers.Default) { QueryParser.parse(text, lang, now) }
        mutable.update { it.copy(parsed = parsed) }
        if (parsed.steps.isEmpty()) {
            mutable.update { it.copy(result = null, circles = emptyList(), running = false, online = false) }
            return
        }
        val q = QueryEdit.withDistances(parsed, distances)
        val result = withContext(Dispatchers.Default) {
            runCatching { OfflineEvaluator(services.placeStores).run(q, context()) }
        }
        result.onSuccess { r ->
            mutable.update { it.copy(result = r, online = false, circles = circlesOf(r.hits), running = false, error = null) }
            // Nothing on the phone (no region here) but a connection: ask Overpass at once.
            if (r.hits.isEmpty() && services.placeStores.size == 0 && online()) runOnline()
        }.onFailure { e ->
            if (e is CancellationException) throw e
            mutable.update { it.copy(running = false, error = e.message ?: e.toString()) }
        }
    }

    private fun context(): EvalContext {
        val at = here()
        val now = ZonedDateTime.now(services.clock)
        return EvalContext(location = at, now = now, lang = lang, limit = LIMIT, hours = HereContext.hours(services, at, now.zone))
    }

    /** Where the query looks online: around the answers found offline, else 1.5 km around here. */
    private fun area(): OverpassArea {
        val hits = mutable.value.result?.hits.orEmpty()
        val box = BBox.of(hits.map { it.record.location })
        return if (box != null && !mutable.value.online) OverpassArea.Box(box.buffer(AREA_MARGIN_M)) else OverpassArea.Around(here(), AROUND_M)
    }

    companion object {
        const val LIMIT = 60
        const val DEBOUNCE_MS = 220L
        private const val AROUND_M = 1_500.0
        private const val AREA_MARGIN_M = 300.0
        private const val MAX_CIRCLES = 40

        /** The far-side features of the best answers, each once per clause, as circles of the clause's distance. */
        fun circlesOf(hits: List<Hit>): List<NearCircle> {
            val seen = HashSet<String>()
            val out = ArrayList<NearCircle>()
            for (h in hits.take(20)) for (n in h.near) {
                val key = "${n.clause}:${n.record.place.key}"
                if (!seen.add(key)) continue
                out += NearCircle(n.clause, n.record.location, n.maxDistance, key, n.record.symbol)
                if (out.size >= MAX_CIRCLES) return out
            }
            return out
        }
    }
}
