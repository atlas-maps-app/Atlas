package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Hazard
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** A converted route together with the raw track it came from (energy per point, tags), for planners that need more. */
data class TracedRoute(val route: Route, val raw: RawTrack)

/**
 * Offline routing with BRouter, in process. Every [Mode] but transit maps to an Atlas profile (see [Profiles]); preferences
 * and the vehicle become key values (see [ProfileOptions]).
 *
 * Trucks, campers and vehicles with given dimensions are checked against [restrictions] after routing: ways whose limits the
 * vehicle breaks become nogo lines and the route is computed again, up to [MAX_VALIDATION_PASSES] times; what was avoided
 * is reported as hazards with tags["avoided"] = "yes".
 *
 * @param segmentDir the folder of `.rd5` segment files (see [SegmentStore])
 * @param profileDir where `lookups.dat` and the generated profiles are written
 * @param memoryClassMb BRouter's memory class (the app passes ActivityManager.getMemoryClass())
 */
class BRouterRouter(
    segmentDir: File,
    profileDir: File,
    memoryClassMb: Int = 64,
    maxRunningMillis: Long = 60_000,
    private val restrictions: RestrictionSource? = null,
    namer: WayNamer? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val alternativeParallelism: Int = 2,
    lookups: File? = null,
) : Router {
    val engine = BRouterEngine(segmentDir, memoryClassMb, maxRunningMillis, dispatcher)
    val profiles = ProfileStore(profileDir, lookups)
    private val converter = TrackConverter(namer)

    /**
     * Stops every search in progress, also one waiting for its restrictions between two engine runs;
     * their callers get [RouteResult.Cancelled]. Searches started afterwards are not affected.
     */
    fun cancelAll() = engine.cancelAll()

    override suspend fun route(req: RouteRequest): RouteResult = route(req, emptyList())

    /** Routes avoiding [nogos] as well. */
    suspend fun route(req: RouteRequest, nogos: List<Nogo>): RouteResult = when (val t = trace(req, nogos)) {
        is Traced.Ok -> RouteResult.Found(t.routes.map { it.route }, t.millis)
        is Traced.Failed -> t.failure
    }

    /** What [trace] returns: routes with their raw tracks, best first, or a failure. */
    sealed interface Traced {
        data class Ok(val routes: List<TracedRoute>, val millis: Long) : Traced
        data class Failed(val failure: RouteResult.Failure) : Traced
    }

    /**
     * Routes [req] and keeps the raw tracks. [waypoints] replaces the request's points when given (to mark straight legs).
     * The work, conversion and restriction checks included, runs on the router's dispatcher, not the caller's thread.
     */
    suspend fun trace(req: RouteRequest, nogos: List<Nogo> = emptyList(), waypoints: List<EngineWaypoint>? = null): Traced {
        val since = engine.cancelEpoch
        return withContext(dispatcher) { traceHere(req, nogos, waypoints, since) }
    }

    private suspend fun traceHere(req: RouteRequest, nogos: List<Nogo>, waypoints: List<EngineWaypoint>?, since: Long): Traced {
        val started = System.currentTimeMillis()
        val spec = Profiles.forMode(req.mode) ?: return Traced.Failed(RouteResult.Unsupported(req.mode))
        val points = waypoints ?: req.waypoints.map { EngineWaypoint(it) }
        if (points.size < 2) return Traced.Failed(RouteResult.Invalid("need at least two waypoints, got ${points.size}"))
        val profile = try {
            profiles.file(spec)
        } catch (e: Exception) {
            return Traced.Failed(RouteResult.Error("cannot install profiles: ${e.message}", e))
        }
        val kv = ProfileOptions.keyValues(req.mode, req.prefs, req.vehicle)

        val best = when (val b = bestValidated(req, profile, kv, points, nogos, since)) {
            is Validated.Failed -> return Traced.Failed(withMissingTiles(b.failure, points))
            is Validated.Ok -> b
        }
        val routes = ArrayList<TracedRoute>()
        routes += best.route
        val wanted = req.alternatives.coerceIn(0, 3)
        if (wanted > 0) {
            val gate = Semaphore(alternativeParallelism.coerceAtLeast(1))
            val alternatives = coroutineScope {
                (1..wanted).map { idx ->
                    async {
                        gate.withPermit {
                            when (val o = engine.run(profile, kv, points, best.nogos, idx, req.startHeading, since)) {
                                is EngineOutcome.Ok -> o.track
                                is EngineOutcome.Failed -> null
                            }
                        }
                    }
                }.awaitAll()
            }
            if (engine.cancelEpoch != since) return Traced.Failed(RouteResult.Cancelled)
            for (raw in alternatives) {
                if (raw == null) continue
                val checked = if (best.checked) checkAlternative(req, raw) else emptyList()
                if (checked == null) continue // it breaks a limit: not offered
                val route = converter.convert(raw, req.mode, checked)
                if (routes.none { Alternatives.similarity(route.points, it.route.points) > MAX_OVERLAP }) {
                    routes += TracedRoute(route, raw)
                }
            }
        }
        if (engine.cancelEpoch != since) return Traced.Failed(RouteResult.Cancelled)
        val ends = points.map { it.point }
        return Traced.Ok(routes.map { it.copy(route = withOffNetwork(it.route, ends.first(), ends.last())) }, System.currentTimeMillis() - started)
    }

    /** Ends the engine had to move far to reach a usable way (a start in a field, a point at sea) are worth a warning. */
    private fun withOffNetwork(route: Route, from: LatLon, to: LatLon): Route {
        val extra = ArrayList<Hazard>(2)
        val start = Geo.distance(from, route.points.first())
        if (start > OFF_NETWORK_METRES) extra += Hazard(HazardKind.OFF_NETWORK, 0, from, start, null, mapOf("end" to "start"))
        val last = route.points.size - 1
        val end = Geo.distance(to, route.points[last])
        if (end > OFF_NETWORK_METRES) extra += Hazard(HazardKind.OFF_NETWORK, last, to, end, null, mapOf("end" to "destination"))
        return if (extra.isEmpty()) route else route.copy(hazards = (route.hazards + extra).sortedBy { it.pointIndex })
    }

    private sealed interface Validated {
        data class Ok(val route: TracedRoute, val nogos: List<Nogo>, val checked: Boolean) : Validated
        data class Failed(val failure: RouteResult.Failure) : Validated
    }

    /** The best route, re-routed around the vehicle's conflicts when a restriction source is set. */
    private suspend fun bestValidated(
        req: RouteRequest,
        profile: File,
        kv: Map<String, String>,
        points: List<EngineWaypoint>,
        extraNogos: List<Nogo>,
        since: Long,
    ): Validated {
        val first = when (val o = engine.run(profile, kv, points, extraNogos, 0, req.startHeading, since)) {
            is EngineOutcome.Failed -> return Validated.Failed(o.failure)
            is EngineOutcome.Ok -> o.track
        }
        val source = restrictions
        if (source == null || !RestrictionRules.applies(req.mode, req.vehicle)) {
            return Validated.Ok(TracedRoute(converter.convert(first, req.mode), first), extraNogos, false)
        }
        val nogos = ArrayList(extraNogos)
        val avoided = LinkedHashMap<String, Conflict>()
        var raw = first
        var pass = 0
        while (true) {
            val found = try {
                fetch(source, raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val route = converter.convert(raw, req.mode, listOf(Hazard(HazardKind.UNCHECKED, 0, raw.point(0), text = e.message)))
                return Validated.Ok(TracedRoute(route, raw), nogos, false)
            }
            // stopped while the restrictions were fetched
            if (engine.cancelEpoch != since) return Validated.Failed(RouteResult.Cancelled)
            val matcher = RouteMatcher(raw.points())
            val onRoute = found.mapNotNull { r -> if (RestrictionRules.relevant(r, req.mode)) matcher.touch(r)?.let { r to it } else null }
            val conflicts = onRoute.mapNotNull { (r, touch) ->
                val nearEnds = touch.along < NEAR_ENDS || touch.along > matcher.length - NEAR_ENDS
                RestrictionRules.conflict(r, req.mode, req.vehicle, nearEnds)?.let { it to touch }
            }
            if (conflicts.isEmpty() || pass >= MAX_VALIDATION_PASSES) {
                val hazards = restrictionHazards(raw, matcher, onRoute, conflicts.map { it.first }, avoided.values)
                return Validated.Ok(TracedRoute(converter.convert(raw, req.mode, hazards), raw), nogos, true)
            }
            val added = ArrayList<Nogo>()
            for ((c, touch) in conflicts) {
                avoided[key(c.restriction)] = c
                added += nogosFor(c.restriction, touch)
            }
            nogos += added
            pass++
            raw = when (val o = engine.run(profile, kv, points, nogos, 0, req.startHeading, since)) {
                is EngineOutcome.Ok -> o.track
                is EngineOutcome.Failed -> {
                    if (o.failure is RouteResult.Cancelled) return Validated.Failed(o.failure)
                    // no way round: keep the last route and say what it breaks
                    for ((c, _) in conflicts) avoided.remove(key(c.restriction))
                    val hazards = restrictionHazards(raw, matcher, onRoute, conflicts.map { it.first }, avoided.values)
                    return Validated.Ok(TracedRoute(converter.convert(raw, req.mode, hazards), raw), nogos.dropLast(added.size), true)
                }
            }
        }
    }

    /**
     * Lines that close a restriction to the router: across the route where it meets the restriction, and, for a way the
     * route runs along, across the middle of the way's pieces too, so the next route cannot use another part of it.
     */
    private fun nogosFor(r: Restriction, touch: RouteMatcher.Touch): List<Nogo> {
        val out = arrayListOf<Nogo>(Nogo.across(touch.point, touch.bearing))
        if (r.isPoint || r.crossing) return out
        val g = r.geometry
        val pieces = (0 until g.size - 1).filter { Geo.distance(g[it], g[it + 1]) >= 1.0 }
        val picked = if (pieces.size <= MAX_NOGOS_PER_WAY) pieces else List(MAX_NOGOS_PER_WAY) { pieces[it * pieces.size / MAX_NOGOS_PER_WAY] }
        for (i in picked) out += Nogo.across(Geo.interpolate(g[i], g[i + 1], 0.5), Geo.bearing(g[i], g[i + 1]))
        return out
    }

    /** Null when the alternative breaks a limit, else the restriction hazards along it. */
    private suspend fun checkAlternative(req: RouteRequest, raw: RawTrack): List<Hazard>? {
        val source = restrictions ?: return emptyList()
        val found = try {
            fetch(source, raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val matcher = RouteMatcher(raw.points())
        val onRoute = found.mapNotNull { r -> if (RestrictionRules.relevant(r, req.mode)) matcher.touch(r)?.let { r to it } else null }
        for ((r, touch) in onRoute) {
            val nearEnds = touch.along < NEAR_ENDS || touch.along > matcher.length - NEAR_ENDS
            if (RestrictionRules.conflict(r, req.mode, req.vehicle, nearEnds) != null) return null
        }
        return restrictionHazards(raw, matcher, onRoute, emptyList(), emptyList())
    }

    private suspend fun fetch(source: RestrictionSource, raw: RawTrack): List<Restriction> =
        source.restrictionsAlong(Corridor(raw.points(), CORRIDOR_METRES))

    private fun key(r: Restriction) = r.id?.toString() ?: r.geometry.first().toString()

    /**
     * Hazards for restrictions: those on the route (limits the vehicle meets, with tags["violated"] = "yes" when it breaks them)
     * and those it was routed around (tags["avoided"] = "yes", at the nearest route point).
     */
    private fun restrictionHazards(
        raw: RawTrack,
        matcher: RouteMatcher,
        onRoute: List<Pair<Restriction, RouteMatcher.Touch>>,
        violated: List<Conflict>,
        avoided: Collection<Conflict>,
    ): List<Hazard> {
        val out = ArrayList<Hazard>()
        val violatedKeys = violated.map { key(it.restriction) }.toSet()
        for ((r, touch) in onRoute) {
            val idx = matcher.pointIndex(touch.along)
            val c = violated.firstOrNull { key(it.restriction) == key(r) }
            val kind = c?.kind ?: RestrictionRules.kindOf(r)
            val tags = HashMap<String, String>()
            r.id?.let { tags["osm"] = it.toString() }
            for ((k, v) in r.tags) if (k in DESCRIPTIVE_TAGS || k.contains("vhf") || k.endsWith(":channel")) tags[k] = v
            if (key(r) in violatedKeys) tags["violated"] = "yes"
            c?.actual?.let { tags["vehicle"] = it.toString() }
            r.hgv?.let { tags["hgv"] = it }
            out += Hazard(kind, idx, raw.point(idx), c?.limit ?: RestrictionRules.limitOf(r), null, tags)
        }
        if (avoided.isNotEmpty()) {
            val points = raw.points()
            for (c in avoided) {
                if (key(c.restriction) in violatedKeys) continue
                val at = c.restriction.geometry.first()
                var bestI = 0
                var bestD = Double.MAX_VALUE
                for ((i, p) in points.withIndex()) {
                    val d = Geo.distance(p, at)
                    if (d < bestD) {
                        bestD = d; bestI = i
                    }
                }
                val tags = HashMap<String, String>()
                c.restriction.id?.let { tags["osm"] = it.toString() }
                tags["avoided"] = "yes"
                tags["distance"] = bestD.toInt().toString()
                c.actual?.let { tags["vehicle"] = it.toString() }
                out += Hazard(c.kind, bestI, at, c.limit, null, tags)
            }
        }
        return out
    }

    /** A route that failed for want of data along the way says which tiles are missing. */
    private fun withMissingTiles(failure: RouteResult.Failure, points: List<EngineWaypoint>): RouteResult.Failure {
        if (failure !is RouteResult.NoRoute) return failure
        val missing = SegmentStore.tilesForCorridor(points.map { it.point }, CORRIDOR_TILE_METRES)
            .filter { !File(engine.segmentDir, it).isFile }
        return if (missing.isEmpty()) failure else RouteResult.NoData(missing, "no route; routing data is missing for ${missing.joinToString()}")
    }

    companion object {
        /** Re-routes around broken limits after the first route. */
        const val MAX_VALIDATION_PASSES = 3
        /** Alternatives sharing more than this with a better route are dropped. */
        const val MAX_OVERLAP = 0.8
        private const val CORRIDOR_METRES = 25.0
        private const val MAX_NOGOS_PER_WAY = 12
        private const val CORRIDOR_TILE_METRES = 20_000.0
        /** hgv=destination and delivery are fine this close to the start or the destination. */
        private const val NEAR_ENDS = 3_000.0
        /** A route end further than this from the asked point gets an [HazardKind.OFF_NETWORK] hazard. */
        const val OFF_NETWORK_METRES = 300.0
        /** Tags of a restriction worth passing on with its hazard (what the guide can say about a bridge or a lock). */
        private val DESCRIPTIVE_TAGS = setOf("name", "ref", "opening_hours", "operator", "bridge:movable", "seamark:bridge:category")
    }
}
