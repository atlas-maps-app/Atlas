package app.atlas.route

import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RouteRequest

/**
 * Finds routes. Implementations work off the caller's thread and honour coroutine cancellation:
 * cancelling the calling coroutine stops the search and the cancellation propagates as usual.
 */
fun interface Router {
    suspend fun route(req: RouteRequest): RouteResult
}

/** What a router found: routes (best first, then alternatives) or a typed reason why not. */
sealed interface RouteResult {

    /** One or more routes, best first. */
    data class Found(val routes: List<Route>, val millis: Long = 0) : RouteResult {
        init {
            require(routes.isNotEmpty()) { "Found needs at least one route" }
        }

        val best: Route get() = routes.first()
        val alternatives: List<Route> get() = routes.drop(1)
    }

    sealed interface Failure : RouteResult {
        val message: String
    }

    /** The offline routing data for the area is missing: [missingTiles] names the segment files to download. */
    data class NoData(val missingTiles: List<String>, override val message: String = "no routing data for ${missingTiles.joinToString()}") : Failure

    /** The data is there but no way connects the points for this mode (an island, a forbidden area, a point far from any way). */
    data class NoRoute(override val message: String) : Failure

    /** The search ran out of time. */
    data class Timeout(val afterMillis: Long, override val message: String = "routing timed out after ${afterMillis / 1000} s") : Failure

    /** The router was stopped from outside before it finished (not by cancelling the caller's coroutine). */
    data object Cancelled : Failure {
        override val message: String get() = "routing cancelled"
    }

    /** No router handles this mode. */
    data class Unsupported(val mode: Mode, override val message: String = "no router for $mode") : Failure

    /** The request itself is wrong, e.g. fewer than two waypoints. */
    data class Invalid(override val message: String) : Failure

    /** The engine failed unexpectedly. */
    data class Error(override val message: String, val cause: Throwable? = null) : Failure
}

/** The routes when found, else null. */
val RouteResult.routes: List<Route>? get() = (this as? RouteResult.Found)?.routes

/**
 * Dispatches by [Mode]: each mode goes to its router; requests with an electric vehicle in a motor mode
 * go to [ev] when one is given (it plans charging stops).
 */
class MultiRouter(
    private val byMode: Map<Mode, Router>,
    private val ev: Router? = null,
) : Router {

    override suspend fun route(req: RouteRequest): RouteResult {
        if (ev != null && req.vehicle.ev != null && req.mode.motor) return ev.route(req)
        val router = byMode[req.mode] ?: return RouteResult.Unsupported(req.mode)
        return router.route(req)
    }

    /** The modes this router can serve. */
    val modes: Set<Mode> get() = byMode.keys

    companion object {
        /** Land modes to [land], water modes to [water]; transit is not routed offline. */
        fun standard(land: Router, water: Router, ev: Router? = null): MultiRouter {
            val map = HashMap<Mode, Router>()
            for (m in Mode.entries) {
                when {
                    m.water -> map[m] = water
                    m == Mode.TRANSIT -> Unit
                    else -> map[m] = land
                }
            }
            return MultiRouter(map, ev)
        }
    }
}
