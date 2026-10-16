package app.atlas.ui.go

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.guide.Callout
import app.atlas.guide.Cue
import app.atlas.guide.FeatureLookup
import app.atlas.guide.GuideSettings
import app.atlas.guide.NavState
import app.atlas.guide.Navigator
import app.atlas.guide.Simulator
import app.atlas.guide.StepContext
import app.atlas.model.Fix
import app.atlas.model.ManeuverType
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.model.Step
import app.atlas.route.RouteResult
import app.atlas.route.Router
import app.atlas.services.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** What the guidance screens and the notification show, after each fix. */
data class NavView(
    val route: Route,
    /** Where things stand; null before the first fix. */
    val state: NavState? = null,
    /** The next maneuver in words: "Turn right onto Raadhuisstraat". */
    val instruction: String? = null,
    /** The next maneuver's street (or its road number), for the street sign. */
    val street: String? = null,
    /** The maneuver after the next, when it follows closely ("then left"). */
    val then: Step? = null,
    /** The warnings still ahead within 10 km, nearest first, with their distance. */
    val upcoming: List<Pair<Callout, Double>> = emptyList(),
    val rerouting: Boolean = false,
    /** Why the last new route could not be found, while off the route. */
    val rerouteFailed: String? = null,
    val arrived: Boolean = false,
    /** The driver's hours, for trucks. */
    val tacho: TachoState? = null,
    val fixes: Int = 0,
) {
    /** Metres of the route already behind. */
    val travelled: Double get() = state?.along ?: 0.0
}

/**
 * One trip being guided: the `:guide` [Navigator] over the route, fed every fix. It says, plays and vibrates what the
 * guide asks through [voice]; when the traveller leaves the route it asks [router] for a new one from where they are,
 * through the stops not yet reached, and follows that; it counts a truck driver's hours ([tachoStart]). [view] has what
 * to show. The session runs in [scope] (the navigation service's, so it survives the screen going off).
 */
class NavSession(
    val request: RouteRequest,
    route: Route,
    val destinationName: String?,
    private val router: Router,
    private val voice: Voice,
    val settings: GuideSettings,
    features: FeatureLookup?,
    private val scope: CoroutineScope,
    /** Driven by the simulator rather than the phone's location. */
    val demo: Boolean,
    tachoStart: TachoState? = null,
    /** Called with the driver's hours whenever they change by a minute, to keep them. */
    private val onTacho: (TachoState) -> Unit = {},
) {
    val destination: LatLon = request.waypoints.last()
    val navigator = Navigator(route, settings, features, destination)
    private val viewState = MutableStateFlow(NavView(route, tacho = tachoStart))
    val view: StateFlow<NavView> = viewState.asStateFlow()

    private var feed: Job? = null
    private var rerouteJob: Job? = null
    private var tacho: TachoState? = tachoStart
    private var tachoSavedAt = 0L
    private var fixCount = 0

    /** True once [stop] was called or the destination reached and the feed ended. */
    @Volatile
    var finished = false
        private set

    /** Follows [fixes] until they end or the session stops. */
    fun start(fixes: Flow<Fix>) {
        feed?.cancel()
        feed = scope.launch {
            try {
                fixes.collect { onFix(it) }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    /** One fix: guidance, cues and the view. Fixes arrive one at a time, in order. */
    @Synchronized
    fun onFix(fix: Fix) {
        if (finished) return
        val g = navigator.update(fix)
        fixCount++
        for (cue in g.cues) {
            when (cue) {
                is Cue.Reroute -> reroute(cue.from)
                is Cue.Arrived -> Unit
                else -> voice.play(cue)
            }
        }
        tacho?.let { t ->
            val next = Tachograph.advance(t, fix.time, (fix.speed ?: g.state.speed) >= Tachograph.MOVING)
            tacho = next
            val minute = (next.drivenToday + next.stoppedFor) / 60
            if (minute != tachoSavedAt) {
                tachoSavedAt = minute
                onTacho(next)
            }
        }
        publish(g.state)
    }

    private fun publish(s: NavState) {
        val book = navigator.phrasebook
        val next = s.next
        val words = next?.let { book.instruction(it, StepContext(arrivalSide = s.arrivalSide)).text }
        // The step after the next, when it comes right after it ("then left").
        val then = s.following?.takeIf { next != null && next.distance < THEN_WITHIN_M }
        val upcoming = navigator.upcoming()
        viewState.update {
            it.copy(
                route = navigator.route,
                state = s,
                instruction = words,
                street = next?.let { n -> n.street ?: n.ref },
                then = then,
                upcoming = upcoming,
                arrived = s.arrived,
                tacho = tacho,
                fixes = fixCount,
            )
        }
    }

    /** Finds a new route from [from] through the stops not yet passed, unless one is being found already. */
    private fun reroute(from: Fix) {
        if (rerouteJob?.isActive == true) return
        viewState.update { it.copy(rerouting = true) }
        rerouteJob = scope.launch {
            val via = remainingVias()
            val req = request.copy(waypoints = listOf(from.location) + via + destination, startHeading = from.bearing, alternatives = 0)
            val result = try {
                router.route(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RouteResult.Error(e.message ?: e.toString(), e)
            }
            when (result) {
                is RouteResult.Found -> {
                    synchronized(this@NavSession) { navigator.replaceRoute(result.best, destination) }
                    viewState.update { it.copy(route = result.best, rerouting = false, rerouteFailed = null) }
                }
                is RouteResult.Failure -> viewState.update { it.copy(rerouting = false, rerouteFailed = result.message) }
            }
        }
    }

    /** The stops on the way not yet reached: those whose WAYPOINT maneuver is still ahead. */
    private fun remainingVias(): List<LatLon> {
        val vias = request.waypoints.drop(1).dropLast(1)
        if (vias.isEmpty()) return emptyList()
        val s = navigator.state ?: return vias
        val passed = navigator.route.steps.take(s.stepIndex + 1).count { it.maneuver.type == ManeuverType.WAYPOINT }
        // The route may have been replaced already: count the stops near the part behind as passed too.
        return vias.drop(passed).filter { v -> Geo.distance(v, s.location) > ARRIVED_M || passed == 0 }
    }

    /** Says the next instruction again, with its distance refreshed. */
    fun repeat() {
        val cues = synchronized(this) { navigator.repeat() }
        if (cues.isEmpty()) voice.repeat() else cues.forEach(voice::play)
    }

    /** Says the distance and time to go and the arrival time. */
    fun summary() {
        synchronized(this) { navigator.summary() }.forEach(voice::play)
    }

    fun stop() {
        finished = true
        feed?.cancel()
        rerouteJob?.cancel()
        voice.stop()
    }

    companion object {
        /** "Then …" shows when the next maneuver is closer than this to the one after it. */
        const val THEN_WITHIN_M = 150.0
        private const val ARRIVED_M = 25.0

        /**
         * The simulator's fixes along [route] from [start], one every [interval] seconds of trip time, sent every
         * [interval] / [speedup] seconds of real time: what demo mode feeds a session.
         */
        fun demoFixes(route: Route, start: Instant, interval: Double = 1.0, speedup: Double = 1.0): Flow<Fix> = flow {
            val sim = Simulator(route, interval = interval, start = start, noise = 0.0)
            for (f in sim.fixes()) {
                emit(f)
                delay((interval * 1000 / speedup).toLong().coerceAtLeast(16))
            }
        }
    }
}
