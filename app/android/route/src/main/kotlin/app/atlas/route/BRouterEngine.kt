package app.atlas.route

import app.atlas.geo.LatLon
import btools.mapaccess.MatchedWaypoint
import btools.router.AtlasTrackAccess
import btools.router.OsmNodeNamed
import btools.router.OsmNogoPolygon
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** A point the engine routes through; [direct] draws a straight line from here to the next point instead of routing. */
data class EngineWaypoint(val point: LatLon, val direct: Boolean = false)

/** One engine run: a raw track, or why there is none. */
sealed interface EngineOutcome {
    data class Ok(val track: RawTrack, val millis: Long) : EngineOutcome
    data class Failed(val failure: RouteResult.Failure) : EngineOutcome
}

/**
 * Runs BRouter in this process. Each [run] builds a [RoutingContext], runs a [RoutingEngine] on [dispatcher] and copies
 * the result through [AtlasTrackAccess]. Cancelling the calling coroutine terminates the engine.
 */
class BRouterEngine(
    val segmentDir: File,
    private val memoryClassMb: Int = 64,
    private val maxRunningMillis: Long = 60_000,
    private val dispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) {
    /** Running engines, each with a flag saying whether we stopped it (as opposed to it failing on its own). */
    private val running = ConcurrentHashMap<RoutingEngine, AtomicBoolean>()

    /** Counts [cancelAll] calls, so a search between two engine runs (or not yet started) learns it was stopped. */
    private val cancels = AtomicLong()

    /** The number of [cancelAll] calls so far: a search that saw another number when it began has been cancelled. */
    val cancelEpoch: Long get() = cancels.get()

    /** Stops every running search; their callers get [RouteResult.Cancelled]. */
    fun cancelAll() {
        cancels.incrementAndGet()
        for ((engine, stopped) in running) {
            stopped.set(true)
            engine.terminate()
        }
    }

    /**
     * Routes through [waypoints] with [profile]. When BRouter fails on a multi-point request for its own reasons
     * (an internal error, not "no route"), each leg is routed on its own and the legs are joined. This happens in practice:
     * BRouter's elevation repair after joining legs (`RoutingEngine.postElevationCheck`) throws a NullPointerException when
     * a via point lies inside a stretch without elevation data, as on Amsterdam's Dam square.
     * A search whose caller began before a later [cancelAll] ([since] is the [cancelEpoch] it saw) ends as cancelled.
     */
    suspend fun run(
        profile: File,
        keyValues: Map<String, String>,
        waypoints: List<EngineWaypoint>,
        nogos: List<Nogo> = emptyList(),
        alternative: Int = 0,
        startHeading: Double? = null,
        since: Long = cancelEpoch,
    ): EngineOutcome {
        val whole = runOnce(profile, keyValues, waypoints, nogos, alternative, startHeading, since)
        if (whole !is EngineOutcome.Failed || whole.failure !is RouteResult.Error || waypoints.size <= 2) return whole
        var joined: RawTrack? = null
        var millis = 0L
        for (i in 0 until waypoints.size - 1) {
            val leg = listOf(waypoints[i], waypoints[i + 1].copy(direct = false))
            when (val o = runOnce(profile, keyValues, leg, nogos, alternative, if (i == 0) startHeading else null, since)) {
                is EngineOutcome.Failed -> return o
                is EngineOutcome.Ok -> {
                    joined = joined?.append(o.track) ?: o.track
                    millis += o.millis
                }
            }
        }
        return EngineOutcome.Ok(joined!!, millis)
    }

    private suspend fun runOnce(
        profile: File,
        keyValues: Map<String, String>,
        waypoints: List<EngineWaypoint>,
        nogos: List<Nogo>,
        alternative: Int,
        startHeading: Double?,
        since: Long,
    ): EngineOutcome {
        if (waypoints.size < 2) return EngineOutcome.Failed(RouteResult.Invalid("need at least two waypoints"))
        if (cancels.get() != since) return EngineOutcome.Failed(RouteResult.Cancelled)
        val missing = waypoints.map { SegmentStore.tileName(it.point) }.distinct().filter { !File(segmentDir, it).isFile }
        if (missing.isNotEmpty()) return EngineOutcome.Failed(RouteResult.NoData(missing))

        val rc = RoutingContext().apply {
            localFunction = profile.absolutePath
            memoryclass = memoryClassMb.coerceIn(16, 256)
            this.keyValues = withDigest(keyValues)
            turnInstructionMode = TURN_INSTRUCTION_MODE
            alternativeIdx = alternative.coerceIn(0, 3)
            if (nogos.isNotEmpty()) nogopoints = ArrayList(nogos.map(::toBRouter))
            if (startHeading != null) {
                startDirection = startHeading.roundToInt().mod(360)
                forceUseStartDirection = true
            }
        }
        val wps = waypoints.mapIndexed { i, w ->
            OsmNodeNamed().apply {
                name = when (i) {
                    0 -> "from"
                    waypoints.size - 1 -> "to"
                    else -> "via$i"
                }
                ilon = ilon(w.point.lon)
                ilat = ilat(w.point.lat)
                wpttype = if (w.direct && i < waypoints.size - 1) MatchedWaypoint.WAYPOINT_TYPE_DIRECT else MatchedWaypoint.WAYPOINT_TYPE_SHAPING
            }
        }
        val engine = try {
            RoutingEngine(null, null, segmentDir, ArrayList(wps), rc).apply { quite = true }
        } catch (e: RuntimeException) {
            return EngineOutcome.Failed(RouteResult.Error("profile ${profile.name}: ${e.message}", e))
        }
        val stopped = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        running[engine] = stopped
        // a cancelAll() between the check above and the registration would otherwise miss this engine
        if (cancels.get() != since) {
            stopped.set(true)
            engine.terminate()
        }
        try {
            coroutineScope {
                // cancelling the caller stops the engine; a normal finish leaves it alone
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!engine.isFinished) engine.terminate()
                    }
                }
                try {
                    withContext(dispatcher) { engine.doRun(maxRunningMillis) }
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            running.remove(engine)
        }
        currentCoroutineContext().ensureActive()
        val millis = System.currentTimeMillis() - started
        val error = engine.errorMessage
        if (error != null) return EngineOutcome.Failed(classify(error, stopped.get(), millis))
        val track = engine.foundTrack
        if (track == null || track.nodes.size < 2) return EngineOutcome.Failed(RouteResult.NoRoute("no track found"))
        return EngineOutcome.Ok(toRaw(AtlasTrackAccess.read(track), "brouter:" + profile.name.removeSuffix(".brf")), millis)
    }

    private fun classify(message: String, stoppedByUs: Boolean, millis: Long): RouteResult.Failure = when {
        stoppedByUs -> RouteResult.Cancelled
        message.contains("timeout") -> RouteResult.Timeout(millis, message)
        message.startsWith("datafile ") && message.contains("not found") ->
            RouteResult.NoData(listOf(message.removePrefix("datafile ").substringBefore(' ')))
        message.contains("not mapped") -> RouteResult.NoRoute("a point is too far from any way this mode can use ($message)")
        message.contains("island") -> RouteResult.NoRoute("a point is on an island of ways not connected to the rest ($message)")
        message.contains("restricted area") -> RouteResult.NoRoute("a point lies in an avoided area ($message)")
        message.contains("no track found") -> RouteResult.NoRoute(message)
        message.contains("need two lat/lon") || message.contains("one lat/lon") -> RouteResult.Invalid(message)
        else -> RouteResult.Error(message)
    }

    companion object {
        /** BRouter's own style: real exit-left/exit-right hints and the geometry of every junction. */
        const val TURN_INSTRUCTION_MODE = 9

        init {
            // several profiles (alternatives, re-routes, other modes) may be in use at once
            ProfileCache.setSize(4)
        }

        internal fun toBRouter(n: Nogo): OsmNodeNamed {
            val weight = n.weight ?: Double.NaN
            return when (n) {
                is Nogo.Circle -> OsmNodeNamed().apply {
                    ilon = ilon(n.center.lon)
                    ilat = ilat(n.center.lat)
                    radius = n.radiusMetres
                    name = "nogo"
                    isNogo = true
                    nogoWeight = weight
                }
                is Nogo.Line, is Nogo.Area -> {
                    val pts = if (n is Nogo.Line) n.points else (n as Nogo.Area).ring
                    OsmNogoPolygon(n is Nogo.Area).apply {
                        for (p in pts) addVertex(ilon(p.lon), ilat(p.lat))
                        calcBoundingCircle()
                        name = "nogo"
                        nogoWeight = weight
                    }
                }
            }
        }

        /** The key value that carries [withDigest]'s digest. */
        internal const val DIGEST_KEY = "atlas_options_digest"

        /**
         * [keyValues] plus a digest of them all. BRouter's profile cache tells parsed profiles apart by the file's date and the
         * *sum* of the keys' and values' hash codes, so two requests that only swap values (avoid_toll=1 avoid_motorways=0, then
         * avoid_toll=0 avoid_motorways=1) would share one parsed profile and the second would get the first one's route.
         * The digest is a number, as every key value must be, and changes that sum whenever the set changes.
         */
        internal fun withDigest(keyValues: Map<String, String>): HashMap<String, String> {
            val out = HashMap<String, String>(keyValues.size + 2)
            out.putAll(keyValues)
            out.remove(DIGEST_KEY)
            var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
            for (k in out.keys.sorted()) {
                for (c in k) h = (h xor c.code.toLong()) * 0x100000001b3L
                h = (h xor '='.code.toLong()) * 0x100000001b3L
                for (c in out.getValue(k)) h = (h xor c.code.toLong()) * 0x100000001b3L
                h = (h xor ';'.code.toLong()) * 0x100000001b3L
            }
            out[DIGEST_KEY] = ((h xor (h ushr 32)) and 0x7fffffff).toString()
            return out
        }

        internal fun ilon(lon: Double) = ((lon + 180.0) * 1e6 + 0.5).toInt()
        internal fun ilat(lat: Double) = ((lat + 90.0) * 1e6 + 0.5).toInt()

        /** Copies BRouter's track into a [RawTrack]. */
        internal fun toRaw(t: AtlasTrackAccess.Track, source: String): RawTrack {
            val n = t.size
            val lat = DoubleArray(n) { t.ilat[it] / 1e6 - 90.0 }
            val lon = DoubleArray(n) { t.ilon[it] / 1e6 - 180.0 }
            val ele = DoubleArray(n) { if (t.selev[it] == Short.MIN_VALUE) Double.NaN else t.selev[it] / 4.0 }
            val time = DoubleArray(n) { t.time[it].toDouble() }
            val energy = DoubleArray(n) { t.energy[it].toDouble() }
            val hints = t.hints.mapNotNull { h ->
                val cmd = command(h.command) ?: return@mapNotNull null
                RawHint(
                    index = h.index, command = cmd, exit = h.exit, angle = h.angle, giveWay = h.giveWay,
                    priority = h.priority, previousPriority = h.previousPriority, link = h.link, previousLink = h.previousLink,
                )
            }.sortedBy { it.index }
            return RawTrack(
                lat, lon, ele, time, energy, t.wayTags.copyOf(), t.nodeTags.copyOf(), hints,
                viaIndices(t.waypointIndices, t.waypointNames, n), t.ascend.toDouble(), source,
            )
        }

        /** The user's via points and the end: "viaN" (or "viaN_add", the real point when BRouter added a straight join). */
        internal fun viaIndices(indices: IntArray, names: Array<String?>, size: Int): IntArray {
            val byVia = sortedMapOf<Int, Int>()
            val add = HashSet<Int>()
            for (k in indices.indices) {
                val name = names.getOrNull(k) ?: continue
                val m = Regex("""^via(\d+)(_add)?$""").find(name) ?: continue
                val via = m.groupValues[1].toInt()
                val isAdd = m.groupValues[2].isNotEmpty()
                if (via !in byVia || (isAdd && via !in add)) {
                    byVia[via] = indices[k]
                    if (isAdd) add += via
                }
            }
            val out = byVia.values.filter { it in 1 until size - 1 }.toMutableList()
            out += size - 1
            return out.toIntArray()
        }

        internal fun command(c: Int): HintCommand? = when (c) {
            AtlasTrackAccess.C -> HintCommand.CONTINUE
            AtlasTrackAccess.TL -> HintCommand.LEFT
            AtlasTrackAccess.TSLL -> HintCommand.SLIGHT_LEFT
            AtlasTrackAccess.TSHL -> HintCommand.SHARP_LEFT
            AtlasTrackAccess.TR -> HintCommand.RIGHT
            AtlasTrackAccess.TSLR -> HintCommand.SLIGHT_RIGHT
            AtlasTrackAccess.TSHR -> HintCommand.SHARP_RIGHT
            AtlasTrackAccess.KL -> HintCommand.KEEP_LEFT
            AtlasTrackAccess.KR -> HintCommand.KEEP_RIGHT
            AtlasTrackAccess.TLU -> HintCommand.U_TURN_LEFT
            AtlasTrackAccess.TRU -> HintCommand.U_TURN_RIGHT
            AtlasTrackAccess.TU -> HintCommand.U_TURN
            AtlasTrackAccess.OFFR -> HintCommand.OFF_ROUTE
            AtlasTrackAccess.RNDB -> HintCommand.ROUNDABOUT
            AtlasTrackAccess.RNLB -> HintCommand.ROUNDABOUT_LEFT
            AtlasTrackAccess.BL -> HintCommand.BEELINE
            AtlasTrackAccess.EL -> HintCommand.EXIT_LEFT
            AtlasTrackAccess.ER -> HintCommand.EXIT_RIGHT
            AtlasTrackAccess.END -> HintCommand.END
            else -> null
        }
    }
}
