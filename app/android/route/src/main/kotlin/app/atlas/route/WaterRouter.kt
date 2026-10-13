package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RouteRequest

/**
 * Routes boats (BOAT, SAIL) along rivers, canals, fairways and locks with the Atlas boat profile. Start and end points away
 * from the network join it with straight lines (BRouter's beeline), and legs with no useful waterway connection (open water
 * between two points at sea, or a detour to a far-away fairway that is longer than going straight) become straight segments;
 * both are reported as [HazardKind.OPEN_WATER] hazards, since nothing checks depth or land there.
 */
class WaterRouter(private val brouter: BRouterRouter) : Router {

    override suspend fun route(req: RouteRequest): RouteResult {
        if (!req.mode.water) return RouteResult.Unsupported(req.mode, "the water router only takes boats")
        if (req.waypoints.size < 2) return RouteResult.Invalid("need at least two waypoints")
        val started = System.currentTimeMillis()
        val since = brouter.engine.cancelEpoch
        // first try: the whole trip on the network
        when (val whole = brouter.trace(req)) {
            is BRouterRouter.Traced.Ok -> if (!mostlyOpenWater(whole.routes.first().route)) {
                return RouteResult.Found(whole.routes.map { it.route }, whole.millis)
            }
            is BRouterRouter.Traced.Failed -> if (whole.failure !is RouteResult.NoRoute) return whole.failure
        }
        // one leg, and the network does not serve it: that answer is already in hand
        if (req.waypoints.size == 2) {
            return RouteResult.Found(listOf(straight(req.waypoints, req.mode, req.vehicle.maxSpeedKmh)), System.currentTimeMillis() - started)
        }
        // leg by leg: each leg the network does not really serve becomes a straight line
        val direct = BooleanArray(req.waypoints.size - 1)
        for (i in 0 until req.waypoints.size - 1) {
            val leg = req.copy(waypoints = listOf(req.waypoints[i], req.waypoints[i + 1]), alternatives = 0)
            when (val t = brouter.trace(leg)) {
                is BRouterRouter.Traced.Ok -> direct[i] = mostlyOpenWater(t.routes.first().route)
                is BRouterRouter.Traced.Failed -> when (t.failure) {
                    is RouteResult.NoRoute -> direct[i] = true
                    else -> return t.failure
                }
            }
        }
        if (brouter.engine.cancelEpoch != since) return RouteResult.Cancelled
        if (direct.all { it }) {
            return RouteResult.Found(listOf(straight(req.waypoints, req.mode, req.vehicle.maxSpeedKmh)), System.currentTimeMillis() - started)
        }
        val waypoints = req.waypoints.mapIndexed { i, p -> EngineWaypoint(p, direct = i < direct.size && direct[i]) }
        return when (val t = brouter.trace(req.copy(alternatives = 0), waypoints = waypoints)) {
            is BRouterRouter.Traced.Ok -> RouteResult.Found(t.routes.map { it.route }, System.currentTimeMillis() - started)
            is BRouterRouter.Traced.Failed -> {
                if (t.failure !is RouteResult.NoRoute) return t.failure
                // nothing of the network is usable: straight lines all the way
                RouteResult.Found(listOf(straight(req.waypoints, req.mode, req.vehicle.maxSpeedKmh)), System.currentTimeMillis() - started)
            }
        }
    }

    /** More than half of the route is straight lines over open water: the waterways are not what carries it. */
    private fun mostlyOpenWater(route: Route): Boolean =
        route.hazards.filter { it.kind == HazardKind.OPEN_WATER }.sumOf { it.value ?: 0.0 } > route.distance * MAX_OPEN_WATER_SHARE

    private fun straight(points: List<LatLon>, mode: Mode, speedKmh: Double?): Route {
        val speed = speedKmh ?: if (mode == Mode.SAIL) SAIL_KMH else BOAT_KMH
        var raw: RawTrack? = null
        for (i in 0 until points.size - 1) {
            val leg = RawTrack.straight(points[i], points[i + 1], speed, "atlas=open_water", "atlas:open_water")
            raw = raw?.append(leg) ?: leg
        }
        return TrackConverter().convert(raw!!, mode)
    }

    companion object {
        const val BOAT_KMH = 12.0
        const val SAIL_KMH = 8.0
        private const val MAX_OPEN_WATER_SHARE = 0.5
    }
}
