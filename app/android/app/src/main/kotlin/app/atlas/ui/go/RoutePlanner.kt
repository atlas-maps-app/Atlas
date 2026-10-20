package app.atlas.ui.go

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RoutePrefs
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import app.atlas.route.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime

/** One end or stop of a trip: a name and a point; [here] is "where you are", found again when planning. */
data class Endpoint(val name: String?, val at: LatLon, val here: Boolean = false)

/** When the trip happens: leave now, leave at [at], or be there by [at]. */
data class Timing(val kind: Kind = Kind.NOW, val at: LocalDateTime? = null) {
    enum class Kind { NOW, DEPART, ARRIVE }
}

/** What the planner knows about one mode's way there. */
sealed interface Plan {
    /** No destination yet. */
    data object Waiting : Plan

    data object Loading : Plan

    /** Routes, best first, then the alternatives; [fact] is the row's key fact. */
    data class Found(val routes: List<Route>, val fact: KeyFact?) : Plan {
        val best: Route get() = routes.first()
    }

    /** The phone lacks the routing data for the area: [needs] says which segments and how big. */
    data class NeedsData(val needs: List<SegmentNeed>) : Plan

    /** No way for this mode ([kind]), with the router's words. */
    data class Failed(val kind: Kind, val message: String) : Plan {
        enum class Kind { NO_ROUTE, UNSUPPORTED, TIMEOUT, ERROR }
    }
}

/** A way to compare on the planner: the fastest, or one that favours quiet, flat or toll-free roads. */
enum class WayKind { FASTEST, QUIET, FLAT, NO_MOTORWAYS, NO_TOLLS }

/** One compared way and its route. */
data class WayOption(val kind: WayKind, val route: Route)

/** The planner's whole state. */
data class RouteUi(
    val from: Endpoint,
    val to: Endpoint?,
    val stops: List<Endpoint> = emptyList(),
    val mode: Mode = Mode.WALK,
    val timing: Timing = Timing(),
    val prefs: RoutePrefs = RoutePrefs(),
    val vehicles: Map<Mode, Vehicle> = emptyMap(),
    val plans: Map<Mode, Plan> = emptyMap(),
    /** Which of the selected mode's routes is chosen (0 the best). */
    val chosen: Int = 0,
    /** The selected mode's ways compared; empty until planned. */
    val ways: List<WayOption> = emptyList(),
    /** Segment downloads in progress (0..1) by tile. */
    val downloading: Map<String, Float> = emptyMap(),
    val downloadError: String? = null,
    /** True when the routes come from the demo fixtures (no data on the phone, demo mode). */
    val demo: Boolean = false,
) {
    val plan: Plan get() = plans[mode] ?: if (to == null) Plan.Waiting else Plan.Loading

    /** The route to show and to go by: the chosen one of the selected mode. */
    val route: Route? get() = (plan as? Plan.Found)?.routes?.let { it.getOrNull(chosen) ?: it.first() }

    /** The modes in the rows, in order: the selected one first, then the compared ones. */
    val rowModes: List<Mode> get() = (listOf(mode) + GoMode.compared.map { it.mode }).distinct()

    fun vehicle(m: Mode): Vehicle = vehicles[m] ?: GoStore.defaultVehicle(m)
}

/**
 * The route planner of the Route board: from (here by default), to, stops on the way; the mode, when, and the
 * preferences; then the routes of the selected mode (with alternatives and its ways compared) and the other modes'
 * rows. Planning runs offline through the app's router as soon as anything changes; data missing for the area turns
 * into an offer to download the routing segment.
 */
class RouteViewModel(private val services: Services, from: Endpoint, to: Endpoint?, mode: Mode?, stops: List<Endpoint> = emptyList()) : ViewModel() {
    private val hub = services.go
    private val state = MutableStateFlow(RouteUi(from = from, to = to, stops = stops, mode = mode ?: Mode.WALK))
    val ui: StateFlow<RouteUi> = state.asStateFlow()
    private var planning: Job? = null
    private val gate = Semaphore(2)

    init {
        viewModelScope.launch {
            val prefs = hub.store.prefs.first()
            val vehicles = hub.store.vehicles.first()
            val m = mode ?: hub.store.mode.first()
            state.update { it.copy(prefs = prefs, vehicles = vehicles, mode = m) }
            replan(now = true)
        }
    }

    fun setMode(m: Mode) {
        if (m == state.value.mode) return
        state.update { it.copy(mode = m, chosen = 0, ways = emptyList()) }
        viewModelScope.launch { hub.store.setMode(m) }
        replan()
    }

    fun swap() {
        val s = state.value
        val to = s.to ?: return
        state.update { it.copy(from = to, to = s.from, stops = s.stops.reversed(), chosen = 0) }
        replan()
    }

    fun setTo(e: Endpoint) {
        state.update { it.copy(to = e, chosen = 0) }
        replan()
    }

    fun addStop(e: Endpoint) {
        state.update { it.copy(stops = it.stops + e, chosen = 0) }
        replan()
    }

    fun removeStop(i: Int) {
        state.update { s -> s.copy(stops = s.stops.filterIndexed { k, _ -> k != i }, chosen = 0) }
        replan()
    }

    /** Moves stop [i] one place earlier ([by] −1) or later (+1). */
    fun moveStop(i: Int, by: Int) {
        state.update { s ->
            val j = i + by
            if (i !in s.stops.indices || j !in s.stops.indices) return@update s
            val list = s.stops.toMutableList()
            list[i] = s.stops[j]
            list[j] = s.stops[i]
            s.copy(stops = list, chosen = 0)
        }
        replan()
    }

    fun setTiming(t: Timing) {
        state.update { it.copy(timing = t) }
        replan()
    }

    fun setPrefs(p: RoutePrefs) {
        state.update { it.copy(prefs = p, chosen = 0) }
        viewModelScope.launch { hub.store.setPrefs(p) }
        replan()
    }

    fun choose(i: Int) = state.update { it.copy(chosen = i) }

    /** Goes by one of the compared ways: its preferences become yours for this mode. */
    fun pickWay(kind: WayKind) = setPrefs(prefsFor(kind, state.value.prefs))

    /** Plans again (after a download, or when the user asks). */
    fun retry() = replan(now = true)

    private fun replan(now: Boolean = false) {
        planning?.cancel()
        planning = viewModelScope.launch {
            if (!now) delay(DEBOUNCE_MS)
            planAll()
        }
    }

    private suspend fun planAll() {
        val s0 = state.value
        val to = s0.to ?: run {
            state.update { it.copy(plans = emptyMap(), ways = emptyList()) }
            return
        }
        val from = resolve(s0.from)
        val points = listOf(from) + s0.stops.map { it.at } + to.at
        val modes = s0.rowModes
        state.update { s -> s.copy(plans = modes.associateWith { Plan.Loading }, ways = emptyList(), demo = false) }
        val demo = services.location.isDemo
        coroutineScope {
            modes.map { m ->
                async {
                    val p = gate.withPermit { planOne(m, points, if (m == s0.mode) ALTERNATIVES else 0, s0, demo) }
                    state.update { s -> s.copy(plans = s.plans + (m to p.first), demo = s.demo || p.second) }
                }
            }.awaitAll()
        }
        // The selected mode's ways side by side, once its own route is in.
        val best = (state.value.plans[s0.mode] as? Plan.Found)?.best ?: return
        val kinds = waysFor(s0.mode)
        val ways = coroutineScope {
            kinds.map { k ->
                async {
                    if (k == WayKind.FASTEST && s0.prefs == prefsFor(WayKind.FASTEST, s0.prefs)) return@async WayOption(k, best)
                    val req = request(s0.mode, points, 0, s0.copy(prefs = prefsFor(k, s0.prefs)))
                    gate.withPermit { (route(req, demo).first as? RouteResult.Found)?.best?.let { WayOption(k, it) } }
                }
            }.awaitAll().filterNotNull()
        }
        state.update { it.copy(ways = ways) }
    }

    /** Plans [mode]; the second value is true when the route is a demo fixture. */
    private suspend fun planOne(mode: Mode, points: List<LatLon>, alternatives: Int, s: RouteUi, demo: Boolean): Pair<Plan, Boolean> {
        val (result, fromDemo) = route(request(mode, points, alternatives, s), demo)
        val plan = when (result) {
            is RouteResult.Found -> {
                val fact = withContext(Dispatchers.IO) { runCatching { RouteFacts.of(mode, result.best, points.last(), services.placeStores) }.getOrNull() }
                Plan.Found(result.routes, fact)
            }
            is RouteResult.NoData -> Plan.NeedsData(result.missingTiles.map { t ->
                val name = t.removeSuffix(".rd5")
                SegmentNeed(name, SegmentOffer.size(services.context, services.segments, name, services.connectivity.online.value))
            })
            is RouteResult.NoRoute -> Plan.Failed(Plan.Failed.Kind.NO_ROUTE, result.message)
            is RouteResult.Unsupported -> Plan.Failed(Plan.Failed.Kind.UNSUPPORTED, result.message)
            is RouteResult.Timeout -> Plan.Failed(Plan.Failed.Kind.TIMEOUT, result.message)
            is RouteResult.Failure -> Plan.Failed(Plan.Failed.Kind.ERROR, result.message)
        }
        return plan to fromDemo
    }

    private suspend fun route(req: RouteRequest, demo: Boolean): Pair<RouteResult, Boolean> {
        val direct = try {
            services.router.route(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RouteResult.Error(e.message ?: e.toString(), e)
        }
        if (direct is RouteResult.Found || !demo || req.waypoints.size != 2) return direct to false
        val fixture = withContext(Dispatchers.IO) { DemoRoutes.find(services.context, req.mode, req.waypoints.first(), req.waypoints.last()) }
        return if (fixture != null) RouteResult.Found(listOf(fixture)) to true else direct to false
    }

    private fun request(mode: Mode, points: List<LatLon>, alternatives: Int, s: RouteUi): RouteRequest {
        // Only a chosen departure is one: for "arrive by" the time is the arrival, and the departure is not known yet.
        val depart = if (s.timing.kind == Timing.Kind.DEPART) s.timing.at?.atZone(services.clock.zone)?.toInstant() else null
        return RouteRequest(mode, points, s.prefs, s.vehicle(mode), departAt = depart, alternatives = alternatives)
    }

    /** Where "here" is now: the last fix, else the point the planner opened with. */
    private fun resolve(e: Endpoint): LatLon = if (e.here) services.location.last.value?.location ?: e.at else e.at

    /** Downloads the routing segments the selected mode lacks, then plans again. */
    fun download() {
        val needs = (state.value.plan as? Plan.NeedsData)?.needs ?: return
        viewModelScope.launch {
            state.update { it.copy(downloadError = null) }
            try {
                for (n in needs) {
                    SegmentOffer.download(services.regions, services.segments, n.tile).collect { f ->
                        state.update { s -> s.copy(downloading = s.downloading + (n.tile to f)) }
                    }
                }
                state.update { it.copy(downloading = emptyMap()) }
                replan(now = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(downloading = emptyMap(), downloadError = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * Keeps the destination as home or work; when the planner says "arrive by", that time comes along, so the Go tab can
     * say when to leave.
     */
    fun saveAs(home: Boolean) {
        val s = state.value
        val to = s.to ?: return
        val by = if (s.timing.kind == Timing.Kind.ARRIVE) s.timing.at?.toLocalTime() else null
        val p = SavedPlace(to.name ?: "", to.at, usedAt = services.clock.millis(), arriveBy = by)
        viewModelScope.launch { if (home) hub.store.setHome(p) else hub.store.setWork(p) }
    }

    /** Adds a stop at [at] (a long press on the map), named after its street when the map's data knows it. */
    fun addStopAt(at: LatLon) {
        viewModelScope.launch {
            val name = runCatching { services.localData.streetNear(at, 60.0) }.getOrNull()
            addStop(Endpoint(name, at))
        }
    }

    /** Sets the destination to [at] (a long press on the map with none chosen yet). */
    fun setToAt(at: LatLon) {
        viewModelScope.launch {
            val name = runCatching { services.localData.streetNear(at, 60.0) }.getOrNull()
            setTo(Endpoint(name, at))
        }
    }

    /**
     * Starts guiding the chosen route from the visible screen ([context]); [demo] drives the simulator. Returns false
     * when there is no route to go by yet.
     */
    suspend fun start(context: Context, demo: Boolean): Boolean {
        val s = state.value
        val route = s.route ?: return false
        val to = s.to ?: return false
        val points = listOf(route.points.first()) + s.stops.map { it.at } + to.at
        val req = request(s.mode, points, 0, s)
        val settings = hub.guideSettings(s.mode, s.vehicle(s.mode))
        hub.startNav(context, req, route, to.name, settings, demo || s.demo)
        return true
    }

    /** When to leave and when you get there by [route], by the planner's timing. */
    fun times(route: Route): Pair<Instant, Instant> {
        val t = state.value.timing
        val now = services.clock.instant()
        val at = t.at?.atZone(services.clock.zone)?.toInstant()
        val secs = (route.duration * 1000).toLong()
        return when (t.kind) {
            Timing.Kind.NOW -> now to now.plusMillis(secs)
            Timing.Kind.DEPART -> (at ?: now) to (at ?: now).plusMillis(secs)
            Timing.Kind.ARRIVE -> (at ?: now).minusMillis(secs) to (at ?: now)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 200L
        private const val ALTERNATIVES = 2

        /** The ways the planner compares for [mode]. */
        fun waysFor(mode: Mode): List<WayKind> = when {
            mode.water -> emptyList()
            mode.motor -> listOf(WayKind.FASTEST, WayKind.NO_MOTORWAYS, WayKind.NO_TOLLS)
            else -> listOf(WayKind.FASTEST, WayKind.QUIET, WayKind.FLAT)
        }

        /** The preferences of [kind], keeping the avoidances of [base] that it does not decide. */
        fun prefsFor(kind: WayKind, base: RoutePrefs): RoutePrefs = when (kind) {
            WayKind.FASTEST -> base.copy(quiet = 0.0, green = 0.0, flat = 0.0, shade = 0.0, avoidMotorways = false, avoidTolls = false, fast = 0.5)
            WayKind.QUIET -> base.copy(quiet = 1.0, green = 0.6, flat = 0.0)
            WayKind.FLAT -> base.copy(flat = 1.0, quiet = 0.0)
            WayKind.NO_MOTORWAYS -> base.copy(avoidMotorways = true)
            WayKind.NO_TOLLS -> base.copy(avoidTolls = true)
        }

        /** The fastest way and the others that really differ from it (less than 95 % of their length shared). */
        fun distinctWays(ways: List<WayOption>): List<WayOption> {
            val fastest = ways.firstOrNull { it.kind == WayKind.FASTEST } ?: return ways
            return ways.filter { it === fastest || app.atlas.route.Alternatives.similarity(it.route.points, fastest.route.points) < 0.95 }
        }

        /** Seconds [way] costs over [fastest]: positive is slower. */
        fun cost(way: Route, fastest: Route): Double = way.duration - fastest.duration

        /** True when [a] and [b] are the same trip's ends (within 30 m). */
        fun sameTrip(a: LatLon, b: LatLon): Boolean = Geo.distance(a, b) < 30.0
    }
}
